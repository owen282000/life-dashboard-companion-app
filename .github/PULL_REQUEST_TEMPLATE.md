## What does this PR do?

<!-- What changes, and why. Link the issue it closes, if there is one. -->

## How was it tested?

<!-- Unit tests, the instrumented suite, a real phone or an emulator, against which destination. -->

## Checklist

- [ ] Unit tests pass (`./gradlew testDebugUnitTest`), and ktlint (`./gradlew ktlintCheck`) and lint (`./gradlew lintDebug`) are clean
- [ ] A change to the sync path passes the instrumented suite (`scripts/instrumented.sh`, see [CONTRIBUTING.md](https://github.com/owen282000/life-dashboard-companion-app/blob/main/CONTRIBUTING.md)), and a bug fix comes with a test that fails without the fix
- [ ] User-facing text lives in `strings.xml`, not in code or the manifest (`scripts/check-hardcoded-strings.sh` enforces this)
- [ ] An entry under `## [Unreleased]` in `CHANGELOG.md`, unless this changes nothing a user would notice
- [ ] Payload format unchanged, or [docs/webhook.md](https://github.com/owen282000/life-dashboard-companion-app/blob/main/docs/webhook.md) and [docs/webhook-schema.json](https://github.com/owen282000/life-dashboard-companion-app/blob/main/docs/webhook-schema.json) updated to match. The schema is shared with the iOS app, so a change there needs a good reason
- [ ] No secrets or health data in logs
- [ ] Documentation updated if behavior or configuration changed
- [ ] AI assistance, if any, is named in the description above ([AI_POLICY.md](https://github.com/owen282000/life-dashboard-companion-app/blob/main/AI_POLICY.md))
