#!/usr/bin/env bash
#
# Captures the app screens for the Google Play screenshots (docs/brand/store-src/A*.png) on the
# store emulator, at its native 1080x2400, uncropped: docs/brand/play-panorama/panorama.html
# puts them in phone frames and crops the floating cards from them.
#
# The emulator is the AVD `ldc-store` ("Sam's phone", see docs/brand/store-shot.html for what is
# on it), started on a port of its own so a running test emulator is never touched:
#
#   emulator -avd ldc-store -port 5560 -no-window -no-audio -no-boot-anim -no-snapshot-save
#
# with the app already installed and its example setup in place. The script only drives it:
# the phone clock goes to STORE_NOW (the day of the example data, so "Today" and "Last sync"
# read as a phone in use), the status bar to a fixed clock in demo mode, and the app and Health
# Connect to the chosen language with Android's per-app language. Nothing is changed in the
# app's settings; the pairing sheet comes from a link with a made-up address and secret.
#
# LANG_CODE picks the language: en (the default, 12-hour clock) writes to store-src/, nl and de
# (24-hour clock) to store-src/nl/ and store-src/de/ for the Dutch and German store pages.
#
#   scripts/store-screenshots.sh
#   LANG_CODE=nl scripts/store-screenshots.sh
#
# It prints where the bands and rows that panorama.html crops ended up, per capture, so its
# RECTS can follow when a language wraps differently. Look at every capture at full size.
#
set -euo pipefail
cd "$(dirname "$0")/.."

SERIAL=${SERIAL:-emulator-5560}
LANG_CODE=${LANG_CODE:-en}
STORE_NOW=${STORE_NOW:-2026-10-20T16:09}    # the phone clock for the app screens
WEIGHT_DAY=${WEIGHT_DAY:-2026-10-17T16:09}  # the day the scale reading came in from Home Assistant
APP=com.owen282000.lifedashboard
HC=com.google.android.healthconnect.controller

case "$LANG_CODE" in
    en) LOCALES=""; CLOCK=12; OUT=docs/brand/store-src
        L_BACKFILL=Backfill; L_CANCEL=Cancel; L_APPS="Apps to send"; L_RECEIVE=Receive
        L_HEALTH=Health; L_WEIGHT=Weight ;;
    nl) LOCALES=nl-NL; CLOCK=24; OUT=docs/brand/store-src/nl
        L_BACKFILL=Aanvullen; L_CANCEL=Annuleren; L_APPS="Welke apps"; L_RECEIVE=Ontvangen
        L_HEALTH=Gezondheid; L_WEIGHT=Gewicht ;;
    de) LOCALES=de-DE; CLOCK=24; OUT=docs/brand/store-src/de
        L_BACKFILL=Nachtragen; L_CANCEL=Abbrechen; L_APPS="Welche Apps"; L_RECEIVE=Empfangen
        L_HEALTH=Gesundheit; L_WEIGHT=Gewicht ;;
    *) echo "LANG_CODE must be en, nl or de" >&2; exit 1 ;;
esac
mkdir -p "$OUT"

adb() { command adb -s "$SERIAL" "$@"; }
if [ "$(adb shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')" != ldc-store ]; then
    echo "$SERIAL is not the AVD ldc-store" >&2
    exit 1
fi

