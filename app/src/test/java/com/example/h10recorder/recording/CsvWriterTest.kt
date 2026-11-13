package com.example.h10recorder.recording

import com.example.h10recorder.testutil.CsvTestUtils
import com.example.h10recorder.testutil.FakeClock
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.FileAlreadyExistsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CsvWriterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = FakeClock()
    private val header = listOf("a", "b")
    private val file by lazy { tmp.root.resolve("out.csv") }
    private fun writer() = CsvWriter(file, header, clock, flushIntervalNanos = 5_000_000_000L)
    private fun onDisk() = CsvTestUtils.readRows(file)

    @Test fun `header is on disk as soon as the writer is created`() {
        writer().use { assertEquals(listOf(header), onDisk()) }
    }

    @Test fun `refuses to overwrite an existing file`() {
        file.writeText("raw data\n")
        assertFailsWith<FileAlreadyExistsException> { writer() }
        assertEquals("raw data\n", file.readText())
    }

    @Test fun `rows are on disk after flush`() {
        writer().use { w ->
            w.write(listOf("1", "2"))
            w.flush()
            assertEquals(listOf(header, listOf("1", "2")), onDisk())
        }
    }

    @Test fun `rows are on disk after close`() {
        writer().apply { write(listOf("1", "2")); close() }
        assertEquals(listOf(header, listOf("1", "2")), onDisk())
    }

    @Test fun `flushes automatically once the interval has passed`() {
        writer().use { w ->
            w.write(listOf("1", "2"))
            clock.advanceSeconds(5.0)
            w.write(listOf("3", "4"))
            assertEquals(listOf(header, listOf("1", "2"), listOf("3", "4")), onDisk())
        }
    }

    @Test fun `lines end with a single newline`() {
        writer().apply { write(listOf("1", "2")); close() }
        assertEquals("a,b\n1,2\n", file.readText())
    }

    @Test fun `escapes per RFC 4180`() {
        assertEquals("plain", CsvWriter.escape("plain"))
        assertEquals("", CsvWriter.escape(""))
        assertEquals("\"a,b\"", CsvWriter.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvWriter.escape("say \"hi\""))
        assertEquals("\"two\nlines\"", CsvWriter.escape("two\nlines"))
        assertEquals("\"cr\rhere\"", CsvWriter.escape("cr\rhere"))
    }

    @Test fun `awkward notes survive a round trip`() {
        val notes = listOf("stood up, walked", "said \"stop\"", "line1\nline2", "café ✓")
        writer().use { w -> notes.forEach { w.write(listOf("x", it)) } }
        assertEquals(notes, onDisk().drop(1).map { it[1] })
    }

    @Test fun `rejects rows with the wrong number of fields`() {
        writer().use { w -> assertFailsWith<IllegalArgumentException> { w.write(listOf("only one")) } }
    }

    @Test fun `write after close fails, close twice is fine`() {
        val w = writer()
        w.close()
        w.close()
        assertFailsWith<IllegalStateException> { w.write(listOf("1", "2")) }
    }
}
