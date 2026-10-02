#!/usr/bin/env bash
#
# Runs the instrumented suite (app/src/androidTest) on the emulator `ldc-instrumented`, the same
# way locally and in CI:
#
#   scripts/instrumented.sh                  # build, install, run everything
#   scripts/instrumented.sh --class com.owen282000.lifedashboard.sync.OutboxTest
#   scripts/instrumented.sh --no-build       # reuse the APKs already built
#   scripts/instrumented.sh --ci             # CI: no questions, no build, no AVD management
#   scripts/instrumented.sh --small          # leave out the @LargeTest tests (budgets, hangs)
#   scripts/instrumented.sh --no-smoke       # skip the background smoke run at the end
#   scripts/instrumented.sh --smoke-only     # only the background smoke run
#
# After the suite a background smoke run checks the real background route with a receiver on
# the host (scripts/webhook-receiver.py) and `cmd jobscheduler run -f`; see smoke() below.
#
# The suite overwrites the app's settings and deletes the app's own Health Connect records, so
# it refuses any device that is not the AVD `ldc-instrumented` (a real phone, another emulator
# with a setup worth keeping) unless --force is given. Locally the AVD is created when it is
# missing, from an installed system image with API 34 or higher, and started headless when it
# is not running.
#
# It never trusts the exit code of `adb shell am instrument`, which is 0 when tests fail and when
# the app crashes: the output goes through scripts/instrument_to_junit.py. Everything lands in
# build/instrumented/: raw.txt, junit.xml, summary.md, logcat.txt and witness/ (what the tests
# kept as evidence). Needs docker, for a broker of its own (eclipse-mosquitto, anonymous, on
# host port 18830, reached from the device as 127.0.0.1:1883 through adb reverse).
#
set -euo pipefail

cd "$(dirname "$0")/.."

AVD_NAME="ldc-instrumented"
APP_ID="com.owen282000.lifedashboard"
RUNNER="$APP_ID.test/$APP_ID.harness.LdTestRunner"
OUT="build/instrumented"
MQTT_CONTAINER="ldc-instrumented-mosquitto"
MQTT_HOST_PORT="${LDC_MQTT_PORT:-18830}"
BOOT_TIMEOUT=300

CI_MODE=0
FORCE=0
BUILD=1
CLASS=""
SMOKE=1
SIZE=""
SMOKE_ONLY=0

usage() { awk 'NR > 2 && /^#/ { sub(/^# ?/, ""); print; next } NR > 2 { exit }' "$0"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --ci) CI_MODE=1; BUILD=0 ;;
        --force) FORCE=1 ;;
        --no-build) BUILD=0 ;;
        --no-smoke) SMOKE=0 ;;
        --small) SIZE="small" ;;
        --smoke-only) SMOKE_ONLY=1 ;;
        --class) CLASS="${2:?--class needs a class name}"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

say() { printf '\n==> %s\n' "$*"; }
die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

started_at=$(date +%s)

# --- The SDK's own adb, never the one on PATH -------------------------------------------------
# Two adb binaries of different versions kill each other's server; on the maintainer's Mac a
# Homebrew adb comes first on PATH.
sdk_dir() {
    if [ -n "${ANDROID_HOME:-}" ]; then echo "$ANDROID_HOME"; return; fi
    if [ -n "${ANDROID_SDK_ROOT:-}" ]; then echo "$ANDROID_SDK_ROOT"; return; fi
    if [ -f local.properties ]; then
        local dir
        dir=$(sed -n 's/^sdk\.dir=//p' local.properties | sed 's/\\:/:/g; s/\\\\/\\/g')
        if [ -n "$dir" ]; then echo "$dir"; return; fi
    fi
    echo "$HOME/Library/Android/sdk"
}
SDK="$(sdk_dir)"
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || die "no adb at $ADB (set ANDROID_HOME or sdk.dir in local.properties)"
if "$ADB" start-server 2>&1 | grep -q "doesn't match"; then
    die "another adb with a different version is running its server; stop it, then retry with $ADB"
