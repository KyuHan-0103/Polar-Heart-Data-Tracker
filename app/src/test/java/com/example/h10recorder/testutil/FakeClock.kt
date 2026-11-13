package com.example.h10recorder.testutil

import com.example.h10recorder.data.SessionClock
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.Instant

/**
 * Manually driven clock. advanceSeconds() moves both clocks; jumpWallClock() simulates a phone
 * clock sync. If [scheduler] is set, coroutine virtual time (delay/withTimeout in runTest) also
 * moves the clock, so timeouts and timestamps agree.
 */
class FakeClock(
    var wall: Instant = Instant.parse("2026-11-12T15:00:00Z"),
    var mono: Long = 5_000_000_000L,
    var scheduler: TestCoroutineScheduler? = null,
) : SessionClock {
    private val virtualNanos: Long get() = (scheduler?.currentTime ?: 0L) * 1_000_000
    override fun nowUtc(): Instant = wall.plusNanos(virtualNanos)
    override fun monotonicNanos(): Long = mono + virtualNanos

    fun advanceSeconds(seconds: Double) {
        val nanos = (seconds * 1_000_000_000).toLong()
        mono += nanos
        wall = wall.plusNanos(nanos)
    }

    /** Wall clock changes, monotonic clock does not (what NTP / manual time changes do). */
    fun jumpWallClock(seconds: Long) {
        wall = wall.plusSeconds(seconds)
    }
}
