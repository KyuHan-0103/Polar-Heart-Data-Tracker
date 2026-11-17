"""Data shapes: the labeled items that travel on the queue, the CSV rows, and session.json."""
from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from typing import Union

from .clock import format_elapsed, format_utc

# ---------------------------------------------------------------------------------------------
# Queue items. sensor.py and markers.py put these on one asyncio.Queue; recorder.py takes them off.
# Each is stamped with the monotonic clock (ns) the moment it happened; clock.py turns that into times.
# ---------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class HrPacket:
    """One Bluetooth heart-rate notification (~1/s)."""
    received_ns: int
    hr_bpm: int
    rr_raw: tuple[int, ...]      # RR intervals in 1/1024 s, exactly as sent (no rounding)
    contact: bool | None         # skin contact; None if the sensor doesn't report it


@dataclass(frozen=True)
class EcgFrame:
    """One ECG notification (only when ECG is on)."""
    received_ns: int
    sensor_ns: int               # sensor time of the LAST sample (bleakheart-based, see clock.EcgTimeline)
    samples_uv: tuple[int, ...]  # microvolts, oldest first


@dataclass(frozen=True)
class Connected:
    at_ns: int


@dataclass(frozen=True)
class Disconnected:
    at_ns: int
    reason: str


@dataclass(frozen=True)
class ReconnectFailed:
    """Reconnecting gave up after config.CONNECT_TIMEOUT_S. The session ends."""
    at_ns: int


@dataclass(frozen=True)
class Marker:
    """Experimenter marker typed in the terminal."""
    at_ns: int
    note: str


@dataclass(frozen=True)
class StopRequested:
    """Experimenter asked to stop (typed 'q' or pressed Ctrl+C)."""
    at_ns: int


QueueItem = Union[HrPacket, EcgFrame, Connected, Disconnected, ReconnectFailed, Marker, StopRequested]


# ---------------------------------------------------------------------------------------------
# CSV rows. Each has HEADER and fields() -> list of strings; storage.py does the CSV quoting.
# ---------------------------------------------------------------------------------------------

def rr_ms(raw: int) -> str:
    """RR in 1/1024 s -> ms as exact decimal text, no rounding: 840 -> '820.3125', 1024 -> '1000.0'."""
    text = format(Decimal(raw * 125) / Decimal(128), "f")     # raw * 1000 / 1024, always a finite decimal
    return text if "." in text else text + ".0"


@dataclass(frozen=True)
class HRRow:
    HEADER = ["time_utc", "elapsed_s", "hr_bpm", "contact"]
    utc_ns: int
    elapsed_s: float
    hr_bpm: int
    contact: bool | None

    def fields(self) -> list[str]:
        contact = "" if self.contact is None else str(self.contact).lower()   # "true" / "false" / ""
        return [format_utc(self.utc_ns), format_elapsed(self.elapsed_s), str(self.hr_bpm), contact]


@dataclass(frozen=True)
class RRRow:
    """One beat. time/elapsed = ESTIMATED beat time (clock.BeatTimeline); received = its packet's arrival."""
    HEADER = ["time_utc", "elapsed_s", "rr_ms", "received_elapsed_s"]
    utc_ns: int
    elapsed_s: float
    rr_raw: int
    received_elapsed_s: float

    def fields(self) -> list[str]:
        return [format_utc(self.utc_ns), format_elapsed(self.elapsed_s), rr_ms(self.rr_raw),
                format_elapsed(self.received_elapsed_s)]


@dataclass(frozen=True)
class ECGRow:
    """One ECG sample. time/elapsed from the sensor's sample clock (clock.EcgTimeline)."""
    HEADER = ["time_utc", "elapsed_s", "ecg_uv", "received_elapsed_s"]
    utc_ns: int
    elapsed_s: float
    ecg_uv: int
    received_elapsed_s: float

    def fields(self) -> list[str]:
        return [format_utc(self.utc_ns), format_elapsed(self.elapsed_s), str(self.ecg_uv),
                format_elapsed(self.received_elapsed_s)]


