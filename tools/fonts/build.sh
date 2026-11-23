#!/usr/bin/env bash
# Builds SF-Pro.ttf and AppleColorEmoji-<strike>.ttf into fonts-local/out/
# from this Mac's system fonts. Run on macOS with Docker (OrbStack) running.
#
#   tools/fonts/build.sh                 # default strike (127, chosen on the phone in #254)
#   tools/fonts/build.sh 96 127          # one font per strike (CBDT max is 127)
#   tools/fonts/build.sh --sbix-sample   # also write the raw sbix font (negative test case)
#
# The fonts are Apple's: never commit, upload or share them (ADR-0011).
set -euo pipefail

DEFAULT_STRIKE=127
IMAGE=reach-fonts

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
in="$root/fonts-local/in"
out="$root/fonts-local/out"

sbix_sample=0
strikes=()
for arg in "$@"; do
  case "$arg" in
    --sbix-sample) sbix_sample=1 ;;
    *[!0-9]*|'') echo "unknown argument: $arg" >&2; exit 2 ;;
    *) strikes+=("$arg") ;;
  esac
done
[ ${#strikes[@]} -eq 0 ] && strikes=("$DEFAULT_STRIKE")

mkdir -p "$in" "$out"
# /System is not mounted into the container; copy the inputs next to it.
cp "/System/Library/Fonts/Apple Color Emoji.ttc" "$in/"
cp "/System/Library/Fonts/SFNS.ttf" "$in/"
cp "$in/SFNS.ttf" "$out/SF-Pro.ttf"

docker build -t "$IMAGE" "$here"

run() {
  docker run --rm --network none \
    -v "$in:/in:ro" -v "$out:/out" \
    "$IMAGE" "$@"
}

built=()
for strike in "${strikes[@]}"; do
  echo "== Apple Color Emoji, strike $strike"
  run "$strike"
  built+=("AppleColorEmoji-$strike.ttf")
done
if [ "$sbix_sample" -eq 1 ]; then run sbix-sample; fi

# Older AppleColorEmoji-*.ttf in out/ are not checked: they may predate android_fixups (#284)
for path in "$out"/AppleColorEmoji-*.ttf; do
  [ -e "$path" ] || continue
  name="$(basename "$path")"
  case " ${built[*]} " in
    *" $name "*) ;;
    *) echo "warning: $name was not built in this run and is not checked; rebuild or delete it" >&2 ;;
  esac
done

echo "== Verify"
if ! run verify "${built[@]}"; then
  echo "verify failed: the fonts in $out are not ready to use" >&2
  exit 1
fi

echo "== Output"
ls -lh "$out"
