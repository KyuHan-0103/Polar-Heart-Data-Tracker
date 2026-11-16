import pytest

from h10_recorder.models import ECGRow, EventRow, HRRow, RRRow, SessionInfo, rr_ms
from tests.conftest import START_UTC_NS

T = START_UTC_NS // 1_000_000 * 1_000_000


@pytest.mark.parametrize("raw,text", [(840, "820.3125"), (1024, "1000.0"), (1, "0.9765625"), (819, "799.8046875"),
                                      (0, "0.0"), (65535, "63999.0234375")])
def test_rr_ms_is_exact_with_no_rounding(raw, text):
    assert rr_ms(raw) == text


def test_hr_row_contact_is_true_false_or_empty():
    assert HRRow(T, 1.5, 72, True).fields() == ["2026-11-16T12:00:00.123Z", "1.500", "72", "true"]
    assert HRRow(T, 1.5, 72, False).fields()[3] == "false"
    assert HRRow(T, 1.5, 72, None).fields()[3] == ""


def test_rr_row_fields():
    assert RRRow(T, 0.8203125, 840, 1.25).fields() == ["2026-11-16T12:00:00.123Z", "0.820", "820.3125", "1.250"]


def test_ecg_and_event_rows():
    assert ECGRow(T, 2.0, -152, 2.1).fields() == ["2026-11-16T12:00:00.123Z", "2.000", "-152", "2.100"]
    assert EventRow(T, 3.0, "marker", "task A").fields() == ["2026-11-16T12:00:00.123Z", "3.000", "marker", "task A"]


def test_headers_match_the_documented_columns():
    assert HRRow.HEADER == ["time_utc", "elapsed_s", "hr_bpm", "contact"]
    assert RRRow.HEADER == ["time_utc", "elapsed_s", "rr_ms", "received_elapsed_s"]
    assert EventRow.HEADER == ["time_utc", "elapsed_s", "event", "note"]
    assert ECGRow.HEADER == ["time_utc", "elapsed_s", "ecg_uv", "received_elapsed_s"]


def test_session_info_json_round_trip_with_start_and_end():
    info = SessionInfo("P001", "01", "B5A3F42C", T, T + 90_500_000_000, "user", False, {"bleak": "3.1.0"}, "pilot")
    assert SessionInfo.from_json(info.to_json()) == info
    text = info.to_json()
    assert '"start_utc": "2026-11-16T12:00:00.123Z"' in text
    assert '"end_utc": "2026-11-16T12:01:30.623Z"' in text
    assert '"duration_s": "90.500"' in text


def test_session_info_before_the_end_has_null_end():
    info = SessionInfo("P001", "01", "B5A3F42C", T)
    assert '"end_utc": null' in info.to_json()
    assert SessionInfo.from_json(info.to_json()).end_utc_ns is None
