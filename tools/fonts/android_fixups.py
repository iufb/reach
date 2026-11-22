#!/usr/bin/env python3
"""Makes the converted emoji font work as the FIRST font of an Android fallback chain.

Why first: Android's font fallback (minikin) gives a character to the earliest font in a
custom fallback chain that covers it. With the text font first (Inter, SF Pro), every
emoji whose base the text font also has (❤️, ☀️, ↔️, 1️⃣, ©️ …) was drawn with the text
font's glyph, and ZWJ sequences containing them (❤️‍🔥, 🙂‍↔️) fell apart. So Reach puts
the emoji font first, and this script makes that safe:

1. Text-like symbols (digits, #, *, ©, ®, ™, arrows, ▶ …, plus space) leave the regular
   cmap, so plain text keeps the text font; those with an emoji form stay reachable only
   as "base + U+FE0F" through cmap format 14 (non-default UVS).
2. Every other emoji-style variation sequence (❤️, ☀️ …) is a default UVS entry, so
   minikin also picks this font for "base + U+FE0F".
3. Vertical metrics (hhea, OS/2, CBLC line metrics) become Inter's, because the first
   font of a Typeface defines the line's ascent and descent. Glyph bitmaps keep their
   own per-glyph metrics, so emoji don't move.

Usage: android_fixups.py <font.ttf>   (rewrites the file in place)
"""

from __future__ import annotations

import sys
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.ttLib.tables._c_m_a_p import CmapSubtable

SEQUENCES = Path("/opt/apple-emoji-ttf/sequences/emoji-variation-sequences.txt")
VS16 = 0xFE0F

# Symbols that text fonts have and plain text uses: emoji only with U+FE0F.
TEXT_LIKE = {
    0x0023, 0x002A, *range(0x0030, 0x003A), 0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139,
    *range(0x2194, 0x219A), 0x21A9, 0x21AA, 0x23CF, 0x24C2, 0x25AA, 0x25AB, 0x25B6, 0x25C0,
    0x25FB, 0x25FC, 0x2611, 0x2714, 0x2716, 0x2733, 0x2734, 0x2747, 0x27A1, 0x2934, 0x2935,
    0x2B05, 0x2B06, 0x2B07, 0x3030, 0x303D, 0x3297, 0x3299,
}
# Not emoji at all.
DROP = {0x0020, 0x00A0}

# Inter's metrics (units per em 2048): hhea and typo ascender/descender.
ASCENT_EM = 1984 / 2048
DESCENT_EM = 494 / 2048


def emoji_style_bases(path: Path) -> set[int]:
    bases = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        data = line.split("#", 1)[0].strip()
        if not data:
            continue
        seq, style = (part.strip() for part in data.split(";")[:2])
        codes = [int(c, 16) for c in seq.split()]
        if style == "emoji style" and len(codes) == 2 and codes[1] == VS16:
            bases.add(codes[0])
    return bases


def fix_cmap(font: TTFont) -> tuple[int, int]:
    cmap = font["cmap"]
    best = dict(font.getBestCmap())
    vs_bases = emoji_style_bases(SEQUENCES)
    uvs_only = {cp: best[cp] for cp in TEXT_LIKE if cp in best}
    removed = TEXT_LIKE | DROP

    for table in cmap.tables:
        if table.format in (4, 12):
            table.cmap = {cp: name for cp, name in table.cmap.items() if cp not in removed}
    cmap.tables = [t for t in cmap.tables if t.format != 14]

    default = sorted(cp for cp in vs_bases if cp in best and cp not in removed)
    uvs = CmapSubtable.newSubtable(14)
    uvs.platformID, uvs.platEncID, uvs.language = 0, 5, 0
    uvs.cmap = {}
    uvs.uvsDict = {VS16: [(cp, None) for cp in default] + sorted(uvs_only.items())}
    cmap.tables.append(uvs)
    cmap.tables.sort(key=lambda t: (t.platformID, t.platEncID))
    return len(default), len(uvs_only)


def fix_metrics(font: TTFont) -> None:
    upem = font["head"].unitsPerEm
    ascent, descent = round(ASCENT_EM * upem), round(DESCENT_EM * upem)
    hhea = font["hhea"]
    hhea.ascent, hhea.descent, hhea.lineGap = ascent, -descent, 0
    os2 = font["OS/2"]
    os2.sTypoAscender, os2.sTypoDescender, os2.sTypoLineGap = ascent, -descent, 0
    os2.usWinAscent, os2.usWinDescent = ascent, descent
    for strike in font["CBLC"].strikes:
        size = strike.bitmapSizeTable
        for line in (size.hori, size.vert):
            line.ascender = min(127, round(ASCENT_EM * size.ppemY))
            line.descender = -round(DESCENT_EM * size.ppemY)


def main() -> int:
    path = Path(sys.argv[1])
    font = TTFont(path)
    default, non_default = fix_cmap(font)
    fix_metrics(font)
    font.save(path)
    print(f"  cmap format 14: {default} default + {non_default} FE0F-only sequences; metrics: Inter")
    return 0


if __name__ == "__main__":
    sys.exit(main())
