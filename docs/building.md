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

## Release builds and R8

`assembleRelease` runs R8 with resource shrinking (`isMinifyEnabled` and `isShrinkResources` in `app/build.gradle.kts`). The keep rules live in `app/proguard-rules.pro`: the MQTT stack (HiveMQ client and the Netty it bundles) and kotlinx.serialization resolve classes reflectively, enum names are stored in preferences, and WorkManager's Room database is instantiated by name, so those are kept whole. The obfuscation map is written to `app/build/outputs/mapping/release/mapping.txt` for every release build; keep it next to a release if you want readable stack traces from that version.

Signing is unchanged: without a `KEYSTORE_PATH` the build produces `app-release-unsigned.apk`. For a quick device test of a release build, sign it with the debug key: `apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --out app-release-debugsigned.apk app/build/outputs/apk/release/app-release-unsigned.apk`.
