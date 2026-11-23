#!/usr/bin/env python3
"""Checks the built fonts. Runs inside the reach-fonts container.

Emoji (the AppleColorEmoji-*.ttf given as arguments, or every one in --out):
  - has CBDT, CBLC, cmap, GSUB; has no sbix;
  - android_fixups.py ran: no digits/space in the regular cmap, ❤️ and 1️⃣ in cmap format 14;
  - sample ZWJ sequences, flags and skin tones shape to one glyph;
  - every sequence the converter knows shapes as in its verify_gsub.py.
SF Pro: prints the fvar axes and their ranges.
"""

from __future__ import annotations

import argparse
import dataclasses
import sys
from pathlib import Path

CONVERTER = Path("/opt/apple-emoji-ttf")
sys.path.insert(0, str(CONVERTER))

from fontTools.ttLib import TTFont  # noqa: E402

REQUIRED_TABLES = ("CBDT", "CBLC", "cmap", "GSUB")
FORBIDDEN_TABLES = ("sbix",)

SAMPLES = {
    "family (ZWJ)": "\U0001F468‍\U0001F469‍\U0001F467",
    "rainbow flag (ZWJ)": "\U0001F3F3️‍\U0001F308",
    "flag RU": "\U0001F1F7\U0001F1FA",
    "flag US": "\U0001F1FA\U0001F1F8",
    "thumbs up, medium skin": "\U0001F44D\U0001F3FD",
    "woman technologist, dark skin (ZWJ)": "\U0001F469\U0001F3FF‍\U0001F4BB",
    "heart (VS16)": "❤️",
}


def stale_build(font: TTFont) -> bool:
    """Built before android_fixups.py (PR #281): no cmap format 14, or digits/space in the regular cmap."""
    if "cmap" not in font:
        return False
    has_uvs = any(t.format == 14 for t in font["cmap"].tables)
    best = font.getBestCmap()
    return not has_uvs or any(cp in best for cp in (0x0020, 0x0031))


def strike_of(path: Path) -> str:
    """`AppleColorEmoji-96.ttf` → `96`."""
    return path.stem.rsplit("-", 1)[-1]


def check_emoji(path: Path, source: Path) -> list[str]:
    import uharfbuzz as hb

    errors: list[str] = []
    font = TTFont(path)
    if stale_build(font):
        # Дальнейшие проверки на таком файле падают с traceback (#284): одна понятная ошибка вместо них
        return [f"{path.name}: собран без android_fixups — пересобери tools/fonts/build.sh {strike_of(path)}"]
    for tag in REQUIRED_TABLES:
        if tag not in font:
            errors.append(f"missing table {tag}")
    for tag in FORBIDDEN_TABLES:
        if tag in font:
            errors.append(f"unexpected table {tag}")
    uvs = next((t for t in font["cmap"].tables if t.format == 14), None) if "cmap" in font else None
    vs16 = {base for base, _ in uvs.uvsDict.get(0xFE0F, [])} if uvs else set()
    print(f"  cmap format 14: {len(vs16)} sequences with U+FE0F")
    for base in (0x2764, 0x0031):
        if base not in vs16:
            errors.append(f"cmap format 14 has no U+{base:04X} U+FE0F")
    best = font.getBestCmap() if "cmap" in font else {}
    for cp in (0x0020, 0x0031):
        if cp in best:
            errors.append(f"U+{cp:04X} is in the regular cmap: it would win over the text font")
    strikes = [s.bitmapSizeTable.ppemY for s in font["CBLC"].strikes] if "CBLC" in font else []
    print(f"  strikes: {strikes}, glyphs: {len(font.getGlyphOrder())}")

    order = font.getGlyphOrder()
    hb_font = hb.Font(hb.Face(hb.Blob(path.read_bytes())))
    for label, text in SAMPLES.items():
        buf = hb.Buffer()
        buf.add_str(text)
        buf.guess_segment_properties()
        hb.shape(hb_font, buf)
        gids = [info.codepoint for info in buf.glyph_infos]
        names = [order[g] for g in gids]
        ok = len(gids) == 1 and gids[0] != 0
        print(f"  {'ok ' if ok else 'BAD'} {label}: {names}")
        if not ok:
            errors.append(f"{label} shapes to {names}")

    errors += check_all_sequences(path, source)
    return errors


