#!/usr/bin/env python3
"""Adds a non-native sbix strike so the converter can use it as its one strike.

Apple's sbix strikes are 20, 26, 32, 40, 48, 52, 64, 96 and 160 ppem. CBDT
metrics are int8, so a CBDT strike can be at most 127 ppem (the converter
rejects more). For a ppem Apple doesn't ship (e.g. 127), this script scales the
largest sbix strike down to it and writes a copy of face 0 with that strike
added. For a native ppem it writes nothing: the .ttc is used as is.

usage: derive_strike.py <ttc> <ppem> <out.ttf>
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, "/opt/apple-emoji-ttf")

from fontTools.ttLib import TTFont  # noqa: E402
from fontTools.ttLib.tables.sbixGlyph import Glyph  # noqa: E402
from fontTools.ttLib.tables.sbixStrike import Strike  # noqa: E402

from bitmap.png import resize_png  # noqa: E402
from source.sbix import collect_sbix_glyph_images  # noqa: E402

CBDT_MAX_PPEM = 127


def main() -> int:
    ttc, ppem, out = Path(sys.argv[1]), int(sys.argv[2]), Path(sys.argv[3])
    if not 1 <= ppem <= CBDT_MAX_PPEM:
        print(f"strike {ppem}: CBDT allows 1..{CBDT_MAX_PPEM} ppem", file=sys.stderr)
        return 2

    font = TTFont(ttc, fontNumber=0)
    strikes = font["sbix"].strikes
    if ppem in strikes:
        return 0

    source = max(strikes)
    images, meta = collect_sbix_glyph_images(font, ppem=source)
    strike = Strike(ppem=ppem, resolution=meta.resolution)
    for image in images:
        strike.glyphs[image.name] = Glyph(
            glyphName=image.name,
            graphicType="png ",
            imageData=resize_png(image.png, ppem),
            originOffsetX=round(image.origin_x * ppem / source),
            originOffsetY=round(image.origin_y * ppem / source),
        )
    strikes[ppem] = strike
    font.save(out)
    print(f"Derived sbix strike {ppem} from {source} ({len(images)} glyphs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
