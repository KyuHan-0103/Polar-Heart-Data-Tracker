package com.example.h10recorder.data

import com.example.h10recorder.testutil.FakeClock
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeUtilsTest {

    @Test fun `formatUtc always has millisecond precision and Z suffix`() {
        assertEquals("2026-11-12T16:20:01.000Z", TimeUtils.formatUtc(Instant.parse("2026-11-12T16:20:01Z")))
        assertEquals("2026-11-12T16:20:01.120Z", TimeUtils.formatUtc(Instant.parse("2026-11-12T16:20:01.12Z")))
    }

    @Test fun `formatUtc truncates sub-millisecond digits rather than rounding up`() {
        assertEquals("2026-11-12T16:20:01.123Z", TimeUtils.formatUtc(Instant.parse("2026-11-12T16:20:01.123999999Z")))
    }

    @Test fun `formatElapsed uses 3 decimals`() {
        assertEquals("0.000", TimeUtils.formatElapsed(0.0))
        assertEquals("12.345", TimeUtils.formatElapsed(12.3456))     // truncated, like time_utc
        assertEquals("1.000", TimeUtils.formatElapsed(1.0006))
        assertEquals("1.001", TimeUtils.formatElapsed(1.001))        // binary 1.00099999... still 1.001
        assertEquals("3600.000", TimeUtils.formatElapsed(3600.0))
    }

    @Test fun `formatElapsed floors negatives like time_utc and never writes negative zero`() {
        assertEquals("-0.001", TimeUtils.formatElapsed(-0.0001))
        assertEquals("-0.400", TimeUtils.formatElapsed(-0.4))
        assertEquals("0.000", TimeUtils.formatElapsed(-0.0))
    }

    @Test fun `formatElapsed uses a dot even when the phone locale uses comma decimals`() =
        withLocale(Locale.GERMANY) { assertEquals("1.500", TimeUtils.formatElapsed(1.5)) }

    @Test fun `rr1024ToMs converts exactly without rounding`() {
        assertEquals(1000.0, TimeUtils.rr1024ToMs(1024))
        assertEquals(820.3125, TimeUtils.rr1024ToMs(840))
        assertEquals(0.9765625, TimeUtils.rr1024ToMs(1))
    }

    @Test fun `timeline starts at zero elapsed`() {
        val clock = FakeClock()
        val tl = SessionTimeline(clock)
        assertEquals(Stamp(clock.wall, 0.0), tl.start)
        assertEquals(0.0, tl.now().elapsedS)
    }

    @Test fun `timeline follows the monotonic clock`() {
        val clock = FakeClock()
        val tl = SessionTimeline(clock)
        val start = clock.wall
        clock.advanceSeconds(2.5)
        assertEquals(Stamp(start.plusMillis(2500), 2.5), tl.now())
    }

    @Test fun `timeline ignores wall clock jumps during a session`() {
        val clock = FakeClock()
        val tl = SessionTimeline(clock)
        val start = clock.wall
        clock.advanceSeconds(10.0)
        clock.jumpWallClock(-3600)          // phone syncs clock backwards an hour
        clock.advanceSeconds(1.0)
        val now = tl.now()
        assertEquals(11.0, now.elapsedS)
        assertEquals(start.plusSeconds(11), now.timeUtc)
    }

    @Test fun `timeline stamps never go backwards`() {
        val clock = FakeClock()
        val tl = SessionTimeline(clock)
        var prev = tl.now()
        repeat(100) {
            clock.advanceSeconds(0.013)
            if (it == 50) clock.jumpWallClock(-60)
            val next = tl.now()
            assertTrue(next.timeUtc >= prev.timeUtc && next.elapsedS >= prev.elapsedS)
            prev = next
        }
    }
}

internal fun <T> withLocale(locale: Locale, block: () -> T): T {
    val old = Locale.getDefault()
    Locale.setDefault(locale)
    try { return block() } finally { Locale.setDefault(old) }
}

class SessionTimelineUtcAtTest {
    @Test fun `utcAt maps elapsed seconds onto the session start`() {
        val clock = FakeClock()
        val tl = SessionTimeline(clock)
        assertEquals(clock.wall.plusMillis(1500), tl.utcAt(1.5))
        assertEquals(clock.wall.minusMillis(400), tl.utcAt(-0.4))
    }
}

/** Per-beat time estimation. Algorithm described in DATA_NOTES.md. */
class BeatTimelineTest {
    private fun BeatTimeline.times(t: Double, vararg rr: Int) = add(t, rr.toList()).beats.map { it.elapsedS }

    @Test fun `first packet puts its last beat at the arrival time`() {
        assertEquals(listOf(1.0), BeatTimeline().times(1.0, 1024))
    }

    @Test fun `beats in one packet are spaced by their RR intervals`() {
        assertEquals(listOf(0.5, 1.0), BeatTimeline().times(1.0, 512, 512))
    }

