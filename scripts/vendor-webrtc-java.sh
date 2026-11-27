#!/usr/bin/env bash
# Переносит Java-часть WebRTC (org.webrtc.*) из Telegram Android в core:voip (ADR-0015).
# Нативный WebRTC из voip.yml вызывает эти классы по именам, поэтому коммит должен совпадать
# с TELEGRAM_COMMIT в .github/workflows/voip.yml. Запускать после смены TELEGRAM_COMMIT:
#   scripts/vendor-webrtc-java.sh
# Классы Telegram, на которые ссылается их копия WebRTC, подменяются шимами из
# core/voip/src/main/java/io/github/iufb/reach/core/voip/compat.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
commit=$(grep -oE 'TELEGRAM_COMMIT: [0-9a-f]{40}' "$root/.github/workflows/voip.yml" | cut -d' ' -f2)
dest="$root/core/voip/src/main/java/org/webrtc"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

git -C "$work" init -q tg
git -C "$work/tg" remote add origin https://github.com/DrKLO/Telegram.git
git -C "$work/tg" config core.sparseCheckout true
echo /TMessagesProj/src/main/java/org/webrtc/ > "$work/tg/.git/info/sparse-checkout"
git -C "$work/tg" fetch -q --depth 1 --filter=blob:none origin "$commit"
git -C "$work/tg" checkout -q FETCH_HEAD

rm -rf "$dest"
mkdir -p "$(dirname "$dest")"
cp -R "$work/tg/TMessagesProj/src/main/java/org/webrtc" "$dest"

# Рендер Telegram (их анимации, VoIPService) — не нужен; для видео (#411) свой рендер
rm "$dest/TextureViewRenderer.java"

compat=io.github.iufb.reach.core.voip.compat
find "$dest" -name '*.java' -print0 | xargs -0 sed -i.bak -E \
  -e "s/^import org\.telegram\.messenger\.(FileLog|ApplicationLoader|SharedConfig);/import $compat.\1;/" \
  -e "s/^import org\.telegram\.ui\.Stories\.LivePlayer;/import $compat.LivePlayer;/" \
  -e "s/^import org\.telegram\.messenger\.voip\.VideoCapturerDevice;/import io.github.iufb.reach.core.voip.VideoCapturerDevice;/"
find "$dest" -name '*.bak' -delete

if grep -rn 'org\.telegram' "$dest"; then
  echo "Остались ссылки на org.telegram — добавьте шим в compat" >&2
  exit 1
fi
echo "$commit" > "$dest/TELEGRAM_COMMIT"
echo "org.webrtc из Telegram $commit: $(find "$dest" -name '*.java' | wc -l | tr -d ' ') файлов"
