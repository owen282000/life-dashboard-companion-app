# Features

Everything Life Dashboard Companion does, in detail. For setup and day-to-day use see [usage.md](usage.md); for the payload your server receives see [webhook.md](webhook.md).

## Health Connect integration

Syncs data from Google Health Connect to your webhook, with a per-data-type toggle and permission management, and a configurable sync interval (minimum 15 minutes).

### 33 supported data types

| Category | Types |
|---|---|
| Activity | Steps, Distance, Active Calories, Total Calories, Exercise Sessions |
| Body | Weight, Height, Body Temperature, Skin Temperature, Basal Body Temperature |
| Body composition | Body Fat %, Lean Body Mass, Bone Mass, Body Water Mass |
| Vitals | Heart Rate, Resting Heart Rate, Heart Rate Variability (HRV), Blood Pressure, Blood Glucose, Oxygen Saturation, Respiratory Rate, Basal Metabolic Rate, VO2 Max |
| Sleep | Sleep sessions with stages |
| Nutrition | Hydration, Nutrition records |
| Mindfulness | Meditation sessions (from apps like Waking Up, Headspace) |
| Cycle tracking | Menstruation Period, Menstruation Flow, Intermenstrual Bleeding, Ovulation Test, Cervical Mucus, Sexual Activity, Basal Body Temperature |

Cycle tracking covers logged data from cycle apps that write to Health Connect, such as Clue and Flo. Samsung Health does not share cycle data with Health Connect, and predictions stay in the source app.

What individual source apps do and do not write is collected in [DATA_SOURCES.md](DATA_SOURCES.md).

## Screen Time tracking

- Tracks foreground time per app via Android's `UsageStatsManager`, with System UI and the launcher excluded so totals are comparable to Digital Wellbeing
- **Configurable day boundary** for night owls: set the boundary to 4 AM and phone usage between midnight and 4 AM counts towards the previous day, instead of an arbitrary midnight cutoff
- Syncs the last 7 days of usage data
- App names resolved from package names

## Webhook configuration

- **Multiple webhook URLs** - send to several endpoints simultaneously
- **Custom headers** - auth tokens, API keys, or any custom HTTP header, per category
- **HMAC payload signing** - optional `X-Signature` header so your server can verify the sender
- **Test ping** - send a small test payload to verify your server setup without waiting for real data
- **Retries with backoff** - transient failures are retried automatically; permanent errors fail fast
- **Separate configuration** - different URLs, headers and signing secrets for Health and Screen Time
- **HTTPS by default, plain HTTP on request** - `http://` URLs are refused unless "Allow plain HTTP webhooks" is switched on, for Home Assistant or receivers only reachable over a private LAN or VPN

Delivery details, retry rules and signature verification are described in [webhook.md](webhook.md#delivery-retries-and-signing).

## Home Assistant and MQTT

- **MQTT publishing with Home Assistant Discovery** - point the app at your MQTT broker and the latest value of every synced data type appears in Home Assistant automatically as sensors (steps, heart rate, sleep duration, weight, blood pressure, and more), grouped under one device. No server-side configuration needed.
- States and discovery configs are published retained, so values survive Home Assistant restarts
- Optional TLS and username/password authentication; credentials are stored encrypted on-device
- Event-like types (exercise, nutrition, cycle tracking) remain webhook-only; MQTT sensors represent the most recent record per type
- Screen Time publishes too: today's and yesterday's total minutes and today's most used app (top five apps as attributes), under the same Home Assistant device. Health Connect and Screen Time each have their own switch and base topic and share one broker connection by default; either section can switch to its own broker.

## Automation

- **Home screen widget** - last sync result and records delivered today at a glance
- **Quick Settings tile** - trigger an immediate sync from the notification shade
- **Tasker / MacroDroid support** - trigger syncs with an explicit broadcast intent: `com.owen282000.lifedashboard.ACTION_SYNC`
- **Failure notifications** - local notification after repeated failed syncs, with a configurable threshold

## Data tools

- **Data preview** - view the exact JSON payload before syncing
- **Export as CSV/JSON** - export sync logs via the Android share sheet
- **Sync history dashboard** - overview of success rates, record counts and recent failures
- **Settings backup and restore** - export every webhook, header, secret, MQTT broker and toggle as a JSON file, and import it on another device. See [settings-backup.md](settings-backup.md)

## General

- **Background sync** - uses WorkManager for reliable background execution
- **Webhook logs** - recent sync attempts with payloads, for debugging
- **Health Connect install check** - clear guidance when Health Connect is missing or outdated
- **Modern UI** - Material 3 design with dark mode support

## Tech stack

- **Kotlin** - modern Android development
- **Jetpack Compose** - declarative UI with Material 3
- **Health Connect SDK** - official Google Health Connect API
- **WorkManager** - reliable background task scheduling
- **OkHttp** - HTTP client with retry logic
- **Kotlinx Serialization** - JSON serialization
