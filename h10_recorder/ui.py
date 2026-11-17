"""The recording window, for people who don't use a terminal. Start it with the "Start H10 Recorder" file,
or:  python -m h10_recorder   (no options)

Three pages: Setup (codes, pick the strap) -> Recording (connection, live bpm, markers, Stop) -> Finished (summary).

Threads: the window runs on the main thread (Tk requires it). Bluetooth and file writing run in an asyncio loop on
a background thread (AsyncRunner). The window never touches files or Bluetooth itself: it starts run_session() on
that loop, sends markers/Stop through SessionControl, and reads LiveStatus every REFRESH_MS to redraw.
"""
from __future__ import annotations

import asyncio
import concurrent.futures
import os
import subprocess
import sys
import threading
import tkinter as tk
from datetime import datetime
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from typing import Any, Callable

from . import config
from .clock import SessionClock, SystemClock
from .models import LiveStatus, Phase, StopReason
from .session import SessionControl, run_session
from .storage import SessionPaths, next_session_id, require_valid_ids

REFRESH_MS = 250              # how often the window redraws the live values
NO_DATA_WARNING_S = 5.0       # while recording, warn if no heart-rate packet for this long

GREEN, ORANGE, RED, GREY = "#1e8e3e", "#e37400", "#d93025", "#5f6368"


# ---------------------------------------------------------------------------------------------
# Pure helpers (no widgets), unit-tested in tests/test_ui.py
# ---------------------------------------------------------------------------------------------

def format_hms(seconds: float | None) -> str:
    if seconds is None:
        return "--:--:--"
    s = max(0, int(seconds))
    return f"{s // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}"


def local_clock(utc_ns: int | None) -> str:
    if utc_ns is None:
        return "—"
    return datetime.fromtimestamp(utc_ns / 1e9).astimezone().strftime("%H:%M:%S  (%d %b %Y)")


def check_inputs(participant: str, session: str, root: Path) -> str | None:
    """A plain-language problem with the typed codes, or None if they're fine."""
    participant, session = participant.strip(), session.strip()
    if not participant:
        return f"Type the participant code, for example {config.PARTICIPANT_ID_EXAMPLE}."            # ID-FORMAT
    if not session:
        return f"Type the session number, for example {config.SESSION_ID_EXAMPLE}."                  # ID-FORMAT
    try:
        require_valid_ids(participant, session)
    except ValueError as e:
        return f"{e}. Use codes only, never names."
    if SessionPaths(root, participant, session).dir.exists():
        return (f"Session {session} for {participant} was already recorded. "
                f"Use the next number ({next_session_id(root, participant)}).")
    return None


def status_view(live: LiveStatus, now_ns: int) -> tuple[str, str, str]:
    """(headline, detail, colour) for the big status line."""
    p = live.phase
    if p == Phase.SEARCHING:
        return "Searching for the strap…", "Make sure it is worn with wet electrodes.", ORANGE
    if p == Phase.CONNECTING:
        return "Connecting…", f"Strap {live.device_id or ''}. This can take up to {config.CONNECT_TIMEOUT_S:g} s.", ORANGE
    if p == Phase.RECONNECTING:
        return ("Connection lost — reconnecting…",
                f"Keep the strap on and nearby. Gives up after {config.CONNECT_TIMEOUT_S:g} s.", RED)
    if p == Phase.RECORDING:
        age = None if live.last_packet_ns is None else (now_ns - live.last_packet_ns) / 1e9
        if age is None:
            return "Connected — waiting for heart rate…", f"Strap {live.device_id}", ORANGE
        if age > NO_DATA_WARNING_S:
            return "Connected, but no heart rate data", f"Nothing received for {age:.0f} s. Check the strap is worn.", ORANGE
        return "Connected — recording", f"Strap {live.device_id} · last data {age:.0f} s ago", GREEN
    if p == Phase.STOPPING:
        return "Stopping and saving…", "Please wait.", GREY
    if p == Phase.STOPPED:
        return "Stopped", "", GREY
    return "Not recording", "", GREY


def contact_text(contact: bool | None) -> tuple[str, str]:
    if contact is True:
        return "Skin contact: good", GREEN
    if contact is False:
        return "Skin contact: poor — wet the electrodes or tighten the strap", RED
    return "", GREY


