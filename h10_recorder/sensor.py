"""The only module that imports bleak/bleakheart: find, connect, stream, reconnect, stop.

Everything the strap sends is stamped with the monotonic clock the moment it arrives and put on the queue
as a labeled item (models.py). Nothing here writes files.

Why the HR packets are decoded here and not by bleakheart.HeartRate: bleakheart rounds RR to whole ms
(`round(rr * 1000 / 1024)`, up to 0.5 ms error per beat) and stamps packets with the wall clock
(`time_ns()`, which jumps when the clock syncs). We need the raw 1/1024 s values and the monotonic clock.
bleakheart is still used for ECG (PolarMeasurementData) and the battery level.
"""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from typing import Any, Callable

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError
from bleakheart import BatteryLevel, HeartRate, PolarMeasurementData

from . import config
from .clock import SessionClock
from .models import Connected, Disconnected, EcgFrame, HrPacket, QueueItem, ReconnectFailed

log = logging.getLogger(__name__)

HR_MEASUREMENT_UUID = HeartRate.CHARACTERISTIC     # Bluetooth SIG Heart Rate Measurement (0x2A37)
STOP_STEP_TIMEOUT_S = 5.0                          # each stop/disconnect step, so a hung call can't block exit

# Errors that mean "the link/connection failed, try again". Anything else is a bug and is raised.
RETRYABLE = (BleakError, OSError, asyncio.TimeoutError)


class SensorError(Exception):
    """Could not find or connect to the strap."""


@dataclass(frozen=True)
class FoundSensor:
    name: str          # "Polar H10 B5A3F42C"
    device_id: str     # "B5A3F42C", printed on the sensor
    ble_device: Any    # bleak BLEDevice (on macOS identified by a UUID, not a Bluetooth address)


def decode_hr(data: bytes) -> tuple[int, tuple[int, ...], bool | None]:
    """Heart Rate Measurement packet (Bluetooth SIG Heart Rate Service 1.0, section 3.1) ->
    (hr_bpm, rr intervals in 1/1024 s, skin contact or None if not reported)."""
    if len(data) < 2:
        raise ValueError("HR packet too short")
    flags = data[0]
    contact = bool(flags & 0x02) if flags & 0x04 else None   # bit 2: contact supported, bit 1: contact
    if flags & 0x01:                                          # bit 0: HR is uint16
        if len(data) < 3:
            raise ValueError("HR packet too short")
        hr, i = int.from_bytes(data[1:3], "little"), 3
    else:
        hr, i = data[1], 2
    if flags & 0x08:                                          # bit 3: energy expended present, skip it
        i += 2
    rr: list[int] = []
    if flags & 0x10:                                          # bit 4: RR intervals present
        while i + 1 < len(data):
            rr.append(int.from_bytes(data[i:i + 2], "little"))
            i += 2
    return hr, tuple(rr), contact


async def find(timeout_s: float = config.SCAN_TIMEOUT_S, prefix: str = config.SENSOR_NAME_PREFIX) -> list[FoundSensor]:
    """Straps whose advertised name starts with prefix ("Polar H10"). Never a hard-coded address."""
    found = await BleakScanner.discover(timeout=timeout_s, return_adv=True)
    sensors = []
    for device, adv in found.values():
        name = adv.local_name or device.name or ""
        if name.startswith(prefix):
            sensors.append(FoundSensor(name, name[len(prefix):].strip(), device))
    return sorted(sensors, key=lambda s: s.device_id)


