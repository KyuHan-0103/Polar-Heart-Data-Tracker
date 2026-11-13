package com.example.h10recorder.sensor

import android.content.Context
import com.polar.androidcommunications.api.ble.exceptions.BleDisconnected
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.PolarBleDisconnectInfo
import com.polar.sdk.api.errors.PolarDeviceDisconnected
import com.polar.sdk.api.errors.PolarDeviceNotConnected
import com.polar.sdk.api.errors.PolarDeviceNotFound
import com.polar.sdk.api.errors.PolarNotificationNotEnabled
import com.polar.sdk.api.errors.PolarServiceNotAvailable
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarHrData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * The only file that uses the Polar SDK. Turns the SDK's callback-style connection API into the
 * suspend/Flow [HrSensor] interface.
 *
 * Connection flow in the SDK: connectToDevice() returns at once, then the callbacks
 * deviceConnected -> bleSdkFeatureReady(FEATURE_HR) arrive. We count a device as connected only
 * once FEATURE_HR is ready, because streaming before that fails.
 */
class PolarSensor(private val api: PolarBleApi) : HrSensor {

    private val lock = Any()
    private val ready = mutableSetOf<String>()                                // HR feature ready
    private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()   // connect() waiting

    private val _events = MutableSharedFlow<SensorEvent>(extraBufferCapacity = 64)
    override val events: Flow<SensorEvent> = _events.asSharedFlow()

    init {
        // SessionRecorder owns reconnection (gives up after 20 s and tells the user). The SDK's own
        // auto-reconnect would keep trying after that, holding one of the H10's two connection slots.
        api.setAutomaticReconnection(false)
        api.setApiCallback(Callback())
    }

    private inner class Callback : PolarBleApiCallback() {
        override fun bleSdkFeatureReady(identifier: String, feature: PolarBleSdkFeature) {
            if (feature != PolarBleSdkFeature.FEATURE_HR) return
            val newlyReady = synchronized(lock) {
                pending.remove(identifier)?.complete(Unit)
                ready.add(identifier)
            }
            if (newlyReady) _events.tryEmit(SensorEvent.Connected(identifier))
        }

        override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo, info: PolarBleDisconnectInfo) =
            onDisconnected(polarDeviceInfo.deviceId, "${info.reason}/${info.recoveryAction}")

        // Older overload; the SDK may call either or both. onDisconnected de-duplicates.
        @Deprecated("SDK deprecated overload")
        override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) =
            onDisconnected(polarDeviceInfo.deviceId, null)

        // Abstract in the published 8.5.0 PolarBleApiCallback; not used by this app.
        override fun disInformationReceived(identifier: String, disInfo: DisInfo) {}
        override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) {}
    }

    private fun onDisconnected(deviceId: String, reason: String?) {
        val wasReady = synchronized(lock) {
            pending.remove(deviceId)?.completeExceptionally(
                SensorConnectionLost("Disconnected while connecting to $deviceId" + (reason?.let { " ($it)" } ?: ""))
            )
            ready.remove(deviceId)
        }
        if (wasReady) _events.tryEmit(SensorEvent.Disconnected(deviceId, reason))
    }

    override fun search(): Flow<FoundDevice> =
        api.searchForDevice(H10_NAME_PREFIX).map { FoundDevice(it.deviceId, it.name, it.rssi) }

    override suspend fun connect(deviceId: String) {
        val waiter = synchronized(lock) {
            if (deviceId in ready) return
            pending.getOrPut(deviceId) { CompletableDeferred() }
        }
        try {
            api.connectToDevice(deviceId)
            waiter.await()
        } catch (e: CancellationException) {
            // Timed out or stopped: don't leave the SDK connecting in the background.
            runCatching { api.disconnectFromDevice(deviceId) }
            throw e
        } finally {
            synchronized(lock) { if (pending[deviceId] === waiter) pending.remove(deviceId) }
        }
    }

    override fun hrStream(deviceId: String): Flow<HrSample> = flow {
        api.startHrStreaming(deviceId).collect { data ->
            for (sample in data.samples) emit(sample.toHrSample())
        }
        // The SDK's HR flow only ends on its own when the connection closes.
        throw SensorConnectionLost("HR stream from $deviceId ended")
    }.catch { e ->
        // .catch only sees errors from upstream (the SDK), and never the cancellation of our own
        // coroutine (Stop). So any CancellationException here came from the SDK: on link loss the SDK
        // 8.5.0 *cancels* its HR channel (CancellationException caused by BleDisconnected) rather
        // than throwing BleDisconnected. Found by the independent review (ReviewSdkDisconnectTest).
        throw when {
            e is SensorConnectionLost -> e
            e.isConnectionLoss() -> SensorConnectionLost("Connection to $deviceId lost", e)
            else -> e
        }
    }

    override suspend fun batteryLevel(deviceId: String): Int? =
        runCatching { api.getBatteryLevel(deviceId) }.getOrNull()?.takeIf { it in 0..100 } // SDK returns -1 if unknown

    override suspend fun disconnect(deviceId: String) {
        // Stop the stream explicitly before disconnecting (SPEC: always stop streams explicitly).
        // Skipped when the link is already gone: no stream can be running, and the SDK's
        // stopHrStreaming would block up to 10 s waiting for a service that isn't there.
        val linkUp = synchronized(lock) { deviceId in ready }
        if (linkUp) runCatching { api.stopHrStreaming(deviceId) }
        runCatching { api.disconnectFromDevice(deviceId) }
        onDisconnected(deviceId, "DISCONNECT_REQUESTED")
    }

    /** Releases the SDK. Call once when the app no longer needs the sensor (e.g. service destroyed). */
    fun shutDown() = api.shutDown()

    companion object {
        const val H10_NAME_PREFIX = "Polar H10"
        val FEATURES = setOf(PolarBleSdkFeature.FEATURE_HR, PolarBleSdkFeature.FEATURE_BATTERY_INFO)
        val SDK_VERSION: String get() = PolarBleApiDefaultImpl.versionInfo()

        fun create(context: Context): PolarSensor =
            PolarSensor(PolarBleApiDefaultImpl.defaultImplementation(context.applicationContext, FEATURES))
    }
}

private fun Throwable.isConnectionLoss(): Boolean =
    this is CancellationException ||
        this is BleDisconnected || this is PolarDeviceDisconnected || this is PolarDeviceNotConnected ||
        // startHrStreaming waits up to 10 s for GATT service discovery and then throws these: worth a reconnect.
        this is PolarServiceNotAvailable || this is PolarNotificationNotEnabled || this is PolarDeviceNotFound

/** SDK sample -> app sample. Uses raw 1/1024 s RRs (SDK's rrsMs is rounded to whole ms). */
internal fun PolarHrData.PolarHrSample.toHrSample(): HrSample = HrSample(
    hrBpm = hr,
    rrRaw1024 = if (rrAvailable) rrs else emptyList(),
    contact = if (contactStatusSupported) contactStatus else null,
)
