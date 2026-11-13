package com.example.h10recorder.data

import android.os.SystemClock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** Source of time. Injected so tests can control it. */
interface SessionClock {
    /** Wall-clock time (can jump if the phone syncs its clock). */
    fun nowUtc(): Instant
    /** Monotonic nanoseconds (never jumps). On Android: SystemClock.elapsedRealtimeNanos(). */
    fun monotonicNanos(): Long
}

/** The real phone clock. Not used in JVM tests (SystemClock is Android-only). */
object SystemSessionClock : SessionClock {
    override fun nowUtc(): Instant = Instant.now()
    // Counts time in deep sleep too, unlike System.nanoTime() on Android.
    override fun monotonicNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

/** A point in a session: absolute UTC time and seconds since the session started. */
data class Stamp(val timeUtc: Instant, val elapsedS: Double)

/**
 * Anchors a session to one wall-clock reading taken at start, then derives every later
 * timestamp from the monotonic clock. A phone clock sync mid-session therefore can't make
 * time_utc jump backwards or forwards.
 * The H10 heart-rate stream carries no sensor timestamps, so the phone is the only clock.
 */
class SessionTimeline(private val clock: SessionClock) {
    // Both clocks read once, at construction = session start.
    private val startUtc: Instant = clock.nowUtc()
    private val startMonoNanos: Long = clock.monotonicNanos()

    val start: Stamp = Stamp(startUtc, 0.0)

    fun now(): Stamp {
        val elapsedNanos = clock.monotonicNanos() - startMonoNanos
        return Stamp(startUtc.plusNanos(elapsedNanos), elapsedNanos / 1_000_000_000.0)
    }

