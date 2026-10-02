#!/usr/bin/env bash
# Retro v7: the phone checks that do not need a phone, scripted for an emulator. Run from Git Bash:
#   scripts/emulator-checks.sh avd       create an API 37 AVD at 1080x2424 (its system server crashed in a loop on the owner's PC; use emulator-5554)
#   scripts/emulator-checks.sh smoke     install debug, run the instrumented smokes (LiveSmokeTest, ThemeSweepSmokeTest)
#   scripts/emulator-checks.sh offline   airplane mode on/off with the app open; pull report.txt
#   scripts/emulator-checks.sh disk      fill the data partition, then free it (full-disk path; emulator only)
#   scripts/emulator-checks.sh shots     light and dark screenshots of the main tabs into build/emulator-shots
# Never run `disk` or `smoke` against the owner's phone: pass the serial via SERIAL and it must start with emulator-.
set -u
SDK="$LOCALAPPDATA/Android/Sdk"
ADB="$SDK/platform-tools/adb.exe"
SERIAL="${SERIAL:-emulator-5554}"
case "$SERIAL" in emulator-*) ;; *) echo "refusing: $SERIAL is not an emulator"; exit 2;; esac
adb() { MSYS_NO_PATHCONV=1 "$ADB" -s "$SERIAL" "$@"; }
PKG=com.smugview.app

case "${1:-}" in
avd)
  export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager.bat" create avd -n SmugView37 -k "system-images;android-37.0;google_apis;x86_64" -d pixel_7 --force || exit 1
  AVD="$USERPROFILE/.android/avd/SmugView37.avd/config.ini"
  sed -i -e 's/^hw.lcd.height=.*/hw.lcd.height=2424/' -e 's/^disk.dataPartition.size=.*/disk.dataPartition.size=6G/' "$AVD"
  echo "start it with: $SDK/emulator/emulator -avd SmugView37 -no-snapshot-save &"
  ;;
smoke)
  export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"; unset MSYS_NO_PATHCONV
  export ANDROID_SERIAL="$SERIAL"
  ./gradlew.bat installDebug installDebugAndroidTest -PlocalBuild || exit 1
  adb shell am instrument -w "$PKG.test/androidx.test.runner.AndroidJUnitRunner"
  ;;
offline)
  adb shell am start -n "$PKG/.MainActivity" >/dev/null
  sleep 8
  adb shell cmd connectivity airplane-mode enable
  sleep 6; adb exec-out screencap -p > "${TEMP:-/tmp}/offline.png"; echo "offline screenshot: ${TEMP:-/tmp}/offline.png"
  adb shell cmd connectivity airplane-mode disable
  sleep 8
  adb pull "/sdcard/Android/data/$PKG/files/diagnostics/report.txt" "${TEMP:-/tmp}/emulator-report.txt" && grep -n -i "offline\|error\|exception" "${TEMP:-/tmp}/emulator-report.txt" | head -20
  ;;
disk)
  FREE_KB=$(adb shell df /data | tail -1 | awk '{print $4}')
  echo "free before: ${FREE_KB} KB"
  adb shell "fallocate -l $(( (FREE_KB - 20000) * 1024 )) /data/local/tmp/fill.bin" || adb shell "dd if=/dev/zero of=/data/local/tmp/fill.bin bs=1M count=$(( (FREE_KB - 20000) / 1024 ))"
  echo "disk is now full; exercise a download or kept gallery, then press Enter to free it"; read -r _
  adb shell rm -f /data/local/tmp/fill.bin
  ;;
shots)
  OUT=build/emulator-shots; mkdir -p "$OUT"
  adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 6
  for mode in no yes; do
    adb shell cmd uimode night $mode; sleep 3
    adb exec-out screencap -p > "$OUT/home-night-$mode.png"
  done
  adb shell cmd uimode night auto
  echo "wrote $OUT (the tab sweep itself is ThemeSweepSmokeTest)"
  ;;
*) sed -n 2,9p "$0"; exit 1;;
esac