fi

# --- Which device -------------------------------------------------------------------------------
avd_of() { "$ADB" -s "$1" emu avd name 2>/dev/null | head -1 | tr -d '\r' || true; }

list_devices() { "$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }'; }

find_suite_emulator() {
    local serial
    for serial in $(list_devices); do
        if [ "$(avd_of "$serial")" = "$AVD_NAME" ]; then echo "$serial"; return; fi
    done
}

host_abi() { case "$(uname -m)" in arm64|aarch64) echo "arm64-v8a" ;; *) echo "x86_64" ;; esac; }

create_avd() {
    local abi image="" api best=0 dir tag
    abi=$(host_abi)
    for dir in "$SDK"/system-images/android-*/google_apis*/"$abi"; do
        [ -d "$dir" ] || continue
        api=$(echo "$dir" | sed -n 's#.*/android-\([0-9][0-9]*\).*#\1#p')
        tag=$(basename "$(dirname "$dir")")
        # Health Connect is part of the platform from API 34.
        if [ -n "$api" ] && [ "$api" -ge 34 ] && [ "$api" -gt "$best" ]; then
            best=$api
            image="system-images;$(basename "$(dirname "$(dirname "$dir")")");$tag;$abi"
        fi
    done
    [ -n "$image" ] || die "no system image with API 34 or higher for $abi under $SDK/system-images; install one with sdkmanager"
    local avdmanager="$SDK/cmdline-tools/latest/bin/avdmanager"
    [ -x "$avdmanager" ] || die "no avdmanager at $avdmanager (install the SDK command-line tools)"
    say "Creating AVD $AVD_NAME from $image"
    echo no | "$avdmanager" create avd -n "$AVD_NAME" -k "$image" -d medium_phone >/dev/null
    local config="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
    sed -i.bak -e 's/^disk.dataPartition.size=.*/disk.dataPartition.size=6G/' -e 's/^hw.ramSize=.*/hw.ramSize=3G/' \
        -e '/^disk.dataPartition.path=/d' "$config" && rm -f "$config.bak"
}

start_avd() {
    say "Starting $AVD_NAME headless (log: $OUT/emulator.log)"
    nohup "$SDK/emulator/emulator" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
        > "$OUT/emulator.log" 2>&1 &
    local waited=0
    while [ -z "$(find_suite_emulator)" ]; do
        sleep 2; waited=$((waited + 2))
        [ $waited -lt $BOOT_TIMEOUT ] || die "$AVD_NAME did not come up within $BOOT_TIMEOUT s; see $OUT/emulator.log"
    done
}

