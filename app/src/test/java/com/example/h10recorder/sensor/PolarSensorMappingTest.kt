package com.example.h10recorder.sensor

import com.polar.sdk.api.model.PolarHrData.PolarHrSample
import kotlin.test.Test
import kotlin.test.assertEquals

/** The SDK -> app conversion. Constructing PolarHrSample here also proves the SDK is on the classpath. */
class PolarSensorMappingTest {
    private fun polar(
        hr: Int = 72, rrs: List<Int> = listOf(840, 856), rrsMs: List<Int> = listOf(820, 836),
        rrAvailable: Boolean = true, contact: Boolean = true, contactSupported: Boolean = true,
    ) = PolarHrSample(
        hr = hr, correctedHr = 0, ppgQuality = 0, rrs = rrs, rrsMs = rrsMs,
        rrAvailable = rrAvailable, contactStatus = contact, contactStatusSupported = contactSupported,
    )

    @Test fun `maps hr, raw RRs and contact`() {
        assertEquals(HrSample(72, listOf(840, 856), true), polar().toHrSample())
        assertEquals(HrSample(72, listOf(840, 856), false), polar(contact = false).toHrSample())
    }

    @Test fun `uses raw 1-1024 RRs, not the SDK's rounded milliseconds`() {
        assertEquals(listOf(840, 856), polar(rrs = listOf(840, 856), rrsMs = listOf(820, 836)).toHrSample().rrRaw1024)
    }

    @Test fun `no RRs when the sensor says none are available`() {
        assertEquals(emptyList(), polar(rrAvailable = false, rrs = emptyList(), rrsMs = emptyList()).toHrSample().rrRaw1024)
    }

    @Test fun `contact is null when the sensor does not support contact detection`() {
        assertEquals(null, polar(contact = false, contactSupported = false).toHrSample().contact)
    }

    @Test fun `finds the H10 by advertised name`() {
        assertEquals("Polar H10", PolarSensor.H10_NAME_PREFIX)
    }
}
