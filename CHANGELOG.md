# Changelog

All notable changes to this project are documented in this file. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/). For older releases, see the [GitHub Releases](https://github.com/owen282000/life-dashboard-companion-app/releases).

## [Unreleased]

## [1.18.0] - 2026-09-18

### Added

- Deleting a record in Health Connect now reaches your webhook. The app follows Health
  Connect's own change tracking and names the records that are gone in a
  `deleted_records` list, so a receiver can drop exactly those. Apps that edit by
  replacing, such as Cronometer, previously left both the old and the new record on the
  receiver ([#61](https://github.com/owen282000/life-dashboard-companion-app/issues/61)).
- Types whose deletion tracking was interrupted, which happens after 30 days without a
  sync, are named in `deletions_unavailable`. Reconcile those against a backfill window
  instead of the incremental payload.
- The last payload of a backfill window carries `window_complete`, which makes the
  window a snapshot: a receiver may treat records it holds in that range that were not
  in the window as deleted. An empty window now sends one payload as well, so an empty
  window is distinguishable from an unreported one.
- Every Health Connect payload carries a `sequence` counter that only goes up, so a
  retry that arrives after a newer payload can be recognised as stale instead of undoing
  it. Previewing data does not take a number: looking is not sending.
- A sync whose only change is a deletion, which is what removing a meal without adding
  one looks like, now sends a payload carrying the deletion and no records, and reports
  it like any other delivery instead of saying there was no new data. Deletions are kept
  until a payload has actually been delivered or stored in the outbox, so a sync that
  finds nothing to send, or one that is interrupted, hands them to the next sync instead
  of losing them.

## [1.17.1] - 2026-09-16

### Fixed

- A backfill sent the daily totals only in the first payload of each window, so a
  receiver saw the later chunks of a busy window as a window without totals and warned
  about it. Every payload of a window now carries them; the figures are identical, and
  a receiver that keeps history ignores a repeat.

## [1.17.0] - 2026-09-16

### Added

- A backfill now carries the daily totals for every day it covers, in the first
  payload of each window. A receiver that keeps history, such as the Home Assistant
  integration from 0.3.0, gets each past day's real step, distance and calorie total
  from Health Connect's own deduplicated figures, instead of only today's. A day cut
  by a window boundary is sent by both windows with the same numbers.

## [1.16.4] - 2026-09-16

### Fixed

- The scanner could not read the pairing code Home Assistant shows in its dark theme, which draws light modules on a dark card. The decoder read dark-on-light only; it now tries both. This, not distance or resolution, is why the phone's own camera app read the same code instantly

## [1.16.3] - 2026-09-16

### Fixed

- The scanner still read nothing where the phone's own camera app read the same code instantly. Measured against blurred frames rather than guessed at: what decides it is how much of the frame the code covers, not the resolution and not the length of the code. A code filling a quarter of the frame is unreadable at any resolution; one filling most of it survives several pixels of camera softness
- The scanner now shows an outline for the code to fill and says that closer is better, pins autofocus to the middle of the frame rather than letting it settle on the text under the code, applies a modest zoom, and analyses at 960p

## [1.16.2] - 2026-09-16

### Fixed

- The scanner in the app analysed camera frames at 640x480, which is not enough to read a pairing code through the softness of a hand-held camera: the phone's own camera app reads the same code instantly because it works at full resolution. Frames are now analysed at 720p, which roughly doubles the blur the code survives

## [1.16.1] - 2026-09-15

### Fixed

- The scanner in the app found nothing while the phone's own camera read the same pairing code instantly. A camera buffer routinely ends before the padding of its last row, and the decoder demanded a full padded rectangle, so every real frame was discarded before it could be read

## [1.16.0] - 2026-09-15

### Added

- **Pairing by QR code.** The Life Dashboard integration for Home Assistant shows a code; point the phone's camera at it and the app opens with the address and the signing secret ready to confirm. The link is an Android App Link verified against the release signing certificate, so the browser is skipped entirely and no other app can claim it. Without the app installed the code opens a page that says where to get it
- **A scanner in the app**, on the Webhook card of both tabs and as the first option in the setup wizard, for a code on a screen you are already looking at. CameraX with ZXing, no Play Services: the APK still carries none. The camera permission is asked for at the moment of scanning, never at startup, and the camera is released as soon as the scanner closes
- One confirmation dialog for every route in: it names the receiver and its host, offers the sections the receiver accepts, says when a section's existing secret will be replaced, and turns on plain HTTP only when the address needs it and you agree. Nothing is written until you tap **Pair**, and a scanned code never decides which data types are synced or when
- Pairing appends the address rather than replacing the list, so a second receiver you configured by hand survives

### Changed

- The setup wizard stores a signing secret, which it never did: it wrote addresses only, so a scanned secret would have been dropped on a first run
- On a webhook card with no receiver yet, scanning leads: a full-width button above the address field. Once one exists the button is gone and the scanner is a QR icon inside the field, so the card is no taller than before

## [1.15.0] - 2026-09-15

### Added

- A **Generate** button next to the HMAC signing secret: 32 bytes from SecureRandom, hex encoded, so nobody has to invent a passphrase. Copy puts it on the clipboard flagged as sensitive on Android 13 and later

### Changed

- The Screen Time day boundary is chosen with a clock instead of typed as a number, shown in the device's own 12 or 24 hour format. Stored exactly as before, as a whole hour
- Three labels that were still English for Dutch and German users (the logs and about screens in the app switcher, the Quick Settings tile) are translated
- The plain HTTP switch says plainly that it covers both tabs and leaves MQTT alone

## [1.14.0] - 2026-09-14

### Added

- Sync at fixed times of day per tab, such as 08:00 and 21:00, so the night's sleep reaches Home Assistant before you get up. With an optional weekday filter and quiet hours, next to the interval the app has always had
- Data resolution per type: send dense series (heart rate, HRV, steps, calories and five more) as one value per 1, 5 or 15 minutes, or per hour, instead of every record. Measured values are averaged with their min and max, quantities are summed. Defaults to every record, so existing receivers are unaffected
- Both settings travel with the settings backup

Details on the scheduling rules and the bucketed payload shape are in [docs/features.md](docs/features.md) and [docs/webhook.md](docs/webhook.md#data-resolution).

### Changed

- The line under the save button reports the real schedule and destination ("At 08:00, 21:00 to MQTT") instead of assuming an interval and ignoring MQTT

## [1.13.4] - 2026-09-14

### Fixed

- MQTT states for weight, temperatures, percentages and other decimal values are rounded to one or two decimals instead of the raw double (`78.2006048685296`)
- Home Assistant forced two decimals on distance, weight and duration sensors (`5,921.00 m`); the discovery config now sets `suggested_display_precision` to match the state

### Added

- Emulator seeder for a week of Health Connect data, and a Docker compose file with Mosquitto and Home Assistant (docs/building.md)
- A screenshot of the Home Assistant device in the README and a two-minute "phone to Home Assistant" quickstart in docs/usage.md

## [1.13.3] - 2026-09-14

### Changed

- Home Assistant sensors for steps, distance, active and total calories now carry today's total from the deduplicated daily aggregate instead of the last record. "Steps (latest record): 7 steps" was true and useless; "Steps Today: 6,412" is what a dashboard wants. Their entity ids change accordingly (`steps_today` and so on); the old `steps`, `distance`, `active_calories` and `total_calories` sensors are removed from the broker and from Home Assistant on the next publish
- Every MQTT publish sends the full set of sensors the app has mapped so far, not only the types that had new records in that sync. Pointing the app at a new broker, or adding Home Assistant later, now shows the whole device after one sync instead of one sensor at a time

### Fixed

- A backfill now counts on the Health Connect dashboard: "today" and "last sync" said nothing while thousands of records went out, because only regular syncs recorded the status

## [1.13.2] - 2026-09-14

### Added

- `scripts/webhook-receiver.py`: a zero-dependency receiver for a laptop on the same network that prints every POST the app sends, appends it to a JSON Lines file and can verify the `X-Signature` header. The quickest way to see what the app sends before building a real receiver

### Fixed

- Screen time payloads named an app the phone would not let us look up after the last segment of its package, so `org.wakingup.android` became "android". The fallback now skips generic segments and picks the one that says something: `wakingup`
- The Health Connect dashboard could show more records today than in its lifetime: "today" counted every sync including screen time apps, "lifetime" only webhook deliveries. Both counters are now kept per source, so the Health tab counts Health Connect records only, and MQTT-only syncs count too. The widget keeps the app-wide numbers; per-source lifetime totals start counting from this version

## [1.13.1] - 2026-09-14

### Fixed

- A Health Connect sync with MQTT as the only destination failed with "No webhook URLs configured". The wizard and the tabs have accepted MQTT alone since 1.13.0, and Screen Time already synced that way; the Health Connect sync manager still insisted on a webhook URL. It now reads and publishes without one, exactly like Screen Time
- Backfill on an MQTT-only setup failed with the same message. Backfill posts history to webhooks and MQTT only carries the latest value of each type, so the button now says exactly that instead of opening the dialog

## [1.13.0] - 2026-09-14

### Added

- A first-run wizard. A fresh install used to open on the Health tab with 33 toggles and no destination; the wizard now asks what to sync (Health Connect, Screen Time or both), where the data should go (a webhook URL with a test ping, an MQTT broker, or both, applied only to the sources you picked) and which health data types to start with (the essentials, all 33, or none yet), then ends with a summary and the two permissions that remain. Every choice stays editable on the tabs, the whole thing can be skipped, and it is available in English, Dutch and German
- The wizard shows the plain-HTTP opt-in switch as soon as an `http://` URL is typed, so a receiver on the LAN can be tested from the first screen instead of failing with a pointer to the logs
- MQTT publishes now appear in the Logs tab next to webhook deliveries, with the broker, sensor count and the error when the broker could not be reached. Until now a failing broker was only visible as a one-line status inside the MQTT settings
- A test ping on the Screen Time tab, which has its own webhook URLs but had no way to test them
- The failure-notification setting is reachable from both tabs; it was always app-wide but only shown on Health Connect
- `PRIVACY.md`, a plain-language privacy policy, linked from the About screen together with the documentation, the changelog of the running version, the issue tracker and the licence

### Changed

- The three tabs and the About screen share one design: a coloured status banner per tab (green Health Connect, purple Screen Time, blue Logs) with the permission state and one action in it, a stat card, settings grouped in cards of icon rows with the webhook and MQTT destinations side by side, one sync button with the secondary actions as tiles, and the About hero on the brand's dark ground with the mark. The red "permissions required" card is gone; the banner says it instead. Logs rows show the destination as an icon and the outcome as one word, and the log filter is a segmented control
- The two sync tabs are backed by view models (`HealthConnectViewModel`, `ScreenTimeViewModel`) exposing `StateFlow`, with the screens as pure functions of state and callbacks and the shared sections (webhook, MQTT, notifications, data types) as separate composables. One `PreferencesManager` is shared through the Application instead of being constructed per screen. The models are unit tested against in-memory fakes: validation, change tracking, sync outcomes and permission flows
- A destination is now either a webhook URL or MQTT: saving and syncing no longer insist on a webhook URL when MQTT is enabled
- The release build is minified with R8 and resource shrinking, which takes the APK from 19 MB to about 4 MB. Keep rules cover HiveMQ/Netty, kotlinx.serialization, enum names stored in preferences and WorkManager's Room database; `mapping.txt` is kept with the build outputs
- The About screen is fully translated (English, Dutch, German); it was the last screen with hardcoded English
- The Screen Time dashboard shows the icon of today's most used app instead of its package name
- `docs/features.md` compares the app with the Home Assistant companion app's Health Connect sensors on verifiable points (data types, history window, Android versions, screen time), each checked against the companion app's documentation and issue tracker on 14 September 2026. The README links to it

## [1.12.2] - 2026-09-14

### Fixed

- Reproducible builds: the version name is now the exact tag whenever HEAD sits on one, regardless of the state of the working tree. F-Droid's builder modifies the tree before building (it strips signing configs and removes the Gradle wrapper jar), which made `git describe --dirty` stamp `1.12.1-dirty` into the manifest while the released APK says `1.12.1`; that one word was the only difference between the two builds and failed the verification

### Changed

- The APK no longer carries Google's dependency-info block, a dependency-tree blob in the signing block encrypted with a Google public key that only Google can read. IzzyOnDroid's scanner flagged it and F-Droid checks for the same; it served no purpose outside Google Play
- The brand mark gained broadcast arcs: the pulse ends in a dot that radiates the signal, chosen from three explored directions because measuring AND publishing is what the app does. Applied to the launcher icon, the store icon, the feature graphic and the repository banner, all rendered from the sources in `docs/brand/`

## [1.12.1] - 2026-09-14

### Added

- `version.properties` with the literal version name and code, generated by `scripts/prepare-release.sh` and verified against the tag by the release workflow. F-Droid's update checker cannot evaluate a Gradle build that derives its version from git tags, so this file is what lets F-Droid detect new releases automatically
- Store listing assets for IzzyOnDroid and F-Droid: a 512x512 icon and a 1024x500 feature graphic in the banner's visual identity, plus four fresh screenshots showing real syncs. The sources live in `docs/brand/` as HTML and render with headless Chrome, the same way the repository banner was made, so they can be regenerated instead of only existing as pixels
- The repository banner itself is regenerated from a committed source (`docs/brand/banner.html`) in the same identity

### Changed

- The launcher icon is now the brand's heartbeat mark on the dark ground from the banner, replacing the Android Studio template robot that had shipped since the first commit. The adaptive icon carries a monochrome layer for themed icons, and the legacy density PNGs are rendered from the same source
- The README shows four screenshots instead of three, adding the MQTT and sync-actions view
- The store description now leads with MQTT and Home Assistant Discovery, which it previously did not mention at all, and covers the outbox, settings backup, backfill and the three interface languages

## [1.12.0] - 2026-09-13

### Added

- Settings backup and restore under About: export every webhook URL, header, signing secret, MQTT broker and toggle as a JSON file and import it on another device. Exports that carry secrets are encrypted with a password (AES-256-GCM, PBKDF2-HMAC-SHA256); a secret-free export can be shared without handing over access. Importing shows a preview of what will be replaced first, and sync watermarks and logs are deliberately left out. Documented in `docs/settings-backup.md`
- Per-version release notes for F-Droid, IzzyOnDroid and Play under `fastlane/metadata/android/en-US/changelogs/`, generated from `CHANGELOG.md` by `scripts/generate-fastlane-changelogs.sh`. The release workflow fails when the file for the tag being released is missing or stale, so store notes cannot drift from the changelog

### Changed

- Dutch and German translations of the whole interface. Android picks them up from the system language; other locales fall back to English, and translations for more are welcome as a pull request
- Every user-facing string on the Health Connect, Screen Time, Logs and MQTT screens now lives in `strings.xml`, so the app can be translated. Counts use plurals rather than a hardcoded "(s)", and sentences are built with format arguments instead of concatenation. MQTT sensor names stay English on purpose: they are published to Home Assistant. A CI check fails the build on new hardcoded UI text, since Android's own lint only inspects XML layouts and cannot see Compose
- Webhook logs moved out of the main settings file into their own store, with raw payloads kept as separate files. Retention is now capped by total size (5 MB) as well as entry count, so a run of large payloads can no longer grow storage without bound; previously 100 busy syncs could retain roughly 25 MB in a single value that was rewritten on every delivery
- Raw payloads are truncated to 16 KB by default, with a "Keep full payloads" switch on the logs screen for debugging. Payloads are raw health data, so they are also excluded from backup
- Webhook delivery and log writing now run on the IO dispatcher; the test ping previously did both on the main thread
- `targetSdk` raised to 36 (Android 16), which Google Play requires for new apps and updates from 31 August 2026

### Fixed

- Section headings no longer collide with the label beside them when a translation is long: "Sync Interval" is 13 characters in English but 26 in German, and the title and subtitle overlapped

### Security

- Webhook auth headers, HMAC signing secrets and MQTT credentials are excluded from Android cloud backup and device transfer. They are stored with a key that never leaves the device, so a restored copy could not be decrypted anyway; excluding them also removes any chance of a keystore outage shipping them off-device
- A keystore outage no longer falls back to plain, backup-eligible storage. Secrets are now kept in memory for that process only, so they are never written unencrypted; the affected screens show a banner explaining why saved credentials are temporarily unavailable

### Notes

- Existing webhook logs are carried over, but their stored payloads are dropped on first launch after the update. New syncs store payloads as usual

## [1.11.0] - 2026-09-13

### Added

- Plain HTTP webhooks for private LAN/VPN receivers, behind an explicit "Allow plain HTTP webhooks" switch; `http://` URLs stay refused with a clear log message until it is on, and HTTPS remains the default (#51)
- MQTT publishing for Screen Time: today's and yesterday's total minutes and today's most used app, with the top five apps as attributes, under the same Home Assistant device; screen time syncs also run with MQTT alone and no webhook configured (#52)
- MQTT settings are now symmetric: Health Connect and Screen Time each have their own switch and base topic, share one broker connection by default (existing settings carry over), and either section can switch to its own broker
- Collapsible "Advanced" and "Notifications" cards, with the daily totals and plain HTTP switches under Advanced
- At-a-glance card at the top of the Screen Time tab: today's minutes, today's most used app, last sync, and a 7-day minutes sparkline, matching the Health Connect dashboard card
- `raw_min_time`, `raw_max_time` and `raw_latest_modified_time` in `_diagnostics`, describing everything Health Connect returned before the incremental filter, so a receiver can tell "the source app has not written it yet" from "the filter dropped it" (#53)
- `docs/DATA_SOURCES.md`: what individual source apps (Fitbit, Cronometer, Health Sync, Zepp, Garmin) do and do not write, and how screen time is measured

### Changed

- README documents `daily_totals`, server-side deduplication on `uuid`, the `_diagnostics` block, the screen time method, and adds troubleshooting entries for inflated totals, late nightly metrics, missing nutrients and inflated screen time

## [1.10.2] - 2026-09-12

### Fixed

- Screen time per-app minutes could run far above Digital Wellbeing (a weather app at 15 hours on a 4-hour day): a session whose ACTIVITY_PAUSED was never recorded was counted until the end of the day, and activities of the same app overwrote each other's start time. Sessions are now tracked per activity and also closed by ACTIVITY_STOPPED, screen off, keyguard and shutdown events, and System UI and the launcher are excluded as Digital Wellbeing does

## [1.10.1] - 2026-09-12

### Added

- Regression test that pins chloride, energy from fat, folic acid and thiamin through the full `NutritionRecord` to JSON path, after a user report showed them absent from an export: the fields were never written by the source app, and the test guarantees they appear as soon as Health Connect carries them

## [1.10.0] - 2026-09-09

### Added

- Full `NutritionRecord` export: food name, meal type and all 38 further nutrients Health Connect exposes (fibre, sugars, fat subtypes, cholesterol, minerals, vitamins, caffeine), with units in the key suffix; the four original keys are unchanged and every new field is optional (#50)

## [1.9.0] - 2026-09-08

### Fixed

- Heart Rate Variability could never be granted: the manifest declared a nonexistent permission name (#40); the permission request list is now derived from the data type enum, which also restores the 10 newest types that had silently dropped out of the permission dialog
- Heart-rate backlogs no longer grow faster than they drain: a sync run now delivers up to 8 capped batches instead of one (#38)
- Records sharing the cap-boundary modification time are no longer skipped: the oldest-first cap extends across timestamp ties, keeping the strict watermark filter safe (#38)
- Backfill drains each 3-day window until exhausted instead of dropping dense data past the per-type cap (#39)
- The pagination loop treats an empty page token as completion, matching Health Connect behavior (#38)

### Added

- `READ_HEALTH_DATA_HISTORY` permission, requested with the normal flow and surfaced in the backfill dialog: without it Health Connect caps reads at 30 days before the first grant, so long backfills silently returned only recent data (#39)

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

[Unreleased]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.17.1...HEAD
[1.17.1]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.17.0...1.17.1
[1.17.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.16.4...1.17.0
[1.16.4]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.16.3...1.16.4
[1.16.3]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.16.2...1.16.3
[1.16.2]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.16.1...1.16.2
[1.16.1]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.16.0...1.16.1
[1.16.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.15.0...1.16.0
[1.15.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.14.0...1.15.0
[1.14.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.13.4...1.14.0
[1.13.4]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.13.3...1.13.4
[1.13.3]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.13.2...1.13.3
[1.13.2]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.13.1...1.13.2
[1.13.1]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.13.0...1.13.1
[1.13.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.12.2...1.13.0
[1.12.2]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.12.1...1.12.2
[1.12.1]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.12.0...1.12.1
[1.12.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.11.0...1.12.0
[1.11.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.10.2...1.11.0
[1.10.2]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.10.1...1.10.2
[1.10.1]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.10.0...1.10.1
[1.10.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.9.0...1.10.0
[1.9.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.8.0...1.9.0
[1.8.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.7.0...1.8.0
[1.7.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.6.1...1.7.0
[1.6.1]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.6.0...1.6.1
[1.6.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.5.0...1.6.0
[1.5.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.4.1...1.5.0
