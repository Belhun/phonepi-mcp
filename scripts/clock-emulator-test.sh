#!/bin/sh
# PhonePi Clock checks that change Doze, the clock, or the timezone.
# Default is a dry run. Pass --run to execute them.
# This script exits unless the only targeted adb device is an emulator.
set -eu

RUN=0
if [ "${1:-}" = "--run" ]; then
  RUN=1
elif [ -n "${1:-}" ]; then
  echo "Usage: scripts/clock-emulator-test.sh [--run]"
  exit 2
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not on PATH. Nothing was sent to a device."
  exit 1
fi

serial=$(adb devices | awk 'NR>1 && $2=="device" { print $1; exit }')
if [ -z "${serial}" ]; then
  echo "No adb device is connected. Connect an emulator before --run."
  exit 1
fi

qemu=$(adb -s "$serial" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')
boot=$(adb -s "$serial" shell getprop ro.boot.qemu 2>/dev/null | tr -d '\r')
case "$serial" in
  emulator-*) emulator=1 ;;
  *) emulator=0 ;;
esac
if [ "$emulator" != 1 ] && [ "$qemu" != 1 ] && [ "$boot" != 1 ]; then
  echo "Refusing ${serial}. This is not an emulator."
  exit 2
fi

echo "Emulator ${serial}"
echo "1. dumpsys deviceidle force-idle, then unforce"
echo "2. adb root, then set the date so TIME_SET fires"
echo "3. set the timezone to America/Los_Angeles"
echo "4. reboot and confirm the next alarm is still armed before unlock"
if [ "$RUN" != 1 ]; then
  echo "Dry run only. Pass --run to execute these on the emulator."
  exit 0
fi

adb -s "$serial" shell dumpsys deviceidle force-idle
adb -s "$serial" shell dumpsys deviceidle unforce
adb -s "$serial" root || true
adb -s "$serial" shell date 010112002026.00
adb -s "$serial" shell setprop persist.sys.timezone America/Los_Angeles
echo "Rebooting the emulator. Confirm alarms after it boots and before you unlock."
adb -s "$serial" reboot