@dataclass(frozen=True)
class EventRow:
    HEADER = ["time_utc", "elapsed_s", "event", "note"]
    utc_ns: int
    elapsed_s: float
    event: str
    note: str = ""

    def fields(self) -> list[str]:
        return [format_utc(self.utc_ns), format_elapsed(self.elapsed_s), self.event, self.note]


class Events:
    """Event names written to events.csv (listed in the codebook)."""
    RECORDING_STARTED = "recording_started"
    RECORDING_STOPPED = "recording_stopped"   # note says why: user / connection_lost / error: ...
    CONNECTED = "connected"
    DISCONNECTED = "disconnected"
    RECONNECT_FAILED = "reconnect_failed"
    MARKER = "marker"
    RR_RESYNC = "rr_resync"                   # beat chain re-anchored because beats were missing


class StopReason:
    USER = "user"
    CONNECTION_LOST = "connection_lost"
    ERROR = "error"


# ---------------------------------------------------------------------------------------------
# session.json. Participant code only, never names or birthdates.
# ---------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class SessionInfo:
    participant_id: str
    session_id: str
    device_id: str
    start_utc_ns: int
    end_utc_ns: int | None = None       # None until the session ends (stays None if the program was killed)
    stop_reason: str | None = None
    ecg_recorded: bool = False
    software: dict[str, str] | None = None   # versions of h10_recorder, bleak, bleakheart, Python
    notes: str = ""

    @property
    def duration_s(self) -> float | None:
        return None if self.end_utc_ns is None else (self.end_utc_ns - self.start_utc_ns) / 1e9

    def to_json(self) -> str:
        return json.dumps({
            "participant_id": self.participant_id,
            "session_id": self.session_id,
            "device_id": self.device_id,
            "start_utc": format_utc(self.start_utc_ns),
            "end_utc": None if self.end_utc_ns is None else format_utc(self.end_utc_ns),
            "duration_s": None if self.duration_s is None else format_elapsed(self.duration_s),
            "stop_reason": self.stop_reason,
            "ecg_recorded": self.ecg_recorded,
            "software": self.software or {},
            "notes": self.notes,
        }, indent=2) + "\n"

    @staticmethod
    def from_json(text: str) -> SessionInfo:
        d = json.loads(text)
        return SessionInfo(
            participant_id=d["participant_id"], session_id=d["session_id"], device_id=d["device_id"],
            start_utc_ns=_parse_utc(d["start_utc"]),
            end_utc_ns=None if d["end_utc"] is None else _parse_utc(d["end_utc"]),
            stop_reason=d["stop_reason"], ecg_recorded=d["ecg_recorded"], software=d["software"], notes=d["notes"],
        )


def _parse_utc(text: str) -> int:
    dt = datetime.fromisoformat(text.replace("Z", "+00:00"))
    return (dt - datetime(1970, 1, 1, tzinfo=timezone.utc)) // timedelta(microseconds=1) * 1000   # exact integer


# ---------------------------------------------------------------------------------------------
# Live status for the window (ui.py). Written by session.py and recorder.py on the recording thread,
# read by the window every ~0.25 s. Plain attributes only, so a read never sees a half-made object.
# ---------------------------------------------------------------------------------------------

class Phase:
    IDLE = "idle"
    SEARCHING = "searching"
    CONNECTING = "connecting"
    RECORDING = "recording"
    RECONNECTING = "reconnecting"
    STOPPING = "stopping"
    STOPPED = "stopped"


@dataclass
class LiveStatus:
    phase: str = Phase.IDLE
    device_id: str | None = None
    battery_pct: int | None = None
    hr_bpm: int | None = None              # None while disconnected
    contact: bool | None = None
    last_packet_ns: int | None = None      # monotonic arrival of the latest HR packet
    start_utc_ns: int | None = None
    start_monotonic_ns: int | None = None
    stop_utc_ns: int | None = None
    duration_s: float | None = None
    stop_reason: str | None = None
    folder: str | None = None
    disconnects: int = 0
    markers: list[tuple[float, str]] = None  # (elapsed_s, note), newest last
    problems: list[str] = None               # cleanup problems to show the user

    def __post_init__(self) -> None:
        self.markers = [] if self.markers is None else self.markers
        self.problems = [] if self.problems is None else self.problems
