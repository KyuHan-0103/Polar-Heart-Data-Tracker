"""The window: plain-language helpers, plus a full run of the real window with a fake strap."""
import time

import pytest

tk = pytest.importorskip("tkinter")

from h10_recorder import ui
from h10_recorder.models import HrPacket, LiveStatus, Phase, StopReason
from h10_recorder.storage import next_session_id
from tests.conftest import read_csv
from tests.test_session import FOUND, FakeSensor


# ---- helpers ----

def test_format_hms():
    assert ui.format_hms(None) == "--:--:--"
    assert ui.format_hms(0) == "00:00:00"
    assert ui.format_hms(3725.9) == "01:02:05"


def test_check_inputs_explains_problems_in_plain_language(tmp_path):
    assert "participant code" in ui.check_inputs("", "01", tmp_path)
    assert "session number" in ui.check_inputs("P001", "", tmp_path)
    assert "never names" in ui.check_inputs("Jane Doe", "01", tmp_path)
    assert ui.check_inputs(" P001 ", "01", tmp_path) is None
    (tmp_path / "sub-P001" / "ses-01").mkdir(parents=True)
    msg = ui.check_inputs("P001", "01", tmp_path)
    assert "already recorded" in msg and "(02)" in msg


def test_next_session_id(tmp_path):
    assert next_session_id(tmp_path, "P001") == "01"
    for s in ("01", "02", "05"):
        (tmp_path / "sub-P001" / f"ses-{s}").mkdir(parents=True)
    assert next_session_id(tmp_path, "P001") == "06"
    assert next_session_id(tmp_path, "P002") == "01"
    with pytest.raises(ValueError):
        next_session_id(tmp_path, "Jane")


def test_status_view_for_each_phase():
    now = 100 * 10**9
    live = LiveStatus(phase=Phase.CONNECTING, device_id="B5A3F42C")
    assert ui.status_view(live, now)[0] == "Connecting…"
    live.phase = Phase.RECORDING
    assert "waiting for heart rate" in ui.status_view(live, now)[0]
    live.last_packet_ns = now - 10**9
    head, detail, colour = ui.status_view(live, now)
    assert head == "Connected — recording" and colour == ui.GREEN and "1 s ago" in detail
    live.last_packet_ns = now - 8 * 10**9
    assert "no heart rate data" in ui.status_view(live, now)[0]
    live.phase = Phase.RECONNECTING
    assert ui.status_view(live, now)[2] == ui.RED


def test_contact_text():
    assert ui.contact_text(True)[1] == ui.GREEN
    assert "poor" in ui.contact_text(False)[0]
    assert ui.contact_text(None)[0] == ""


def test_summary_wording():
    ok = LiveStatus(stop_reason=StopReason.USER, duration_s=65, folder="/x")
    assert ui.summary(ok)[0] == "Recording finished and saved"
    lost = LiveStatus(stop_reason=StopReason.CONNECTION_LOST, folder="/x")
    head, colour, lines = ui.summary(lost)
    assert "connection was lost" in head and colour == ui.RED and any("NEW session" in l for l in lines)


# ---- the real window, fake strap ----

@pytest.fixture
def window(tmp_path, clock, monkeypatch):
    try:
        root = tk.Tk()
    except tk.TclError:
        pytest.skip("no display")
    root.withdraw()
    monkeypatch.setattr(ui.messagebox, "askyesno", lambda *a, **k: True)   # "Yes, stop"

    async def finder():
        return [FOUND]

    async def script(s):
        s.put(lambda t: HrPacket(t, 72, (840,), True))

    app = ui.App(root, data_root=tmp_path, clock=clock, finder=finder, sensor_factory=FakeSensor(script),
                 keep_awake=False)
    yield app
    app.runner.close()
    root.destroy()


def pump(app, until, timeout=5.0):
    end = time.monotonic() + timeout
    while not until():
        app.root.update()
        if time.monotonic() > end:
            raise AssertionError("timed out waiting for the window")
        time.sleep(0.01)


def test_full_session_through_the_window(window, tmp_path):
    app = window
    pump(app, lambda: app.strap.get() == "B5A3F42C")              # searched automatically on opening
    assert "Found 1 strap" in app.search_status.cget("text")

    app.start()                                                    # no codes typed yet
    assert "participant code" in app.setup_error.cget("text") and app.page == "setup"
    app.participant.set("P001")
    assert app.session.get() == "01"                               # suggested automatically
    app.start()
    assert app.page == "recording"

    pump(app, lambda: app.bpm.cget("text") == "72")                # live bpm shown
    assert app.headline.cget("text") == "Connected — recording"
    assert app.contact.cget("text") == "Skin contact: good"
    assert app.info["Strap battery"].cget("text") == "87%"

    app.marker_note.set("task A")
    app.add_marker()
    pump(app, lambda: app.marker_list.size() == 1)
    assert "task A" in app.marker_list.get(0)

    app.stop()
    pump(app, lambda: app.page == "finished")
    assert app.finished_head.cget("text") == "Recording finished and saved"
    d = tmp_path / "sub-P001" / "ses-01"
    events = [e["event"] for e in read_csv(d / "sub-P001_ses-01_events.csv")]
    assert events == ["recording_started", "connected", "marker", "recording_stopped"]
    assert len(read_csv(d / "sub-P001_ses-01_hr.csv")) == 1

    app.new_session()
    assert app.page == "setup" and app.session.get() == "02"


