package com.example.h10recorder.sensor

import com.polar.androidcommunications.api.ble.exceptions.BleDisconnected
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.PolarBleApiCallbackProvider
import com.polar.sdk.api.PolarBleDisconnectInfo
import com.polar.sdk.api.PolarBleDisconnectReason
import com.polar.sdk.api.PolarBleRecoveryAction
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarHrData.PolarHrSample
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * PolarSensor against a mocked PolarBleApi. The tests play the SDK's part by invoking the
 * callbacks PolarSensor registered, in the order the SDK documents.
 */
class PolarSensorTest {
    private val id = "B5A3F42C"
    private val api = mockk<PolarBleApi>(relaxed = true)
    private val callback = slot<PolarBleApiCallbackProvider>()
    private val sensor: PolarSensor

    init {
        every { api.setApiCallback(capture(callback)) } returns Unit
        sensor = PolarSensor(api)
    }

    private fun info(deviceId: String = id) = PolarDeviceInfo(deviceId, "A0:9E:1A:00:00:01", -55, "Polar H10 $deviceId", true)
    private fun hrReady(deviceId: String = id) = callback.captured.bleSdkFeatureReady(deviceId, PolarBleSdkFeature.FEATURE_HR)
    private fun dropped(deviceId: String = id) = callback.captured.deviceDisconnected(
        info(deviceId), PolarBleDisconnectInfo(PolarBleDisconnectReason.CONNECTION_LOST, PolarBleRecoveryAction.RETRY_CONNECTION),
    )
    private fun sample(hr: Int, vararg rrs: Int) =
        PolarHrSample(hr, 0, 0, rrs.toList(), rrs.map { it * 1000 / 1024 }, rrs.isNotEmpty(), true, true)

    // ---- setup ----

    @Test fun `registers its callback and turns off SDK auto-reconnect`() {
        assertTrue(callback.isCaptured)
        verify { api.setAutomaticReconnection(false) }
    }

    @Test fun `asks for the HR and battery features only`() {
        assertEquals(setOf(PolarBleSdkFeature.FEATURE_HR, PolarBleSdkFeature.FEATURE_BATTERY_INFO), PolarSensor.FEATURES)
    }

    // ---- connect ----

    @Test fun `connect waits for the HR feature, not just the link`() = runTest {
        val connecting = async { sensor.connect(id) }
        runCurrent()
        verify { api.connectToDevice(id) }
        callback.captured.deviceConnected(info())
        runCurrent()
        assertFalse(connecting.isCompleted, "must not count as connected before FEATURE_HR is ready")
        callback.captured.bleSdkFeatureReady(id, PolarBleSdkFeature.FEATURE_BATTERY_INFO)
        runCurrent()
        assertFalse(connecting.isCompleted)
        hrReady(); runCurrent()
        assertTrue(connecting.isCompleted)
        connecting.await()
    }

    @Test fun `connect returns at once when already connected`() = runTest {
        launch { sensor.connect(id) }; runCurrent(); hrReady(); runCurrent()
        sensor.connect(id)
        verify(exactly = 1) { api.connectToDevice(id) }
    }

    @Test fun `connect fails if the link drops while connecting`() = runTest {
        val connecting = async { runCatching { sensor.connect(id) } }
        runCurrent()
        dropped(); runCurrent()
        assertIs<SensorConnectionLost>(connecting.await().exceptionOrNull())
    }

    @Test fun `a timed out connect abandons the attempt`() = runTest {
        assertNull(withTimeoutOrNull(20_000) { sensor.connect(id) })
        verify { api.disconnectFromDevice(id) }
    }

    @Test fun `connect propagates SDK errors such as an invalid id`() = runTest {
        every { api.connectToDevice("bad") } throws IllegalArgumentException("invalid id")
        assertFailsWith<IllegalArgumentException> { sensor.connect("bad") }
    }

    // ---- events ----

