# Data sources and their limits

Life Dashboard Companion forwards what Health Connect and Android's usage statistics contain. It cannot add what the source app never wrote, and it delivers late-arriving data only once the source has written it. This page collects what is known per source, mostly from user reports, so the same question does not have to be answered twice. For setting up a brand from scratch, see the [brand pages](brands/README.md). Corrections and additions are welcome as a pull request or issue.

## How records are picked up

Every sync reads each enabled type from Health Connect and keeps the records whose `metadata.lastModifiedTime` is newer than the per-type watermark from the previous delivered batch. The watermark is based on modification time, not on the record's own timestamp, so a record that a source app writes hours or days after the fact (with its original, older timestamp) is still delivered on the next sync after it appears. The query window starts a week before the previous sync that read the whole type, rather than a week before now, so this holds for records up to a week older than that sync, also when the phone did not sync for days in between. It reaches back 30 days at most. Edited records are re-sent the same way; deduplicate on `uuid` server-side.

A record that a source writes more than a week after its own timestamp while the phone keeps syncing, for instance a watch that was away from the phone for ten days, falls before the query window and is not delivered. The sync does see it in Health Connect's change feed and names it in `records_outside_window` (see [webhook.md](webhook.md#deletions)), with the timestamp range a backfill of that period needs to send it.

The `_diagnostics` block in every payload shows per type what Health Connect returned before and after that filter:

| Field | Meaning |
|---|---|
| `raw_record_count` | Records Health Connect returned for the query window |
| `raw_min_time`, `raw_max_time` | Timestamp range of those records |
| `raw_latest_modified_time` | Newest modification time among them |
| `filtered_record_count` | Records newer than the watermark, i.e. delivered in this payload |
| `min_time`, `max_time` | Timestamp range of the delivered records |
| `last_sync` | The watermark this sync filtered against |
| `read_from` | Start of the query window |
| `lookback_gap_from` | Set only after a pause too long for the window: records timestamped from here to `read_from` that changed during the pause were not read; a backfill of that range sends them. Named until a payload has carried it |

If `raw_latest_modified_time` is older than `last_sync`, Health Connect simply has nothing new for that type yet. That is the source app, not the filter.

## Health Connect sources

### Fitbit (`com.fitbit.FitbitMobile`)

Nightly metrics such as heart rate variability (5-minute RMSSD samples), respiratory rate (one value per night), sleep and SpO2 are written to Health Connect when the Fitbit app syncs after you wake up, often an hour or more later. The companion app delivers them on the first sync after that. Observed on a Galaxy S25 Ultra with Android 16 (September 2026): records for a night appeared in Health Connect around 07:45 local time and were in the next payload one minute later.

### Cronometer (`com.cronometer.android.gold`)

Writes energy, the macros and most micronutrients, including folate, riboflavin, niacin, B6 and B12. Does not write thiamin (B1), folic acid as a separate field, chloride or energy from fat, so those keys are absent from the export even though the app supports them. Salt is not a Health Connect field at all; compute it from `sodium_mg` (salt in mg is sodium times 2.5). Cronometer documents only "Nutrition" as an export category, without a list per nutrient.

### Health Sync (`nl.appyhapps.healthsync`)

Nutrition records mirrored by Health Sync carry only calories, the macros, fibre, sugars, fat subtypes, cholesterol, sodium and potassium; vitamins and minerals are dropped and supplements end up as all zeros. When Health Sync mirrors an app that already writes to Health Connect itself (Cronometer, for example) you get the same meal twice, once per `source`. Disable nutrition in Health Sync in that case.

### Zepp and Garmin

Upload watch data to Health Connect hours later with the original timestamps. The modification-time watermark picks those records up on the next sync.

### UREVO (`com.urevo.app`)

The UREVO Android app writes treadmill sessions into Health Connect. Observed on a Foldi 3S with that app as the source:

| Type | Observed |
|---|---|
| Exercise sessions | Yes |
| Distance | Yes |
| Steps | Yes |
| Total calories | Yes |
| Speed | Not observed |

The companion app only forwards what Health Connect holds, so actual treadmill speed is not in the payload unless some other app writes it.

