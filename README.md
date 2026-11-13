The purpose of this script/project is to pull heart rate data from Polar devices. Particularly from Polar H10. This data will then be used for research purposes.
The current references as to how to utilize the Polar API is through this github repo: https://github.com/polarofficial/polar-ble-sdk

We only want Heart Rate and RR intervals
Raw ECG and accelerometer signals are uneeded at the moment

The raw files should NEVER be edited. Cleaning and analysis go in a separate folder, produced by scripts.
Participants should only be identified by code. No names or birthdates in files or filenames. 

Here is the target structure of the program (up for edits):
app/src/
├── main/
│   ├── AndroidManifest.xml          # Bluetooth permissions, foreground service
│   ├── res/xml/data_extraction_rules.xml  # no cloud backup / device transfer of participant data
│   └── java/com/example/h10recorder/
│       ├── MainActivity.kt
│       ├── sensor/
│       │   ├── HrSensor.kt          # interface the app uses; lets tests swap in a fake H10
│       │   └── PolarSensor.kt       # only file using the SDK: search, connect, HR stream, battery
│       ├── recording/
│       │   ├── RecordingService.kt  # foreground service so recording survives the screen turning off
│       │   ├── SessionRecorder.kt   # collects the stream, writes rows, logs events and disconnects
│       │   └── CsvWriter.kt         # buffered writes, flushed every few seconds
│       ├── data/
│       │   ├── Models.kt            # HrRow, RrRow, EventRow, SessionInfo
│       │   ├── SessionFiles.kt      # folder/file naming, ID validation, sessions.csv, zip export
│       │   └── TimeUtils.kt         # phone clock -> UTC + elapsed time, RR unit conversion
│       └── ui/
│           ├── SetupScreen.kt       # participant + session IDs, pick the sensor
│           ├── RecordingScreen.kt   # live HR, timer, event buttons, stop
│           ├── SessionsScreen.kt    # past sessions, export
│           └── RecordingViewModel.kt
├── test/                            # JVM unit + wiring tests, run on the Mac (no phone, no H10)
│   └── java/com/example/h10recorder/
│       ├── testutil/                # FakeHrSensor, FakeClock, H10Fixtures, CsvTestUtils
│       ├── data/                    # TimeUtils, Models, SessionFiles
│       ├── recording/               # CsvWriter, SessionRecorder (fake H10 -> files on disk)
│       └── sensor/                  # Polar SDK sample -> app sample mapping
└── androidTest/                     # on a phone with a real H10; skipped unless a device ID is given
    └── java/com/example/h10recorder/sensor/H10HardwareTest.kt

Here is the current suggested structure of the data:
H10_Data/
├── README.md                             # codebook: every file, column, unit, clock
├── sessions.csv                          # one row per session: IDs, date, duration, notes
└── sub-P001/
    └── ses-01/
        ├── sub-P001_ses-01_session.json  # metadata for this recording
        ├── sub-P001_ses-01_hr.csv        # time_utc, elapsed_s, hr_bpm, contact
        ├── sub-P001_ses-01_rr.csv        # time_utc, elapsed_s, rr_ms, received_elapsed_s (one row per beat)
        └── sub-P001_ses-01_events.csv    # time_utc, elapsed_s, event, note

See DATA_NOTES.md for how times, RR precision and beat times work, and the temporary participant ID format.

## Review tests
`app/src/test/java/com/example/h10recorder/review/` holds tests written by an independent reviewer agent that had no part in
writing the code. They run with the rest of the suite and stay as regression tests for the bugs they found.

## Setup and tests
See TESTING.md. Short version:

    source env.sh                  # project-local toolchain, like activating a venv
    ./gradlew testDebugUnitTest    # all JVM tests, no phone or H10 needed

## How it works: contracts
These are the rules each part promises the rest of the program. Each rule has a test (in brackets), so
breaking one fails the test suite. ✅ = written, ⏳ = rule and tests exist, code not written yet.

### Sensor: `sensor/HrSensor.kt`, `sensor/PolarSensor.kt` ✅
- `PolarSensor.kt` is the **only** file that imports the Polar SDK. Everything else uses the `HrSensor` interface.
- **The stream always stops before disconnecting.** `disconnect()` calls `stopHrStreaming()` first, then
  `disconnectFromDevice()`. If stopping fails, disconnecting still happens. Safe to call when already disconnected.
  When the link is already gone, the stop call is skipped (no stream can be running, and the SDK would block for 10 s). [PolarSensorTest]
