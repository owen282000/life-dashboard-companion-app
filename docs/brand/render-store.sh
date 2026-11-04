#!/bin/sh
# Puts the store images F-Droid reads from the repository in place. Run from the repository root,
# after scripts/store-screenshots.sh and docs/brand/play-panorama/render.sh have filled store-assets/
# for en, nl and de.
#
#   <locale>/images/phoneScreenshots/1.png to 8.png  store-assets/<lang>-1..8.png, at 1080x2160
#   <locale>/images/featureGraphic.png               store-assets/feature-<lang>.png, 1024x500
#   en-US/images/icon.png                            with "icon" as argument: icon.html, 512x512, RGBA (Play lists a 32-bit PNG)
#
# for the locales en-US, nl-NL and de-DE under fastlane/metadata/android/. The Dutch and German
# folders hold only images: their text falls back to en-US, in F-Droid as on Play until a
# translated listing is added. The icon has no text and is the same for every language.
#
# Only these stay in git. Everything else in store-assets/, for the Play Console, does not. With
# oxipng installed, a PNG that is new or differs from git is recompressed without loss; one that
# matches git is left alone.
set -e
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
META=fastlane/metadata/android
OUT=store-assets

for pair in en:en-US nl:nl-NL de:de-DE; do
  lang=${pair%%:*}; images=$META/${pair#*:}/images
  mkdir -p "$images/phoneScreenshots"
  for n in 1 2 3 4 5 6 7 8; do
    sips -z 2160 1080 "$OUT/$lang-$n.png" --out "$images/phoneScreenshots/$n.png" >/dev/null
  done
  cp "$OUT/feature-$lang.png" "$images/featureGraphic.png"
done
if [ "${1:-}" = icon ]; then
  "$CHROME" --headless=new --screenshot="$META/en-US/images/icon.png" --window-size=512,512 --hide-scrollbars \
    --force-device-scale-factor=1 --virtual-time-budget=3000 --default-background-color=00000000 \
    "file://$PWD/docs/brand/icon.html" 2>/dev/null
fi

if command -v oxipng >/dev/null; then
  for f in "$META"/*/images/phoneScreenshots/*.png "$META"/*/images/featureGraphic.png "$META/en-US/images/icon.png"; do
    if ! git ls-files --error-unmatch "$f" >/dev/null 2>&1 || ! git diff --quiet HEAD -- "$f"; then
      oxipng -q -o 4 --nx --strip safe "$f"
    fi
  done
fi

sips -g pixelWidth -g pixelHeight -g hasAlpha "$META"/*/images/phoneScreenshots/*.png \
  "$META"/*/images/featureGraphic.png "$META/en-US/images/icon.png"
