# tools/fonts — SF Pro and Apple Color Emoji for Reach

Builds two fonts from the owner's Mac for personal use in Reach (ADR-0011):

- `SF-Pro.ttf` — a copy of `/System/Library/Fonts/SFNS.ttf` (variable font, `wght` and `opsz` axes);
- `AppleColorEmoji-<strike>.ttf` — `Apple Color Emoji.ttc` converted from Apple's `sbix`
  (Android cannot render it) to `CBDT/CBLC` with one bitmap strike and a `GSUB` table for
  ZWJ sequences, flags and skin tones.

**The fonts are Apple's. Never commit, push, upload, attach or share them** — not in git,
not in CI, not in issues or PRs. Only this build code lives in the repo. Output goes to
`fonts-local/` in the repo root, which is in `.gitignore`.

## Build

Needs macOS and Docker (OrbStack) running. From the repo root:

```sh
tools/fonts/build.sh            # strike 127 (default)
tools/fonts/build.sh 96 127     # one emoji font per strike, to compare
```

Result in `fonts-local/out/`: `SF-Pro.ttf`, `AppleColorEmoji-127.ttf` (and `-96` if asked).

Default strike 127 (113 MB) was picked on the phone (#254): at 17 sp text 96 and 127 look the
same, but large single emoji (96 sp without animation) and sticker placeholders are visibly
softer with 96 (66 MB).

Apple ships sbix strikes 20, 26, 32, 40, 48, 52, 64, 96 and 160. A CBDT strike can be at
most 127 ppem (its metrics are int8), so 160 can't be kept as is: for a ppem Apple doesn't
ship, `derive_strike.py` scales the 160 strike down to it first (127 is the sharpest
possible).
The script ends with `verify.py`, which must print `OK`, and lists the file sizes.
After a macOS update changes the emoji, run it again.

`--sbix-sample` also writes `sbix-sample.ttf` (the raw `sbix` face from the `.ttc`) as a
font the app must reject or fall back from.

## How it works

- `build.sh` copies the system fonts to `fonts-local/in/` (`/System` is not mounted into
  the container), builds the image and runs the converter with `--network none`.
- `Dockerfile` installs [samuelngs/apple-emoji-ttf](https://github.com/samuelngs/apple-emoji-ttf)
  pinned to a reviewed commit (`CONVERTER_SHA`). Network is used only by `docker build`.
  When bumping the SHA, read the converter's diff first: it is third-party code.
- `emoji.yaml` is the converter recipe (based on its `configs/linux.yaml`, plus `GSUB`);
  `entrypoint.sh` puts the strike into it; `derive_strike.py` makes non-native strikes.
- `android_fixups.py` prepares the font to be the first font of Android's fallback chain
  (Reach puts it before the text font, otherwise the text font's ❤, ☀, ↔, digits … win
  over ❤️, ☀️, ↔️, 1️⃣ and sequences like ❤️‍🔥 fall apart): text-like symbols (digits,
  #, *, ©, ®, ™, arrows …) and space leave the regular `cmap` and stay emoji only with
  U+FE0F (`cmap` format 14); every other emoji-style sequence is a default UVS entry;
  vertical metrics become Inter's, since the first font sets the line's ascent/descent.
  Sequences with a text-like base and no U+FE0F (e.g. `0031 20E3`) are no longer emoji.
- `verify.py` checks tables (`CBDT`, `CBLC`, `cmap`, `GSUB`, no `sbix`), shapes sample
  sequences, runs the converter's own GSUB check over every known sequence, and prints
  the SF Pro `fvar` axes.

## Add the fonts to the app

On the phone: Settings → Font, pick the file for text (`SF-Pro.ttf`) or emoji
(`AppleColorEmoji-127.ttf`). Or send the files to Saved Messages once and use
"Install as font" on the document. The choice applies after restarting the app.
