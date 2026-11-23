#!/usr/bin/env bash
# Smoke-тест запуска на эмуляторе (workflow api31-smoke): ставит dctest-APK, открывает
# MainActivity и падает, если приложение упало. Только для CI — локально не собираем (ADR-0001).
set -euo pipefail

pkg=io.github.iufb.reach.testdc
apk=$(ls app/build/outputs/apk/dctest/*.apk | head -n 1)
mkdir -p smoke

adb wait-for-device
adb shell getprop ro.build.version.sdk | tee smoke/sdk.txt
adb install -r "$apk"
adb logcat -c
adb shell am start -W -n "$pkg/io.github.iufb.reach.MainActivity"
sleep 30

adb exec-out screencap -p > smoke/start.png || true
adb logcat -d > smoke/logcat.txt
pid=$(adb shell pidof "$pkg" || true)

if grep -q "FATAL EXCEPTION" smoke/logcat.txt; then
  echo "::error::FATAL EXCEPTION в logcat"
  grep -A 40 "FATAL EXCEPTION" smoke/logcat.txt
  exit 1
fi
if [ -z "$pid" ]; then
  echo "::error::процесс $pkg не работает после запуска"
  tail -n 200 smoke/logcat.txt
  exit 1
fi
echo "OK: $pkg работает (pid $pid) на API $(cat smoke/sdk.txt)"