- `connect()` returns only when the strap is ready to stream HR (the SDK's `FEATURE_HR` ready callback), not
  just when the Bluetooth link is up. It has no timeout of its own. The caller sets one. If it is cancelled
  (timed out or Stop), the connection attempt is abandoned, so the SDK isn't left connecting in the background. [PolarSensorTest]
- `hrStream()` throws `SensorConnectionLost` when the connection drops. Counted as a drop:
  - the stream just ending;
  - SDK disconnect errors;
  - the SDK *cancelling* its HR channel, which is how SDK 8.5.0 reports a lost link;
  - "service not available / notification not enabled / device not found" after a slow GATT setup.

  Any other error is treated as unexpected. Cancelling the collection stops the stream. [PolarSensorTest, ReviewSdkDisconnectTest]
- `events` emits `Connected` once per connection and `Disconnected` once (with the SDK's reason), only for a connection that was ready. [PolarSensorTest]
- The SDK's own automatic reconnection is **off**. `SessionRecorder` owns reconnecting. [PolarSensorTest]
- RR intervals are passed on as raw 1/1024 s values. The SDK's rounded `rrsMs` is ignored. [PolarSensorMappingTest]
- `batteryLevel()` returns 0–100, or null if unknown. [PolarSensorTest]

### Data: `data/` ✅
- **Raw data is never overwritten.** `createSessionDir` refuses an existing session folder, and `CsvWriter` refuses an existing file. [SessionFilesTest, CsvWriterTest]
- `session.json` is written at start (`end_utc` null) and replaced once at the end. Each write goes to a temp file, which is saved to storage (fsync) and then renamed, so neither a crash nor a power cut can leave a half-written file. [SessionFilesTest]
- IDs use ASCII digits only (`[0-9]`, since `\d` also matches e.g. Arabic-Indic digits on Android) and are checked before any file path is built, so text like `../` can't reach the file system. Error messages never repeat the
  rejected text (in case a name was typed). [SessionFilesTest]
- Times: `time_utc` is ISO-8601 UTC, milliseconds, truncated. `elapsed_s` has 3 decimals, also truncated, so `time_utc` = start + `elapsed_s` exactly in every row. Numbers always use `.` whatever
  the phone's language. Times come from one clock reading at session start plus the monotonic clock. [TimeUtilsTest, ModelsTest]
- `rr_ms` is exact (raw × 1000 / 1024), never rounded. Beat times come from `BeatTimeline` (see DATA_NOTES.md). [BeatTimelineTest]
- CSV: UTF-8, header first, `\n` line endings, RFC 4180 quoting (fields with `,` `"` or newlines are quoted). [ModelsTest, CsvWriterTest]
- `sessions.csv` is append-only: one row per finished session. Zip export only reads session files and must be saved outside `H10_Data/`. [SessionFilesTest]

### Recording: `recording/` ✅
**`CsvWriter.kt`**
- Opens files with `CREATE_NEW`, so the operating system itself refuses to overwrite an existing file. The header is written and saved straight away. [CsvWriterTest]
- Rows are buffered and flushed every 5 s, on `flush()`, and on `close()`. Every flush is also forced to storage (fsync).
  `flushIfDue()` lets the recorder flush all three files on every HR packet, so a quiet file (events, rr) doesn't sit in memory.
  `writeIfOpen()` returns false instead of throwing once the file is closed. [CsvWriterTest, ReviewSessionRecorderTest]
- A row must have as many fields as the header. Writing after `close()` fails, and closing twice is fine. Safe to use from several threads. [CsvWriterTest]

**`SessionRecorder.kt`**: `run(config)` does, in order:
1. Checks the IDs and that the session doesn't exist yet, **before connecting**. [SessionRecorderTest]
2. Connects. Failed attempts are retried every 1 s for up to 20 s. If that fails: status `Stopped(CONNECTION_LOST)`, **nothing is written**, and the session ID stays free. [SessionRecorderTest]
3. Creates the session folder, `session.json` and the three CSVs. Logs `recording_started` and `connected`. Status `Recording`.
4. For each HR packet: one `hr.csv` row (packet arrival time), one `rr.csv` row per beat (time from `BeatTimeline`), `liveHr` updated. A beat-chain resync logs `rr_resync`. [SessionRecorderTest]
5. **Connection dropped** (`SensorConnectionLost`): flush all files to disk at once, log `disconnected` (note = the error message), set `liveHr` to null, status `Reconnecting`,
   restart the beat chain, then wait 1 s and retry every 1 s for up to 20 s.
   - Back: log `connected`, status `Recording`, continue.
   - Not back: log `reconnect_failed`, end the session, status `Stopped(CONNECTION_LOST)`. `run()` returns normally. [SessionRecorderTest]
6. Only connection-type errors (`IOException`) are retried. Any other error ends the session: status `Stopped(ERROR)`, and the error is passed on. Data before the error is kept. [SessionRecorderTest]
   - **Every** way out of `run()` leaves a `Stopped` status for the UI, including errors before any file exists: bad IDs, a blank device ID, an existing session, an SDK error while connecting. [ReviewSessionRecorderTest]
   - A cancellation counts as Stop **only if the recorder's own coroutine was cancelled**. One arriving from elsewhere (e.g. the SDK) is reported as `Stopped(ERROR)`, never as a silent Stop. [ReviewSdkDisconnectTest]
7. **Cleanup always runs** (Stop, give-up or crash), even while being cancelled, in this order:
   stop stream + disconnect → log `recording_stopped` (note = reason) → close the 3 CSVs → write `end_utc` to `session.json` → append to `sessions.csv`.
   Every step runs even if an earlier one fails. Any cleanup failure turns the status into `Stopped(ERROR, "Cleanup failed: …")`. [SessionRecorderTest]
- **Stop = cancel the coroutine running `run()`.** `logEvent()` (marker buttons) can be called from any thread, but not the main thread, because it writes to disk.
  Each event is forced to disk immediately. It is ignored (never throws) when no session is open, including while Stop's cleanup is closing the files. [ReviewSessionRecorderTest]
- `run()` does no thread switching. The caller (`RecordingService`) runs it on `Dispatchers.IO`. A recorder runs one session at a time.

**`RecordingService.kt`** (Android foreground service; only its wording is unit-tested [RecordingNotificationsTest])
- `RecordingService.start(context, participant, session, deviceId, notes)` / `RecordingService.stop(context)`. One recording at a time.
  Every start request is answered with `startForeground` at once, even one that's ignored, because Android crashes an app that doesn't.
  A stop with nothing running just closes the service.
  The UI reads `RecordingService.activeRecorder` for live HR and status.
- Its job is `superviseRecording()`: run the recorder with the notification updater alongside, then **always** run the
  finish step exactly once, after the updater has stopped, however recording ended. The finish step shows the alert,
  releases the wake lock and stops the service. It runs even after Stop, when the coroutine is already cancelled. [RecordingJobTest, ReviewFollowUpTest]
- Shows a permanent notification with live HR and status. If recording ends for any reason other than Stop
  (connection lost, error), it shows a **high-priority, vibrating "Recording stopped" alert**.
- Holds a partial wake lock while recording. A foreground service keeps the app alive, but not the CPU awake, and
  without the lock the 1 s / 20 s reconnect timers could be delayed while the phone sleeps. The lock is capped at 12 h.
- Not restarted automatically if Android kills it (`START_NOT_STICKY`), because a restarted recording would need a new session ID. If Android destroys it
  mid-recording, it cancels the recorder and waits up to 3 s for cleanup before shutting down the SDK.
- Before `start`, the UI must have the Bluetooth permissions (Android 14 refuses the service otherwise) and should have notification permission (for the alert).
- Data goes to the app's own storage: `Android/data/com.example.h10recorder/files/H10_Data/`. **Android deletes this if the app is uninstalled.** Export first.
- App data is excluded from cloud backup and device-to-device transfer (`res/xml/data_extraction_rules.xml`). It leaves the phone only through export.

## Settings you can change (knobs)
| Setting | Current | Where |
|---|---|---|
| Give up (re)connecting after | 20 s | `CONNECTION_TIMEOUT` in `recording/SessionRecorder.kt` |
| Pause between reconnect attempts | 1 s | `RETRY_DELAY` in `recording/SessionRecorder.kt` |
| CSV flush interval | 5 s | `DEFAULT_FLUSH_INTERVAL_NANOS` in `recording/CsvWriter.kt` |
| Beat-chain resync threshold | 2 s | `DEFAULT_GAP_RESET_S` in `data/TimeUtils.kt` (`BeatTimeline`) |
| Beat-chain offset window | 300 s | `DEFAULT_WINDOW_S` in `data/TimeUtils.kt` (`BeatTimeline`); trade-off in DATA_NOTES.md |
| Packet gap counted as "on schedule" | ≤ 1.25 s | `REGULAR_GAP_S` in `data/TimeUtils.kt` (`BeatTimeline`) |
| Participant / session ID format (TEMPORARY) | `P001` / `01` | `Ids` in `data/SessionFiles.kt`; search `ID-FORMAT` |
| Strap name used when searching | `Polar H10` | `H10_NAME_PREFIX` in `sensor/PolarSensor.kt` |
| SDK features enabled | HR, battery | `FEATURES` in `sensor/PolarSensor.kt` |
| Polar SDK version | 8.5.0 | `polarSdk` in `gradle/libs.versions.toml` |
| Minimum Android version | 33 (Android 13) | `minSdk` in `app/build.gradle.kts` (Polar SDK requires ≥ 33) |
| Wake lock safety cap (longest recording kept awake) | 12 h | `MAX_RECORDING_MS` in `recording/RecordingService.kt` |
| Where data is stored on the phone | app storage `H10_Data/` | `dataRoot()` in `recording/RecordingService.kt` |
| Notification wording | | `RecordingNotifications` in `recording/RecordingService.kt` |
| Hardware test timeouts | 20–30 s | `withTimeout(...)` in `androidTest/.../H10HardwareTest.kt` |

When you change a knob, run the tests. Tests that check the old value (e.g. the 20 s give-up) read the constant, so
they follow the change, apart from the ID-format tests, which list example IDs.
