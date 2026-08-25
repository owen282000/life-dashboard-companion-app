# Life Dashboard Companion

[![Download APK](https://img.shields.io/github/v/release/owen282000/life-dashboard-companion-app?label=Download%20APK)](https://github.com/owen282000/life-dashboard-companion-app/releases/latest)
[![Build](https://github.com/owen282000/life-dashboard-companion-app/actions/workflows/build.yml/badge.svg)](https://github.com/owen282000/life-dashboard-companion-app/actions/workflows/build.yml)
[![Security](https://github.com/owen282000/life-dashboard-companion-app/actions/workflows/security.yml/badge.svg)](https://github.com/owen282000/life-dashboard-companion-app/actions/workflows/security.yml)
[![Release](https://github.com/owen282000/life-dashboard-companion-app/actions/workflows/release.yml/badge.svg)](https://github.com/owen282000/life-dashboard-companion-app/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Android](https://img.shields.io/badge/Android-14%2B-green.svg)](https://developer.android.com)

<p align="center">
  <img src="docs/screenshots/health-connect.png" width="250" alt="Health Connect Screen">
  <img src="docs/screenshots/screen-time.png" width="250" alt="Screen Time Screen">
  <img src="docs/screenshots/logs.png" width="250" alt="Webhook Logs Screen">
</p>

A privacy-focused Android app that syncs your **Health Connect** and **Screen Time** data to your own server via webhooks. Perfect for self-hosted dashboards, Home Assistant integrations, or any quantified self setup.

Also on iPhone? Check out [Life Dashboard Companion for iOS](https://github.com/owen282000/life-dashboard-companion-app-ios), which sends a compatible payload from Apple Health (HealthKit) so both apps can feed the same backend.

## Why This App?

- **Own Your Data** - Send health data to your own server, not third-party clouds
- **Flexible Webhooks** - Works with any backend that accepts JSON POST requests
- **Combined App** - Health Connect + Screen Time in one app
- **25 Health Data Types** - Supports all major Health Connect data types
- **Modern UI** - Built with Jetpack Compose and Material 3

## Features

### Health Connect Integration
- Syncs data from Google Health Connect to your webhook
- **25 supported data types:**
  - Activity: Steps, Distance, Active Calories, Total Calories, Exercise Sessions
  - Body: Weight, Height, Body Temperature
  - Body Composition: Body Fat %, Lean Body Mass, Bone Mass, Body Water Mass
  - Vitals: Heart Rate, Resting Heart Rate, Heart Rate Variability (HRV), Blood Pressure, Blood Glucose, Oxygen Saturation, Respiratory Rate
  - Sleep: Sleep sessions with stages
  - Nutrition: Hydration, Nutrition records
  - Mindfulness: Meditation sessions (from apps like Waking Up, Headspace)
  - Cycle Tracking: Menstruation Period, Menstruation Flow (logged data from cycle apps that write to Health Connect, such as Clue and Flo; note that Samsung Health does not share cycle data with Health Connect, and predictions stay in the source app)
- Per-data-type toggle and permission management
- Configurable sync interval (minimum 15 minutes)

### Screen Time Tracking
- Tracks app usage statistics via Android's UsageStatsManager
- **Configurable day boundary** - Perfect for night owls! If you set the boundary to 4 AM, any phone usage between midnight and 4 AM counts towards the previous day's total. This gives you accurate "real day" statistics instead of arbitrary midnight cutoffs.
- Syncs last 7 days of usage data
- App names resolved from package names

### Webhook Configuration
- **Multiple webhook URLs** - Send to multiple endpoints simultaneously
- **Custom headers** - Add auth tokens, API keys, or any custom HTTP headers per category
- **HMAC payload signing** - Optional `X-Signature` header so your server can verify the sender
- **Retries with backoff** - Transient failures are retried automatically; permanent errors fail fast
- **Separate configuration** - Different URLs, headers, and signing secrets for Health and Screen Time

### Data Tools
- **Data preview** - View the exact JSON payload before syncing
- **Export as CSV/JSON** - Export sync logs via the Android share sheet
- **Sync history dashboard** - Overview of success rates, record counts, and recent failures

### General
- **Background sync** - Uses WorkManager for reliable background execution
- **Webhook logs** - View recent sync attempts with payloads for debugging
- **Health Connect install check** - Clear guidance when Health Connect is missing or outdated
- **Modern UI** - Material 3 design with dark mode support

## Requirements

- Android 14+ (API 34)
- [Health Connect](https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata) app installed
- Usage access permission (for Screen Time feature)

## Installation

### From Releases (Recommended)
1. Download the latest APK from [Releases](https://github.com/owen282000/life-dashboard-companion-app/releases/latest)
2. Install on your Android device (enable "Install from unknown sources" if needed)

### Build from Source
```bash
# Clone the repository
git clone https://github.com/owen282000/life-dashboard-companion-app.git
cd life-dashboard-companion-app

# Build debug APK
./gradlew assembleDebug

# The APK will be at: app/build/outputs/apk/debug/app-debug.apk

# Or install directly on connected device
./gradlew installDebug
```

## Setup

1. **Install the app** on your Android device
2. **Grant Health Connect permissions** - Tap "Grant" and select the data types you want to sync
3. **Grant Usage Access** (for Screen Time) - Go to Settings when prompted
4. **Configure webhook URLs** - Enter your server endpoint(s)
5. **Add webhook headers** (optional) - Configure auth tokens or API keys
6. **Set sync intervals** - Minimum 15 minutes
7. **Tap "Preview Data"** to inspect the payload, then **"Sync Now"** to send

## Webhook Payload Format

### Health Connect

Every Health Connect payload has these top-level fields:

```json
{
  "timestamp": "2025-02-05T12:00:00Z",
  "app_version": "1.2.0",
  "source": "health_connect",
  "steps": [],
  "sleep": [],
  "heart_rate": [],
  "distance": [],
  "active_calories": [],
  "total_calories": [],
  "weight": [],
  "height": [],
  "blood_pressure": [],
  "blood_glucose": [],
  "oxygen_saturation": [],
  "body_temperature": [],
  "respiratory_rate": [],
  "resting_heart_rate": [],
  "exercise": [],
  "hydration": [],
  "nutrition": [],
  "mindfulness": [],
  "body_fat": [],
  "lean_body_mass": [],
  "bone_mass": [],
  "body_water_mass": [],
  "heart_rate_variability": [],
  "menstruation_period": [],
  "menstruation_flow": []
}
```

Only enabled data types are included. Each array contains records with the following fields:

#### Activity

**Steps**
```json
{ "count": 1234, "start_time": "2025-02-05T08:00:00Z", "end_time": "2025-02-05T09:00:00Z" }
```

**Distance**
```json
{ "meters": 1523.5, "start_time": "2025-02-05T08:00:00Z", "end_time": "2025-02-05T09:00:00Z" }
```

**Active Calories**
```json
{ "calories": 245.3, "start_time": "2025-02-05T08:00:00Z", "end_time": "2025-02-05T09:00:00Z" }
```

**Total Calories**
```json
{ "calories": 1850.0, "start_time": "2025-02-05T08:00:00Z", "end_time": "2025-02-05T09:00:00Z" }
```

**Exercise Sessions**
```json
{ "type": "running", "start_time": "2025-02-05T07:00:00Z", "end_time": "2025-02-05T08:00:00Z", "duration_seconds": 3600 }
```

#### Body

**Weight**
```json
{ "kilograms": 75.5, "time": "2025-02-05T07:00:00Z" }
```

**Height**
```json
{ "meters": 1.82, "time": "2025-02-05T07:00:00Z" }
```

**Body Temperature**
```json
{ "celsius": 36.6, "time": "2025-02-05T07:00:00Z" }
```

#### Body Composition

**Body Fat %**
```json
{ "percentage": 18.5, "time": "2025-02-05T07:00:00Z" }
```

**Lean Body Mass**
```json
{ "kilograms": 61.5, "time": "2025-02-05T07:00:00Z" }
```

**Bone Mass**
```json
{ "kilograms": 3.2, "time": "2025-02-05T07:00:00Z" }
```

**Body Water Mass**
```json
{ "kilograms": 42.0, "time": "2025-02-05T07:00:00Z" }
```

#### Vitals

**Heart Rate**
```json
{ "bpm": 72, "time": "2025-02-05T10:30:00Z" }
```

**Resting Heart Rate**
```json
{ "bpm": 58, "time": "2025-02-05T07:00:00Z" }
```

**Heart Rate Variability (HRV)**
```json
{ "heart_rate_variability_millis": 42.5, "time": "2025-02-05T07:00:00Z" }
```

**Blood Pressure**
```json
{ "systolic": 120.0, "diastolic": 80.0, "time": "2025-02-05T07:00:00Z" }
```

**Blood Glucose**
```json
{ "mmol_per_liter": 5.5, "time": "2025-02-05T07:00:00Z" }
```

**Oxygen Saturation**
```json
{ "percentage": 98.0, "time": "2025-02-05T07:00:00Z" }
```

**Respiratory Rate**
```json
{ "rate": 16.0, "time": "2025-02-05T07:00:00Z" }
```

#### Sleep

**Sleep Sessions**
```json
{
  "session_end_time": "2025-02-05T07:30:00Z",
  "duration_seconds": 28800,
  "stages": [
    {
      "stage": "deep",
      "start_time": "2025-02-04T23:00:00Z",
      "end_time": "2025-02-05T01:00:00Z",
      "duration_seconds": 7200
    }
  ]
}
```

Possible `stage` values: `unknown`, `awake`, `sleeping`, `out_of_bed`, `light`, `deep`, `rem`, `awake_in_bed`.

#### Nutrition

**Hydration**
```json
{ "liters": 0.5, "start_time": "2025-02-05T08:00:00Z", "end_time": "2025-02-05T08:00:00Z" }
```

**Nutrition**
```json
{ "calories": 450.0, "protein_grams": 25.0, "carbs_grams": 60.0, "fat_grams": 12.0, "start_time": "2025-02-05T12:00:00Z", "end_time": "2025-02-05T12:30:00Z" }
```

All nutrition fields (`calories`, `protein_grams`, `carbs_grams`, `fat_grams`) are optional and omitted when not available.

#### Mindfulness

**Mindfulness Sessions**
```json
{ "title": "Morning Meditation", "start_time": "2025-02-05T06:00:00Z", "end_time": "2025-02-05T06:15:00Z", "duration_seconds": 900 }
```

The `title` field is optional and may be `null`.

#### Cycle Tracking

**Menstruation Period**
```json
{ "start_time": "2025-02-01T00:00:00Z", "end_time": "2025-02-05T00:00:00Z" }
```

**Menstruation Flow**
```json
{ "flow": "medium", "time": "2025-02-03T00:00:00Z" }
```

The `flow` field is one of `light`, `medium`, `heavy`, or `unknown`.

### Screen Time
```json
{
  "timestamp": "2025-02-05T12:00:00Z",
  "app_version": "1.2.0",
  "device": "Google Pixel 8",
  "source": "screen_time",
  "screen_time": [
    {
      "date": "2025-02-05",
      "total_screen_time_minutes": 180,
      "apps": [
        {
          "package": "com.instagram.android",
          "name": "Instagram",
          "minutes": 45,
          "last_used": "2025-02-05T11:30:00Z"
        }
      ]
    }
  ]
}
```

### Delivery, Retries and Signing

Every configured webhook URL receives each payload. A sync counts as delivered when at least one endpoint accepted it; per-URL results are visible in the in-app webhook logs.

Failed posts are retried up to 3 times with exponential backoff (1s, 2s), but only for transient failures: network errors, timeouts, HTTP 408, 429, and 5xx. Permanent client errors (401, 404, ...) fail immediately without retrying. The logs distinguish "recovered after retry" from "failed after all attempts".

When an HMAC signing secret is configured (under Webhook Headers in the app), every POST includes:

```
X-Signature: sha256=<hex of HMAC-SHA256(secret, raw request body)>
```

Verify it server-side by recomputing the HMAC over the raw body:

```javascript
const crypto = require('crypto');

function verifySignature(req, secret) {
  const expected = 'sha256=' + crypto
    .createHmac('sha256', secret)
    .update(req.rawBody) // the exact raw request body bytes
    .digest('hex');
  const actual = req.get('X-Signature') || '';
  return actual.length === expected.length &&
    crypto.timingSafeEqual(Buffer.from(actual), Buffer.from(expected));
}
```

## Example Backend Integrations

### Simple Express.js Server
```javascript
const express = require('express');
const app = express();
app.use(express.json());

app.post('/api/health-connect', (req, res) => {
  console.log('Health data received:', req.body);
  // Store in database, forward to InfluxDB, etc.
  res.status(200).send('OK');
});

app.post('/api/screen-time', (req, res) => {
  console.log('Screen time data received:', req.body);
  res.status(200).send('OK');
});

app.listen(3000);
```

### Home Assistant Webhook
Use Home Assistant's webhook trigger to receive data and store it or trigger automations.

## Tech Stack

- **Kotlin** - Modern Android development
- **Jetpack Compose** - Declarative UI with Material 3
- **Health Connect SDK** - Official Google Health Connect API
- **WorkManager** - Reliable background task scheduling
- **OkHttp** - HTTP client with retry logic
- **Kotlinx Serialization** - JSON serialization

## Privacy

This app:
- Does **not** collect any data itself
- Does **not** send data anywhere except your configured webhook URLs
- Does **not** include any analytics or tracking
- Stores settings locally on your device only

You are in full control of where your data goes.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [Google Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect) for the excellent SDK
- [HC Webhook](https://github.com/mcnaveen/health-connect-webhook) by mcnaveen for inspiration on Health Connect integration patterns
- The Quantified Self community for inspiration
- [Claude Code](https://claude.ai/claude-code) for assistance with development

## Support

If you find this project useful, please consider:
- Starring the repository
- Sharing it with others who might benefit
- Contributing improvements

---

Made by [Owen Vogelaar](https://github.com/owen282000) for the self-hosted and quantified self community.
