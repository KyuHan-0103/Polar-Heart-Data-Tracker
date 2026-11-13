package com.example.h10recorder.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.example.h10recorder.data.SessionFiles
import com.example.h10recorder.data.SystemSessionClock
import com.example.h10recorder.sensor.PolarSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Foreground service that runs one SessionRecorder, so recording survives the screen turning off
 * and the app being in the background. Start/stop it with [start] and [stop]. The UI watches
 * [activeRecorder] for live HR and status.
 *
 * Before calling [start] the UI must hold BLUETOOTH_SCAN/BLUETOOTH_CONNECT (Android 14 refuses a
 * "connectedDevice" foreground service otherwise) and should hold POST_NOTIFICATIONS so the
 * connection-lost alert can be shown.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var sensor: PolarSensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var lastOngoingText = "starting…"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> {
                val running = job
                if (running?.isActive == true) running.cancel()   // recorder's finally block does the cleanup
                else stopSelf()                                     // nothing to stop: don't linger
            }
        }
        // Don't restart automatically after the system kills the service: a new run would need a new session ID.
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        createChannels()
        // Every startForegroundService() must be answered by startForeground() within seconds, or
        // Android crashes the app. So answer first, even if this start is then ignored.
        startForeground(ONGOING_ID, ongoing(lastOngoingText), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (job?.isActive == true) return        // one recording at a time (also while the last one is cleaning up)
        val config = SessionConfig(
            participantId = intent.getStringExtra(EXTRA_PARTICIPANT).orEmpty(),
            sessionId = intent.getStringExtra(EXTRA_SESSION).orEmpty(),
            deviceId = intent.getStringExtra(EXTRA_DEVICE).orEmpty(),
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown",
            sdkVersion = PolarSensor.SDK_VERSION,
            notes = intent.getStringExtra(EXTRA_NOTES).orEmpty(),
        )
        showOngoing(RecordingNotifications.ongoingText(RecorderStatus.Connecting, null, config))
        acquireWakeLock()

        val s = sensor ?: PolarSensor.create(this).also { sensor = it }
        val recorder = SessionRecorder(s, SessionFiles(dataRoot(this)), SystemSessionClock)
        _activeRecorder.value = recorder

        job = scope.launch {
            superviseRecording(
                record = { recorder.run(config) },
                notify = {
                    combine(recorder.status, recorder.liveHr) { st, hr -> RecordingNotifications.ongoingText(st, hr, config) }
                        .collect { showOngoing(it) }
                },
                finish = {
                    RecordingNotifications.alertText(recorder.status.value)?.let(::alert)
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                },
            )
        }
    }

    override fun onDestroy() {
        // Normally the job has already finished. If the system destroys us mid-recording, cancel it and
        // give the recorder's cleanup a moment to close the files before the SDK is shut down.
        val running = job
        if (running?.isActive == true) {
            running.cancel()
            runBlocking { withTimeoutOrNull(CLEANUP_WAIT_MS) { running.join() } }
        }
        sensor?.shutDown()
        sensor = null
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ---- notifications ----

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun showOngoing(text: String) {
        lastOngoingText = text
        notificationManager().notify(ONGOING_ID, ongoing(text))
    }

    private fun createChannels() = notificationManager().createNotificationChannels(listOf(
        NotificationChannel(CHANNEL_ONGOING, "Recording", NotificationManager.IMPORTANCE_LOW),
        NotificationChannel(CHANNEL_ALERT, "Recording stopped", NotificationManager.IMPORTANCE_HIGH)
            .apply { enableVibration(true) },
    ))

    private fun ongoing(text: String): Notification = Notification.Builder(this, CHANNEL_ONGOING)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("H10 recording")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun alert(text: String) = notificationManager().notify(ALERT_ID, Notification.Builder(this, CHANNEL_ALERT)
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentTitle("Recording stopped")
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .build())

    // ---- wake lock ----
    // A foreground service keeps the app alive but not the CPU awake. Without a wake lock, the
    // reconnect timers (1 s retry, 20 s give-up) can be delayed while the phone sleeps.

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "H10Recorder:recording")
            .apply { acquire(MAX_RECORDING_MS) }   // safety cap so a bug can't hold it forever
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.example.h10recorder.START"
        const val ACTION_STOP = "com.example.h10recorder.STOP"
        const val EXTRA_PARTICIPANT = "participant_id"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_DEVICE = "device_id"
        const val EXTRA_NOTES = "notes"

        private const val CHANNEL_ONGOING = "recording"
        private const val CHANNEL_ALERT = "recording_stopped"
        private const val ONGOING_ID = 1
        private const val ALERT_ID = 2
        /** Wake lock safety cap. Knob: see README. */
        const val MAX_RECORDING_MS = 12 * 60 * 60 * 1000L
        private const val CLEANUP_WAIT_MS = 3_000L

        private val _activeRecorder = MutableStateFlow<SessionRecorder?>(null)
        /** The recorder of the current (or last) recording, for the UI. */
        val activeRecorder: StateFlow<SessionRecorder?> = _activeRecorder.asStateFlow()

        /** H10_Data/ in the app's own storage. Note: Android deletes this folder if the app is uninstalled. */
        fun dataRoot(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "H10_Data")

        fun start(context: Context, participantId: String, sessionId: String, deviceId: String, notes: String = "") {
            context.startForegroundService(Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PARTICIPANT, participantId)
                .putExtra(EXTRA_SESSION, sessionId)
                .putExtra(EXTRA_DEVICE, deviceId)
                .putExtra(EXTRA_NOTES, notes))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}

