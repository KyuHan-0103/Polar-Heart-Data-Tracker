package com.example.h10recorder.review

import com.example.h10recorder.data.Events
import com.example.h10recorder.data.FileKind
import com.example.h10recorder.data.SessionFiles
import com.example.h10recorder.recording.RecorderStatus
import com.example.h10recorder.recording.RecordingNotifications
import com.example.h10recorder.recording.SessionConfig
import com.example.h10recorder.recording.SessionRecorder
import com.example.h10recorder.sensor.PolarSensor
import com.example.h10recorder.sensor.SensorConnectionLost
import com.example.h10recorder.testutil.CsvTestUtils
import com.example.h10recorder.testutil.FakeClock
import com.polar.androidcommunications.api.ble.model.gatt.BleGattTxInterface
import com.polar.androidcommunications.common.ble.AtomicSet
import com.polar.androidcommunications.common.ble.ChannelUtils
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.PolarBleApiCallbackProvider
import com.polar.sdk.api.PolarBleDisconnectInfo
import com.polar.sdk.api.PolarBleDisconnectReason
import com.polar.sdk.api.PolarBleRecoveryAction
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarHrData.PolarHrSample
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PolarSensor against the HR stream as the REAL Polar SDK 8.5.0 builds it.
 *
 * In 8.5.0, BDBleApiImpl.startHrStreaming() returns BleHrClient.observeHrNotifications(), which is
 * ChannelUtils.monitorNotifications(hrObserverAtomicList, ...). On a BLE link loss the SDK calls
 * BleHrClient.reset() -> ChannelUtils.postDisconnectedAndClearList(hrObserverAtomicList), which
 * CANCELS each observer channel with CancellationException("Channel closed due to error",
 * cause = BleDisconnected()). (Verified in the 8.5.0 classes.jar bytecode.)
 *
 * These tests use the SDK's own ChannelUtils/AtomicSet classes from the AAR, so the stream fails
 * exactly the way it does on a phone when the strap is unclipped.
 */
class ReviewSdkDisconnectTest {
    @get:Rule val tmp = TemporaryFolder()

    private val id = "B5A3F42C"
    private val api = mockk<PolarBleApi>(relaxed = true)
    private val callback = slot<PolarBleApiCallbackProvider>()
    private val observers = AtomicSet<Channel<PolarHrSample>>()           // BleHrClient.hrObserverAtomicList
    private val tx = mockk<BleGattTxInterface>(relaxed = true).also { every { it.isConnected() } returns true }
    private val sensor: PolarSensor

    init {
        every { api.setApiCallback(capture(callback)) } returns Unit
        // Same shape as BDBleApiImpl.startHrStreaming: observeHrNotifications(true).map { PolarHrData(listOf(it)) }
        every { api.startHrStreaming(id) } answers {
            ChannelUtils.monitorNotifications(observers, tx, true).map { PolarHrData(listOf(it)) }
        }
        sensor = PolarSensor(api)
    }

    private fun sample(hr: Int, vararg rrs: Int) =
        PolarHrSample(hr, 0, 0, rrs.toList(), rrs.map { it * 1000 / 1024 }, rrs.isNotEmpty(), true, true)

    /** BleHrClient.processServiceData -> ChannelUtils.emitNext(...) { it.trySend(..) } */
    private fun notify(s: PolarHrSample) = ChannelUtils.emitNext(observers) { it.trySend(s) }

    /** What BleHrClient.reset() does when the BLE link drops. */
    private fun linkLost() = ChannelUtils.postDisconnectedAndClearList(observers)

    private fun info() = PolarDeviceInfo(id, "A0:9E:1A:00:00:01", -55, "Polar H10 $id", true)

    @Test fun `hrStream reports a real SDK link loss as SensorConnectionLost`() = runTest {
        val got = mutableListOf<Int>()
        val outcome = async { runCatching { sensor.hrStream(id).collect { got += it.hrBpm } } }
        runCurrent()
        notify(sample(70, 878)); runCurrent()
        linkLost(); runCurrent()

        assertEquals(listOf(70), got)
        val e = outcome.await().exceptionOrNull()
        assertIs<SensorConnectionLost>(e, "README contract: a dropped connection throws SensorConnectionLost, but got: $e (cause ${e?.cause})")
    }

    @Test fun `recorder treats a real SDK link loss as a disconnect and starts reconnecting`() = runTest {
        val clock = FakeClock(scheduler = testScheduler)
        val files = SessionFiles(tmp.newFolder("H10_Data"))
        val recorder = SessionRecorder(sensor, files, clock)
        val config = SessionConfig("P001", "01", id, appVersion = "0.1.0", sdkVersion = "8.5.0")

        val job = launch { runCatching { recorder.run(config) } }
        runCurrent()
        callback.captured.bleSdkFeatureReady(id, PolarBleSdkFeature.FEATURE_HR); runCurrent()
        assertEquals(RecorderStatus.Recording, recorder.status.value)

        clock.advanceSeconds(1.0); notify(sample(70, 878)); runCurrent()

        // Strap unclipped: SDK cancels the HR observer channels, then reports deviceDisconnected.
        linkLost()
        callback.captured.deviceDisconnected(info(),
            PolarBleDisconnectInfo(PolarBleDisconnectReason.CONNECTION_LOST, PolarBleRecoveryAction.RETRY_CONNECTION))
        runCurrent()

        val status = recorder.status.value
        val events = CsvTestUtils.readRecords(files.file("P001", "01", FileKind.EVENTS)).map { it["event"] }
        job.cancelAndJoin()
        assertTrue(Events.DISCONNECTED in events, "no 'disconnected' event; events=$events, status=$status")
        assertEquals(RecorderStatus.Reconnecting, status,
            "strap drop ended the session as if Stop was pressed; RecordingService alert would be " +
                "${RecordingNotifications.alertText(status)}")
    }

    @Test fun `sanity - the SDK stream fails with CancellationException wrapping BleDisconnected`() = runTest {
        val outcome = async { runCatching { api.startHrStreaming(id).collect { } } }
        runCurrent(); linkLost(); runCurrent()
        val e = assertNotNull(outcome.await().exceptionOrNull())
        assertIs<kotlinx.coroutines.CancellationException>(e)
        val chain = generateSequence(e.cause) { it.cause }.toList()
        assertTrue(chain.any { it is com.polar.androidcommunications.api.ble.exceptions.BleDisconnected }, "cause chain: $chain")
    }
}
