package com.example.h10recorder.review

import com.example.h10recorder.data.Events
import com.example.h10recorder.data.FileKind
import com.example.h10recorder.data.SessionClock
import com.example.h10recorder.data.SessionFiles
import com.example.h10recorder.data.SessionInfo
import com.example.h10recorder.recording.RecorderStatus
import com.example.h10recorder.recording.SessionConfig
import com.example.h10recorder.recording.SessionRecorder
import com.example.h10recorder.recording.StopReason
import com.example.h10recorder.sensor.HrSensor
import com.example.h10recorder.testutil.CsvTestUtils
import com.example.h10recorder.testutil.FakeClock
import com.example.h10recorder.testutil.FakeHrSensor
import com.example.h10recorder.testutil.H10Fixtures
import com.example.h10recorder.testutil.H10Fixtures.sample
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sensor whose connect/disconnect can be replaced; everything else is FakeHrSensor. */
private class ScriptedSensor(val fake: FakeHrSensor = FakeHrSensor()) : HrSensor by fake {
    var onConnect: (suspend (String) -> Unit)? = null
    var onDisconnect: (suspend (String) -> Unit)? = null
    override suspend fun connect(deviceId: String) { onConnect?.invoke(deviceId) ?: fake.connect(deviceId) }
    override suspend fun disconnect(deviceId: String) { fake.disconnect(deviceId); onDisconnect?.invoke(deviceId) }
}

/** Clock that runs [hook] when read from a thread named "ui" (used to pause logEvent mid-call). */
private class HookClock(private val base: SessionClock) : SessionClock {
    @Volatile var hook: (() -> Unit)? = null
    override fun nowUtc(): Instant = base.nowUtc()
    override fun monotonicNanos(): Long {
        if (Thread.currentThread().name == "ui") hook?.invoke()
        return base.monotonicNanos()
    }
}

class ReviewSessionRecorderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val root by lazy { tmp.newFolder("H10_Data") }
    private val files by lazy { SessionFiles(root) }
    private val clock = FakeClock()
    private val sensor = ScriptedSensor()
    private val id = H10Fixtures.DEVICE_ID
    private val config = SessionConfig("P001", "01", id, appVersion = "0.1.0", sdkVersion = "8.5.0")
    private val start: Instant = clock.wall

    private fun recorderTest(body: suspend TestScope.() -> Unit) = runTest { clock.scheduler = testScheduler; body() }
    private fun f(kind: FileKind): File = files.file("P001", "01", kind)
    private fun records(kind: FileKind) = CsvTestUtils.readRecords(f(kind))

    // ---------------------------------------------------------------- status on early failures

    @Test fun `a run that fails before connecting leaves a Stopped status for the UI`() = recorderTest {
        files.createSessionDir("P001", "01")
        val recorder = SessionRecorder(sensor, files, clock)
        runCatching { recorder.run(config) }
        // RecordingService: "Status already says Stopped(ERROR/USER)" and decides the alert from it.
        assertIs<RecorderStatus.Stopped>(recorder.status.value, "status after SessionExistsException")
    }

    @Test fun `a non-IO connect error ends with Stopped(ERROR), not stuck in Connecting`() = recorderTest {
        // e.g. PolarInvalidArgument from api.connectToDevice("") when EXTRA_DEVICE was missing
        sensor.onConnect = { throw IllegalArgumentException("invalid device id") }
        val recorder = SessionRecorder(sensor, files, clock)
        val outcome = runCatching { recorder.run(config) }
        assertTrue(outcome.isFailure)
        assertEquals(StopReason.ERROR, (recorder.status.value as? RecorderStatus.Stopped)?.reason,
            "status is ${recorder.status.value}")
    }

    // ---------------------------------------------------------------- logEvent vs cleanup

    @Test fun `a marker pressed while Stop is cleaning up is ignored, not thrown at the UI`() = recorderTest {
        val hookClock = HookClock(clock)
        val recorder = SessionRecorder(sensor, files, hookClock)
        val job = launch { recorder.run(config) }
        runCurrent()
        assertEquals(RecorderStatus.Recording, recorder.status.value)

        val inside = CountDownLatch(1)
        val cleanupDone = CountDownLatch(1)
        hookClock.hook = { inside.countDown(); cleanupDone.await(5, TimeUnit.SECONDS) }
        var uiError: Throwable? = null
        val ui = thread(name = "ui") {
            try { recorder.logEvent(Events.MARKER, "button") } catch (t: Throwable) { uiError = t }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))     // UI thread has passed the `session ?: return` check
        job.cancelAndJoin()                                // Stop: cleanup closes events.csv
        cleanupDone.countDown()
        ui.join(5_000)
        assertNull(uiError, "logEvent threw on the UI thread: $uiError")
    }

    // ---------------------------------------------------------------- durability

    @Test fun `a marker is on disk within the flush interval even if no further events follow`() = recorderTest {
        val recorder = SessionRecorder(sensor, files, clock)
        val job = launch { recorder.run(config) }
        runCurrent()
        clock.advanceSeconds(2.0)                          // < 5 s after the start events were written
        recorder.logEvent(Events.MARKER, "task 1 start")
        // 10 more minutes of normal recording, no further events
        repeat(600) { clock.advanceSeconds(1.0); sensor.fake.send(sample(60, 1024)); runCurrent() }
        val onDisk = records(FileKind.EVENTS).map { it["event"] }   // read while still recording (phone could die now)
        val hrOnDisk = records(FileKind.HR).size
        job.cancelAndJoin()
        assertTrue(hrOnDisk >= 595, "hr.csv is flushed: $hrOnDisk rows")
        assertTrue(Events.MARKER in onDisk,
            "README: 'a dead battery loses at most ~5 s of rows' - marker logged 600 s ago is still only in RAM; on disk: $onDisk")
    }

    @Test fun `rows written just before a drop are on disk while reconnecting`() = recorderTest {
        val recorder = SessionRecorder(sensor, files, clock)
        val job = launch { recorder.run(config) }
        runCurrent()
        repeat(3) { clock.advanceSeconds(1.0); sensor.fake.send(sample(60, 1024)); runCurrent() }   // < 5 s since last flush
        sensor.fake.connectFailures = Int.MAX_VALUE
        sensor.fake.dropConnection(); runCurrent()
        advanceTimeBy(10_000)                                     // 10 s into the 20 s reconnect window
        assertEquals(RecorderStatus.Reconnecting, recorder.status.value)
        val hrOnDisk = records(FileKind.HR).size                  // phone could die now
        job.cancelAndJoin()
        assertEquals(3, hrOnDisk, "SessionRecorder.record flushes with flushIfDue(), which isn't due: rows stay in RAM during the gap")
    }

    // ---------------------------------------------------------------- time columns

    @Test fun `time_utc equals session start plus elapsed_s`() = recorderTest {
        val recorder = SessionRecorder(sensor, files, clock)
        val job = launch { recorder.run(config) }
        runCurrent()
        clock.advanceSeconds(1.0006)           // packet 1.0006 s after start
        sensor.fake.send(sample(60)); runCurrent()
        job.cancelAndJoin()
        val row = records(FileKind.HR).single()
        val utcMs = Duration.between(start, Instant.parse(row["time_utc"])).toMillis()
        val elapsedMs = Math.round(row["elapsed_s"]!!.toDouble() * 1000)
        assertEquals(utcMs, elapsedMs, "time_utc=${row["time_utc"]} elapsed_s=${row["elapsed_s"]} (truncated vs rounded)")
    }

    // ---------------------------------------------------------------- things that should work (coverage)

    @Test fun `stop during the first connect writes nothing and reports USER`() = recorderTest {
        sensor.fake.connectHangs = true
        val recorder = SessionRecorder(sensor, files, clock)
        val job = launch { recorder.run(config) }
        runCurrent(); advanceTimeBy(5_000)
        job.cancelAndJoin()
        assertEquals(RecorderStatus.Stopped(StopReason.USER), recorder.status.value)
        assertFalse(files.sessionDir("P001", "01").exists())
    }

    @Test fun `stop during reconnect still finalises the session`() = recorderTest {
        val recorder = SessionRecorder(sensor, files, clock)
        val job = launch { recorder.run(config) }
        runCurrent()
        sensor.fake.connectFailures = Int.MAX_VALUE
        sensor.fake.dropConnection(); runCurrent()
        advanceTimeBy(5_000)
        assertEquals(RecorderStatus.Reconnecting, recorder.status.value)
        job.cancelAndJoin()
        assertEquals(RecorderStatus.Stopped(StopReason.USER), recorder.status.value)
        assertEquals(Events.RECORDING_STOPPED, records(FileKind.EVENTS).last()["event"])
        assertTrue(SessionInfo.fromJson(f(FileKind.SESSION).readText()).endUtc != null)
        assertEquals(1, CsvTestUtils.readRecords(File(root, SessionFiles.SESSIONS_INDEX)).size)
    }

    @Test fun `a failing disconnect in cleanup still closes files and reports ERROR`() = recorderTest {
        sensor.onDisconnect = { throw IllegalStateException("SDK exploded") }
        val recorder = SessionRecorder(sensor, files, clock)
        val job = launch { recorder.run(config) }
        runCurrent()
        job.cancelAndJoin()
        val st = recorder.status.value as RecorderStatus.Stopped
        assertEquals(StopReason.ERROR, st.reason)
        assertTrue(st.message!!.startsWith("Cleanup failed"))
        assertEquals(Events.RECORDING_STOPPED, records(FileKind.EVENTS).last()["event"])
        assertTrue(SessionInfo.fromJson(f(FileKind.SESSION).readText()).endUtc != null)
    }

    @Test fun `a recorder can be reused for a second session`() = recorderTest {
        val recorder = SessionRecorder(sensor, files, clock)
        var job: Job = launch { recorder.run(config) }
        runCurrent(); job.cancelAndJoin()
        job = launch { recorder.run(config.copy(sessionId = "02")) }
        runCurrent()
        assertEquals(RecorderStatus.Recording, recorder.status.value)
        job.cancelAndJoin()
        assertEquals(2, CsvTestUtils.readRecords(File(root, SessionFiles.SESSIONS_INDEX)).size)
    }
}
