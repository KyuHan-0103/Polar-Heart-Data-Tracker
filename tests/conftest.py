"""Shared test helpers: a controllable clock and CSV readers. No Bluetooth needed for any test."""
from __future__ import annotations

import csv
from pathlib import Path

import pytest

START_UTC_NS = 1_794_830_400_123_456_789     # 2026-11-16T12:00:00.123456789Z (not on a ms boundary on purpose)
START_MONO_NS = 5_000_000_000


class FakeClock:
    """Wall + monotonic clock moved by hand. advance() moves both; jump_wall() moves only the wall clock."""

    def __init__(self, utc_ns: int = START_UTC_NS, mono_ns: int = START_MONO_NS) -> None:
        self.utc_ns, self.mono_ns = utc_ns, mono_ns

    def time_ns(self) -> int:
        return self.utc_ns

    def monotonic_ns(self) -> int:
        return self.mono_ns

    def advance(self, seconds: float) -> None:
        self.utc_ns += round(seconds * 1e9)
        self.mono_ns += round(seconds * 1e9)

    def jump_wall(self, seconds: float) -> None:
        self.utc_ns += round(seconds * 1e9)

    def mono_at(self, seconds_after_start: float) -> int:
        return START_MONO_NS + round(seconds_after_start * 1e9)


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


def read_csv(path: Path) -> list[dict[str, str]]:
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def read_header(path: Path) -> list[str]:
    with open(path, encoding="utf-8", newline="") as f:
        return next(csv.reader(f))
