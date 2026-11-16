"""Feed queue items to the Recorder and check the rows that come out."""
import asyncio

import pytest

from h10_recorder.clock import SessionTimeline
from h10_recorder.models import (
    Connected, Disconnected, ECGRow, EcgFrame, EventRow, HRRow, HrPacket, Marker, ReconnectFailed, RRRow,
    StopReason, StopRequested,
)
from h10_recorder.recorder import Recorder
from h10_recorder.storage import CsvWriter
from tests.conftest import read_csv


@pytest.fixture
def rec(tmp_path, clock):
    timeline = SessionTimeline(clock)
    mk = lambda name, header: CsvWriter(tmp_path / name, header, clock.monotonic_ns)
    r = Recorder(timeline, mk("hr.csv", HRRow.HEADER), mk("rr.csv", RRRow.HEADER),
                 mk("events.csv", EventRow.HEADER), mk("ecg.csv", ECGRow.HEADER))
    yield r
    for w in r.writers:
        w.close()


def rows(tmp_path, rec, name):
    for w in rec.writers:
        w.flush()
    return read_csv(tmp_path / name)


def test_hr_packet_becomes_one_hr_row_and_one_rr_row_per_beat(tmp_path, rec, clock):
    rec.handle(HrPacket(clock.mono_at(2.0), 72, (840, 856), True))
    hr = rows(tmp_path, rec, "hr.csv")
    assert hr == [{"time_utc": "2026-11-16T12:00:02.123Z", "elapsed_s": "2.000", "hr_bpm": "72", "contact": "true"}]
    rr = rows(tmp_path, rec, "rr.csv")
    assert [r["rr_ms"] for r in rr] == ["820.3125", "835.9375"]
    assert [r["received_elapsed_s"] for r in rr] == ["2.000", "2.000"]
    assert rr[1]["elapsed_s"] == "2.000" and rr[0]["elapsed_s"] == "1.164"      # chained back by 856/1024 s
    assert rr[0]["time_utc"] == "2026-11-16T12:00:01.287Z"
    assert rec.last_hr == 72


def test_packet_without_rr_gives_hr_row_only(tmp_path, rec, clock):
    rec.handle(HrPacket(clock.mono_at(1.0), 70, (), None))
    assert len(rows(tmp_path, rec, "hr.csv")) == 1
    assert rows(tmp_path, rec, "hr.csv")[0]["contact"] == ""
    assert rows(tmp_path, rec, "rr.csv") == []


def test_row_time_is_arrival_time_not_processing_time(tmp_path, rec, clock):
    arrival = clock.mono_at(1.0)
    clock.advance(30)                          # queue was slow to get to it
    rec.handle(HrPacket(arrival, 70, (), None))
    assert rows(tmp_path, rec, "hr.csv")[0]["elapsed_s"] == "1.000"


def test_marker_connect_disconnect_events(tmp_path, rec, clock):
    rec.handle(Marker(clock.mono_at(3.0), "task A, start"))
    rec.handle(Disconnected(clock.mono_at(4.0), "connection lost"))
    rec.handle(Connected(clock.mono_at(9.0)))
    ev = rows(tmp_path, rec, "events.csv")
    assert [(e["elapsed_s"], e["event"], e["note"]) for e in ev] == [
        ("3.000", "marker", "task A, start"), ("4.000", "disconnected", "connection lost"), ("9.000", "connected", "")]
    assert rec.last_hr is None


def test_events_are_on_disk_immediately(tmp_path, rec, clock):
    rec.handle(Marker(clock.mono_at(1.0), "x"))
    assert "marker" in (tmp_path / "events.csv").read_text()       # no flush needed


def test_disconnect_flushes_everything_to_disk(tmp_path, rec, clock):
    rec.handle(HrPacket(clock.mono_at(1.0), 70, (1024,), True))
    assert (tmp_path / "hr.csv").read_text().count("\n") == 1      # buffered
    rec.handle(Disconnected(clock.mono_at(1.5), "connection lost"))
    assert (tmp_path / "hr.csv").read_text().count("\n") == 2
    assert (tmp_path / "rr.csv").read_text().count("\n") == 2


