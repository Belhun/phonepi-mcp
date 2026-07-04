@echo off
cd /d "%~dp0"
echo Starting PhonePi Test Console...
echo.
start "" "http://127.0.0.1:8765"
node server.js
