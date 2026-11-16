# Data notes
Decisions about how the data is recorded. Copy into the `H10_Data/README.md` codebook when it's written.

## Participant and session IDs: TEMPORARY (ID-FORMAT)
Placeholder rules: participant `P` + 3 digits (`P001`), session 2 digits (`01`). Anything else is rejected,
so names can't be typed in. Defined in `h10_recorder/config.py` (`PARTICIPANT_ID_PATTERN`, `SESSION_ID_PATTERN`).
To change it, search the project for `ID-FORMAT`.

## Clock
The H10 heart-rate stream has no timestamps. All times come from the recording computer: one UTC reading at session start
(truncated to the millisecond), then the computer's monotonic clock. `time_utc` = start + `elapsed_s`, so a clock sync
mid-session can't make it jump. The monotonic clock keeps counting while the computer sleeps (on macOS `CLOCK_MONOTONIC`,
because Python's default `time.monotonic()` there pauses during sleep).

## Session start and stop
- `start_utc` = when the program began connecting to the strap (elapsed 0). Logged as `recording_started` in `events.csv`.
  The first data rows come a few seconds later, after `connected`.
- `end_utc` = after the strap was stopped and disconnected, the moment `recording_stopped` was logged (same clock reading).
  Its note gives the reason: `user`, `connection_lost`, or `error: ...`.
- Both are in `session.json` (with `duration_s` and `stop_reason`) and in `sessions.csv`.
- If the program is killed outright, `end_utc` stays empty in `session.json` and the session has no `sessions.csv` row.

## hr.csv
One row per packet (~1/s), timed when the packet reached the computer. `contact` is `true`/`false`, empty if unsupported.

## rr.csv: precision
The sensor reports RR in 1/1024 s. `rr_ms` = raw × 1000 / 1024, unrounded (e.g. 840 → 820.3125).
bleakheart's HR decoder rounds to whole ms (up to 0.5 ms error per beat), so `sensor.py` decodes the packets itself.

## rr.csv: beat times
Each RR row is one beat. `elapsed_s`/`time_utc` = **estimated** time of the beat that ends the interval.
`received_elapsed_s` = when its packet reached the computer, kept so the estimate can be redone in analysis.

Why estimate: the H10 sends a packet about once a second with the beats completed since the last packet, so a
beat can be up to ~1 s older than its packet, and several beats share one packet.

Method (`BeatTimeline` in `h10_recorder/clock.py`):
1. RR intervals are measured by the sensor and are exact, so beats are chained: beat time = offset + sum of RRs so far.
2. A beat can't happen after the packet that reports it. So offset = the smallest (arrival time − RR sum) among the
   packets of the **last 300 s**. A sliding window, not an all-time minimum, lets the offset follow clock drift
   between sensor and computer. An all-time minimum drifted up to 1.3 s over 12 h at 30 ppm.
   The 300 s is how far back it *looks*, **not a minimum recording length**: a 2-minute session is recorded normally,
   every beat gets a time from the first packet on, and the estimate simply gets tighter during the first minutes
   as better-timed packets come in. **To revisit with real H10 data** (knob `BEAT_WINDOW_S` in `config.py`).
   Because raw arrival times are saved, beat times can be recomputed later with any window.
3. A packet arriving more than 2 s later than the chain predicts is either a Bluetooth delay or missing beats:
   - If it came on the regular ~1 s packet schedule (gap since the previous packet, *including packets without RRs*,
     ≤ 1.25 s), nothing was delayed, so beats are missing. Re-anchor to its arrival time and log `rr_resync`.
     This is the usual case after poor skin contact, when the H10 keeps sending packets without RRs.
   - After an irregular gap it may be a delay. It is placed on the chain, and if the next packet is late too,
     the chain re-anchors then.
4. After a reconnect the chain starts fresh.

Accuracy, from the independent review's simulation of the Android version (same algorithm) (realistic H10 timing: 1 Hz packets, BLE latency 5–60 ms, 20 ppm drift):
- Typical error with no lost beats: p50 / p99 = 0.05 / 0.11 s at ~75 bpm, 0.06 / 0.19 s at ~60 bpm.
- At a resting ~60 bpm, beats keep the same phase relative to the 1 s packets, which is why a long window is needed:
  a 30 s window gave 0.26 / 0.87 s.
- Early in a session, beats may be placed up to ~1 s late until the window holds a well-timed packet.

Known limitations:
- If the sensor silently leaves out one beat (gap under 2 s), later beats are ~1 RR early until the window passes that
  point, i.e. up to 300 s. The review found a faster fix: a lower bound from "the beat wasn't in the previous packet".
  It only works if the H10's internal detection delay is below an assumed slack, and wrong assumptions caused ~34
  false resyncs per hour in simulation. **To do:** measure that delay on a real H10 (distribution of arrival − chain
  in recorded data) before adopting it.
- A beat time is never earlier than the previous one. A beat just before recording started can have a negative `elapsed_s`.
- Everything needed to recompute beat times later is recorded: every packet's arrival is in `hr.csv`, and each beat's
  packet arrival is `received_elapsed_s` in `rr.csv`.

## Connection loss
If the strap disconnects, the program logs `disconnected` and retries for up to 20 s. If that fails it logs
`reconnect_failed`, ends the session (files closed and finalised), beeps and prints a warning. A new recording then
needs a new session ID.

## ecg.csv (only with `--ecg`)
One row per sample, 130 Hz, `ecg_uv` in microvolts. Unlike HR, ECG frames carry a sensor timestamp (the frame's last sample),
so samples are spaced exactly 1/130 s apart on the sensor's clock. Sensor time is mapped to session time with the same rule as beats:
the smallest (arrival − sensor time) over the last 300 s, since a sample can't arrive before it was measured.
`received_elapsed_s` is the frame's arrival. Not yet checked on a real strap.
