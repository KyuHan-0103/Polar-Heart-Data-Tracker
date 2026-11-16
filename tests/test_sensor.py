"""sensor.py against a fake BleakClient: decoding, connect/retry, reconnect, and stop order.
No Bluetooth hardware needed. The real strap is tested by hand (README "First run on a real H10")."""
import asyncio

import pytest
from bleak.exc import BleakError

from h10_recorder.models import Connected, Disconnected, EcgFrame, HrPacket, ReconnectFailed
from h10_recorder.sensor import H10, HR_MEASUREMENT_UUID, FoundSensor, SensorError, decode_hr

BATTERY_UUID = "00002a19-0000-1000-8000-00805f9b34fb"


# ---- decoding (Bluetooth SIG Heart Rate Measurement) ----

def test_decode_uint8_hr_with_rr_and_contact():
    # flags 0x16: RR present, contact supported, contact detected; HR 72; RR 840, 856
    assert decode_hr(bytes([0x16, 72, 0x48, 0x03, 0x58, 0x03])) == (72, (840, 856), True)


def test_decode_contact_not_detected_and_not_supported():
    assert decode_hr(bytes([0x14, 60, 0x00, 0x04])) == (60, (1024,), False)
    assert decode_hr(bytes([0x00, 60])) == (60, (), None)


def test_decode_uint16_hr_and_energy_field_is_skipped():
    # flags 0x19: uint16 HR, energy present, RR present
    assert decode_hr(bytes([0x19, 0x2C, 0x01, 0xFF, 0xFF, 0x00, 0x04])) == (300, (1024,), None)


def test_decode_keeps_raw_1024ths_without_rounding():
    hr, rr, _ = decode_hr(bytes([0x10, 70, 0x01, 0x00]))
    assert rr == (1,)                                   # bleakheart would report round(0.977) = 1 ms


def test_decode_ignores_a_dangling_odd_byte_and_rejects_short_packets():
    assert decode_hr(bytes([0x10, 70, 0x00, 0x04, 0x01]))[1] == (1024,)
    with pytest.raises(ValueError):
        decode_hr(bytes([0x10]))
    with pytest.raises(ValueError):
        decode_hr(bytes([0x01, 0x2C]))


# ---- fakes ----

class FakeClient:
    """Plays the strap. log records every call in order."""
    instances: list["FakeClient"] = []
    fail_connects = 0          # the next N connect() calls raise BleakError
    hang_connect = False

    def __init__(self, device, disconnected_callback=None, timeout=30):
        self.device, self.on_disconnect, self.timeout = device, disconnected_callback, timeout
        self.is_connected = False
        self.log: list[str] = []
        self.hr_callback = None
        FakeClient.instances.append(self)

    async def connect(self):
        self.log.append("connect")
        if FakeClient.hang_connect:
            await asyncio.sleep(3600)
        if FakeClient.fail_connects > 0:
            FakeClient.fail_connects -= 1
            raise BleakError("device not found")
        self.is_connected = True

    async def start_notify(self, uuid, cb):
        assert uuid == HR_MEASUREMENT_UUID
        self.log.append("start_notify")
        self.hr_callback = cb

    async def stop_notify(self, uuid):
        self.log.append("stop_notify")

    async def read_gatt_char(self, uuid):
        assert uuid == BATTERY_UUID
        return bytearray([87])

    async def disconnect(self):
        self.log.append("disconnect")
        was = self.is_connected
        self.is_connected = False
        if was and self.on_disconnect:
            self.on_disconnect(self)

    def drop(self):
        """The strap goes out of range / is unclipped."""
        self.is_connected = False
        self.on_disconnect(self)


class FakePmd:
    def __init__(self, client, callback=None):
        self.client, self.callback = client, callback
        self.refuse = False

    async def start_streaming(self, what):
        self.client.log.append(f"start_{what}")
        return (0, "SUCCESS", None)

    async def stop_streaming(self, what):
        self.client.log.append(f"stop_{what}")
        return (0, "SUCCESS")


@pytest.fixture(autouse=True)
def reset_fake():
    FakeClient.instances, FakeClient.fail_connects, FakeClient.hang_connect = [], 0, False


FOUND = FoundSensor("Polar H10 B5A3F42C", "B5A3F42C", object())


def make(clock, queue, ecg=False, timeout=0.5):
    return H10(FOUND, queue, clock, ecg=ecg, connect_timeout_s=timeout, retry_delay_s=0.01,
               client_factory=FakeClient, pmd_factory=FakePmd)


# ---- connect / stream / stop ----

async def test_connect_starts_hr_notifications_and_reads_battery(clock):
    q = asyncio.Queue()
    s = make(clock, q)
    await s.connect()
    assert FakeClient.instances[0].log == ["connect", "start_notify"]
    assert s.battery_pct == 87
    assert s.connected_at_ns == clock.monotonic_ns()


