#!/usr/bin/env bash
# Windows (adaptive quality, 19 Nov 2026): pop-up windows, resizing and continuity, on the main
# screen's shape (859 x 896 dp). Writes windows.txt; a line starting ::error:: fails the run.
#
# 1. The dawn in a pop-up window, shrunk to half width mid-dawn: the wake screen must stay and ring.
#    Before Kit PR #7 this read as a fold and stopped the alarm.
# 2. The alarm list in a pop-up window, narrow then wide: one column, then two panes, with the next
#    alarm, Add an alarm and the first alarm in view each time.
# 3. Continuity across a turn: the Add an alarm sheet stays open; the dawn keeps going.
#
# Pop-up windows need freeform support, which the emulator only reads at boot: this script reboots
# it, so it runs last.
PKG=com.cerniera.sveglia
out=windows.txt; : > $out
note() { echo "::notice::$*" | tee -a $out; }
fail() { echo "::error::$*" | tee -a $out; }
warn() { echo "::warning::$*" | tee -a $out; }
shot() { adb exec-out screencap -p > "shot-win-$1.png"; }

# ---- Freeform on, then reboot so the system reads it
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
adb reboot
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
sleep 15
adb shell input keyevent 82   # wake and unlock
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
for a in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $a 0; done

# The main screen, upright
adb shell wm density 280; adb shell wm size 1503x1568; sleep 3

# The app's tasks: "<id> <mode>" for the task whose affinity is $1 (exact)
task() {
  adb shell dumpsys activity activities | python3 -c "
import sys, re
for line in sys.stdin:
    m = re.search(r'\* Task\{\S+ #(\d+) .*?A=\d+:(\S+) .*?mode=(\S+)', line)
    if m and m.group(2) == '$1': print(m.group(1), m.group(3)); break"
}
resumed() { adb shell dumpsys activity activities | grep -E "ResumedActivity" | grep -q "$1"; }

# ---- 1. The dawn in a pop-up window, shrunk mid-dawn
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity --es open preview --es bounds "0,0,1503,1568"; sleep 8
set -- $(task $PKG.wake)
wake=$1; mode=$2
echo "wake task: $wake $mode" | tee -a $out
if [ "$mode" != freeform ]; then
  warn "Dawn in a pop-up window: the emulator would not open a pop-up window (mode ${mode:-none}); skipped"
else
  shot dawn-popup-full
  adb shell am task resize "$wake" 0 0 740 1568; sleep 4
  shot dawn-popup-narrow
  if resumed WakeActivity; then note "Dawn in a pop-up window: still showing after shrinking to half width"
  else fail "Dawn in a pop-up window: the wake screen closed when its window was shrunk (read as a fold?)"; fi
  sleep 22; shot ringing-popup-narrow
  if resumed WakeActivity; then note "Dawn in a pop-up window: rang in the narrow window"
  else fail "Dawn in a pop-up window: gone before the ring"; fi
fi
adb shell am force-stop $PKG; sleep 2

# ---- 2. The alarm list in a pop-up window, narrow then wide
layout() {  # name, wide expected (True/False)
  for try in 1 2 3 4 5 6 7 8; do
    json=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null); [ -n "$json" ] && break; sleep 1
  done
  python3 - "$1" "$2" "$json" <<'PY' | tee -a windows.txt
import sys, json
name, want, raw = sys.argv[1], sys.argv[2] == "True", sys.argv[3]
try: d = json.loads(raw)
except Exception: print(f"::error::{name}: no layout recorded"); sys.exit(0)
vw, vh = d["view"]; il, it, ir, ib = d["insets"]; ox, oy = d["origin"]
bad = []
for k in ("next", "add", "first-alarm"):
    if k not in d["parts"]: bad.append(f"{k} not laid out"); continue
    l, t, r, b = d["parts"][k]; l -= ox; r -= ox; t -= oy; b -= oy
    if l < 0 or t < 0 or r > vw or b > vh - ib: bad.append(f"{k} at {[l, t, r, b]} leaves the view {vw}x{vh}")
if d["wide"] != want: bad.append(("two panes" if d["wide"] else "one column") + " where " + ("two panes" if want else "one column") + " was expected")
lay = "two panes" if d["wide"] else "one column"
print(("::error::" if bad else "::notice::") + f"{name} ({lay}, view {vw}x{vh}): " + ("; ".join(bad) if bad else "next, add and first-alarm in view"))
PY
}
adb shell run-as $PKG rm -f files/layout.json
adb shell am start -W --windowingMode 5 -n $PKG/.MainActivity >/dev/null; sleep 5
set -- $(task $PKG)
list=$1; mode=$2
echo "list task: $list $mode" | tee -a $out
if [ "$mode" != freeform ]; then
  warn "List in a pop-up window: the emulator would not open a pop-up window (mode ${mode:-none}); skipped"
else
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am task resize "$list" 0 0 740 1568; sleep 4
  shot list-popup-narrow; layout "List in a narrow pop-up window" False
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am task resize "$list" 0 0 1503 1568; sleep 4
  shot list-popup-wide; layout "List in a wide pop-up window" True
fi
adb shell am force-stop $PKG; sleep 2

# ---- 3. Continuity across a turn (full screen)
has_text() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -qF "text=\"$1\""; }
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -F "text=\"$1\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b; [ -n "$4" ] && adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
adb shell am start -W --windowingMode 1 -n $PKG/.MainActivity >/dev/null; sleep 4
tap_text "Add an alarm"; sleep 2
if has_text "Save"; then
  shot sheet-upright
  adb shell settings put system user_rotation 1; sleep 4; shot sheet-turned
  if has_text "Save"; then note "Add an alarm sheet: still open after turning"; else fail "Add an alarm sheet: closed by turning the phone"; fi
  adb shell settings put system user_rotation 0; sleep 3
else
  warn "Add an alarm sheet: could not open it; skipped"
fi
adb shell am force-stop $PKG; sleep 1

adb shell am start -n $PKG/.MainActivity --es open preview; sleep 8
adb shell settings put system user_rotation 1; sleep 4; shot dawn-turned
if resumed WakeActivity; then note "Dawn: still showing after turning"; else fail "Dawn: the wake screen closed when the phone was turned"; fi
adb shell settings put system user_rotation 0; sleep 3
adb shell am force-stop $PKG

adb shell wm size reset; adb shell wm density reset
