# Changelog

All notable changes to this project are documented in this file. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/). For older releases, see the [GitHub Releases](https://github.com/owen282000/life-dashboard-companion-app/releases).

## [Unreleased]

## [1.6.0] - 2026-08-26

### Added

- Record `uuid` on every payload record (stable Health Connect id) for server-side deduplication, matching the iOS companion app
- Local notification after repeated sync failures, with an in-app toggle and threshold (3/5/10)
- Home screen widget with last sync result and records delivered today
- Quick Settings tile to trigger an immediate sync
- Broadcast intent (`com.owen282000.lifedashboard.ACTION_SYNC`) so Tasker/MacroDroid can trigger syncs
- Send Test Ping button to verify webhook configuration without waiting for real data
- About screen easter eggs
- CodeQL analysis, OpenSSF Scorecard, and Android Lint in CI

### Changed

- Webhook headers and HMAC signing secrets moved from plain SharedPreferences to Keystore-backed EncryptedSharedPreferences, with silent migration

## [1.5.0] and earlier

See the [GitHub Releases](https://github.com/owen282000/life-dashboard-companion-app/releases) for full notes. Highlights: HMAC payload signing and smart retries (1.4.x), menstruation data types and resilient reads (1.3.x), payload pagination and bounded batches (1.2.x), initial Health Connect and Screen Time sync (1.0.0).

[Unreleased]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.6.0...HEAD
[1.6.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.5.0...1.6.0