    /** UTC time of a moment given as seconds since session start (used for estimated beat times). */
    fun utcAt(elapsedS: Double): Instant = startUtc.plusNanos((elapsedS * 1_000_000_000).roundToLong())
}

/**
 * Estimates when each beat happened from its RR interval. Algorithm and rationale: DATA_NOTES.md.
 *
 * The H10 sends a packet about once a second holding the RR intervals completed since the last
 * packet, and no timestamps. RR intervals come from the sensor's own clock and are exact, so beats
 * are chained: beat_k = offset + (sum of RRs up to beat k).
 *
 * offset = the smallest (arrival time - RR sum) among packets of the last [windowS] seconds: a beat
 * can't happen after the packet that reports it, so the smallest value is the tightest placement.
 * A sliding window (not an all-time minimum) lets the offset follow sensor/phone clock drift and
 * recover from a lost beat within [windowS].
 *
 * If a packet's arrival is more than [gapResetS] later than the chain predicts, that is either a
 * Bluetooth delay or missing beats:
 *  - it arrived on the regular ~1 s packet schedule (gap since the previous packet, with or without
 *    RRs, <= [REGULAR_GAP_S]): nothing was delayed, so beats are missing -> re-anchor now (resynced = true);
 *  - after an irregular gap: could be a delay. Placed on the chain; if the next packet is late too,
 *    beats are missing -> re-anchor then.
 */
class BeatTimeline(
    private val gapResetS: Double = DEFAULT_GAP_RESET_S,
    private val windowS: Double = DEFAULT_WINDOW_S,
) {
    data class Beat(val elapsedS: Double, val rrMs: Double)
    data class Result(val beats: List<Beat>, val resynced: Boolean)

    private class Point(val arrivalS: Double, val candidateS: Double)

    private var rrSumRaw = 0L                     // sum of RRs since anchor, in 1/1024 s (exact integer)
    private val window = ArrayDeque<Point>()      // recent packets, oldest first
    private var offsetS: Double? = null
    private var latePending = false               // previous packet was more than gapResetS late
    private var lastArrivalS: Double? = null      // previous packet of any kind, including ones without RRs
    private var lastBeatS = Double.NEGATIVE_INFINITY

    /** One packet: arrival time (s since session start) and its RRs in 1/1024 s. */
    fun add(receivedElapsedS: Double, rrRaw1024: List<Int>): Result {
        val previousArrival = lastArrivalS
        lastArrivalS = receivedElapsedS
        if (rrRaw1024.isEmpty()) return Result(emptyList(), false)
        val onSchedule = previousArrival != null && receivedElapsedS - previousArrival <= REGULAR_GAP_S
        val sumsRaw = rrRaw1024.runningFold(rrSumRaw) { acc, rr -> acc + rr }.drop(1)
        rrSumRaw = sumsRaw.last()
        val sumsS = sumsRaw.map { it / 1024.0 }
        // the offset that would put this packet's last beat exactly at its arrival time
        val candidate = receivedElapsedS - sumsS.last()

        var resynced = false
        val current = offsetS
        val placeAt: Double = when {
            current == null -> anchor(receivedElapsedS, candidate)                // first packet / after reset()
            candidate - current > gapResetS && (latePending || onSchedule) -> {    // beats missing
                resynced = true
                anchor(receivedElapsedS, candidate)
            }
            candidate - current > gapResetS -> {                                   // late once: assume delay
                latePending = true
                window.addLast(Point(receivedElapsedS, candidate))
                current
            }
            else -> {
                latePending = false
                window.addLast(Point(receivedElapsedS, candidate))
                while (window.first().arrivalS < receivedElapsedS - windowS) window.removeFirst()
                window.minOf { it.candidateS }.also { offsetS = it }
            }
        }
        val beats = sumsS.zip(rrRaw1024).map { (sumS, rr) ->
            val t = maxOf(placeAt + sumS, lastBeatS)     // never earlier than the previous beat
            lastBeatS = t
            Beat(t, TimeUtils.rr1024ToMs(rr))
        }
        return Result(beats, resynced)
    }

    private fun anchor(arrivalS: Double, candidate: Double): Double {
        window.clear()
        window.addLast(Point(arrivalS, candidate))
        offsetS = candidate
        latePending = false
        return candidate
    }

    /** Forget the chain, e.g. after a reconnect. The next packet anchors fresh (not reported as resynced). */
    fun reset() {
        rrSumRaw = 0
        window.clear()
        offsetS = null
        latePending = false
        lastArrivalS = null
        lastBeatS = Double.NEGATIVE_INFINITY
    }

    companion object {
        /** Packets arrive ~1 Hz, so the last beat can be ~1 s older than its packet; 2 s leaves margin. Knob. */
        const val DEFAULT_GAP_RESET_S = 2.0
        /**
         * How far back the offset looks. Longer = tighter offset but slower recovery from a beat the sensor
         * never reported (beats stay ~1 RR early for up to this long). 300 s chosen from the review's
         * simulation: steady-state error p50/p99 0.05/0.11 s at 75 bpm and 0.06/0.19 s at 60 bpm, versus
         * 0.12/0.25 and 0.26/0.87 s for 30 s (at ~60 bpm beats phase-lock to the 1 s packets, so a short
         * window may contain no tight packet). 20 ppm clock drift costs ~6 ms over 300 s. Knob.
         */
        const val DEFAULT_WINDOW_S = 300.0
        /** Packets 0.8-1.25 s apart are "on schedule" (H10 sends ~1 Hz). Used to tell missing beats from delays. */
        const val REGULAR_GAP_S = 1.25
    }
}

object TimeUtils {
    /** ISO-8601 UTC, always millisecond precision, e.g. 2026-11-12T16:20:01.123Z */
    fun formatUtc(instant: Instant): String = UTC_MILLIS.format(instant)

    /**
     * Seconds with exactly 3 decimals, always '.' as decimal separator (locale independent).
     * Truncated (floored) to the millisecond, like formatUtc, so time_utc = start + elapsed_s holds
     * to the millisecond in every row. The 1e-6 ms guard absorbs binary rounding (1.001 stored as 1.00099999...).
     */
    fun formatElapsed(seconds: Double): String {
        val millis = Math.floor(seconds * 1000.0 + 1e-6)
        // Locale.ROOT: in e.g. a German locale "%.3f" would give "1,500" and break the CSV.
        val s = String.format(Locale.ROOT, "%.3f", millis / 1000.0)
        return if (s == "-0.000") "0.000" else s
    }

    // 'SSS' truncates (never rounds up into the next millisecond).
    private val UTC_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    /** RR interval in Bluetooth Heart Rate Service units (1/1024 s) -> milliseconds, no rounding. */
    fun rr1024ToMs(raw: Int): Double = raw * 1000.0 / 1024.0
}
