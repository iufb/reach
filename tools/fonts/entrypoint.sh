#!/bin/sh
# Container entrypoint. Inputs are read from /in (read-only), outputs go to /out.
#   <strike>      build /out/AppleColorEmoji-<strike>.ttf with one bitmap strike
#   verify ...    run verify.py (see verify.py --help)
#   sbix-sample   write /out/sbix-sample.ttf: face 0 of the .ttc, sbix only (a
#                 font Android cannot render; used as a negative test case)
set -eu

TTC="/in/Apple Color Emoji.ttc"

case "${1:-}" in
  verify)
    shift
    exec python -I /opt/reach/verify.py "$@"
    ;;
  sbix-sample)
    exec python -I -c '
import sys
from fontTools.ttLib import TTCollection
TTCollection(sys.argv[1]).fonts[0].save(sys.argv[2])
print("Wrote", sys.argv[2])
' "$TTC" /out/sbix-sample.ttf
    ;;
  ''|*[!0-9]*)
    echo "usage: <strike ppem> | verify ... | sbix-sample" >&2
    exit 2
    ;;
  *)
    strike="$1"
    recipe="/tmp/emoji-$strike.yaml"
    src="/tmp/source-$strike.ttf"
    sed "s/__STRIKE__/$strike/" /opt/reach/emoji.yaml > "$recipe"
    # Non-native ppem (e.g. 127): scale Apple's largest strike down first.
    python -I /opt/reach/derive_strike.py "$TTC" "$strike" "$src"
    [ -f "$src" ] || src="$TTC"
    cd /opt/apple-emoji-ttf
    python cli.py -c "$recipe" -i "$src" -o "/out/AppleColorEmoji-$strike.ttf"
    # Reach puts the emoji font first in the fallback chain: cmap and metrics for that
    exec python -I /opt/reach/android_fixups.py "/out/AppleColorEmoji-$strike.ttf"
    ;;
esac
