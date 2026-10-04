#!/bin/sh
# Renders the eight project cards of the "Part of Life Dashboard" README section from
# docs/brand/family-card.html into docs/family/. Run from the repository root. The same
# docs/family/ folder goes into all four Life Dashboard repositories unchanged.
set -e
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
mkdir -p docs/family
for p in android ios ha stack; do
  for here in 0 1; do
    out="docs/family/$p.png"; [ "$here" = 1 ] && out="docs/family/$p-here.png"
    "$CHROME" --headless=new --screenshot="$out" --window-size=800,232 \
      --hide-scrollbars --force-device-scale-factor=1 --default-background-color=00000000 \
      --virtual-time-budget=2000 "file://$PWD/docs/brand/family-card.html?p=$p&here=$here"
  done
done
