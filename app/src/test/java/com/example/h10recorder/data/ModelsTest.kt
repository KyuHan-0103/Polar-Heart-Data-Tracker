package com.example.h10recorder.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelsTest {
    private val t = Instant.parse("2026-11-12T15:00:01Z")

    // Headers are part of the data codebook (H10_Data/README.md). Changing them breaks analysis scripts.
    @Test fun `csv headers match the codebook`() {
        assertEquals(listOf("time_utc", "elapsed_s", "hr_bpm", "contact"), HrRow.HEADER)
        assertEquals(listOf("time_utc", "elapsed_s", "rr_ms", "received_elapsed_s"), RrRow.HEADER)
        assertEquals(listOf("time_utc", "elapsed_s", "event", "note"), EventRow.HEADER)
    }

    @Test fun `HrRow fields`() {
        assertEquals(listOf("2026-11-12T15:00:01.000Z", "1.000", "72", "true"), HrRow(t, 1.0, 72, true).csvFields())
        assertEquals(listOf("2026-11-12T15:00:01.000Z", "1.000", "72", "false"), HrRow(t, 1.0, 72, false).csvFields())
    }

    @Test fun `HrRow contact is empty when the sensor does not report it`() {
        assertEquals("", HrRow(t, 1.0, 72, null).csvFields()[3])
    }

    @Test fun `RrRow keeps full precision`() {
        assertEquals("820.3125", RrRow(t, 1.0, 820.3125, 1.2).csvFields()[2])
        assertEquals("0.9765625", RrRow(t, 1.0, 0.9765625, 1.2).csvFields()[2])
        assertEquals("1000.0", RrRow(t, 1.0, 1000.0, 1.2).csvFields()[2])
    }

    @Test fun `RrRow fields - estimated beat time plus packet arrival time`() {
        assertEquals(listOf("2026-11-12T15:00:01.000Z", "1.000", "820.3125", "1.250"),
            RrRow(t, 1.0, 820.3125, 1.25).csvFields())
    }

    @Test fun `RrRow allows negative elapsed for a beat just before recording started`() {
        assertEquals("-0.400", RrRow(t, -0.4, 820.3125, 0.2).csvFields()[1])
    }

    @Test fun `numbers are locale independent`() = withLocale(Locale.GERMANY) {
        assertEquals(listOf("2026-11-12T15:00:01.000Z", "1.500", "820.3125", "2.000"), RrRow(t, 1.5, 820.3125, 2.0).csvFields())
    }

    @Test fun `EventRow fields pass the note through unescaped`() {
        // escaping is CsvWriter's job
        assertEquals(listOf("2026-11-12T15:00:01.000Z", "1.000", "marker", "stood up, walked"),
            EventRow(t, 1.0, Events.MARKER, "stood up, walked").csvFields())
    }

    @Test fun `Csv line quotes only when needed and ends with newline`() {
        assertEquals("a,\"b,c\",\"say \"\"hi\"\"\"\n", Csv.line(listOf("a", "b,c", "say \"hi\"")))
    }

    private val info = SessionInfo(
        participantId = "P001", sessionId = "01", deviceId = "B5A3F42C",
        startUtc = Instant.parse("2026-11-12T15:00:00Z"), endUtc = Instant.parse("2026-11-12T16:00:00.5Z"),
        appVersion = "0.1.0", sdkVersion = "8.5.0", notes = "baseline",
    )

    @Test fun `SessionInfo json round trips`() {
        assertEquals(info, SessionInfo.fromJson(info.toJson()))
        val open = info.copy(endUtc = null)
        assertEquals(open, SessionInfo.fromJson(open.toJson()))
    }

    @Test fun `SessionInfo json uses snake_case keys and formatted UTC strings`() {
        val obj = Json.parseToJsonElement(info.toJson()).jsonObject
        assertEquals(
            setOf("participant_id", "session_id", "device_id", "start_utc", "end_utc", "app_version", "sdk_version", "notes"),
            obj.keys,
        )
        assertEquals("2026-11-12T15:00:00.000Z", obj["start_utc"]!!.jsonPrimitive.content)
        assertEquals("2026-11-12T16:00:00.500Z", obj["end_utc"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, Json.parseToJsonElement(info.copy(endUtc = null).toJson()).jsonObject["end_utc"])
    }
}
