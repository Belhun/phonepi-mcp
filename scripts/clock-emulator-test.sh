#!/bin/sh
# Install PhonePi Clock, then the companion, and check that an alarm stays armed.
# Pass -s SERIAL for an emulator or a real device. The run changes Doze, the
# timezone, and the clock, reboots, and sets a PIN when the device has no
# credential. It restores the date, timezone, and that PIN before it exits.
#
#   scripts/clock-emulator-test.sh -s emulator-5554 \
#     --clock-apk phonepi-clock/app/build/outputs/apk/debug/app-debug.apk \
#     --companion-apk phonepi-android/app/build/outputs/apk/debug/app-debug.apk
#
# --update-apk is optional. Without it the script builds a debug clock APK
# whose versionCode is one higher than --clock-apk (-PclockVersionCode).
set -u

ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
SERIAL=${ANDROID_SERIAL:-}
CLOCK_APK=""
COMPANION_APK=""
UPDATE_APK=""
LEAD=${CLOCK_TEST_LEAD_SEC:-180}
PIN=1357
FAILED=0
PIN_SET=0
OPEN_ALARM=""
SAVED_TZ=""
SAVED_EPOCH=""
SAVED_HOST_EPOCH=""
LOCK_WAS_DISABLED=""

usage() {
  echo "Usage: scripts/clock-emulator-test.sh -s SERIAL --clock-apk PATH --companion-apk PATH [--update-apk PATH]"
  echo "SERIAL may be an emulator or a device. The run reboots that device."
}

while [ $# -gt 0 ]; do
  case "$1" in
    -s|--serial) SERIAL=${2:-}; shift 2 ;;
    --clock-apk) CLOCK_APK=${2:-}; shift 2 ;;
    --companion-apk) COMPANION_APK=${2:-}; shift 2 ;;
    --update-apk) UPDATE_APK=${2:-}; shift 2 ;;
    --lead) LEAD=${2:-}; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1"; usage; exit 2 ;;
  esac
done

adb_shell() {
  adb -s "$SERIAL" shell "$@"
}

record() {
  name=$1
  status=$2
  detail=$3
  printf '%s %s %s\n' "$name" "$status" "$detail"
  echo "$name $status" >> "$WORK/results"
  if [ "$status" = "FAIL" ]; then
    FAILED=1
  fi
}

version_code() {
  apk=$1
  "$AAPT" dump badging "$apk" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -n 1
}

device_epoch() {
  adb_shell date +%s | tr -d '\r'
}

device_tz() {
  adb_shell getprop persist.sys.timezone | tr -d '\r'
}

set_timezone() {
  tz=$1
  if adb_shell cmd alarm set-timezone "$tz" >/dev/null 2>&1; then
    return 0
  fi
  adb_shell service call alarm 3 s16 "$tz" >/dev/null 2>&1
}

restore_device() {
  echo "Restoring date, timezone, and PIN on ${SERIAL:-unknown}"
  if [ -n "$SAVED_TZ" ]; then
    set_timezone "$SAVED_TZ" || echo "RESTORE timezone command failed"
  fi
  if [ -n "$SAVED_EPOCH" ] && [ -n "$SAVED_HOST_EPOCH" ]; then
    now_host=$(date +%s)
    elapsed=$((now_host - SAVED_HOST_EPOCH))
    target=$((SAVED_EPOCH + elapsed))
    device_now=$(device_epoch 2>/dev/null || echo 0)
    delta=$((device_now - target))
    if [ "$delta" -gt 20 ] || [ "$delta" -lt -20 ]; then
      if adb_shell cmd alarm set-time "$((target * 1000))" >/dev/null 2>&1; then
        echo "RESTORE date set back to epoch ${target}"
      else
        echo "RESTORE date command failed"
      fi
    else
      echo "RESTORE date left in place"
    fi
  fi
  if [ "$PIN_SET" = 1 ]; then
    adb_shell locksettings clear --old "$PIN" >/dev/null 2>&1 || echo "RESTORE PIN clear failed"
    if [ "$LOCK_WAS_DISABLED" = "true" ]; then
      adb_shell locksettings set-disabled true >/dev/null 2>&1 || true
    fi
    echo "RESTORE test PIN cleared"
  fi
  if [ -n "$OPEN_ALARM" ]; then
    adb_shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    adb_shell wm dismiss-keyguard >/dev/null 2>&1 || true
    dismiss_alarm "$OPEN_ALARM"
    delete_alarm "$OPEN_ALARM"
    echo "RESTORE test alarm delete attempted"
  fi
}