def summary(live: LiveStatus) -> tuple[str, str, list[str]]:
    """(headline, colour, lines) for the Finished page."""
    if live.stop_reason == StopReason.CONNECTION_LOST:
        head, colour = "Recording ended: the strap connection was lost", RED
    elif live.stop_reason == StopReason.ERROR or live.problems:
        head, colour = "Recording ended because of a problem", RED
    else:
        head, colour = "Recording finished and saved", GREEN
    lines = [f"Started:   {local_clock(live.start_utc_ns)}",
             f"Stopped:   {local_clock(live.stop_utc_ns)}",
             f"Duration:  {format_hms(live.duration_s)}",
             f"Markers:   {len(live.markers)}",
             f"Saved in:  {live.folder}"]
    if live.disconnects:
        lines.append(f"Connection drops during the session: {live.disconnects}")
    if live.stop_reason == StopReason.CONNECTION_LOST:
        lines.append("Data up to the drop is saved. Check the strap, then record a NEW session number.")
    lines += [f"Problem: {p}" for p in live.problems]
    return head, colour, lines


def open_folder(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    if sys.platform == "darwin":
        subprocess.Popen(["open", str(path)])
    elif sys.platform.startswith("win"):
        os.startfile(str(path))  # type: ignore[attr-defined]
    else:
        subprocess.Popen(["xdg-open", str(path)])


# ---------------------------------------------------------------------------------------------
# Background asyncio loop
# ---------------------------------------------------------------------------------------------

class AsyncRunner:
    """An asyncio event loop on a daemon thread. submit() runs a coroutine there and returns a Future."""

    def __init__(self) -> None:
        self.loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self.loop.run_forever, name="recording", daemon=True)
        self._thread.start()

    def submit(self, coro) -> concurrent.futures.Future:
        return asyncio.run_coroutine_threadsafe(coro, self.loop)

    def close(self) -> None:
        self.loop.call_soon_threadsafe(self.loop.stop)
        self._thread.join(timeout=5)


# ---------------------------------------------------------------------------------------------
# Widgets
# ---------------------------------------------------------------------------------------------

class BigButton(tk.Label):
    """A large coloured button. (A Label, because native macOS buttons ignore colours.)"""

    def __init__(self, parent, text: str, colour: str, command: Callable[[], None], **kw) -> None:
        super().__init__(parent, text=text, bg=colour, fg="white", font=("TkDefaultFont", 16, "bold"),
                         padx=24, pady=12, cursor="hand2", **kw)
        self.colour, self.command, self.enabled = colour, command, True
        self.bind("<Button-1>", lambda _e: self.enabled and self.command())

    def set_enabled(self, on: bool) -> None:
        self.enabled = on
        self.configure(bg=self.colour if on else "#bdc1c6", cursor="hand2" if on else "arrow")


