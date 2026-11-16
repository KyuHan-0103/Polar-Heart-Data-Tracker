"""The one place timestamps become time_utc and elapsed_s.

A session is anchored to ONE wall-clock reading taken at start. Every later time is derived from the
monotonic clock, so a computer clock sync mid-session can't make time_utc jump. The H10 heart-rate
stream has no sensor timestamps, so the computer is the only clock. See DATA_NOTES.md "Clock".
"""
from __future__ import annotations

import math
import sys
import time
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Protocol

from . import config

NS_PER_S = 1_000_000_000
NS_PER_MS = 1_000_000


class SessionClock(Protocol):
    """Source of time. Injected so tests can control it."""

    def time_ns(self) -> int:
        """Wall clock, ns since 1970 UTC (can jump if the computer syncs its clock)."""

    def monotonic_ns(self) -> int:
        """Monotonic ns (never jumps, keeps counting while the computer sleeps)."""


def _monotonic_clock_id() -> int | None:
    # time.monotonic() on macOS is mach_absolute_time(), which STOPS while the Mac sleeps, so time_utc
    # would fall behind after a sleep. CLOCK_MONOTONIC keeps counting during sleep on macOS
    # (man 3 clock_gettime); on Linux that's CLOCK_BOOTTIME.
    if sys.platform == "darwin":
        return time.CLOCK_MONOTONIC
    if sys.platform.startswith("linux"):
        return time.CLOCK_BOOTTIME
    return None    # Windows: time.monotonic_ns (QueryPerformanceCounter, counts during sleep)


class SystemClock:
    """The real computer clock."""

    def __init__(self) -> None:
        self._clock_id = _monotonic_clock_id()

    def time_ns(self) -> int:
        return time.time_ns()

    def monotonic_ns(self) -> int:
        if self._clock_id is None:
            return time.monotonic_ns()
        return time.clock_gettime_ns(self._clock_id)


@dataclass(frozen=True)
class Stamp:
    """A moment in a session: UTC (ns since 1970) and seconds since the session started."""
    utc_ns: int
    elapsed_s: float


class SessionTimeline:
    """Reads both clocks once, at construction (= session start), then converts monotonic readings."""

    def __init__(self, clock: SessionClock) -> None:
        self.start_monotonic_ns = clock.monotonic_ns()
        # Start truncated to a whole millisecond: then truncating start + elapsed gives exactly
        # start + truncated elapsed, so time_utc and elapsed_s agree to the millisecond in every row.
        self.start_utc_ns = clock.time_ns() // NS_PER_MS * NS_PER_MS
        self.start = Stamp(self.start_utc_ns, 0.0)
        self._clock = clock

    def at(self, monotonic_ns: int) -> Stamp:
        """The stamp of a monotonic reading (e.g. when a packet arrived)."""
        elapsed_ns = monotonic_ns - self.start_monotonic_ns
        return Stamp(self.start_utc_ns + elapsed_ns, elapsed_ns / NS_PER_S)

    def now(self) -> Stamp:
        return self.at(self._clock.monotonic_ns())

    def utc_ns_at(self, elapsed_s: float) -> int:
        """UTC of a moment given as seconds since start (used for estimated beat / ECG sample times)."""
        return self.start_utc_ns + round(elapsed_s * NS_PER_S)


# ---- formatting: the only functions that turn times into CSV text ----

