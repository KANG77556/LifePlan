#!/usr/bin/env bash
# A new API 34 Android emulator exercises the published v0.4.3 updater ->
# published v0.4.4. The target APK must be installed by Android's UI.
set -euo pipefail

OLD_APK="/tmp/LifePlan-v0.4.3.apk"
OLD_SHA="6f8699869a8ff12fdee199376e697c562cdc67c726edbca9de7f58293341e643"
OLD_VERSION="0.4.3"
NEW_VERSION="0.4.4"
DURATION_SECONDS=210
mkdir -p artifacts
(
  adb logcat -c || true
  curl -fL --retry 3 --connect-timeout 15 --max-time 90 \
    "https://github.com/KANG77556/LifePlan/releases/download/v0.4.3/LifePlan-release.apk" \
    --output "$OLD_APK"
  echo "$OLD_SHA  $OLD_APK" | sha256sum -c -
  adb wait-for-device
  adb shell getprop sys.boot_completed
  adb install -r "$OLD_APK"
  BEFORE="$(adb shell dumpsys package kr.lifeplan.app)"
  echo "$BEFORE" | grep -q "versionName=$OLD_VERSION"
  echo "$BEFORE" | grep -m1 -E "userId="
  adb shell appops set kr.lifeplan.app REQUEST_INSTALL_PACKAGES allow
  echo "Original v$OLD_VERSION installed; requesting automatic upgrade."
  adb shell am force-stop kr.lifeplan.app || true
  adb shell am start -n kr.lifeplan.app/.MainActivity

  seen=0
  started="$SECONDS"
  while (( SECONDS - started < DURATION_SECONDS )); do
    top="$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity=|mResumedActivity=' || true)"
    focus="$(adb shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus=' || true)"
    printf 'ELAPSED=%s TOP=%s FOCUS=%s\n' "$((SECONDS-started))" "$top" "$focus"
    if printf '%s\n%s\n' "$top" "$focus" | grep -Eiq 'packageinstaller|package\.installer|permissioncontroller.*install'; then
      echo 'AUTO_INSTALLER_UI=DETECTED'
      seen=1
      break
    fi
    sleep 4
  done
  if [[ "$seen" != 1 ]]; then
    echo 'AUTO_INSTALLER_UI=NOT_DETECTED'
    exit 2
  fi

  # The system installer still requires confirmation; ADB acts as the user.
  for try in $(seq 1 6); do
    adb shell uiautomator dump /sdcard/lifeplan-installer.xml >/dev/null
    adb pull /sdcard/lifeplan-installer.xml artifacts/installer-ui.xml >/dev/null
    click="$(python3 - <<'PY'
from xml.etree import ElementTree as ET
import re
root=ET.parse('artifacts/installer-ui.xml').getroot()
items=[]
for e in root.iter('node'):
    a=e.attrib
    title=(a.get('text','')+' '+a.get('content-desc','')).strip().lower()
    rid=a.get('resource-id','').lower()
    if any(word in title for word in ('update','install','업데이트','설치')) or (rid.endswith(':id/button1') and a.get('clickable')=='true'):
        m=re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',a.get('bounds',''))
        if m and a.get('enabled')=='true' and a.get('clickable')=='true':
            x1,y1,x2,y2=map(int,m.groups())
            items.append((0 if title in ('update','install','업데이트','설치') else 1,
                          (x1+x2)//2,(y1+y2)//2,title))
if items:
    _,x,y,title=min(items)
    print(f'{x} {y}')
PY
)"
    if [[ -n "$click" ]]; then
      echo "TAPPING_SYSTEM_INSTALL_BUTTON=$click"
      adb shell input tap $click
      break
    fi
    sleep 3
  done

  installed=0
  for t in $(seq 1 30); do
    package="$(adb shell dumpsys package kr.lifeplan.app)"
    if echo "$package" | grep -q "versionName=$NEW_VERSION"; then
      installed=1
      break
    fi
    sleep 3
  done
  if [[ "$installed" != 1 ]]; then
    echo 'UPGRADE_RESULT=FAILED'
    exit 3
  fi
  echo "UPGRADE_RESULT=SUCCESS $OLD_VERSION->$NEW_VERSION"
  echo 'Proves: auto-release check, APK download, verification, installer launch and system-approved install.'
) || {
  code=$?
  echo "TEST_FAILED_EXIT=$code"
  adb shell dumpsys activity activities > artifacts/activity-dump.txt 2>&1 || true
  adb shell dumpsys window > artifacts/window-dump.txt 2>&1 || true
  adb logcat -d -v threadtime -t 5000 > artifacts/logcat.txt 2>&1 || true
  adb exec-out screencap -p > artifacts/screen.png 2>/dev/null || true
  exit "$code"
}
