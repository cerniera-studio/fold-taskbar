#!/usr/bin/env bash
# Fold Taskbar in the four shapes of the Fold: settings on the cover and the main screen, each upright
# and turned, and the bar itself over the home screen on the main screen both ways round (it hides on
# the cover by design). The glass in dp from Mechack's screenshots: cover about 411 x 960, main about
# 859 x 896; the emulator stops at 1920 px, so density is lowered to keep the dp.
set +e
PKG=com.fergusonconsultancy.foldtaskbar
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
for i in $(seq 1 30); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 2; done
sleep 30
adb install -r "$APK"
adb logcat -c
mkdir -p shots
shot() { adb exec-out screencap -p > "shots/$1.png"; echo "shot $1"; }
settings() { adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 6; }
size() { adb shell wm density $2; adb shell wm size $1; sleep 3; }
for s in "1-cover-portrait 823x1920 320" "2-cover-landscape 1920x823 320" "3-main-portrait 1503x1568 280" "4-main-landscape 1568x1503 280"; do
  set -- $s
  size $2 $3; settings; shot "$1-settings"
  adb shell input swipe 300 1200 300 200 300; sleep 0.6; adb shell input swipe 300 1200 300 200 300; sleep 1.5
  shot "$1-settings-lower"
done
# The bar: switch the service on (the emulator allows it from the shell) and go home
SVC="$PKG/.TaskbarService"
echo "service: $SVC"
adb shell settings put secure enabled_accessibility_services "$SVC"
adb shell settings put secure accessibility_enabled 1
for s in "5-main-portrait-bar 1503x1568 280" "6-main-landscape-bar 1568x1503 280"; do
  set -- $s
  size $2 $3
  adb shell input keyevent KEYCODE_HOME; sleep 5; shot "$1-home"
  adb shell am start -W -a android.settings.SETTINGS >/dev/null; sleep 5; shot "$1-over-app"
done
size 823x1920 320; adb shell input keyevent KEYCODE_HOME; sleep 4; shot "7-cover-bar-hidden"
adb shell wm size reset; adb shell wm density reset
adb logcat -d > logcat.txt
if grep -q "FATAL EXCEPTION" logcat.txt; then
  grep -A 40 "FATAL EXCEPTION" logcat.txt | grep -E "AndroidRuntime" | head -40 | sed 's/^.*AndroidRuntime: //' | while IFS= read -r line; do echo "::error::$line"; done
  exit 1
fi
echo "::notice::Fold Taskbar ran in every shape without a crash"
exit 0