    @Test fun `emits Connected once and Disconnected once with the reason`() = runTest {
        val events = mutableListOf<SensorEvent>()
        backgroundScope.launch { sensor.events.collect { events += it } }
        runCurrent()
        hrReady(); hrReady()                       // duplicate callback
        dropped()
        @Suppress("DEPRECATION") callback.captured.deviceDisconnected(info())   // SDK may call both overloads
        runCurrent()
        assertEquals(
            listOf(SensorEvent.Connected(id), SensorEvent.Disconnected(id, "CONNECTION_LOST/RETRY_CONNECTION")),
            events,
        )
    }

    @Test fun `after a drop, connect has to connect again`() = runTest {
        launch { sensor.connect(id) }; runCurrent(); hrReady(); runCurrent()
        dropped()
        val again = async { sensor.connect(id) }
        runCurrent()
        assertFalse(again.isCompleted)
        verify(exactly = 2) { api.connectToDevice(id) }
        hrReady(); runCurrent()
        again.await()
    }

    // ---- search ----

    @Test fun `search filters by H10 name and maps device info`() = runTest {
        every { api.searchForDevice("Polar H10") } returns flowOf(info())
        assertEquals(listOf(FoundDevice(id, "Polar H10 $id", -55)), sensor.search().toList())
    }

    // ---- streaming ----

    @Test fun `hrStream flattens SDK packets in order`() = runTest {
        every { api.startHrStreaming(id) } returns flow {
            emit(PolarHrData(listOf(sample(70, 878))))
            emit(PolarHrData(listOf(sample(71), sample(72, 840, 856))))
            throw BleDisconnected()
        }
        val got = mutableListOf<HrSample>()
        assertFailsWith<SensorConnectionLost> { sensor.hrStream(id).collect { got += it } }
        assertEquals(listOf(70, 71, 72), got.map { it.hrBpm })
        assertEquals(listOf(listOf(878), emptyList(), listOf(840, 856)), got.map { it.rrRaw1024 })
    }

    @Test fun `stream ending on its own counts as a lost connection`() = runTest {
        every { api.startHrStreaming(id) } returns flowOf(PolarHrData(listOf(sample(70))))
        assertFailsWith<SensorConnectionLost> { sensor.hrStream(id).collect {} }
    }

    @Test fun `unexpected SDK errors are passed through unchanged`() = runTest {
        val bug = IllegalStateException("SDK bug")
        every { api.startHrStreaming(id) } returns flow { throw bug }
        assertSame(bug, assertFailsWith<IllegalStateException> { sensor.hrStream(id).collect {} })
    }

    // ---- battery / disconnect ----

    @Test fun `battery level, with -1 and errors mapped to null`() = runTest {
        every { api.getBatteryLevel(id) } returns 87
        assertEquals(87, sensor.batteryLevel(id))
        every { api.getBatteryLevel(id) } returns -1
        assertNull(sensor.batteryLevel(id))
        every { api.getBatteryLevel(id) } throws IllegalStateException("not connected")
        assertNull(sensor.batteryLevel(id))
    }

    @Test fun `disconnect stops the HR stream explicitly then disconnects`() = runTest {
        launch { sensor.connect(id) }; runCurrent(); hrReady(); runCurrent()
        sensor.disconnect(id)
        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            api.stopHrStreaming(id)
            api.disconnectFromDevice(id)
        }
    }

    @Test fun `disconnect skips the blocking stop call when the link is already gone`() = runTest {
        sensor.disconnect(id)                       // never connected / already dropped
        io.mockk.coVerify(exactly = 0) { api.stopHrStreaming(id) }
        verify { api.disconnectFromDevice(id) }
    }

    @Test fun `disconnect still disconnects when stopping the stream fails`() = runTest {
        launch { sensor.connect(id) }; runCurrent(); hrReady(); runCurrent()
        io.mockk.coEvery { api.stopHrStreaming(id) } throws IllegalStateException("already stopped")
        sensor.disconnect(id)
        verify { api.disconnectFromDevice(id) }
    }
}
