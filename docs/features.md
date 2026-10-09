# Features

What Life Dashboard Companion does, section by section. For setup and day-to-day use see [usage.md](usage.md); for the payload your server receives see [webhook.md](webhook.md).

## At a glance

- **Every record.** Each heart rate sample, sleep stage, meal and workout, with the app that wrote it and a stable ID, plus deduplicated daily totals.
- **Home Assistant, MQTT or any webhook.** The Life Dashboard integration (paired by QR code), MQTT Discovery, or any server that accepts a JSON POST.
- **A year of history.** Backfill sends up to 365 days, so your graphs start full.
- **Two-way with Health Connect.** With the Life Dashboard integration, readings from a scale or blood pressure monitor in Home Assistant go into Health Connect, where Samsung Health and Google Health pick up the weight and body fat.
- **Screen time per app**, next to your health data, with a day that ends when you go to bed.
- **No cloud of its own and no analytics.** Data goes only to the destinations you set.

## Contents

- [Health data](#health-data)
- [Screen time](#screen-time)
- [Destinations](#destinations)
- [Scheduling](#scheduling)
- [History and backfill](#history-and-backfill)
- [Reliability](#reliability)
- [Receiving from Home Assistant](#receiving-from-home-assistant)
- [Privacy and security](#privacy-and-security)
- [Accessibility and languages](#accessibility-and-languages)
- [Widget, tile and automation apps](#widget-tile-and-automation-apps)
- [Status on the phone](#status-on-the-phone)
- [Preview, export and logs](#preview-export-and-logs)
- [Settings backup](#settings-backup)
- [How this compares to the Home Assistant companion app](#how-this-compares-to-the-home-assistant-companion-app)

<a id="33-supported-data-types"></a>

## Health data

The app reads 33 Health Connect data types. Each has its own switch and its own permission, so you share only what you choose. Every enabled type goes to your webhooks as individual records; 24 of them also become a sensor in Home Assistant, through the integration or MQTT. The integration adds minutes per day for workouts and mindfulness sessions.

| Category | Type | Sensor in Home Assistant |
|---|---|---|
| Activity | Steps | Today's total |
| Activity | Distance | Today's total |
| Activity | Active calories | Today's total |
| Activity | Total calories | Today's total |
| Activity | Exercise sessions | No; minutes per day in the integration's statistics |
| Body | Weight | Latest value |
| Body | Height | Latest value |
| Body | Body temperature | Latest value |
| Body | Skin temperature | Latest value (the delta) |
| Body | Basal body temperature | Latest value |
| Body composition | Body fat | Latest value |
| Body composition | Lean body mass | Latest value |
| Body composition | Bone mass | Latest value |
| Body composition | Body water mass | Latest value |
| Vitals | Heart rate | Latest value |
| Vitals | Resting heart rate | Latest value |
| Vitals | Heart rate variability (HRV) | Latest value |
| Vitals | Blood pressure | Latest value (systolic and diastolic) |
| Vitals | Blood glucose | Latest value |
| Vitals | Oxygen saturation | Latest value |
| Vitals | Respiratory rate | Latest value |
| Vitals | Basal metabolic rate | Latest value |
| Vitals | VO2 max | Latest value |
| Sleep | Sleep sessions, with stages | Last sleep duration |
| Nutrition | Hydration | Latest value |
| Nutrition | Nutrition (meals and nutrients) | No, records only |
| Mindfulness | Meditation sessions (from apps such as Waking Up or Headspace) | No; minutes per day in the integration's statistics |
| Cycle tracking | Menstruation period | No, records only |
| Cycle tracking | Menstruation flow | No, records only |
| Cycle tracking | Intermenstrual bleeding | No, records only |
| Cycle tracking | Ovulation test | No, records only |
| Cycle tracking | Cervical mucus | No, records only |
| Cycle tracking | Sexual activity | No, records only |

Cycle tracking covers what cycle apps such as Clue and Flo write to Health Connect. Samsung Health doesn't share cycle data with Health Connect, and predictions stay in the source app. What individual source apps do and don't write is collected in [DATA_SOURCES.md](DATA_SOURCES.md).

**Daily totals.** Next to the raw records, each payload carries `daily_totals` for steps, distance and calories, computed by Health Connect so that a walk recorded by both your phone and your watch counts once. On by default; see [webhook.md](webhook.md#daily-totals).

**Record metadata.** Off by default. Under **Advanced** on the **Health** tab, **Record metadata in payload** adds Health Connect's metadata to every record: when the source last changed it, the source's own ID and version, the recording method, the device and the time zone offsets. See [webhook.md](webhook.md#record-metadata).

### Data resolution

Dense series can make payloads large: a heart rate sample per second is 86,400 records a day. Under **Data Resolution** on the **Health** tab you choose, per type, whether to send every record or one value per 1, 5, 15 or 60 minutes.

- Measured values (heart rate, for example) are averaged, with the minimum and maximum kept, so you can still tell sleep from a sprint. Accumulated values (steps, distance, calories) are summed.
- Windows align to the clock, and every window says how many samples went into it. A window still filling when a sync runs waits until it is complete.
- The payload names the resolution it used per series, so the receiver needs no matching setting.
- Everything defaults to every record. Bucketing loses detail, so the app never turns it on for you.

[webhook.md](webhook.md#data-resolution) shows the bucketed shape, and how a window that's sent again replaces the earlier copy.

## Screen time

- Foreground time per app, read from Android's usage statistics. System UI and the launcher are left out, so the totals line up with Digital Wellbeing.
- **A custom day boundary**, on by default at 04:00: phone use between midnight and 04:00 counts toward the day before. Under **Day Boundary** on the **Screen Time** tab, **Day starts at** sets the hour and **Enable day boundary** switches it off.
- Every sync sends the last 7 days, so the next sync corrects a day that was still in progress at the last one.
- App names are resolved from package names.
- **Apps to send.** Send every app (**All**), every app except the ones you select (**All except**), or only the ones you select (**Only**). The list shows the apps you used in the last 30 days, with their minutes; when it's long, **Search apps** finds one by name. With **Only** and nothing selected, only the daily totals go out. An app that's filtered out never leaves the phone: it isn't in the payload, not on MQTT and not among the top apps. `total_screen_time_minutes` still counts the apps you left out, and `filtered_screen_time_minutes` holds the time of the apps that are sent. See [webhook.md](webhook.md#which-apps-are-sent).

Screen time has its own webhooks, schedule and MQTT switch, separate from Health Connect.

## Destinations

A section (Health Connect or Screen Time) can send to the Life Dashboard integration, to MQTT, to webhooks of your own, or to several at once.

### Home Assistant and MQTT

There are two ways into Home Assistant. Use one, not both: with both you get two devices holding the same numbers.

**The [Life Dashboard integration](https://github.com/owen282000/life-dashboard-ha)** is the recommended one. You install it through HACS (as a custom repository for now) and pair the phone by scanning a QR code. It receives the webhook directly, needs no broker, keeps every day in long-term statistics (a backfill and screen time included), and comes with an example dashboard. It is also the only way to [receive measurements](#receiving-from-home-assistant) on the phone.

**MQTT with Home Assistant Discovery** suits a setup that already has a broker and only needs current values. Point the app at the broker, and sensors for 24 of the 33 types, plus screen time, appear under one device without any configuration in Home Assistant.

<img src="screenshots/mqtt.png" alt="The MQTT section in the app: broker host, port, optional credentials and a shared base topic" width="300" align="right">

<img src="screenshots/home-assistant.png" alt="The device Home Assistant creates from the app's MQTT discovery messages, with its sensors" width="560">

#### The sensors

Both routes create the same sensors, each one the first time its type arrives, so you only get the types you sync. Grouped by key:

- **Today's totals:** `steps_today`, `distance_today`, `active_calories_today`, `total_calories_today`, each with the day as a `date` attribute.
- **Latest value:** `heart_rate`, `resting_heart_rate`, `heart_rate_variability`, `sleep_duration`, `weight`, `height`, `blood_pressure_systolic`, `blood_pressure_diastolic`, `blood_glucose`, `oxygen_saturation`, `body_temperature`, `skin_temperature_delta`, `basal_body_temperature`, `respiratory_rate`, `hydration`, `body_fat`, `lean_body_mass`, `bone_mass`, `body_water_mass`, `basal_metabolic_rate`, `vo2_max`. Each carries `measured_at`, `source` (the app that wrote the record) and `uuid` (the record's ID) as attributes. In the integration, a type you send per time window under **Data Resolution** shows the average of the newest window instead, with `sample_count`, `min`, `max` and `sources` as attributes.
- **Screen time:** `screen_time_today` and `screen_time_yesterday` in minutes, with `date`, `app_count` and `top_apps` (the top five with their minutes) as attributes, and `screen_time_top_app`, today's most used app, with `package`, `minutes` and `date`. The integration adds a sensor per app, described [below](#what-only-the-integration-adds). With an app filter on, the minutes are those of the apps that are sent and `all_apps_minutes` holds the real total (in the integration, 0.8.0 or newer).

Over MQTT the key is part of the topic (`<base>/<key>/state`), and the sensors sit on a device called Life Dashboard Companion. With the integration, they sit on a device with the **Name** you gave when you added the integration, and the entity ID is that name plus the key: "Pixel 8" gives `sensor.pixel_8_steps_today`. Three sensors have a different name in the integration:

| MQTT key | Integration entity |
|---|---|
| `sleep_duration` | `sensor.<phone>_last_sleep_duration` (**Last sleep duration**) |
| `hydration` | `sensor.<phone>_last_drink` (**Last drink**) |
| `screen_time_top_app` | `sensor.<phone>_most_used_app_today` (**Most used app today**) |

The other nine types (exercise, nutrition, mindfulness and the six cycle tracking types) are events, not single values, so neither route turns them into sensors. The integration counts workouts and mindfulness sessions as minutes per day in its statistics. Single meals, cycle tracking entries and workout details reach only a webhook of your own, as records.

#### What only the integration adds

- **Long-term statistics.** One value per day for steps, distance, active and total calories, sleep minutes, exercise minutes, mindfulness minutes, hydration and screen time, each on its own date, so a year of backfill shows up as a year of days. Heart rate, weight, blood pressure and the other measured values get an hourly mean, minimum and maximum. The IDs look like `life_dashboard:<entry id>_steps`; pick them by name in the **Statistics graph** card.
- **A sensor per app.** Every app gets a sensor of its own with its minutes today, such as `sensor.<phone>_youtube_screen_time`, with `app`, `package`, `date` and `week_minutes` as attributes. They are created disabled: show the disabled entities on the phone's device and enable the apps you want. An app needs 5 minutes over the days a sync carries to get one, a phone gets 50 at most, and a sensor you delete stays deleted. An app missing from a newer day, or left out with the app filter since, reads 0 for that day. Requires integration 0.9.0 or newer.
- **Diagnostics.** `last_health_sync` and `last_screen_time_sync` hold the time of the last payload of each kind. A **Test ping** updates them too, which is the quickest check that pairing worked.
- **History for the phone.** Once an entity is mapped for [receiving](#receiving-from-home-assistant), a **Send history to phone** button queues up to 30 days of its readings for the phone.

#### How MQTT behaves

- States and discovery configs are retained, so values survive a Home Assistant restart. Every publish carries every sensor the app has mapped so far, so a new broker or a fresh Home Assistant sees the whole device after one sync.
- TLS and a username and password are optional. The credentials are stored encrypted. When the broker isn't on a private network and TLS is off, the settings warn you.
- Health Connect and Screen Time each have their own switch and **Base topic**. They share one broker connection by default. Switch off **Use the shared broker** in a tab's **MQTT** row to give that section a broker of its own.
- **Topics.** The **Base topic** is `lifedashboard` unless you change it. Each sensor has a state topic `<base>/<key>/state` and a JSON attributes topic `<base>/<key>/attributes`, and its discovery config is at `homeassistant/sensor/life_dashboard_companion_<key>/config`.
- **Two phones on one broker:** give each a name under **Advanced > Phone name**. A named phone publishes under its own device, such as `Life Dashboard Companion (Pixel 8)`, with its name as a slug in its topics (`<base>/<slug>/<key>/state`, `<base>/<slug>/<key>/attributes`) and in its discovery topic (`homeassistant/sensor/life_dashboard_companion_<slug>_<key>/config`). A phone without a name keeps the plain topics.

The step-by-step setup for both is in [usage.md](usage.md#phone-to-home-assistant).

<a id="webhook-configuration"></a>

### Webhooks

Any server that accepts a JSON POST can receive the data: your own backend, Node-RED, n8n, or the example [life-dashboard-stack](https://github.com/owen282000/life-dashboard-stack).

- **Several URLs per section**, and separate URLs, headers and signing secrets for Health Connect and Screen Time.
- **Custom headers** for auth tokens or API keys. They go to the URLs you typed in, never to one that QR pairing added.
- **HMAC signing.** With a signing secret set, every request carries an `X-Signature` header (HMAC-SHA256 of the body), so your server can check the sender. **Generate** creates a secret of 32 random bytes as hex.
- **Test ping** sends a small payload marked `test: true`, signed when a secret is set, so you can check an address and a secret before any data goes there.
- **Retries.** A request that fails for a temporary reason is tried three times with a growing wait in between; a permanent error fails at once.
- **QR pairing** with the Home Assistant integration fills in the address and the secret after one confirmation. See [Pairing by QR code](usage.md#pairing-by-qr-code).

Retry rules are in [webhook.md](webhook.md#responses-retries-and-timeouts), and how to check a signature in [Verifying the signature](webhook.md#verifying-the-signature).

### More than one destination

A section can have several webhooks and MQTT at the same time. When a sync reaches some webhooks and not others, the line under **Sync Now** says so, for example "Delivered to 1 of 2 destinations, see Logs." If it keeps happening for as many syncs in a row as you set under **Notifications** (3, 5 or 10), a notification names the host that keeps failing. The app doesn't queue the missed data for that address, so check the **Logs** tab.

## Scheduling

<img src="screenshots/sync-schedule.png" alt="Sync Schedule set to fixed times: 09:00, 11:00 and 14:00, on Monday, Wednesday, Thursday, Saturday and Sunday" width="300" align="right">

Health Connect and Screen Time each have their own **Sync Schedule**, so screen time can sync every hour while health data syncs at 08:00 and 21:00.

- **Every X minutes**, 15 minutes at the shortest (Android's limit for background work).
- **At fixed times**, a list of times of day. This suits data that arrives in batches: a watch writes the night to Health Connect when it syncs in the morning, so a sync at 08:00 puts last night's sleep, resting heart rate and HRV in Home Assistant before you look.
- **Days**: any set of weekdays.
- **Quiet hours**: never sync between two times. An interval sync resumes at the end of the window; a fixed time inside the window is skipped, not moved.
- The schedule warns when a combination would never sync (no days, no times, or every time inside the quiet hours).
- **Sync Now** runs a sync at any moment. The schedule is part of the [settings backup](settings-backup.md).

## History and backfill

A regular sync reads what changed since the last one, reaching back a week for records that a source writes late. To fill a receiver with older data, use backfill.

### Backfill

**Backfill** on the **Health** tab sends the last 30, 90 or 365 days of every enabled type to your webhooks.

- It goes in 3-day chunks, oldest first, and doesn't touch what the regular sync keeps track of. A receiver can drop records sent twice by their `uuid`.
- Data from more than 30 days before you first gave the app access needs Health Connect's history access, which the dialog asks for.
- Backfill goes to webhooks, and the Life Dashboard integration counts as one. MQTT carries only the latest value of each type, so a backfill has nothing to add there.
- As a background job, it keeps going when you leave the screen; the tab shows its progress when you come back, and **Stop** ends it.
- When Android stops it, or Health Connect's read quota runs out, it continues by itself from the last payload that went through. After a failed delivery, start a backfill of the same length within a day and it continues from there.
- After six runs in a row that sent nothing, it stops and says why.
- Switching data types on or off while it runs starts it over at the first chunk, so every chunk carries the same types.
- The **Logs** tab shows one row per run, with how far it got and how many records it sent.

The payload fields a receiver can use to reconcile a window (`backfill`, `window_start`, `window_end`, `window_complete`) are described in [webhook.md](webhook.md#backfill-windows).

## Reliability

- **A store-and-forward outbox.** Each sync writes its payload to the phone before it moves on, and deletes that copy only once a webhook has accepted it. When the server is down, the network is gone, or Android kills the app partway through, the payload waits in the outbox and the next sync delivers it first. The outbox holds up to 700 Health Connect payloads, a week of failed syncs at the 15-minute interval. Screen time keeps only its newest snapshot, since each one carries the whole week.
- **Deletions.** When a record is deleted in Health Connect, the next payload names it in `deleted_records`, so a receiver can drop it. See [webhook.md](webhook.md#deletions).
- **Catch-up after a pause.** A phone that was off, asleep or force-stopped picks up where it left off, reaching back up to 30 days. When the pause was longer, the payload says which range it couldn't read, and a backfill fills it.
- **Health Connect's read quota.** When Health Connect refuses reads because its quota is used up, the sync delivers what it has read so far and the next sync continues. It isn't counted as an outage.
- **Pairing checks itself.** Right after a QR pairing, the app sends a test ping with the new secret and says "Paired with <host>, test ping delivered", or why the ping failed.
- **Failure notifications.** After a number of failed syncs in a row (3 by default; pick 3, 5 or 10 under **Notifications**), a local notification tells you, with the last error. [usage.md](usage.md#notifications-optional) lists every notification the app posts, including the one for data the outbox had to drop.

Background work runs on Android's WorkManager. The app checks that Health Connect is installed and up to date, and says what to do when it isn't.

## Receiving from Home Assistant

The other direction: readings from a scale or a blood pressure monitor that talks to Home Assistant, and not to the phone, go into Health Connect. The **Receive** row on the **Health** tab switches it on per type, and the [Life Dashboard integration](https://github.com/owen282000/life-dashboard-ha) (0.7.0 or later) chooses which entities go to which phone. There is no second channel and no second secret. The measurements come back in the integration's signed response to the webhook call the app already makes, and that response is tied to its request. Nothing between the phone and Home Assistant can slip a reading in.

- **Seven types:** weight, height, body fat, lean body mass, bone mass, body water mass and blood pressure. Each has its own switch and its own Health Connect write permission, asked for when you switch it on. Refusing the permission leaves the type off.
- Measurements keep the time they were taken. A repeat is an update, not a second record: the integration's ID and version become Health Connect's client record ID and version, so a resend changes nothing and a correction replaces the old value.
- Readings older than 30 days are refused unless **Accept older measurements** is on. The integration's **Send history to phone** button sends up to 30 days, as far back as Home Assistant's recorder keeps states (10 days by default); its `life_dashboard.queue_history` action goes back up to 90.
- What the app writes never goes back out: those records are left out of the outgoing payload and of `deleted_records`, and counted in `_diagnostics` as `own_records_skipped`.
- If another app already writes the same type to Health Connect, the app warns you as you switch the type on: a scale's own app plus Home Assistant means two readings a day.
- Each response from Home Assistant that carries readings gets a row in the **Logs** tab, and so does a response the app rejects. The row expands to show each reading and its outcome. The values show only when **Keep full payloads**, at the top of the **Logs** tab, is on.

This app can only promise that a reading reaches Health Connect. Whether another app shows it is up to that app. Checked on September 26, 2026:

| App | Reads from Health Connect |
|---|---|
| Google Health (the Fitbit app's successor) | Weight, body fat, blood glucose, exercise and nutrition; blood pressure isn't in its list |
| Samsung Health | Says it reads and writes, and confirmed weight and body fat from a Withings scale through Health Connect in May 2026; community reports say body composition doesn't always come through, and blood pressure from third parties is unverified |
| Garmin Connect | Doesn't read weight from Health Connect |

The protocol is in [webhook.md](webhook.md#inbound-what-the-integration-may-send-back). The setup per scale (Xiaomi, Renpho, Eufy, Withings or a cloud account) is in [recipes/scale-to-health-connect.md](recipes/scale-to-health-connect.md).

## Privacy and security

- **No account, no cloud of its own, no analytics or crash reporting.** The app sends data only to the webhooks and brokers you set. [PRIVACY.md](../PRIVACY.md) has the full policy.
- **Encrypted secrets.** Auth headers, signing secrets and MQTT passwords are encrypted with AES-256-GCM, using a key that the Android Keystore generates and holds. They are never stored unencrypted: when the Keystore can't be used, they are neither read nor saved until it can. If the key is lost for good, or an update from 1.22.0 or older skips 1.23.0 (which moved them out of the old store this version can no longer read), a banner says so and asks you to enter them again.
- **HTTPS by default.** An `http://` URL is refused unless you switch on **Allow plain HTTP webhooks**, which is meant for a Home Assistant server or receiver you only reach on your LAN or over a VPN.
- **Client certificates (mTLS).** The app can present a certificate from Android's credential store to every webhook, for servers behind a reverse proxy that requires one. Install it in Android's settings first, then pick it under **Advanced > Client certificate (mTLS)** with **Choose**. MQTT doesn't use it.
- **Android backup leaves out** the secrets, the webhook logs (which hold raw health data) and the Receive ledger. Your other settings carry over to a new phone through Android's backup and device transfer; the secrets move only through an encrypted [settings backup](settings-backup.md).
- **Exports are cleaned up.** An exported file waits in the app's cache until the next export replaces it, or until the app starts more than a day later. See [PRIVACY.md](../PRIVACY.md#what-stays-on-the-device).

## Accessibility and languages

- **TalkBack.** Every switch row is one control that TalkBack reads with its name and state, such as "Allow plain HTTP webhooks, off, switch", and a tap anywhere on the row flips it. Selected tabs, filters and schedule days are announced, as is the result of a sync or a test ping. Status that was only a color is spoken too: the widget reads "Last sync succeeded" or "Last sync failed".
- **Contrast.** Text in the accent and status colors reads at 4.5:1 or more, in both the light and dark themes.
- **Material 3** with a dark theme that follows the system.
- **Languages:** English, Dutch and German, following the system language.

<a id="automation"></a>

## Widget, tile and automation apps

- **Home screen widget** with the last sync result and the number of records delivered today, for both sections together.
- **Quick Settings tile** (**Sync Life Dashboard**) that syncs Health Connect and Screen Time from the notification shade. Add it by editing the Quick Settings panel.
- **Tasker and MacroDroid** can start a sync with a broadcast. The package is required: Android doesn't deliver the broadcast to the app without it.
  - In Tasker, add the **System > Send Intent** action with Action `com.owen282000.lifedashboard.ACTION_SYNC`, Package `com.owen282000.lifedashboard` and Target **Broadcast Receiver**.
  - In MacroDroid, add the **Send Intent** action with the same action and package name, and target **Broadcast**.
  - From a computer: `adb shell am broadcast -n com.owen282000.lifedashboard/.SyncBroadcastReceiver -a com.owen282000.lifedashboard.ACTION_SYNC`

  It runs a normal sync of Health Connect and Screen Time to the destinations you configured, nothing more. It reads no extras from the intent.
- The tile and the broadcast share a one-minute limit: a second trigger within a minute of the last accepted one is ignored, and on Android 10 and later the tile says "Try again in a minute".

## Status on the phone

The top of the **Health** tab shows how many records went out **Today** and over the app's **Lifetime**, when the **Last sync** ran and whether it worked, and a small chart of your steps per day over the last week. The **Screen Time** tab shows today's minutes, the **Top app** of the day, the **Last sync**, and a chart of minutes per day over the last week.

## Preview, export and logs

- **Preview:** **View** on the **Health** and **Screen Time** tabs shows the JSON payload the next sync would send, without sending it. A long payload is truncated on screen; **Export** has all of it.
- **Export:** **Export** on the **Health** and **Screen Time** tabs shares the current data as JSON through the Android share sheet.
- **Logs:** the **Logs** tab lists up to the last 100 webhook deliveries, MQTT publishes, backfill runs and readings received from Home Assistant, with status, error and payload. Tap a row to see its details. The switch at the top, **Keep full payloads**, stores whole payloads; otherwise they're shortened to save space. Below it, a summary shows the success rate, the number of deliveries and of records delivered, each section's successes, and the time of the last success. **All**, **Health** and **Screen Time** filter the list. **Export logs** shares the logs shown as CSV or JSON, and **Clear logs** deletes the logs shown from the phone, after asking.

## Settings backup

Under **About > Backup & restore** you can export your webhooks, headers, secrets, MQTT brokers, schedules and options as a JSON file, and import it on another phone. With secrets included, the file is encrypted with a password. The same format moves between this app and the iOS app. See [settings-backup.md](settings-backup.md).

## How this compares to the Home Assistant companion app

The [Home Assistant companion app](https://companion.home-assistant.io/docs/core/sensors) ships Health Connect sensors of its own, so if you run Home Assistant you may wonder what this app adds. The companion app gives you the latest value of 25 metrics inside Home Assistant; this app gives you every record of 33 types, wherever you want them. Checked against the companion app's documentation on September 14, 2026.

| | HA companion app | Life Dashboard Companion |
|---|---|---|
| Health Connect coverage | 25 sensors | 33 data types |
| Exercise sessions, nutrition, sleep stages, cycle tracking, mindfulness, skin temperature | Missing ([open issue](https://github.com/home-assistant/android/issues/4804)) | Supported |
| Detail | Latest value or daily aggregate per sensor | Every record, with the source app and a stable ID, plus deduplicated daily totals |
| History | "Only the last 30 days of data is used" | Unlimited, with backfill of up to a year |
| Screen time | Last used app; total screen-on time through History Stats | Foreground time per app, custom day boundary |
| Destination | Your Home Assistant | Home Assistant through the Life Dashboard integration or MQTT Discovery, and any webhook backend |
| Direction | Export only | Both: Health Connect to your server, and Home Assistant to Health Connect through the Life Dashboard integration |
| Delivery | Sensor updates | HMAC-signed webhooks, retries, store-and-forward outbox, delivery logs |
| Android | 9+ on the Play build, 14+ otherwise | 9+ for Health Connect, 8.0+ for screen time |

The two work side by side. Keep the companion app for presence, notifications and device sensors, and add this app when you want the full health history, writing into Health Connect, or delivery to something other than Home Assistant.