dump_alarm() {
  adb_shell dumpsys alarm | tr -d '\r'
}

# Print trigger times in epoch millis for dev.phonepi.clock, one per line.
# dumpsys alarm puts the package on the Alarm toString line
# (origWhen <epoch millis> ... dev.phonepi.clock) and formats the clock as
# triggerTime=yyyy-MM-dd HH:mm:ss.SSS in the device zone.
trigger_times() {
  tz=$1
  dump_alarm | TZ_NAME="$tz" python3 -c '
import os, re, sys
from datetime import datetime
from zoneinfo import ZoneInfo
text = sys.stdin.read()
tz = ZoneInfo(os.environ["TZ_NAME"])
needle = "dev.phonepi.clock"
times = []
start = 0
date_re = re.compile(r"(?:triggerTime|origWhen)=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})(?:\.\d+)?")
while True:
    i = text.find(needle, start)
    if i < 0:
        break
    window = text[max(0, i - 400): i + 1600]
    for match in re.finditer(r"origWhen (\d{12,})", window):
        times.append(int(match.group(1)))
    for match in date_re.finditer(window):
        parsed = datetime.strptime(match.group(1), "%Y-%m-%d %H:%M:%S").replace(tzinfo=tz)
        times.append(int(parsed.timestamp() * 1000))
    start = i + len(needle)
if needle not in text:
    sys.exit(2)
for item in times:
    print(item)
'
}

near_expected() {
  expected=$1
  tz=$2
  times=$(trigger_times "$tz" 2>/dev/null || true)
  if [ -z "$times" ]; then
    echo "no dev.phonepi.clock trigger in dumpsys alarm"
    return 1
  fi
  printf '%s\n' "$times" | python3 -c '
import sys
expected = int(sys.argv[1])
ok = False
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    if abs(int(line) - expected) <= 90000:
        ok = True
print("match" if ok else "miss")
' "$expected"
}

plan_alarm() {
  lead=$1
  epoch=$(device_epoch)
  tz=$(device_tz)
  python3 -c '
import sys
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
epoch = int(sys.argv[1])
lead = int(sys.argv[2])
zone = ZoneInfo(sys.argv[3])
now = datetime.fromtimestamp(epoch, zone)
fire = (now + timedelta(seconds=lead)).replace(second=0, microsecond=0)
if fire <= now + timedelta(seconds=45):
    fire = fire + timedelta(minutes=1)
print(fire.hour)
print(fire.minute)
print(int(fire.timestamp() * 1000))
' "$epoch" "$lead" "$tz"
}

provider_call() {
  method=$1
  json=$2
  escaped=$(printf '%s' "$json" | sed 's/"/\\"/g')
  adb_shell "run-as dev.phonepi.clock content call --uri content://dev.phonepi.clock.provider --method $method --extra payload:s:$escaped"
}

create_alarm() {
  lead=$1
  plan=$(plan_alarm "$lead") || return 1
  hour=$(printf '%s\n' "$plan" | sed -n '1p')
  minute=$(printf '%s\n' "$plan" | sed -n '2p')
  expected=$(printf '%s\n' "$plan" | sed -n '3p')
  request_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
  json=$(python3 -c 'import json,sys; print(json.dumps({"requestId":sys.argv[1],"hour":int(sys.argv[2]),"minute":int(sys.argv[3]),"days":0,"label":"harness","enabled":True}, separators=(",", ":")))' "$request_id" "$hour" "$minute")
  result=$(provider_call clock_create_alarm "$json" 2>&1 || true)
  alarm_id=$(printf '%s' "$result" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -n 1)
  if [ -z "$alarm_id" ]; then
    echo "create failed: $result" >&2
    return 1
  fi
  OPEN_ALARM=$alarm_id
  printf '%s %s %s %s\n' "$hour" "$minute" "$expected" "$alarm_id"
}

delete_alarm() {
  alarm_id=$1
  [ -n "$alarm_id" ] || return 0
  request_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
  json=$(python3 -c 'import json,sys; print(json.dumps({"requestId":sys.argv[1],"id":sys.argv[2]}, separators=(",", ":")))' "$request_id" "$alarm_id")
  provider_call clock_delete_alarm "$json" >/dev/null 2>&1 || true
}

