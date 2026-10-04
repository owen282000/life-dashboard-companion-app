# Building from source

How to build the app, run its tests and find your way around the code. To contribute a change, read [CONTRIBUTING.md](../CONTRIBUTING.md) as well. Cutting a release is in [releasing.md](releasing.md).

## Contents

- [Prerequisites](#prerequisites) - [Build](#build) - [Versions come from git tags](#versions-come-from-git-tags) - [Reproduce a release build](#reproduce-a-release-build)
- [Tech stack](#tech-stack) - [Project layout](#project-layout) - [Translations](#translations) - [Local test stack](#local-test-stack)
- [Instrumented tests](#instrumented-tests) - [Contributing](#contributing)

## Prerequisites

- **JDK 21.** CI and the release workflow build with Temurin 21 (`.github/workflows/build.yml` and `release.yml`). Android Studio's bundled JDK works if it is 21.
- **The Android SDK with platform 37**, the `compileSdk` in `app/build.gradle.kts`. Android Studio installs it when you open the project; from the command line use `sdkmanager "platforms;android-37"`.
- **Where the SDK is.** Either `sdk.dir=/path/to/Android/sdk` in `local.properties` at the repository root (Android Studio writes this file for you, and git ignores it), or the `ANDROID_HOME` environment variable.
- **git with the tags.** The app version comes from the latest git tag, see [Versions come from git tags](#versions-come-from-git-tags).

Gradle itself needs no install: `./gradlew` downloads the version the project uses.

## Build

```bash
git clone https://github.com/owen282000/life-dashboard-companion-app.git
cd life-dashboard-companion-app

./gradlew assembleDebug        # debug APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # build and install on a connected device
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew ktlintCheck          # code style, as CI runs it
./gradlew lintDebug            # Android Lint, as CI runs it
```

Health Connect features need a real device, or an emulator with a Google APIs system image of API 34 or newer, where Health Connect is built in.

**A debug build replaces the released app, or fails to.** Debug and release builds share the application ID `com.owen282000.lifedashboard`; there is no suffix for debug. A phone with the released APK installed refuses to install a debug build over it, because the two are signed with different keys (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Uninstall the released app first, which deletes its settings: export them under **About > Backup & restore** before you do. Health Connect keeps the data that other apps wrote.

## Versions come from git tags

The version is not written in any build file. `app/build.gradle.kts` runs `git describe` at build time:

- On a release tag such as `1.23.0`, the version name is the tag.
- Between tags it is the `git describe` form, such as `1.23.0-4-g1a2b3c4`, with `-dirty` when the working tree has changes.
- The version code is `major * 10000 + minor * 100 + patch` of the latest tag, so `1.23.0` is `12300`.

A clone without tags falls back. That happens with a fork made with GitHub's default "Copy the main branch only", and with a shallow clone (`--depth 1`). The build then reads `version.properties` and adds `-untagged` to the name (`1.23.0-untagged`), so such a build never passes for a release. Without that file it is `0.0.0-dev`. Get the tags with `git fetch --tags https://github.com/owen282000/life-dashboard-companion-app.git`, or `git fetch --unshallow --tags` for a shallow clone.

This repository's own CI fails instead of falling back: there a missing tag means the checkout was shallow.

## Reproduce a release build

Releases are built by the public [release workflow](../.github/workflows/release.yml) from the tag, with `./gradlew assembleRelease` on JDK 21. You can build the same APK yourself and check that the release contains nothing else:

```bash
git clone https://github.com/owen282000/life-dashboard-companion-app.git
cd life-dashboard-companion-app
git checkout 1.23.0
./gradlew assembleRelease
# unsigned: app/build/outputs/apk/release/app-release-unsigned.apk
```

Use a full clone, not a shallow one, so the version name is the tag. Then download `app-release.apk` from the [release](https://github.com/owen282000/life-dashboard-companion-app/releases) of the same tag. Your build has no signature. The signature sits in a block of the APK outside its files, so the files themselves can be compared one for one.

With [apksigcopier](https://github.com/obfusk/apksigcopier) and `apksigner` from the Android SDK build tools, copy the release's signature onto your build and verify it. The signature only verifies when every other byte is the same:

```bash
apksigcopier copy app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk copied.apk
apksigner verify copied.apk
```

Without extra tools, compare the contents. The APK holds resource files whose names differ only in case, so unzipping both on macOS or Windows overwrites files. Compare inside the archives instead:

```bash
python3 - app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk <<'EOF'
import hashlib, sys, zipfile
def entries(path):
    z = zipfile.ZipFile(path)
    return {i.filename: hashlib.sha256(z.read(i)).hexdigest() for i in z.infolist()}
a, b = entries(sys.argv[1]), entries(sys.argv[2])
print("identical" if a == b else sorted(k for k in a.keys() | b.keys() if a.get(k) != b.get(k)))
EOF
```

## Tech stack

Kotlin, with the UI in Jetpack Compose and Material 3. Health data comes from the Jetpack Health Connect client, screen time from Android's `UsageStatsManager`. Background syncs are WorkManager jobs. Webhooks go out through OkHttp, MQTT through the HiveMQ MQTT client, and JSON is kotlinx.serialization. The home screen widget is built with Glance, and the pairing scanner uses CameraX with ZXing for decoding. The versions are in `gradle/libs.versions.toml`.

## Project layout

| Path | Contents |
|---|---|
| `app/` | The Android app module: Compose UI, sync workers, webhook and MQTT delivery |
| `hc-fixture/` | A debug-only second Health Connect app: the week seeder and a foreign data source for the tests |
| `docs/` | Documentation, screenshots and the payload JSON Schema |
| `fastlane/metadata/` | Store listing text, screenshots and per-version release notes |
| `scripts/` | CI checks, the instrumented test runner, release helpers and `webhook-receiver.py` |
| `.github/workflows/` | Build, release, CodeQL, security and Scorecard workflows |
| `.githooks/` | Optional hook enforcing strict, increasing semver tags |

Pure sync logic lives in small, dependency-free types (`ResilientReadLogic`, `WebhookSupport`), so it stays unit-testable on the JVM, separate from the Health Connect and Android APIs.

## Translations

UI text lives in `app/src/main/res/values/strings.xml` and is referenced with `stringResource(R.string.…)`; counts use `<plurals>` and `pluralStringResource`. Translations sit in `values-<locale>/strings.xml`, currently Dutch (`values-nl`) and German (`values-de`).

Two rules keep translation possible:

- **Never build a sentence from translated fragments.** Use positional format arguments (`%1$s`) so a translator can reorder them.
- **Never hardcode user-facing text in Compose.** `scripts/check-hardcoded-strings.sh` fails the build when you do, and it runs in CI. Android's own `HardcodedText` lint only inspects XML layouts, so it sees nothing in a Compose UI.

A few files are still on that script's allowlist. Shrink it when you touch them; do not add to it.

Strings that are not UI text stay hardcoded on purpose: MQTT sensor names go to Home Assistant, and JSON keys, MQTT topics and HTTP headers are wire format. Translating either would break receivers.

## Local test stack

Two pieces of tooling make an emulator behave like a phone with a year of history and a Home Assistant next to it.

**Seeded Health Connect data, from another app.** The `:hc-fixture` module is a small debug-only app of its own (`com.owen282000.lifedashboard.fixture`, never released) that writes Health Connect records the way a watch or scale app would. Its instrumentation class `SeedWeek` inserts a deterministic week: hourly steps, distance and active calories, daily total calories, a heart rate sample every ten minutes, resting heart rate, weight, sleep with stages, and mindfulness sessions. Because the data comes from a different package, the app sees it exactly as it sees a real source: it is synced, and never mistaken for something the app wrote itself.

```bash
./gradlew :hc-fixture:assembleDebug :hc-fixture:assembleDebugAndroidTest
adb install -r -t hc-fixture/build/outputs/apk/debug/hc-fixture-debug.apk
adb install -r -t hc-fixture/build/outputs/apk/androidTest/debug/hc-fixture-debug-androidTest.apk
adb shell am instrument -w --no-hidden-api-checks -e class com.owen282000.lifedashboard.fixture.SeedWeek \
  com.owen282000.lifedashboard.fixture.test/androidx.test.runner.AndroidJUnitRunner
```

Reruns update the same records: each carries a client record ID per day and slot. The fixture grants itself its permissions through the same hidden call Health Connect's own dialog uses (hence `--no-hidden-api-checks`), which also registers it as a data source, so its records count in aggregates and the daily totals are real; without the flag it falls back to accepting the dialog. `adb shell pm grant` is not enough: inserts succeed, but the app is never registered as a data source, and the daily totals stay empty. `ClearFixtureData` in the same module deletes everything the fixture wrote (Health Connect keeps an uninstalled app's data). Never seed the suite's own AVD, see below.

**Broker and Home Assistant in Docker.** `scripts/dev/docker-compose.yml` starts a Mosquitto broker without authentication on port 1883 and a Home Assistant on port 8123, with its configuration under `scripts/dev/ha-config/` (ignored by git). In Home Assistant, add the **MQTT** integration with broker `mosquitto` and port 1883. Then point the app at `10.0.2.2` from an emulator, or at the laptop's LAN address from a phone, and the device appears under Settings > Devices & services > MQTT after the first sync.

```bash
docker compose -f scripts/dev/docker-compose.yml up -d
```

## Instrumented tests

`app/src/androidTest` holds a suite that runs the real sync path on an Android runtime: Health Connect, the payload, the signature, the webhook receiver, the outbox, and Receive with a stand-in for the Home Assistant integration. One command runs it:

```bash
scripts/instrumented.sh                      # build, install, run everything
scripts/instrumented.sh --class com.owen282000.lifedashboard.sync.OutboxTest
scripts/instrumented.sh --no-build           # reuse the APKs already built
scripts/instrumented.sh --small              # leave out the @LargeTest tests (real budgets, hangs)
scripts/instrumented.sh --smoke-only         # only the background smoke run
```

### Its own emulator

Every test starts by wiping the app's settings, logs, outbox and its own Health Connect records, so the script only runs on an AVD named `ldc-instrumented` and refuses any other device unless you pass `--force`. When the AVD is missing it is created from the newest installed system image with API 34 or higher (a `google_apis` or `google_apis_playstore` image, which carry Health Connect); when it is not running it is started headless. An emulator with a setup you care about is never touched. Do not seed `ldc-instrumented` with the fixture: every delivery test asserts that each record it put into Health Connect arrives exactly once and that nothing else does, so data from another app fails it (the tests say so, and `ClearFixtureData` removes it).

### What it needs

The Android SDK (the script uses the SDK's own `adb`, found through `ANDROID_HOME` or `sdk.dir` in `local.properties`, never the one on `PATH`), python3, and a running Docker: the script starts its own Mosquitto (`ldc-instrumented-mosquitto`, anonymous, host port 18830 or `LDC_MQTT_PORT`) and forwards it to the emulator's `127.0.0.1:1883` with `adb reverse`, then stops it again. A broker you already run on 1883 is left alone.

### How it runs

APKs are installed with `adb install -r -t` and the tests started with `am instrument`, not `connectedDebugAndroidTest`, which uninstalls the app afterward. The runner (`harness/LdTestRunner`) puts WorkManager in test mode before the app schedules anything, so no real sync worker runs next to a test. Health Connect permissions are granted by the tests themselves through the call Health Connect's dialog uses, which is hidden, so the script passes `--no-hidden-api-checks` and sets `hidden_api_policy` for the run.

### Reading the result

`adb`'s exit code says nothing about test results, so the output is parsed by `scripts/instrument_to_junit.py`. Everything lands in `build/instrumented/`: `summary.md` (one line per test), `junit.xml`, `raw.txt` (the instrumentation output), `logcat.txt`, and `witness/`, where a failed test leaves the requests the receiver got, schema errors and the like. In CI the same script runs in the `instrumented` job of `build.yml` on an API 36 emulator; the summary appears on the run page and the reports are uploaded as artifacts.

### What the suite covers

One class per part of the sync path: delivery and the conservation law (every record Health Connect holds arrives exactly once, nothing else), the outbox, deletions, backfill, Receive against a stand-in for the Home Assistant integration that checks and signs with code of its own, MQTT against the suite's Mosquitto, Screen Time, the logs, the settings backup, the scheduling chain, cancellation, two Compose screens, and `ForeignSourceTest`, which lets the `:hc-fixture` app write as another source during the test and removes its records again. A test that shows a bug the app still has is committed with `@Ignore("F<n>: ...")` and the behavior it should have; the fix removes the `@Ignore`. Tests that take real time (the 20 second budgets, calls that hang) carry `@LargeTest`: pull requests leave them out, a push to main runs everything.

### The background smoke run

Under instrumentation the app counts as in the foreground, so no test above meets Health Connect's rule for background reads. The script therefore ends with one check outside JUnit: the fixture seeds four foreign records, `BackgroundSmokeSetup` points the app at `scripts/webhook-receiver.py` on the host, a broadcast to WorkManager's diagnostics receiver starts the app's process without an activity so the real WorkManager plans a run, and `cmd jobscheduler run -f` runs that job while the app is in the background. The receiver must get exactly the fixture's records, signed.

### A hard check next to it

`scripts/check-cancellation.sh` runs in the build job and fails on a `catch (e: Exception)`, `catch (e: Throwable)` or `runCatching` in suspend code that does not let a `CancellationException` through first. Its allowlist, per file, is empty and stays that way: a new case is fixed, not listed. A swallowed cancellation makes a stopped worker log a failed delivery and carry on instead of stopping.

## Contributing

[CONTRIBUTING.md](../CONTRIBUTING.md) covers how to propose a change, what CI checks and what a pull request needs.
