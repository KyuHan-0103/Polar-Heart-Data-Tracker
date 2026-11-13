package com.example.h10recorder.recording

import com.example.h10recorder.data.EventRow
import com.example.h10recorder.data.Events
import com.example.h10recorder.data.FileKind
import com.example.h10recorder.data.HrRow
import com.example.h10recorder.data.RrRow
import com.example.h10recorder.data.SessionExistsException
import com.example.h10recorder.data.SessionFiles
import com.example.h10recorder.data.SessionInfo
import com.example.h10recorder.sensor.HrSample
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wiring test: FakeHrSensor -> SessionRecorder -> CsvWriter -> SessionFiles -> files on disk.
 * Everything except the real Polar SDK. Coroutine virtual time drives the 20 s connection timeout.
 */
class SessionRecorderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val root by lazy { tmp.newFolder("H10_Data") }
    private val files by lazy { SessionFiles(root) }
    private val clock = FakeClock()
    private val sensor = FakeHrSensor()
    private val recorder by lazy { SessionRecorder(sensor, files, clock) }
    private val id = H10Fixtures.DEVICE_ID
    private val config = SessionConfig("P001", "01", id, appVersion = "0.1.0", sdkVersion = "8.5.0")
    private val start: Instant = clock.wall
    private val timeoutMs = SessionRecorder.CONNECTION_TIMEOUT.inWholeMilliseconds

    /** Runs a recorder test with the fake clock following coroutine virtual time. */
    private fun recorderTest(body: suspend TestScope.() -> Unit) = runTest {
        clock.scheduler = testScheduler
        body()
    }

    private fun TestScope.startRecording(): Job = launch { recorder.run(config) }.also { runCurrent() }

    /** One packet per second, like a real H10. */
    private fun TestScope.sendAndAdvance(vararg samples: HrSample) {
        for (s in samples) { clock.advanceSeconds(1.0); sensor.send(s); runCurrent() }
    }

    private fun f(kind: FileKind): File = files.file("P001", "01", kind)
    private fun records(kind: FileKind) = CsvTestUtils.readRecords(f(kind))
    private fun eventNames() = records(FileKind.EVENTS).map { it["event"] }
    private fun sessionInfo() = SessionInfo.fromJson(f(FileKind.SESSION).readText())

    // ---- start ----

    @Test fun `connects to the configured strap and starts streaming`() = recorderTest {
        val job = startRecording()
        assertEquals(listOf(id), sensor.connectCalls)
        assertTrue(sensor.isStreaming)
        assertEquals(RecorderStatus.Recording, recorder.status.value)
        job.cancelAndJoin()
    }

    @Test fun `creates the session files with headers and session json at start`() = recorderTest {
        val job = startRecording()
        assertEquals(HrRow.HEADER, CsvTestUtils.readRows(f(FileKind.HR)).first())
        assertEquals(RrRow.HEADER, CsvTestUtils.readRows(f(FileKind.RR)).first())
        assertEquals(EventRow.HEADER, CsvTestUtils.readRows(f(FileKind.EVENTS)).first())
        val info = sessionInfo()
        assertEquals("P001", info.participantId)
        assertEquals(id, info.deviceId)
        assertEquals(start, info.startUtc)
        assertNull(info.endUtc)
        job.cancelAndJoin()
    }

    @Test fun `refuses to record over an existing session`() = recorderTest {
        files.createSessionDir("P001", "01")
        val existing = f(FileKind.HR).apply { writeText("raw data") }
        assertFailsWith<SessionExistsException> { recorder.run(config) }
        assertTrue(sensor.connectCalls.isEmpty())
        assertEquals("raw data", existing.readText())
    }

    @Test fun `first connect gives up after 20 s, notifies, and writes nothing`() = recorderTest {
        sensor.connectHangs = true
        val job = startRecording()
        assertEquals(RecorderStatus.Connecting, recorder.status.value)
        advanceTimeBy(timeoutMs + 1)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(StopReason.CONNECTION_LOST, (recorder.status.value as RecorderStatus.Stopped).reason)
        assertFalse(files.sessionDir("P001", "01").exists(), "no empty session folder")
    }

    // ---- data ----

    @Test fun `one hr row per packet, one rr row per beat`() = recorderTest {
        val job = startRecording()
        sendAndAdvance(
            sample(72, H10Fixtures.RR_RAW_A),
            sample(73),                                             // packet without RR
            sample(71, H10Fixtures.RR_RAW_A, H10Fixtures.RR_RAW_B), // packet with 2 beats
        )
        job.cancelAndJoin()

        val hr = records(FileKind.HR)
        assertEquals(listOf("72", "73", "71"), hr.map { it["hr_bpm"] })
        assertEquals(listOf("1.000", "2.000", "3.000"), hr.map { it["elapsed_s"] })
        assertEquals("2026-11-12T15:00:01.000Z", hr[0]["time_utc"])
        assertEquals("true", hr[0]["contact"])

        val rr = records(FileKind.RR)
        assertEquals(listOf("820.3125", "820.3125", "835.9375"), rr.map { it["rr_ms"] })
        assertEquals(listOf("1.000", "3.000", "3.000"), rr.map { it["received_elapsed_s"] })
    }

    @Test fun `rr rows carry estimated beat times`() = recorderTest {
        val job = startRecording()
        sendAndAdvance(sample(60, 1024), sample(60, 512, 512))   // packets at 1 s and 2 s
        job.cancelAndJoin()
        val rr = records(FileKind.RR)
        assertEquals(listOf("1.000", "1.500", "2.000"), rr.map { it["elapsed_s"] })
        assertEquals(
            listOf("2026-11-12T15:00:01.000Z", "2026-11-12T15:00:01.500Z", "2026-11-12T15:00:02.000Z"),
            rr.map { it["time_utc"] },
        )
    }

    @Test fun `a gap in beats is logged as rr_resync`() = recorderTest {
        val job = startRecording()
        sendAndAdvance(sample(60, 1024))
        clock.advanceSeconds(9.0)
        sendAndAdvance(sample(60, 1024), sample(60, 1024))       // 8 s of beats missing; 2 late packets = not a delay
        job.cancelAndJoin()
        assertTrue(Events.RR_RESYNC in eventNames())
        assertEquals("12.000", records(FileKind.RR).last()["elapsed_s"])
    }

    @Test fun `a long recording keeps every sample`() = recorderTest {
        val job = startRecording()
        val samples = H10Fixtures.resting(600)   // 10 minutes
        sendAndAdvance(*samples.toTypedArray())
        job.cancelAndJoin()
        assertEquals(600, records(FileKind.HR).size)
        assertEquals(samples.sumOf { it.rrRaw1024.size }, records(FileKind.RR).size)
        assertFalse(Events.RR_RESYNC in eventNames())
    }

    @Test fun `marker events from the UI are logged with notes`() = recorderTest {
        val job = startRecording()
        clock.advanceSeconds(30.0)
        recorder.logEvent(Events.MARKER, "task 1 start, seated")
        job.cancelAndJoin()
        val marker = records(FileKind.EVENTS).single { it["event"] == Events.MARKER }
        assertEquals("task 1 start, seated", marker["note"])
        assertEquals("30.000", marker["elapsed_s"])
    }

    @Test fun `live hr follows the latest packet and clears on stop`() = recorderTest {
        val job = startRecording()
        assertNull(recorder.liveHr.value)
        sendAndAdvance(sample(72), sample(75))
        assertEquals(75, recorder.liveHr.value)
        job.cancelAndJoin()
        assertNull(recorder.liveHr.value)
    }

    // ---- stop ----

    @Test fun `stop cleans up - stream stopped, disconnected, json and index finalised`() = recorderTest {
        val job = startRecording()
        sendAndAdvance(sample(70, 900))
        clock.advanceSeconds(1.0)
        job.cancelAndJoin()

        assertFalse(sensor.isStreaming)
        assertEquals(listOf(id), sensor.disconnectCalls)
        assertEquals(start.plusSeconds(2), sessionInfo().endUtc)
        val index = CsvTestUtils.readRecords(File(root, SessionFiles.SESSIONS_INDEX)).single()
        assertEquals("P001", index["participant_id"])
        assertEquals("2.000", index["duration_s"])
        assertEquals(RecorderStatus.Stopped(StopReason.USER), recorder.status.value)
    }

    @Test fun `events log start, connect and stop in order`() = recorderTest {
        val job = startRecording()
        job.cancelAndJoin()
        assertEquals(listOf(Events.RECORDING_STARTED, Events.CONNECTED, Events.RECORDING_STOPPED), eventNames())
    }

    // ---- connection loss ----

    @Test fun `disconnect is logged and the recorder reconnects and keeps recording`() = recorderTest {
        val job = startRecording()
        sendAndAdvance(sample(70, 900))
        sensor.dropConnection()
        runCurrent()
        assertEquals(RecorderStatus.Reconnecting, recorder.status.value)
        advanceTimeBy(SessionRecorder.RETRY_DELAY.inWholeMilliseconds + 1)
        runCurrent()

        assertEquals(2, sensor.connectCalls.size)
        assertTrue(sensor.isStreaming)
        assertEquals(RecorderStatus.Recording, recorder.status.value)
        sendAndAdvance(sample(71, 910))
        job.cancelAndJoin()

        assertEquals(
            listOf(Events.RECORDING_STARTED, Events.CONNECTED, Events.DISCONNECTED, Events.CONNECTED, Events.RECORDING_STOPPED),
            eventNames(),
        )
        assertEquals(listOf("70", "71"), records(FileKind.HR).map { it["hr_bpm"] })
    }

    @Test fun `reconnect retries failed attempts within the 20 s window`() = recorderTest {
        val job = startRecording()
        sensor.connectFailures = 3
        sensor.dropConnection()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1 + 4, sensor.connectCalls.size)        // first connect + 3 failures + success
        assertEquals(RecorderStatus.Recording, recorder.status.value)
        job.cancelAndJoin()
    }

    @Test fun `reconnect gives up after 20 s, ends the session and tells the user`() = recorderTest {
        val job = startRecording()
        sendAndAdvance(sample(70, 900))
        sensor.connectFailures = Int.MAX_VALUE
        sensor.dropConnection()
        advanceTimeBy(timeoutMs - 500)
        runCurrent()
        assertFalse(job.isCompleted, "still trying before 20 s")
        assertEquals(RecorderStatus.Reconnecting, recorder.status.value)

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(StopReason.CONNECTION_LOST, (recorder.status.value as RecorderStatus.Stopped).reason)
        assertEquals(
            listOf(Events.RECORDING_STARTED, Events.CONNECTED, Events.DISCONNECTED, Events.RECONNECT_FAILED, Events.RECORDING_STOPPED),
            eventNames(),
        )
        assertNotNull(sessionInfo().endUtc)
        assertEquals(1, CsvTestUtils.readRecords(File(root, SessionFiles.SESSIONS_INDEX)).size)
        assertEquals(listOf("70"), records(FileKind.HR).map { it["hr_bpm"] })   // data before the drop kept
    }

    @Test fun `a reconnect attempt that hangs is also cut off at 20 s`() = recorderTest {
        val job = startRecording()
        sensor.connectHangs = true
        sensor.dropConnection()
        advanceTimeBy(timeoutMs + 1)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(StopReason.CONNECTION_LOST, (recorder.status.value as RecorderStatus.Stopped).reason)
    }

    @Test fun `beat chain restarts after a reconnect`() = recorderTest {
        val job = startRecording()
        sendAndAdvance(sample(60, 1024))
        sensor.dropConnection()
        clock.advanceSeconds(5.0)
        advanceTimeBy(SessionRecorder.RETRY_DELAY.inWholeMilliseconds + 1)
        runCurrent()
        sendAndAdvance(sample(60, 1024))
        job.cancelAndJoin()
        val last = records(FileKind.RR).last()
        assertEquals(last["received_elapsed_s"], last["elapsed_s"])   // anchored fresh at arrival
        assertFalse(Events.RR_RESYNC in eventNames())                   // reconnect isn't a resync
    }

    // ---- crash ----

    @Test fun `unexpected stream error still runs cleanup (finally block)`() = recorderTest {
        var error: Throwable? = null
        val job = launch { try { recorder.run(config) } catch (e: Throwable) { error = e } }
        runCurrent()
        sendAndAdvance(sample(70, 900))
        sensor.crashStream(IllegalStateException("SDK bug"))
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals("SDK bug", error?.message)
        assertEquals(StopReason.ERROR, (recorder.status.value as RecorderStatus.Stopped).reason)
        assertEquals(listOf(id), sensor.disconnectCalls)
        assertNotNull(sessionInfo().endUtc)
        assertEquals(listOf("70"), records(FileKind.HR).map { it["hr_bpm"] })   // data before the crash kept
    }
}
