## What does this PR do?

<!-- A short description of the change and the motivation behind it. -->

## Checklist

- [ ] Unit tests pass (`./gradlew testDebugUnitTest`) and lint is clean (`./gradlew lintDebug`)
- [ ] User-facing text lives in `strings.xml`, not in code or the manifest
      (`scripts/check-hardcoded-strings.sh` enforces this)
- [ ] An entry under `## [Unreleased]` in `CHANGELOG.md`, unless this changes nothing a user
      would notice
- [ ] Payload format unchanged, or [docs/webhook.md](../docs/webhook.md) and
      [docs/webhook-schema.json](../docs/webhook-schema.json) updated to match. The schema is
      shared with the iOS app, so changes there need a good reason
- [ ] No secrets or health data in logs
- [ ] Documentation updated if behaviour or configuration changed
