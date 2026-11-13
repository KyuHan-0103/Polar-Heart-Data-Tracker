package com.example.h10recorder.testutil

import com.example.h10recorder.sensor.FoundDevice
import com.example.h10recorder.sensor.HrSample
import kotlin.math.roundToInt

/**
 * Realistic H10 data. Real H10 packets arrive ~1 Hz with 0, 1 or 2 RR intervals each,
 * in 1/1024 s units (Bluetooth Heart Rate Service).
 */
object H10Fixtures {
    const val DEVICE_ID = "B5A3F42C"   // made up, same 8-hex-digit format as printed on a real H10
    val DEVICE = FoundDevice(DEVICE_ID, "Polar H10 $DEVICE_ID", rssi = -55)

    // Raw RR (1/1024 s) -> exact ms. raw * 1000 / 1024
    const val RR_RAW_A = 840     // 820.3125 ms
    const val RR_MS_A = 820.3125
    const val RR_RAW_B = 856     // 835.9375 ms
    const val RR_MS_B = 835.9375

    fun sample(hr: Int, vararg rrRaw: Int, contact: Boolean? = true) = HrSample(hr, rrRaw.toList(), contact)

    /** Deterministic resting HR: 60-64 bpm, one beat per packet, a 2-beat packet every 7th. */
    fun resting(packets: Int): List<HrSample> = List(packets) { i ->
        val hr = 60 + i % 5
        val rr = (60_000.0 / hr * 1024 / 1000).roundToInt()
        if (i % 7 == 6) sample(hr, rr, rr) else sample(hr, rr)
    }
}
