"""Wiring: run_session with a fake strap -> files on disk. Checks the start/stop timestamps and cleanup."""
import asyncio
import io
import json

import pytest

from h10_recorder import markers
from h10_recorder.models import Disconnected, HrPacket, Marker, ReconnectFailed, StopRequested
from h10_recorder.sensor import FoundSensor, SensorError
from h10_recorder.session import run_session
from h10_recorder.storage import SessionExistsError
from tests.conftest import read_csv, read_header

FOUND = FoundSensor("Polar H10 B5A3F42C", "B5A3F42C", None)


class FakeSensor:
    """Stands in for sensor.H10. script(sensor) runs as watch(): it plays the strap by putting items on the queue."""

    def __init__(self, script=None, fail_connect=False):
        self.script, self.fail_connect = script, fail_connect
        self.calls: list[str] = []

    def __call__(self, found, queue, clock, ecg=False):       # used as the sensor_factory
        self.found, self.queue, self.clock, self.ecg = found, queue, clock, ecg
        return self

    battery_pct = 87

    async def connect(self):
        self.calls.append("connect")
        if self.fail_connect:
            raise SensorError("Could not connect within 20 s")
        self.clock.advance(2.0)                                 # connecting takes 2 s
        self.connected_at_ns = self.clock.monotonic_ns()

    async def watch(self):
        self.calls.append("watch")
        if self.script:
            await self.script(self)
        await asyncio.Event().wait()

    async def stop(self):
        self.calls.append("stop")

    def put(self, item_fn, after_s=1.0):
        self.clock.advance(after_s)
        self.queue.put_nowait(item_fn(self.clock.monotonic_ns()))


async def finder_one():
    return [FOUND]


def files(root):
    d = root / "sub-P001" / "ses-01"
    return {k: d / f"sub-P001_ses-01_{k}" for k in ("session.json", "hr.csv", "rr.csv", "events.csv", "ecg.csv")}


async def record(tmp_path, clock, script, **kw):
    sensor = FakeSensor(script, **{k: kw.pop(k) for k in ["fail_connect"] if k in kw})
    lines = []
    info = await run_session("P001", "01", root=tmp_path, clock=clock, finder=kw.pop("finder", finder_one),
                             sensor_factory=sensor, read_markers=False, keep_awake=False, out=lines.append, **kw)
    return info, sensor, lines


async def normal_script(s):
    s.put(lambda t: HrPacket(t, 70, (1024,), True))
    s.put(lambda t: Marker(t, "baseline start"))
    s.put(lambda t: HrPacket(t, 71, (1000, 1048), True))
    s.put(lambda t: StopRequested(t), after_s=57.0)


async def test_a_normal_session_writes_all_files_with_start_and_stop_times(tmp_path, clock):
    info, sensor, lines = await record(tmp_path, clock, normal_script, notes="pilot")
    f = files(tmp_path)
    meta = json.loads(f["session.json"].read_text())
    assert meta["start_utc"] == "2026-11-16T12:00:00.123Z"           # when connecting began
    assert meta["end_utc"] == "2026-11-16T12:01:02.123Z"
    assert meta["duration_s"] == "62.000"
    assert meta["stop_reason"] == "user"
    assert meta["participant_id"] == "P001" and meta["device_id"] == "B5A3F42C" and meta["notes"] == "pilot"
    assert set(meta["software"]) == {"h10_recorder", "bleak", "bleakheart", "python"}

    events = read_csv(f["events.csv"])
    assert [(e["elapsed_s"], e["event"], e["note"]) for e in events] == [
        ("0.000", "recording_started", ""), ("2.000", "connected", "battery 87%"),
        ("4.000", "marker", "baseline start"), ("62.000", "recording_stopped", "user")]
    assert events[0]["time_utc"] == meta["start_utc"] and events[-1]["time_utc"] == meta["end_utc"]

    assert [r["hr_bpm"] for r in read_csv(f["hr.csv"])] == ["70", "71"]
    assert len(read_csv(f["rr.csv"])) == 3
    assert not f["ecg.csv"].exists()
    index = read_csv(tmp_path / "sessions.csv")
    assert index == [{"participant_id": "P001", "session_id": "01", "device_id": "B5A3F42C",
                      "start_utc": meta["start_utc"], "end_utc": meta["end_utc"], "duration_s": "62.000",
                      "stop_reason": "user", "notes": "pilot"}]
    assert sensor.calls == ["connect", "watch", "stop"]
    assert any(l.startswith("Started  2026-11-16T12:00:00.123Z") for l in lines)
    assert any(l.startswith("Stopped  2026-11-16T12:01:02.123Z") for l in lines)
    assert info.stop_reason == "user"


async def test_session_json_exists_with_start_time_while_recording(tmp_path, clock):
    seen = {}

    async def script(s):
        seen["meta"] = json.loads(files(tmp_path)["session.json"].read_text())
        s.put(lambda t: StopRequested(t))
    await record(tmp_path, clock, script)
    assert seen["meta"]["start_utc"] == "2026-11-16T12:00:00.123Z" and seen["meta"]["end_utc"] is None


