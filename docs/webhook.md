# Webhook payload reference

The complete payload your server receives, per data type. A machine-readable [JSON Schema](webhook-schema.json) of the whole payload is published in this repository; validate your receiver against it.

Want a ready-made backend? [life-dashboard-stack](https://github.com/owen282000/life-dashboard-stack) is a docker-compose with an HMAC-verifying receiver, Postgres and a provisioned Grafana dashboard: from phone to Grafana in 10 minutes.

## Contents

- [Health Connect payload](#health-connect-payload)
  - [Activity](#activity) - [Body](#body) - [Body composition](#body-composition) - [Vitals](#vitals) - [Sleep](#sleep)
  - [Nutrition](#nutrition) - [Mindfulness](#mindfulness) - [Cycle tracking](#cycle-tracking) - [Metabolic and fitness](#metabolic-and-fitness)
  - [Daily totals](#daily-totals) - [Deletions](#deletions) - [Data resolution](#data-resolution) - [Diagnostics](#diagnostics)
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

When several apps write the same activity to Health Connect (phone and watch, or a mirroring app such as Health Sync), the raw records above contain each copy and adding them up double counts. The payload therefore also carries `daily_totals`, computed with Health Connect's aggregate API, which deduplicates across sources and matches what the Health Connect app shows. It covers yesterday and today, only for the enabled types, and can be switched off in the app. A backfill carries it for every day its window touches (from 1.17.0), in every payload of the window, so a receiver that keeps history gets the real total for each past day; a day cut by a window boundary appears in both windows with the same figures.

```json
"daily_totals": [
  { "date": "2025-02-05", "steps": 8421, "distance_meters": 6210.4, "active_calories": 412.0, "total_calories": 2231.5 }
]
```

Use `daily_totals` for day totals and the raw records for detail. Records that arrive late, for example a watch that uploads hours later with the original timestamps, are still delivered: the sync filters on each record's modification time, not on its timestamp. Because a batch is re-sent after a failed delivery and edited records are sent again, deduplicate on `uuid` server-side.

### Deletions

A record that is deleted in Health Connect leaves nothing behind for a sync to read, so a receiver that stores records would keep it forever. Apps that edit by replacing make this visible: Cronometer, for instance, deletes a meal and inserts a new one, which arrives as a second record with a different `uuid` while the original is still on the receiver.

From 1.18.0 the app follows Health Connect's own change tracking and names the records that are gone:

```json
"deleted_records": [
  { "type": "nutrition", "uuid": "0f7c...e91" }
]
```

`type` is the payload key the record arrived under, so a receiver drops that `uuid` from that collection. The field is absent when nothing was deleted, and deletions ride along on the first payload of a sync.

Two limits are worth building around:

- **Tracking starts when the app first syncs a type**, so deletions from before that were never observable.
- **Some syncs cannot vouch for a type**, and those are named in `deletions_unavailable`, a list of payload keys. It happens when Health Connect forgets a phone that has not synced for 30 days, when a type has more changes than one sync can read, and when a type cannot be read at all. In each case the app does not know what was deleted, so reconcile those types against a backfill window instead of trusting the incremental payload.

```json
"deletions_unavailable": ["nutrition", "hydration"]
```

A backfill window is the fallback, and says so explicitly. Every payload of a backfill carries `backfill`, `window_start` and `window_end`; the last payload of a window also carries `window_complete: true`, which means every record the phone holds for that window has now been sent. At that point a receiver may treat any `uuid` it holds inside the window that was not in the window as deleted. A window that was split into several payloads carries `window_complete: false` on all but the last, and a window that holds nothing still sends one payload with `window_complete: true`, which is what distinguishes an empty window from an unreported one.

Every Health Connect payload also carries `sequence`, a counter that only goes up for a given install. The app drains its outbox before each sync, so payloads normally arrive in order, but a receiver behind several webhook URLs, a proxy or a retrying load balancer can still see an older one land after a newer one. Recording the highest sequence applied per install lets a receiver ignore the late one instead of letting it restore a record that was deleted since. Screen Time payloads carry no sequence, so treat the field as absent rather than zero.

A deletion is often the only thing that changed, for instance when a meal is removed and nothing is added. Such a sync sends a payload with `deleted_records` and no record arrays at all, which is why a payload with no data is not necessarily an empty one.

### Data resolution

A chest strap writes a heart rate sample every second, which is 86,400 records a day that no dashboard reads one by one. Any of the dense types can be sent as one value per time window instead: 1, 5 or 15 minutes, or hourly, set per type under **Data Resolution** on the Health tab. Everything defaults to every record, so a receiver that was built before this existed keeps seeing exactly what it saw.

A bucketed series replaces its raw array under the same key, and the objects inside are a different shape. They never carry the raw field name, so `"bucket_start" in obj` is a reliable test and a parser looking for `bpm` cannot mistake an average for a measurement.

```json
"heart_rate": [
  { "bucket_start": "2025-02-05T08:00:00Z", "bucket_end": "2025-02-05T08:01:00Z",
    "sample_count": 58, "avg": 72.4, "min": 66, "max": 81, "sources": ["com.garmin.android.apps.connectmobile"] }
],
"steps": [
  { "bucket_start": "2025-02-05T08:00:00Z", "bucket_end": "2025-02-05T09:00:00Z",
    "sample_count": 12, "total": 1840 }
],
"_resolutions": { "heart_rate": "1m", "steps": "1h" }
```

Measured values (heart rate, HRV, oxygen saturation, respiratory rate, skin temperature) are averaged, with `min` and `max` kept because an average alone cannot tell a night's sleep from a sprint. Accumulated quantities (steps, distance, active and total calories) are summed into `total`, and carry no average: the mean of a sum describes the records that went in, not the window.

Four things worth knowing when you store these:

- **Windows are aligned to the clock**, not to the first sample. A 15-minute window starts at :00, :15, :30 or :45 in UTC, so buckets from different syncs line up instead of drifting.
- **`sample_count` says how complete a bucket is.** A window with two samples and one with sixty are both one object; without the count you cannot tell them apart or merge them.
- **A window is normally sent once, complete.** Bucketed series arrive in the last payload of a sync, even when a large backlog made the sync deliver its raw records in several payloads. A window that is still filling when a sync runs is not sent yet; its samples are kept and bucketed together with the next sync's records, so the bucket goes out whole. The sync's incremental watermark is not involved.
- **Empty windows produce nothing.** No bucket means nothing was measured, which is not the same as a measured zero.

The exception is a record that arrives late for a window already sent, such as a watch uploading hours after the fact, or a record edited afterwards. That window is sent again with only the late samples. Every bucket carries enough to merge exactly, so a receiver that keys on `bucket_start` should combine rather than replace: add the `sample_count`s, add the `total`s, take the smaller `min` and the larger `max`, and weight the `avg` by `sample_count` (`(avg1 * n1 + avg2 * n2) / (n1 + n2)`). A receiver that simply keeps the object with the larger `sample_count` is right in every case but that one.

`_resolutions` names the window per series so a receiver can store the data correctly without being configured separately. It lists only the bucketed series, and is absent when nothing is bucketed.

Bucketing applies to webhook payloads. The Home Assistant sensors always publish the latest value or today's total, and `daily_totals` is unaffected because it comes from Health Connect's own aggregate.

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
