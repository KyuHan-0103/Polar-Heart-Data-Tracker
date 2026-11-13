package com.example.h10recorder.review

import com.example.h10recorder.data.BeatTimeline
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Simulation answering the F5(a) design question: can a lost/omitted beat be corrected quickly
 * without misfiring on real 1 Hz H10 packet timing with random beat phase?
 *
 * H10 model (assumptions stated, all knobs below):
 *  - the strap sends an HR packet every PACKET_S of its own clock, grid phase random;
 *  - beats: RR random walk 0.55-1.1 s (HRV), plus respiratory modulation;
 *  - each beat is reported in the first packet sent at least d after it, d ~ U(0, DETECT_MAX)
 *    (beat-detection delay inside the strap, unknown for the H10, so it's a parameter);
 *  - RR = difference of beat timestamps quantised to 1/1024 s (so RR sums telescope);
 *  - BLE + phone latency ~ U(LAT_MIN, LAT_MAX); sensor clock drifts DRIFT vs the phone.
 *
 * Candidate algorithm ("two-sided"): besides the existing upper bound (a beat is not after its
 * packet's arrival), use the lower bound the H10 also gives: a beat reported in packet k was NOT
 * in packet k-1, so it happened after packet k-1 was sent (minus detection delay + latency
 * slack). Empty packets (no RR) count as "previous packet" too. If the max lower bound exceeds
 * the min upper bound, the chain is inconsistent (beats missing) -> re-anchor at once.
 * The lower bound is only used when the previous inter-arrival gap is regular (0.8-1.25 s), so
 * a bunch of BLE-delayed packets doesn't produce a false bound.
 */
class ReviewBeatTimelineSimTest {

    // ---------------- simulation ----------------
    data class Pkt(val arrival: Double, val rr: List<Int>, val trueBeats: List<Double>)

    class Model(
        val packetS: Double = 1.0, val detectMax: Double = 0.25,
        val latMin: Double = 0.005, val latMax: Double = 0.060, val drift: Double = 20e-6,
        val rrCenter: Double = 0.8, val rrNoise: Double = 0.015,
    )

    /**
     * [omitAtS]: the first RR reported at or after this sensor time is dropped (H10 omits an RR).
     * [noRrFrom]..[noRrTo]: packets in this window carry no RRs at all (poor contact).
     * [delayAt]: packets sent in [delayAt, delayAt+2) are held back and delivered at delayAt+2.5.
     */
    fun simulate(seed: Long, durationS: Double, m: Model = Model(), omitAtS: Double? = null,
                 noRrFrom: Double? = null, noRrTo: Double? = null, delayAt: Double? = null): List<Pkt> {
        val rng = Random(seed)
        val beats = ArrayList<Double>()
        var t = rng.nextDouble()
        var rr = m.rrCenter
        while (t < durationS + 5) {
            beats += t
            rr = (rr + 0.05 * (m.rrCenter - rr) + rng.nextGaussian() * m.rrNoise).coerceIn(0.4, 1.5)
            t += rr + 0.03 * kotlin.math.sin(t * 2 * Math.PI / 4.0)  // respiratory sinus arrhythmia
        }
        val q = beats.map { (it * 1024).roundToLong() }
        val detect = beats.map { rng.nextDouble() * m.detectMax }
        val out = ArrayList<Pkt>()
        var next = 1                    // next beat (index >= 1, has an RR) not yet reported
        var omitted = omitAtS == null
        var send = 1.0 + rng.nextDouble() * m.packetS
        while (send < durationS) {
            val rrs = ArrayList<Int>(); val tb = ArrayList<Double>()
            while (next < beats.size && beats[next] + detect[next] <= send) {
                val skip = !omitted && beats[next] >= omitAtS!!
                if (skip) omitted = true
                val noRr = noRrFrom != null && send >= noRrFrom && send < noRrTo!!
                if (!skip && !noRr) { rrs += (q[next] - q[next - 1]).toInt(); tb += beats[next] * (1 + m.drift) }
                next++
            }
            var arrival = send * (1 + m.drift) + m.latMin + rng.nextDouble() * (m.latMax - m.latMin)
            if (delayAt != null && send >= delayAt && send < delayAt + 2.0) arrival = (delayAt + 2.5) * (1 + m.drift)
            out.lastOrNull()?.let { arrival = max(arrival, it.arrival) }   // BLE delivers in order
            out += Pkt(arrival, rrs, tb)
            send += m.packetS
        }
        return out
    }

    // ---------------- candidate algorithm ----------------
    class TwoSided(private val slackS: Double = 0.35, private val windowS: Double = 30.0) {
        private class P(val t: Double, val ub: Double, val lb: Double)
        private val win = ArrayDeque<P>()
        private var sumRaw = 0L
        private var prevArrival: Double? = null
        private var prevGapRegular = false
        private var lastBeat = Double.NEGATIVE_INFINITY
        var resyncs = 0; private set

        fun add(arrival: Double, rr: List<Int>): List<Double> {
            val prev = prevArrival
            val gapRegular = prev != null && arrival - prev in 0.8..1.25
            prevArrival = arrival
            val useLb = gapRegular && prevGapRegular     // both gaps regular: no delayed bunch involved
            prevGapRegular = gapRegular
            if (rr.isEmpty()) return emptyList()
            var s = sumRaw
            val sums = rr.map { s += it; s / 1024.0 }
            sumRaw = s
            val ub = arrival - sums.last()
            val lb = if (useLb) prev!! - slackS - sums.first() else Double.NEGATIVE_INFINITY
            win.addLast(P(arrival, ub, lb))
            while (win.first().t < arrival - windowS) win.removeFirst()
            if (win.size > 1 && win.maxOf { it.lb } > win.minOf { it.ub }) {
                resyncs++
                win.clear(); win.addLast(P(arrival, ub, lb))
            }
            val offset = win.minOf { it.ub }
            return sums.map { max(offset + it, lastBeat).also { b -> lastBeat = b } }
        }
    }

    // ---------------- metrics ----------------
    data class Run(val errors: List<Pair<Double, Double>>, val resyncs: Int)   // (true time, est - true)

    private fun runProduction(pkts: List<Pkt>, windowS: Double = BeatTimeline.DEFAULT_WINDOW_S): Run {
        val bt = BeatTimeline(windowS = windowS); var resyncs = 0
        val errs = ArrayList<Pair<Double, Double>>()
        for (p in pkts) {
            val r = bt.add(p.arrival, p.rr); if (r.resynced) resyncs++
            r.beats.zip(p.trueBeats).forEach { (b, tb) -> errs += tb to (b.elapsedS - tb) }
        }
        return Run(errs, resyncs)
    }

    private fun runTwoSided(pkts: List<Pkt>, slack: Double = 0.35): Run {
        val bt = TwoSided(slack)
        val errs = ArrayList<Pair<Double, Double>>()
        for (p in pkts) bt.add(p.arrival, p.rr).zip(p.trueBeats).forEach { (b, tb) -> errs += tb to (b - tb) }
        return Run(errs, bt.resyncs)
    }

    /**
     * Same seed with and without the omitted RR gives identical packets except that one RR, so the
     * extra error per beat is (estimate with loss) - (estimate without). Recovery = seconds after
     * [fromS] until that extra error stays below [tolS] for good.
     */
    private fun recovery(lost: Run, clean: Run, fromS: Double, tolS: Double = 0.05): Double {
        val base = clean.errors.associate { it.first to it.second }
        val late = lost.errors.filter { it.first >= fromS && abs(it.second - (base[it.first] ?: it.second)) > tolS }
        return (late.maxOfOrNull { it.first } ?: fromS) - fromS
    }

    private fun absErr(run: Run, afterS: Double = 60.0) = run.errors.filter { it.first > afterS }.map { abs(it.second) }
    private fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]
    private fun pct(xs: List<Double>, p: Double) = xs.sorted()[min(xs.size - 1, (xs.size * p).toInt())]
    private fun f(x: Double) = "%.3f".format(x)

    // ---------------- experiments ----------------

    @Test fun `omitted RR - recovery time, production vs two-sided`() {
        val prod = ArrayList<Double>(); val two = ArrayList<Double>()
        var twoResyncs = 0
        repeat(200) { seed ->
            val clean = simulate(seed.toLong(), 300.0)
            val lost = simulate(seed.toLong(), 300.0, omitAtS = 150.0)
            prod += recovery(runProduction(lost), runProduction(clean), 150.0)
            val t = runTwoSided(lost); twoResyncs += t.resyncs
            two += recovery(t, runTwoSided(clean), 150.0)
        }
        println("omitted RR, 200 seeds, s until extra error < 50 ms: production(30 s window) median=${f(median(prod))} p95=${f(pct(prod, .95))} max=${f(prod.max())}" +
            " | two-sided median=${f(median(two))} p95=${f(pct(two, .95))} max=${f(two.max())} resyncs=$twoResyncs")
        assertEquals(200, twoResyncs, "two-sided detects every omitted RR (one resync per run)")
        assertTrue(median(two) < median(prod) / 2)
    }

    @Test fun `no loss, 12 h - misfires and accuracy, 30 s window vs all-time min vs two-sided`() {
        for (seed in 1L..3L) {
            val pkts = simulate(seed, 12 * 3600.0)
            val p = runProduction(pkts); val all = runProduction(pkts, windowS = 1e12); val t = runTwoSided(pkts)
            println("12 h seed $seed |err| p50/p99: 30 s window ${f(median(absErr(p)))}/${f(pct(absErr(p), .99))} resyncs=${p.resyncs}" +
                " | all-time min ${f(median(absErr(all)))}/${f(pct(absErr(all), .99))} resyncs=${all.resyncs}" +
                " | two-sided ${f(median(absErr(t)))}/${f(pct(absErr(t), .99))} resyncs=${t.resyncs}")
            assertEquals(0, t.resyncs, "two-sided misfired")
            val w = listOf(120.0, 300.0).joinToString(" | ") { ws -> val r = runProduction(pkts, windowS = ws); "window ${ws.toInt()} s ${f(median(absErr(r)))}/${f(pct(absErr(r), .99))}" }
            println("   seed $seed: $w")
        }
    }

    @Test fun `resting HR near 60 bpm - beat phase locks to the 1 s packet grid`() {
        for (center in listOf(1.0, 0.98, 0.8)) {
            val pkts = simulate(11, 3600.0, Model(rrCenter = center, rrNoise = 0.005))
            val line = listOf(30.0, 120.0, 300.0, 1e12).joinToString(" | ") { w ->
                val r = runProduction(pkts, windowS = w)
                "window ${if (w > 1e9) "all-time" else "${w.toInt()} s"} ${f(median(absErr(r)))}/${f(pct(absErr(r), .99))}"
            }
            println("RR~$center s, low HRV, 1 h |err| p50/p99: $line")
        }
    }

    @Test fun `robustness - detection delay larger than the slack assumes`() {
        val m = Model(detectMax = 0.5)                     // model mismatch: slack 0.35 < real delay spread
        val pkts = simulate(7, 3600.0, m)
        println("detectMax 0.5 s, 1 h: two-sided slack .35 resyncs=${runTwoSided(pkts).resyncs}, slack .6 resyncs=${runTwoSided(pkts, 0.6).resyncs}")
        val clean = simulate(7, 300.0, m); val lost = simulate(7, 300.0, m, omitAtS = 150.0)
        println("  omitted RR with detectMax .5: two-sided slack .6 recovery=${f(recovery(runTwoSided(lost, 0.6), runTwoSided(clean, 0.6), 150.0))} s," +
            " production=${f(recovery(runProduction(lost), runProduction(clean), 150.0))} s")
    }

    @Test fun `scenarios - delayed bunch and no-RR gap`() {
        val clean = simulate(3, 300.0)
        val delayed = simulate(3, 300.0, delayAt = 150.0)
        val gap = simulate(3, 300.0, noRrFrom = 150.0, noRrTo = 158.0)
        for ((name, pkts) in listOf("delayed bunch 2.5 s" to delayed, "8 s no-RR gap" to gap)) {
            val p = runProduction(pkts); val t = runTwoSided(pkts)
            val w = { r: Run -> r.errors.filter { it.first in 145.0..200.0 }.maxOf { abs(it.second) } }
            println("$name: production resyncs=${p.resyncs} max|err| 145-200 s=${f(w(p))}" +
                " | two-sided resyncs=${t.resyncs} max|err|=${f(w(t))}")
        }
    }
}