async def test_connection_lost_ends_the_session_and_warns(tmp_path, clock):
    async def script(s):
        s.put(lambda t: HrPacket(t, 70, (1024,), True))
        s.put(lambda t: Disconnected(t, "connection lost"))
        s.put(lambda t: ReconnectFailed(t), after_s=20.0)
    info, _, lines = await record(tmp_path, clock, script)
    assert info.stop_reason == "connection_lost"
    ev = [e["event"] for e in read_csv(files(tmp_path)["events.csv"])]
    assert ev == ["recording_started", "connected", "disconnected", "reconnect_failed", "recording_stopped"]
    assert any("connection was lost" in l for l in lines)
    assert json.loads(files(tmp_path)["session.json"].read_text())["stop_reason"] == "connection_lost"


async def test_failed_connect_writes_nothing(tmp_path, clock):
    info, sensor, lines = await record(tmp_path, clock, None, fail_connect=True)
    assert info is None
    assert not (tmp_path / "sub-P001").exists() and not (tmp_path / "sessions.csv").exists()
    assert sensor.calls == ["connect", "stop"]
    assert any("Nothing was recorded" in l for l in lines)


async def test_no_strap_found_writes_nothing(tmp_path, clock):
    async def none():
        return []
    info, _, lines = await record(tmp_path, clock, None, finder=none)
    assert info is None and not tmp_path.joinpath("sub-P001").exists()
    assert any("No Polar H10" in l for l in lines)


async def test_several_straps_need_a_device_id(tmp_path, clock):
    other = FoundSensor("Polar H10 AAAA0000", "AAAA0000", None)

    async def two():
        return [FOUND, other]
    info, _, lines = await record(tmp_path, clock, None, finder=two)
    assert info is None and any("--device" in l for l in lines)
    info, _, _ = await record(tmp_path, clock, lambda s: _stop_soon(s), finder=two, device_id="b5a3f42c")
    assert info.device_id == "B5A3F42C"


async def _stop_soon(s):
    s.put(lambda t: StopRequested(t))


async def test_an_existing_session_is_refused_before_connecting(tmp_path, clock):
    (tmp_path / "sub-P001" / "ses-01").mkdir(parents=True)
    sensor = FakeSensor()
    with pytest.raises(SessionExistsError):
        await run_session("P001", "01", root=tmp_path, clock=clock, finder=finder_one, sensor_factory=sensor,
                          read_markers=False, keep_awake=False, out=lambda _: None)
    assert sensor.calls == []


async def test_invalid_ids_are_refused_before_anything_happens(tmp_path, clock):
    with pytest.raises(ValueError):
        await run_session("Jane Doe", "01", root=tmp_path, clock=clock, finder=finder_one,
                          sensor_factory=FakeSensor(), read_markers=False, keep_awake=False, out=lambda _: None)
    assert list(tmp_path.iterdir()) == []


async def test_an_error_while_recording_still_finalises_the_files(tmp_path, clock):
    async def script(s):
        s.put(lambda t: HrPacket(t, 70, (1024,), True))
        s.put(lambda t: "garbage")                                  # recorder raises TypeError
    with pytest.raises(TypeError):
        await record(tmp_path, clock, script)
    f = files(tmp_path)
    meta = json.loads(f["session.json"].read_text())
    assert meta["stop_reason"] == "error" and meta["end_utc"] is not None
    stopped = read_csv(f["events.csv"])[-1]
    assert stopped["event"] == "recording_stopped" and stopped["note"].startswith("error: TypeError")
    assert len(read_csv(f["hr.csv"])) == 1
    assert read_csv(tmp_path / "sessions.csv")[0]["stop_reason"] == "error"


async def test_cancellation_still_finalises_the_files(tmp_path, clock):
    async def script(s):
        s.put(lambda t: HrPacket(t, 70, (1024,), True))
    task = asyncio.create_task(record(tmp_path, clock, script))
    for _ in range(20):
        await asyncio.sleep(0)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    meta = json.loads(files(tmp_path)["session.json"].read_text())
    assert meta["stop_reason"] == "user" and meta["end_utc"] is not None


async def test_packets_queued_before_stop_are_still_written(tmp_path, clock):
    async def script(s):
        s.put(lambda t: StopRequested(t))
        s.put(lambda t: HrPacket(t, 70, (), True), after_s=0.1)    # arrived just before the stream stopped
    await record(tmp_path, clock, script)
    assert len(read_csv(files(tmp_path)["hr.csv"])) == 1


async def test_ecg_file_only_when_ecg_is_on(tmp_path, clock):
    await record(tmp_path, clock, _stop_soon, ecg=True)
    assert read_header(files(tmp_path)["ecg.csv"]) == ["time_utc", "elapsed_s", "ecg_uv", "received_elapsed_s"]
    assert json.loads(files(tmp_path)["session.json"].read_text())["ecg_recorded"] is True


# ---- markers typed in the terminal ----

def test_marker_lines():
    assert markers.parse_line("\n", 5) == Marker(5, "")
    assert markers.parse_line("  task A start \n", 5) == Marker(5, "task A start")
    assert markers.parse_line("Q\n", 5) == StopRequested(5)


async def test_marker_thread_delivers_typed_lines_with_their_time(clock):
    q = asyncio.Queue()
    got = []
    markers.start(q, clock, stream=io.StringIO("first\n\nq\n"), on_marker=got.append)
    items = [await asyncio.wait_for(q.get(), 1) for _ in range(3)]
    assert items == [Marker(clock.monotonic_ns(), "first"), Marker(clock.monotonic_ns(), ""),
                     StopRequested(clock.monotonic_ns())]
    assert len(got) == 2
