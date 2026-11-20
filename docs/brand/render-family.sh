#!/bin/sh
# Renders the pictures of the "Part of Life Dashboard" README section from
# docs/brand/family-card.html into docs/family/: every tile for GitHub's dark and light mode,
# plain and as the "You're here" tile, and the arrow between the groups. Run from the
# repository root. The same docs/family/ folder goes into every Life Dashboard repository; the
# card repositories only keep the pictures their own README shows.
set -e
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
mkdir -p docs/family
shot() { # shot <out> <width> <height> <query>
  "$CHROME" --headless=new --screenshot="docs/family/$1" --window-size="$2,$3" \
    --hide-scrollbars --force-device-scale-factor=2 --default-background-color=00000000 \
    --virtual-time-budget=2000 \
    "file://$PWD/docs/brand/family-card.html?$4" 2>/dev/null
}
# Width and height per kind of tile, so the tiles of one group line up.
tiles() { # tiles <width> <height> <part>...
  w="$1"; h="$2"; shift 2
  for p in "$@"; do
    for theme in dark light; do
      shot "$p-$theme.png" "$w" "$h" "p=$p&theme=$theme"
      [ "$p" = own-server ] || shot "$p-here-$theme.png" "$w" "$h" "p=$p&theme=$theme&here=1"
    done
  done
}
tiles 236 211 android ios
tiles 200 229 stack ha own-server
tiles 186 190 sleep-card screen-time-card year-card activity-card before-bed-card
for theme in dark light; do
  shot "arrow-$theme.png" 24 36 "p=arrow&theme=$theme"
done
