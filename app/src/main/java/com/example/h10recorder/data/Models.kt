package com.example.h10recorder.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant

/** Anything that becomes one CSV line. */
interface CsvRow {
    fun csvFields(): List<String>
}

/** CSV formatting shared by CsvWriter and sessions.csv: RFC 4180 quoting, "\n" line endings. */
object Csv {
    /** Quotes a field only if it contains a comma, quote, CR or LF; inner quotes are doubled. */
    fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\""
        else field

    fun line(fields: List<String>): String = fields.joinToString(",") { escape(it) } + "\n"
}

/** One heart-rate packet (~1 Hz). contact = null when the sensor doesn't report contact. */
data class HrRow(val timeUtc: Instant, val elapsedS: Double, val hrBpm: Int, val contact: Boolean?) : CsvRow {
    override fun csvFields(): List<String> = listOf(
        TimeUtils.formatUtc(timeUtc),
        TimeUtils.formatElapsed(elapsedS),
        hrBpm.toString(),
        contact?.toString() ?: "",          // "true" / "false" / "" (not reported)
    )
    companion object { val HEADER = listOf("time_utc", "elapsed_s", "hr_bpm", "contact") }
}

/**
 * One RR interval (one beat). timeUtc/elapsedS = estimated time of the beat that ends this interval
 * (BeatTimeline). receivedElapsedS = when its packet reached the phone, kept so the estimate can be
 * recomputed later. rrMs is exact: raw 1/1024 s units * 1000 / 1024.
 */
data class RrRow(val timeUtc: Instant, val elapsedS: Double, val rrMs: Double, val receivedElapsedS: Double) : CsvRow {
    override fun csvFields(): List<String> = listOf(
        TimeUtils.formatUtc(timeUtc),
        TimeUtils.formatElapsed(elapsedS),
        exact(rrMs),
        TimeUtils.formatElapsed(receivedElapsedS),
    )
    companion object { val HEADER = listOf("time_utc", "elapsed_s", "rr_ms", "received_elapsed_s") }
}

/** Every digit of the double, no rounding, no exponent, locale independent (820.3125, 1000.0). */
private fun exact(value: Double): String = BigDecimal.valueOf(value).toPlainString()

data class EventRow(val timeUtc: Instant, val elapsedS: Double, val event: String, val note: String = "") : CsvRow {
    override fun csvFields(): List<String> =
        listOf(TimeUtils.formatUtc(timeUtc), TimeUtils.formatElapsed(elapsedS), event, note)
    companion object { val HEADER = listOf("time_utc", "elapsed_s", "event", "note") }
}

/** Event names written to events.csv. Listed in the H10_Data codebook. */
object Events {
    const val RECORDING_STARTED = "recording_started"
    const val RECORDING_STOPPED = "recording_stopped"
    const val CONNECTED = "connected"
    const val DISCONNECTED = "disconnected"
    const val MARKER = "marker"
    /** Gave up reconnecting after SessionRecorder.CONNECTION_TIMEOUT; the session ends. */
    const val RECONNECT_FAILED = "reconnect_failed"
    /** Beat timeline re-anchored because beats were missing (see DATA_NOTES.md). */
    const val RR_RESYNC = "rr_resync"
}

/**
 * Contents of sub-XXX_ses-XX_session.json. Participant code only, never names or birthdates.
 * Times are stored as formatUtc strings (millisecond precision).
 */
data class SessionInfo(
    val participantId: String,
    val sessionId: String,
    val deviceId: String,
    val startUtc: Instant,
    val endUtc: Instant? = null,
    val appVersion: String,
    val sdkVersion: String,
    val notes: String = "",
) {
    fun toJson(): String = JSON.encodeToString(SessionJson.serializer(), SessionJson(
        participantId, sessionId, deviceId,
        TimeUtils.formatUtc(startUtc), endUtc?.let(TimeUtils::formatUtc),
        appVersion, sdkVersion, notes,
    ))

    companion object {
        fun fromJson(json: String): SessionInfo = JSON.decodeFromString(SessionJson.serializer(), json).run {
            SessionInfo(participantId, sessionId, deviceId,
                Instant.parse(startUtc), endUtc?.let(Instant::parse), appVersion, sdkVersion, notes)
        }
        private val JSON = Json { prettyPrint = true }
    }
}

/** On-disk JSON shape. Kept separate so Kotlin names can change without renaming JSON keys. */
@Serializable
private data class SessionJson(
    @SerialName("participant_id") val participantId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("start_utc") val startUtc: String,
    @SerialName("end_utc") val endUtc: String?,
    @SerialName("app_version") val appVersion: String,
    @SerialName("sdk_version") val sdkVersion: String,
    @SerialName("notes") val notes: String,
)
