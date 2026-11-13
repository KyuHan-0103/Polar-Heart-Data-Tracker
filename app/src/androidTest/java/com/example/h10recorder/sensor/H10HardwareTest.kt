package com.example.h10recorder.sensor

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-hardware tests. Need an Android phone over USB and a worn H10.
 * Skipped unless the strap's device ID is passed:
 *
 *   ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.h10DeviceId=XXXXXXXX
 *
 * Close the Polar app / unpair watches first: the H10 allows only two BLE connections.
 */
@RunWith(AndroidJUnit4::class)
class H10HardwareTest {
    @get:Rule val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    private val deviceId: String? = InstrumentationRegistry.getArguments().getString("h10DeviceId")
    private lateinit var sensor: PolarSensor

    @Before fun setUp() {
        assumeTrue("No h10DeviceId instrumentation argument: hardware tests skipped", deviceId != null)
        sensor = PolarSensor.create(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After fun tearDown() = runBlocking {
        if (deviceId == null) return@runBlocking
        try { sensor.disconnect(deviceId) } finally { sensor.shutDown() }
    }

    @Test fun findsStrapByName() = runBlocking {
        val found = withTimeout(20_000) { sensor.search().first { it.deviceId == deviceId } }
        assertTrue(found.name.startsWith(PolarSensor.H10_NAME_PREFIX))
    }

    @Test fun connectsStreamsHrAndRr() = runBlocking {
        val id = deviceId!!
        withTimeout(30_000) { sensor.connect(id) }
        val samples = withTimeout(30_000) { sensor.hrStream(id).take(10).toList() }
        assertTrue("HR out of range: $samples", samples.all { it.hrBpm in 30..220 })
        assertTrue("no RR intervals received - is the strap worn?", samples.any { it.rrRaw1024.isNotEmpty() })
        assertTrue("strap reports no skin contact", samples.last().contact == true)
    }

    @Test fun hrStreamStopsWhenCancelledAndCanRestart() = runBlocking {
        val id = deviceId!!
        withTimeout(30_000) { sensor.connect(id) }
        withTimeout(30_000) { sensor.hrStream(id).first() }        // collecting first() cancels the stream
        withTimeout(30_000) { sensor.hrStream(id).first() }        // a second stream works on the same link
        Unit
    }

    @Test fun readsBattery() = runBlocking {
        val id = deviceId!!
        withTimeout(30_000) { sensor.connect(id) }
        val level = sensor.batteryLevel(id)
        assertTrue("battery $level", level != null && level in 0..100)
    }
}