mkdir -p "$OUT"
rm -rf "$OUT/witness" "$OUT"/*.txt "$OUT"/*.xml "$OUT"/*.md

SERIAL="${ANDROID_SERIAL:-}"
if [ -z "$SERIAL" ]; then
    SERIAL="$(find_suite_emulator)"
    if [ -z "$SERIAL" ] && [ $CI_MODE -eq 0 ] && [ $FORCE -eq 0 ]; then
        [ -d "$HOME/.android/avd/$AVD_NAME.avd" ] || create_avd
        start_avd
        SERIAL="$(find_suite_emulator)"
    fi
    if [ -z "$SERIAL" ]; then
        count=$(list_devices | wc -l | tr -d ' ')
        [ "$count" -eq 1 ] || die "no running $AVD_NAME among $count device(s); start it, or pick a device with ANDROID_SERIAL and --force"
        SERIAL="$(list_devices)"
    fi
fi
export ANDROID_SERIAL="$SERIAL"
adb() { "$ADB" -s "$SERIAL" "$@"; }

state=$("$ADB" -s "$SERIAL" get-state 2>&1 || true)
case "$state" in
    device) ;;
    *unauthorized*) die "$SERIAL is unauthorized: accept the USB debugging prompt on the device" ;;
    *offline*) die "$SERIAL is offline" ;;
    *) die "$SERIAL is not available: $state" ;;
esac

avd="$(avd_of "$SERIAL")"
if [ "$avd" != "$AVD_NAME" ] && [ $FORCE -eq 0 ]; then
    die "$SERIAL is ${avd:-not an emulator}, not $AVD_NAME. The suite overwrites the app's settings and deletes its Health Connect records; use --force only on a device without a setup worth keeping"
fi

waited=0
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2; waited=$((waited + 2))
    [ $waited -lt $BOOT_TIMEOUT ] || die "$SERIAL did not finish booting within $BOOT_TIMEOUT s"
done
sdk_level=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
[ "$sdk_level" -ge 34 ] || die "$SERIAL runs API $sdk_level; the suite needs 34 or higher (Health Connect in the platform)"
adb shell pm path com.google.android.healthconnect.controller >/dev/null 2>&1 \
    || adb shell pm path com.android.healthconnect.controller >/dev/null 2>&1 \
    || die "$SERIAL has no Health Connect controller; use a google_apis image"
say "Device $SERIAL (${avd:-physical}, API $sdk_level)"

# --- Broker -------------------------------------------------------------------------------------
connack() {
    python3 - "$1" <<'PY' 2>/dev/null
import socket, sys
try:
    s = socket.create_connection(("127.0.0.1", int(sys.argv[1])), 3)
    s.sendall(bytes([0x10, 0x0c, 0, 4]) + b"MQTT" + bytes([4, 2, 0, 60, 0, 0]))
    sys.exit(0 if s.recv(4) == bytes([0x20, 2, 0, 0]) else 1)
except OSError:
    sys.exit(1)
PY
}

command -v docker >/dev/null || die "docker is needed for the suite's MQTT broker"
docker info >/dev/null 2>&1 || die "docker is installed but its daemon is not running"
started_broker=0
if ! connack "$MQTT_HOST_PORT"; then
    say "Starting broker $MQTT_CONTAINER on host port $MQTT_HOST_PORT"
    docker run -d --rm --name "$MQTT_CONTAINER" -p "$MQTT_HOST_PORT:1883" eclipse-mosquitto:2 \
        mosquitto -c /mosquitto-no-auth.conf >/dev/null
    started_broker=1
    for _ in $(seq 1 30); do connack "$MQTT_HOST_PORT" && break; sleep 1; done
    connack "$MQTT_HOST_PORT" || die "the broker on host port $MQTT_HOST_PORT does not answer"
fi

old_policy=""
screen_prepared=0
cleanup() {
    set +e
    "$ADB" -s "$SERIAL" reverse --remove tcp:1883 >/dev/null 2>&1
    if [ -n "$old_policy" ]; then
        if [ "$old_policy" = "null" ]; then
            "$ADB" -s "$SERIAL" shell settings delete global hidden_api_policy >/dev/null 2>&1
        else
            "$ADB" -s "$SERIAL" shell settings put global hidden_api_policy "$old_policy" >/dev/null 2>&1
        fi
    fi
    if [ $screen_prepared -eq 1 ]; then
        "$ADB" -s "$SERIAL" shell svc power stayon false >/dev/null 2>&1
        "$ADB" -s "$SERIAL" shell locksettings set-disabled false >/dev/null 2>&1
    fi
    [ $started_broker -eq 1 ] && docker stop "$MQTT_CONTAINER" >/dev/null 2>&1
}
trap cleanup EXIT

adb reverse tcp:1883 "tcp:$MQTT_HOST_PORT" >/dev/null
probe=$(adb shell "(printf '\x10\x0c\x00\x04MQTT\x04\x02\x00\x3c\x00\x00'; sleep 1) | nc -w 3 127.0.0.1 1883 | od -An -tx1" | tr -d ' \r\n')
[ "$probe" = "20020000" ] || die "the device cannot reach the broker through adb reverse (got '$probe')"

# The permission rule calls a hidden Health Connect API; am instrument gets
# --no-hidden-api-checks, and this setting is the second line for the same thing.
old_policy=$(adb shell settings get global hidden_api_policy | tr -d '\r')
adb shell settings put global hidden_api_policy 1

# --- Build and install --------------------------------------------------------------------------
if [ $BUILD -eq 1 ]; then
    say "Building the app, the fixture and their test APKs"
    ./gradlew -q assembleDebug assembleDebugAndroidTest
fi
install() {
    [ -f "$1" ] || die "missing $1; build first (./gradlew assembleDebug assembleDebugAndroidTest)"
    local result
    result=$(adb install -r -t "$1" 2>&1) || true
    case "$result" in
        *Success*) ;;
        *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
            die "$1 is signed with another key than what is installed. Not uninstalling on your behalf: that would drop the app's data and Health Connect grants on $SERIAL. Uninstall it yourself if that is fine." ;;
        *) die "installing $1 failed: $result" ;;
    esac
}
say "Installing"
install app/build/outputs/apk/debug/app-debug.apk
install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
install hc-fixture/build/outputs/apk/debug/hc-fixture-debug.apk
install hc-fixture/build/outputs/apk/androidTest/debug/hc-fixture-debug-androidTest.apk

# Screen time and the failure notification; Health Connect is granted by the tests themselves.
adb shell appops set "$APP_ID" android:get_usage_stats allow
adb shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS

# The screen stays on and unlocked for the whole run. A fresh emulator boots to the lock
# screen, and screen time only counts an app that is really in front: behind the keyguard the
# Settings app that ScreenTimeUse opens never gets its minute (the first CI run, where the AVD
# is new every time). Put back at the end.
screen_prepared=1
adb shell svc power stayon true
adb shell locksettings set-disabled true >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard

# The fixture grants itself Health Connect access in an instrumentation of its own, where it
# has UiAutomation. Inside the suite it cannot: ForeignSourceTest starts it nested, and a
# nested instrumentation has none, so on a fresh emulator it had no way in.
#
# Then a write proves the access, by clearing what the fixture wrote before (a stopped run can
# leave records). A reinstalled fixture whose old records Health Connect kept is refused writes
# after its first grant, although the permissions show as granted; revoking one and granting
# it again settles that, so the script does that once before giving up.
fixture_run() {
    adb shell am instrument -w --no-hidden-api-checks -e class "$APP_ID.fixture.$1" \
        "$APP_ID.fixture.test/androidx.test.runner.AndroidJUnitRunner" 2>&1
}
fixture_ready() {
    fixture_run GrantForSuite > "$OUT/fixture-prepare.txt"
    grep -q 'OK (1 test)' "$OUT/fixture-prepare.txt" || return 1
    fixture_run ClearFixtureData >> "$OUT/fixture-prepare.txt"
    [ "$(grep -c 'OK (1 test)' "$OUT/fixture-prepare.txt")" -eq 2 ]
}
if ! fixture_ready; then
    adb shell pm revoke "$APP_ID.fixture" android.permission.health.WRITE_STEPS >/dev/null 2>&1
    fixture_ready || die "the fixture could not get Health Connect access; see $OUT/fixture-prepare.txt"
fi

# The app proves its own Health Connect access the same way, before the suite. Health Connect
# can refuse the app's first writes after a first grant too, and the cure, a revoke and a grant,
# kills the app's process: inside the suite that ended the whole run as "Process crashed".
app_ready() {
    adb shell am instrument -w --no-hidden-api-checks -e class "$APP_ID.smoke.GrantForSuite" \
        "$RUNNER" > "$OUT/app-prepare.txt" 2>&1
    grep -q 'OK (1 test)' "$OUT/app-prepare.txt"
}
if ! app_ready; then
    adb shell pm revoke "$APP_ID" android.permission.health.WRITE_STEPS >/dev/null 2>&1
    app_ready || die "the app could not get Health Connect write access; see $OUT/app-prepare.txt"
fi

# --- Run ----------------------------------------------------------------------------------------
summary="$OUT/summary.md"
status=0
if [ $SMOKE_ONLY -eq 0 ]; then
    say "Running${CLASS:+ $CLASS}${SIZE:+ ($SIZE tests)}"
    adb logcat -c
    adb shell rm -rf "/sdcard/Android/data/$APP_ID/files/instrumented"
    set -- -w -r --no-hidden-api-checks -e timeout_msec 180000
    if [ -n "$CLASS" ]; then
        set -- "$@" -e class "$CLASS"
    else
        # The preparation of the background run below is not a test of its own.
        set -- "$@" -e notPackage "$APP_ID.smoke"
    fi
    [ "$SIZE" = "small" ] && set -- "$@" -e notAnnotation androidx.test.filters.LargeTest
    suite_started=$(date +%s)
    adb shell am instrument "$@" "$RUNNER" | tee "$OUT/raw.txt" | grep -E '^(INSTRUMENTATION_STATUS: test=|INSTRUMENTATION_STATUS_CODE: -|FAILURES|OK \(|Time: )' || true
    suite_took=$(( $(date +%s) - suite_started ))

    adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
    adb pull "/sdcard/Android/data/$APP_ID/files/instrumented" "$OUT/witness" >/dev/null 2>&1 || true

    python3 scripts/instrument_to_junit.py "$OUT/raw.txt" "$OUT/junit.xml" --summary "$summary" || status=$?
    if [ $status -ne 0 ]; then
        adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 && adb pull /sdcard/window.xml "$OUT/window.xml" >/dev/null 2>&1 || true
    fi
    echo "The suite took ${suite_took} s on the device." | tee -a "$summary"
fi

# --- Background smoke run -----------------------------------------------------------------------
# The one check of the real background route, outside JUnit: under instrumentation the app
# counts as foreground, so no test above ever meets READ_HEALTH_DATA_IN_BACKGROUND. The fixture
# app seeds foreign records, BackgroundSmokeSetup points the app at a receiver on the host, the
# app's process is started without an activity so the real WorkManager plans the run, and
# JobScheduler is told to run that job now. The receiver must get the fixture's records.
smoke() {
    local port=18765 secret out job namespace line i
    secret="smoke-$(date +%s)"
    out="$OUT/smoke.jsonl"
    rm -f "$out"
    python3 scripts/webhook-receiver.py --port "$port" --secret "$secret" --out "$out" > "$OUT/smoke-receiver.log" 2>&1 &
    smoke_receiver=$!
    adb reverse "tcp:$port" "tcp:$port" >/dev/null
    adb shell am instrument -w --no-hidden-api-checks -e class "$APP_ID.fixture.SeedForSuite" \
        "$APP_ID.fixture.test/androidx.test.runner.AndroidJUnitRunner" > "$OUT/smoke-seed.txt"
    grep -q 'OK (1 test)' "$OUT/smoke-seed.txt" || { echo "the fixture did not seed; see smoke-seed.txt"; return 1; }
    adb shell am instrument -w -r --no-hidden-api-checks -e class "$APP_ID.smoke.BackgroundSmokeSetup" \
        -e smokeUrl "http://127.0.0.1:$port/health" -e smokeSecret "$secret" "$RUNNER" > "$OUT/smoke-setup.txt"
    grep -q 'OK (1 test)' "$OUT/smoke-setup.txt" || { echo "the setup failed; see smoke-setup.txt"; return 1; }
    # A broadcast to WorkManager's own diagnostics receiver (shell holds the DUMP permission it
    # asks for) starts the process without an activity and without a sync: the application
    # plans its timed run in the real WorkManager, and the process falls back to the background.
    adb shell am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS -n "$APP_ID/androidx.work.impl.diagnostics.DiagnosticsReceiver" >/dev/null
    # The diagnostics worker's own job comes and goes within a second; what stays is the run.
    sleep 5
    # WorkManager 2.10 and later keep their jobs in a namespace of their own on API 34+:
    # `JOB androidx.work.systemjobscheduler:u0a238/93: e4d328c @androidx.work.systemjobscheduler@<app>/...`
    # instead of `JOB #u0a238/93: e4d328c <app>/...`; `run` then needs that namespace too.
    job=""
    namespace=""
    for i in $(seq 1 30); do
        line=$(adb shell dumpsys jobscheduler | grep -E "^ +JOB (#|[^ :]+:)u[0-9a-z]+/[0-9]+: [0-9a-f]+ (@[^@ ]+@)?$APP_ID/androidx\.work" | head -1 || true)
        job=$(printf '%s' "$line" | sed -E 's#^ *JOB (\#|[^ :]+:)u[0-9a-z]+/([0-9]+):.*#\2#')
        namespace=$(printf '%s' "$line" | sed -nE 's#^ *JOB ([^ :#]+):u.*#\1#p')
        [ -n "$job" ] && break
        sleep 1
    done
    [ -n "$job" ] || { echo "no WorkManager job of the app in JobScheduler"; return 1; }
    uid=$(adb shell pm list packages -U "$APP_ID" | tr -d '\r' | sed -n "s/^package:$APP_ID uid:\([0-9]*\)$/\1/p")
    echo "running job $job with the app's uid in state $(adb shell cmd activity get-uid-state "$uid" | tr -d '\r')"
    adb shell cmd jobscheduler run -f ${namespace:+-n "$namespace"} "$APP_ID" "$job" > "$OUT/smoke-run.txt" 2>&1
    for i in $(seq 1 90); do
        [ -s "$out" ] && break
        sleep 1
    done
    python3 - "$out" "$APP_ID.fixture" <<'PY'
import json, sys
path, fixture = sys.argv[1], sys.argv[2]
try:
    posts = [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
except FileNotFoundError:
    posts = []
records = []
for post in posts:
    body = post["body"] if isinstance(post["body"], dict) else {}
    for key in ("steps", "weight"):
        records += [r for r in body.get(key, []) if isinstance(r, dict)]
foreign = [r for r in records if r.get("source") == fixture]
signed = all(p.get("signature_ok") for p in posts)
print(f"background run: {len(posts)} POST(s), {len(foreign)} record(s) of the fixture, signatures {'valid' if signed else 'INVALID'}")
sys.exit(0 if posts and signed and len(foreign) == 4 else 1)
PY
}
smoke_status=0
smoke_receiver=""
if [ -z "$CLASS" ] && [ $SMOKE -eq 1 ]; then
    say "Background smoke run"
    smoke_started=$(date +%s)
    # Not through a pipe: smoke() sets the receiver's pid for the kill below.
    smoke > "$OUT/smoke.txt" 2>&1 || smoke_status=1
    cat "$OUT/smoke.txt"
    grep -q '^background run: .*signatures valid' "$OUT/smoke.txt" || smoke_status=1
    [ -n "$smoke_receiver" ] && kill "$smoke_receiver" 2>/dev/null
    adb reverse --remove tcp:18765 >/dev/null 2>&1 || true
    adb shell am instrument -w --no-hidden-api-checks -e class "$APP_ID.fixture.ClearFixtureData" \
        "$APP_ID.fixture.test/androidx.test.runner.AndroidJUnitRunner" > "$OUT/smoke-clear.txt" 2>&1 || true
    grep -q 'OK (1 test)' "$OUT/smoke-clear.txt" || { echo "the fixture could not clear its records"; smoke_status=1; }
    result="passed"; [ $smoke_status -eq 0 ] || result="FAILED"
    echo "Background smoke run: $result in $(( $(date +%s) - smoke_started )) s ($(tail -1 "$OUT/smoke.txt"))" | tee -a "$summary"
    [ $smoke_status -eq 0 ] || status=1
fi
elapsed=$(( $(date +%s) - started_at ))
printf '\nScript took %d s. Reports in %s/\n' "$elapsed" "$OUT"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    cat "$summary" >> "$GITHUB_STEP_SUMMARY"
    echo "Script took ${elapsed} s." >> "$GITHUB_STEP_SUMMARY"
fi
exit $status
