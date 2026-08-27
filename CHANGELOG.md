# Changelog

All notable changes to this project are documented in this file. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/). For older releases, see the [GitHub Releases](https://github.com/owen282000/life-dashboard-companion-app/releases).

## [Unreleased]

## [1.8.0] - 2026-08-27

### Added

- MQTT publishing with Home Assistant Discovery: every enabled data type appears automatically as a sensor in Home Assistant
- Store-and-forward outbox: failed webhook deliveries are queued on disk and drained on the next sync, so no data is lost when the receiver is down
- Historical backfill of up to a year of data (30/90/365 days) in small windows with progress feedback
- Optional deduplicated daily totals (steps, distance, calories) in the payload via Health Connect's aggregate API
- At-a-glance dashboard card on the Health screen: records today, lifetime records, last sync status, and a 7-day steps sparkline
- Published JSON Schema for the webhook payload (`docs/webhook-schema.json`)
- Ready-made self-hosted receiving stack (Postgres, receiver, Grafana) linked from the README

### Fixed

- Sync watermark now uses `lastModifiedTime` instead of the record timestamp, so records backfilled by watch apps are never skipped
- Data preview now also shows the daily totals when enabled

## [1.7.0] - 2026-08-27

### Added

- 8 new data types (33 total): basal metabolic rate, VO2 max, skin temperature, basal body temperature, intermenstrual bleeding, ovulation test, cervical mucus, and sexual activity
- Fastlane metadata and privacy policy for F-Droid/IzzyOnDroid distribution
- OpenSSF Best Practices passing badge

## [1.6.1] - 2026-08-26

### Added

- Build provenance attestation published with every release (verifiable via `gh attestation verify`)

### Changed

- Gradle wrapper checksum validation in CI and dependency updates (AGP 9.3.2, OkHttp 5.5.0, Gradle 9.7.1)

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