dismiss_alarm() {
  alarm_id=$1
  request_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
  json=$(python3 -c 'import json,sys; print(json.dumps({"requestId":sys.argv[1],"id":sys.argv[2]}, separators=(",", ":")))' "$request_id" "$alarm_id")
  provider_call clock_dismiss "$json" >/dev/null 2>&1 || true
}

expect_in_zone() {
  hour=$1
  minute=$2
  tz=$3
  epoch=$(device_epoch)
  python3 -c '
import sys
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
epoch = int(sys.argv[1])
hour = int(sys.argv[2])
minute = int(sys.argv[3])
zone = ZoneInfo(sys.argv[4])
now = datetime.fromtimestamp(epoch, zone)
fire = now.replace(hour=hour, minute=minute, second=0, microsecond=0)
if fire <= now:
    fire = fire + timedelta(days=1)
print(int(fire.timestamp() * 1000))
' "$epoch" "$hour" "$minute" "$tz"
}

# Prints none, locked, or unknown. locked and unknown both refuse a new PIN.
credential_state() {
  adb_shell dumpsys lock_settings | tr -d '\r' | python3 -c '
import re, sys
text = sys.stdin.read().lower()
types = re.findall(r"credentialtype:\s*([a-z]+)", text)
if not types:
    print("unknown")
elif any(item != "none" for item in types):
    print("locked")
else:
    print("none")
'
}

if [ -z "$SERIAL" ] || [ -z "$CLOCK_APK" ] || [ -z "$COMPANION_APK" ]; then
  usage
  exit 2
fi
if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not on PATH"
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is not on PATH"
  exit 1
fi
if [ ! -f "$CLOCK_APK" ] || [ ! -f "$COMPANION_APK" ]; then
  echo "Clock APK and companion APK must both exist"
  exit 1
fi

ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$ANDROID_HOME" ] && [ -d "$HOME/android-sdk" ]; then
  ANDROID_HOME=$HOME/android-sdk
fi
AAPT=""
if [ -n "$ANDROID_HOME" ]; then
  AAPT=$(find "$ANDROID_HOME/build-tools" -name aapt -type f 2>/dev/null | sort | tail -n 1)
fi
if [ -z "$AAPT" ]; then
  echo "aapt was not found under ANDROID_HOME/build-tools"
  exit 1
fi

state=$(adb devices | awk -v serial="$SERIAL" '$1 == serial { print $2 }')
if [ "$state" != "device" ]; then
  echo "adb serial ${SERIAL} is not in the device state (got '${state:-missing}')"
  exit 1
fi

WORK=$(mktemp -d)
trap 'restore_device; rm -rf "$WORK"' EXIT
: > "$WORK/results"

cp "$CLOCK_APK" "$WORK/clock-base.apk"
BASE_CODE=$(version_code "$WORK/clock-base.apk")
if [ -z "$BASE_CODE" ]; then
  echo "Could not read versionCode from $CLOCK_APK"
  exit 1
fi
if [ -z "$UPDATE_APK" ]; then
  next=$((BASE_CODE + 1))
  echo "Building clock versionCode ${next} for the update scenario"
  if ! (cd "$ROOT/phonepi-clock" && ./gradlew :app:assembleDebug -PclockVersionCode="$next"); then
    echo "Gradle build of the update APK failed"
    exit 1
  fi
  cp "$ROOT/phonepi-clock/app/build/outputs/apk/debug/app-debug.apk" "$WORK/clock-update.apk"
  UPDATE_APK=$WORK/clock-update.apk
fi
if [ ! -f "$UPDATE_APK" ]; then
  echo "Update APK does not exist: $UPDATE_APK"
  exit 1
fi
UPDATE_CODE=$(version_code "$UPDATE_APK")
if [ -z "$UPDATE_CODE" ] || [ "$UPDATE_CODE" -le "$BASE_CODE" ]; then
  echo "Update APK versionCode (${UPDATE_CODE:-none}) must be higher than ${BASE_CODE}"
  exit 1
fi

SAVED_TZ=$(device_tz)
SAVED_EPOCH=$(device_epoch)
SAVED_HOST_EPOCH=$(date +%s)
LOCK_WAS_DISABLED=$(adb_shell locksettings get-disabled | tr -d '\r' || true)
echo "Target ${SERIAL} timezone ${SAVED_TZ}"

if ! adb -s "$SERIAL" install -r -d "$WORK/clock-base.apk"; then
  for name in doze timezone reboot update; do
    record "$name" FAIL "clock install failed"
  done
  printf '\n'
  cat "$WORK/results"
  exit 1
