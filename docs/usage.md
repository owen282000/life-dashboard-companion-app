# Installation and setup

## Requirements

- Android 8.0+ (minSdk 26); some Health Connect features need a recent Android version
- The [Health Connect](https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata) app installed
- Usage access permission, for the Screen Time feature
- A webhook endpoint, or an MQTT broker for the Home Assistant route

## Install

### From releases (recommended)

1. Download the latest APK from [Releases](https://github.com/owen282000/life-dashboard-companion-app/releases/latest)
2. Install it on your Android device (enable "Install from unknown sources" if needed)

### Build from source

```bash
git clone https://github.com/owen282000/life-dashboard-companion-app.git
cd life-dashboard-companion-app

./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # or install directly on a connected device
```

More on the project layout and the build in [building.md](building.md).

## First run

1. **Install the app** on your Android device
2. **Grant Health Connect permissions** - tap "Grant" and select the data types you want to sync
3. **Grant Usage Access** for Screen Time - go to Settings when prompted
4. **Configure webhook URLs** - enter your server endpoint(s)
5. **Add webhook headers** (optional) - auth tokens or API keys
6. **Set sync intervals** - minimum 15 minutes
7. **Tap "Preview Data"** to inspect the payload, then **"Sync Now"** to send

Use the **Test ping** button to confirm your server accepts a POST before waiting for real data.

Moving from another device? Import your settings under **About > Backup & restore** instead of typing everything again; see [settings-backup.md](settings-backup.md).

## Troubleshooting

### Background syncs stop after a while

Many manufacturers (Samsung, Xiaomi, OnePlus, Huawei, and others) aggressively kill background work to save battery, which silently stops the WorkManager syncs this app relies on. If syncs only happen when you open the app:

1. Go to **Settings > Apps > Life Dashboard > Battery** and set it to **Unrestricted** (naming varies per manufacturer).
2. On heavily customized Android skins, also exempt the app from the manufacturer's own battery or startup manager. [dontkillmyapp.com](https://dontkillmyapp.com) has per-brand instructions.
3. Keep in mind Android enforces a minimum interval of 15 minutes for periodic background work, and may delay syncs further in Doze mode.

The webhook logs screen shows when the last sync attempts actually ran, which helps confirm whether syncs are being suppressed.

### "CLEARTEXT communication not permitted" or "Plain HTTP is blocked"

Webhook URLs must use HTTPS unless you opt in. For a receiver that is only reachable over your LAN or a VPN and has no certificate, switch on **Allow plain HTTP webhooks** (in the Health Connect or Screen Time settings; it applies to both). Keep in mind that the payload, headers and signature then travel unencrypted on that network.

### Step, distance or calorie totals are far too high

Health Connect usually holds the same activity from more than one app: the phone's step counter, the watch app, Samsung Health, or a mirroring app. Each copy is a record with its own `source`, and summing the raw records counts the activity two or three times. Use the [`daily_totals`](webhook.md#daily-totals) array for day totals (it is deduplicated by Health Connect itself) and deduplicate raw records on `uuid`, since a batch is re-sent after a failed delivery.

### Nightly metrics (HRV, respiratory rate, sleep) arrive hours after waking

Watch apps such as Fitbit write the night's results to Health Connect only when they sync in the morning, sometimes an hour or more after you wake up. Until then the records do not exist in Health Connect, and [`_diagnostics`](webhook.md#diagnostics) shows `raw_record_count` unchanged and `raw_latest_modified_time` older than `last_sync`. They are delivered on the first sync after the source writes them; no data is lost.

### A nutrient or other field is missing from the export

The app exports every field Health Connect's record types expose, but only when the source app wrote it. Cronometer, for example, does not write thiamin, folic acid, chloride or energy from fat, and Health Sync drops vitamins and minerals from mirrored meals. Salt is not a Health Connect field at all. [DATA_SOURCES.md](DATA_SOURCES.md) lists what is known per source app.

### Screen time is much higher than Digital Wellbeing

Update to 1.10.2 or later. Earlier versions counted a session whose pause event was never recorded until the end of the day, which produced per-app values of 10 to 15 hours. After the update the next sync re-sends the last 7 days with corrected values.
