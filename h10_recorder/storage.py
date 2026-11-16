"""Session folder and file naming, ID checks, CSV writers, session.json and sessions.csv.

Layout:  <DATA_ROOT>/sub-P001/ses-01/sub-P001_ses-01_hr.csv   (also _rr.csv, _events.csv, _ecg.csv, _session.json)
Raw files are never edited: data files are created with exclusive create ('x'), so an existing file can't be
overwritten, and session.json is only ever replaced whole, atomically.
"""
from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Callable

from . import config
from .models import SessionInfo
from .clock import format_elapsed, format_utc

HR, RR, EVENTS, ECG, SESSION = "hr.csv", "rr.csv", "events.csv", "ecg.csv", "session.json"
SESSIONS_INDEX = "sessions.csv"
SESSIONS_HEADER = ["participant_id", "session_id", "device_id", "start_utc", "end_utc", "duration_s",
                   "stop_reason", "notes"]


class SessionExistsError(FileExistsError):
    pass


# ---- IDs (format: config.py, ID-FORMAT) ----

def require_valid_ids(participant_id: str, session_id: str) -> tuple[str, str]:
    """Strict on purpose so a name can't be typed in by mistake. Messages don't repeat the rejected text,
    so a mistyped name doesn't end up in logs."""
    if not re.fullmatch(config.PARTICIPANT_ID_PATTERN, participant_id, flags=re.ASCII):
        raise ValueError(f"Participant ID must look like {config.PARTICIPANT_ID_EXAMPLE}")   # ID-FORMAT
    if not re.fullmatch(config.SESSION_ID_PATTERN, session_id, flags=re.ASCII):
        raise ValueError(f"Session ID must look like {config.SESSION_ID_EXAMPLE}")           # ID-FORMAT
    return participant_id, session_id


class SessionPaths:
    """Where one session's files go. IDs are checked first, so no path is built from unchecked text ("../")."""

    def __init__(self, root: Path, participant_id: str, session_id: str) -> None:
        self.participant_id, self.session_id = require_valid_ids(participant_id, session_id)
        self.root = Path(root)
        self.dir = self.root / f"sub-{participant_id}" / f"ses-{session_id}"

    def file(self, kind: str) -> Path:
        return self.dir / f"sub-{self.participant_id}_ses-{self.session_id}_{kind}"

    def create_dir(self) -> None:
        """Creates the session folder. Refuses if it exists rather than touch existing raw data."""
        if self.dir.exists():
            raise SessionExistsError(f"Session folder already exists: {self.dir}")
        self.dir.mkdir(parents=True)


# ---- CSV ----

def csv_escape(field: str) -> str:
    """RFC 4180: quote only if the field has a comma, quote, CR or LF; inner quotes doubled."""
    if any(c in field for c in ',"\r\n'):
        return '"' + field.replace('"', '""') + '"'
    return field


def csv_line(fields: list[str]) -> str:
    return ",".join(csv_escape(f) for f in fields) + "\n"


class CsvWriter:
    """Append-only buffered CSV writer. Creates the file (refuses an existing one) and writes the header
    to disk at once. Rows are buffered and forced to disk (flush + fsync) once flush_interval_s has passed
    (checked on each write and on flush_if_due), on flush(), and on close(). UTF-8, "\\n" line endings.
    If the computer dies mid-session, at most one flush interval of rows is lost.
    """

    def __init__(self, path: Path, header: list[str], monotonic_ns: Callable[[], int],
                 flush_interval_s: float = config.FLUSH_INTERVAL_S) -> None:
        self._columns = len(header)
        self._now = monotonic_ns
        self._interval_ns = int(flush_interval_s * 1e9)
        self._file = open(path, "x", encoding="utf-8", newline="")    # 'x': fails if the file exists
        self.closed = False
        try:
            self._file.write(csv_line(header))
            self._flush_to_disk()
        except BaseException:
            self._file.close()
            raise

    def write(self, fields: list[str], flush_now: bool = False) -> None:
        if self.closed:
            raise ValueError("CsvWriter is closed")
        if len(fields) != self._columns:
            raise ValueError(f"Row has {len(fields)} fields, header has {self._columns}")
        self._file.write(csv_line(fields))
        if flush_now:
            self._flush_to_disk()
        else:
            self.flush_if_due()

    def flush_if_due(self) -> None:
        if not self.closed and self._now() - self._last_flush_ns >= self._interval_ns:
            self._flush_to_disk()

    def flush(self) -> None:
        if not self.closed:
            self._flush_to_disk()

    def close(self) -> None:
        """Flushes and closes. Calling it again does nothing."""
        if self.closed:
            return
        self.closed = True
        try:
            self._flush_to_disk()
        finally:
            self._file.close()

    def _flush_to_disk(self) -> None:
        self._file.flush()
        os.fsync(self._file.fileno())
        self._last_flush_ns = self._now()


# ---- session.json and sessions.csv ----

def write_session_info(paths: SessionPaths, info: SessionInfo) -> None:
    """Written at start (end_utc empty) and again at the end. Atomic: temp file, fsync, then rename over the
    old one, so a crash mid-write can never leave a half-written session.json."""
    target = paths.file(SESSION)
    tmp = target.with_name(target.name + ".tmp")
    with open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(info.to_json())
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, target)


def append_sessions_index(root: Path, info: SessionInfo) -> None:
    """Appends one row to <root>/sessions.csv (header on first use). Existing rows are never rewritten."""
    index = Path(root) / SESSIONS_INDEX
    text = ""
    if not index.exists() or index.stat().st_size == 0:
        text += csv_line(SESSIONS_HEADER)
    text += csv_line([
        info.participant_id, info.session_id, info.device_id,
        format_utc(info.start_utc_ns),
        "" if info.end_utc_ns is None else format_utc(info.end_utc_ns),
        "" if info.duration_s is None else format_elapsed(info.duration_s),
        info.stop_reason or "",
        info.notes,
    ])
    index.parent.mkdir(parents=True, exist_ok=True)
    with open(index, "a", encoding="utf-8", newline="") as f:
        f.write(text)
        f.flush()
        os.fsync(f.fileno())