def format_utc(utc_ns: int) -> str:
    """ISO 8601 UTC, always 3 decimals, truncated (never rounded up): 2026-11-16T14:03:07.123Z"""
    seconds, rest = divmod(utc_ns, NS_PER_S)
    base = datetime.fromtimestamp(seconds, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
    return f"{base}.{rest // NS_PER_MS:03d}Z"


def format_elapsed(seconds: float) -> str:
    """Seconds with exactly 3 decimals, '.' separator, truncated (floored) to the ms like format_utc.
    The 1e-6 ms guard absorbs binary rounding (1.001 stored as 1.00099999...)."""
    ms = math.floor(seconds * 1000.0 + 1e-6)
    sign = "-" if ms < 0 else ""
    ms = abs(ms)
    return f"{sign}{ms // 1000}.{ms % 1000:03d}"


# ---- beat and ECG sample times (method and evidence: DATA_NOTES.md) ----

class _WindowedMin:
    """Minimum of values added during the last window_s seconds."""

    def __init__(self, window_s: float) -> None:
        self._window_s = window_s
        self._points: deque[tuple[float, float]] = deque()   # (arrival_s, value), oldest first

    def add(self, arrival_s: float, value: float) -> None:
        self._points.append((arrival_s, value))

    def min(self, now_s: float) -> float:
        while self._points[0][0] < now_s - self._window_s:
            self._points.popleft()
        return min(v for _, v in self._points)

    def clear(self) -> None:
        self._points.clear()


@dataclass(frozen=True)
class Beat:
    elapsed_s: float   # estimated time of the beat that ends this RR interval
    rr_raw: int        # RR in 1/1024 s, as sent by the sensor


@dataclass(frozen=True)
class BeatResult:
    beats: list[Beat]
    resynced: bool     # the chain was re-anchored because beats were missing


class BeatTimeline:
    """Estimates when each beat happened from its RR interval.

    The H10 sends a packet about once a second holding the RRs completed since the last packet, and no
    timestamps. RRs come from the sensor's clock and are exact, so beats are chained:
    beat_k = offset + (sum of RRs up to beat k).

    offset = the smallest (arrival - RR sum) among packets of the last window_s: a beat can't happen after
    the packet that reports it, so the smallest value is the tightest placement. A sliding window (not an
    all-time minimum) lets the offset follow sensor/computer clock drift and recover from a lost beat.

    A packet arriving more than gap_reset_s later than the chain predicts is a Bluetooth delay or missing beats:
      - it arrived on the regular ~1 s schedule (gap since the previous packet, with or without RRs,
        <= regular_gap_s): nothing was delayed, so beats are missing -> re-anchor now (resynced=True);
      - after an irregular gap it may be a delay: placed on the chain; if the next packet is late too,
        beats are missing -> re-anchor then.
    """

    def __init__(self, gap_reset_s: float = config.BEAT_GAP_RESET_S, window_s: float = config.BEAT_WINDOW_S,
                 regular_gap_s: float = config.BEAT_REGULAR_GAP_S) -> None:
        self._gap_reset_s = gap_reset_s
        self._regular_gap_s = regular_gap_s
        self._window = _WindowedMin(window_s)
        self.reset()

    def reset(self) -> None:
        """Forget the chain (after a reconnect). The next packet anchors fresh, not reported as a resync."""
        self._rr_sum_raw = 0                      # sum of RRs since anchor, 1/1024 s (exact integer)
        self._window.clear()
        self._offset: float | None = None
        self._late_pending = False                # previous packet was more than gap_reset_s late
        self._last_arrival: float | None = None   # previous packet of any kind, including ones without RRs
        self._last_beat = -math.inf

    def add(self, received_s: float, rr_raw: list[int]) -> BeatResult:
        """One packet: arrival (s since session start) and its RRs in 1/1024 s."""
        previous_arrival, self._last_arrival = self._last_arrival, received_s
        if not rr_raw:
            return BeatResult([], False)
        on_schedule = previous_arrival is not None and received_s - previous_arrival <= self._regular_gap_s
        sums_raw = []
        for rr in rr_raw:
            self._rr_sum_raw += rr
            sums_raw.append(self._rr_sum_raw)
        # the offset that would put this packet's last beat exactly at its arrival
        candidate = received_s - sums_raw[-1] / 1024

        resynced = False
        if self._offset is None:                                       # first packet / after reset()
            place_at = self._anchor(received_s, candidate)
        elif candidate - self._offset > self._gap_reset_s:
            if self._late_pending or on_schedule:                      # beats missing
                resynced = True
                place_at = self._anchor(received_s, candidate)
            else:                                                      # late once: assume a delay
                self._late_pending = True
                self._window.add(received_s, candidate)
                place_at = self._offset
        else:
            self._late_pending = False
            self._window.add(received_s, candidate)
            self._offset = place_at = self._window.min(received_s)

        beats = []
        for sum_raw, rr in zip(sums_raw, rr_raw):
            t = max(place_at + sum_raw / 1024, self._last_beat)       # never earlier than the previous beat
            self._last_beat = t
            beats.append(Beat(t, rr))
        return BeatResult(beats, resynced)

    def _anchor(self, arrival_s: float, candidate: float) -> float:
        self._window.clear()
        self._window.add(arrival_s, candidate)
        self._offset = candidate
        self._late_pending = False
        return candidate


class EcgTimeline:
    """Times of ECG samples (only used when ECG is on).

    Unlike HR, ECG frames carry a sensor timestamp: the time of the frame's LAST sample, on the sensor's
    clock. Samples are 1/sample_rate apart. Sensor clock -> session time uses the same rule as beats:
    offset = smallest (arrival - sensor time) over the last window_s, since a sample can't arrive before
    it was measured. Reset after a reconnect (bleakheart re-bases sensor time per connection).
    """

    def __init__(self, sample_rate_hz: float = config.ECG_SAMPLE_RATE_HZ,
                 window_s: float = config.BEAT_WINDOW_S) -> None:
        self._period_s = 1.0 / sample_rate_hz
        self._window = _WindowedMin(window_s)
        self._sensor0_ns: int | None = None

    def reset(self) -> None:
        self._window.clear()
        self._sensor0_ns = None

    def add(self, received_s: float, sensor_ns: int, n_samples: int) -> list[float]:
        """Elapsed seconds of each of the frame's n_samples, oldest first."""
        if self._sensor0_ns is None:
            self._sensor0_ns = sensor_ns
        last_s = (sensor_ns - self._sensor0_ns) / NS_PER_S   # small numbers keep float precision
        self._window.add(received_s, received_s - last_s)
        offset = self._window.min(received_s)
        return [offset + last_s - (n_samples - 1 - i) * self._period_s for i in range(n_samples)]
