# Data sources and their limits

Life Dashboard Companion forwards what Health Connect and Android's usage statistics contain. It cannot add what the source app never wrote, and it delivers late-arriving data only once the source has written it. This page collects what is known per source, mostly from user reports, so the same question does not have to be answered twice. For setting up a brand from scratch, see the [brand pages](brands/README.md). Corrections and additions are welcome as a pull request or issue.

## How records are picked up

Every sync reads each enabled type from Health Connect and keeps the records whose `metadata.lastModifiedTime` is newer than the per-type watermark from the previous batch. The watermark is based on modification time, not on the record's own timestamp. A record that a source app writes hours or days after the fact, with its original timestamp, is still delivered on the next sync after it appears. The query window starts a week before the previous sync that read the whole type, not a week before now. So this holds for records up to a week older than that sync, even when the phone did not sync for days in between. It reaches back 30 days at most. Edited records are re-sent the same way; deduplicate on `uuid` server-side.

A record that a source writes more than a week after its own timestamp while the phone keeps syncing, for instance a watch that was away from the phone for ten days, falls before the query window and is not delivered. The sync does see it in Health Connect's change feed and names it in `records_outside_window` (see [webhook.md](webhook.md#records-outside-the-read-window)), with the timestamp range a backfill of that period needs to send it.