/**
 * The service's recording job, without Android, so it is unit-tested. Runs [record] with [notify]
 * alongside, then always runs [finish] once, after [notify] has fully stopped, however [record]
 * ended (finished, error, or Stop cancelling this coroutine). Errors from [record] are swallowed:
 * the recorder has already cleaned up and its status says what happened.
 */
internal suspend fun superviseRecording(
    record: suspend () -> Unit,
    notify: suspend () -> Unit,
    finish: () -> Unit,
) = coroutineScope {
    val notifier = launch { notify() }
    try {
        record()
    } catch (e: Throwable) {
        // status already says Stopped(ERROR/USER/CONNECTION_LOST)
    } finally {
        // NonCancellable: after Stop this coroutine is cancelled, and a plain suspend call here
        // (cancelAndJoin) would throw and skip the rest, leaving the service and wake lock alive.
        withContext(NonCancellable) {
            notifier.cancelAndJoin()   // no late update may re-post the notification after it's removed
            finish()
        }
    }
}

/** Notification wording. Plain Kotlin (no Android) so it is unit-tested on the JVM. */
object RecordingNotifications {
    fun ongoingText(status: RecorderStatus, liveHr: Int?, config: SessionConfig): String {
        val who = "sub-${config.participantId} ses-${config.sessionId}"
        return when (status) {
            RecorderStatus.Idle, RecorderStatus.Connecting -> "$who · connecting to H10…"
            RecorderStatus.Recording -> "$who · " + (liveHr?.let { "$it bpm" } ?: "waiting for heart rate…")
            RecorderStatus.Reconnecting -> "$who · connection lost, reconnecting…"
            is RecorderStatus.Stopped -> "$who · stopped"
        }
    }

    /** Text of the "recording stopped" alert, or null when no alert is needed (user pressed Stop). */
    fun alertText(status: RecorderStatus): String? = when {
        status !is RecorderStatus.Stopped -> null
        status.reason == StopReason.USER -> null
        status.reason == StopReason.CONNECTION_LOST ->
            "Lost the H10 connection and couldn't reconnect within ${SessionRecorder.CONNECTION_TIMEOUT}. " +
                "Data up to the drop is saved. Check the strap is worn and nearby, then start a new session."
        else -> "Recording stopped because of an error: ${status.message ?: "unknown"}. Data recorded so far is saved."
    }
}