fi
if ! adb -s "$SERIAL" install -r -d "$COMPANION_APK"; then
  for name in doze timezone reboot update; do
    record "$name" FAIL "companion install failed"
  done
  printf '\n'
  cat "$WORK/results"
  exit 1
fi

run_doze() {
  created=$(create_alarm "$LEAD") || {
    record doze FAIL "provider create failed"
    return
  }
  hour=$(printf '%s\n' "$created" | awk '{print $1}')
  minute=$(printf '%s\n' "$created" | awk '{print $2}')
  expected=$(printf '%s\n' "$created" | awk '{print $3}')
  alarm_id=$(printf '%s\n' "$created" | awk '{print $4}')
  tz=$(device_tz)
  armed=$(near_expected "$expected" "$tz" || true)
  if [ "$armed" != "match" ]; then
    delete_alarm "$alarm_id"
    record doze FAIL "alarm not armed before idle ($armed)"
    return
  fi
  adb -s "$SERIAL" logcat -c || true
  adb_shell dumpsys deviceidle force-idle >/dev/null 2>&1 || adb_shell cmd deviceidle force-idle >/dev/null 2>&1 || true
  idle=$(adb_shell dumpsys deviceidle | tr -d '\r' | grep -E 'mForceIdle=true|mState=IDLE$' || true)
  if [ -z "$idle" ]; then
    adb_shell dumpsys deviceidle unforce >/dev/null 2>&1 || adb_shell cmd deviceidle unforce >/dev/null 2>&1 || true
    delete_alarm "$alarm_id"
    record doze FAIL "deviceidle force-idle did not enter idle"
    return
  fi
  deadline=$(( $(date +%s) + LEAD + 90 ))
  seen=0
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if adb -s "$SERIAL" logcat -d | tr -d '\r' | grep PhonePiClock | grep -E 'RingingService start|RingActivity start' >/dev/null; then
      seen=1
      break
    fi
    sleep 5
  done
  adb_shell dumpsys deviceidle unforce >/dev/null 2>&1 || adb_shell cmd deviceidle unforce >/dev/null 2>&1 || true
  dismiss_alarm "$alarm_id"
  delete_alarm "$alarm_id"
  if [ "$seen" = 1 ]; then
    record doze PASS "fired at ${hour}:$(printf '%02d' "$minute") and logcat showed the ring"
  else
    record doze FAIL "dumpsys was armed but RingActivity/RingingService did not start"
  fi
}

run_timezone() {
  created=$(create_alarm "$LEAD") || {
    record timezone FAIL "provider create failed"
    return
  }
  hour=$(printf '%s\n' "$created" | awk '{print $1}')
  minute=$(printf '%s\n' "$created" | awk '{print $2}')
  before=$(printf '%s\n' "$created" | awk '{print $3}')
  alarm_id=$(printf '%s\n' "$created" | awk '{print $4}')
  current=$(device_tz)
  if [ "$current" = "America/New_York" ]; then
    target_tz=America/Los_Angeles
  else
    target_tz=America/New_York
  fi
  if ! set_timezone "$target_tz"; then
    delete_alarm "$alarm_id"
    record timezone FAIL "could not set timezone to ${target_tz}"
    return
  fi
  tries=0
  while [ "$(device_tz)" != "$target_tz" ] && [ "$tries" -lt 8 ]; do
    tries=$((tries + 1))
    sleep 1
  done
  if [ "$(device_tz)" != "$target_tz" ]; then
    delete_alarm "$alarm_id"
    set_timezone "$SAVED_TZ" || true
    record timezone FAIL "timezone stayed $(device_tz) after set-timezone ${target_tz}"
    return
  fi
  expected=$(expect_in_zone "$hour" "$minute" "$target_tz")
  armed=miss
  tries=0
  while [ "$tries" -lt 8 ]; do
    armed=$(near_expected "$expected" "$target_tz" || true)
    if [ "$armed" = "match" ]; then
      break
    fi
    tries=$((tries + 1))
    sleep 2
  done
  moved=0
  if [ -n "$before" ] && [ -n "$expected" ] && [ "$before" != "$expected" ]; then
    moved=1
  fi
  delete_alarm "$alarm_id"
  set_timezone "$SAVED_TZ" || true
  if [ "$armed" = "match" ] && [ "$moved" = 1 ]; then
    record timezone PASS "wall ${hour}:$(printf '%02d' "$minute") moved from ${before} to ${expected} in ${target_tz}"
  else
    record timezone FAIL "armed=${armed} moved=${moved} expected=${expected}"
  fi
}

