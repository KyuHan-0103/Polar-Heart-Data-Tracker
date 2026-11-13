package com.example.h10recorder.review

import com.example.h10recorder.data.BeatTimeline
import com.example.h10recorder.data.Events
import com.example.h10recorder.data.FileKind
import com.example.h10recorder.data.SessionFiles
import com.example.h10recorder.recording.RecorderStatus
import com.example.h10recorder.recording.SessionConfig
import com.example.h10recorder.recording.SessionRecorder
import com.example.h10recorder.recording.StopReason
import com.example.h10recorder.sensor.PolarSensor
import com.example.h10recorder.testutil.CsvTestUtils
import com.example.h10recorder.testutil.FakeClock
import com.polar.androidcommunications.api.ble.model.gatt.BleGattTxInterface
import com.polar.androidcommunications.common.ble.AtomicSet
import com.polar.androidcommunications.common.ble.ChannelUtils
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.PolarBleApiCallbackProvider
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarHrData.PolarHrSample
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Re-verification of the fixes for the first review, plus regressions they introduced. */
class ReviewFollowUpTest {
    @get:Rule val tmp = TemporaryFolder()

    private val id = "B5A3F42C"
    private val api = mockk<PolarBleApi>(relaxed = true)
    private val callback = slot<PolarBleApiCallbackProvider>()
    private val observers = AtomicSet<Channel<PolarHrSample>>()
    private val tx = mockk<BleGattTxInterface>(relaxed = true).also { every { it.isConnected() } returns true }
    private val sensor: PolarSensor

    init {
        every { api.setApiCallback(capture(callback)) } returns Unit
        every { api.startHrStreaming(id) } answers {
            ChannelUtils.monitorNotifications(observers, tx, true).map { PolarHrData(listOf(it)) }
        }
        sensor = PolarSensor(api)
    }

    private fun sample(hr: Int, vararg rrs: Int) =
        PolarHrSample(hr, 0, 0, rrs.toList(), rrs.map { it * 1000 / 1024 }, rrs.isNotEmpty(), true, true)

    // ---- F1 re-check: pressing Stop while the REAL SDK-shaped stream is running ----

    @Test fun `Stop during a live SDK stream is a clean Stop, not a disconnect`() = runTest {
        val clock = FakeClock(scheduler = testScheduler)
        val files = SessionFiles(tmp.newFolder("H10_Data"))
        val recorder = SessionRecorder(sensor, files, clock)
        val statuses = mutableListOf<RecorderStatus>()
        backgroundScope.launch { recorder.status.collect { statuses += it } }
        val job = launch { runCatching { recorder.run(SessionConfig("P001", "01", id, "0.1.0", "8.5.0")) } }
        runCurrent()
        callback.captured.bleSdkFeatureReady(id, PolarBleSdkFeature.FEATURE_HR); runCurrent()
        clock.advanceSeconds(1.0); ChannelUtils.emitNext(observers) { it.trySend(sample(70, 878)) }; runCurrent()

        job.cancelAndJoin()                                                     // Stop

        val events = CsvTestUtils.readRecords(files.file("P001", "01", FileKind.EVENTS)).map { it["event"] }
        assertEquals(RecorderStatus.Stopped(StopReason.USER), recorder.status.value)
        assertEquals(listOf(Events.RECORDING_STARTED, Events.CONNECTED, Events.RECORDING_STOPPED), events,
            "statuses seen: $statuses")
        assertTrue(RecorderStatus.Reconnecting !in statuses, "Stop showed 'reconnecting': $statuses")
    }

    // ---- F8 re-check: RecordingService's job (superviseRecording is the exact code the service runs) ----

    @Test fun `service finally block still runs its cleanup after ACTION_STOP cancels the job`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var wakeLockReleased = false
        var stopSelfCalled = false
        val job = scope.launch {
            com.example.h10recorder.recording.superviseRecording(
                record = { awaitCancellation() },                      // recorder.run(config)
                notify = { awaitCancellation() },                      // combine(...).collect { showOngoing(it) }
                finish = { wakeLockReleased = true; stopSelfCalled = true },
            )
        }
        yield(); kotlinx.coroutines.delay(50)
        job.cancel()                                                   // ACTION_STOP -> job.cancel()
        withTimeout(2_000) { job.join() }
        scope.cancel()
        assertTrue(wakeLockReleased && stopSelfCalled,
            "cancelAndJoin() in a cancelled coroutine throws CancellationException: wake lock kept, service never stopped")
    }

    // ---- F5 re-check: beats missing because the strap lost skin contact (packets keep coming, no RRs) ----

    @Test fun `the first beat after a no-RR gap is not placed a whole gap too early`() {
        val bt = BeatTimeline()
        for (k in 1..60) bt.add(k.toDouble(), listOf(1024))              // 60 bpm, zero latency
        for (k in 61..68) bt.add(k.toDouble(), emptyList())               // 8 s without RRs (poor contact)
        val first = bt.add(69.0, listOf(1024)).beats.single().elapsedS    // true beat time 69.0
        val second = bt.add(70.0, listOf(1024)).beats.single().elapsedS   // true beat time 70.0
        assertEquals(70.0, second, 0.01)
        assertTrue(abs(first - 69.0) < 0.5, "first beat after the gap placed at $first s, true 69.0 s")
    }
}

private fun CoroutineScope.cancel() = coroutineContext[kotlinx.coroutines.Job]?.cancel()