# Finding things on screen by their text, through uiautomator.
UI=$(mktemp -d)
trap 'rm -rf "$UI"' EXIT
cat > "$UI/ui.py" <<'PY'
import re, subprocess, sys, time
import xml.etree.ElementTree as ET
serial, cmd, *args = sys.argv[1:]
def dump():
    for _ in range(5):
        subprocess.run(["adb", "-s", serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
        x = subprocess.run(["adb", "-s", serial, "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
        if x.startswith("<?xml"):
            return ET.fromstring(x)
        time.sleep(1)
    sys.exit("uiautomator dump failed")
nodes = []
for n in dump().iter("node"):
    b = list(map(int, re.findall(r"\d+", n.get("bounds", ""))))
    t = n.get("text") or n.get("content-desc") or ""
    if len(b) == 4 and t:
        nodes.append((t, b))
if cmd == "find":      # find TEXT [INDEX]: bounds of the INDEXth node whose text is TEXT exactly
    hits = [b for t, b in nodes if t == args[0]]
    idx = int(args[1]) if len(args) > 1 else 0
    print(*hits[idx]) if len(hits) > idx else sys.exit(1)
elif cmd == "after":   # after PART NEXT: bounds of a node containing PART whose next node contains NEXT
    for i, (t, b) in enumerate(nodes[:-1]):
        if args[0] in t and args[1] in nodes[i + 1][0]:
            print(*b); break
    else:
        sys.exit(1)
elif cmd == "has":     # has PART: bounds of the first node whose text contains PART
    hits = [b for t, b in nodes if args[0] in t]
    print(*hits[0]) if hits else sys.exit(1)
PY
ui() { python3 -I "$UI/ui.py" "$SERIAL" "$@"; }
tap() { # tap TEXT [INDEX]
    local b; b=$(ui find "$@") || { echo "not on screen: $1" >&2; exit 1; }
    set -- $b; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
scroll() { # scroll up|down: one page
    if [ "$1" = down ]; then adb shell input swipe 540 1700 540 700 300; else adb shell input swipe 540 700 540 1700 300; fi
    sleep 0.6
}
bring() { # bring TOP Y: scroll slowly until the top of the box TOP (four numbers) is at Y; TOP is a ui call
    local want=$1; shift
    for _ in 1 2 3 4; do
        local b; b=$(ui "$@") || return 1
        set -- $b "$@"; local d=$(( $2 - want )); shift 4
        if [ "$d" -gt -12 ] && [ "$d" -lt 12 ]; then return 0; fi
        if [ "$d" -gt 0 ]; then adb shell input swipe 540 1900 540 $(( 1900 - d )) 2500
        else adb shell input swipe 540 600 540 $(( 600 - d )) 2500; fi
        sleep 1
    done
}
shot() { sleep "${2:-1.5}"; adb exec-out screencap -p > "$OUT/$1"; echo "  $OUT/$1"; }
set_clock() {
    adb shell cmd alarm set-time "$(python3 -c 'import datetime as d, sys, zoneinfo
print(int(d.datetime.fromisoformat(sys.argv[1]).replace(tzinfo=zoneinfo.ZoneInfo("Europe/Amsterdam")).timestamp() * 1000))' "$1")"
}
tab() { # tab 1|2|3: Health, Screen Time, Logs in the bottom bar
    adb shell input tap $(( 208 + ($1 - 1) * 332 )) 2203; sleep 1.5
}

# The phone: its native 1080x2400 (a size override from other work is put back at the end),
# clock, 12 or 24 hours, dark, and a quiet status bar at 16:09 (4:09 in 12 hours).
OLD_SIZE=$(adb shell wm size | sed -n 's/^Override size: //p' | tr -d '\r')
adb shell wm size reset
sleep 2
set_clock "$STORE_NOW"
adb shell settings put system time_12_24 "$CLOCK"
adb shell cmd uimode night yes >/dev/null
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
adb shell settings put global sysui_demo_allowed 1
demo exit
demo enter
demo clock -e hhmm "${STORE_NOW:11:2}${STORE_NOW:14:2}"
demo battery -e level 100 -e plugged false -e powersave false
demo network -e wifi show -e level 4 -e fully true -e mobile hide -e airplane hide -e nosim hide
demo notifications -e visible false
demo status -e volume hide -e bluetooth hide -e location hide -e alarm hide -e zen hide -e sync hide

# The language, for the app and for Health Connect (the weight screen).
adb shell cmd locale set-app-locales "$APP" --locales "$LOCALES"
adb shell cmd locale set-app-locales "$HC" --locales "$LOCALES"
adb shell am force-stop "$APP"
adb shell am force-stop "$HC"
adb shell monkey -p "$APP" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5

echo "LANG_CODE=$LANG_CODE"
# A1: the Health tab as it opens.
tab 1
shot A1-health.png

# A2: the pairing sheet, from a link with a made-up address and secret.
adb shell am start -a android.intent.action.VIEW -d "'lifedashboard://pair#v=1&url=https%3A%2F%2Fhomeassistant.local%3A8123%2Fapi%2Fwebhook%2F0000example0000not0a0real0webhook0id0000&secret=example-secret-not-real-0123456789&name=Home%20Assistant&sources=health_connect,screen_time'" "$APP" >/dev/null 2>&1
shot A2-pair.png 3
tap "$L_CANCEL"

# A3: the backfill lengths, from the button row at the bottom of the Health tab.
scroll down; scroll down; scroll down
tap "$L_BACKFILL"
shot A3-backfill.png 2
tap "$L_CANCEL"
scroll up; scroll up; scroll up

# A4: the Screen Time tab with the app filter open.
tab 2
tap "$L_APPS"
shot A4-screen-time.png
tap "$L_APPS"

# A5: the Health tab with the webhook addresses open.
tab 1
tap Webhook
shot A5-webhook.png
tap Webhook

# A6: a delivery with a deletion in it (17 October, 18:08:21, to the stack's receiver), opened,
# its header near the top.
tab 3
tap "$L_HEALTH"
sleep 1
for _ in 1 2 3 4 5 6; do
    ui after "192.168.1.20" "18:08:21" >/dev/null && break
    scroll down
done
set -- $(ui after "192.168.1.20" "18:08:21")
adb shell input tap 540 $(( ($2 + $4) / 2 ))
sleep 1.5
bring 453 after "192.168.1.20" "18:08:21"
echo "  A6: entry title at $(ui after "192.168.1.20" "18:08:21")"
shot A6-payload.png

# A7: Receive opened, its header near the top. The address under it holds the webhook secret:
# panorama.html leaves that band out.
tab 1
scroll up; scroll up
tap "$L_RECEIVE"
sleep 1
bring 303 find "$L_RECEIVE"
echo "  A7: Receive at $(ui find "$L_RECEIVE"), the address with the secret at $(ui has "/api/webhook/")"
shot A7-receive.png
tap "$L_RECEIVE"
scroll up; scroll up

# A7: the reading in Health Connect, on the day it came in.
set_clock "$WEIGHT_DAY"
adb shell am start -a android.health.connect.action.MANAGE_HEALTH_DATA >/dev/null
sleep 3
tap "$L_WEIGHT"
shot A7-hc-weight.png 2.5
adb shell am force-stop "$HC"
set_clock "$STORE_NOW"

# Back to how the phone was: its own language and 12 hours, the app in front.
adb shell cmd locale set-app-locales "$APP" --locales ""
adb shell cmd locale set-app-locales "$HC" --locales ""
adb shell settings put system time_12_24 12
if [ -n "$OLD_SIZE" ]; then adb shell wm size "$OLD_SIZE"; fi
adb shell input keyevent HOME
echo "Wrote $(ls "$OUT"/A*.png | wc -l | tr -d ' ') captures to $OUT. Look at every one at full size."
