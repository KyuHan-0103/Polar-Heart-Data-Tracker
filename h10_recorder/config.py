"""Every setting you might want to change ("knobs"), in one place. See README "Settings you can change"."""
from pathlib import Path

# ---- sensor ----
SENSOR_NAME_PREFIX = "Polar H10"     # the strap advertises as "Polar H10 <device ID>"
SCAN_TIMEOUT_S = 10.0                # how long to search for straps
ECG_ENABLED = False                  # also record raw ECG (130 Hz, microvolts). Off: only HR + RR are needed.
ECG_SAMPLE_RATE_HZ = 130             # the H10's ECG rate (bleakheart default)

# ---- connection ----
CONNECT_TIMEOUT_S = 20.0             # first connect and each reconnect give up after this long
RETRY_DELAY_S = 1.0                  # pause between failed (re)connect attempts

# ---- files ----
DATA_ROOT = Path.home() / "Downloads" / "H10_Data"
FLUSH_INTERVAL_S = 5.0               # rows are forced to disk at least this often (max data lost on a crash)

# ---- beat times (see DATA_NOTES.md "rr.csv: beat times") ----
BEAT_GAP_RESET_S = 2.0               # a packet this much later than the beat chain predicts = delay or missing beats
# How far back the beat-time estimate looks. NOT a minimum recording length: a 2-minute session works, and
# accuracy just keeps improving for the first few minutes. 300 s was chosen from simulation.
# REVISIT with real H10 data (see DATA_NOTES.md To do).
BEAT_WINDOW_S = 300.0
BEAT_REGULAR_GAP_S = 1.25            # packets at most this far apart are "on the regular ~1 s schedule"

# ---- IDs ----
# ============================== ID-FORMAT (TEMPORARY) ==============================
# PLACEHOLDER until the lab decides on an ID scheme. Change these two patterns, then the examples in
# tests/test_storage.py (ID-FORMAT tests), DATA_NOTES.md and the --help text in __main__.py.
# Search the project for "ID-FORMAT" to find every place.
PARTICIPANT_ID_PATTERN = r"P[0-9]{3}"    # ID-FORMAT: e.g. P001  ([0-9], not \d: \d also matches non-ASCII digits)
PARTICIPANT_ID_EXAMPLE = "P001"          # ID-FORMAT
SESSION_ID_PATTERN = r"[0-9]{2}"         # ID-FORMAT: e.g. 01
SESSION_ID_EXAMPLE = "01"                # ID-FORMAT
# ===================================================================================
