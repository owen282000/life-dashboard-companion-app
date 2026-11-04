#!/bin/sh
# Renders the Google Play phone screenshots from panorama.html with headless Chrome into
# store-assets/, which is not in git. Run from the repository root, after
# scripts/store-screenshots.sh has filled store-assets/raw/ and store-assets/raw/<lang>/. measure.py
# first finds the rows and cards the panels crop, per language (raw/rects.js).
#
#   docs/brand/play-panorama/render.sh           every language
#   docs/brand/play-panorama/render.sh nl de     only these
#
#   panorama-en.png        the whole row, 10560x2640
#   en-1.png to en-8.png   one panel each, 1320x2640, RGB without alpha
#   contact-sheet.png      the eight panels side by side, scaled down, for review; other languages
#     contact-sheet-<lang>.png
#   feature-en.png         the feature graphic of docs/brand/feature-graphic.html, 1024x500, RGB
#
# Upload these to the Play Console. docs/brand/render-store.sh copies en, nl and de into fastlane,
# which F-Droid reads from the repository and so does stay in git.
set -e
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
DIR=docs/brand/play-panorama
OUT=store-assets
SRC="file://$PWD/$DIR/panorama.html"
SRGB="/System/Library/ColorSync/Profiles/sRGB Profile.icc"

shot() { # out width height url
  "$CHROME" --headless=new --screenshot="$1" --window-size="$2,$3" --hide-scrollbars --allow-file-access-from-files \
    --force-device-scale-factor=1 --force-color-profile=srgb --virtual-time-budget=6000 --default-background-color=0A0F0DFF \
    "$4" 2>/dev/null
}

WORK=build/store-render
mkdir -p "$WORK" "$OUT"
# Chrome writes an alpha channel even for an opaque page, and Play refuses one.
cat > "$WORK/flatten.swift" <<'SWIFT'
import AppKit
let path = CommandLine.arguments[1]
let source = NSBitmapImageRep(data: try Data(contentsOf: URL(fileURLWithPath: path)))!.cgImage!
let space = CGColorSpace(name: CGColorSpace.sRGB)!
let context = CGContext(data: nil, width: source.width, height: source.height, bitsPerComponent: 8, bytesPerRow: 0,
                        space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
context.draw(source, in: CGRect(x: 0, y: 0, width: source.width, height: source.height))
try NSBitmapImageRep(cgImage: context.makeImage()!).representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
SWIFT
swiftc -O -o "$WORK/flatten" "$WORK/flatten.swift" 2>/dev/null
finish() { "$WORK/flatten" "$1"; sips --embedProfile "$SRGB" "$1" >/dev/null; }

python3 -I "$DIR/measure.py"
[ $# -gt 0 ] || set -- en nl de fr es it pt-BR ja ar hi zh-CN
for lang in "$@"; do
  if [ "$lang" = en ]; then sheet=contact-sheet.png; else sheet=contact-sheet-$lang.png; fi
  shot "$OUT/panorama-$lang.png" 10560 2640 "$SRC?lang=$lang"
  finish "$OUT/panorama-$lang.png"
  for n in 1 2 3 4 5 6 7 8; do
    shot "$OUT/$lang-$n.png" 1320 2640 "$SRC?lang=$lang&panel=$n"
    finish "$OUT/$lang-$n.png"
  done
  shot "$OUT/feature-$lang.png" 1024 500 "file://$PWD/docs/brand/feature-graphic.html?lang=$lang"
  finish "$OUT/feature-$lang.png"
  shot "$OUT/$sheet" 2620 680 "file://$PWD/$DIR/contact-sheet.html?lang=$lang"
done

# Play refuses screenshots with an alpha channel: every one must say "no".
sips -g pixelWidth -g pixelHeight -g hasAlpha "$OUT"/*-[1-8].png "$OUT"/feature-*.png \
  | grep -E 'pixel|hasAlpha' | paste - - - | sort | uniq -c
