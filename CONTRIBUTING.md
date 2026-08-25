# Contributing

Thanks for your interest in improving Life Dashboard Companion!

## Getting Started

1. Fork and clone the repository
2. Open the project in Android Studio, or build from the command line:

   ```bash
   ./gradlew assembleDebug
   ./gradlew installDebug   # install on a connected device
   ```

3. Health Connect features need a real device (or emulator) with the Health Connect app installed

## Development Guidelines

- **Run the tests** before opening a PR:

  ```bash
  ./gradlew testDebugUnitTest
  ```

- **Payload compatibility matters.** The JSON payload format is shared with the [iOS companion app](https://github.com/owen282000/life-dashboard-companion-app-ios); both apps feed the same backends. Changes to payload keys or value formats need a very good reason and matching documentation in the README.
- **Keep pure logic testable.** Sync logic that does not need Health Connect lives in small, dependency-free types (see `ResilientReadLogic`, `WebhookSupport`); follow that pattern so it stays unit-testable on the JVM.
- **Commit messages** follow the conventional style used in the history: `feat:`, `fix:`, `docs:`, `ci:`, `build:`, `test:`, `chore:`.

## Version tags

If you push version tags, enable the repo's git hooks once:

```bash
git config core.hooksPath .githooks
```

Tags must be strict semver (X.Y.Z) and higher than the previous tag; the app version is derived from them at build time.

## Opening a Pull Request

1. Create a feature branch (`git checkout -b feature/amazing-feature`)
2. Make your changes, with tests where it makes sense
3. Make sure the build and tests pass
4. Open a PR describing what changed and why

Small, focused PRs are much easier to review than big ones. When in doubt, open an issue first to discuss the direction.
