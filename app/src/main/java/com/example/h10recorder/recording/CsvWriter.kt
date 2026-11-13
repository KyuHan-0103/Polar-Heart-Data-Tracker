package com.example.h10recorder.recording

import com.example.h10recorder.data.Csv
import com.example.h10recorder.data.SessionClock
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.OutputStreamWriter
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Append-only buffered CSV writer. Creates [file] (refuses an existing one), writes the header
 * immediately, then buffers rows and flushes once [flushIntervalNanos] has passed (checked on
 * each write), on [flush], and on [close]. RFC 4180 quoting, UTF-8, "\n" line endings.
 *
 * Every flush also forces the bytes to storage (fsync), so if the phone dies mid-session at most
 * one flush interval of rows is lost. Thread-safe: the recorder and UI markers can write at once.
 */
class CsvWriter(
    file: File,
    header: List<String>,
    private val clock: SessionClock,
    private val flushIntervalNanos: Long = DEFAULT_FLUSH_INTERVAL_NANOS,
) : Closeable {
    private val columns = header.size
    // CREATE_NEW fails atomically (FileAlreadyExistsException) if the file exists: raw data can't be overwritten.
    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    private val out = BufferedWriter(OutputStreamWriter(Channels.newOutputStream(channel), Charsets.UTF_8))
    private var lastFlushNanos = clock.monotonicNanos()
    private var closed = false

    init {
        try {
            out.write(Csv.line(header))
            flushToDisk()
        } catch (e: Throwable) {
            runCatching { out.close() }
            throw e
        }
    }

    @Synchronized
    fun write(fields: List<String>) {
        check(!closed) { "CsvWriter is closed" }
        require(fields.size == columns) { "Row has ${fields.size} fields, header has $columns" }
        out.write(Csv.line(fields))
        if (clock.monotonicNanos() - lastFlushNanos >= flushIntervalNanos) flushToDisk()
    }

    /**
     * Like [write], but returns false instead of throwing when the writer is already closed, and can
     * force the row to disk at once. For callers on other threads that may race with close (UI markers).
     */
    @Synchronized
    fun writeIfOpen(fields: List<String>, flushNow: Boolean = false): Boolean {
        if (closed) return false
        write(fields)
        if (flushNow) flushToDisk()
        return true
    }

    /** Flushes if the flush interval has passed since the last flush. No-op when closed. */
    @Synchronized
    fun flushIfDue() {
        if (!closed && clock.monotonicNanos() - lastFlushNanos >= flushIntervalNanos) flushToDisk()
    }

    @Synchronized
    fun flush() {
        check(!closed) { "CsvWriter is closed" }
        flushToDisk()
    }

    /** Flushes and closes. Calling it again does nothing. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { flushToDisk() } finally { out.close() }
    }

    private fun flushToDisk() {
        out.flush()
        channel.force(false)
        lastFlushNanos = clock.monotonicNanos()
    }

    companion object {
        const val DEFAULT_FLUSH_INTERVAL_NANOS = 5_000_000_000L   // knob: see README "Settings you can change"
        fun escape(field: String): String = Csv.escape(field)
    }
}