The `_diagnostics` block of a Health Connect payload shows per type what Health Connect returned before and after that filter; [webhook.md](webhook.md#diagnostics) lists its fields. If `raw_latest_modified_time` is older than `last_sync`, Health Connect has nothing new for that type yet. That is the source app, not the filter.

## Health Connect sources

### Fitbit (`com.fitbit.FitbitMobile`)

Nightly metrics such as heart rate variability (5-minute RMSSD samples), respiratory rate (one value per night), sleep and SpO2 are written to Health Connect when the Fitbit app syncs after you wake up, often an hour or more later. This app delivers them on the first sync after that. Observed on a Galaxy S25 Ultra with Android 16 (September 2026): records for a night appeared in Health Connect around 07:45 local time and were in the next payload one minute later.

### Cronometer (`com.cronometer.android.gold`)

Writes energy, the macros and most micronutrients, including folate, riboflavin, niacin, B6 and B12. Does not write thiamin (B1), folic acid as a separate field, chloride or energy from fat, so those keys are absent from the payload even though the app supports them. Salt is not a Health Connect field at all; compute it from `sodium_mg` (salt in mg is sodium times 2.5). Cronometer documents only "Nutrition" as an export category, without a list per nutrient.

### Health Sync (`nl.appyhapps.healthsync`)

Nutrition records mirrored by Health Sync carry only calories, the macros, fiber, sugars, fat subtypes, cholesterol, sodium and potassium; vitamins and minerals are dropped and supplements end up as all zeros. When Health Sync mirrors an app that already writes to Health Connect itself (Cronometer, for example) you get the same meal twice, once per `source`. Disable nutrition in Health Sync in that case.

### Zepp and Garmin

Both upload watch data to Health Connect hours later, with the original timestamps. The modification-time watermark picks those records up on the next sync.

### UREVO (`com.urevo.app`)

The UREVO Android app writes treadmill sessions into Health Connect. Observed on a Foldi 3S with that app as the source:

| Type | Observed |
|---|---|
| Exercise sessions | Yes |
| Distance | Yes |
| Steps | Yes |
| Total calories | Yes |
| Speed | Not observed |

This app only forwards what Health Connect holds, so actual treadmill speed is not in the payload unless some other app writes it.

**Duplicate metric records.** One physical session was observed as a single Exercise record plus two Distance, two Steps and two Total Calories records. Each pair had different Health Connect UUIDs and the same `source`, type, start time, end time and value. Deduplicating only on `uuid` still double-counts if a receiver sums those records. For session totals, treat identical source, type, interval and value as one measurement and keep the UUIDs as provenance. This was seen with UREVO; other apps may not do it.

Synthetic example of that shape (not a real session):

```json
{
  "exercise": [
    {
      "type": "79",
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
    { "meters": 2760.0, "start_time": "2026-01-10T16:00:00Z", "end_time": "2026-01-10T16:35:00Z", "source": "com.urevo.app", "uuid": "synthetic-distance-a" },
    { "meters": 2760.0, "start_time": "2026-01-10T16:00:00Z", "end_time": "2026-01-10T16:35:00Z", "source": "com.urevo.app", "uuid": "synthetic-distance-b" }
  ],
  "total_calories": [
    { "calories": 239.7, "start_time": "2026-01-10T16:00:00Z", "end_time": "2026-01-10T16:35:00Z", "source": "com.urevo.app", "uuid": "synthetic-calories-a" },
    { "calories": 239.7, "start_time": "2026-01-10T16:00:00Z", "end_time": "2026-01-10T16:35:00Z", "source": "com.urevo.app", "uuid": "synthetic-calories-b" }
  ]
}
```

A receiver that stores both steps records and adds them reports 7500 steps for a 3750-step session. The same doubling applies to distance (5520 m instead of 2760 m) and total calories (479.4 instead of 239.7).

**Session association.** For the observed UREVO data, the Exercise record is a useful session anchor. Matching Distance, Steps and Total Calories records shared that producer and the exact start and end times. `daily_totals` are Health Connect's whole-day aggregates across sources and activities; they are not measurements of one exercise session.

### Gadgetbridge (`nodomain.freeyourgadget.gadgetbridge`)

Gadgetbridge writes steps, distance and calories as one record per minute, with a client record ID made from the minute's end time, and each export writes the last hour again, so that late watch data lands in the right minute. With a Huawei watch, the way Gadgetbridge lays the watch's samples out per minute puts the last minute of data in an export one minute late. The next export writes that minute again in its right place, and also writes the minute after it with its real value, which replaces the misplaced copy. When that next minute has no steps, Gadgetbridge writes nothing for it, and the misplaced copy stays: Health Connect then holds the same count twice, in two adjacent minutes, under different IDs.

A user with a Huawei Watch GT 3 Pro reported this in September 2026, and Gadgetbridge's code explains it: the one-hour rewrite has existed since 0.92.0, and the misplaced minute is still there in 0.94.0. It adds at most one minute per export, and only when you were idle in the minute after the export's last activity, as you usually are while the watch syncs. Distance and calories go through the same code.

The copy covers a different minute, so it looks like real activity to everything downstream. Deduplicating on `uuid`, or on source, type, interval and value as for UREVO above, keeps it, and so does `daily_totals`: Health Connect only merges records that overlap. The Health Connect app and Home Assistant's step sensors and history show the same extra minute. The fix belongs in Gadgetbridge.

### Several sources for the same activity

Phone, watch app, Samsung Health or a mirroring app can each write their own copy of the same steps, distance or calories. Adding up the raw records then counts the same activity two or three times. The `daily_totals` array, on by default, uses Health Connect's aggregate API, which deduplicates across sources, and matches what the Health Connect app shows. Use it for the totals per day and keep the raw records for detail. A single source can also write two records for the same interval (see [UREVO](#urevo-comurevoapp) above). `daily_totals` counts such an overlap once, from the record written last, but it stays a day's figure, not a per-session one. Records that do not overlap all count, including a copy that a source put in the wrong minute (see [Gadgetbridge](#gadgetbridge-nodomainfreeyourgadgetgadgetbridge) above).

## Screen time (UsageStatsManager)

- Per-app minutes are foreground time only, derived from activity resume, pause and stop events. Background time and foreground services are not counted.
- A package is in the foreground while at least one of its activities is resumed. Screen off, keyguard and shutdown end every open session, so a missed pause event is bounded by the next screen off rather than the end of the day.
- System UI and the launcher are left out, as in Digital Wellbeing. Apps with one minute or less in a day are left out.
- Every sync recomputes the last 7 days from the device's event log and sends all 7 again. Store per date and let the payload with the highest `sequence` win, as [webhook.md](webhook.md#screen-time-payload) explains; days older than 7 days are not re-sent, and the device does not keep events much longer anyway.
- With the day boundary on (the default, at 04:00) totals will not match Digital Wellbeing's midnight day exactly. Observed on a Pixel 9a with Android 17: 388 minutes versus 404 in Digital Wellbeing for the same day.
- Recent Android versions emit `ACTIVITY_STOPPED` without a preceding `ACTIVITY_PAUSED` more often than older ones did. The app ends a session on either event; counting such a session until the end of the day would give per-app values of 10 to 15 hours.

## Reporting a source problem

Include the `_diagnostics` entry for the type, the `source` value of the records involved, your device and Android version, and the source app. Diagnostics contain no health values, only counts and timestamps.
