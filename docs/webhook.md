# Webhook payload reference

The complete payload your server receives, per data type. A machine-readable [JSON Schema](webhook-schema.json) of the whole payload is published in this repository; validate your receiver against it.

Want a ready-made backend? [life-dashboard-stack](https://github.com/owen282000/life-dashboard-stack) is a docker-compose with an HMAC-verifying receiver, Postgres and a provisioned Grafana dashboard: from phone to Grafana in 10 minutes.

## Contents

- [Health Connect payload](#health-connect-payload)
  - [Activity](#activity) - [Body](#body) - [Body composition](#body-composition) - [Vitals](#vitals) - [Sleep](#sleep)
  - [Nutrition](#nutrition) - [Mindfulness](#mindfulness) - [Cycle tracking](#cycle-tracking) - [Metabolic and fitness](#metabolic-and-fitness)
  - [Daily totals](#daily-totals) - [Diagnostics](#diagnostics)
- [Screen Time payload](#screen-time-payload)
- [Delivery, retries and signing](#delivery-retries-and-signing)
- [Example backend integrations](#example-backend-integrations)

## Health Connect payload

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
  "menstruation_flow": [],
  "basal_metabolic_rate": [],
  "vo2_max": [],
  "skin_temperature": [],
  "basal_body_temperature": [],
  "intermenstrual_bleeding": [],
  "ovulation_test": [],
  "cervical_mucus": [],
  "sexual_activity": []
}
```

Only enabled data types are included. Every record additionally carries a `uuid` (the stable Health Connect record id, useful for server-side deduplication since batches can be re-sent) and a `source` field with the package name of the app that wrote it to Health Connect (e.g. `"source": "com.zepp.app"`), so backends receiving data from multiple sources (phone, watch, third-party apps) can tell records apart. These are omitted from the examples below for brevity. Each array contains records with the following fields.

### Activity

**Steps**
```json
{ "count": 1234, "start_time": "2025-02-05T08:00:00Z", "end_time": "2025-02-05T09:00:00Z", "source": "com.zepp.app" }
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

### Body

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

### Body composition

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

### Vitals

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

### Sleep

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

### Nutrition

**Hydration**
```json
{ "liters": 0.5, "start_time": "2025-02-05T08:00:00Z", "end_time": "2025-02-05T08:00:00Z" }
```

**Nutrition**
```json
{
  "calories": 450.0, "protein_grams": 25.0, "carbs_grams": 60.0, "fat_grams": 12.0,
  "name": "Oatmeal with berries", "meal_type": "breakfast",
  "dietary_fibre_g": 6.2, "sugars_g": 9.1, "saturated_fat_g": 2.0, "trans_fat_g": 0.0,
  "sodium_mg": 180.0, "potassium_mg": 320.0, "iron_mg": 1.8,
  "vitamin_c_mg": 8.0, "vitamin_d_mcg": 2.5, "vitamin_b12_mcg": 1.2,
  "start_time": "2025-02-05T12:00:00Z", "end_time": "2025-02-05T12:30:00Z"
}
```

Every nutrient Health Connect's `NutritionRecord` exposes is exported: energy from fat, fibre, sugars, the fat subtypes, cholesterol, 14 minerals and trace elements, 14 vitamins and related nutrients, and caffeine. Units are in the key suffix (`_g`, `_mg`, `_mcg`, `_kcal`). All fields are optional and omitted when the source app did not write them; a real zero (for example `trans_fat_g: 0.0`) is kept. `meal_type` is one of `breakfast`, `lunch`, `dinner`, `snack`, `unknown`. The full key list is in [webhook-schema.json](webhook-schema.json).

### Mindfulness

**Mindfulness Sessions**
```json
{ "title": "Morning Meditation", "start_time": "2025-02-05T06:00:00Z", "end_time": "2025-02-05T06:15:00Z", "duration_seconds": 900 }
```

The `title` field is optional and may be `null`.

### Cycle tracking

**Menstruation Period**
```json
{ "start_time": "2025-02-01T00:00:00Z", "end_time": "2025-02-05T00:00:00Z" }
```

**Menstruation Flow**
```json
{ "flow": "medium", "time": "2025-02-03T00:00:00Z" }
```

The `flow` field is one of `light`, `medium`, `heavy`, or `unknown`.

**Intermenstrual Bleeding**
```json
{ "time": "2025-02-10T00:00:00Z", "source": "com.example.cycleapp" }
```

**Ovulation Test**
```json
{ "result": "positive", "time": "2025-02-12T08:00:00Z" }
```

The `result` field is one of `positive`, `high`, `negative`, `inconclusive`, or `unknown`.

**Cervical Mucus**
```json
{ "appearance": "egg_white", "sensation": "medium", "time": "2025-02-12T08:00:00Z" }
```

**Sexual Activity**
```json
{ "protection_used": "protected", "time": "2025-02-11T00:00:00Z" }
```

**Basal Body Temperature**
```json
{ "celsius": 36.4, "time": "2025-02-12T06:30:00Z" }
```

### Metabolic and fitness

**Basal Metabolic Rate**
```json
{ "kilocalories_per_day": 1650.0, "time": "2025-02-05T00:00:00Z" }
```

**VO2 Max**
```json
{ "vo2_ml_per_min_per_kg": 42.5, "time": "2025-02-05T09:00:00Z" }
```

**Skin Temperature**
```json
{ "delta_celsius": -0.3, "baseline_celsius": 33.5, "time": "2025-02-05T03:00:00Z" }
```

Skin temperature is reported as deltas from a per-record baseline, matching how wearables write it to Health Connect; `baseline_celsius` is omitted when the source app provides none.

### Daily totals

When several apps write the same activity to Health Connect (phone and watch, or a mirroring app such as Health Sync), the raw records above contain each copy and adding them up double counts. The payload therefore also carries `daily_totals`, computed with Health Connect's aggregate API, which deduplicates across sources and matches what the Health Connect app shows. It covers yesterday and today, only for the enabled types, and can be switched off in the app.

```json
"daily_totals": [
  { "date": "2025-02-05", "steps": 8421, "distance_meters": 6210.4, "active_calories": 412.0, "total_calories": 2231.5 }
]
```

Use `daily_totals` for day totals and the raw records for detail. Records that arrive late, for example a watch that uploads hours later with the original timestamps, are still delivered: the sync filters on each record's modification time, not on its timestamp. Because a batch is re-sent after a failed delivery and edited records are sent again, deduplicate on `uuid` server-side.

### Diagnostics

Every payload ends with a `_diagnostics` object with one entry per enabled type, so a receiver can see what Health Connect returned before and after the incremental filter:

```json
"_diagnostics": {
  "heart_rate_variability": {
    "permission_granted": true,
    "page_count": 1,
    "raw_record_count": 472,
    "raw_min_time": "2026-09-11T22:10:00Z",
    "raw_max_time": "2026-09-12T05:20:00Z",
    "raw_latest_modified_time": "2026-09-12T05:43:39.120Z",
    "filtered_record_count": 0,
    "min_time": null,
    "max_time": null,
    "last_sync": "2026-09-12T05:44:06.439Z",
    "error": null
  }
}
```

`raw_*` describes everything Health Connect returned for the query window; `filtered_record_count` and `min_time`/`max_time` describe what this payload delivered. When `raw_latest_modified_time` is older than `last_sync`, the source app has not written anything new yet. See [DATA_SOURCES.md](DATA_SOURCES.md) for what individual source apps do and do not write.

## Screen Time payload

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

Minutes are foreground time per app, derived from Android's activity resume, pause and stop events; background time is not counted. A session also ends on screen off, keyguard and shutdown, System UI and the launcher are excluded, and apps with under one minute per day are omitted, so totals are comparable to Digital Wellbeing (with a custom day boundary they will not match its midnight day exactly). Every sync recomputes and re-sends the last 7 days from the device's event log, so store per date and let the newest payload win.

## Delivery, retries and signing

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

## Example backend integrations

### Simple Express.js server

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

### Home Assistant webhook

Use Home Assistant's webhook trigger to receive data and store it or trigger automations. For sensors without any server-side wiring, use the built-in MQTT publishing with Home Assistant Discovery instead; see [features.md](features.md#home-assistant-and-mqtt).