class App:
    def __init__(self, root: tk.Tk, data_root: Path = config.DATA_ROOT, clock: SessionClock | None = None,
                 runner: AsyncRunner | None = None, finder: Callable | None = None,
                 sensor_factory: Callable | None = None, keep_awake: bool = True) -> None:
        self.root, self.data_root = root, Path(data_root)
        self.clock = clock or SystemClock()
        self.runner = runner or AsyncRunner()
        self._finder, self._sensor_factory, self._keep_awake = finder, sensor_factory, keep_awake
        self.found: list[Any] = []
        self.future: concurrent.futures.Future | None = None
        self.live = LiveStatus()
        self.control: SessionControl | None = None
        self.messages: list[str] = []
        self._closing = False

        root.title("H10 Heart Rate Recorder")
        root.minsize(620, 560)
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.pages = {name: ttk.Frame(root, padding=(24, 16)) for name in ("setup", "recording", "finished")}
        self._build_setup(self.pages["setup"])
        self._build_recording(self.pages["recording"])
        self._build_finished(self.pages["finished"])
        self.show("setup")
        self.search()

    # ---- page switching ----

    def show(self, name: str) -> None:
        for frame in self.pages.values():
            frame.pack_forget()
        self.pages[name].pack(fill="both", expand=True)
        self.page = name

    # ---- Setup page ----

    def _build_setup(self, f: ttk.Frame) -> None:
        ttk.Label(f, text="New recording", font=("TkDefaultFont", 22, "bold")).pack(anchor="w")
        ttk.Label(f, text="1. Wet the strap's electrodes and put it on the participant.\n"
                          "2. Type the participant code and session number.\n"
                          "3. Choose the strap, then press Start.", justify="left").pack(anchor="w", pady=(4, 16))

        form = ttk.Frame(f)
        form.pack(fill="x")
        self.participant = tk.StringVar()
        self.session = tk.StringVar()
        self.notes = tk.StringVar()
        self.ecg = tk.BooleanVar(value=config.ECG_ENABLED)
        rows = [("Participant code", self.participant, f"like {config.PARTICIPANT_ID_EXAMPLE} — never a name"),  # ID-FORMAT
                ("Session number", self.session, f"like {config.SESSION_ID_EXAMPLE} — filled in for you"),     # ID-FORMAT
                ("Notes (optional)", self.notes, "no names or identifying details")]
        self.entries = {}
        for r, (label, var, hint) in enumerate(rows):
            ttk.Label(form, text=label, font=("TkDefaultFont", 13, "bold")).grid(row=2 * r, column=0, sticky="w",
                                                                                 pady=(6, 0))
            e = ttk.Entry(form, textvariable=var, width=20, font=("TkDefaultFont", 14))
            e.grid(row=2 * r, column=1, sticky="w", padx=12, pady=(6, 0))
            ttk.Label(form, text=hint, foreground=GREY).grid(row=2 * r + 1, column=1, sticky="w", padx=12)
            self.entries[label] = e
        self.participant.trace_add("write", lambda *_: self._suggest_session())

        ttk.Label(form, text="Heart rate strap", font=("TkDefaultFont", 13, "bold")).grid(row=6, column=0, sticky="w", pady=6)
        self.strap = ttk.Combobox(form, state="readonly", width=18, font=("TkDefaultFont", 14))
        self.strap.grid(row=6, column=1, sticky="w", padx=12)
        self.search_button = ttk.Button(form, text="Search again", command=self.search)
        self.search_button.grid(row=6, column=2, sticky="w")
        self.search_status = ttk.Label(f, text="", foreground=GREY, wraplength=560, justify="left")
        self.search_status.pack(anchor="w", pady=(2, 10))

        ttk.Checkbutton(f, text="Also record raw ECG (advanced, larger files)", variable=self.ecg).pack(anchor="w")

        folder = ttk.Frame(f)
        folder.pack(fill="x", pady=(14, 0))
        ttk.Label(folder, text="Data is saved in:").pack(side="left")
        ttk.Button(folder, text="Open", command=lambda: open_folder(self.data_root)).pack(side="left", padx=6)
        ttk.Button(folder, text="Change…", command=self._change_folder).pack(side="left")
        self.folder_label = ttk.Label(f, text=str(self.data_root), foreground=GREY, wraplength=560)
        self.folder_label.pack(anchor="w")

        self.setup_error = ttk.Label(f, text="", foreground=RED, wraplength=560, justify="left",
                                     font=("TkDefaultFont", 13))
        self.setup_error.pack(anchor="w", pady=12)
        self.start_button = BigButton(f, "Start recording", GREEN, self.start)
        self.start_button.pack(anchor="w")

    def _suggest_session(self) -> None:
        p = self.participant.get().strip()
        try:
            self.session.set(next_session_id(self.data_root, p))
        except ValueError:
            pass   # not a valid code (yet): leave the session box alone

    def _change_folder(self) -> None:
        chosen = filedialog.askdirectory(initialdir=str(self.data_root.parent), title="Choose where to save data")
        if chosen:
            self.data_root = Path(chosen)
            self.folder_label.configure(text=str(self.data_root))
            self._suggest_session()

    def search(self) -> None:
        if self._finder is None:
            from .sensor import find
            self._finder = find
        self.search_button.state(["disabled"])
        self.search_status.configure(text=f"Searching for straps ({config.SCAN_TIMEOUT_S:g} s)…", foreground=ORANGE)
        fut = self.runner.submit(self._finder())
        self._when_done(fut, self._search_done)

    def _search_done(self, fut: concurrent.futures.Future) -> None:
        self.search_button.state(["!disabled"])
        try:
            self.found = list(fut.result())
        except Exception as e:     # noqa: BLE001 - e.g. Bluetooth off / not allowed
            self.found = []
            self.search_status.configure(
                text=f"Bluetooth problem: {e}\nIs Bluetooth on, and allowed for this program "
                     "(System Settings → Privacy & Security → Bluetooth)?", foreground=RED)
            self.strap.configure(values=[])
            self.strap.set("")
            return
        self.strap.configure(values=[s.device_id for s in self.found])
        if self.found:
            self.strap.current(0)
            n = len(self.found)
            self.search_status.configure(foreground=GREEN, text=(
                "Found 1 strap." if n == 1 else
                f"Found {n} straps. Pick the one with the ID printed on the participant's sensor."))
        else:
            self.strap.set("")
            self.search_status.configure(foreground=RED, text=(
                "No strap found. Wet the electrodes, put the strap on (it only wakes up when worn), keep it close, "
                "close the Polar app on any phone, then press Search again."))

    def start(self) -> None:
        problem = check_inputs(self.participant.get(), self.session.get(), self.data_root)
        if problem is None and not self.found:
            problem = "No strap selected. Press Search again with the strap worn."
        if problem:
            self.setup_error.configure(text=problem)
            return
        self.setup_error.configure(text="")
        sensor = self.found[self.strap.current()]
        self.live, self.messages = LiveStatus(), []
        self.control = SessionControl(self.clock)
        self.future = self.runner.submit(run_session(
            self.participant.get().strip(), self.session.get().strip(), ecg=self.ecg.get(),
            notes=self.notes.get().strip(), root=self.data_root, found=sensor, live=self.live,
            control=self.control, read_markers=False, keep_awake=self._keep_awake, out=self.messages.append,
            sensor_factory=self._sensor_factory))
        self.who.configure(text=f"Participant {self.participant.get().strip()} · Session {self.session.get().strip()}")
        self.marker_list.delete(0, "end")
        self.show("recording")
        self._when_done(self.future, self._session_done)
        self.refresh()

    # ---- Recording page ----

    def _build_recording(self, f: ttk.Frame) -> None:
        self.who = ttk.Label(f, text="", font=("TkDefaultFont", 14))
        self.who.pack(anchor="w")
        status = tk.Frame(f)
        status.pack(anchor="w", pady=(10, 0), fill="x")
        self.dot = tk.Label(status, text="●", font=("TkDefaultFont", 28))
        self.dot.pack(side="left")
        self.headline = tk.Label(status, text="", font=("TkDefaultFont", 20, "bold"))
        self.headline.pack(side="left", padx=8)
        self.detail = ttk.Label(f, text="", foreground=GREY)
        self.detail.pack(anchor="w")

        hr = tk.Frame(f)
        hr.pack(pady=4)
        self.bpm = tk.Label(hr, text="--", font=("TkDefaultFont", 80, "bold"))
        self.bpm.pack(side="left")
        tk.Label(hr, text="bpm", font=("TkDefaultFont", 24), fg=GREY).pack(side="left", anchor="s", pady=16)
        self.contact = tk.Label(f, text="", font=("TkDefaultFont", 13))
        self.contact.pack()

        info = ttk.Frame(f)
        info.pack(fill="x", pady=6)
        self.info = {}
        for r, key in enumerate(["Recording time", "Started", "Strap battery"]):
            ttk.Label(info, text=key + ":", font=("TkDefaultFont", 13, "bold")).grid(row=r, column=0, sticky="w")
            self.info[key] = ttk.Label(info, text="—", font=("TkDefaultFont", 13))
            self.info[key].grid(row=r, column=1, sticky="w", padx=10)

        marker = ttk.LabelFrame(f, text=" Markers (e.g. 'task start') ", padding=10)
        marker.pack(fill="x", pady=8)
        row = ttk.Frame(marker)
        row.pack(fill="x")
        self.marker_note = tk.StringVar()
        entry = ttk.Entry(row, textvariable=self.marker_note, font=("TkDefaultFont", 14), width=28)
        entry.pack(side="left")
        entry.bind("<Return>", lambda _e: self.add_marker())
        self.marker_button = ttk.Button(row, text="Add marker", command=self.add_marker)
        self.marker_button.pack(side="left", padx=8)
        self.marker_feedback = ttk.Label(marker, text="Type an optional note and press Add marker (or Enter).",
                                         foreground=GREY)
        self.marker_feedback.pack(anchor="w", pady=(4, 2))
        self.marker_list = tk.Listbox(marker, height=3, font=("TkDefaultFont", 12))
        self.marker_list.pack(fill="x")

        self.message = ttk.Label(f, text="", foreground=GREY, wraplength=560, justify="left")
        self.message.pack(anchor="w", pady=4)
        self.stop_button = BigButton(f, "Stop recording", RED, self.stop)
        self.stop_button.pack(pady=6)

    def add_marker(self) -> None:
        note = self.marker_note.get().strip()
        if self.control is not None and self.control.add_marker(note):
            self.marker_note.set("")
            self.marker_feedback.configure(text="✓ Marker added" + (f": {note}" if note else ""), foreground=GREEN)
        else:
            self.marker_feedback.configure(text="Markers can be added once recording has started.", foreground=ORANGE)

    def stop(self) -> None:
        if self.future is None or self.future.done():
            return
        if self.live.phase in (Phase.SEARCHING, Phase.CONNECTING):
            self.future.cancel()               # nothing written yet
            return
        if messagebox.askyesno("Stop recording?", "Stop the recording now? The data will be saved.", parent=self.root):
            self.control.stop()

    def refresh(self) -> None:
        """Redraws the Recording page from LiveStatus. Reschedules itself while the session runs."""
        if self.page != "recording":
            return
        live, now = self.live, self.clock.monotonic_ns()
        head, detail, colour = status_view(live, now)
        self.dot.configure(fg=colour)
        self.headline.configure(text=head, fg=colour)
        self.detail.configure(text=detail)
        self.bpm.configure(text="--" if live.hr_bpm is None else str(live.hr_bpm),
                           fg="black" if live.phase == Phase.RECORDING and live.hr_bpm is not None else GREY)
        text, c = contact_text(live.contact)
        self.contact.configure(text=text, fg=c)
        running = live.start_monotonic_ns is not None
        self.info["Recording time"].configure(text=format_hms((now - live.start_monotonic_ns) / 1e9) if running else "—")
        self.info["Started"].configure(text=local_clock(live.start_utc_ns))
        self.info["Strap battery"].configure(text="—" if live.battery_pct is None else f"{live.battery_pct}%")
        markers = list(live.markers)
        if len(markers) != self.marker_list.size():
            self.marker_list.delete(0, "end")
            for t, note in reversed(markers):
                self.marker_list.insert("end", f"{format_hms(t)}   {note or '(no note)'}")
        self.message.configure(text=self.messages[-1] if self.messages else "")
        connecting = live.phase in (Phase.SEARCHING, Phase.CONNECTING)
        self.stop_button.configure(text="Cancel" if connecting else "Stop recording")
        self.stop_button.set_enabled(live.phase not in (Phase.STOPPING, Phase.STOPPED))
        if live.phase == Phase.RECONNECTING and not getattr(self, "_alerted", False):
            self._alerted = True
            self.root.bell()
            self.root.deiconify()
            self.root.lift()
        if live.phase != Phase.RECONNECTING:
            self._alerted = False
        self.root.after(REFRESH_MS, self.refresh)

    # ---- Finished page ----

    def _build_finished(self, f: ttk.Frame) -> None:
        self.finished_head = tk.Label(f, text="", font=("TkDefaultFont", 20, "bold"), wraplength=560, justify="left")
        self.finished_head.pack(anchor="w")
        self.finished_text = ttk.Label(f, text="", font=("TkFixedFont", 13), justify="left", wraplength=580)
        self.finished_text.pack(anchor="w", pady=16)
        buttons = ttk.Frame(f)
        buttons.pack(anchor="w")
        ttk.Button(buttons, text="Open data folder",
                   command=lambda: open_folder(Path(self.live.folder) if self.live.folder else self.data_root)
                   ).pack(side="left")
        BigButton(f, "Record another session", GREEN, self.new_session).pack(anchor="w", pady=20)

    def _session_done(self, fut: concurrent.futures.Future) -> None:
        if self._closing:
            self.root.destroy()
            return
        try:
            info = fut.result()
        except concurrent.futures.CancelledError:
            info, error = None, "Cancelled. Nothing was recorded."
        except Exception as e:     # noqa: BLE001
            info, error = None, f"{type(e).__name__}: {e}"
        else:
            error = None
        if info is None and self.live.folder is None:          # never started recording: back to Setup
            self.show("setup")
            self.setup_error.configure(text=error or (self.messages[-1] if self.messages else "Could not start."))
            return
        if error:
            self.live.problems.append(error)
        head, colour, lines = summary(self.live)
        self.finished_head.configure(text=head, fg=colour)
        self.finished_text.configure(text="\n".join(lines))
        self.show("finished")
        if colour == RED:
            self.root.bell()
            self.root.deiconify()
            self.root.lift()

    def new_session(self) -> None:
        self.marker_note.set("")
        self._suggest_session()
        self.show("setup")

    # ---- helpers ----

    def _when_done(self, fut: concurrent.futures.Future, callback: Callable[[concurrent.futures.Future], None]) -> None:
        """Calls callback(fut) on the window's thread once fut is done (Tk widgets must only be touched there)."""
        def poll() -> None:
            if fut.done():
                callback(fut)
            else:
                self.root.after(100, poll)
        poll()

    def on_close(self) -> None:
        running = self.future is not None and not self.future.done()
        if not running:
            self.root.destroy()
            return
        if not messagebox.askyesno("Recording in progress",
                                   "A recording is running. Stop it, save the data and close?", parent=self.root):
            return
        self._closing = True                  # _session_done closes the window once files are saved
        if not (self.control and self.control.stop()):
            self.future.cancel()


def main(data_root: Path = config.DATA_ROOT) -> int:
    root = tk.Tk()
    try:
        ttk.Style(root).theme_use("aqua" if sys.platform == "darwin" else "clam")
    except tk.TclError:
        pass
    app = App(root, data_root)
    root.mainloop()
    app.runner.close()
    return 0
