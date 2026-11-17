"""Takes labeled items off the queue and turns them into rows. No Bluetooth, no terminal: pure and testable.

Every item carries the monotonic time it happened; the recorder converts it with the SessionTimeline,
so a row's time is when the packet arrived / the marker was typed, not when the queue got to it.
"""
from __future__ import annotations

import asyncio

from .clock import BeatTimeline, EcgTimeline, SessionTimeline
from .models import (
    Connected, Disconnected, ECGRow, EcgFrame, EventRow, Events, HRRow, HrPacket, LiveStatus, Marker, Phase,
    QueueItem, ReconnectFailed, RRRow, StopReason, StopRequested,
)
from .storage import CsvWriter


class Recorder:
    def __init__(self, timeline: SessionTimeline, hr: CsvWriter, rr: CsvWriter, events: CsvWriter,
                 ecg: CsvWriter | None = None, beats: BeatTimeline | None = None,
                 ecg_timeline: EcgTimeline | None = None, live: LiveStatus | None = None) -> None:
        self.timeline = timeline
        self.hr, self.rr, self.events, self.ecg = hr, rr, events, ecg
        self.beats = beats or BeatTimeline()
        self.ecg_timeline = ecg_timeline or EcgTimeline()
        self.last_hr: int | None = None     # for the terminal status line; None while disconnected
        self.live = live if live is not None else LiveStatus()   # for the window

    @property
    def writers(self) -> list[CsvWriter]:
        return [w for w in (self.hr, self.rr, self.events, self.ecg) if w is not None]

    async def run(self, queue: asyncio.Queue[QueueItem]) -> str:
        """Handles items until one ends the session. Returns the StopReason."""
        while True:
            reason = self.handle(await queue.get())
            if reason is not None:
                return reason

    def handle(self, item: QueueItem) -> str | None:
        """Writes the rows for one item. Returns a StopReason if the item ends the session."""
        match item:
            case HrPacket():
                self._on_hr(item)
            case EcgFrame():
                self._on_ecg(item)
            case Marker(at_ns=t, note=note):
                self.log_event(Events.MARKER, note, t)
                self.live.markers.append((self.timeline.at(t).elapsed_s, note))
            case Connected(at_ns=t):
                self.log_event(Events.CONNECTED, "", t)
                self.live.phase = Phase.RECORDING
            case Disconnected(at_ns=t, reason=why):
                self.last_hr = self.live.hr_bpm = self.live.contact = None
                self.live.phase = Phase.RECONNECTING
                self.live.disconnects += 1
                for w in self.writers:
                    w.flush()                   # don't leave rows in memory during the gap
                self.log_event(Events.DISCONNECTED, why, t)
                self.beats.reset()              # the RR chain doesn't carry across a gap
                self.ecg_timeline.reset()       # bleakheart re-bases ECG sensor time per connection
            case ReconnectFailed(at_ns=t):
                self.log_event(Events.RECONNECT_FAILED, "gave up reconnecting", t)
                return StopReason.CONNECTION_LOST
            case StopRequested():
                return StopReason.USER
            case _:
                raise TypeError(f"Unknown queue item: {item!r}")
        return None

    def log_event(self, event: str, note: str = "", at_ns: int | None = None) -> None:
        """Events are rare and important, so each one is forced to disk at once."""
        stamp = self.timeline.now() if at_ns is None else self.timeline.at(at_ns)
        self.events.write(EventRow(stamp.utc_ns, stamp.elapsed_s, event, note).fields(), flush_now=True)

    def _on_hr(self, p: HrPacket) -> None:
        now = self.timeline.at(p.received_ns)
        self.hr.write(HRRow(now.utc_ns, now.elapsed_s, p.hr_bpm, p.contact).fields())
        result = self.beats.add(now.elapsed_s, list(p.rr_raw))
        if result.resynced:
            self.log_event(Events.RR_RESYNC, "beats missing; beat times re-anchored to packet arrival", p.received_ns)
        for b in result.beats:
            self.rr.write(RRRow(self.timeline.utc_ns_at(b.elapsed_s), b.elapsed_s, b.rr_raw, now.elapsed_s).fields())
        self.last_hr = self.live.hr_bpm = p.hr_bpm
        self.live.contact, self.live.last_packet_ns = p.contact, p.received_ns
        for w in self.writers:
            w.flush_if_due()                    # rr/events may not get a write of their own for a while

    def _on_ecg(self, f: EcgFrame) -> None:
        if self.ecg is None:
            return
        now = self.timeline.at(f.received_ns)
        times = self.ecg_timeline.add(now.elapsed_s, f.sensor_ns, len(f.samples_uv))
        for t, uv in zip(times, f.samples_uv):
            self.ecg.write(ECGRow(self.timeline.utc_ns_at(t), t, uv, now.elapsed_s).fields())