async def test_packets_are_stamped_on_arrival_and_queued(clock):
    q = asyncio.Queue()
    s = make(clock, q)
    await s.connect()
    clock.advance(1.25)
    FakeClient.instances[0].hr_callback(None, bytearray([0x16, 72, 0x48, 0x03]))
    item = q.get_nowait()
    assert item == HrPacket(clock.monotonic_ns(), 72, (840,), True)


async def test_malformed_packets_are_ignored(clock):
    q = asyncio.Queue()
    s = make(clock, q)
    await s.connect()
    FakeClient.instances[0].hr_callback(None, bytearray([0x10]))
    assert q.empty()


async def test_stop_stops_the_stream_before_disconnecting(clock):
    q = asyncio.Queue()
    s = make(clock, q, ecg=True)
    await s.connect()
    await s.stop()
    assert FakeClient.instances[0].log == ["connect", "start_notify", "start_ECG", "stop_ECG", "stop_notify", "disconnect"]
    assert q.empty()                                        # our own disconnect is not a "connection lost"


async def test_stop_twice_and_stop_without_connect_are_safe(clock):
    await make(clock, asyncio.Queue()).stop()
    s = make(clock, asyncio.Queue())
    await s.connect()
    await s.stop()
    await s.stop()
    assert FakeClient.instances[0].log.count("disconnect") == 1


async def test_a_stopped_sensor_does_not_reconnect(clock):
    s = make(clock, asyncio.Queue())
    await s.stop()
    with pytest.raises(SensorError):
        await s.connect()
    assert FakeClient.instances == []


async def test_ecg_frames_are_queued_with_arrival_time(clock):
    q = asyncio.Queue()
    s = make(clock, q, ecg=True)
    await s.connect()
    s._pmd.callback(("ECG", 123, [1, 2, 3]))
    assert q.get_nowait() == EcgFrame(clock.monotonic_ns(), 123, (1, 2, 3))


# ---- retries and timeouts ----

async def test_connect_retries_connection_errors(clock):
    FakeClient.fail_connects = 2
    s = make(clock, asyncio.Queue())
    await s.connect()
    assert len(FakeClient.instances) == 3
    assert FakeClient.instances[-1].is_connected


async def test_connect_gives_up_after_the_timeout(clock):
    FakeClient.fail_connects = 10**6
    s = make(clock, asyncio.Queue(), timeout=0.2)
    loop = asyncio.get_running_loop()
    t0 = loop.time()
    with pytest.raises(SensorError):
        await s.connect()
    assert 0.15 < loop.time() - t0 < 1.0


async def test_a_hanging_connect_is_abandoned_and_its_link_closed(clock):
    FakeClient.hang_connect = True
    s = make(clock, asyncio.Queue(), timeout=0.2)
    with pytest.raises(SensorError):
        await s.connect()
    assert FakeClient.instances[0].log == ["connect", "disconnect"]


async def test_unexpected_errors_are_not_retried(clock):
    class Broken(FakeClient):
        async def start_notify(self, uuid, cb):
            raise RuntimeError("bug")
    s = H10(FOUND, asyncio.Queue(), clock, connect_timeout_s=0.5, client_factory=Broken, pmd_factory=FakePmd)
    with pytest.raises(RuntimeError):
        await s.connect()
    assert len(Broken.instances) == 1 and Broken.instances[0].log[-1] == "disconnect"


# ---- reconnect ----

async def test_drop_is_reported_then_reconnected(clock):
    q = asyncio.Queue()
    s = make(clock, q)
    await s.connect()
    watch = asyncio.create_task(s.watch())
    FakeClient.instances[0].drop()
    assert isinstance(await asyncio.wait_for(q.get(), 1), Disconnected)
    assert isinstance(await asyncio.wait_for(q.get(), 1), Connected)
    assert len(FakeClient.instances) == 2 and FakeClient.instances[1].log == ["connect", "start_notify"]
    watch.cancel()
    await s.stop()


async def test_reconnect_gives_up_and_reports_it(clock):
    q = asyncio.Queue()
    s = make(clock, q, timeout=0.2)
    await s.connect()
    watch = asyncio.create_task(s.watch())
    FakeClient.fail_connects = 10**6
    FakeClient.instances[0].drop()
    assert isinstance(await asyncio.wait_for(q.get(), 1), Disconnected)
    assert isinstance(await asyncio.wait_for(q.get(), 2), ReconnectFailed)
    await asyncio.wait_for(watch, 1)                       # watch() ends after giving up


async def test_drop_during_setup_is_not_missed(clock):
    class DropsDuringBattery(FakeClient):
        async def read_gatt_char(self, uuid):
            self.drop()                                    # callback fires before setup finished
            return bytearray([50])
    q = asyncio.Queue()
    s = H10(FOUND, q, clock, connect_timeout_s=0.5, retry_delay_s=0.01,
            client_factory=DropsDuringBattery, pmd_factory=FakePmd)
    await s.connect()
    assert isinstance(q.get_nowait(), Disconnected)