def test_existing_session_is_refused_in_the_window(window, tmp_path):
    app = window
    pump(app, lambda: app.strap.get() == "B5A3F42C")
    (tmp_path / "sub-P001" / "ses-01").mkdir(parents=True)
    app.participant.set("P001")
    app.session.set("01")
    app.start()
    assert "already recorded" in app.setup_error.cget("text") and app.page == "setup"


def test_failed_connect_returns_to_setup_with_the_reason(tmp_path, clock, monkeypatch):
    try:
        root = tk.Tk()
    except tk.TclError:
        pytest.skip("no display")
    root.withdraw()

    async def finder():
        return [FOUND]
    app = ui.App(root, data_root=tmp_path, clock=clock, finder=finder,
                 sensor_factory=FakeSensor(fail_connect=True), keep_awake=False)
    try:
        pump(app, lambda: app.strap.get() == "B5A3F42C")
        app.participant.set("P001")
        app.start()
        pump(app, lambda: app.page == "setup" and app.setup_error.cget("text"))
        assert "Nothing was recorded" in app.setup_error.cget("text")
        assert not (tmp_path / "sub-P001").exists()
    finally:
        app.runner.close()
        root.destroy()


def test_no_strap_found_message(tmp_path, clock):
    try:
        root = tk.Tk()
    except tk.TclError:
        pytest.skip("no display")
    root.withdraw()

    async def finder():
        return []
    app = ui.App(root, data_root=tmp_path, clock=clock, finder=finder, keep_awake=False)
    try:
        pump(app, lambda: "No strap found" in app.search_status.cget("text"))
        app.participant.set("P001")
        app.start()
        assert "No strap selected" in app.setup_error.cget("text")
    finally:
        app.runner.close()
        root.destroy()


def _tk_or_skip():
    try:
        root = tk.Tk()
    except tk.TclError:
        pytest.skip("no display")
    root.withdraw()
    return root


async def _one_strap():
    return [FOUND]


def test_closing_the_window_mid_recording_saves_the_session(tmp_path, clock, monkeypatch):
    root = _tk_or_skip()
    monkeypatch.setattr(ui.messagebox, "askyesno", lambda *a, **k: True)
    app = ui.App(root, data_root=tmp_path, clock=clock, finder=_one_strap, sensor_factory=FakeSensor(),
                 keep_awake=False)
    destroyed = []
    root.bind("<Destroy>", lambda e: e.widget is root and destroyed.append(True))
    pump(app, lambda: app.strap.get() == "B5A3F42C")
    app.participant.set("P001")
    app.start()
    pump(app, lambda: app.live.phase == Phase.RECORDING)
    app.on_close()
    end = time.monotonic() + 5
    while not destroyed and time.monotonic() < end:
        root.update()
        time.sleep(0.01)
    app.runner.close()
    assert destroyed
    import json
    meta = json.loads((tmp_path / "sub-P001" / "ses-01" / "sub-P001_ses-01_session.json").read_text())
    assert meta["stop_reason"] == "user" and meta["end_utc"] is not None


def test_cancel_while_connecting_writes_nothing(tmp_path, clock):
    import asyncio

    class Hangs(FakeSensor):
        async def connect(self):
            self.calls.append("connect")
            await asyncio.sleep(3600)
    root = _tk_or_skip()
    sensor = Hangs()
    app = ui.App(root, data_root=tmp_path, clock=clock, finder=_one_strap, sensor_factory=sensor, keep_awake=False)
    try:
        pump(app, lambda: app.strap.get() == "B5A3F42C")
        app.participant.set("P001")
        app.start()
        pump(app, lambda: app.live.phase == Phase.CONNECTING and app.stop_button.cget("text") == "Cancel")
        app.stop()
        pump(app, lambda: app.page == "setup")
        assert "Cancelled" in app.setup_error.cget("text")
        assert not (tmp_path / "sub-P001").exists()
        pump(app, lambda: "stop" in sensor.calls)                 # the half-open connection was closed
    finally:
        app.runner.close()
        root.destroy()