**Duplicate metric records.** One physical session was observed as a single Exercise record plus two Distance, two Steps and two Total Calories records. Each pair had different Health Connect UUIDs and the same `source`, type, start time, end time and value. Deduplicating only on `uuid` still double-counts if a receiver sums those records. For session totals, treat identical source, type, interval and value as one measurement and keep the UUIDs as provenance. This was seen with UREVO; it is not assumed for every Health Connect producer.

Synthetic example of that shape (not a real session):

```json
{
  "exercise": [
    {
      "type": "walking",
      "start_time": "2026-01-10T16:00:00Z",
      "end_time": "2026-01-10T16:35:00Z",
      "duration_seconds": 2100,
      "source": "com.urevo.app",
      "uuid": "synthetic-exercise"
    }
  ],
  "steps": [
    { "count": 3750, "start_time": "2026-01-10T16:00:00Z", "end_time": "2026-01-10T16:35:00Z", "source": "com.urevo.app", "uuid": "synthetic-steps-a" },
    { "count": 3750, "start_time": "2026-01-10T16:00:00Z", "end_time": "2026-01-10T16:35:00Z", "source": "com.urevo.app", "uuid": "synthetic-steps-b" }
  ],
  "distance": [
    { "meters": 2760, "start_time": "2026-01-10T16:00:00Z", "end_time": "2026-01-10T16:35:00Z", "source": "com.urevo.app", "uuid": "synthetic-distance-a" },
    { "meters": 2760, "start_time": "2026-01-10T16:00:00Z", "end_time": "2026-01-10T16:35:00Z", "source": "com.urevo.app", "uuid": "synthetic-distance-b" }
  ],
  "total_calories": [
    { "calories": 239.7, "start_time": "2026-01-10T16:00:00Z", "end_time": "2026-01-10T16:35:00Z", "source": "com.urevo.app", "uuid": "synthetic-calories-a" },
    { "calories": 239.7, "start_time": "2026-01-10T16:00:00Z", "end_time": "2026-01-10T16:35:00Z", "source": "com.urevo.app", "uuid": "synthetic-calories-b" }
  ]
}
```

A receiver that stores both steps records and adds them reports 7500 steps for a 3750-step session. The same doubling applies to distance (5520 m instead of 2760 m) and total calories (479.4 instead of 239.7).

**Session association.** For the observed UREVO data, the Exercise record is a useful session anchor. Matching Distance, Steps and Total Calories records shared that producer and the exact start and end times. `daily_totals` are Health Connect's whole-day aggregates across sources and activities; they are not measurements of one exercise session.

### Several sources for the same activity

Phone, watch app, Samsung Health or a mirroring app can each write their own copy of the same steps, distance or calories. Adding up the raw records then counts the same activity two or three times. The `daily_totals` array, on by default, uses Health Connect's aggregate API, which deduplicates across sources, and matches what the Health Connect app shows. Use it for day totals and keep the raw records for detail. A single source can also write two records for the same interval (see [UREVO](#urevo-comurevoapp) above); `daily_totals` still does not turn those into a per-session figure.

## Screen time (UsageStatsManager)

- Per-app minutes are foreground time only, derived from activity resume, pause and stop events. Background time and foreground services are not counted.
- A package is in the foreground while at least one of its activities is resumed. Screen off, keyguard and shutdown end every open session, so a missed pause event is bounded by the next screen off rather than the end of the day.
- System UI and the launcher are excluded, as Digital Wellbeing does. Apps with under one minute per day are omitted.
- Every sync recomputes the last 7 days from the device's event log and sends all 7 again. Store per date and let the newest payload win; days older than 7 days are not re-sent, and the device does not keep events much longer anyway.
- With a custom day boundary (4 AM, for example) totals will not match Digital Wellbeing's midnight day exactly. Observed on a Pixel 9a with Android 17: 388 minutes versus 404 in Digital Wellbeing for the same day.
- Recent Android versions emit ACTIVITY_STOPPED without a preceding ACTIVITY_PAUSED more often than older ones did. Versions before 1.10.2 counted such a session until the end of the day, which produced per-app values of 10 to 15 hours.

## Reporting a source problem

Include the `_diagnostics` entry for the type, the `source` value of the records involved, your device and Android version, and the source app. Diagnostics contain no health values, only counts and timestamps.
