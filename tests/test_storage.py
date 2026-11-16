import os

import pytest

from h10_recorder import storage
from h10_recorder.models import SessionInfo
from h10_recorder.storage import (
    CsvWriter, SessionExistsError, SessionPaths, append_sessions_index, csv_line, require_valid_ids,
    write_session_info,
)
from tests.conftest import START_UTC_NS, read_csv

T = START_UTC_NS // 1_000_000 * 1_000_000


# ---- IDs (ID-FORMAT: update these examples when the format changes) ----

@pytest.mark.parametrize("p,s", [("P001", "01"), ("P999", "99")])
def test_valid_ids(p, s):
    assert require_valid_ids(p, s) == (p, s)


@pytest.mark.parametrize("p,s", [("p001", "01"), ("P01", "01"), ("P0001", "01"), ("Jane", "01"), ("P001", "1"),
                                 ("P001", "001"), ("../x", "01"), ("P00١", "01"), ("P001\n", "01"), ("", "")])
def test_invalid_ids_are_rejected_without_echoing_them(p, s):
    with pytest.raises(ValueError) as e:
        require_valid_ids(p, s)
    if p not in ("", "P001"):
        assert p not in str(e.value)


# ---- naming ----

def test_layout_and_file_names(tmp_path):
    paths = SessionPaths(tmp_path, "P001", "01")
    assert paths.dir == tmp_path / "sub-P001" / "ses-01"
    assert paths.file(storage.HR).name == "sub-P001_ses-01_hr.csv"
    assert paths.file(storage.RR).name == "sub-P001_ses-01_rr.csv"
    assert paths.file(storage.EVENTS).name == "sub-P001_ses-01_events.csv"
    assert paths.file(storage.ECG).name == "sub-P001_ses-01_ecg.csv"
    assert paths.file(storage.SESSION).name == "sub-P001_ses-01_session.json"


def test_create_dir_refuses_an_existing_session(tmp_path):
    paths = SessionPaths(tmp_path, "P001", "01")
    paths.create_dir()
    with pytest.raises(SessionExistsError):
        paths.create_dir()


def test_default_data_root_is_in_downloads():
    from pathlib import Path
    from h10_recorder import config
    assert config.DATA_ROOT == Path.home() / "Downloads" / "H10_Data"


# ---- CSV formatting ----

@pytest.mark.parametrize("field,out", [("plain", "plain"), ("a,b", '"a,b"'), ('say "hi"', '"say ""hi"""'),
                                       ("two\nlines", '"two\nlines"'), ("cr\r", '"cr\r"'), ("", "")])
def test_rfc4180_quoting(field, out):
    assert csv_line([field]) == out + "\n"


# ---- CsvWriter ----

def test_header_is_on_disk_at_once(tmp_path, clock):
    path = tmp_path / "x.csv"
    w = CsvWriter(path, ["a", "b"], clock.monotonic_ns)
    assert path.read_text() == "a,b\n"
    w.close()


def test_refuses_to_overwrite_an_existing_file(tmp_path, clock):
    path = tmp_path / "x.csv"
    path.write_text("raw data\n")
    with pytest.raises(FileExistsError):
        CsvWriter(path, ["a"], clock.monotonic_ns)
    assert path.read_text() == "raw data\n"


def test_rows_are_buffered_until_the_flush_interval(tmp_path, clock):
    path = tmp_path / "x.csv"
    w = CsvWriter(path, ["a"], clock.monotonic_ns, flush_interval_s=5)
    w.write(["1"])
    assert path.read_text() == "a\n"            # still buffered
    clock.advance(5)
    w.write(["2"])
    assert path.read_text() == "a\n1\n2\n"
    w.write(["3"])
    clock.advance(5)
    w.flush_if_due()
    assert path.read_text().endswith("3\n")
    w.close()


def test_flush_now_and_close(tmp_path, clock):
    path = tmp_path / "x.csv"
    w = CsvWriter(path, ["a"], clock.monotonic_ns)
    w.write(["1"], flush_now=True)
    assert path.read_text() == "a\n1\n"
    w.write(["2"])
    w.close()
    w.close()                                     # idempotent
    assert path.read_text() == "a\n1\n2\n"
    with pytest.raises(ValueError):
        w.write(["3"])
    w.flush()                                     # no-op once closed
    w.flush_if_due()


def test_column_count_is_checked(tmp_path, clock):
    w = CsvWriter(tmp_path / "x.csv", ["a", "b"], clock.monotonic_ns)
    with pytest.raises(ValueError):
        w.write(["only one"])
    w.close()


def test_flushes_are_fsynced(tmp_path, clock, monkeypatch):
    synced = []
    real = os.fsync
    monkeypatch.setattr(storage.os, "fsync", lambda fd: (synced.append(fd), real(fd)))
    w = CsvWriter(tmp_path / "x.csv", ["a"], clock.monotonic_ns)
    w.write(["1"], flush_now=True)
    w.close()
    assert len(synced) == 3                       # header, flush_now, close


def test_utf8_and_unix_line_endings(tmp_path, clock):
    path = tmp_path / "x.csv"
    w = CsvWriter(path, ["note"], clock.monotonic_ns)
    w.write(["café"])
    w.close()
    assert path.read_bytes() == "note\ncafé\n".encode("utf-8")


# ---- session.json / sessions.csv ----

def test_session_json_is_replaced_atomically_with_the_end_time(tmp_path):
    paths = SessionPaths(tmp_path, "P001", "01")
    paths.create_dir()
    info = SessionInfo("P001", "01", "B5A3F42C", T)
    write_session_info(paths, info)
    assert '"end_utc": null' in paths.file(storage.SESSION).read_text()
    write_session_info(paths, SessionInfo("P001", "01", "B5A3F42C", T, T + 60_000_000_000, "user"))
    assert SessionInfo.from_json(paths.file(storage.SESSION).read_text()).end_utc_ns == T + 60_000_000_000
    assert [p.name for p in paths.dir.iterdir()] == ["sub-P001_ses-01_session.json"]   # no temp file left


def test_sessions_index_header_once_then_appends(tmp_path):
    append_sessions_index(tmp_path, SessionInfo("P001", "01", "B5A3F42C", T, T + 61_500_000_000, "user", notes="a,b"))
    append_sessions_index(tmp_path, SessionInfo("P002", "01", "B5A3F42C", T, None, None))
    rows = read_csv(tmp_path / "sessions.csv")
    assert rows[0] == {"participant_id": "P001", "session_id": "01", "device_id": "B5A3F42C",
                       "start_utc": "2026-11-16T12:00:00.123Z", "end_utc": "2026-11-16T12:01:01.623Z",
                       "duration_s": "61.500", "stop_reason": "user", "notes": "a,b"}
    assert rows[1]["end_utc"] == "" and rows[1]["duration_s"] == ""
    assert (tmp_path / "sessions.csv").read_text().count("participant_id") == 1
