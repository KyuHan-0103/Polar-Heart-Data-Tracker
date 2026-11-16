"""Real H10 tests. Skipped unless a strap is named:  H10_DEVICE_ID=B5A3F42C pytest tests/test_hardware.py -s
Wear the strap (moist electrodes) and make sure no phone/watch app is connected to it."""
import asyncio
import os

import pytest

from h10_recorder.clock import SystemClock
from h10_recorder.models import HrPacket

DEVICE_ID = os.environ.get("H10_DEVICE_ID")
pytestmark = pytest.mark.skipif(not DEVICE_ID, reason="set H10_DEVICE_ID to run tests against a real H10")


async def _sensor(queue, **kw):
    from h10_recorder.sensor import H10, find
    found = [s for s in await find() if s.device_id.upper() == DEVICE_ID.upper()]
    assert found, f"H10 {DEVICE_ID} not found by search"
    return H10(found[0], queue, SystemClock(), **kw)


async def test_streams_hr_packets_about_once_a_second():
    q = asyncio.Queue()
    s = await _sensor(q)
    await s.connect()
    try:
        packets = [await asyncio.wait_for(q.get(), 5) for _ in range(5)]
    finally:
        await s.stop()
    assert all(isinstance(p, HrPacket) and 25 <= p.hr_bpm <= 240 for p in packets)
    gaps = [(b.received_ns - a.received_ns) / 1e9 for a, b in zip(packets, packets[1:])]
    print("packet gaps (s):", [round(g, 3) for g in gaps], "battery:", s.battery_pct)
    assert all(0.3 < g < 3 for g in gaps)
    assert any(p.rr_raw for p in packets), "no RR intervals: is the strap worn?"


async def test_stream_can_be_restarted_after_stop():
    for _ in range(2):
        q = asyncio.Queue()
        s = await _sensor(q)
        await s.connect()
        try:
            assert isinstance(await asyncio.wait_for(q.get(), 5), HrPacket)
        finally:
            await s.stop()


async def test_ecg_streams_and_stops():
    q = asyncio.Queue()
    s = await _sensor(q, ecg=True)
    await s.connect()
    try:
        frames = []
        while len(frames) < 3:
            item = await asyncio.wait_for(q.get(), 10)
            if not isinstance(item, HrPacket):
                frames.append(item)
    finally:
        await s.stop()
    assert all(len(f.samples_uv) > 0 for f in frames)
