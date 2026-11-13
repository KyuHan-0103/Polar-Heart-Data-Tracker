package com.example.h10recorder.sensor

import kotlinx.coroutines.flow.Flow
import java.io.IOException

/** One HR packet, independent of the Polar SDK types. rrRaw1024 = RR intervals in 1/1024 s. */
data class HrSample(val hrBpm: Int, val rrRaw1024: List<Int>, val contact: Boolean?)

data class FoundDevice(val deviceId: String, val name: String, val rssi: Int)

sealed interface SensorEvent {
    val deviceId: String
    /** Connected and ready to stream HR. */
    data class Connected(override val deviceId: String) : SensorEvent
    /** A connection that was ready has dropped. reason is diagnostic text (e.g. "CONNECTION_LOST/NONE"), may be null. */
    data class Disconnected(override val deviceId: String, val reason: String? = null) : SensorEvent
}

/** The connection to the strap dropped (unclipped, out of range, Bluetooth off). Recoverable by reconnecting. */
class SensorConnectionLost(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * What the app needs from a heart-rate sensor. PolarSensor is the real implementation (the only
 * code that touches the Polar SDK); tests use FakeHrSensor.
 */
interface HrSensor {
    /** Connection changes, for UI status. */
    val events: Flow<SensorEvent>

    /** Nearby H10s (advertised name "Polar H10 <device id>"). Runs until cancelled. */
    fun search(): Flow<FoundDevice>

    /**
     * Suspends until connected and ready to stream. Has no timeout of its own: callers wrap it
     * in withTimeout. Cancelling it abandons the attempt. Throws if the attempt fails.
     */
    suspend fun connect(deviceId: String)

    /**
     * HR packets. Throws [SensorConnectionLost] when the connection drops; any other exception is
     * an unexpected error. Cancelling the collector stops the stream.
     */
    fun hrStream(deviceId: String): Flow<HrSample>

    /** 0-100, or null when unknown. */
    suspend fun batteryLevel(deviceId: String): Int?

    /** Stops streaming and disconnects. Safe to call when already disconnected. */
    suspend fun disconnect(deviceId: String)
}
