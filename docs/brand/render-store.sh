#!/bin/sh
# Renders the Google Play store graphics from docs/brand/ with headless Chrome. Run from the
# repository root.
#
#   phoneScreenshots/1.png to 8.png  store-shot.html?n=1..8, 1080x1920, RGB (no alpha)
#   featureGraphic.png               feature-graphic.html, 1024x500, RGB (no alpha)
#   icon.png                         icon.html, 512x512, RGBA (Play lists a 32-bit PNG)
#
# The screenshots use the captures in docs/brand/store-src/.
set -e
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
IMAGES=fastlane/metadata/android/en-US/images
mkdir -p "$IMAGES/phoneScreenshots"

shot() { # out width height url [extra flag]
  "$CHROME" --headless=new --screenshot="$1" --window-size="$2,$3" --hide-scrollbars \
    --force-device-scale-factor=1 --virtual-time-budget=3000 $5 "$4" 2>/dev/null
}

for n in 1 2 3 4 5 6 7 8; do
  shot "$IMAGES/phoneScreenshots/$n.png" 1080 1920 "file://$PWD/docs/brand/store-shot.html?n=$n"
done
shot "$IMAGES/featureGraphic.png" 1024 500 "file://$PWD/docs/brand/feature-graphic.html"
shot "$IMAGES/icon.png" 512 512 "file://$PWD/docs/brand/icon.html" --default-background-color=00000000

sips -g pixelWidth -g pixelHeight -g hasAlpha "$IMAGES"/phoneScreenshots/*.png \
  "$IMAGES/featureGraphic.png" "$IMAGES/icon.png"