def test_beat_chain_restarts_after_a_disconnect(tmp_path, rec, clock):
    rec.handle(HrPacket(clock.mono_at(1.0), 60, (1024,), True))
    rec.handle(Disconnected(clock.mono_at(1.5), "connection lost"))
    rec.handle(HrPacket(clock.mono_at(20.0), 60, (1024,), True))   # would be 19 s "late" on the old chain
    ev = [e["event"] for e in rows(tmp_path, rec, "events.csv")]
    assert "rr_resync" not in ev
    assert rows(tmp_path, rec, "rr.csv")[-1]["elapsed_s"] == "20.000"


def test_missing_beats_log_rr_resync(tmp_path, rec, clock):
    for k in range(1, 6):
        rec.handle(HrPacket(clock.mono_at(k), 60, (1024,), True))
    for k in range(6, 9):
        rec.handle(HrPacket(clock.mono_at(k), 60, (), False))       # poor contact, no RRs
    rec.handle(HrPacket(clock.mono_at(9), 60, (1024,), True))
    ev = rows(tmp_path, rec, "events.csv")
    assert [e["event"] for e in ev] == ["rr_resync"]
    assert ev[0]["elapsed_s"] == "9.000"


def test_stop_items_return_the_reason(rec, clock):
    assert rec.handle(StopRequested(clock.mono_at(1))) == StopReason.USER
    assert rec.handle(ReconnectFailed(clock.mono_at(2))) == StopReason.CONNECTION_LOST
    assert rec.handle(Marker(clock.mono_at(3), "")) is None


def test_reconnect_failed_is_logged(tmp_path, rec, clock):
    rec.handle(ReconnectFailed(clock.mono_at(25)))
    assert rows(tmp_path, rec, "events.csv")[0]["event"] == "reconnect_failed"


def test_ecg_frame_becomes_one_row_per_sample(tmp_path, rec, clock):
    rec.handle(EcgFrame(clock.mono_at(10.0), 77_000_000_000, (-10, 20, 30)))
    ecg = rows(tmp_path, rec, "ecg.csv")
    assert [e["ecg_uv"] for e in ecg] == ["-10", "20", "30"]
    assert ecg[-1]["elapsed_s"] == "10.000"
    assert ecg[0]["elapsed_s"] == "9.984"                            # 2 samples of 1/130 s earlier


def test_ecg_ignored_when_ecg_is_off(tmp_path, clock):
    t = SessionTimeline(clock)
    mk = lambda name, header: CsvWriter(tmp_path / name, header, clock.monotonic_ns)
    r = Recorder(t, mk("hr.csv", HRRow.HEADER), mk("rr.csv", RRRow.HEADER), mk("events.csv", EventRow.HEADER))
    r.handle(EcgFrame(clock.mono_at(1), 1, (1,)))
    assert not (tmp_path / "ecg.csv").exists()


def test_buffered_rows_reach_disk_within_the_flush_interval(tmp_path, rec, clock):
    rec.handle(HrPacket(clock.mono_at(0.5), 70, (1024,), True))
    clock.advance(5)
    rec.handle(HrPacket(clock.mono_at(5.5), 70, (), True))          # an HR-only packet still flushes rr.csv
    assert (tmp_path / "rr.csv").read_text().count("\n") == 2


def test_unknown_item_is_a_bug(rec):
    with pytest.raises(TypeError):
        rec.handle("not an item")


async def test_run_handles_items_until_stop(tmp_path, rec, clock):
    q = asyncio.Queue()
    for item in [HrPacket(clock.mono_at(1), 70, (1024,), True), Marker(clock.mono_at(2), "m"),
                 StopRequested(clock.mono_at(3)), Marker(clock.mono_at(4), "after stop")]:
        q.put_nowait(item)
    assert await rec.run(q) == StopReason.USER
    assert q.qsize() == 1
    assert len(rows(tmp_path, rec, "hr.csv")) == 1
