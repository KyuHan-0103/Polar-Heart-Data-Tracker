"""Wires everything together for one session.

Order (README "Contracts"):
 1. Check the IDs and that the session folder doesn't exist yet.
 2. Find the strap and connect (max CONNECT_TIMEOUT_S). If this fails NOTHING is written,
    so a failed attempt doesn't use up a session ID.
 3. Create the folder, session.json (start_utc), the CSVs; log recording_started + connected.
 4. Record until: Stop (q / Ctrl+C), reconnect gave up, or an error.
 5. Always, in this order: stop streams + disconnect -> log recording_stopped -> close CSVs ->
    session.json gets end_utc + stop_reason -> one row appended to sessions.csv.
"""
from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import platform
import signal
import subprocess
import sys
from dataclasses import replace
from datetime import datetime
from pathlib import Path
from typing import Callable

from . import __version__, config, markers
from .clock import SessionClock, SessionTimeline, SystemClock, format_utc
from .models import (
    ECGRow, EventRow, Events, HRRow, LiveStatus, Marker, Phase, QueueItem, ReconnectFailed, RRRow, SessionInfo,
    StopReason, StopRequested,
)
from .recorder import Recorder
from .storage import (
    ECG, EVENTS, HR, RR, CsvWriter, SessionExistsError, SessionPaths, append_sessions_index, write_session_info,
)

log = logging.getLogger(__name__)
STATUS_EVERY_S = 30.0


def software_versions() -> dict[str, str]:
    from importlib.metadata import version
    return {"h10_recorder": __version__, "bleak": version("bleak"), "bleakheart": version("bleakheart"),
            "python": platform.python_version()}


def local_time(utc_ns: int) -> str:
    return datetime.fromtimestamp(utc_ns / 1e9).astimezone().strftime("%Y-%m-%d %H:%M:%S %Z")


class SessionControl:
    """Lets another thread (the window) send markers and Stop into a running session. Works once the session
    is recording; before that (searching/connecting) add_marker/stop return False and the caller should
    cancel the session's task instead, which writes nothing."""

    def __init__(self, clock: SessionClock) -> None:
        self.clock = clock
        self._loop: asyncio.AbstractEventLoop | None = None
        self._queue: asyncio.Queue[QueueItem] | None = None

    def attach(self, loop: asyncio.AbstractEventLoop, queue: asyncio.Queue[QueueItem]) -> None:
        self._loop, self._queue = loop, queue

    def _send(self, item: QueueItem) -> bool:
        if self._loop is None or self._queue is None or self._loop.is_closed():
            return False
        self._loop.call_soon_threadsafe(self._queue.put_nowait, item)
        return True

    def add_marker(self, note: str) -> bool:
        return self._send(Marker(self.clock.monotonic_ns(), note.strip()))   # time = when the button was clicked

    def stop(self) -> bool:
        return self._send(StopRequested(self.clock.monotonic_ns()))


async def choose_sensor(device_id: str | None, finder: Callable, out: Callable[[str], None]):
    out(f"Searching for {config.SENSOR_NAME_PREFIX} straps ({config.SCAN_TIMEOUT_S:g} s)…")
    found = await finder()
    if device_id:
        found = [s for s in found if s.device_id.upper() == device_id.upper()]
    if not found:
        raise_msg = f"No {config.SENSOR_NAME_PREFIX}" + (f" with ID {device_id}" if device_id else "") + " found. " \
            "Is it worn (moist electrodes), nearby, and not connected to 2 other devices?"
        raise LookupError(raise_msg)
    if len(found) > 1:
        ids = ", ".join(s.device_id for s in found)
        raise LookupError(f"Several straps found ({ids}). Choose one with --device <ID>.")
    return found[0]


