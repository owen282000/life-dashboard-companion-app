#!/bin/sh
# Renders the Google Play phone screenshots in the panorama style from panorama.html with
# headless Chrome, like docs/brand/render-store.sh. Run from the repository root.
#
#   panorama-en.png        the whole row, 7560x1920
#   en-1.png to en-7.png   one panel each, 1080x1920, RGB without alpha
#   contact-sheet.png      the seven panels side by side, scaled down, for review
#
# docs/brand/render-store.sh runs it and copies the panels into fastlane, the store set.
set -e
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
DIR=docs/brand/play-panorama
SRC="file://$PWD/$DIR/panorama.html"

shot() { # out width height url
  "$CHROME" --headless=new --screenshot="$1" --window-size="$2,$3" --hide-scrollbars \
    --force-device-scale-factor=1 --force-color-profile=srgb --virtual-time-budget=4000 \
    --default-background-color=0C1714FF "$4" 2>/dev/null
}

shot "$DIR/panorama-en.png" 7560 1920 "$SRC"
for n in 1 2 3 4 5 6 7; do
  shot "$DIR/en-$n.png" 1080 1920 "$SRC?panel=$n"
done
shot "$DIR/contact-sheet.png" 2172 578 "file://$PWD/$DIR/contact-sheet.html"

# Tagged as sRGB, which is what Chrome rendered them in.
for f in "$DIR"/en-*.png "$DIR/panorama-en.png"; do
  sips --embedProfile "/System/Library/ColorSync/Profiles/sRGB Profile.icc" "$f" >/dev/null
done

# Play wants RGB without alpha: every one must say "no".
sips -g pixelWidth -g pixelHeight -g hasAlpha "$DIR"/en-*.png "$DIR/panorama-en.png"
