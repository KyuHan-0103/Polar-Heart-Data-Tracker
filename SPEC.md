Platform:
    Build a Python program (Python 3.11+) that runs on a computer with Bluetooth (macOS, Windows or Linux), using
    bleak (cross-platform Bluetooth LE) and bleakheart (Polar H10 support: ECG stream, battery).
    Polar's BLE SDK (https://github.com/polarofficial/polar-ble-sdk) supports Android and iOS only, so it isn't used directly.
    It is the reference for the H10's Bluetooth protocol.
    Heart Rate and RR intervals are always recorded. Raw ECG is optional (off by default). Accelerometer is not used.
    Dependencies are installed in a project-local virtual environment (.venv/), never system-wide.
    Users without coding experience record through a window (Tkinter, part of standard Python) opened by double-clicking a launcher. A terminal mode is kept for developers.

Constraints:
    This lab only has a Polar H10 so here are the constraints of such a device:
        Unclipping: the H10 drops the Bluetooth connection about 45 seconds after you unclip it.

        Connection limit: it allows only two Bluetooth connections at a time. If a phone's Polar app and a watch are both connected, the program can't get in.

        Stopping streams: if you stream ECG or accelerometer data, always stop the stream explicitly. Polar's known-issues page says that if the program doesn't end it, the H10 stays on until the battery runs out, even after it's taken off the strap. Stop-and-disconnect code goes in a finally block so it runs even if the program crashes.

        Finding the strap: don't hard-code a Bluetooth address (macOS doesn't even expose one). Search by name instead: the strap advertises as "Polar H10" followed by the device ID printed on the sensor.

        Timestamps: the H10 heart-rate stream carries no sensor timestamps. All HR/RR times come from the computer: one wall-clock reading at session start, then a monotonic clock that keeps counting during sleep, so a clock sync mid-session can't make time_utc jump.

        File names: don't name a module after a library it imports (e.g. a module called bleak.py or bleakheart.py) to avoid import clashes.
