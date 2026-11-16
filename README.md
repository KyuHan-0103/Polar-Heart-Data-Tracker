The purpose of this project is to pull heart rate data from Polar devices, particularly the Polar H10. The data is used for research.
It is a Python program that runs on a computer (macOS, Windows or Linux) with Bluetooth, using [bleak](https://github.com/hbldh/bleak)
and [bleakheart](https://github.com/fsmeraldi/bleakheart). Polar's own SDK (https://github.com/polarofficial/polar-ble-sdk) is Android/iOS only.
It documents the H10's Bluetooth protocol, which bleakheart implements.

We only want Heart Rate and RR intervals. Raw ECG can be switched on (off by default). Accelerometer is not used.

The raw files should NEVER be edited. Cleaning and analysis go in a separate folder, produced by scripts.
Participants are only identified by code. No names or birthdates in files or filenames (and never type one into a marker note).

## Structure (up for edits)
```
├── requirements.txt          # bleak, bleakheart (exact versions)
├── requirements-dev.txt      # pytest, pytest-asyncio
├── pytest.ini
├── README.md                 # this file: how to run a session, how the code works
├── SPEC.md                   # platform and H10 constraints
├── DATA_NOTES.md             # how times, RR precision and beat times work (draft codebook)
├── h10_recorder/
│   ├── __init__.py
│   ├── __main__.py           # entry point: python -m h10_recorder --participant P001 --session 01
│   ├── config.py             # every knob: sensor name prefix, data folder, ECG on/off, flush interval, timeouts, IDs
│   ├── models.py             # dataclasses: queue items, HRRow, RRRow, ECGRow, EventRow, SessionInfo
│   ├── clock.py              # the one place timestamps become time_utc and elapsed_s (+ beat/ECG sample times)
│   ├── sensor.py             # only module importing bleak/bleakheart: find, connect, stream, reconnect, stop
│   ├── markers.py            # experimenter markers typed in the terminal
│   ├── recorder.py           # takes labeled items off the queue, turns them into rows
│   ├── storage.py            # session folder and file naming, ID checks, CSV writers, session.json, sessions.csv
│   └── session.py            # wires everything together
└── tests/
    ├── conftest.py           # FakeClock, CSV readers
    ├── test_clock.py         # time formatting, session timeline, beat and ECG sample times
    ├── test_models.py        # row formatting, exact rr_ms, session.json round trip
    ├── test_storage.py       # file naming, ID rules, CSV formatting, writer, session.json, sessions.csv
    ├── test_recorder.py      # feed sample queue items, check the rows that come out
    ├── test_sensor.py        # HR packet decoding; connect/reconnect/stop against a fake Bluetooth client
    ├── test_session.py       # wiring: a fake strap -> real files on disk; start/stop times; cleanup
    └── test_hardware.py      # real H10; skipped unless H10_DEVICE_ID is set
```

How data flows:
```
 H10 --Bluetooth--> sensor.py --+                                  +--> hr.csv / rr.csv / ecg.csv
                                +--> asyncio.Queue --> recorder.py +--> events.csv
 keyboard --------> markers.py -+   (labeled items,               |
 Ctrl+C ----------> session.py -+    stamped on arrival)          session.py: session.json, sessions.csv
```

## Data layout
Saved in the computer's **Downloads folder**: `~/Downloads/H10_Data/` (change with `--data-root` or `DATA_ROOT` in `config.py`).
```
H10_Data/
├── README.md                             # codebook: every file, column, unit, clock (to write, from DATA_NOTES.md)
├── sessions.csv                          # one row per session: IDs, device, start_utc, end_utc, duration_s, stop_reason, notes
└── sub-P001/
    └── ses-01/
        ├── sub-P001_ses-01_session.json  # metadata: start_utc, end_utc, duration_s, stop_reason, software versions
        ├── sub-P001_ses-01_hr.csv        # time_utc, elapsed_s, hr_bpm, contact
        ├── sub-P001_ses-01_rr.csv        # time_utc, elapsed_s, rr_ms, received_elapsed_s (one row per beat)
        ├── sub-P001_ses-01_events.csv    # time_utc, elapsed_s, event, note
        └── sub-P001_ses-01_ecg.csv       # only with --ecg: time_utc, elapsed_s, ecg_uv, received_elapsed_s
```
**Start and stop of every session are timestamped in three places:** `session.json` (`start_utc`, `end_utc`, `duration_s`),
`events.csv` (`recording_started` at elapsed 0 and `recording_stopped`, with the same times), and `sessions.csv`. The terminal
also prints both, in UTC and local time. If the program is killed outright (power cut, `kill -9`), `end_utc` stays empty;
the last row in the CSVs shows roughly when it ended.

See DATA_NOTES.md for how times, RR precision and beat times work, and the temporary participant ID format.

## Setup (once)
Everything installs into a project-local virtual environment `.venv/`, nothing system-wide. Python 3.11 or newer.
```
python3 -m venv .venv
source .venv/bin/activate              # Windows: .venv\Scripts\activate
pip install -r requirements.txt -r requirements-dev.txt
```
macOS: the first Bluetooth use asks to allow Bluetooth for the Terminal (or your editor). Allow it, in
System Settings → Privacy & Security → Bluetooth if you missed the prompt.

## Running a session
```
source .venv/bin/activate
python -m h10_recorder --scan                                   # optional: list straps in range and their device IDs
python -m h10_recorder --participant P001 --session 01          # record; add --device B5A3F42C if several straps are in range
```
1. Wet the strap's electrodes, put it on, then start. The program searches for 10 s, connects (up to 20 s) and prints `Started …`.
2. **Markers:** press Enter to log a marker. Type a short note first to label it (e.g. `baseline start` then Enter).
3. **Stop:** type `q` + Enter, or press Ctrl+C. It prints `Stopped …`, the duration and the reason.
4. If the strap disconnects, it reconnects by itself. If it can't within 20 s, the session ends, the terminal beeps and prints a
   warning. Data up to the drop is saved. Start again with a **new session ID**.
5. Keep the computer awake and within Bluetooth range (~10 m). On macOS idle sleep is blocked while recording (`caffeinate`),
   but closing the lid still sleeps the Mac and drops the connection.

Exit codes: 0 stopped by user, 1 error, 2 connection lost, 3 did not start (no strap, bad ID, session already exists).
Options: `--ecg` also records ECG, `--notes "..."` adds a session note, `-v` shows Bluetooth debug messages.

## Tests
```
pytest                                         # all tests, no strap needed (~2 s)
H10_DEVICE_ID=B5A3F42C pytest tests/test_hardware.py -s    # with a worn H10: streaming, restart, ECG
```
Everything except `test_hardware.py` runs without Bluetooth: `sensor.py` is tested against a fake bleak client, and
the wiring (`test_session.py`) uses a fake strap that writes real files into a temporary folder. The fake clock (`tests/conftest.py`)
lets tests set exact times. The hardware tests **have not been run yet** (no strap available while writing).

## How it works: contracts
Rules each module promises the rest of the program. The test that checks each rule is in brackets, so breaking a
rule fails the suite.

### `sensor.py`
- The **only** module importing bleak/bleakheart. The strap is found by its advertised name `Polar H10 <device ID>`, never a hard-coded address. [test_session]
- HR packets are decoded here (Bluetooth SIG Heart Rate Measurement) and **not** with `bleakheart.HeartRate`, because bleakheart rounds RR to
  whole ms and stamps packets with the wall clock (`bleakheart/_core.py`). RR stays in raw 1/1024 s units. Contact is `True`/`False`, or `None` if not reported. [test_sensor: decode_*]
- Every packet is stamped with the monotonic clock **the moment it arrives**, then put on the queue as an `HrPacket`. Malformed packets are dropped. [test_sensor]
- `connect()` connects, starts HR notifications (and ECG if on), reads the battery. Connection errors (`BleakError`, `OSError`, timeouts) are retried every 1 s for up to 20 s,
  then it raises `SensorError`. A hanging attempt is abandoned at the deadline and its half-open link is closed. Any other error is a bug and is raised at once. [test_sensor]
- `watch()` runs during the session. On a drop it queues `Disconnected`, then reconnects (first try after 1 s, up to 20 s): `Connected` on success, `ReconnectFailed` and return if not.
  A drop during connection setup is not missed. [test_sensor]
- **`stop()` stops the streams before disconnecting:** ECG stream stop → HR notifications stop → disconnect. Each step has a 5 s timeout and errors are logged, not raised,
  so the next step still runs. Safe to call any number of times. After `stop()` the object is finished and won't reconnect. Our own disconnect is not reported as a drop. [test_sensor]

### `markers.py`
- Enter = marker (optional note), `q`/`quit`/`stop` = Stop. The time is taken when Enter is pressed, not when the line is processed. [test_session: marker_*]
- Reads on a daemon thread, so a pending read never keeps the program from exiting.

### `clock.py`
- One wall-clock reading at session start, truncated to the millisecond. Every other time is start + monotonic elapsed, so a clock sync mid-session can't make `time_utc` jump. [test_clock]
- The monotonic clock keeps counting while the computer sleeps. On macOS Python's `time.monotonic()` stops during sleep, so `CLOCK_MONOTONIC` is used (`man 3 clock_gettime`). [test_clock]
- `time_utc`: ISO 8601 UTC, 3 decimals, truncated. `elapsed_s`: 3 decimals, truncated. So `time_utc` = start + `elapsed_s` to the millisecond in every row, and numbers always use `.`. [test_clock]
- `BeatTimeline` places each beat (method and accuracy: DATA_NOTES.md). `EcgTimeline` places ECG samples from the sensor's sample clock. [test_clock]

### `models.py`
- Queue items (`HrPacket`, `EcgFrame`, `Connected`, `Disconnected`, `ReconnectFailed`, `Marker`, `StopRequested`) each carry the monotonic time they happened.
- Rows (`HRRow`, `RRRow`, `ECGRow`, `EventRow`) each have `HEADER` and `fields()`. `rr_ms` is exact decimal text, never rounded (840 → `820.3125`). [test_models]
- `SessionInfo` ↔ `session.json`, with times as `time_utc` strings and `end_utc` null until the end. [test_models]

### `storage.py`
- IDs are checked (pattern in `config.py`, ASCII digits only) before any path is built, so text like `../` can't reach the file system. Error messages never repeat the rejected text. [test_storage]
- **Raw data is never overwritten:** the session folder must not exist, and data files are opened in exclusive-create mode (`'x'`), so the OS refuses an existing file. [test_storage]
- `CsvWriter`: header written and fsynced at once. Rows are buffered and flushed + fsynced every 5 s (checked on each write and `flush_if_due()`), on `flush()`, on `close()`,
  or right away with `write(..., flush_now=True)`. Column count is checked. `close()` twice is fine, writing after close fails. UTF-8, `\n`, RFC 4180 quoting. [test_storage]
- `session.json` is written at start and replaced once at the end, via temp file + fsync + atomic rename, so it is never half-written. [test_storage]
- `sessions.csv` is append-only, header once, one row per finished session, fsynced. [test_storage]

### `recorder.py`
- Pure: no Bluetooth, no terminal. `handle(item)` writes rows and returns a stop reason for `StopRequested` (user) and `ReconnectFailed` (connection_lost). An unknown item is a bug (`TypeError`). [test_recorder]
- `HrPacket` → one `hr.csv` row at its arrival time, one `rr.csv` row per beat at its estimated time (`received_elapsed_s` = packet arrival), `rr_resync` event if the chain re-anchored.
  Then every file gets `flush_if_due()`, so a quiet file (rr, events) still reaches disk within 5 s. [test_recorder]
- Every event is forced to disk immediately. [test_recorder]
- `Disconnected` → flush every file to disk, log `disconnected`, restart the beat chain and ECG timeline. `Connected` → log `connected`. [test_recorder]

### `session.py`: `run_session(...)`, in order
1. Checks the IDs and that the session folder doesn't exist yet, **before** touching Bluetooth. [test_session]
2. Finds the strap (exactly one, or the one given by `--device`) and connects. **If this fails, nothing is written** and the session ID stays free. [test_session]
3. Creates the folder, `session.json` (`start_utc` = when connecting began), and the CSVs. Logs `recording_started` (elapsed 0) and `connected` (note: battery %). Prints `Started`. [test_session]
4. Records until Stop (`q`, Ctrl+C), `ReconnectFailed`, or an error. Prints HR every 30 s. Ctrl+C is turned into a normal Stop, so files close properly.
5. **Cleanup always runs**, also on errors and cancellation, in this order: stop streams + disconnect → write packets still in the queue →
   log `recording_stopped` (note = reason) → close CSVs → `session.json` gets `end_utc`, `duration_s`, `stop_reason` → append to `sessions.csv`.
   Every step runs even if an earlier one fails. Failures are printed, and the session's `stop_reason` becomes `error`. `recording_stopped` and `end_utc` are the same clock reading. [test_session]
6. Connection lost → terminal beep plus a warning to start a new session ID. [test_session]

## Settings you can change (knobs)
All in `h10_recorder/config.py`. Tests read the constants, so they follow a change, apart from the ID-format tests, which list example IDs.

| Setting | Current | Name in `config.py` |
|---|---|---|
| Where data is saved | `~/Downloads/H10_Data` | `DATA_ROOT` (or `--data-root`) |
| Record ECG too | off | `ECG_ENABLED` (or `--ecg`) |
| Give up (re)connecting after | 20 s | `CONNECT_TIMEOUT_S` |
| Pause between connect attempts | 1 s | `RETRY_DELAY_S` |
| Search time | 10 s | `SCAN_TIMEOUT_S` |
| CSV flush interval (max data lost in a crash) | 5 s | `FLUSH_INTERVAL_S` |
| Beat-chain resync threshold | 2 s | `BEAT_GAP_RESET_S` |
| Beat-time look-back window (**revisit with real data**) | 300 s | `BEAT_WINDOW_S`; trade-off in DATA_NOTES.md |
| Packet gap counted as "on schedule" | ≤ 1.25 s | `BEAT_REGULAR_GAP_S` |
| Participant / session ID format (TEMPORARY) | `P001` / `01` | `PARTICIPANT_ID_PATTERN`, `SESSION_ID_PATTERN`; search `ID-FORMAT` |
| Strap name used when searching | `Polar H10` | `SENSOR_NAME_PREFIX` |
| ECG sample rate | 130 Hz | `ECG_SAMPLE_RATE_HZ` |
| HR status line in the terminal | every 30 s | `STATUS_EVERY_S` in `session.py` |
| Timeout of each stop/disconnect step | 5 s | `STOP_STEP_TIMEOUT_S` in `sensor.py` |
| Library versions | bleak 3.1.0, bleakheart 0.2.0 | `requirements.txt` |
