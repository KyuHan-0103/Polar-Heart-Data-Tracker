"""Entry point:  python -m h10_recorder --participant P001 --session 01

Other commands:
  python -m h10_recorder --scan          list the H10 straps in range and their device IDs
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from pathlib import Path

from . import config
from .models import StopReason
from .storage import SessionExistsError

EXIT_OK, EXIT_ERROR, EXIT_CONNECTION_LOST, EXIT_NOT_STARTED = 0, 1, 2, 3


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="python -m h10_recorder", description="Record HR + RR from a Polar H10.")
    p.add_argument("--participant", help=f"participant code, e.g. {config.PARTICIPANT_ID_EXAMPLE}")  # ID-FORMAT
    p.add_argument("--session", help=f"session number, e.g. {config.SESSION_ID_EXAMPLE}")            # ID-FORMAT
    p.add_argument("--device", help="H10 device ID printed on the sensor (needed if several straps are in range)")
    p.add_argument("--ecg", action=argparse.BooleanOptionalAction, default=config.ECG_ENABLED,
                   help="also record raw ECG (default: %(default)s)")
    p.add_argument("--notes", default="", help="free-text session note (no names or identifying details)")
    p.add_argument("--data-root", type=Path, default=config.DATA_ROOT, help="data folder (default: %(default)s)")
    p.add_argument("--scan", action="store_true", help="list H10 straps in range and exit")
    p.add_argument("-v", "--verbose", action="store_true", help="show Bluetooth debug messages")
    args = p.parse_args(argv)
    if not args.scan and (not args.participant or not args.session):
        p.error("--participant and --session are required (or use --scan)")
    return args


async def scan() -> int:
    from .sensor import find
    found = await find()
    if not found:
        print(f"No {config.SENSOR_NAME_PREFIX} found. Wear the strap (moist electrodes) and try again.")
        return EXIT_NOT_STARTED
    for s in found:
        print(f"{s.device_id}\t{s.name}")
    return EXIT_OK


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.WARNING,
                        format="%(levelname)s %(name)s: %(message)s")
    if args.scan:
        return asyncio.run(scan())
    from .session import run_session
    try:
        info = asyncio.run(run_session(args.participant, args.session, device_id=args.device, ecg=args.ecg,
                                       notes=args.notes, root=args.data_root))
    except (ValueError, SessionExistsError) as e:     # bad IDs / session already recorded
        print(e, file=sys.stderr)
        return EXIT_NOT_STARTED
    except KeyboardInterrupt:
        return EXIT_OK
    if info is None:
        return EXIT_NOT_STARTED
    return {StopReason.USER: EXIT_OK, StopReason.CONNECTION_LOST: EXIT_CONNECTION_LOST}.get(info.stop_reason, EXIT_ERROR)


if __name__ == "__main__":
    sys.exit(main())
