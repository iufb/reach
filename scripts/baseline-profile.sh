#!/usr/bin/env bash
# Снимает baseline profile на подключённом телефоне (ADR-0014). Локально ничего не собираем (ADR-0001):
# APK берутся из последнего успешного прогона workflow «Baseline profile» для ветки.
# Нужно: телефон с API 33+, сборка test DC на нём залогинена (иначе профиль покроет только вход).
#
# Использование: scripts/baseline-profile.sh [ветка]
# Результат: app/src/main/generated/baselineProfiles/{baseline,startup}-prof.txt — закоммитить.
set -euo pipefail

branch="${1:-$(git rev-parse --abbrev-ref HEAD)}"
app_id=io.github.iufb.reach.testdc
test_id=io.github.iufb.reach.baselineprofile
out=app/src/main/generated/baselineProfiles

run=$(gh run list --workflow "Baseline profile" --branch "$branch" --status success --limit 1 --json databaseId --jq '.[0].databaseId // empty')
[ -n "$run" ] || { echo "Ошибка: нет успешного прогона «Baseline profile» для '$branch'" >&2; exit 1; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
gh run download "$run" --name reach-baseline-profile-apks --dir "$tmp"
app_apk=$(find "$tmp" -path '*app*' -name '*.apk' | head -1)
test_apk=$(find "$tmp" -path '*baselineprofile*' -name '*.apk' | head -1)
[ -n "$app_apk" ] && [ -n "$test_apk" ] || { echo "Ошибка: в артефакте нет APK" >&2; find "$tmp" -name '*.apk' >&2; exit 1; }

adb install -r "$app_apk"
adb install -r -t "$test_apk"

device_dir=/sdcard/Android/media/$test_id/additional_test_output
adb shell rm -rf "$device_dir"
adb shell am instrument -w \
  -e class io.github.iufb.reach.baselineprofile.BaselineProfileGenerator \
  -e targetAppId "$app_id" \
  -e androidx.benchmark.enabledRules BaselineProfile \
  -e no-isolated-storage true \
  "$test_id/androidx.test.runner.AndroidJUnitRunner" | tee "$tmp/instrument.txt"
grep -q "OK (1 test)" "$tmp/instrument.txt" || { echo "Ошибка: генератор упал" >&2; exit 1; }

adb pull "$device_dir" "$tmp/output" >/dev/null
baseline=$(find "$tmp/output" -name '*-baseline-prof.txt' | head -1)
startup=$(find "$tmp/output" -name '*-startup-prof.txt' | head -1)
[ -n "$baseline" ] || { echo "Ошибка: профиль не найден" >&2; ls -R "$tmp/output" >&2; exit 1; }

mkdir -p "$out"
cp "$baseline" "$out/baseline-prof.txt"
[ -z "$startup" ] || cp "$startup" "$out/startup-prof.txt"
echo "Готово: $(wc -l < "$out/baseline-prof.txt") правил в $out/baseline-prof.txt"