run_reboot() {
  lock_state=$(credential_state)
  if [ "$lock_state" != "none" ]; then
    record reboot FAIL "screen lock is ${lock_state}; PIN was not changed"
    return
  fi
  created=$(create_alarm "$LEAD") || {
    record reboot FAIL "provider create failed"
    return
  }
  expected=$(printf '%s\n' "$created" | awk '{print $3}')
  alarm_id=$(printf '%s\n' "$created" | awk '{print $4}')
  tz=$(device_tz)
  armed=$(near_expected "$expected" "$tz" || true)
  if [ "$armed" != "match" ]; then
    delete_alarm "$alarm_id"
    record reboot FAIL "alarm not armed before reboot ($armed)"
    return
  fi
  if ! adb_shell locksettings set-pin "$PIN" >/dev/null 2>&1; then
    delete_alarm "$alarm_id"
    record reboot FAIL "locksettings set-pin failed"
    return
  fi
  PIN_SET=1
  adb -s "$SERIAL" reboot || {
    record reboot FAIL "reboot command failed"
    return
  }
  adb -s "$SERIAL" wait-for-device
  ready=0
  tries=0
  while [ "$tries" -lt 45 ]; do
    if dump_alarm | grep -q 'dev.phonepi.clock'; then
      ready=1
      break
    fi
    tries=$((tries + 1))
    sleep 2
  done
  locked=$(adb_shell dumpsys window | tr -d '\r' | grep -E 'mDreamingLockscreen=true|isKeyguardShowing=true|mShowingLockscreen=true' || true)
  armed=$(near_expected "$expected" "$tz" || true)
  if [ "$ready" = 1 ] && [ "$armed" = "match" ] && [ -n "$locked" ]; then
    record reboot PASS "dumpsys still shows the alarm before unlock"
  else
    record reboot FAIL "ready=${ready} armed=${armed} locked=$([ -n "$locked" ] && echo yes || echo no)"
  fi
}

run_update() {
  created=$(create_alarm "$LEAD") || {
    record update FAIL "provider create failed"
    return
  }
  hour=$(printf '%s\n' "$created" | awk '{print $1}')
  minute=$(printf '%s\n' "$created" | awk '{print $2}')
  expected=$(printf '%s\n' "$created" | awk '{print $3}')
  alarm_id=$(printf '%s\n' "$created" | awk '{print $4}')
  tz=$(device_tz)
  armed=$(near_expected "$expected" "$tz" || true)
  if [ "$armed" != "match" ]; then
    delete_alarm "$alarm_id"
    record update FAIL "alarm not armed before update ($armed)"
    return
  fi
  adb -s "$SERIAL" logcat -c || true
  if ! adb -s "$SERIAL" install -r "$UPDATE_APK"; then
    delete_alarm "$alarm_id"
    record update FAIL "adb install -r failed"
    return
  fi
  installed=""
  log_hit=""
  armed=miss
  tries=0
  while [ "$tries" -lt 8 ]; do
    installed=$(adb_shell dumpsys package dev.phonepi.clock | tr -d '\r' | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n 1)
    log_hit=$(adb -s "$SERIAL" logcat -d | tr -d '\r' | grep PhonePiClock | grep -E 'MY_PACKAGE_REPLACED|rearm android.intent.action.MY_PACKAGE_REPLACED' || true)
    armed=$(near_expected "$expected" "$tz" || true)
    if [ "$installed" = "$UPDATE_CODE" ] && [ -n "$log_hit" ] && [ "$armed" = "match" ]; then
      break
    fi
    tries=$((tries + 1))
    sleep 2
  done
  if [ "$installed" = "$UPDATE_CODE" ] && [ -n "$log_hit" ] && [ "$armed" = "match" ]; then
    record update PASS "versionCode ${installed} re-armed ${hour}:$(printf '%02d' "$minute")"
  else
    record update FAIL "version=${installed:-none} log=$([ -n "$log_hit" ] && echo yes || echo no) armed=${armed}"
  fi
  delete_alarm "$alarm_id"
}

run_doze
run_timezone
run_update
run_reboot

printf '\n'
cat "$WORK/results"
if [ "$FAILED" = 0 ]; then
  exit 0
fi
exit 1
