package com.example.h10recorder.testutil

import com.example.h10recorder.sensor.FoundDevice
import com.example.h10recorder.sensor.HrSample
import com.example.h10recorder.sensor.HrSensor
import com.example.h10recorder.sensor.SensorConnectionLost
import com.example.h10recorder.sensor.SensorEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import java.io.IOException

/**
 * In-memory H10 that mimics how the Polar SDK behaves:
 *  - hrStream() only works while connected
 *  - a dropped connection makes the stream fail (SensorConnectionLost) and emits Disconnected
 *  - cancelling the collector stops the stream
 * Records every call so tests can assert on the sensor's side of the conversation.
 */
class FakeHrSensor(
    private val nearby: List<FoundDevice> = listOf(H10Fixtures.DEVICE),
) : HrSensor {
    private val _events = MutableSharedFlow<SensorEvent>(extraBufferCapacity = 64)
    override val events: Flow<SensorEvent> = _events

    val connectCalls = mutableListOf<String>()
    val disconnectCalls = mutableListOf<String>()
    var streamStartCount = 0; private set

    /** Number of upcoming connect() calls that should fail. */
    var connectFailures = 0
    /** connect() never completes (strap out of range); only a timeout/cancel ends it. */
    var connectHangs = false
    var battery: Int? = 87

    var connectedId: String? = null; private set
    val isStreaming: Boolean get() = channel != null
    private var channel: Channel<HrSample>? = null

    override fun search(): Flow<FoundDevice> = nearby.asFlow()

    override suspend fun connect(deviceId: String) {
        connectCalls += deviceId
        if (connectHangs) awaitCancellation()
        if (connectFailures > 0) {
            connectFailures--
            throw IOException("fake: connect failed")
        }
        connectedId = deviceId
        _events.emit(SensorEvent.Connected(deviceId))
    }

    override fun hrStream(deviceId: String): Flow<HrSample> = flow {
        check(connectedId == deviceId) { "fake: hrStream($deviceId) while not connected" }
        val ch = Channel<HrSample>(Channel.UNLIMITED)
        channel = ch
        streamStartCount++
        try {
            for (sample in ch) emit(sample)
        } finally {
            if (channel === ch) channel = null
        }
    }

    override suspend fun batteryLevel(deviceId: String): Int? = battery

    override suspend fun disconnect(deviceId: String) {
        disconnectCalls += deviceId
        connectedId = null
        channel?.close()
        channel = null
    }

    // ---- test controls ----

    /** Deliver one HR packet to whoever is streaming. */
    fun send(sample: HrSample) {
        val ch = checkNotNull(channel) { "fake: nobody is streaming" }
        ch.trySend(sample).getOrThrow()
    }

    fun sendAll(samples: List<HrSample>) = samples.forEach(::send)

    /** Strap unclipped / out of range: stream fails, Disconnected is emitted. */
    suspend fun dropConnection() {
        val id = checkNotNull(connectedId) { "fake: not connected" }
        connectedId = null
        channel?.close(SensorConnectionLost("fake: connection lost"))
        channel = null
        _events.emit(SensorEvent.Disconnected(id, "fake: strap unclipped"))
    }

    /** Stream fails with an unexpected (non-connection) error, e.g. an SDK bug. */
    fun crashStream(error: Throwable) {
        checkNotNull(channel) { "fake: nobody is streaming" }.close(error)
        channel = null
    }
}
