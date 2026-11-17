@echo off
rem Double-click this file (Windows) to open the H10 recorder window.
rem First time: it sets up its own private Python environment in .venv\ (needs internet, ~1 minute).
cd /d "%~dp0"

if exist .venv\Scripts\python.exe goto run
echo Setting up the recorder (first start). This needs internet and takes about a minute...
py -3 -m venv .venv 2>nul || python -m venv .venv
if not exist .venv\Scripts\python.exe (
  echo.
  echo !!! Python 3.11 or newer was not found. Install it from https://www.python.org/downloads/
  echo     and tick "Add python.exe to PATH" in the installer. Then double-click this file again.
  pause
  exit /b 1
)
.venv\Scripts\python -m pip install --quiet --disable-pip-version-check -r requirements.txt
if errorlevel 1 (
  echo !!! Could not download the Bluetooth libraries. Check the internet connection and try again.
  rmdir /s /q .venv
  pause
  exit /b 1
)

:run
echo Opening the H10 recorder window. Keep this window open while recording.
.venv\Scripts\python -m h10_recorder
if errorlevel 1 pause