class H10:
    """One strap. connect() -> watch() (handles reconnects) -> stop(). stop() is safe to call any time."""

    def __init__(self, found: FoundSensor, queue: asyncio.Queue[QueueItem], clock: SessionClock,
                 ecg: bool = config.ECG_ENABLED, connect_timeout_s: float = config.CONNECT_TIMEOUT_S,
                 retry_delay_s: float = config.RETRY_DELAY_S,
                 client_factory: Callable[..., Any] = BleakClient,
                 pmd_factory: Callable[..., Any] = PolarMeasurementData) -> None:
        self.found = found
        self._queue = queue
        self._clock = clock
        self._ecg = ecg
        self._connect_timeout_s = connect_timeout_s
        self._retry_delay_s = retry_delay_s
        self._client_factory = client_factory
        self._pmd_factory = pmd_factory
        self._client: Any = None
        self._pmd: Any = None
        self._lost = asyncio.Event()
        self._stopping = False
        self.battery_pct: int | None = None
        self.connected_at_ns: int | None = None

    # ---- connecting ----

    async def connect(self) -> None:
        """Connects (retrying) within connect_timeout_s, starts HR (and ECG) notifications. Raises SensorError."""
        await self._connect_with_retry(first_delay_s=0.0)

    async def watch(self) -> None:
        """Runs for the whole session. After a drop, retries for up to connect_timeout_s: puts Connected on
        success, or ReconnectFailed and returns. Cancel it to stop watching."""
        while True:
            await self._lost.wait()
            self._lost.clear()
            try:
                await self._connect_with_retry(first_delay_s=self._retry_delay_s)
            except SensorError:
                self._queue.put_nowait(ReconnectFailed(self._clock.monotonic_ns()))
                return
            self._queue.put_nowait(Connected(self.connected_at_ns))

    async def _connect_with_retry(self, first_delay_s: float) -> None:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + self._connect_timeout_s
        await asyncio.sleep(first_delay_s)
        last_error: BaseException | None = None
        while not self._stopping:
            remaining = deadline - loop.time()
            if remaining <= 0:
                break
            try:
                async with asyncio.timeout(remaining):
                    await self._connect_once(remaining)
                return
            except RETRYABLE as e:
                last_error = e
                log.info("connect attempt failed: %r", e)
                await asyncio.sleep(min(self._retry_delay_s, max(0.0, deadline - loop.time())))
        raise SensorError(f"Could not connect to {self.found.name} within {self._connect_timeout_s:g} s"
                          + (f" (last error: {last_error})" if last_error else ""))

    async def _connect_once(self, timeout_s: float) -> None:
        client = self._client_factory(self.found.ble_device, disconnected_callback=self._on_disconnect,
                                      timeout=timeout_s)
        pmd = None
        try:
            await client.connect()
            await client.start_notify(HR_MEASUREMENT_UUID, self._on_hr)
            if self._ecg:
                pmd = self._pmd_factory(client, callback=self._on_pmd)
                code, message, _ = await pmd.start_streaming("ECG")
                if code != 0:
                    raise BleakError(f"ECG start refused: {message}")
            try:
                self.battery_pct = await BatteryLevel(client).read()
            except RETRYABLE:
                self.battery_pct = None
        except BaseException:
            # includes cancellation by the timeout: never leave a half-open link behind
            await self._close(client, pmd)
            raise
        self._client, self._pmd = client, pmd
        self.connected_at_ns = self._clock.monotonic_ns()
        if not client.is_connected:          # dropped while we were still setting up: its callback was ignored
            self._on_disconnect(client)

    # ---- callbacks (run in the event loop) ----

    def _on_hr(self, _characteristic: Any, data: bytearray) -> None:
        t = self._clock.monotonic_ns()     # first thing: the arrival time is the only time this packet has
        try:
            hr, rr, contact = decode_hr(bytes(data))
        except ValueError:
            log.warning("ignored malformed HR packet")
            return
        self._queue.put_nowait(HrPacket(t, hr, rr, contact))

    def _on_pmd(self, item: tuple) -> None:
        t = self._clock.monotonic_ns()
        if item[0] == "ECG":               # ('ECG', sensor time of last sample in ns, [microvolts])
            self._queue.put_nowait(EcgFrame(t, item[1], tuple(item[2])))

    def _on_disconnect(self, client: Any) -> None:
        if self._stopping or client is not self._client:
            return                           # our own stop(), or a client from a failed attempt
        self._client = self._pmd = None
        self._queue.put_nowait(Disconnected(self._clock.monotonic_ns(), "connection lost"))
        self._lost.set()

    # ---- stopping ----

    async def stop(self) -> None:
        """Stops the ECG stream and HR notifications explicitly, then disconnects (SPEC: an unstopped stream
        can keep the H10 on until its battery runs out). Each step has a timeout; errors are logged, not raised."""
        self._stopping = True
        client, pmd = self._client, self._pmd
        self._client = self._pmd = None
        if client is not None:
            await self._close(client, pmd)

    async def _close(self, client: Any, pmd: Any) -> None:
        async def step(what: str, coro_fn: Callable[[], Any]) -> None:
            try:
                async with asyncio.timeout(STOP_STEP_TIMEOUT_S):
                    await coro_fn()
            except Exception as e:           # noqa: BLE001 - best effort, keep going
                log.warning("%s failed: %r", what, e)

        if getattr(client, "is_connected", False):
            if pmd is not None:
                await step("stop ECG", lambda: pmd.stop_streaming("ECG"))
            await step("stop HR", lambda: client.stop_notify(HR_MEASUREMENT_UUID))
        await step("disconnect", client.disconnect)
