#!/usr/bin/env bash
#
# Captures the app screens for the Google Play screenshots in store-assets/raw/, which is not in
# git, at the native 1080x2400 of the store emulator, uncropped: docs/brand/play-panorama/panorama.html
# puts them in phone frames and crops the floating cards from them.
#
# The emulator is the AVD `ldc-store`, on a port of its own so a running test emulator is never touched:
#
#   emulator -avd ldc-store -port 5558 -no-snapshot-save -no-boot-anim -no-window -no-audio -gpu swiftshader_indirect
#
# It builds the debug APKs of the app and of :hc-fixture and installs them with adb, since only a
# debuggable build opens pages through `ld.route` and lets this script write its settings. A
# phone in use, the same in every language:
#
#   - Health Connect: a week from the fixture app (SeedWeek), its source app as on a real phone.
#   - Settings, under the app's own keys: the Essentials, a webhook on n8n.example.com, Home
#     Assistant, an MQTT broker, four fixed times a day. Secrets go in as the plain keys of an older
#     version, and the app moves them into its Keystore store at launch. Everything is
#     made up: example.com, homeassistant.local, mqtt.local and a secret that says it is not real.
#   - The log: a week of deliveries with their payloads and a backfill of the whole history. The
#     last sync is at 16:06.
#   - Screen time: ScreenTimeDemo.kt says why this one is a file only a debug build reads.
#   - The clock: STORE_NOW for every capture, so "3 min ago" and "Today" come out the same at any
#     hour; the status bar at a fixed 16:09 in demo mode; dark. No network, so no worker tries a
#     made-up address during the capture, and the battery card is dismissed.
#
# LANG_CODE picks the language: en (the default, British English, so kilograms and a 24-hour
# clock) writes to store-assets/raw/, the others to store-assets/raw/<code>/. Units and dates follow
# the locale. Arabic uses ar-AE, for Gregorian dates and Western digits.
#
#   scripts/store-screenshots.sh
#   LANG_CODE=nl scripts/store-screenshots.sh
#   SKIP_BUILD=1 LANG_CODE=de scripts/store-screenshots.sh   # after a first run in this session
#
# Then docs/brand/play-panorama/render.sh renders the panels. Look at every capture at full size.
#
set -euo pipefail
cd "$(dirname "$0")/.."

SERIAL=${SERIAL:-emulator-5558}
LANG_CODE=${LANG_CODE:-en}
STORE_NOW=${STORE_NOW:-2026-11-03T16:09}    # the phone clock for the app screens
VERSION=$(sed -n 's/^VERSION_NAME=//p' version.properties)
APP=com.owen282000.lifedashboard
FIXTURE=com.owen282000.lifedashboard.fixture

case "$LANG_CODE" in
    en) LOCALES=en-GB ;;
    nl) LOCALES=nl-NL ;;
    de) LOCALES=de-DE ;;
    fr) LOCALES=fr-FR ;;
    es) LOCALES=es-ES ;;
    it) LOCALES=it-IT ;;
    pt-BR) LOCALES=pt-BR ;;
    ja) LOCALES=ja-JP ;;
    ar) LOCALES=ar-AE ;;
    hi) LOCALES=hi-IN ;;
    zh-CN) LOCALES=zh-CN ;;
    *) echo "LANG_CODE must be en, nl, de, fr, es, it, pt-BR, ja, ar, hi or zh-CN" >&2; exit 1 ;;
esac
OUT=store-assets/raw
[ "$LANG_CODE" = en ] || OUT=$OUT/$LANG_CODE
mkdir -p "$OUT"

adb() { command adb -s "$SERIAL" "$@"; }
if [ "$(adb shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')" != ldc-store ]; then
    echo "$SERIAL is not the AVD ldc-store" >&2
    exit 1
fi

if [ -z "${SKIP_BUILD:-}" ]; then
    ./gradlew :app:assembleDebug :hc-fixture:assembleDebug :hc-fixture:assembleDebugAndroidTest -q -PldVersionName="$VERSION"
fi
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

set_clock() {
    adb shell cmd alarm set-time "$(python3 -c 'import datetime as d, sys, zoneinfo
print(int(d.datetime.fromisoformat(sys.argv[1]).replace(tzinfo=zoneinfo.ZoneInfo("Europe/Amsterdam")).timestamp() * 1000))' "$1")" >/dev/null
}

