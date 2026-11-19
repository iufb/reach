#!/usr/bin/env python3
"""Собирает app/src/main/assets/emoji/ordering.txt из метаданных noto-emoji.

Источник: https://github.com/googlefonts/emoji-metadata (Apache 2.0), файл emoji_<версия>_ordering.json.
Версия — та, что поддерживает шрифт из emoji2-bundled (PRODUCT.md §4.5).

    gh api repos/googlefonts/emoji-metadata/contents/emoji_15_1_ordering.json \
        -H 'Accept: application/vnd.github.raw' > /tmp/ordering.json
    python3 scripts/gen_emoji_ordering.py /tmp/ordering.json

Формат: строка на категорию — «ключ<TAB>эмодзи через пробел». Только базовые эмодзи,
без вариантов цвета кожи.
"""
import json
import pathlib
import sys

GROUP_KEYS = {
    "Smileys and emotions": "smileys",
    "People": "people",
    "Animals and nature": "nature",
    "Food and drink": "food",
    "Travel and places": "travel",
    "Activities and events": "activities",
    "Objects": "objects",
    "Symbols": "symbols",
    "Flags": "flags",
}

def main(source: str) -> None:
    groups = json.loads(pathlib.Path(source).read_text(encoding="utf-8"))
    lines = []
    for group in groups:
        key = GROUP_KEYS[group["group"]]
        emojis = ["".join(chr(cp) for cp in item["base"]) for item in group["emoji"]]
        lines.append(key + "\t" + " ".join(emojis))
    out = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/emoji/ordering.txt"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{out}: {sum(len(l.split(' ')) for l in lines)} emoji in {len(lines)} groups")

if __name__ == "__main__":
    main(sys.argv[1])
