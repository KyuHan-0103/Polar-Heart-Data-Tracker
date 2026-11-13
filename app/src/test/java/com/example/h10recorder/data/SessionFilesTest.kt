package com.example.h10recorder.data

import com.example.h10recorder.testutil.CsvTestUtils
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val root by lazy { tmp.newFolder("H10_Data") }
    private val files by lazy { SessionFiles(root) }

    // ID-FORMAT (TEMPORARY): these two tests encode the placeholder ID scheme in Ids. Update with it.
    @Test fun `valid participant and session ids`() {
        assertEquals("P001", Ids.requireValidParticipant("P001"))
        assertEquals("P123", Ids.requireValidParticipant("P123"))
        assertEquals("01", Ids.requireValidSession("01"))
    }

    @Test fun `rejects ids that are not codes`() {
        for (bad in listOf("", "p001", "P01", "P0001", "John", "P001 ", "../P001", "P001/x", "Jane_Doe_1990"))
            assertFailsWith<IllegalArgumentException>("participant '$bad'") { Ids.requireValidParticipant(bad) }
        for (bad in listOf("", "1", "001", "a1", "../"))
            assertFailsWith<IllegalArgumentException>("session '$bad'") { Ids.requireValidSession(bad) }
    }

    @Test fun `error messages do not repeat the rejected text`() {
        val e = assertFailsWith<IllegalArgumentException> { Ids.requireValidParticipant("Jane Doe") }
        assertFalse("Jane" in e.message.orEmpty())
    }

    @Test fun `folder layout`() {
        assertEquals(File(root, "sub-P001/ses-01"), files.sessionDir("P001", "01"))
    }

    @Test fun `file names`() {
        val dir = File(root, "sub-P001/ses-01")
        assertEquals(File(dir, "sub-P001_ses-01_session.json"), files.file("P001", "01", FileKind.SESSION))
        assertEquals(File(dir, "sub-P001_ses-01_hr.csv"), files.file("P001", "01", FileKind.HR))
        assertEquals(File(dir, "sub-P001_ses-01_rr.csv"), files.file("P001", "01", FileKind.RR))
        assertEquals(File(dir, "sub-P001_ses-01_events.csv"), files.file("P001", "01", FileKind.EVENTS))
    }

    @Test fun `paths are validated so nothing escapes the data folder`() {
        assertFailsWith<IllegalArgumentException> { files.sessionDir("../../etc", "01") }
        assertFailsWith<IllegalArgumentException> { files.file("P001", "../01", FileKind.HR) }
    }

    @Test fun `createSessionDir creates the folder`() {
        val dir = files.createSessionDir("P001", "01")
        assertTrue(dir.isDirectory)
        assertEquals(files.sessionDir("P001", "01"), dir)
    }

    @Test fun `createSessionDir refuses an existing session and leaves its data untouched`() {
        files.createSessionDir("P001", "01")
        val hr = files.file("P001", "01", FileKind.HR).apply { writeText("raw data") }
        assertFailsWith<SessionExistsException> { files.createSessionDir("P001", "01") }
        assertEquals("raw data", hr.readText())
    }

    private fun info(p: String, s: String, end: Instant?, notes: String = "") = SessionInfo(
        participantId = p, sessionId = s, deviceId = "B5A3F42C",
        startUtc = Instant.parse("2026-11-12T15:00:00Z"), endUtc = end,
        appVersion = "0.1.0", sdkVersion = "8.5.0", notes = notes,
    )

    @Test fun `writeSessionInfo writes and then replaces session json, leaving no temp file`() {
        files.createSessionDir("P001", "01")
        val open = info("P001", "01", end = null)
        files.writeSessionInfo(open)
        val target = files.file("P001", "01", FileKind.SESSION)
        assertEquals(open, SessionInfo.fromJson(target.readText()))
        val done = open.copy(endUtc = Instant.parse("2026-11-12T16:00:00Z"))
        files.writeSessionInfo(done)
        assertEquals(done, SessionInfo.fromJson(target.readText()))
        assertEquals(listOf(target.name), files.sessionDir("P001", "01").list()!!.toList())
    }

    @Test fun `sessions index gets a header once and one row per session`() {
        files.appendToSessionsIndex(info("P001", "01", Instant.parse("2026-11-12T16:00:00Z")))
        files.appendToSessionsIndex(info("P002", "01", Instant.parse("2026-11-12T15:30:00.250Z"), notes = "short, interrupted"))
        val index = File(root, SessionFiles.SESSIONS_INDEX)
        val rows = CsvTestUtils.readRows(index)
        assertEquals(SessionFiles.SESSIONS_HEADER, rows[0])
        assertEquals(3, rows.size)
        val recs = CsvTestUtils.readRecords(index)
        assertEquals(
            mapOf("participant_id" to "P001", "session_id" to "01", "device_id" to "B5A3F42C",
                "start_utc" to "2026-11-12T15:00:00.000Z", "end_utc" to "2026-11-12T16:00:00.000Z",
                "duration_s" to "3600.000", "notes" to ""),
            recs[0],
        )
        assertEquals("1800.250", recs[1]["duration_s"])
        assertEquals("short, interrupted", recs[1]["notes"])
    }

    @Test fun `sessions index leaves end and duration empty for an unfinished session`() {
        files.appendToSessionsIndex(info("P001", "01", end = null))
        val rec = CsvTestUtils.readRecords(File(root, SessionFiles.SESSIONS_INDEX)).single()
        assertEquals("", rec["end_utc"])
        assertEquals("", rec["duration_s"])
    }

    @Test fun `exportZip contains every session file with identical bytes`() {
        files.createSessionDir("P001", "01")
        val originals = FileKind.entries.associateWith { kind ->
            files.file("P001", "01", kind).apply { writeText("content of $kind\n") }
        }
        val zip = files.exportZip("P001", "01", File(tmp.root, "export.zip"))
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertEquals(originals.values.map { "sub-P001/ses-01/${it.name}" }.toSet(), names)
            for (f in originals.values) {
                val bytes = z.getInputStream(z.getEntry("sub-P001/ses-01/${f.name}")).readBytes()
                assertContentEquals(f.readBytes(), bytes)
            }
        }
        assertFailsWith<IllegalArgumentException>("export into the data folder") {
            files.exportZip("P001", "01", File(root, "export.zip"))
        }
        assertFailsWith<java.io.FileNotFoundException> { files.exportZip("P002", "01", File(tmp.root, "x.zip")) }
        // raw files untouched
        originals.forEach { (kind, f) -> assertEquals("content of $kind\n", f.readText()) }
    }
}
