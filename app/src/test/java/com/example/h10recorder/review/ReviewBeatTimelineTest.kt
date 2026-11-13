package com.example.h10recorder.review

import com.example.h10recorder.data.BeatTimeline
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BeatTimeline edge cases. Ground truth is known in each scenario, so the estimate's error can be
 * measured. Packets arrive with zero latency unless stated (the best case for the algorithm).
 */
class ReviewBeatTimelineTest {

    @Test fun `one lost packet does not shift every later beat by a whole RR`() {
        val bt = BeatTimeline()
        for (k in 1..10) bt.add(k.toDouble(), listOf(1024))       // 60 bpm, beat k at k s
        // packet 11 (beat at 11 s) never arrives
        var worst = 0.0
        var resyncs = 0
        // Agreed after review discussion: no algorithm without sensor timestamps is exact within seconds of
        // an unreported beat; the windowed minimum must recover once the window has passed the loss.
        val recoveredAfter = 11 + BeatTimeline.DEFAULT_WINDOW_S.toInt() + 1
        for (k in 12..(recoveredAfter + 300)) {
            val r = bt.add(k.toDouble(), listOf(1024))
            if (r.resynced) resyncs++
            if (k > recoveredAfter) worst = maxOf(worst, abs(r.beats.single().elapsedS - k))
        }
        assertEquals(0, resyncs)
        assertTrue(worst < 0.5, "beats still $worst s off after the window passed the lost packet")
    }

    @Test fun `30 ppm clock drift over a 12 h session`() {
        // Sensor clock 30 ppm slow vs the phone: a beat the sensor measures as 1024/1024 s takes 1.00003 s phone time.
        val bt = BeatTimeline()
        var worst = 0.0
        var resyncs = 0
        val beats = 12 * 3600
        for (k in 1..beats) {
            val trueT = k * 1.00003
            val r = bt.add(trueT, listOf(1024))
            if (r.resynced) resyncs++
            worst = maxOf(worst, abs(r.beats.single().elapsedS - trueT))
        }
        assertEquals(0, resyncs, "no resync")
        assertTrue(worst < 0.5, "beats silently drift up to $worst s early over 12 h")
    }

    @Test fun `a packet delayed by BLE is not reported as missing beats`() {
        val bt = BeatTimeline()
        val results = mutableListOf<BeatTimeline.Result>()
        for (k in 1..10) results += bt.add(k.toDouble(), listOf(1024))
        // packets for beats 11, 12, 13 are held up and delivered together at 13.5 s (in order, nothing lost)
        results += bt.add(13.5, listOf(1024))
        results += bt.add(13.5, listOf(1024))
        results += bt.add(13.5, listOf(1024))
        for (k in 14..20) results += bt.add(k.toDouble(), listOf(1024))
        val times = results.flatMap { r -> r.beats.map { it.elapsedS } }
        val errors = times.mapIndexed { i, t -> t - (i + 1) }
        assertFalse(results.any { it.resynced }, "rr_resync ('beats missing') logged although no beat was missing")
        assertTrue(errors.all { abs(it) < 0.5 }, "beat errors: $errors")
    }

    @Test fun `no precision loss in a 12 h session with many beats per packet`() {
        val bt = BeatTimeline()
        var t = 0.0
        var last = Double.NEGATIVE_INFINITY
        repeat(12 * 3600) {
            t += 1.0
            val r = bt.add(t, listOf(256, 256, 256, 256))  // 240 bpm, 4 beats per packet
            for ((j, b) in r.beats.withIndex()) {
                assertEquals(t - 0.75 + 0.25 * j, b.elapsedS, 1e-9)
                assertTrue(b.elapsedS > last)
                last = b.elapsedS
            }
        }
    }
}