async def run_session(
    participant_id: str,
    session_id: str,
    *,
    device_id: str | None = None,
    ecg: bool = config.ECG_ENABLED,
    notes: str = "",
    root: Path = config.DATA_ROOT,
    clock: SessionClock | None = None,
    finder: Callable | None = None,
    sensor_factory: Callable | None = None,
    read_markers: bool = True,
    keep_awake: bool = True,
    out: Callable[[str], None] = print,
    found=None,
    live: LiveStatus | None = None,
    control: SessionControl | None = None,
) -> SessionInfo | None:
    """Records one session. Returns the final SessionInfo, or None if the strap couldn't be found/connected
    (nothing written). Raises on invalid IDs, an existing session, or an unexpected error (after cleanup).
    found: a strap already picked (ui.py) skips the search. live: updated for the window as things happen.
    control: attached once recording, so the window can send markers/Stop."""
    clock = clock or (control.clock if control else SystemClock())
    live = live if live is not None else LiveStatus()
    paths = SessionPaths(root, participant_id, session_id)          # validates IDs
    if paths.dir.exists():
        raise SessionExistsError(f"Session folder already exists: {paths.dir}")

    if finder is None or sensor_factory is None:
        from . import sensor as ble                                  # only here: tests run without Bluetooth
        finder = finder or ble.find
        sensor_factory = sensor_factory or ble.H10
    queue: asyncio.Queue[QueueItem] = asyncio.Queue()

    # 2. find + connect
    if found is None:
        live.phase = Phase.SEARCHING
        try:
            found = await choose_sensor(device_id, finder, out)
        except LookupError as e:
            out(str(e))
            live.phase, live.stop_reason = Phase.STOPPED, "not_started"
            return None
    live.phase, live.device_id = Phase.CONNECTING, found.device_id
    out(f"Connecting to {found.name}…")
    sensor = sensor_factory(found, queue, clock, ecg=ecg)
    timeline = SessionTimeline(clock)       # session start = when we began connecting
    try:
        await sensor.connect()
    except asyncio.CancelledError:          # cancelled while connecting (window's Cancel): nothing written
        await sensor.stop()
        live.phase, live.stop_reason = Phase.STOPPED, "not_started"
        raise
    except Exception as e:                  # noqa: BLE001 - SensorError, or any SDK surprise: nothing written
        await sensor.stop()
        out(f"{e}. Nothing was recorded; session {session_id} is still unused.")
        live.phase, live.stop_reason = Phase.STOPPED, "not_started"
        return None

    # 3. create the session
    info = SessionInfo(participant_id, session_id, found.device_id, timeline.start_utc_ns, ecg_recorded=ecg,
                       software=software_versions(), notes=notes)
    writers: list[CsvWriter] = []
    try:
        paths.create_dir()
        write_session_info(paths, info)

        def new(kind: str, header: list[str]) -> CsvWriter:
            writers.append(CsvWriter(paths.file(kind), header, clock.monotonic_ns))
            return writers[-1]

        recorder = Recorder(timeline, hr=new(HR, HRRow.HEADER), rr=new(RR, RRRow.HEADER),
                            events=new(EVENTS, EventRow.HEADER), ecg=new(ECG, ECGRow.HEADER) if ecg else None,
                            live=live)
    except BaseException:
        for w in writers:
            w.close()
        await sensor.stop()
        live.phase, live.stop_reason = Phase.STOPPED, StopReason.ERROR
        raise
    live.start_utc_ns, live.start_monotonic_ns = timeline.start_utc_ns, timeline.start_monotonic_ns
    live.battery_pct, live.folder, live.phase = sensor.battery_pct, str(paths.dir), Phase.RECORDING
    if control is not None:
        control.attach(asyncio.get_running_loop(), queue)
    # 4. record. Everything from here on is inside try, so a failure at any point still cleans up.
    background: list[asyncio.Task] = []
    caffeinate = None
    remove_sigint: Callable[[], None] = lambda: None
    reason, message, error = StopReason.ERROR, None, None
    try:
        recorder.log_event(Events.RECORDING_STARTED, "", timeline.start_monotonic_ns)
        battery = f"battery {sensor.battery_pct}%" if sensor.battery_pct is not None else ""
        recorder.log_event(Events.CONNECTED, battery, sensor.connected_at_ns)
        out(f"Recording sub-{participant_id} ses-{session_id} -> {paths.dir}")
        out(f"Started  {format_utc(timeline.start_utc_ns)}  ({local_time(timeline.start_utc_ns)})"
            + (f"  {battery}" if battery else ""))
        out(markers.HELP)
        background.append(asyncio.create_task(sensor.watch(), name="reconnect"))
        background.append(asyncio.create_task(_status_lines(recorder, timeline, out), name="status"))
        if read_markers:
            markers.start(queue, clock, on_marker=lambda m: out("  marker logged" + (f": {m.note}" if m.note else "")))
        caffeinate = _keep_awake() if keep_awake else None
        remove_sigint = _stop_on_sigint(queue, clock)
        reason = await recorder.run(queue)
    except asyncio.CancelledError:
        reason = StopReason.USER            # e.g. KeyboardInterrupt on platforms without signal handlers
        raise
    except BaseException as e:
        message, error = f"{type(e).__name__}: {e}", e
        raise
    finally:
        # 5. always clean up, in the contract order
        live.phase = Phase.STOPPING
        remove_sigint()
        for t in background:
            t.cancel()
        for t in background:
            with contextlib.suppress(BaseException):
                await t
        await sensor.stop()
        failures: list[str] = []

        def step(what: str, fn: Callable[[], object]) -> None:
            try:
                fn()
            except Exception as e:      # noqa: BLE001 - keep going so the other files are still finalised
                failures.append(f"{what}: {e}")
                log.exception("cleanup step failed: %s", what)

        if error is None:                   # data that arrived before the stream stopped is still recorded
            step("write remaining packets", lambda: _drain(queue, recorder))
        stop_ns = clock.monotonic_ns()      # one reading: recording_stopped and end_utc are the same moment
        stop = timeline.at(stop_ns)
        note = reason + (f": {message}" if message else "")
        step("log recording_stopped", lambda: recorder.log_event(Events.RECORDING_STOPPED, note, stop_ns))
        for w in writers:
            step("close csv", w.close)
        final = replace(info, end_utc_ns=stop.utc_ns, stop_reason=reason if not failures else StopReason.ERROR)
        step("session.json", lambda: write_session_info(paths, final))
        step("sessions.csv", lambda: append_sessions_index(root, final))
        if caffeinate is not None:
            caffeinate.terminate()
        live.hr_bpm = live.contact = None
        live.stop_utc_ns, live.duration_s, live.stop_reason = stop.utc_ns, stop.elapsed_s, final.stop_reason
        live.problems.extend(failures)
        live.phase = Phase.STOPPED

        out(f"Stopped  {format_utc(stop.utc_ns)}  ({local_time(stop.utc_ns)})  after {stop.elapsed_s / 60:.1f} min"
            f"  reason: {reason}")
        if reason == StopReason.CONNECTION_LOST:
            out("\a!!! The H10 connection was lost and could not be restored within "
                f"{config.CONNECT_TIMEOUT_S:g} s. Data up to the drop is saved. "
                "Check the strap is worn and nearby, then start a NEW session ID.")
        for f in failures:
            out(f"!!! Cleanup problem: {f}")
    return final


