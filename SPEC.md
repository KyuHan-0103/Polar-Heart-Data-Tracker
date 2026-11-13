Platform:
    Build an Android app in Kotlin using Polar's BLE SDK (https://github.com/polarofficial/polar-ble-sdk).
    The SDK supports Android and iOS only. It requires minSdk 33, so the recording phone must run Android 13 or newer.
    Only Heart Rate and RR intervals are recorded. ECG and accelerometer streams are not used.

Constraints:
    This lab only has a Polar H10 so here are the constraints of such a device: 
        Unclipping: the H10 drops the Bluetooth connection about 45 seconds after you unclip it. 

        Connection limit: it allows only two Bluetooth connections at a time. If your phone’s Polar app and a watch are both connected, the app can’t get in. 

        Stopping streams: if you stream ECG or accelerometer data, always stop the stream explicitly. Polar’s known-issues page says that if the app doesn’t end it, the H10 stays on until the battery runs out, even after it’s taken off the strap. ECG/ACC are not used, but stop-and-disconnect code still goes in a finally block so it runs even if the app crashes. 

        Finding the strap: don't hard-code a Bluetooth address. Search by name instead: the strap advertises as “Polar H10” followed by the device ID printed on the sensor, and the SDK connects using that device ID.

        Timestamps: the H10 heart-rate stream carries no sensor timestamps. All times come from the phone: one wall-clock reading at session start, then the monotonic clock (SystemClock.elapsedRealtimeNanos), so a clock sync mid-session can't make time_utc jump.

        File names: don't name a source file or class after a library it imports (e.g. a class called PolarBleApi) to avoid name clashes.
