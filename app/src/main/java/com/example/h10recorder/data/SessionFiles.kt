package com.example.h10recorder.data

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class FileKind(val suffix: String) {
    SESSION("session.json"), HR("hr.csv"), RR("rr.csv"), EVENTS("events.csv")
}

class SessionExistsException(dir: File) : IllegalStateException("Session folder already exists: $dir")

/**
 * Participant/session ID rules. Strict on purpose so a name can't be typed in by mistake.
 *
 * ============================== ID-FORMAT (TEMPORARY) ==============================
 * PLACEHOLDER until the lab decides on an ID scheme. To change it, edit the two regexes
 * below, then update the examples in: SessionFilesTest (ID-FORMAT tests), DATA_NOTES.md,
 * and the hint text in ui/SetupScreen.kt (where the researcher types the IDs).
 * Search the project for "ID-FORMAT" to find every place.
 * ===================================================================================
 */
object Ids {
    // [0-9], not \\d: on Android (ICU regex) \\d also matches non-ASCII digits such as "١".
    val PARTICIPANT = Regex("^P[0-9]{3}$")   // ID-FORMAT: e.g. P001
    val SESSION = Regex("^[0-9]{2}$")        // ID-FORMAT: e.g. 01

    // Messages don't repeat the rejected text: if a name was typed by mistake it shouldn't end up in logs.
    fun requireValidParticipant(id: String): String =
        id.also { require(PARTICIPANT.matches(it)) { "Participant ID must look like P001" } }  // ID-FORMAT

    fun requireValidSession(id: String): String =
        id.also { require(SESSION.matches(it)) { "Session ID must look like 01" } }            // ID-FORMAT
}

/** Folder/file naming, sessions.csv, zip export. Layout: root/sub-P001/ses-01/sub-P001_ses-01_hr.csv */
class SessionFiles(val root: File) {
    /** IDs are validated first, so no path is ever built from unchecked text (e.g. "../"). */
    fun sessionDir(participantId: String, sessionId: String): File {
        val p = Ids.requireValidParticipant(participantId)
        val s = Ids.requireValidSession(sessionId)
        return File(root, "sub-$p/ses-$s")
    }

    fun file(participantId: String, sessionId: String, kind: FileKind): File =
        File(sessionDir(participantId, sessionId), "sub-${participantId}_ses-${sessionId}_${kind.suffix}")

    /** Creates the session folder. Throws SessionExistsException rather than touch existing raw data. */
    fun createSessionDir(participantId: String, sessionId: String): File {
        val dir = sessionDir(participantId, sessionId)
        if (dir.exists()) throw SessionExistsException(dir)
        if (!dir.mkdirs()) throw IOException("Could not create $dir")
        return dir
    }

    /**
     * Writes session.json for [info]'s session (at start, and again at the end with end_utc).
     * Atomic: written to a temp file, then renamed over the old one, so a crash mid-write can
     * never leave a half-written session.json.
     */
    fun writeSessionInfo(info: SessionInfo) {
        val target = file(info.participantId, info.sessionId, FileKind.SESSION)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(info.toJson().toByteArray(Charsets.UTF_8))
            out.fd.sync()          // on storage before the rename, so a power cut can't leave an empty file
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Appends one row to root/sessions.csv (header written on first use). Rows are never rewritten. */
    fun appendToSessionsIndex(info: SessionInfo) {
        val index = File(root, SESSIONS_INDEX)
        val duration = info.endUtc?.let { Duration.between(info.startUtc, it).toNanos() / 1_000_000_000.0 }
        val text = buildString {
            if (!index.exists() || index.length() == 0L) append(Csv.line(SESSIONS_HEADER))
            append(Csv.line(listOf(
                info.participantId, info.sessionId, info.deviceId,
                TimeUtils.formatUtc(info.startUtc),
                info.endUtc?.let(TimeUtils::formatUtc) ?: "",
                duration?.let(TimeUtils::formatElapsed) ?: "",
                info.notes,
            )))
        }
        root.mkdirs()
        index.appendText(text, Charsets.UTF_8)
    }

    /**
     * Zips one session folder to [out] (entries named sub-P001/ses-01/<file>). Only reads the session
     * files. [out] must not be inside the data folder.
     */
    fun exportZip(participantId: String, sessionId: String, out: File): File {
        val dir = sessionDir(participantId, sessionId)
        if (!dir.isDirectory) throw FileNotFoundException("No such session: $dir")
        require(!out.canonicalFile.startsWith(root.canonicalFile)) { "Export must go outside the data folder" }
        val prefix = dir.relativeTo(root).invariantSeparatorsPath
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (f in dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }) {
                zip.putNextEntry(ZipEntry("$prefix/${f.name}").apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    companion object {
        const val SESSIONS_INDEX = "sessions.csv"
        val SESSIONS_HEADER = listOf("participant_id", "session_id", "device_id", "start_utc", "end_utc", "duration_s", "notes")
    }
}
