#!/bin/sh
# Renders the Google Play store graphics from docs/brand/ with headless Chrome. Run from the
# repository root.
#
#   <locale>/images/phoneScreenshots/1.png to 7.png  play-panorama/<lang>-1..7.png, 1080x1920, RGB (no alpha)
#   <locale>/images/featureGraphic.png               feature-graphic.html, 1024x500, RGB (no alpha)
#   en-US/images/icon.png                            icon.html, 512x512, RGBA (Play lists a 32-bit PNG)
#
# for the locales en-US, nl-NL and de-DE under fastlane/metadata/android/. The Dutch and German
# folders hold only images: their text falls back to en-US, in F-Droid as on Play until a
# translated listing is added. The icon has no text and is the same for every language.
#
# The screenshots use the captures in docs/brand/store-src/. With oxipng installed, a PNG that
# is new or differs from git is recompressed without loss; one that matches git is left alone.
set -e
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
META=fastlane/metadata/android

shot() { # out width height url [extra flag]
  "$CHROME" --headless=new --screenshot="$1" --window-size="$2,$3" --hide-scrollbars \
    --force-device-scale-factor=1 --virtual-time-budget=3000 $5 "$4" 2>/dev/null
}

# The phone screenshots are the panorama (docs/brand/play-panorama, rendered by its render.sh);
# store-shot.html keeps the flat set of 17 October for reference.
sh docs/brand/play-panorama/render.sh
for pair in en:en-US nl:nl-NL de:de-DE; do
  lang=${pair%%:*}; images=$META/${pair#*:}/images
  mkdir -p "$images/phoneScreenshots"
  for n in 1 2 3 4 5 6 7; do
    cp "docs/brand/play-panorama/$lang-$n.png" "$images/phoneScreenshots/$n.png"
  done
  if [ "$lang" = en ]; then q=""; else q="?lang=$lang"; fi
  shot "$images/featureGraphic.png" 1024 500 "file://$PWD/docs/brand/feature-graphic.html$q"
done
shot "$META/en-US/images/icon.png" 512 512 "file://$PWD/docs/brand/icon.html" --default-background-color=00000000

if command -v oxipng >/dev/null; then
  for f in "$META"/*/images/phoneScreenshots/*.png "$META"/*/images/featureGraphic.png "$META/en-US/images/icon.png"; do
    if ! git ls-files --error-unmatch "$f" >/dev/null 2>&1 || ! git diff --quiet HEAD -- "$f"; then
      oxipng -q -o 4 --nx --strip safe "$f"
    fi
  done
fi

sips -g pixelWidth -g pixelHeight -g hasAlpha "$META"/*/images/phoneScreenshots/*.png \
  "$META"/*/images/featureGraphic.png "$META/en-US/images/icon.png"