    @Test fun `later beats follow the RR chain, not packet arrival`() {
        val bt = BeatTimeline()
        bt.times(1.0, 1024)
        assertEquals(listOf(2.0), bt.times(2.3, 1024))     // arrived 0.3 s after the beat
    }

    @Test fun `a tighter packet pulls the chain back so no beat is after its packet`() {
        val bt = BeatTimeline()
        assertEquals(listOf(1.5), bt.times(1.5, 1024))    // first packet was late: offset 0.5
        assertEquals(listOf(2.2), bt.times(2.2, 1024))    // shows offset is at most 0.2
        assertEquals(listOf(3.2), bt.times(3.9, 1024))    // chain continues from the better offset
    }

    @Test fun `RR values are exact`() {
        assertEquals(listOf(820.3125, 835.9375), BeatTimeline().add(1.0, listOf(840, 856)).beats.map { it.rrMs })
    }

    @Test fun `packets without RR add nothing and do not break the chain`() {
        val bt = BeatTimeline()
        bt.times(1.0, 1024)
        assertEquals(emptyList(), bt.times(2.0))
        assertEquals(listOf(2.0), bt.times(3.1, 1024))
    }

    @Test fun `missing beats trigger a resync on the second late packet`() {
        val bt = BeatTimeline()
        bt.add(1.0, listOf(1024))
        val first = bt.add(10.0, listOf(1024))               // 8 s of beats never arrived
        assertFalse(first.resynced, "one late packet could just be a Bluetooth delay")
        val second = bt.add(11.0, listOf(1024))
        assertTrue(second.resynced)
        assertEquals(listOf(11.0), second.beats.map { it.elapsedS })
    }

    @Test fun `a lost beat is corrected once the window has moved past it`() {
        val bt = BeatTimeline()
        for (k in 1..10) bt.add(k.toDouble(), listOf(1024))
        // beat 11's packet lost; offset is 1 s too small until the pre-loss packets leave the 30 s window
        val window = BeatTimeline.DEFAULT_WINDOW_S.toInt()
        val late = (12..(window + 60)).map { k -> k to bt.add(k.toDouble(), listOf(1024)).beats.single().elapsedS }
        assertTrue(late.filter { it.first > 10 + window + 1 }.all { (k, t) -> kotlin.math.abs(t - k) < 1e-9 })
    }

    @Test fun `a gap of packets without RR resyncs at the first beat after it`() {
        val bt = BeatTimeline()
        for (k in 1..60) bt.add(k.toDouble(), listOf(1024))
        for (k in 61..68) bt.add(k.toDouble(), emptyList())       // poor skin contact: packets, no RRs
        val r = bt.add(69.0, listOf(1024))                          // on schedule, so not a delay
        assertTrue(r.resynced)
        assertEquals(listOf(69.0), r.beats.map { it.elapsedS })
    }

    @Test fun `follows slow clock drift`() {
        val bt = BeatTimeline()
        var worst = 0.0
        for (k in 1..(12 * 3600)) {                       // 12 h, sensor clock 30 ppm slow
            val trueT = k * 1.00003
            worst = maxOf(worst, kotlin.math.abs(bt.add(trueT, listOf(1024)).beats.single().elapsedS - trueT))
        }
        assertTrue(worst < 0.01, "worst $worst s")
    }

    @Test fun `normal arrival jitter does not resync`() {
        val bt = BeatTimeline()
        bt.add(1.0, listOf(1024))
        assertFalse(bt.add(3.9, listOf(1024)).resynced)      // 1.9 s behind: under the 2 s threshold
    }

    @Test fun `reset starts a fresh chain without reporting a resync`() {
        val bt = BeatTimeline()
        bt.add(1.0, listOf(1024))
        bt.reset()                                           // e.g. after reconnect
        val r = bt.add(60.0, listOf(1024))
        assertFalse(r.resynced)
        assertEquals(listOf(60.0), r.beats.map { it.elapsedS })
    }

    @Test fun `with realistic jittered packets, beats never decrease and never follow their packet`() {
        val bt = BeatTimeline()
        val rng = java.util.Random(42)
        var trueBeat = 0.0
        var prev = Double.NEGATIVE_INFINITY
        var t = 0.0
        repeat(600) {
            t += 1.0
            val rrs = mutableListOf<Int>()
            while (trueBeat + 0.8 <= t) { trueBeat += 0.8; rrs += 819 }  // ~75 bpm
            val arrival = t + rng.nextDouble() * 0.05                    // BLE latency 0-50 ms
            val r = bt.add(arrival, rrs)
            assertFalse(r.resynced)
            for (b in r.beats) {
                assertTrue(b.elapsedS >= prev, "beat went backwards")
                assertTrue(b.elapsedS <= arrival + 1e-9, "beat after its packet")
                prev = b.elapsedS
            }
        }
    }
}
