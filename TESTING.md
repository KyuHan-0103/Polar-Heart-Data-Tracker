# Testing

## Setup
`source env.sh` loads the project-local toolchain (JDK 21, Android SDK, Gradle caches in `.tooling/`).
It's the Android equivalent of activating a venv. Nothing is installed system-wide.

## Two kinds of tests
| | Where | Runs on | Needs |
|---|---|---|---|
| Unit + wiring tests | `app/src/test/` | this Mac (JVM) | nothing |
| Hardware tests | `app/src/androidTest/` | Android phone over USB | phone (Android 13+), worn H10 |

```
./gradlew testDebugUnitTest                              # all JVM tests
./gradlew testDebugUnitTest --tests '*SessionRecorderTest' # one class
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.h10DeviceId=XXXXXXXX
```
Report: `app/build/reports/tests/testDebugUnitTest/index.html`.
Hardware tests skip themselves unless `h10DeviceId` is given. Close the Polar app first (H10 allows 2 connections).

## How the JVM tests work without an H10
- `FakeHrSensor` stands in for the strap. It behaves like the Polar SDK: streams only while connected, a dropped
  connection fails the stream with `SensorConnectionLost`, cancelling stops the stream. It records every call.
- `FakeClock` controls time. It also follows coroutine virtual time, so the 20 s reconnect timeout runs instantly.
- `H10Fixtures` is realistic data: ~1 packet/s, 0-2 RR intervals each, RR in 1/1024 s.
- `CsvTestUtils` reads files back with its own parser, so tests check what is really on disk.
- `PolarSensorTest` checks the real `PolarSensor` against a mocked `PolarBleApi` (MockK), with the test playing the SDK's callbacks.
- `SessionRecorderTest` is the wiring test: fake sensor -> recorder -> CSV writer -> files on disk.

## Status
Tests are written before the code. A test fails with `NotImplementedError` until its module is written.
Done so far: `sensor/`, `data/`, `recording/`: all JVM tests pass. `RecordingService` needs a phone; only its notification wording is unit-tested. Next: `ui/` + `MainActivity`.
