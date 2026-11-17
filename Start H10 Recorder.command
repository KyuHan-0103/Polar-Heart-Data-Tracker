#!/bin/bash
# Double-click this file (macOS) to open the H10 recorder window.
# First time: it sets up its own private Python environment in .venv/ (needs internet, ~1 minute).
cd "$(dirname "$0")" || exit 1

fail() {
  echo
  echo "!!! $1"
  echo
  read -r -p "Press Enter to close this window."
  exit 1
}

# Re-install when requirements.txt changed since the last setup.
if [ ! -x .venv/bin/python ] || [ requirements.txt -nt .venv/.installed ]; then
  echo "Setting up the recorder (first start, or after an update). This needs internet and takes about a minute…"
  PY=""
  for candidate in python3.14 python3.13 python3.12 python3.11 python3 /usr/local/bin/python3; do
    if command -v "$candidate" >/dev/null 2>&1 &&
       "$candidate" -c "import sys, tkinter; sys.exit(sys.version_info < (3, 11))" >/dev/null 2>&1; then
      PY="$candidate"; break
    fi
  done
  [ -n "$PY" ] || fail "Python 3.11 or newer (with Tkinter) was not found.
    Install it from https://www.python.org/downloads/ (the standard installer includes everything),
    then double-click this file again."
  [ -x .venv/bin/python ] || "$PY" -m venv .venv || fail "Could not create the Python environment."
  .venv/bin/python -m pip install --quiet --disable-pip-version-check -r requirements.txt \
    || fail "Could not download the Bluetooth libraries. Check the internet connection and try again."
  touch .venv/.installed
fi

echo "Opening the H10 recorder window. Keep this Terminal window open while recording."
.venv/bin/python -m h10_recorder || fail "The recorder closed with an error (see the messages above)."
