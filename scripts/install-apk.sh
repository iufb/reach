#!/usr/bin/env bash
# Скачивает debug APK из последнего успешного прогона CI для текущей ветки и ставит на устройство.
# Локально ничего не собираем (docs/adr/0001-ci-only-builds.md).
# Требуется: gh (авторизован), adb, подключённое устройство или эмулятор.
#
# Использование: scripts/install-apk.sh [--testdc] [ветка]
#   --testdc  поставить сборку на тестовых серверах Telegram («Reach Test», рядом с основной)
set -euo pipefail

artifact=reach-debug
if [ "${1:-}" = "--testdc" ]; then
  artifact=reach-testdc
  shift
fi

branch="${1:-$(git rev-parse --abbrev-ref HEAD)}"

command -v gh >/dev/null || { echo "Ошибка: нужен gh (https://cli.github.com)" >&2; exit 1; }
command -v adb >/dev/null || { echo "Ошибка: нужен adb (Android SDK platform-tools)" >&2; exit 1; }

devices=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
[ "$devices" -ge 1 ] || { echo "Ошибка: нет подключённого устройства (adb devices)" >&2; exit 1; }

run=$(gh run list --workflow Android --branch "$branch" --status success --limit 1 \
  --json databaseId,headSha,createdAt --jq '.[0] // empty | "\(.databaseId) \(.headSha[0:7]) \(.createdAt)"')
[ -n "$run" ] || { echo "Ошибка: нет успешного прогона CI для ветки '$branch'" >&2; exit 1; }
read -r run_id sha created <<<"$run"

head=$(git rev-parse --short=7 "origin/$branch" 2>/dev/null || true)
[ -z "$head" ] || [ "$head" = "$sha" ] || echo "Внимание: последний успешный прогон собран из $sha, а origin/$branch — $head"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "Скачиваю $artifact: ветка $branch, коммит $sha ($created), run $run_id"
gh run download "$run_id" --name "$artifact" --dir "$tmp"

apk=$(find "$tmp" -name '*.apk' | head -1)
[ -n "$apk" ] || { echo "Ошибка: в артефакте нет APK" >&2; exit 1; }

adb install -r "$apk"
echo "Готово."
