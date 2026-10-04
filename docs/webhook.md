# Webhook payload reference

The complete payload your server receives, per data type. A machine-readable [JSON Schema](webhook-schema.json) of the whole payload is published in this repository; validate your receiver against it.

Want a ready-made backend? [life-dashboard-stack](https://github.com/owen282000/life-dashboard-stack) is a docker-compose with an HMAC-verifying receiver, Postgres and a provisioned Grafana dashboard: from phone to Grafana in 10 minutes.

## Contents

- [Health Connect payload](#health-connect-payload)
  - [Activity](#activity) - [Body](#body) - [Body composition](#body-composition) - [Vitals](#vitals) - [Sleep](#sleep)
  - [Nutrition](#nutrition) - [Mindfulness](#mindfulness) - [Cycle tracking](#cycle-tracking) - [Metabolic and fitness](#metabolic-and-fitness)
  - [Daily totals](#daily-totals) - [Deletions](#deletions) - [Data resolution](#data-resolution) - [Diagnostics](#diagnostics)
- [Screen Time payload](#screen-time-payload)
- [Test ping](#test-ping)
- [Delivery, retries and signing](#delivery-retries-and-signing)
- [Inbound: what the integration may answer](#inbound-what-the-integration-may-answer)
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

The iOS app sends the same shape from Apple Health, with `"source": "healthkit_ios"`, so one receiver can take both. Its [payload reference](https://github.com/owen282000/life-dashboard-companion-app-ios/blob/main/docs/webhook.md) documents the iOS side and most of where it differs.

Only enabled data types are included. Every record additionally carries a `uuid` (the stable Health Connect record id, useful for server-side deduplication since batches can be re-sent) and a `source` field with the package name of the app that wrote it to Health Connect (e.g. `"source": "com.zepp.app"`), so backends receiving data from multiple sources (phone, watch, third-party apps) can tell records apart. These are omitted from the examples below for brevity. Each array contains records with the following fields.

Deduplicating on `uuid` ignores a retransmitted copy of the **same** Health Connect record. It does not collapse **separate** records that happen to share source, type, interval and value; some producers write those, with different UUIDs. The app delivers both, because they are distinct rows in Health Connect. A receiver that sums session distance, steps or calories may still need to treat that fingerprint as one measurement. See [DATA_SOURCES.md](DATA_SOURCES.md#urevo-comurevoapp) for an observed case.

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
{ "type": "56", "start_time": "2025-02-05T07:00:00Z", "end_time": "2025-02-05T08:00:00Z", "duration_seconds": 3600 }
```

`type` is Health Connect's exercise type constant as a string: `"56"` is running, `"79"` walking, and the full list is in [`ExerciseSessionRecord`](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/ExerciseSessionRecord). The iOS app sends a name instead, such as `"running"`.

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

This is RMSSD, the measure Health Connect stores. The iOS app sends SDNN, the one Apple Health stores, under the same key; the two are not the same number.

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

When several apps write the same activity to Health Connect (phone and watch, or a mirroring app such as Health Sync), the raw records above contain each copy and adding them up double counts. The payload therefore also carries `daily_totals`, computed with Health Connect's aggregate API, which deduplicates across sources and matches what the Health Connect app shows. It counts every stretch of time once: where records overlap, the app highest in Health Connect's priority list for that category counts, and between records of one app the one written last. Records that do not overlap all count, so a copy that a source writes into the wrong minute is in the total too (see [DATA_SOURCES.md](DATA_SOURCES.md#gadgetbridge-nodomainfreeyourgadgetgadgetbridge)). An app that is not in that priority list does not count at all; Health Connect normally adds an app there when it is allowed to write. It covers today and the two days before, only for the enabled types, and can be switched off in the app. A backfill carries it for every day its window touches (from 1.17.0), in every payload of the window, so a receiver that keeps history gets the real total for each past day; a day cut by a window boundary appears in both windows with the same figures.

```json
"daily_totals": [
  { "date": "2025-02-05", "steps": 8421, "distance_meters": 6210.4, "active_calories": 412.0, "total_calories": 2231.5 }
]
```

Use `daily_totals` for day totals and the raw records for detail. They are not measurements of a single exercise session: a day can include other activities and other sources. Records that arrive late, for example a watch that uploads hours later with the original timestamps, are still delivered: the sync filters on each record's modification time, not on its timestamp. Because a batch is re-sent after a failed delivery and edited records are sent again, deduplicate on `uuid` server-side.

### Deletions

A record that is deleted in Health Connect leaves nothing behind for a sync to read, so a receiver that stores records would keep it forever. Apps that edit by replacing make this visible: Cronometer, for instance, deletes a meal and inserts a new one, which arrives as a second record with a different `uuid` while the original is still on the receiver.

From 1.18.0 the app follows Health Connect's own change tracking and names the records that are gone:

```json
"deleted_records": [
  { "type": "nutrition", "uuid": "0f7c...e91" }
]
```

`type` is the payload key the record arrived under, so a receiver drops that `uuid` from that collection. The field is absent when nothing was deleted, and deletions ride along on the first payload of a sync.

A `uuid` in `deleted_records` means the record was gone when the app read Health Connect's change feed, not that the id is retired. A source that revises by deleting a record and writing it again under its own client record id, as Fitbit does with sleep and calories, gets the same `uuid` back, so the same `uuid` can arrive again later as a record; store it again. From the release after 1.21.0 the app never names a record as deleted that exists again, and never sends a `uuid` as a record and as a deletion in one payload. Versions 1.18.0 to 1.21.0 could do both (issues #71 and #72). For their payloads, apply `deleted_records` before the records of the same payload, and let a record that arrives in a later payload restore a `uuid` that was deleted. Heart rate and skin temperature samples arrive as `<record uuid>#<epoch millis>` while a deletion names the record, so drop every sample whose `uuid` starts with that `uuid` followed by `#`.

Two limits are worth building around:

- **Tracking starts when the app first syncs a type**, so deletions from before that were never observable.
- **Some syncs cannot vouch for a type**, and those are named in `deletions_unavailable`, a list of payload keys. It happens when Health Connect forgets a phone that has not synced for 30 days, when a type has more changes than one sync can read, when a type cannot be read at all, and when Health Connect is too slow to answer within the time the sync allows the deletion step (five seconds per type, twenty in total, from 1.18.1). In the last case the type keeps its place in the change feed and is read on the next sync. In each case the app does not know what was deleted for that payload, so reconcile those types against a backfill window instead of trusting the incremental payload.

```json
"deletions_unavailable": ["nutrition", "hydration"]
```

The same change feed shows records that a source wrote or edited long after their own timestamp: a watch that was away from the phone for more than a week uploads its readings with their original times. Those that fall before the query window (see `read_from` below) are not in the payload, and they are named per payload key in `records_outside_window`, with how many there were and the timestamp range they fall in. A backfill of that range sends them. Like deletions, the field is absent when there are none and rides along on the next payload that goes out, or on one of its own.

```json
"records_outside_window": {
  "heart_rate": { "count": 412, "from": "2026-09-14T06:02:11Z", "until": "2026-09-20T12:00:03.114Z" }
}
```

A backfill window is the fallback, and says so explicitly. Every payload of a backfill carries `backfill`, `window_start` and `window_end`; the last payload of a window also carries `window_complete: true`, which means every record the phone holds for that window has now been sent. At that point a receiver may treat any `uuid` it holds inside the window that was not in the window as deleted. A window that was split into several payloads carries `window_complete: false` on all but the last, and a window that holds nothing still sends one payload with `window_complete: true`, which is what distinguishes an empty window from an unreported one.

Every Health Connect payload also carries `sequence`, a counter that only goes up for a given install. The app drains its outbox before each sync, so payloads normally arrive in order, but a receiver behind several webhook URLs, a proxy or a retrying load balancer can still see an older one land after a newer one. Recording the highest sequence applied per install and `source` lets a receiver ignore the late one instead of letting it restore a record that was deleted since. Screen Time payloads take their number from the same counter (1.20.0 and older send none), so per source the numbers only go up but can skip, and one highest number per install would wrongly ignore a Screen Time week that waited in the outbox while a Health Connect payload went ahead of it. Screen Time is compared per date rather than per payload, see [Screen Time payload](#screen-time-payload). The field is optional, and the iOS app does not send it, so treat a missing one as unknown rather than zero.

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
    "sample_count": 12, "total": 1840, "complete": true }
],
"_resolutions": { "heart_rate": "1m", "steps": "1h" }
```

Measured values (heart rate, HRV, oxygen saturation, respiratory rate, skin temperature) are averaged, with `min` and `max` kept because an average alone cannot tell a night's sleep from a sprint. Accumulated quantities (steps, distance, active and total calories) are summed into `total`, and carry no average: the mean of a sum describes the records that went in, not the window.

Four things worth knowing when you store these, and a fifth below:

- **Windows are aligned to the clock**, not to the first sample. A 15-minute window starts at :00, :15, :30 or :45 in UTC, so buckets from different syncs line up instead of drifting.
- **`sample_count` says how complete a bucket is.** A window with two samples and one with sixty are both one object; without the count you cannot tell them apart or merge them.
- **A window is normally sent once, complete.** Bucketed series arrive in the last payload of a sync, even when a large backlog made the sync deliver its raw records in several payloads. A window that is still filling when a sync runs is not sent yet; its samples are kept and bucketed together with the next sync's records, so the bucket goes out whole. The sync's incremental watermark is not involved.
- **Empty windows produce nothing.** No bucket means nothing was measured, which is not the same as a measured zero.

A window can still go out again: a record arrives late for a window already sent (a watch uploading hours after the fact), a record is edited, or a source writes records again that it wrote before. Some sources re-export their last hour on every sync under the same record ids, which Health Connect stores as new versions of the same records. So a receiver keys on the series and `bucket_start`, and what it does with a bucket for a window it already holds depends on one field:

- **`"complete": true`: replace the stored window.** The bucket holds every sample Health Connect had in the window when it was built, not only the ones that changed, so it is the whole window as it stands. Adding it to the stored one would count a rewritten record twice.
- **No `complete`: combine.** The bucket holds only the samples that changed: add the `sample_count`s, add the `total`s, take the smaller `min` and the larger `max`, and weight the `avg` by `sample_count` (`(avg1 * n1 + avg2 * n2) / (n1 + n2)`). The app sends one when it could not read the whole window: one reaching back past the range the sync read (a week before the last complete read, 30 days at most), or a read that had to skip part of its range. 1.22.0 and older never mark a bucket, and every window they sent again holds only the late samples; a record they read again because its source wrote it again is counted twice by this rule, and there is nothing in the payload to tell that case apart.

A backfill reads a bucketed type from window bound to window bound rather than over its chunks of days, so every window lies inside one chunk and goes out once, complete. The window that contains the moment the backfill started is left to the normal sync. Before the release after 1.22.0, the window that straddled each chunk bound went out with only its part after the bound.

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
    "error": null,
    "own_records_skipped": 0,
    "read_from": "2026-09-05T05:29:02.112Z",
    "lookback_gap_from": null
  }
}
```

`raw_*` describes everything Health Connect returned for the query window; `filtered_record_count` and `min_time`/`max_time` describe what this payload delivered. When `raw_latest_modified_time` is older than `last_sync`, the source app has not written anything new yet. An `error` of "Health Connect did not return ... within 10 s" or "skipped: the read step used its budget" means Health Connect did not answer in time; that type keeps its place and the next sync reads it. `read_from` is where the query window started: a week before the last sync that read the whole type, so a phone that was off or asleep for a while still picks up what a watch wrote before the pause, reaching back 30 days at most. When a pause was longer than that, `lookback_gap_from` says from where: records timestamped between it and `read_from` that were written or edited during the pause were not read, and a backfill of that range sends them. A sync that sends nothing keeps the gap for the next payload, so it is named at least once. It is null otherwise. An `error` of "skipped: Health Connect's read quota is used up" means Health Connect refused a read for its quota, which it counts per call and per app; the sync stopped reading there and the next one reads on. When one sync sends a backlog over several payloads, the payloads after the first read only the types still draining, so their `_diagnostics` has an entry for those types only, and every payload of the sync carries the same `daily_totals`. See [DATA_SOURCES.md](DATA_SOURCES.md) for what individual source apps do and do not write.

## Screen Time payload

```json
{
  "timestamp": "2025-02-05T12:00:00Z",
  "app_version": "1.2.0",
  "device": "Google Pixel 8",
  "source": "screen_time",
  "sequence": 42,
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

Minutes are foreground time per app, derived from Android's activity resume, pause and stop events; background time is not counted. A session also ends on screen off, keyguard and shutdown, System UI and the launcher are excluded, and apps with under one minute per day are omitted, so totals are comparable to Digital Wellbeing (with a custom day boundary they will not match its midnight day exactly). Every sync recomputes and re-sends the last 7 days from the device's event log, so store per date and let the newest payload win for that date. The newest week that failed waits in the outbox and can arrive after a newer one, so the newest is the one with the highest `sequence`, not the one that arrived last (see [Deletions](#deletions) for the counter): store with each date the `sequence` of the payload that wrote it, and apply a day only from a payload with a higher one. Do not ignore a late week as a whole: its oldest date may be one that no newer week covers any more.

## Test ping

**Test ping** on either tab, **Send Test Ping** in the setup wizard, and every QR pairing (right after it is applied, to the paired address with the paired secret) send one small payload with no records in it:

```json
{
  "test": true,
  "message": "Test ping from Life Dashboard Companion",
  "timestamp": "2025-02-05T12:00:00Z",
  "app_version": "1.2.0",
  "source": "health_connect"
}
```

`source` is the section the address belongs to, `health_connect` or `screen_time`, so a receiver can tell which of its last-sync times the ping answers for (the iOS app sends `healthkit_ios`). It is signed like any other request and passes [webhook-schema.json](webhook-schema.json). A ping is retried like a sync but never queued in the outbox, and the pairing stays in place when it fails: the toast names the reason.

## Delivery, retries and signing

Every configured webhook URL receives each payload. A sync counts as delivered when at least one endpoint accepted it; per-URL results are visible in the in-app webhook logs. Nothing is queued for an endpoint that missed a payload another one took, so the app says so: the sync line reads "Delivered to 1 of 2 destinations", and after as many of those in a row as the failure notification threshold, a notification names the endpoint's host.

Failed posts are retried up to 3 times with exponential backoff (1s, 2s), but only for transient failures: network errors, timeouts, HTTP 408, 429, and 5xx. Permanent client errors (401, 404, ...) fail immediately without retrying. The logs distinguish "recovered after retry" from "failed after all attempts".

Redirects are followed only on the same host: the same port, or `http` on port 80 moving up to `https` on 443, at most 5 in a row. The app sends the same POST there, with the same body, signature and headers, and the log notes the new address so you can enter it and skip the extra request. A redirect to another host, from `https` down to `http`, or to plain `http` without "Allow plain HTTP webhooks" is not followed: it would send the body, the signature and your custom headers to an address you did not enter, past the checks made on the one you did. Such a 3xx counts as a failed delivery, and the log names the host it pointed at: enter that final address as the webhook URL instead.

A payload that failed is kept in an outbox on the phone and sent again, oldest first, at the start of the next sync, with the settings the app has by then. The drain stops at the first payload that fails again, so the order holds while a receiver is down or misconfigured. One kind of refusal is skipped instead: HTTP 400, 413 and 422 say the receiver refuses this payload rather than every payload, so the payloads queued after it are sent anyway and may arrive before it (use `sequence` to order them). A skipped payload stays queued, in case the refusal came from a bug on the receiving side that an update fixes, and is dropped with a log row after a week.

The outbox holds up to 700 Health Connect payloads, a week of 15-minute syncs; beyond that the oldest is dropped, with a log row and a notification. Screen Time keeps only its newest failed week, which replaces the one queued before it, so expect gaps in its `sequence`; after more than a week without a delivery, days that fall out of that week are lost, again with a log row and a notification. One sync drains for at most 2 minutes and leaves the rest to the next.

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

## Inbound: what the integration may answer

From 1.20.0 the app can also take measurements the other way: a scale or a blood pressure monitor that talks to Home Assistant lands in Health Connect, and from there in Samsung Health or Google Health. This is **Receive** on the Health Connect tab, and it needs the [Life Dashboard integration](https://github.com/owen282000/life-dashboard-ha) 0.7.0 or later. There is no second channel: the measurements ride back in the integration's answer to the POST the app already makes. A receiver that is not the integration is not affected in any way; its answer is never read.

### What the app adds to its request

When Receive is on, the payload for **one** webhook URL, the source URL (the section's URL that contains `/api/webhook/`; the app asks which when there are several), carries a `writeback` block. Every other URL of the section gets the plain payload, and the block never appears in Screen Time or backfill payloads.

```json
"writeback": {
  "protocol": 1,
  "types": ["weight", "body_fat", "blood_pressure"],
  "history": false,
  "ack": ["sensor.zejulio_weight@1758869400000"],
  "failed": [
    {"id": "sensor.zejulio_body_fat@1758869400000", "code": "permission_denied"}
  ]
}
```

- `protocol` is always `1`.
- `types` are the types the user switched on **and** holds the Health Connect write permission for; the integration sends readings of those types only.
- `history` is `true` when "Accept older measurements" is on. It is informational: the app enforces the 30-day window itself.
- `ack` names the readings written since the previous request (inserted, or already present at the same or a higher version and therefore left alone).
- `failed` names the readings that were not written, each with a code from the table below. Never a value.

A sync that has nothing to send still makes one request to the source URL when Receive is on, a **heartbeat**: `timestamp`, `app_version`, `source` and the `writeback` block, no record arrays, no `daily_totals`, no `sequence`. It is signed like any other request and never queued in the outbox.

### The answer

The integration answers every accepted POST (status 200) with a JSON body and, since 0.7.0, a signature over it:

```
X-Signature: sha256=<hex of HMAC-SHA256(k_resp, raw response body)>
k_resp = HMAC-SHA256(key = secret as UTF-8 bytes, message = "life-dashboard-response-v1" as UTF-8 bytes)
```

The response key is derived from the shared secret and is never the secret itself, so a request the app signed can never be played back to it as an answer. The app checks, in this order, and stops at the first fault without writing anything: the body is at most 262144 bytes; the header is present and equal (in constant time) to its own computation; `life_dashboard.writeback` is `1`; `writeback.in_reply_to` equals the `X-Signature` the app put on this very request; `writeback.issued_at` is within 10 minutes of the phone's clock; and there are at most 200 readings. A rejected answer is one row in the Logs tab. An answer without the protocol block at all, which is what an integration older than 0.7.0 sends, makes the Receive row say "Update the Life Dashboard integration to receive measurements"; an app with Receive off never reads the body.

```json
{
  "life_dashboard": {"version": "0.7.0", "writeback": 1},
  "writeback": {
    "in_reply_to": "sha256=…",
    "issued_at": "2026-09-27T06:35:01Z",
    "configured": ["weight", "body_fat", "blood_pressure", "height"],
    "pending": [
      {
        "id": "sensor.zejulio_weight@1758955800000",
        "version": 1,
        "type": "weight",
        "kilograms": 81.35,
        "time": "2026-09-27T06:30:00Z",
        "zone_offset": "+02:00",
        "recording_method": "auto",
        "device": {"type": "scale", "manufacturer": "Xiaomi", "model": "Mi Body Composition Scale 2"}
      },
      {
        "id": "sensor.omron_systolic@1758955920000",
        "version": 1,
        "type": "blood_pressure",
        "systolic": 128.0,
        "diastolic": 82.0,
        "time": "2026-09-27T06:32:00Z",
        "zone_offset": "+02:00",
        "recording_method": "active",
        "body_position": "sitting_down",
        "measurement_location": "left_upper_arm",
        "device": {"type": "unknown", "manufacturer": "Omron", "model": "M7 Intelli IT"}
      }
    ],
    "more": false
  }
}
```

`configured` lists the types the integration has a mapping for, so the app offers a switch for exactly those. `pending` holds at most 200 readings, oldest first, and only of the types the request asked for; `more: true` says there are more waiting, and the app asks again in the same sync (in heartbeat form, at most five times) or on the next one. `pending` and `more` are absent when the request carried no `writeback.types`.

### A reading

| Field | Required | Content |
|---|---|---|
| `id` | yes | `{entity_id}@{measured_at_ms}`; becomes the Health Connect `clientRecordId` |
| `version` | yes | integer from 1; becomes `clientRecordVersion`. A correction of the same measurement is the same `id` with `version + 1` |
| `type` | yes | one of the types below |
| type fields | yes | the field names of the outbound payload, in the units Health Connect wants |
| `time` | yes | the measurement time in UTC with `Z`, never the sync time |
| `zone_offset` | no | `"+02:00"`; absent means the phone's zone |
| `recording_method` | no | `auto` (default), `active` or `manual` |
| `device` | no | `{"type", "manufacturer", "model"}`; `type` is one of `unknown`, `watch`, `phone`, `scale`, `ring`, `head_mounted`, `fitness_band`, `chest_strap`, `smart_display`, anything else counts as `unknown` |
| `time_source` | no | `"state"` when the integration used the entity's last change for lack of a timestamp entity; informational, it goes to the app's log |

| `type` | Fields | Health Connect record |
|---|---|---|
| `weight` | `kilograms` | WeightRecord |
| `height` | `meters` | HeightRecord |
| `body_fat` | `percentage` | BodyFatRecord |
| `lean_body_mass` | `kilograms` | LeanBodyMassRecord |
| `bone_mass` | `kilograms` | BoneMassRecord |
| `body_water_mass` | `kilograms` | BodyWaterMassRecord |
| `blood_pressure` | `systolic`, `diastolic`, optional `body_position` (`unknown`, `standing_up`, `sitting_down`, `lying_down`, `reclining`) and `measurement_location` (`unknown`, `left_wrist`, `right_wrist`, `left_upper_arm`, `right_upper_arm`) | BloodPressureRecord |

Unknown fields in a reading are ignored; an unknown `type` is reported as `unsupported_type`. BMI, muscle mass and visceral fat have no Health Connect record and are not offered.

### What the app checks before it writes

Every reading is validated on the phone, and a reading that fails is reported back under `failed` with one of these codes:

| Code | Meaning | What the integration does |
|---|---|---|
| `permission_denied` | the write permission for this type is missing or was revoked | drops it and raises a repair issue asking for the permission |
| `unsupported_type` | a type this app version does not know | drops it and asks to update the app |
| `out_of_range` | outside the bounds below | drops it and warns in the Home Assistant log, naming the entity and not the value |
| `too_old` | older than 30 days while `history` is off | drops it and warns; the backfill button explains the switch |
| `invalid` | a field missing or not a number, or a time more than 5 minutes in the future | drops it and warns |
| `rate_limited` | Health Connect's quota | keeps it and offers it again next round |
| `hc_unavailable` | Health Connect did not answer in time, or answered with an error | keeps it and offers it again next round |

Bounds, inclusive: weight 1 to 500 kg; height 0.3 to 2.8 m; body fat 1 to 80 %; lean body mass 1 to 300 kg; bone mass 0.1 to 30 kg; body water mass 1 to 300 kg; systolic 30 to 300 mmHg; diastolic 10 to 250 mmHg and below systolic. Exactly zero is always out of range.

### Idempotence

The `id` becomes the record's `clientRecordId` and the `version` its `clientRecordVersion`, which Health Connect scopes to the writing app. Sending the same reading again is therefore harmless (the app acknowledges it without a write once it knows the id at that version, and Health Connect ignores an equal or lower version anyway), and a correction with a higher version replaces the earlier record. The app cannot touch records other apps wrote. Records the app writes carry the app's own package as `source`, and the app leaves them out of its outgoing payloads and out of `deleted_records`, so what came from Home Assistant never goes back to it; `_diagnostics` counts them per type as `own_records_skipped`.

Three edges an integration can expect:

- **A lost answer from Health Connect.** When the insert succeeds but its answer arrives after the app's time budget, the records are in Health Connect while the app reports `hc_unavailable` and offers them again next round; the repeat is the same id at the same version and changes nothing. Until that repeat, the app does not yet know those records as its own, so a deletion of one of them in between would appear in `deleted_records` with a uuid the integration never handed out; ignore uuids you do not know.
- **A forgotten ledger.** The app remembers the last 5000 readings it wrote, and forgets all of them when the source URL or the secret changes. A record beyond that is still the app's own in Health Connect (it never goes back out as a record), but a later deletion of it can appear in `deleted_records` for the same reason as above.
- **Blood pressure above 200 mmHg.** The bounds the app checks (30 to 300 systolic, 10 to 250 diastolic) are wider than Health Connect's own constructor limits (20 to 200 systolic, 10 to 180 diastolic); a reading in between passes the app's validation and is then refused by Health Connect, which the app reports as `out_of_range` for that reading alone. Do not test the upper bounds with values above 200 and 180.

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
