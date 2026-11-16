"""Experimenter markers typed in the terminal.

  Enter on its own   -> marker with no note
  some text + Enter  -> marker with that text as its note (never type names or other identifying details)
  q + Enter          -> stop the recording (Ctrl+C does the same)

Lines are read on a background thread (reading the terminal blocks) and handed to the event loop. The time
of a marker is taken the moment Enter is pressed, on the reading thread.
"""
from __future__ import annotations

import asyncio
import sys
import threading
from typing import Callable, TextIO

from .clock import SessionClock
from .models import Marker, QueueItem, StopRequested

STOP_WORDS = {"q", "quit", "stop"}
HELP = "Press Enter to log a marker (optionally type a note first). Type q + Enter or press Ctrl+C to stop."


def parse_line(line: str, at_ns: int) -> QueueItem:
    text = line.strip()
    if text.lower() in STOP_WORDS:
        return StopRequested(at_ns)
    return Marker(at_ns, text)


def start(queue: asyncio.Queue[QueueItem], clock: SessionClock, stream: TextIO = sys.stdin,
          on_marker: Callable[[Marker], None] | None = None) -> threading.Thread:
    """Starts reading lines. A daemon thread, so a pending read never keeps the program from exiting."""
    loop = asyncio.get_running_loop()

    def deliver(item: QueueItem) -> None:
        queue.put_nowait(item)
        if on_marker is not None and isinstance(item, Marker):
            on_marker(item)

    def read() -> None:
        for line in stream:                      # ends at end of input
            item = parse_line(line, clock.monotonic_ns())
            try:
                loop.call_soon_threadsafe(deliver, item)
            except RuntimeError:                 # event loop already closed: the session is over
                return

    thread = threading.Thread(target=read, name="markers", daemon=True)
    thread.start()
    return thread