def _drain(queue: asyncio.Queue[QueueItem], recorder: Recorder) -> None:
    while not queue.empty():
        item = queue.get_nowait()
        if not isinstance(item, (StopRequested, ReconnectFailed)):
            recorder.handle(item)


async def _status_lines(recorder: Recorder, timeline: SessionTimeline, out: Callable[[str], None]) -> None:
    while True:
        await asyncio.sleep(STATUS_EVERY_S)
        minutes = timeline.now().elapsed_s / 60
        hr = f"{recorder.last_hr} bpm" if recorder.last_hr is not None else "no heart rate (disconnected?)"
        out(f"  [{minutes:5.1f} min] {hr}")


def _stop_on_sigint(queue: asyncio.Queue[QueueItem], clock: SessionClock) -> Callable[[], None]:
    """Ctrl+C -> a normal Stop (files closed properly) instead of an exception. Returns an undo function."""
    loop = asyncio.get_running_loop()
    try:
        loop.add_signal_handler(signal.SIGINT, lambda: queue.put_nowait(StopRequested(clock.monotonic_ns())))
    except (NotImplementedError, RuntimeError, ValueError):   # Windows / not the main thread
        return lambda: None
    return lambda: loop.remove_signal_handler(signal.SIGINT)


def _keep_awake() -> subprocess.Popen | None:
    """macOS: stop the Mac idle-sleeping mid-session (sleep pauses Bluetooth). Closing the lid still sleeps."""
    if sys.platform != "darwin":
        return None
    try:
        return subprocess.Popen(["caffeinate", "-i", "-w", str(os.getpid())])
    except OSError:
        return None
