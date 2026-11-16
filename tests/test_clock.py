import math

import pytest

from h10_recorder.clock import (
    BeatTimeline, EcgTimeline, SessionTimeline, SystemClock, format_elapsed, format_utc,
)
from tests.conftest import START_MONO_NS


# ---- formatting ----

def test_format_utc_has_millis_and_truncates():
    assert format_utc(1_794_830_400_123_999_999) == "2026-11-16T12:00:00.123Z"   # never rounds up
    assert format_utc(1_794_830_400_000_000_000) == "2026-11-16T12:00:00.000Z"


@pytest.mark.parametrize("seconds,text", [
    (0.0, "0.000"), (1.5, "1.500"), (1.001, "1.001"), (1.0019, "1.001"), (59.9999, "59.999"),
    (3600.25, "3600.250"), (-0.0001, "-0.001"), (-1.5, "-1.500"), (-0.0, "0.000"),
])
def test_format_elapsed_floors_to_ms(seconds, text):
    assert format_elapsed(seconds) == text


# ---- session timeline ----

def test_timeline_start_is_truncated_to_whole_ms(clock):
    t = SessionTimeline(clock)
    assert t.start_utc_ns % 1_000_000 == 0
    assert format_utc(t.start_utc_ns) == "2026-11-16T12:00:00.123Z"
    assert t.start.elapsed_s == 0.0


def test_timeline_uses_monotonic_clock_so_wall_clock_jumps_do_not_matter(clock):
    t = SessionTimeline(clock)
    clock.advance(10)
    clock.jump_wall(-3600)                    # computer clock synced mid-session
    s = t.now()
    assert s.elapsed_s == 10.0
    assert format_utc(s.utc_ns) == "2026-11-16T12:00:10.123Z"


def test_time_utc_and_elapsed_agree_to_the_ms_in_every_row(clock):
    t = SessionTimeline(clock)
    for k in range(2000):
        s = t.at(START_MONO_NS + k * 1_234_567)
        utc_ms = int(format_utc(s.utc_ns)[-4:-1])
        start_ms = int(format_utc(t.start_utc_ns)[-4:-1])
        elapsed_ms = int(format_elapsed(s.elapsed_s).split(".")[1])
        assert (start_ms + elapsed_ms) % 1000 == utc_ms


def test_utc_at_elapsed(clock):
    t = SessionTimeline(clock)
    assert format_utc(t.utc_ns_at(2.5)) == "2026-11-16T12:00:02.623Z"


def test_system_clock_is_monotonic_and_sane():
    c = SystemClock()
    a, b = c.monotonic_ns(), c.monotonic_ns()
    assert b >= a
    assert c.time_ns() > 1_700_000_000 * 10**9


# ---- beat times ----

def rr(ms: float) -> int:
    return round(ms * 1024 / 1000)


def test_first_packet_puts_last_beat_at_arrival_and_chains_back():
    bt = BeatTimeline()
    r = bt.add(10.0, [1024, 512])
    assert [b.elapsed_s for b in r.beats] == [9.5, 10.0]
    assert [b.rr_raw for b in r.beats] == [1024, 512]
    assert not r.resynced


def test_chain_uses_rr_sums_not_packet_times():
    bt = BeatTimeline()
    bt.add(1.0, [1024])                       # beat at 1.0
    r = bt.add(2.3, [1024])                   # arrived 0.3 s after the beat: chain says 2.0
    assert r.beats[0].elapsed_s == 2.0


def test_earlier_arrival_tightens_the_offset():
    bt = BeatTimeline()
    bt.add(1.3, [1024])                       # beat placed at 1.3 (only information so far)
    r = bt.add(2.05, [1024])                  # tighter: beat 2 at 2.05 -> offset moves back 0.25
    assert r.beats[0].elapsed_s == pytest.approx(2.05)


def test_beats_are_never_moved_before_the_previous_beat():
    bt = BeatTimeline()
    bt.add(1.3, [1024])
    r = bt.add(1.35, [52])                    # tight packet pulls the offset back by 1 s
    assert r.beats[0].elapsed_s >= 1.3


def test_missing_beats_on_schedule_resync_at_once():
    bt = BeatTimeline()
    t = 0.0
    for _ in range(10):
        t += 1.0
        bt.add(t, [1024])
    bt.add(t + 1.0, [])                       # contact lost: packets keep coming without RRs
    bt.add(t + 2.0, [])
    bt.add(t + 3.0, [])
    r = bt.add(t + 4.0, [1024])               # on schedule but 3 s later than the chain predicts
    assert r.resynced
    assert r.beats[0].elapsed_s == t + 4.0


def test_one_late_packet_after_irregular_gap_is_treated_as_a_delay():
    bt = BeatTimeline()
    for k in range(1, 6):
        bt.add(float(k), [1024])
    r = bt.add(9.0, [1024] * 4)               # 4 s gap, all 4 beats present: Bluetooth delay
    assert not r.resynced
    assert [b.elapsed_s for b in r.beats] == [6.0, 7.0, 8.0, 9.0]


def test_second_late_packet_in_a_row_resyncs():
    bt = BeatTimeline()
    for k in range(1, 6):
        bt.add(float(k), [1024])
    assert not bt.add(9.0, [1024]).resynced   # late after an irregular gap: assumed delay
    r = bt.add(10.0, [1024])                  # still late: beats really are missing
    assert r.resynced
    assert r.beats[0].elapsed_s == 10.0


def test_window_lets_the_offset_recover_from_a_lost_beat():
    bt = BeatTimeline(window_s=30.0)
    t = 0.0
    for _ in range(5):
        t += 1.0
        bt.add(t, [1024])
    t += 2.0
    bt.add(t, [1024])                         # one beat silently missing (gap < 2 s late): no resync
    for _ in range(40):                       # after the window passes, the true offset is back
        t += 1.0
        r = bt.add(t, [1024])
    assert r.beats[0].elapsed_s == pytest.approx(t)


def test_reset_starts_a_fresh_chain_without_reporting_a_resync():
    bt = BeatTimeline()
    bt.add(1.0, [1024])
    bt.reset()
    r = bt.add(100.0, [1024])
    assert not r.resynced
    assert r.beats[0].elapsed_s == 100.0


def test_empty_packets_give_no_beats():
    assert BeatTimeline().add(1.0, []).beats == []


def test_rr_sums_are_exact_integers_over_long_sessions():
    bt = BeatTimeline()
    t, raw = 0.0, rr(800)                      # 819 units = 799.8046875 ms
    for _ in range(50_000):
        t += raw / 1024
        r = bt.add(t, [raw])
    assert math.isclose(r.beats[0].elapsed_s, t, abs_tol=1e-6)


# ---- ECG sample times ----

def test_ecg_samples_are_spaced_by_the_sample_period_and_end_at_the_frame_time():
    et = EcgTimeline(sample_rate_hz=130)
    times = et.add(10.0, sensor_ns=50_000_000_000, n_samples=73)
    assert times[-1] == pytest.approx(10.0)
    assert times[1] - times[0] == pytest.approx(1 / 130)


def test_ecg_offset_takes_the_earliest_arrival():
    et = EcgTimeline(sample_rate_hz=130)
    et.add(10.3, 50_000_000_000, 1)              # delayed frame
    t = et.add(11.0, 51_000_000_000, 1)          # on time: sensor says 1 s later
    assert t[-1] == pytest.approx(11.0)
