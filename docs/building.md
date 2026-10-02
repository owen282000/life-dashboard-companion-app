# Building and contributing

## Build

```bash
git clone https://github.com/owen282000/life-dashboard-companion-app.git
cd life-dashboard-companion-app

./gradlew assembleDebug        # debug APK
./gradlew installDebug         # install on a connected device
./gradlew testDebugUnitTest    # JVM unit tests
```

Health Connect features need a real device, or an emulator with the Health Connect app installed.

Release signing is described in [KEYSTORE_SETUP.md](KEYSTORE_SETUP.md). Releases are driven by semver tags; the app version is derived from the tag at build time.

The Google Play build is its own build type, `play`: the release build without the Ko-fi row in About, since that listing is paid. `./gradlew bundlePlay` makes the bundle for Play, with the same application id and signing key as the release build; the GitHub and F-Droid builds (`assembleRelease`) are unchanged.

## Releasing

1. Add a `## [X.Y.Z]` section to [CHANGELOG.md](../CHANGELOG.md)
2. Prepare the release files and commit them:

   ```bash
   scripts/prepare-release.sh 1.13.0
   ```

   This writes `version.properties` (the literal version F-Droid's update checker reads, since the Gradle build derives its version from the tag) and generates the store changelog for that version.

3. Tag the release (`git tag 1.13.0 && git push --tags`)

The tag triggers the release workflow, which builds and signs the APK, attests its provenance, and creates the GitHub release using the matching changelog section as its notes.

The tag fails to release when `version.properties` or the store changelog does not match it, so neither can silently drift. `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` is what F-Droid and Play show as release notes. The files are generated from `CHANGELOG.md` and committed, and the release workflow fails if the one for the tag being released is missing or stale, so store notes cannot silently drift from the changelog. The versionCode is `major * 10000 + minor * 100 + patch`, matching how `app/build.gradle.kts` derives it, and entries are trimmed on a line boundary to Play's 500-character limit with a link to the full changelog.

## Project layout

| Path | Contents |
|---|---|
| `app/` | The Android app module: Compose UI, sync workers, webhook and MQTT delivery |
| `hc-fixture/` | A debug-only second Health Connect app: the week seeder and a foreign data source for the tests |
| `docs/` | Documentation, screenshots and the payload JSON Schema |
| `fastlane/metadata/` | Play Store listing text and screenshots |
| `.github/workflows/` | Build, release, CodeQL, security and Scorecard workflows |
| `.githooks/` | Optional hook enforcing strict, increasing semver tags |

Pure sync logic lives in small, dependency-free types (`ResilientReadLogic`, `WebhookSupport`) so it stays unit-testable on the JVM, separate from the Health Connect and Android APIs.

## Translations

UI text lives in `app/src/main/res/values/strings.xml` and is referenced with `stringResource(R.string.…)`; counts use `<plurals>` and `pluralStringResource`. Translations sit in `values-<locale>/strings.xml`, currently Dutch (`values-nl`) and German (`values-de`).

Two rules keep translation possible:

- **Never concatenate a sentence from translated fragments.** Use positional format arguments (`%1$s`) so a translator can reorder them.
- **Never hardcode user-facing text in Compose.** `scripts/check-hardcoded-strings.sh` fails the build when you do, and it runs in CI. Android's own `HardcodedText` lint only inspects XML layouts, so it sees nothing in a Compose UI.

A few files are still on that script's allowlist. Shrink it when you touch them; do not add to it.

Strings that are not UI text stay hardcoded on purpose: MQTT sensor names go to Home Assistant, and JSON keys, MQTT topics and HTTP headers are wire format. Translating either would break receivers.

## Contributing

Contributions are welcome. [CONTRIBUTING.md](../CONTRIBUTING.md) has the full guidelines; the short version:

1. Fork the repository and create a feature branch (`git checkout -b feature/amazing-feature`)
2. Make your changes, with tests where it makes sense
3. Run `./gradlew testDebugUnitTest` and make sure the build passes
4. Open a Pull Request describing what changed and why

Two things to keep in mind:

- **Payload compatibility matters.** The JSON payload format is shared with the [iOS companion app](https://github.com/owen282000/life-dashboard-companion-app-ios); both feed the same backends. Changes to payload keys or value formats need a very good reason and matching updates to [webhook.md](webhook.md) and [webhook-schema.json](webhook-schema.json).
- **Commit messages** follow the conventional style used in the history: `feat:`, `fix:`, `docs:`, `ci:`, `build:`, `test:`, `chore:`.

Small, focused PRs are much easier to review than big ones. When in doubt, open an issue first to discuss the direction.

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

Reruns update the same records: each carries a client record id per day and slot. The fixture grants itself its permissions through the same hidden call Health Connect's own dialog uses (hence `--no-hidden-api-checks`), which also registers it as a data source, so its records count in aggregates and the daily totals are real; without the flag it falls back to accepting the dialog. `adb shell pm grant` is not enough: inserts succeed, but the app is never registered as a data source, and the daily totals stay empty. `ClearFixtureData` in the same module deletes everything the fixture wrote (Health Connect keeps an uninstalled app's data). Never seed the suite's own AVD, see below.

**Broker and Home Assistant in Docker.** `scripts/dev/docker-compose.yml` starts a Mosquitto broker without authentication on port 1883 and a Home Assistant on port 8123, with its configuration under `scripts/dev/ha-config/` (ignored by git). Point the app at `10.0.2.2` from an emulator, or at the laptop's LAN address from a phone, and the device appears under Settings > Devices & services > MQTT after the first sync.

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

**Its own emulator.** Every test starts by wiping the app's settings, logs, outbox and its own Health Connect records, so the script only runs on an AVD named `ldc-instrumented` and refuses any other device unless you pass `--force`. When the AVD is missing it is created from the newest installed system image with API 34 or higher (a `google_apis` or `google_apis_playstore` image, which carry Health Connect); when it is not running it is started headless. An emulator with a setup you care about is never touched. Do not seed `ldc-instrumented` with the fixture: every delivery test asserts that each record it put into Health Connect arrives exactly once and that nothing else does, so data from another app fails it (the tests say so, and `ClearFixtureData` removes it).

**What it needs.** The Android SDK (the script uses the SDK's own `adb`, found through `ANDROID_HOME` or `sdk.dir` in `local.properties`, never the one on `PATH`), python3, and a running Docker: the script starts its own Mosquitto (`ldc-instrumented-mosquitto`, anonymous, host port 18830 or `LDC_MQTT_PORT`) and forwards it to the emulator's `127.0.0.1:1883` with `adb reverse`, then stops it again. A broker you already run on 1883 is left alone.

**How it runs.** APKs are installed with `adb install -r -t` and the tests started with `am instrument`, not `connectedDebugAndroidTest`, which uninstalls the app afterwards. The runner (`harness/LdTestRunner`) puts WorkManager in test mode before the app schedules anything, so no real sync worker runs next to a test. Health Connect permissions are granted by the tests themselves through the call Health Connect's dialog uses, which is hidden, so the script passes `--no-hidden-api-checks` and sets `hidden_api_policy` for the run.

**Reading the result.** `adb`'s exit code says nothing about test results, so the output is parsed by `scripts/instrument_to_junit.py`. Everything lands in `build/instrumented/`: `summary.md` (one line per test), `junit.xml`, `raw.txt` (the instrumentation output), `logcat.txt`, and `witness/`, where a failed test leaves the requests the receiver got, schema errors and the like. In CI the same script runs in the `instrumented` job of `build.yml` on an API 36 emulator; the summary appears on the run page and the reports are uploaded as artifacts.

**What the suite covers.** One class per part of the sync path: delivery and the conservation law (every record Health Connect holds arrives exactly once, nothing else), the outbox, deletions, backfill, Receive against a stand-in for the Home Assistant integration that checks and signs with code of its own, MQTT against the suite's mosquitto, Screen Time, the logs, the settings backup, the scheduling chain, cancellation, two Compose screens, and `ForeignSourceTest`, which lets the `:hc-fixture` app write as another source during the test and removes its records again. A test that shows a bug the app still has is committed with `@Ignore("F<n>: ...")` and the behaviour it should have; the fix removes the `@Ignore`. Tests that pay real time (the 20 second budgets, calls that hang) carry `@LargeTest`: pull requests leave them out, a push to main runs everything.

**The background smoke run.** Under instrumentation the app counts as in the foreground, so no test above meets Health Connect's rule for background reads. The script therefore ends with one check outside JUnit: the fixture seeds four foreign records, `BackgroundSmokeSetup` points the app at `scripts/webhook-receiver.py` on the host, a broadcast to WorkManager's diagnostics receiver starts the app's process without an activity so the real WorkManager plans a run, and `cmd jobscheduler run -f` runs that job while the app is in the background. The receiver must get exactly the fixture's records, signed.

**A hard check next to it.** `scripts/check-cancellation.sh` runs in the build job and fails on a `catch (e: Exception)`, `catch (e: Throwable)` or `runCatching` in suspend code that does not let a `CancellationException` through first. Its allowlist, per file, is empty and stays that way: a new case is fixed, not listed. A swallowed cancellation makes a stopped worker log a failed delivery and carry on instead of stopping.

## Release builds and R8

`assembleRelease` runs R8 with resource shrinking (`isMinifyEnabled` and `isShrinkResources` in `app/build.gradle.kts`). The keep rules live in `app/proguard-rules.pro`: the MQTT stack (HiveMQ client and the Netty it bundles) and kotlinx.serialization resolve classes reflectively, enum names are stored in preferences, and WorkManager's Room database is instantiated by name, so those are kept whole. The obfuscation map is written to `app/build/outputs/mapping/release/mapping.txt` for every release build; keep it next to a release if you want readable stack traces from that version.

Signing is unchanged: without a `KEYSTORE_PATH` the build produces `app-release-unsigned.apk`. For a quick device test of a release build, sign it with the debug key: `apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --out app-release-debugsigned.apk app/build/outputs/apk/release/app-release-unsigned.apk`.
