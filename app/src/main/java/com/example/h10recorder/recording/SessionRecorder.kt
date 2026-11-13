package com.example.h10recorder.recording

import com.example.h10recorder.data.BeatTimeline
import com.example.h10recorder.data.EventRow
import com.example.h10recorder.data.Events
import com.example.h10recorder.data.FileKind
import com.example.h10recorder.data.HrRow
import com.example.h10recorder.data.Ids
import com.example.h10recorder.data.RrRow
import com.example.h10recorder.data.SessionClock
import com.example.h10recorder.data.SessionExistsException
import com.example.h10recorder.data.SessionFiles
import com.example.h10recorder.data.SessionInfo
import com.example.h10recorder.data.SessionTimeline
import com.example.h10recorder.sensor.HrSample
import com.example.h10recorder.sensor.HrSensor
import com.example.h10recorder.sensor.SensorConnectionLost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class SessionConfig(
    val participantId: String,
    val sessionId: String,
    val deviceId: String,
    val appVersion: String,
    val sdkVersion: String,
    val notes: String = "",
)

/** What the recorder is doing. RecordingService/UI observe this to notify the user. */
sealed interface RecorderStatus {
    data object Idle : RecorderStatus
    data object Connecting : RecorderStatus
    data object Recording : RecorderStatus
    /** Connection dropped; retrying for up to CONNECTION_TIMEOUT. */
    data object Reconnecting : RecorderStatus
    data class Stopped(val reason: StopReason, val message: String? = null) : RecorderStatus
}

enum class StopReason {
    /** Stop pressed. */
    USER,
    /** Could not (re)connect within CONNECTION_TIMEOUT. The user must be notified. */
    CONNECTION_LOST,
    /** Unexpected error; files were still closed. */
    ERROR,
}

/**
 * Records one session.
 *
 * [run]: checks the session doesn't exist yet, connects (max CONNECTION_TIMEOUT; on failure nothing is
 * written), creates the files, then streams HR/RR to CSV and logs events until:
 *  - its coroutine is cancelled (= Stop)                       -> status Stopped(USER)
 *  - a dropped connection can't be restored within CONNECTION_TIMEOUT -> Stopped(CONNECTION_LOST), returns normally
 *  - an unexpected error                                        -> Stopped(ERROR), rethrows
 * Its finally block always stops the stream, disconnects, closes the files, writes endUtc to
 * session.json and appends to sessions.csv. Does no dispatcher switching: the caller runs it on
 * Dispatchers.IO.
 */