def check_all_sequences(path: Path, source: Path) -> list[str]:
    """The converter's own GSUB check (verify_gsub.py) over every known sequence."""
    from emoji_data import default_project_sequence_files, default_sequence_files
    from shaping.oracle import ShapingOracle
    from shaping.sequences import load_sequence_inventory
    from verify_gsub import verify_records

    records = load_sequence_inventory(
        list(default_sequence_files()), list(default_project_sequence_files())
    )
    # android_fixups.py moved text-like bases (digits, ©, arrows …) to cmap format 14 only.
    # Sequences with such a base and no U+FE0F after it are intentionally not emoji; for the
    # rest the converter's check looks up component glyph names in the regular cmap.
    gen = ShapingOracle(path)
    uvs = next(t for t in gen.ttfont["cmap"].tables if t.format == 14)
    fe0f_only = {base: glyph for base, glyph in uvs.uvsDict.get(0xFE0F, []) if glyph}

    def keeps_fe0f(seq: tuple[int, ...]) -> bool:
        return all(nxt == 0xFE0F for cp, nxt in zip(seq, seq[1:] + (0,)) if cp in fe0f_only)

    kept = []
    for record in records:
        raws = tuple(raw for raw in record.raw_sequences if keeps_fe0f(raw))
        if raws:
            kept.append(dataclasses.replace(record, raw_sequences=raws))
    print(f"  sequences: skipped {len(records) - len(kept)} records with a text-like base and no U+FE0F")
    records = kept
    for table in gen.ttfont["cmap"].tables:
        if table.format in (4, 12):
            table.cmap.update(fe0f_only)
    src = ShapingOracle(source, font_number=0)
    try:
        mismatches = verify_records(src, gen, records)
    finally:
        src.close()
        gen.close()
    print(f"  sequences: {len(records)} records, {len(mismatches)} mismatches")
    for item in mismatches[:10]:
        print(f"    {item['raw_sequence']}: {item['reason']}")
    return [f"{len(mismatches)} sequence mismatches"] if mismatches else []


def print_axes(path: Path) -> None:
    font = TTFont(path)
    if "fvar" not in font:
        print("  no fvar (static font)")
        return
    for axis in font["fvar"].axes:
        print(
            f"  axis {axis.axisTag}: min {axis.minValue:g}, "
            f"default {axis.defaultValue:g}, max {axis.maxValue:g}"
        )
    print(f"  named instances: {len(font['fvar'].instances)}")


def size_mb(path: Path) -> str:
    return f"{path.stat().st_size / 1_000_000:.1f} MB"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path("/out"))
    parser.add_argument("--source", type=Path, default=Path("/in/Apple Color Emoji.ttc"))
    parser.add_argument(
        "fonts", nargs="*",
        help="emoji font file names in --out to check (build.sh passes the ones it built); default: all",
    )
    args = parser.parse_args()

    failed = False
    if args.fonts:
        emoji = [args.out / name for name in args.fonts]
        missing = [p for p in emoji if not p.exists()]
        for path in missing:
            print(f"no {path.name} in {args.out}")
        failed |= bool(missing)
        emoji = [p for p in emoji if p.exists()]
    else:
        emoji = sorted(args.out.glob("AppleColorEmoji-*.ttf"))
    if not emoji:
        print("no AppleColorEmoji-*.ttf in", args.out)
        failed = True
    for path in emoji:
        print(f"{path.name} ({size_mb(path)})")
        errors = check_emoji(path, args.source)
        for error in errors:
            print(f"  FAIL: {error}")
        failed |= bool(errors)

    sf = args.out / "SF-Pro.ttf"
    if sf.exists():
        print(f"{sf.name} ({size_mb(sf)})")
        print_axes(sf)
    else:
        print("no SF-Pro.ttf in", args.out)
        failed = True

    print("FAILED" if failed else "OK")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
