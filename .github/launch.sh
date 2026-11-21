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
adb shell svc power stayon true; adb shell settings put system screen_off_timeout 1800000
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
# Lume (Fluido phase 1, 18 Nov 2026): signals injected into the bar's Senso, Lume photographed as it
# offers each moment and recedes, and what Senso decided read back from files/senso.json
fails=0
inject() { adb shell "am broadcast -a com.cerniera.senso.INJECT -p $PKG $1" >/dev/null; }
# Lume is off by default (21 Nov 2026): switch it on as the Fluido switch would
adb shell am start -W -n $PKG/.MainActivity --es lume on >/dev/null; sleep 4
expect() {
  got=$(adb shell run-as $PKG cat files/senso.json | tr -d '\r' | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["offered"] or "none")' 2>/dev/null)
  if [ "$got" = "$2" ]; then echo "::notice::Lume $1: Senso offered $got"
  else echo "::error::Lume $1: expected $2, Senso offered ${got:-nothing readable}"; fails=$((fails+1)); fi
}
size 1503x1568 280
adb shell input keyevent KEYCODE_HOME; sleep 5
inject "--ez clear true --es at 14:00 --es undismiss all"; sleep 2;                                      shot "10a-lume-at-rest"; expect at-rest none
inject "--es rain_in 40 --es rain_chance 80"; sleep 1;                                                    shot "10b-lume-rain-offered"; expect rain rain_coming
sleep 4;                                                                                                  shot "10c-lume-rain-receded"
inject "--ez clear true --es at 14:00 --es event_in 20 --es event_title Dentist --es event_where 'Park Square'"; sleep 1; shot "10d-lume-leaving-soon"; expect leaving leaving_soon
inject "--ez clear true --es at 14:00 --es playing Teardrop --es artist 'Massive Attack' --es headphones true"; sleep 1; shot "10e-lume-listening"; expect listening listening
inject "--es dismiss listening"; sleep 1;                                                                 shot "10f-lume-listening-dismissed"; expect dismissed none
inject "--es undismiss all --ez clear true --es at 14:00 --es rain_in 40 --es rain_chance 80"; sleep 4
size 1568x1503 280
adb shell input keyevent KEYCODE_HOME; sleep 4
inject "--ez clear true --es at 14:00"; sleep 2
inject "--es event_in 15 --es event_title Dentist --es event_where 'Park Square'"; sleep 1;            shot "10g-lume-leaving-landscape"
# What Senso sees, in the settings
size 1503x1568 280
inject "--ez clear true --es at 14:00 --es rain_in 40 --es rain_chance 80 --es event_in 70 --es event_title Dentist --es event_where 'Park Square'"; sleep 2
adb shell am start -W --activity-clear-top -n $PKG/.MainActivity --es open fluido >/dev/null; sleep 6; shot "11a-settings-fluido"
adb shell input swipe 400 1200 400 400 400; sleep 1.5; shot "11b-settings-senso-moments"
adb shell input swipe 400 1200 400 400 400; sleep 1.5; shot "11c-settings-senso-signals"
# Dittico's leaf over Settings, upright then turned: being born, at rest, part-way through the wait
leaf() {
  adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard
  adb shell am start -W -a android.settings.SETTINGS >/dev/null; sleep 4
  adb shell am start -n $PKG/.MainActivity -f 0x10018000 --es open dittico
  sleep 0.45; shot "$1-leaf-born"
  sleep 1.2; shot "$1-leaf-rest"
  sleep 2.2; shot "$1-leaf-waiting"
  sleep 4.5; shot "$1-leaf-gone"
  adb logcat -d -s Dittico:I | tail -5; adb logcat -d | grep -A25 -E "FATAL EXCEPTION|Crash|Kit" | grep -iE "dittico|foldtaskbar|exception|at com.|Caused" | head -30
  adb shell dumpsys activity activities | grep -m2 -E "mResumedActivity|topResumedActivity"
}
size 1503x1568 280; sleep 6; leaf 8-main-portrait
adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1; sleep 3
leaf 9-main-turned
adb shell settings put system user_rotation 0; sleep 2
size 823x1920 320; adb shell input keyevent KEYCODE_HOME; sleep 4; shot "7-cover-bar-hidden"
adb shell wm size reset; adb shell wm density reset
adb logcat -d > logcat.txt
adb shell run-as $PKG cat files/senso.json > shots/senso.json 2>/dev/null
adb shell "run-as $PKG cat shared_prefs/fold_taskbar_log.xml" > shots/event-log.xml 2>/dev/null
# Only Fold Taskbar's own crashes count (the emulator's Bluetooth and Gmail fall over on their own)
if grep -A 2 "FATAL EXCEPTION" logcat.txt | grep -q "Process: $PKG"; then
  grep -A 40 "FATAL EXCEPTION" logcat.txt | grep -E "AndroidRuntime" | head -40 | sed 's/^.*AndroidRuntime: //' | while IFS= read -r line; do echo "::error::$line"; done
  exit 1
fi
[ $fails -gt 0 ] && { echo "::error::$fails Lume scenario(s) failed"; exit 1; }
echo "::notice::Fold Taskbar ran in every shape without a crash"
exit 0