# The phone: its native size, the clock, dark, no motion, a quiet status bar.
OLD_SIZE=$(adb shell wm size | sed -n 's/^Override size: //p' | tr -d '\r')
adb shell wm size reset
adb shell settings put global auto_time 0
adb shell cmd alarm set-timezone Europe/Amsterdam >/dev/null
set_clock "$STORE_NOW"
adb shell settings put system time_12_24 24
adb shell cmd uimode night yes >/dev/null
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$scale" 0
done
adb shell svc wifi disable
adb shell svc data disable
sleep 2
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
adb shell settings put global sysui_demo_allowed 1
demo exit
demo enter
demo clock -e hhmm "${STORE_NOW:11:2}${STORE_NOW:14:2}"
demo battery -e level 100 -e plugged false -e powersave false
demo network -e wifi show -e level 4 -e fully true -e mobile hide -e airplane hide -e nosim hide
demo notifications -e visible false
demo status -e volume hide -e bluetooth hide -e location hide -e alarm hide -e zen hide -e sync hide

# A debug build cannot replace what another key signed, and every run starts from no settings.
adb shell pm uninstall "$APP" >/dev/null 2>&1 || true
adb install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null

# Health Connect: a week from the fixture, once for this day; a rerun only updates the same records.
SEEDED=/data/local/tmp/ld-store-seeded-${STORE_NOW:0:10}
if [ -z "$(adb shell ls "$SEEDED" 2>/dev/null | tr -d '\r')" ]; then
    adb install -r -t hc-fixture/build/outputs/apk/debug/hc-fixture-debug.apk >/dev/null
    adb install -r -t hc-fixture/build/outputs/apk/androidTest/debug/hc-fixture-debug-androidTest.apk >/dev/null
    # The app op of the health permissions stays on ignore on this image after an install.
    adb shell appops set --uid "$FIXTURE" READ_WRITE_HEALTH_DATA allow
    adb shell am instrument -w --no-hidden-api-checks -e class "$FIXTURE.SeedWeek" \
        "$FIXTURE.test/androidx.test.runner.AndroidJUnitRunner" | grep -E 'Seeded|FAILURES|Error' || true
    adb shell touch "$SEEDED"
fi

# Everything the app asks for, so no type has a lock and no prompt comes up.
for permission in $(adb shell dumpsys package "$APP" | sed -n 's/^ *\(android\.permission\.health\.[A-Z0-9_]*\).*/\1/p' | sort -u) android.permission.POST_NOTIFICATIONS; do
    adb shell pm grant "$APP" "$permission" 2>/dev/null || true
done
adb shell appops set "$APP" android:get_usage_stats allow
adb shell appops set --uid "$APP" READ_WRITE_HEALTH_DATA allow
adb shell dumpsys deviceidle whitelist "+$APP" >/dev/null

# The settings and the log, as the files the app itself writes.
python3 -I - "$WORK/seed" "$STORE_NOW" "$VERSION" <<'PY'
import datetime as dt, hashlib, json, os, sys, uuid, zoneinfo
from xml.sax.saxutils import escape

root, store_now, version = sys.argv[1:]
zone = zoneinfo.ZoneInfo("Europe/Amsterdam")
now = dt.datetime.fromisoformat(store_now).replace(tzinfo=zone)
last_sync = now - dt.timedelta(minutes=3)
ms = lambda t: int(t.timestamp() * 1000)
iso = lambda t: t.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

N8N = "https://n8n.example.com/webhook/health"
HA = "http://homeassistant.local:8123/api/webhook/3f9c2a7e51b84d06a1e2c9d8b7f4e5a1"
ST_HOOK = "https://n8n.example.com/webhook/screen-time"
MQTT = "mqtt://mqtt.local:1883/life_dashboard"
SECRET = "example-signing-secret-not-real"
FIXTURE = "com.owen282000.lifedashboard.fixture"
ESSENTIALS = "STEPS,SLEEP,HEART_RATE,RESTING_HEART_RATE,DISTANCE,ACTIVE_CALORIES,TOTAL_CALORIES,WEIGHT"
TIMES = "08:00,12:00,16:00,20:00"
ALL_DAYS = "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY"