class SessionRecorder(
    private val sensor: HrSensor,
    private val files: SessionFiles,
    private val clock: SessionClock,
) {
    private val _liveHr = MutableStateFlow<Int?>(null)
    /** Latest HR for the UI, null when not receiving. */
    val liveHr: StateFlow<Int?> = _liveHr.asStateFlow()

    private val _status = MutableStateFlow<RecorderStatus>(RecorderStatus.Idle)
    val status: StateFlow<RecorderStatus> = _status.asStateFlow()

    /** Open files of the running session; null when not recording. Read by logEvent from the UI thread. */
    @Volatile private var session: OpenSession? = null
    private val running = AtomicBoolean(false)

    private class OpenSession(
        val info: SessionInfo,
        val timeline: SessionTimeline,
        val hr: CsvWriter,
        val rr: CsvWriter,
        val events: CsvWriter,
    ) {
        val writers get() = listOf(hr, rr, events)
    }

    /**
     * Every way out of run() leaves a Stopped status for the UI: Stop -> USER, give-up -> CONNECTION_LOST,
     * anything else (bad IDs, existing session, SDK error, ...) -> ERROR.
     */
    suspend fun run(config: SessionConfig) {
        check(running.compareAndSet(false, true)) { "Recorder already running" }
        try {
            runSession(config)
        } catch (e: CancellationException) {
            if (_status.value !is RecorderStatus.Stopped) _status.value = RecorderStatus.Stopped(StopReason.USER)
            throw e
        } catch (e: Throwable) {
            if (_status.value !is RecorderStatus.Stopped)
                _status.value = RecorderStatus.Stopped(StopReason.ERROR, e.message ?: e.javaClass.simpleName)
            throw e
        } finally {
            running.set(false)
        }
    }

    private suspend fun runSession(config: SessionConfig) {
        _status.value = RecorderStatus.Idle          // clears the previous run's Stopped
        val p = Ids.requireValidParticipant(config.participantId)
        val s = Ids.requireValidSession(config.sessionId)
        require(config.deviceId.isNotBlank()) { "No H10 device ID" }
        files.sessionDir(p, s).let { if (it.exists()) throw SessionExistsException(it) }

        // 1. Connect. Nothing is written if this fails, so failed attempts don't use up a session ID.
        _status.value = RecorderStatus.Connecting
        val connected = try {
            withTimeoutOrNull(CONNECTION_TIMEOUT) { connectWithRetry(config.deviceId, firstDelay = Duration.ZERO) } != null
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { sensor.disconnect(config.deviceId) } }
            throw e.unexpectedCancellationAsError()
        }
        if (!connected) {
            withContext(NonCancellable) { runCatching { sensor.disconnect(config.deviceId) } }
            _status.value = RecorderStatus.Stopped(StopReason.CONNECTION_LOST, "Could not connect to the H10 within $CONNECTION_TIMEOUT")
            return
        }

        // 2. Create the session.
        val open = try {
            openSession(config, p, s)
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { sensor.disconnect(config.deviceId) } }
            throw e
        }
        session = open
        logEvent(Events.RECORDING_STARTED)
        logEvent(Events.CONNECTED)
        _status.value = RecorderStatus.Recording

        // 3. Record until stopped, given up, or crashed. 4. Always clean up.
        var reason = StopReason.USER
        var message: String? = null
        try {
            reason = record(open, config.deviceId)
            if (reason == StopReason.CONNECTION_LOST) message = "H10 connection lost; could not reconnect within $CONNECTION_TIMEOUT"
        } catch (e: Throwable) {
            val err = e.unexpectedCancellationAsError()
            if (err is CancellationException) reason = StopReason.USER
            else { reason = StopReason.ERROR; message = err.message ?: err.javaClass.simpleName }
            throw err
        } finally {
            withContext(NonCancellable) { cleanup(open, config.deviceId, reason, message) }
        }
    }

    /**
     * A CancellationException is a Stop only if *our* coroutine was cancelled. One arriving while we
     * are still active came from somewhere else (e.g. an SDK channel) and must not end the session
     * silently as if Stop was pressed: report it as an error instead.
     */
    private suspend fun Throwable.unexpectedCancellationAsError(): Throwable =
        if (this is CancellationException && currentCoroutineContext().isActive)
            IllegalStateException("Unexpected cancellation: $message", this)
        else this

    /** Streams until the connection can't be restored (returns CONNECTION_LOST). Stop arrives as cancellation. */
    private suspend fun record(open: OpenSession, deviceId: String): StopReason {
        val beats = BeatTimeline()
        while (true) {
            try {
                sensor.hrStream(deviceId).collect { onSample(open, beats, it) }
                throw SensorConnectionLost("HR stream ended")      // a live stream only ends when the link closes
            } catch (e: SensorConnectionLost) {
                _liveHr.value = null
                open.writers.forEach { runCatching { it.flush() } } // don't leave rows in RAM during the gap
                logEvent(Events.DISCONNECTED, e.message.orEmpty())
                _status.value = RecorderStatus.Reconnecting
                beats.reset()                                       // RR chain doesn't carry across a gap
                val back = withTimeoutOrNull(CONNECTION_TIMEOUT) { connectWithRetry(deviceId, firstDelay = RETRY_DELAY) } != null
                if (!back) {
                    logEvent(Events.RECONNECT_FAILED, "gave up after $CONNECTION_TIMEOUT")
                    return StopReason.CONNECTION_LOST
                }
                logEvent(Events.CONNECTED)
                _status.value = RecorderStatus.Recording
            }
        }
    }

    private fun onSample(open: OpenSession, beats: BeatTimeline, sample: HrSample) {
        val now = open.timeline.now()
        open.hr.write(HrRow(now.timeUtc, now.elapsedS, sample.hrBpm, sample.contact).csvFields())
        val result = beats.add(now.elapsedS, sample.rrRaw1024)
        if (result.resynced) logEvent(Events.RR_RESYNC, "beats missing; beat times re-anchored to packet arrival")
        for (b in result.beats) {
            open.rr.write(RrRow(open.timeline.utcAt(b.elapsedS), b.elapsedS, b.rrMs, now.elapsedS).csvFields())
        }
        _liveHr.value = sample.hrBpm
        open.writers.forEach { it.flushIfDue() }   // rr/events may not get a write of their own for a while
    }

    /** Tries connect() until it succeeds. Connection-type failures (IOException) are retried; anything else is thrown. */
    private suspend fun connectWithRetry(deviceId: String, firstDelay: Duration) {
        delay(firstDelay)
        while (true) {
            try {
                sensor.connect(deviceId)
                return
            } catch (e: IOException) {
                delay(RETRY_DELAY)
            }
        }
    }

    private fun openSession(config: SessionConfig, p: String, s: String): OpenSession {
        files.createSessionDir(p, s)
        val timeline = SessionTimeline(clock)
        val info = SessionInfo(
            participantId = p, sessionId = s, deviceId = config.deviceId,
            startUtc = timeline.start.timeUtc, endUtc = null,
            appVersion = config.appVersion, sdkVersion = config.sdkVersion, notes = config.notes,
        )
        files.writeSessionInfo(info)
        val opened = mutableListOf<CsvWriter>()
        try {
            val hr = CsvWriter(files.file(p, s, FileKind.HR), HrRow.HEADER, clock).also(opened::add)
            val rr = CsvWriter(files.file(p, s, FileKind.RR), RrRow.HEADER, clock).also(opened::add)
            val events = CsvWriter(files.file(p, s, FileKind.EVENTS), EventRow.HEADER, clock).also(opened::add)
            return OpenSession(info, timeline, hr, rr, events)
        } catch (e: Throwable) {
            opened.forEach { runCatching { it.close() } }
            throw e
        }
    }

    /**
     * Fixed order (README contract): stop stream + disconnect -> log stop -> close files ->
     * end_utc in session.json -> sessions.csv. Every step runs even if an earlier one fails.
     */
    private suspend fun cleanup(open: OpenSession, deviceId: String, reason: StopReason, message: String?) {
        val failures = mutableListOf<Throwable>()
        fun step(block: () -> Unit) { runCatching(block).onFailure { failures += it } }

        runCatching { sensor.disconnect(deviceId) }.onFailure { failures += it }   // stops the stream, then disconnects
        _liveHr.value = null
        step { logEvent(Events.RECORDING_STOPPED, reason.name.lowercase() + (message?.let { ": $it" } ?: "")) }
        session = null
        step { open.hr.close() }
        step { open.rr.close() }
        step { open.events.close() }
        val finished = open.info.copy(endUtc = open.timeline.now().timeUtc)
        step { files.writeSessionInfo(finished) }
        step { files.appendToSessionsIndex(finished) }

        _status.value = if (failures.isEmpty()) RecorderStatus.Stopped(reason, message)
        else RecorderStatus.Stopped(StopReason.ERROR, "Cleanup failed: ${failures.first().message}")
    }

    /**
     * Event button / marker from the UI. Safe to call from any thread (call it off the main thread:
     * it writes to disk). Events are rare and important, so each one is forced to disk at once.
     * Ignored when no session is open, including while Stop's cleanup is closing the files.
     */
    fun logEvent(event: String, note: String = "") {
        val open = session ?: return
        val now = open.timeline.now()
        open.events.writeIfOpen(EventRow(now.timeUtc, now.elapsedS, event, note).csvFields(), flushNow = true)
    }

    companion object {
        /** Max time for the first connect and for each reconnect before giving up. Knob: see README. */
        val CONNECTION_TIMEOUT: Duration = 20.seconds
        /** Pause between failed reconnect attempts. Knob: see README. */
        val RETRY_DELAY: Duration = 1.seconds
    }
}
