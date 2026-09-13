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
2. Generate the store changelogs and commit them:

   ```bash
   scripts/generate-fastlane-changelogs.sh        # all versions
   scripts/generate-fastlane-changelogs.sh 1.12.0 # just one
   ```

3. Tag the release (`git tag 1.12.0 && git push --tags`)

The tag triggers the release workflow, which builds and signs the APK, attests its provenance, and creates the GitHub release using the matching changelog section as its notes.

`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` is what F-Droid, IzzyOnDroid and Play show as release notes. The files are generated from `CHANGELOG.md` and committed, and the release workflow fails if the one for the tag being released is missing or stale, so store notes cannot silently drift from the changelog. The versionCode is `major * 10000 + minor * 100 + patch`, matching how `app/build.gradle.kts` derives it, and entries are trimmed on a line boundary to Play's 500-character limit with a link to the full changelog.

## Project layout

| Path | Contents |
|---|---|
| `app/` | The Android app module: Compose UI, sync workers, webhook and MQTT delivery |
| `docs/` | Documentation, screenshots and the payload JSON Schema |
| `fastlane/metadata/` | Play Store listing text and screenshots |
| `.github/workflows/` | Build, release, CodeQL, security and Scorecard workflows |
| `.githooks/` | Optional hook enforcing strict, increasing semver tags |

Pure sync logic lives in small, dependency-free types (`ResilientReadLogic`, `WebhookSupport`) so it stays unit-testable on the JVM, separate from the Health Connect and Android APIs.

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
