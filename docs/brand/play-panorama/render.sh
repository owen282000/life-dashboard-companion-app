#!/bin/sh
# Renders the Google Play phone screenshots in the panorama style from panorama.html with
# headless Chrome, like docs/brand/render-store.sh. Run from the repository root, after
# scripts/store-screenshots.sh has filled docs/brand/store-src/ (and store-src/nl/, store-src/de/
# with LANG_CODE=nl and LANG_CODE=de).
#
#   panorama-en.png        the whole row, 7560x1920
#   en-1.png to en-7.png   one panel each, 1080x1920, RGB without alpha
#   contact-sheet.png      the seven panels side by side, scaled down, for review
#   the same in Dutch and German: panorama-nl.png, nl-1.png, contact-sheet-nl.png, and de in
#     place of nl
#
# docs/brand/render-store.sh runs it and copies the panels into fastlane, the store set.
#
# Only what is uploaded or reviewed goes into git: the panels as fastlane copies them, the
# contact sheets and the captures in store-src/. The panoramas (about 7 MB each) and the panels
# here are made for a look and stay local (.gitignore). With oxipng installed (brew install
# oxipng), every new or changed PNG that git keeps is recompressed without loss and without
# changing its colour type; a file that matches git is left alone, so a rerun changes nothing.
set -e
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
DIR=docs/brand/play-panorama
SRC="file://$PWD/$DIR/panorama.html"

shot() { # out width height url
  "$CHROME" --headless=new --screenshot="$1" --window-size="$2,$3" --hide-scrollbars \
    --force-device-scale-factor=1 --force-color-profile=srgb --virtual-time-budget=4000 \
    --default-background-color=0C1714FF "$4" 2>/dev/null
}

for lang in en nl de; do
  # English is the page without a query, as before; the others add lang=.
  if [ "$lang" = en ]; then q="?"; sheet=contact-sheet.png; else q="?lang=$lang&"; sheet=contact-sheet-$lang.png; fi
  shot "$DIR/panorama-$lang.png" 7560 1920 "$SRC${q%[?&]}"
  for n in 1 2 3 4 5 6 7; do
    shot "$DIR/$lang-$n.png" 1080 1920 "$SRC${q}panel=$n"
  done
  shot "$DIR/$sheet" 2172 578 "file://$PWD/$DIR/contact-sheet.html${q%[?&]}"
done

# Tagged as sRGB, which is what Chrome rendered them in.
for f in "$DIR"/??-[1-7].png "$DIR"/panorama-??.png; do
  sips --embedProfile "/System/Library/ColorSync/Profiles/sRGB Profile.icc" "$f" >/dev/null
done

# Smaller files in git, the same pixels: lossless, RGB stays RGB, and the sRGB profile stays.
# Only files that are new or differ from git: an unchanged file keeps the bytes git already has
# (a recompressed file is a whole new blob in git).
if command -v oxipng >/dev/null; then
  for f in "$DIR"/contact-sheet*.png docs/brand/store-src/*.png docs/brand/store-src/*/*.png; do
    if ! git ls-files --error-unmatch "$f" >/dev/null 2>&1 || ! git diff --quiet HEAD -- "$f"; then
      oxipng -q -o 4 --nx --strip safe "$f"
    fi
  done
else
  echo "oxipng not found: the PNGs stay as Chrome wrote them (brew install oxipng)" >&2
fi

# Play wants RGB without alpha: every one must say "no".
sips -g pixelWidth -g pixelHeight -g hasAlpha "$DIR"/??-[1-7].png "$DIR"/panorama-??.png
