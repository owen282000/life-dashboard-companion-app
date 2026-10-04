# Contributing

Bug fixes, translations, documentation and new features are all welcome. Issues labeled [good first issue](https://github.com/owen282000/life-dashboard-companion-app/labels/good%20first%20issue) are the place to start, if there are any open; a translation or a correction to a brand page also makes a fine first pull request. For anything bigger, open an issue first to agree on the direction before you write the code.

## Getting started

1. Fork and clone the repository. Uncheck "Copy the main branch only" when you fork, so the clone gets the Git tags that the version number is built from ([why](docs/building.md#versions-come-from-git-tags)).
2. Install JDK 21 and the Android SDK; [docs/building.md](docs/building.md#prerequisites) lists what the build needs.
3. Open the project in Android Studio, or build from the command line:

   ```bash
   ./gradlew assembleDebug
   ./gradlew installDebug   # install on a connected device
   ```

Health Connect features need a real device, or an emulator with a Google APIs image of API 34 or newer. A debug build can't be installed over the released app, because the two are signed with different keys; see [docs/building.md](docs/building.md#build).

## Before you open a pull request

CI runs these on every pull request, so run them first:

```bash
./gradlew ktlintCheck          # code style
./gradlew testDebugUnitTest    # unit tests
./gradlew lintDebug            # Android Lint
```

The build also runs `scripts/check-hardcoded-strings.sh` and `scripts/check-cancellation.sh`, described below.

## Guidelines

- **Changes to the sync path need the instrumented suite.** `scripts/instrumented.sh` runs the real sync on an emulator of its own: Health Connect, the webhook, the outbox, MQTT and Receive (see [docs/building.md](docs/building.md#instrumented-tests)). CI runs it on every pull request, leaving out the slow `@LargeTest` tests, which run on main. A bug fix comes with a test that fails without the fix.
- **Never swallow a cancellation.** In suspend code, a `catch (e: Exception)` or `runCatching` must let `CancellationException` through first; `scripts/check-cancellation.sh` fails the build otherwise.
- **Payload compatibility matters.** The JSON payload format is shared with the [iOS app](https://github.com/owen282000/life-dashboard-companion-app-ios); both apps feed the same backends. Changes to payload keys or value formats need a very good reason and matching updates to [docs/webhook.md](docs/webhook.md) and [docs/webhook-schema.json](docs/webhook-schema.json).
- **UI text belongs in `strings.xml`.** Use `stringResource(R.string.…)` and positional format arguments rather than building sentences from pieces; `scripts/check-hardcoded-strings.sh` fails the build on hardcoded text. Translations are welcome as a new `values-<locale>/strings.xml`; [docs/building.md](docs/building.md#translations) has the rules.
- **Keep pure logic testable.** Sync logic that doesn't need Health Connect lives in small, dependency-free types (see `ResilientReadLogic`, `WebhookSupport`); follow that pattern so it stays unit-testable on the JVM.
- **AI assistance is fine; say so in the PR.** The rules are in [AI_POLICY.md](AI_POLICY.md): disclose the tool, understand and test what you submit, no unreviewed agent output.

Anything a user would notice gets a line under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md); the pull request template asks for it. Commit messages follow the conventional style used in the history: `feat:`, `fix:`, `docs:`, `ci:`, `build:`, `test:`, `chore:`.

## Opening a pull request

1. Create a branch (`git checkout -b fix/short-description`).
2. Make your change, with tests where it makes sense.
3. Run the checks above.
4. Open a pull request that says what changed and why, and fill in the template's checklist.

Small, focused pull requests are much easier to review than big ones.

Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md).
