package com.example.h10recorder.testutil

import java.io.File

/** Minimal RFC 4180 reader, independent of the app's CsvWriter, used to check what's on disk. */
object CsvTestUtils {
    fun readRows(file: File): List<List<String>> = parse(file.readText(Charsets.UTF_8))

    /** Rows as maps keyed by the header. */
    fun readRecords(file: File): List<Map<String, String>> {
        val rows = readRows(file)
        val header = rows.first()
        return rows.drop(1).map { row ->
            require(row.size == header.size) { "row has ${row.size} fields, header has ${header.size}: $row" }
            header.zip(row).toMap()
        }
    }

    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> { row += field.toString(); field.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += field.toString(); field.clear(); rows += row; row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows
    }
}
