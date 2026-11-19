#!/usr/bin/env bash
# Windows (adaptive quality, 19 Nov 2026; Sveglia's tools/windows.sh is the template). Fold
# Taskbar's settings in a pop-up window and across a turn, on the main screen's shape (859 x 896
# dp). Results in shots/windows.txt; a line starting ::error:: fails the run.
#
# 1. The settings in a pop-up window, wide then narrow: the column fits the window each time.
# 2. The settings scrolled down keep their place across a turn (the activity is rebuilt).
#
# Pop-up windows need freeform support, which the emulator only reads at boot: this reboots it,
# so it runs after the launch test.
PKG=com.fergusonconsultancy.foldtaskbar
mkdir -p shots
out=shots/windows.txt; : > $out
note() { echo "::notice::$*" | tee -a $out; }
fail() { echo "::error::$*" | tee -a $out; }
warn() { echo "::warning::$*" | tee -a $out; }
shot() { adb exec-out screencap -p > "shots/win-$1.png"; }

adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
adb reboot
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
sleep 30
adb shell input keyevent 82
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb logcat -c

# The main screen, upright
adb shell wm density 280; adb shell wm size 1503x1568; sleep 3

task() {  # "<id> <mode>" of the task whose affinity is exactly $1
  adb shell dumpsys activity activities | python3 -c "
import sys, re
for line in sys.stdin:
    m = re.search(r'\* Task\{\S+ #(\d+) .*?A=\d+:(\S+) .*?mode=(\S+)', line)
    if m and m.group(2) == '$1': print(m.group(1), m.group(3)); break"
}
get() {  # a value from the settings' record
  for try in 1 2 3 4 5 6 7 8; do
    v=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null | python3 -c "import sys,json;d=json.load(sys.stdin);v=d['$1'];print(*v) if isinstance(v,list) else print(v)" 2>/dev/null)
    [ -n "$v" ] && break; sleep 1
  done
  echo "$v"
}
fits() {  # name
  set -- "$1" $(get view) $(get column)
  if [ -z "$3" ]; then fail "$1: no layout recorded"
  elif [ "$4" -gt 0 ] && [ "$4" -le "$2" ]; then note "$1: the column (${4}px) fits the window (${2}px wide)"
  else fail "$1: the column (${4}px) doesn't fit the window (${2}px wide)"; fi
}

# ---- 1. The settings in a pop-up window, wide then narrow
adb shell am force-stop $PKG
adb shell am start -W --windowingMode 5 -n $PKG/.MainActivity >/dev/null; sleep 6
set -- $(task $PKG); id=$1; mode=$2
echo "task: $id $mode" | tee -a $out
if [ "$mode" != freeform ]; then
  warn "Pop-up window: the emulator would not open one (mode ${mode:-none}); check 1 skipped"
else
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am task resize "$id" 0 0 1503 1568; sleep 5; shot settings-popup-wide; fits "Settings in a wide pop-up window"
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am task resize "$id" 0 0 740 1568; sleep 5; shot settings-popup-narrow; fits "Settings in a narrow pop-up window"
fi
adb shell am force-stop $PKG; sleep 2

# ---- 2. The settings keep their place across a turn
adb shell run-as $PKG rm -f files/layout.json
adb shell am start -W --windowingMode 1 -n $PKG/.MainActivity >/dev/null; sleep 12
get view >/dev/null
before=0
for try in 1 2 3; do
  adb shell input swipe 750 1300 750 600 600; sleep 2
  before=$(get scroll); [ "${before:-0}" -gt 0 ] && break
done
shot scrolled
adb shell run-as $PKG rm -f files/layout.json
adb shell settings put system user_rotation 1; sleep 6; shot scrolled-turned
after=$(get scroll)
echo "scroll: $before -> $after" | tee -a $out
if [ "${before:-0}" -le 0 ]; then warn "Settings across a turn: the page never scrolled; skipped"
elif [ -z "$after" ]; then warn "Settings across a turn: no scroll position recorded; skipped"
elif [ "$after" -gt 0 ]; then note "Settings across a turn: kept their place (scrolled $after px)"
else fail "Settings across a turn: back at the top after the turn"; fi
adb shell settings put system user_rotation 0; sleep 3
adb shell am force-stop $PKG
adb shell wm size reset; adb shell wm density reset

adb logcat -d > shots/logcat-windows.txt
if grep -A2 "FATAL EXCEPTION" shots/logcat-windows.txt | grep -q "Process: $PKG"; then fail "Fold Taskbar crashed in the windows test"; grep -A20 "FATAL EXCEPTION" shots/logcat-windows.txt | head -40; fi
grep -q "^::error::" $out && exit 1
exit 0
