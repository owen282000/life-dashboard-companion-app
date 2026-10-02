# Installation and setup

## Requirements

- Android 8.0+ (minSdk 26); some Health Connect features need a recent Android version
- The [Health Connect](https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata) app installed
- Usage access permission, for the Screen Time feature
- Home Assistant with the Life Dashboard integration (HACS), a webhook endpoint, or an MQTT broker

## Install

### From releases (recommended)

1. Download the latest APK from [Releases](https://github.com/owen282000/life-dashboard-companion-app/releases/latest)
2. Install it on your Android device (enable "Install from unknown sources" if needed)

To keep it updated automatically from GitHub releases, add the repository to [Obtainium](https://github.com/ImranR98/Obtainium).

Every release is signed with the same key and carries a provenance attestation tying it to the commit it was built from. [SECURITY.md](../SECURITY.md#verifying-a-release) explains how to check both before you install, if you want to.

### Build from source

```bash
git clone https://github.com/owen282000/life-dashboard-companion-app.git
cd life-dashboard-companion-app

./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # or install directly on a connected device
```

More on the project layout and the build in [building.md](building.md).

## First run

The first launch opens a short setup wizard. It asks three questions and nothing else: what to sync (Health Connect, Screen Time, or both), where the data should go (scan the Home Assistant integration's pairing code, which is recommended, a webhook URL with a test ping, an MQTT broker, or a combination) and, when Health Connect is in, which data types to start with (the essentials, all 33, or none yet). The destination only applies to the sources you picked. Everything it sets can be changed later on the Health and Screen Time tabs, and **Skip setup** takes you straight to those tabs.

After the wizard:

1. **Grant Health Connect permissions** - tap "Grant" on the Health tab
2. **Grant Usage Access** for Screen Time - go to Settings when prompted
3. **Add webhook headers** (optional) - auth tokens or API keys
4. **Set the sync schedule** - an interval (minimum 15 minutes) or fixed times of day, per tab, with optional weekdays and quiet hours
5. **Tap "View"** to inspect the payload, then **"Sync Now"** to send

**Send Test Ping** in the wizard, and **Test ping** on both tabs, confirm your server accepts a POST before waiting for real data.

To see exactly what the app sends before you build a receiver, run `python3 scripts/webhook-receiver.py` on a laptop on the same Wi-Fi (it prints its LAN address), add `http://<that address>:8765/health` as a webhook URL, switch on **Advanced > Allow plain HTTP**, and tap Test ping. Every POST is printed with its headers and appended to `received.jsonl`; pass `--secret <your HMAC secret>` to verify signatures.

Moving from another device? Import your settings under **About > Backup & restore** instead of typing everything again; see [settings-backup.md](settings-backup.md).

## Phone to Home Assistant

Two routes, and neither needs YAML. The **Life Dashboard integration** (installed through
HACS from [life-dashboard-ha](https://github.com/owen282000/life-dashboard-ha)) is the
recommended one: it needs no broker at all, is paired by scanning a code, and writes every
synced day into long-term statistics on its own date, so a backfill becomes history rather
than one big number on today. **MQTT** is the alternative for a setup that already has a
broker Home Assistant talks to, and publishes retained latest values that survive a
restart. Pick one: running both gives you two devices holding the same numbers.

### With the integration

You need Home Assistant 2026.3 or newer with [HACS](https://hacs.xyz) installed, and the
app on the phone. Count on 15 to 30 minutes the first time, and longer if HACS is not
installed yet. The pairing itself is the quick part: once Home Assistant shows the code,
the phone is set up in a minute.

1. **Add the repository to HACS.** Use the
   [Open in HACS](https://my.home-assistant.io/redirect/hacs_repository/?owner=owen282000&repository=life-dashboard-ha&category=integration)
   link, or in HACS open the menu, pick **Custom repositories** and add
   `https://github.com/owen282000/life-dashboard-ha` with the type **Integration**. The
   integration is not in the default HACS list yet.
2. **Download** Life Dashboard in HACS.
3. **Restart Home Assistant.** A new integration is only loaded at startup.
4. **Add the integration.** Settings > Devices & services > Add integration, search for
   **Life Dashboard** and give the phone a name. The address is filled in with the one your
   browser is using; the phone has to be able to reach it.
5. **Scan the QR code** the dialog shows, with the phone's camera or with **Scan a pairing
   code** in the app, and tap **Pair**. [Pairing by QR code](#pairing-by-qr-code) below
   has the details.
6. **Grant and sync.** On the Health tab tap **Grant**, switch on the types you want, and
   tap **Sync Now**. The phone appears under Settings > Devices & services > Life Dashboard
   with a sensor for each type it sent. For screen time, allow usage access on the Screen
   Time tab and sync there too; for the past, tap **Backfill** on the Health tab.

### Pairing by QR code

The integration shows a QR code when you add it, and again under its Reconfigure. Three
ways to use it, all ending in the same confirmation dialog:

- **Point your phone's camera at it.** The app opens straight from the camera, because the
  code is an Android App Link verified against the app's signing certificate.
- **Tap Scan a pairing code** on the Webhook card of the Health or Screen Time tab. Once a
  receiver is set up the button becomes a small QR icon inside the address field.
- **From the setup wizard**, as the first option under "Where should your data go?".

The app then asks which sections to fill, says so when a section's existing signing secret
will be replaced, and offers to allow plain HTTP when the address is an internal
`http://` one. Nothing is written until you tap **Pair**. What is synced, and on what
schedule, stays a choice on the tabs: a scanned code only ever fills in the address and
the secret.

An address added by pairing gets none of the section's custom headers. Those were typed for
the receivers you entered yourself, and a code can come from anyone, so an API key never
follows a scanned code to its host. The address says so on the Webhook card while the
section has headers. To send them there anyway, type the address in by hand: it stays in
the list once and from then on gets the headers.

Without the app installed, the code opens a page that explains where to get it. The secret
travels in the part of the link after the `#`, which a browser never sends to any server.

### Receiving measurements from Home Assistant

The way back, for a scale or a blood pressure monitor that talks to Home Assistant and not to
the phone (integration 0.7.0 or later):

1. In Home Assistant, open the integration's options for this phone and pick the entities:
   one per type, and for blood pressure the systolic and diastolic pair.
2. In the app, on the Health tab, open **Receive** and switch it on. It takes the paired
   webhook as its source, or asks which when there are several.
3. Switch on the types you want. Each asks Health Connect for that one write permission; a
   refused permission leaves the switch off.
4. Tap **Sync Now**. The line under it says how much was written, the Logs tab shows every
   reading, and the Receive row counts what was written today.

Measurements keep the time they were taken, so a weigh-in at 07:00 that syncs at 09:00 is
07:00 in Health Connect. Anything older than 30 days is refused unless **Accept older
measurements** is on. The integration's **Send history to phone** button sends up to 30 days;
its `life_dashboard.queue_history` service goes back up to 90, and that is where the switch matters.

Which entity to map for a Xiaomi, Renpho, Eufy or Withings scale, and the templates some of them
need: [Your scale into Samsung Health or Google Health](recipes/scale-to-health-connect.md).

### MQTT

The MQTT route needs no YAML and no server-side setup beyond a broker Home Assistant already talks to.

1. In Home Assistant, install the **Mosquitto broker** add-on (Settings > Add-ons) and add the **MQTT** integration if it is not there yet. Create a user for the app under Settings > People, or in the add-on's login list; a dedicated account keeps the app's credentials out of your own.
2. In the app, open the Health tab, expand **MQTT**, switch on **Enable MQTT publishing** and fill in the broker host (the Home Assistant IP on your LAN, or its hostname), port 1883 and that username and password. Screen Time shares the broker by default. Port 1883 is plain MQTT, fine on your own network; a broker reached over the internet needs **TLS** on (usually port 8883), and the card and the setup wizard say so below the port while TLS is off and the host is not a LAN or VPN address.
3. Tap **Sync Now**. Within a few seconds Settings > Devices & services > MQTT lists a device named **Life Dashboard Companion** with a sensor for every synced type that has a value: 24 of the 33 Health Connect types (today's steps, distance and calories, the latest heart rate, weight, sleep duration, blood pressure and the other measurements) plus screen time. Workouts, meals, mindfulness sessions and cycle tracking are events and stay webhook-only.

Two phones on the same broker? Give each one a name under **Advanced > Phone name**. A named phone becomes its own device, **Life Dashboard Companion (Pixel 8)**, with its own topics under the base topic; a phone without a name keeps publishing exactly as before, so nothing changes for a household with one phone. When you name or rename a phone, its next publish clears the retained topics of the old device, but Home Assistant keeps the device itself: delete the old **Life Dashboard Companion** under Settings > Devices & services > MQTT once the new one has appeared. With two phones that both still publish nameless, name both before the first one syncs, or expect the other's device to drop out of Home Assistant until its next publish brings it back.

Values are published retained, so they survive a Home Assistant restart, and every sync republishes the full set the app has mapped so far. The sensors carry `state_class`, so they show up in the Statistics graphs and in the energy-style history cards. If nothing appears, the Logs tab shows every publish with the broker's answer; `NOT_AUTHORIZED` means the username or password is wrong, and the broker's own log names the client as `lifedashboard-` followed by eight random characters.

For a throwaway setup on a laptop, `scripts/dev/docker-compose.yml` starts a Mosquitto broker without authentication and a Home Assistant on port 8123; an emulator reaches the laptop as `10.0.2.2`, a phone through the laptop's LAN address.

## Troubleshooting

### Background syncs stop after a while

Many manufacturers (Samsung, Xiaomi, OnePlus, Huawei, and others) aggressively kill background work to save battery, which silently stops the WorkManager syncs this app relies on. If syncs only happen when you open the app:

1. Go to **Settings > Apps > Life Dashboard > Battery** and set it to **Unrestricted** (naming varies per manufacturer).
2. On heavily customized Android skins, also exempt the app from the manufacturer's own battery or startup manager. [dontkillmyapp.com](https://dontkillmyapp.com) has per-brand instructions.
3. Keep in mind Android enforces a minimum interval of 15 minutes for periodic background work, and may delay syncs further in Doze mode.

The Logs tab shows when the last sync attempts actually ran, which helps confirm whether syncs are being suppressed.

### Scanning the code opens a web page instead of the app

Android verifies the link against the app's signing certificate the first time the app is
installed, and caches the answer. An APK from GitHub Releases or F-Droid carries the right
signature, but a build you signed yourself does not, and a device that had no network
during install may have failed the check.

The page the browser opens has an **Open in the app** button, which works regardless.
To fix the camera route itself, reinstall the released APK, or approve the domain by hand:

```sh
adb shell pm verify-app-links --re-verify com.owen282000.lifedashboard
adb shell pm get-app-links com.owen282000.lifedashboard
```

The second command should say `owen282000.github.io: verified`. Scanning from inside the
app (the **Scan** button on the Webhook card) never depends on this.

### The app says a pairing code is from a newer version

The code carries a format version, and this build only reads version 1. Update the app and
scan again; the integration and the app ship their formats in step.

### "CLEARTEXT communication not permitted" or "Plain HTTP is blocked"

Webhook URLs must use HTTPS unless you opt in. For a receiver that is only reachable over your LAN or a VPN and has no certificate, switch on **Allow plain HTTP webhooks** (in the Health Connect or Screen Time settings; it applies to both). Keep in mind that the payload, headers and signature then travel unencrypted on that network.

### Client certificate '…' is unavailable

The certificate chosen under **Advanced > Client certificate (mTLS)** was removed from Android's credential store, or the app's access to it was revoked. No webhook is sent until this is fixed: install the certificate again if needed (Settings > Security > Encryption & credentials > Install a certificate) and pick it again with **Choose**, or **Clear** it when the server no longer requires one.

The same message appears after moving to a new phone through Android's own backup or device transfer: the app's settings come along, the certificate itself never leaves the old phone, so the choice names a certificate the new phone does not have.

### Step, distance or calorie totals are far too high

Health Connect usually holds the same activity from more than one app: the phone's step counter, the watch app, Samsung Health, or a mirroring app. Each copy is a record with its own `source`, and summing the raw records counts the activity two or three times. Use the [`daily_totals`](webhook.md#daily-totals) array for day totals (it is deduplicated by Health Connect itself) and deduplicate raw records on `uuid`, since a batch is re-sent after a failed delivery.

A single source can also write two records for the same interval, with different `uuid`s and the same value. That has been observed with UREVO (`com.urevo.app`). Deduplicating on `uuid` is still the right way to ignore a re-sent payload; for session totals of distance, steps or calories, drop extra records that match on source, type, interval and value. Do not apply that to logged entries such as water or meals: two identical glasses logged in the same minute are two glasses. Do not fill a missing session metric from `daily_totals`: those are the whole day's figures, including other activity. [DATA_SOURCES.md](DATA_SOURCES.md#urevo-comurevoapp) has a synthetic example.

### Nightly metrics (HRV, respiratory rate, sleep) arrive hours after waking

Watch apps such as Fitbit write the night's results to Health Connect only when they sync in the morning, sometimes an hour or more after you wake up. Until then the records do not exist in Health Connect, and [`_diagnostics`](webhook.md#diagnostics) shows `raw_record_count` unchanged and `raw_latest_modified_time` older than `last_sync`. They are delivered on the first sync after the source writes them; no data is lost.

### A nutrient or other field is missing from the export

The app exports every field Health Connect's record types expose, but only when the source app wrote it. Cronometer, for example, does not write thiamin, folic acid, chloride or energy from fat, and Health Sync drops vitamins and minerals from mirrored meals. Salt is not a Health Connect field at all. [DATA_SOURCES.md](DATA_SOURCES.md) lists what is known per source app.

### A measurement from Home Assistant is not in Samsung Health

Check the Logs tab first: a row "Health · from Home Assistant" with **Written** means the
reading is in Health Connect, and the Health Connect app lists it under the type with Life
Dashboard as its source. From there on it is up to the other app. Samsung Health reads
weight and body fat from Health Connect; whether it shows body composition or blood pressure
written by a third app varies by version and is not something this app can promise. Google
Health reads weight and body fat but not blood pressure; Garmin Connect reads no weight at
all. [features.md](features.md#receiving-from-home-assistant) has the dated table.

A row with **Failed** names the reason per reading: `permission_denied` means the type's
write permission is missing (switch the type off and on to ask again), `too_old` means the
reading is older than 30 days and **Accept older measurements** is off, `out_of_range`
means the value is outside what the app accepts, and `hc_unavailable` or `rate_limited` mean
Health Connect did not take it this time and the integration will offer it again.

### Weight appears twice

The scale's own app (Zepp, Mi Fitness, Withings) writes to Health Connect itself, and now
Home Assistant does too. The app warns about this when a type is switched on. Pick one
source: switch the type off under Receive, or stop the other app from writing to Health
Connect in its own settings.

### Yesterday's weigh-in shows up today

It does not: a reading carries the time it was measured, and Health Connect shows it there.
What moves is the sync, which brings it over later, and the Logs tab shows both the
measurement time and the round that wrote it. If a reading really lands at the wrong time,
the entity in Home Assistant has no timestamp of its own and the integration used the
moment its state last changed; a timestamp entity in the integration's options fixes that.

### "Update the Life Dashboard integration to receive measurements"

The Receive row says this when the paired Home Assistant answers without the protocol block,
which is what the integration does before 0.7.0. Update it through HACS; nothing on the
phone needs to change. Sending to Home Assistant works as before in the meantime.

### Screen time is much higher than Digital Wellbeing

Update to 1.10.2 or later. Earlier versions counted a session whose pause event was never recorded until the end of the day, which produced per-app values of 10 to 15 hours. After the update the next sync re-sends the last 7 days with corrected values.
