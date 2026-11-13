package com.example.h10recorder.testutil

import com.example.h10recorder.sensor.SensorConnectionLost
import com.example.h10recorder.sensor.SensorEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tests for the test double: if the fake is wrong, every recorder test is wrong. */
class FakeHrSensorTest {
    private val id = H10Fixtures.DEVICE_ID

    @Test fun `search returns the nearby strap`() = runTest {
        assertEquals(listOf(H10Fixtures.DEVICE), FakeHrSensor().search().toList())
    }

    @Test fun `streaming requires a connection`() = runTest {
        assertFailsWith<IllegalStateException> { FakeHrSensor().hrStream(id).toList() }
    }

    @Test fun `delivers samples in order and stops when the collector is cancelled`() = runTest {
        val s = FakeHrSensor().apply { connect(id) }
        val got = mutableListOf<Int>()
        val job = launch { s.hrStream(id).collect { got += it.hrBpm } }
        runCurrent()
        assertTrue(s.isStreaming)
        s.sendAll(H10Fixtures.resting(3))
        runCurrent()
        assertEquals(listOf(60, 61, 62), got)
        job.cancel(); runCurrent()
        assertFalse(s.isStreaming)
    }

    @Test fun `dropped connection fails the stream and emits Disconnected`() = runTest {
        val s = FakeHrSensor().apply { connect(id) }
        val events = mutableListOf<SensorEvent>()
        backgroundScope.launch { s.events.collect { events += it } }
        var error: Throwable? = null
        launch { try { s.hrStream(id).collect {} } catch (e: Throwable) { error = e } }
        runCurrent()
        s.dropConnection(); runCurrent()
        assertIs<SensorConnectionLost>(error)
        assertEquals(listOf<SensorEvent>(SensorEvent.Disconnected(id, "fake: strap unclipped")), events)
        assertEquals(null, s.connectedId)
    }

    @Test fun `connect can be told to fail`() = runTest {
        val s = FakeHrSensor().apply { connectFailures = 1 }
        assertFailsWith<IOException> { s.connect(id) }
        s.connect(id)
        assertEquals(listOf(id, id), s.connectCalls)
        assertEquals(id, s.connectedId)
    }

    @Test fun `connect can be told to hang until cancelled`() = runTest {
        val s = FakeHrSensor().apply { connectHangs = true }
        assertEquals(null, kotlinx.coroutines.withTimeoutOrNull(20_000) { s.connect(id) })
        assertEquals(null, s.connectedId)
    }

    @Test fun `resting fixture looks like real H10 data`() {
        val samples = H10Fixtures.resting(14)
        assertTrue(samples.all { it.hrBpm in 60..64 && it.contact == true })
        assertEquals(16, samples.sumOf { it.rrRaw1024.size })   // two 2-beat packets
        // RR (ms) consistent with HR: 60 bpm -> ~1000 ms
        assertEquals(1024, samples[0].rrRaw1024.single())
    }
}
