#!/usr/bin/env bash
# Run by the emulator job: the alarm list, setting an alarm, the bedside clock, and a full dawn and
# ring, on the cover's shape and the main screen's, with screenshots. Fails if the app crashed.
set -x
PKG=com.cerniera.sveglia
source tools/emu.sh

adb install -r Sveglia.apk
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set $PKG USE_FULL_SCREEN_INTENT allow
adb logcat -c

adb shell am start -n $PKG/.MainActivity
sleep 6; shot home

# A new alarm: the sheet with its time drums, then save
tap_text "Add an alarm"; sleep 2; shot new-alarm
adb shell input swipe 300 1000 300 700 200; sleep 1.5; shot new-alarm-turned
tap_text "Save"; sleep 2; shot home-with-alarm

# The bedside clock
adb shell am start -n $PKG/.BedsideActivity; sleep 4; shot bedside
adb shell input keyevent KEYCODE_BACK; sleep 1

# The dawn and the ring, previewed: part way through the dawn, ringing, then snoozed
adb shell am start -n $PKG/.MainActivity; sleep 2
adb shell am start-foreground-service -n $PKG/.RingService -a com.cerniera.sveglia.PREVIEW
sleep 12; shot dawn-early
sleep 12; shot dawn-late
sleep 9; shot ringing
adb shell input tap 540 1000; sleep 3; shot after-snooze

# The main screen's shape: the list, then the dawn up the crease
adb shell wm size 2256x2504; sleep 3
adb shell am start -n $PKG/.MainActivity; sleep 3; shot home-unfolded
adb shell am start-foreground-service -n $PKG/.RingService -a com.cerniera.sveglia.PREVIEW
sleep 20; shot dawn-unfolded
adb shell am startservice -n $PKG/.RingService -a com.cerniera.sveglia.STOP
adb shell wm size reset; sleep 2

# Day light
adb shell "cmd uimode night no"; sleep 2
adb shell am start -n $PKG/.MainActivity; sleep 3; shot home-day
adb shell "cmd uimode night yes"

adb logcat -d > app-log.txt
if crashed; then echo "::error::Sveglia crashed"; grep -A20 "FATAL EXCEPTION" app-log.txt | head -40; exit 1; fi
echo "No crash"
