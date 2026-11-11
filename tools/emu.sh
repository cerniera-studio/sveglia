# Sourced by emulator_test.sh. shot NAME, tap_text TEXT, crashed
shot() { adb exec-out screencap -p > "shot-$1.png"; }
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -F "text=\"$1\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b
  [ -n "$4" ] && adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
crashed() { adb logcat -d | grep -A2 "FATAL EXCEPTION" | grep -q "Process: $PKG"; }