def prefs(path, values):
    lines = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
    for key, value in values.items():
        if isinstance(value, bool):
            lines.append(f'    <boolean name="{key}" value="{str(value).lower()}" />')
        elif isinstance(value, int):
            tag = "long" if abs(value) > 2**31 or key.startswith(LONGS) else "int"
            lines.append(f'    <{tag} name="{key}" value="{value}" />')
        else:
            lines.append(f'    <string name="{key}">{escape(value)}</string>')
    lines.append("</map>\n")
    full = os.path.join(root, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

LONGS = ("stats_lifetime_records", "stats_first_sync", "widget_last_sync", "screentime_last_sync_ts", "health_schedule_last_run", "screentime_schedule_last_run")
today = now.date().isoformat()
settings = {
    "onboarding_completed": True,
    "battery_hint_dismissed": True,
    "health_types_chosen": True,
    "health_series_resolutions": "ACTIVE_CALORIES=FIFTEEN_MINUTES,DISTANCE=FIFTEEN_MINUTES,HEART_RATE=FIVE_MINUTES,STEPS=FIFTEEN_MINUTES",
    "health_enabled_data_types": ESSENTIALS,
    "health_webhook_urls": f"{N8N},{HA}",
    "health_webhook_secret": SECRET,
    "screentime_webhook_urls": ST_HOOK,
    "screentime_webhook_secret": SECRET,
    "allow_http_webhooks": True,
    "mqtt_enabled": True,
    "mqtt_host": "mqtt.local",
    "mqtt_port": 1883,
    "mqtt_tls": False,
    "mqtt_base_topic": "life_dashboard",
    "mqtt_last_status": f"OK: 19 sensors published at {iso(last_sync)}",
    "screentime_mqtt_enabled": True,
    "screentime_mqtt_base_topic": "life_dashboard",
    "screentime_mqtt_last_status": f"OK: 7 sensors published at {iso(last_sync)}",
    "health_schedule_mode": "TIMES",
    "health_schedule_times": TIMES,
    "health_schedule_days": ALL_DAYS,
    "health_schedule_quiet_from": "23:00",
    "health_schedule_quiet_to": "07:00",
    "health_schedule_last_run": ms(last_sync),
    # Fixed times, the next one hours away: on an interval a sync starts at launch and fails on the
    # made-up address. Two, so the line under Sync Now stays one line and the tiles fit.
    "screentime_schedule_mode": "TIMES",
    "screentime_schedule_times": "08:00,16:00",
    "screentime_schedule_days": ALL_DAYS,
    "screentime_schedule_last_run": ms(last_sync),
    "screentime_last_sync_ts": ms(last_sync),
    "screentime_use_day_boundary": True,
    "screentime_day_boundary_hour": 4,
    "receive_enabled": True,
    "receive_source_url": HA,
    "receive_types": "weight,blood_pressure",
    "receive_configured_types": "weight,blood_pressure",
    "stats_total_deliveries": 1236,
    "stats_lifetime_records": 183946,
    "stats_largest_payload": 248112,
    "stats_first_sync": ms(now - dt.timedelta(days=118)),
}
for suffix in ("", "_HEALTH_CONNECT", "_SCREEN_TIME"):
    settings["widget_last_sync" + suffix] = ms(last_sync)
    settings["widget_last_success" + suffix] = True
    settings["widget_records_today" + suffix] = 214
    settings["widget_records_today_date" + suffix] = today
prefs("shared_prefs/life_dashboard_prefs.xml", settings)

# The log, newest first, each row with a stable id and its payload in a file of its own.
rows = []
def body(records, t, sequence):
    stamp = iso(t)
    def rec(kind, **fields):
        key = uuid.uuid5(uuid.NAMESPACE_DNS, f"{stamp}{kind}{sorted(fields.items())}")
        return {**fields, "uuid": str(key), "source": FIXTURE}
    hour = t.replace(minute=0, second=0)
    steps = [rec("steps", count=212 + n * 37 % 300, start_time=iso(hour - dt.timedelta(hours=n + 1)), end_time=iso(hour - dt.timedelta(hours=n)))
             for n in range(min(records // 3, 4))]
    return json.dumps({
        "timestamp": stamp, "timezone": "Europe/Amsterdam", "app_version": version,
        "source": "health_connect", "sequence": sequence,
        "daily_totals": [{"date": t.date().isoformat(), "steps": 6482, "distance_meters": 4796.7, "active_calories": 259.3, "total_calories": 1909.3}],
        "steps": steps,
        "heart_rate": [rec("hr", bpm=71, time=stamp)],
        "resting_heart_rate": [rec("rest", bpm=58, time=iso(t.replace(hour=6, minute=30, second=0)))],
        "weight": [rec("weight", kilograms=78.4, time=iso(t.replace(hour=6, minute=45, second=0)))],
    }, separators=(",", ":"))

def row(t, url, code, count, data, log_type="HEALTH_CONNECT", destination="WEBHOOK", payload=None, direction="OUT"):
    identity = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{ms(t)}{url}{data}"))
    rows.append(({"id": identity, "timestamp": ms(t), "url": url, "statusCode": code, "success": True,
                  "errorMessage": None, "dataType": data, "recordCount": count, "logType": log_type,
                  "destination": destination, "direction": direction}, payload))

def screen_time(t):
    date = t.date().isoformat()
    return json.dumps({"timestamp": iso(t), "timezone": "Europe/Amsterdam", "app_version": version, "source": "screen_time",
                       "screen_time": [{"date": date, "total_screen_time_minutes": 161, "apps": [
                           {"package_name": "com.android.chrome", "app_name": "Chrome", "total_minutes": 61},
                           {"package_name": "com.google.android.youtube", "app_name": "YouTube", "total_minutes": 52}]}]},
                      separators=(",", ":"))

counts = [38, 21, 64, 17, 45, 29, 52, 33, 19, 41, 27, 58, 24, 36, 47, 22, 31, 55, 26, 43]
sequence = 1842
i = 0
for day in range(4, -1, -1):
    for hour, minute in ((8, 3), (12, 5), (16, 6), (20, 4)):
        t = (now - dt.timedelta(days=day)).replace(hour=hour, minute=minute, second=(i * 7) % 50)
        if t > now:
            continue
        records = counts[i % len(counts)]
        i += 1
        sequence += 1
        # Screen time first: the newest row is the webhook, for logDetail.ok.
        row(t, MQTT, None, 7, "mqtt", log_type="SCREEN_TIME", destination="MQTT")
        row(t + dt.timedelta(seconds=1), ST_HOOK, 200, 7, "screen_time", log_type="SCREEN_TIME", payload=screen_time(t))
        row(t + dt.timedelta(seconds=3), MQTT, None, 19, "mqtt", destination="MQTT")
        row(t + dt.timedelta(seconds=4), HA, 200, records, "health_connect", payload=body(records, t, sequence))
        row(t + dt.timedelta(seconds=5), N8N, 200, records, "health_connect", payload=body(records, t, sequence))
# A backfill of the whole history this morning, after the first sync.
start = now.replace(hour=9, minute=12, second=40)
row(start, N8N, None, 486215, "health_connect_backfill_run",
    payload=json.dumps({"backfill": True, "length": "all_history", "status": "done", "windows_done": 731,
                        "window_count": 731, "records_sent": 486215, "records_sent_by_job": 486215, "restarted": False}))
rows.sort(key=lambda entry: entry[0]["timestamp"], reverse=True)
rows = rows[:100]
for entry, payload in rows:
    if payload:
        path = os.path.join(root, "files/webhook_payloads", entry["id"] + ".json")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(payload)
prefs("shared_prefs/life_dashboard_logs.xml", {"entries": json.dumps([entry for entry, _ in rows], separators=(",", ":"))})

# Screen time: a week of the phone's own apps, today up to this hour. Four today, so the first
# row of tiles shows in full in the languages that wrap the most.
apps = [("com.android.chrome", "Chrome"), ("com.google.android.youtube", "YouTube"), ("com.google.android.gm", "Gmail"),
        ("com.google.android.apps.messaging", "Messages"), ("com.google.android.apps.maps", "Maps"),
        ("com.google.android.apps.photos", "Photos"), ("com.google.android.calendar", "Calendar")]
minutes = [[61, 52, 27, 21], [62, 74, 21, 24, 8, 12, 6], [55, 38, 26, 17, 21, 6, 9], [71, 96, 19, 22, 11, 16, 5],
           [58, 52, 28, 16, 33, 7, 8], [44, 61, 22, 26, 9, 14, 7], [66, 83, 17, 21, 12, 11, 6]]
days = [{"daysAgo": ago, "apps": [{"packageName": p, "name": n, "minutes": m} for (p, n), m in zip(apps, minutes[ago])]} for ago in range(7)]
with open(os.path.join(root, "files/ld_demo_usage.json"), "w", encoding="utf-8") as f:
    json.dump(days, f)
PY
adb shell am force-stop "$APP"
adb shell cmd locale set-app-locales "$APP" --locales "$LOCALES"
chmod -R go-rwx "$WORK/seed"
# By name: an entry for . would put this folder's mode on the app's data folder, and run-as refuses that.
COPYFILE_DISABLE=1 tar -C "$WORK/seed" -cf "$WORK/seed.tar" shared_prefs files
adb exec-in run-as "$APP" tar xf - < "$WORK/seed.tar"

# A capture and what is on it: render.sh crops the floating cards by these bounds, because a
# heading that wraps moves everything under it, per language and per screen, and Arabic mirrors.
capture() { # name
    adb exec-out screencap -p > "$OUT/$1.png"
    adb shell uiautomator dump /sdcard/ld-ui.xml >/dev/null
    adb exec-out cat /sdcard/ld-ui.xml > "$OUT/$1.xml"
    echo "  $OUT/$1.png"
}
open_fresh() { # route
    adb shell am force-stop "$APP"
    set_clock "$STORE_NOW"
    adb shell am start -n "$APP/.MainActivity" --es ld.route "$1" >/dev/null
}
shot() { # name route [seconds to wait]
    open_fresh "$2"
    sleep "${3:-4}"
    capture "$1"
}

echo "LANG_CODE=$LANG_CODE"
shot home home 6
shot screen-time screenTimeHome 5
shot screen-time-apps screenTimeApps
shot logs logsList
shot delivery logDetail.ok
shot webhook webhookDestination
shot mqtt mqttDestination
shot add addDestination
shot what whatToSend
shot category typeCategory.Heart
shot resolution dataResolution
shot preview payloadPreview 6
shot when whenToSend
shot receive receive
shot more more
shot about about
shot paired pairedResult

# The pairing page, from the example link of docs/pairing.md, the one in pairing-example-qr.svg.
adb shell am force-stop "$APP"
set_clock "$STORE_NOW"
PAIR_LINK='lifedashboard://pair#v=1&url=https%3A%2F%2Fhealth.example.com%2Fwebhook%2Fu%2F42&secret=example-secret-not-real-0123456789&name=Example%20Health'
adb shell am start -a android.intent.action.VIEW -d "'$PAIR_LINK'" "$APP" >/dev/null
sleep 5
capture pairing

# A backfill on its way, from the app's own Start: without a network its work stays queued, and
# the stored job says how far it got. Its page reads that when it opens.
shot backfill backfill
python3 -I - "$OUT/backfill.xml" <<'PY' > "$WORK/start"
import re, sys
import xml.etree.ElementTree as ET
last = [n for n in ET.parse(sys.argv[1]).iter("node") if n.get("clickable") == "true"][-1]
x0, y0, x1, y1 = map(int, re.findall(r"\d+", last.get("bounds")))
print((x0 + x1) // 2, (y0 + y1) // 2)
PY
adb shell input tap $(cat "$WORK/start")
sleep 3
adb shell am force-stop "$APP"
adb exec-out run-as "$APP" cat shared_prefs/life_dashboard_prefs.xml > "$WORK/prefs.xml"
python3 -I - "$WORK/prefs.xml" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
text = re.sub(r"&quot;nextWindow&quot;:\d+,?", "", text)
text = re.sub(r"&quot;recordsSent&quot;:\d+,?", "", text)
text = re.sub(r"(<string name=\"backfill_job\">\{)", r"\1&quot;nextWindow&quot;:88,&quot;recordsSent&quot;:214380,", text)
open(sys.argv[1], "w", encoding="utf-8").write(text)
PY
adb shell -T "run-as $APP sh -c 'cat > shared_prefs/life_dashboard_prefs.xml'" < "$WORK/prefs.xml"
shot backfill-run backfillRun

# docs/brand/pairing-sheet.png: the same page on a first pairing, with no other destination to mention.
if [ "$LANG_CODE" = en ]; then
    adb shell am force-stop "$APP"
    adb shell pm clear "$APP" >/dev/null
    adb shell -T "run-as $APP sh -c 'mkdir -p shared_prefs; cat > shared_prefs/life_dashboard_prefs.xml'" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="onboarding_completed" value="true" /><boolean name="battery_hint_dismissed" value="true" /></map>
XML
    adb shell cmd locale set-app-locales "$APP" --locales "$LOCALES"
    adb shell am start -a android.intent.action.VIEW -d "'$PAIR_LINK'" "$APP" >/dev/null
    sleep 10
    adb exec-out screencap -p > "$OUT/pairing-first.png"
    echo "  $OUT/pairing-first.png"
fi

# Back to how the phone was. The clock follows the host again with auto_time, or at the next boot.
adb shell pm clear "$APP" >/dev/null
adb shell cmd locale set-app-locales "$APP" --locales ""
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$scale" 1
done
adb shell svc wifi enable
adb shell svc data enable
demo exit
if [ -n "$OLD_SIZE" ]; then adb shell wm size "$OLD_SIZE"; fi
adb shell input keyevent HOME
echo "Wrote $(ls "$OUT"/*.png | wc -l | tr -d ' ') captures to $OUT. Look at every one at full size."
