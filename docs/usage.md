# Setup guide

This guide takes you from a fresh phone to data arriving in Home Assistant, at your own webhook or at an MQTT broker. It assumes no knowledge of Health Connect.

With the Life Dashboard integration, start in Home Assistant: install the integration first, because it shows the QR code that the app scans. Then install the app, answer its setup questions, grant the permissions and tap **Sync Now**. [Phone to Home Assistant](#phone-to-home-assistant) walks you through it. For [MQTT](#mqtt) or [your own webhook](#your-own-webhook), start with the app. If something doesn't work, see [Troubleshooting](#troubleshooting).

## Contents

1. [What you need](#what-you-need)
2. [Phone to Home Assistant](#phone-to-home-assistant)
3. [Install the app](#install-the-app)
4. [The setup wizard](#the-setup-wizard)
5. [Grant permissions](#grant-permissions)
6. [MQTT](#mqtt)
7. [Your own webhook](#your-own-webhook)
8. [Sync](#sync)
9. [Receiving measurements from Home Assistant](#receiving-measurements-from-home-assistant)
10. [Moving to a new phone](#moving-to-a-new-phone)
11. [Troubleshooting](#troubleshooting)

<a id="requirements"></a>

## What you need

- **An Android phone.** Health data needs Android 9 or newer, because Health Connect doesn't run on older versions. Screen time works on Android 8.0 and newer.
- **Health Connect.** On Android 14 and newer it's part of the system, so there's nothing to install. On Android 9 through 13 it's a separate app from the [Play Store](https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata); the app tells you when it's missing (see [Health Connect](#health-connect)).
- **An app that writes your data into Health Connect**, such as Samsung Health, Garmin Connect, Fitbit or your scale's app. This app reads what those apps put there; it doesn't talk to your watch itself.
- **A place to send the data:** Home Assistant 2026.3 or newer with the Life Dashboard integration, a web server that accepts a JSON POST, or an MQTT broker.

## Phone to Home Assistant

Two routes, and neither needs YAML.

The **Life Dashboard integration** is the recommended one. You install it through [HACS](https://hacs.xyz) from [life-dashboard-ha](https://github.com/owen282000/life-dashboard-ha) and pair the phone by scanning a code. It needs no MQTT broker, and it keeps your history: every day the phone sends goes into Home Assistant's long-term statistics (the data behind the statistics graph cards) on its own date. A year of backfill then shows up as a year of days, not as one big number for today.

**MQTT** is the alternative if you already run an MQTT broker that Home Assistant uses. It sends only the latest value of each type, so there's no history from before the first sync. Its steps are under [MQTT](#mqtt).

Pick one. Running both gives you two devices in Home Assistant with the same numbers.

### With the integration

You need Home Assistant 2026.3 or newer with [HACS](https://hacs.xyz) installed. Plan on 15 to 30 minutes the first time, most of it on the Home Assistant side, and longer if HACS isn't installed yet. The pairing itself takes a minute.

Steps 1 through 4 happen in Home Assistant and end with a QR code on screen. Steps 5 through 7 happen on the phone.

1. **Add the repository to HACS.** Use the [Open in HACS](https://my.home-assistant.io/redirect/hacs_repository/?owner=owen282000&repository=life-dashboard-ha&category=integration) link. Or, in HACS, open the menu, pick **Custom repositories** and add `https://github.com/owen282000/life-dashboard-ha` with the type **Integration**. The integration isn't in the default HACS list yet.
2. **Download** Life Dashboard in HACS.
3. **Restart Home Assistant.** A new integration is only loaded at startup.
4. **Add the integration.** Go to Settings > Devices & services > Add integration, search for **Life Dashboard** and give the phone a name. The address is filled in with the one your browser is using. Check that the phone can reach it: see [Which address to use](#which-address-to-use). The dialog then shows a QR code.
5. **Install the app and scan the code.** Install the app as described under [Install the app](#install-the-app) and open it. At the setup question **Where should your data go?**, tap **Scan a code from Home Assistant**, scan the QR code and tap **Pair**. The app sends a test ping right away and shows whether it arrived. The phone's camera works too: see [Pairing by QR code](#pairing-by-qr-code).
6. **Grant the permissions:** Health Connect on the **Health** tab, usage access on the **Screen Time** tab. See [Grant permissions](#grant-permissions).
7. **Sync.** On the **Health** tab, tap **Sync Now**. The phone appears under Settings > Devices & services > Life Dashboard, with a sensor for each type it sent that fits in a single value (24 of the 33 types do). Do the same on the **Screen Time** tab for screen time. For older history, see [Backfill](#backfill-older-history).

If you opened the app before the integration was ready, pick **I will set this up later** at step 5 and scan the code afterward, as described under [The setup wizard](#the-setup-wizard).

### Which address to use

The phone sends its data to the address in the pairing code, so that address has to work from the phone.

- **`homeassistant.local` often doesn't work on Android.** Android doesn't reliably look up `.local` names. If your browser shows `homeassistant.local:8123`, change the address in the dialog to the IP address of your Home Assistant, such as `http://192.168.1.10:8123`. You can find it under Settings > System > Network in Home Assistant.
- **A home network address** (`http://192.168.x.x:8123`) only works while the phone is on your home Wi-Fi. Syncs that run while you're away fail; the phone keeps the data and sends it with the first sync once you're back home. If you get a notification about failed syncs while you're away, this is why.
- **A public address**, such as your Home Assistant Cloud (Nabu Casa) URL or your own `https://` domain, works everywhere. With a Home Assistant Cloud subscription the dialog also offers **Use Home Assistant Cloud instead**, which needs no port forwarding.

An `http://` address (without the "s") needs plain HTTP allowed in the app. When you scan a code with an `http://` address, the pairing dialog offers to switch that on for you.

To change the address later, open the integration's **Reconfigure**, change it there and scan the new code.

### Pairing by QR code

The integration shows a QR code when you add it, and again under its **Reconfigure**. There are three ways to use it, and all of them end in the same confirmation dialog in the app:

- **From the setup wizard**, with **Scan a code from Home Assistant** under **Where should your data go?**.
- **From the Webhook card** on the **Health** or **Screen Time** tab, with **Scan a pairing code**. Once an address is set up, this becomes a small QR icon inside the address field.
- **With the phone's camera.** Point it at the code. The app opens straight from the camera, because the code is a link that Android checks against the app's signing certificate.

Under **Send from**, pick which sections to fill: **Health**, **Screen Time** or both. The dialog warns you when a section's existing signing secret will be replaced. A section has one secret for all its webhooks, so after pairing, your other webhooks in that section get payloads signed with the new secret. If they check signatures, give them the new secret too, or they'll reject the data. The dialog also offers to allow plain HTTP when the address starts with `http://`. Nothing is saved until you tap **Pair**. A code only fills in the address and the secret. You still choose which data types sync, and how often, on the tabs.

Pairing from the **Webhook** card or with the camera reloads both tabs from the saved settings, so tap **Save Changes** first if you have unsaved changes (see [Changing settings later](#changing-settings-later)).

An address added by pairing gets none of the custom headers you set for that section. Those headers were meant for addresses you entered yourself, and a code can come from anyone, so an API key is never sent to a scanned address. The **Webhook** card says so under the address while the section has headers. To send the headers there anyway, type the address in by hand.

Without the app installed, the code opens a web page that explains where to get it. The secret sits in the part of the link after the `#`, which a browser never sends to any server.

<a id="install"></a>

## Install the app

1. Download the latest `app-release.apk` from [GitHub Releases](https://github.com/owen282000/life-dashboard-companion-app/releases/latest) on the phone.
2. Open the downloaded file. Android asks once to allow installs from the browser or file manager you opened it with; allow it, then tap **Install**.

On the phone the app is called **Life Dashboard**.

To get updates automatically, add the repository to [Obtainium](https://github.com/ImranR98/Obtainium), which installs new versions from GitHub Releases.

F-Droid isn't an option yet; the submission is still in review. A Google Play listing will come later.

Every release is signed with the same key and carries a provenance attestation that ties it to the commit it was built from. [SECURITY.md](../SECURITY.md#verifying-a-release) explains how to check both, if you want to.

To build the app yourself, see [building.md](building.md).

<a id="first-run"></a>

## The setup wizard

The first launch opens a short wizard with three questions:

1. **What do you want to sync?** Health Connect, Screen Time, or both.
2. **Where should your data go?** **Scan a code from Home Assistant** (recommended for Home Assistant), a **Webhook** URL with a test ping, an **MQTT broker**, or several of these.
3. **Which health data matters to you?** Only if you picked Health Connect: **The essentials** (steps, sleep, heart rate, resting heart rate, distance, active and total calories, and weight), **All 33 data types**, or **Decide later**.

The last screen lists what's left to do: the [permissions](#grant-permissions), then **Sync Now**.

If the Life Dashboard integration isn't installed in Home Assistant yet, there's no code to scan. Pick **I will set this up later** at the second question and finish the wizard. Once Home Assistant shows the code ([steps 1 through 4](#with-the-integration)), scan it with **Scan a pairing code** on the **Webhook** card of the **Health** tab, or with the phone's camera. The result is the same as scanning it in the wizard.

### Changing settings later

Everything the wizard sets can be changed later on the **Health** and **Screen Time** tabs. **Skip setup** on the first screen takes you straight to those tabs.

Most settings on a tab are a draft until you save them. As soon as you change one, a green **Save Changes** button appears near the bottom of the tab, below **Sync Now**. These need it:

- On the **Health** tab: **Data Types**, **Data Resolution**, **Sync Schedule**, **Webhook** (including **Webhook Headers** and the signing secret) and **MQTT**.
- On the **Screen Time** tab: **Sync Schedule**, **Webhook**, **MQTT**, **Day Boundary** and **Apps to send**.

An unsaved change survives a look at the other tab, but it is lost when you close the app or Android ends it in the background. Scheduled syncs and **Backfill** always use the saved settings. One exception: **View** and **Export** on the **Health** tab store the **Data Types** shown on screen right away. On the **Health** tab, **Sync Now** saves your changes first. On the **Screen Time** tab it does not: it syncs with the settings you saved last, so tap **Save Changes** before **Sync Now** there. **Test ping** uses the address and secret on screen, saved or not.

The rest takes effect the moment you change it: everything under **Advanced** and **Notifications**, and **Receive** on the **Health** tab.

## Grant permissions

The app reads nothing until you allow it. There are two permissions, one per tab, and two phone settings that keep syncing reliable.

### Health Connect

Health Connect is Android's shared store for health data. Apps like Samsung Health and Garmin Connect write into it, and this app reads from it.

**Check that Health Connect is there.** On Android 9 through 13, if Health Connect is missing or out of date, the card at the top of the **Health** tab says **Health Connect needs to be updated** and shows an **Update** button that opens the Play Store. On Android 14 and newer you never see this, because Health Connect is part of Android's settings (search settings for "Health Connect"; on Samsung phones it's under **Security and privacy**). On Android 8, which Health Connect doesn't support, and in a work profile, the card says **Health Connect is not installed**; screen time still works there. Once access is granted, **Open App** on that card opens Health Connect.

**Let your watch app write into it.** This is a setting in the other app, not in this one:

- Samsung Health: **Settings > Health Connect**, then allow the data. See [Samsung Health](brands/samsung.md).
- Garmin Connect: **Settings > Connected Apps > Health Connect**. Garmin only does this on Android 14 or newer. See [Garmin](brands/garmin.md).
- Other brands: [Fitbit](brands/fitbit.md), [Gadgetbridge](brands/gadgetbridge.md), or the overview in [brands](brands/README.md).

**Grant access to this app.** Tap **Grant** on the card at the top of the **Health** tab. Health Connect opens its own permission screen, which asks for two things:

- **Read access per data type**, one switch for each type you turned on in the wizard. If you picked **Decide later** in the wizard, it offers all 33 types, and the ones you allow become your selection.
- **Reading in the background.** Only **Sync Now**, with the app open on screen, works without it. Scheduled syncs, the quick settings tile and automations all run while the app is closed, and Health Connect refuses them if you switch this off.

A type you turn on later under **Data Types** asks for its own permission at that moment. A dimmed type has no permission yet.

There's a third permission, history access, which Health Connect needs before an app can read data from more than 30 days before you first gave it access. The app asks for it only from the [backfill](#backfill-older-history) dialog, with **Grant history access**.

### Usage access for screen time

Screen time comes from Android's usage statistics, the same numbers Digital Wellbeing shows. Android protects them with a special permission that apps can't ask for with a pop-up.

1. Open the **Screen Time** tab. The card at the top says **Usage access needed**.
2. Tap **Allow**. Android opens its list of apps with usage access.
3. Find **Life Dashboard** in the list, open it and switch access on.
4. Go back to the app. The card now says **Usage access granted**.

### Let the app run in the background

Android runs scheduled syncs as background jobs. Samsung, Xiaomi, OnePlus, Huawei and other brands stop such jobs to save battery, often without telling you, and then the app only syncs when you open it. On these phones, change the settings below now, not after the first missed sync.

On Samsung phones:

1. **Settings > Apps > Life Dashboard > Battery**, set to **Unrestricted**.
2. **Settings > Battery** (on some versions under **Device care**) **> Background usage limits > Never sleeping apps**, and add **Life Dashboard**.

On Xiaomi, Redmi and POCO phones, two settings matter. Switch on autostart for **Life Dashboard** (in its entry under **Settings > Apps**; on newer versions it's **Background autostart** under the app's permissions). Then set the app's battery saver to **No restrictions**. Locking the app in the recent apps screen (long-press it, then the padlock) also keeps it from being cleared. The menus move between MIUI and HyperOS versions; [dontkillmyapp.com/xiaomi](https://dontkillmyapp.com/xiaomi) keeps the current paths.

On other brands, set the app's battery usage to **Unrestricted** or **No restrictions** under **Settings > Apps > Life Dashboard**, and look for a separate autostart or startup manager. [dontkillmyapp.com](https://dontkillmyapp.com) has the steps for each brand.

Even with these settings, Android doesn't run a background job more often than every 15 minutes, and it can wait longer when the phone sits idle for a while.

### Notifications (optional)

The app can tell you when syncs keep failing, so a problem doesn't go unnoticed for days. Open **Notifications** on the **Health** or **Screen Time** tab (it's one setting for both) and pick how many failures in a row it waits for: 3, 5 or 10.

**Notify after failed syncs** is on by default. On Android 13 and newer, though, Android also needs your permission to show notifications, and the app only asks for it when you flip that switch yourself. If you never saw the prompt, switch **Notify after failed syncs** off and on again and tap **Allow**. You can also allow it in Android's settings under **Apps > Life Dashboard > Notifications**.

The app posts these on its **Sync failures** notification channel:

- **Health Connect sync is failing** (or **Screen Time sync is failing**): that many syncs in a row failed. The text ends with the last error.
- **Not every destination gets your Health Connect data** (or Screen Time data): one webhook missed that many syncs in a row while another webhook accepted them. The app doesn't queue those syncs again for the address that missed them. The notification names that address.
- **Measurements from Home Assistant are not reaching Health Connect**: [Receive](#receiving-measurements-from-home-assistant) failed that many rounds in a row.
- **Health Connect data was lost** (or Screen Time data): the phone keeps undelivered syncs in an outbox for a later try, and some had to be dropped from it before your server took them. Their records are gone. This one comes at once, even with **Notify after failed syncs** off.

The first three go away by themselves after the next success, and come back after every further run of that many failures. The last one stays until you dismiss it.

## MQTT

The MQTT route needs no YAML and no server-side setup beyond a broker that Home Assistant already uses. Install the app and grant its permissions first; in the wizard you can enter the broker under **MQTT broker**, or do it on the tabs as below.

1. In Home Assistant, install the **Mosquitto broker** app (called an add-on in older versions) and add the **MQTT** integration if it isn't there yet. Create a separate Home Assistant user for the phone, so the app doesn't hold your own password.
2. In the app, open the **Health** tab, expand **MQTT**, switch on **Enable MQTT publishing** and fill in the broker host (the IP address of Home Assistant on your network), port 1883, and that username and password. Leave **Base topic** at `lifedashboard` unless the broker already uses that name. Tap **Save Changes**. The **Screen Time** tab uses the same broker by default. Port 1883 is unencrypted, which is fine at home. A broker you reach over the internet needs **TLS** switched on (usually port 8883); the app warns you below the port when TLS is off and the host isn't a home or VPN address.
3. Tap **Sync Now**. Within a few seconds, Settings > Devices & services > MQTT lists a device named **Life Dashboard Companion** with a sensor for every type that has a value: 24 of the 33 Health Connect types plus screen time. Workouts, meals, mindfulness sessions and cycle tracking are events, not single values, so MQTT leaves them out. The integration counts workout and mindfulness minutes per day, but neither route turns single meals, cycle tracking entries or workout details into sensors. A webhook of your own gets every record in full.

The broker keeps the last value of every sensor, so the sensors survive a Home Assistant restart. Every sync sends the full set again. The sensors show up in the statistics graph cards from the first sync on.

**Two phones on one broker?** Give each one a name under **Advanced > Phone name**. A named phone becomes its own device, such as **Life Dashboard Companion (Pixel 8)**. A phone without a name keeps publishing as before, so a household with one phone doesn't need this. When you name or rename a phone, Home Assistant keeps the old device: delete the old **Life Dashboard Companion** under Settings > Devices & services > MQTT once the new one has appeared. If two phones both publish without a name, name both before the next sync, or one of them drops out of Home Assistant until it syncs again.

**Nothing appears?** The **Logs** tab shows every publish with the broker's response. `NOT_AUTHORIZED` means the username or password is wrong. The broker's own log names the app as `lifedashboard-` followed by eight random characters. The app gives up on a broker that doesn't answer within two minutes. When MQTT is the only destination of a tab, a failed publish counts as a failed sync. When the tab also has a webhook, the webhook decides, and the MQTT status line under the broker settings shows the error.

For a throwaway test setup on a laptop, see the Docker setup in [building.md](building.md#local-test-stack).

## Your own webhook

For your own server or dashboard, the app sends a JSON POST to every address you enter, after every sync. [webhook.md](webhook.md) describes every field. [life-dashboard-stack](https://github.com/owen282000/life-dashboard-stack) is an example backend with a receiver, Postgres and Grafana.

1. On the **Health** tab, open **Webhook** and add your URL. The **Screen Time** tab has its own list. A URL you entered in the wizard is already on the tabs you picked there.
2. Optional: add headers under **Webhook Headers**, such as an API key or `Authorization` token.
3. Optional but recommended: set an **HMAC signing secret**. **Generate** makes one and **Copy** puts it on the clipboard. Your server can then check that each request really came from your phone ([webhook.md](webhook.md#verifying-the-signature) explains how).
4. Tap **Test ping**: a small POST that tells you whether your server accepts it. Then tap **Save Changes** to keep the settings.

Your server needs `https://` unless you switch on **Allow plain HTTP webhooks** under **Advanced**. Only do that for a server on your own network or VPN: the data then travels unencrypted.

If your server requires a client certificate (mTLS), install it in Android's settings (Security > Encryption & credentials > Install a certificate), then pick it under **Advanced > Client certificate (mTLS)** with **Choose**. One certificate is used for every webhook, on both tabs. MQTT doesn't use it.

**See exactly what the app sends.** Download [webhook-receiver.py](https://raw.githubusercontent.com/owen282000/life-dashboard-companion-app/main/scripts/webhook-receiver.py) to a laptop on the same Wi-Fi as the phone and run `python3 webhook-receiver.py`. It needs nothing but Python 3, and it prints the laptop's network address. Add `http://<that address>:8765/health` as a webhook URL, switch on **Allow plain HTTP webhooks**, and tap **Test ping**. Every POST is printed with its headers and saved to `received.jsonl`. Add `--secret <your signing secret>` to check the signatures too.

## Sync

**View** on either tab shows what the next sync would send, without sending it. On screen it stops after 12,000 characters; **Export** gives the full payload. Neither includes the `sequence` number, which is assigned only when **Sync Now** (or a scheduled sync) actually sends the payload.

The first Health Connect sync reads the last 7 days. After that, each sync sends only what's new or changed since the one before, so **Sync Now** right after a sync says **No new data**. Records that a watch app writes late, up to a week after the fact, are still picked up. For anything older than those first 7 days, use [Backfill](#backfill-older-history).

The line under **Sync Now** says what happened, such as **Synced 42 records**, **No new data** or **Delivered to 1 of 2 destinations, see Logs.** **Server unreachable - 42 records queued for retry** means the phone keeps that data and sends it with a later sync.

Screen time works differently: every sync sends the last 7 days again, so the next sync corrects a day that was still in progress.

**When syncs run.** Each tab has its own **Sync Schedule**. The default is every 60 minutes. You can pick a shorter or longer interval (at least 15 minutes), or fixed times of day. Both can be limited to certain weekdays and can have quiet hours. Scheduled syncs depend on the [background settings](#let-the-app-run-in-the-background) above.

To sync without opening the app, add the **Sync Life Dashboard** tile to Quick Settings, or start a sync from Tasker or MacroDroid ([features.md](features.md#widget-tile-and-automation-apps) has the intent). Together, the tile and the Tasker or MacroDroid broadcast start at most one sync a minute. The home screen widget shows the last sync and the records sent today.

### Backfill older history

**Backfill** on the **Health** tab sends past data for all enabled types, in chunks of 3 days, oldest first. Pick **30d**, **90d** or **365d**.

Health Connect needs history access before it hands out anything from more than 30 days before you first gave this app access. On a recent install, a 90- or 365-day backfill without it comes back short. The backfill dialog shows **Grant history access** when it's missing; tap it before you pick 90d or 365d.

A backfill goes to webhooks, and the Home Assistant integration counts as one. MQTT only carries the latest value, so it can't fill in the past. The backfill runs in the background, so you can leave the app, and if Android interrupts it, it picks up where it left off. A year can take a while and can use mobile data. [features.md](features.md#backfill) has the details.

## Receiving measurements from Home Assistant

This works the other way around: readings from a scale or blood pressure monitor that talks to Home Assistant, and not to the phone, end up in Health Connect, and from there in apps like Samsung Health. It needs the Life Dashboard integration, version 0.7.0 or later.

1. In Home Assistant, open the integration's options for this phone and pick the entities: one per type, and for blood pressure the systolic and diastolic pair.
2. In the app, on the **Health** tab, open **Receive** and switch it on. It uses the paired address as its source, or asks which one when there are several.
3. Switch on the types you want. Each asks Health Connect for permission to write that one type. If you refuse, the switch stays off.
4. Tap **Sync Now**. The line under it says how much was written, the **Logs** tab shows every reading, and the **Receive** row counts what was written today.

Measurements keep the time they were taken, so a weigh-in at 07:00 that syncs at 09:00 shows as 07:00 in Health Connect. Anything older than 30 days is refused unless **Accept older measurements** is on. The integration's **Send history to phone** button sends up to 30 days, as far back as Home Assistant's recorder keeps states (10 days by default); its `life_dashboard.queue_history` action goes back up to 90 days, and that's where the switch matters.

[Your scale into Samsung Health or Google Health](recipes/scale-to-health-connect.md) says which entity to pick for a Xiaomi, Renpho, Eufy or Withings scale, and which ones need a template.

## Moving to a new phone

Tap the info icon at the top right of the app to open **About**, then **Backup & restore**. Export your settings on the old phone and import them on the new one, instead of typing everything again. You choose whether secrets go along, protected by a password. See [settings-backup.md](settings-backup.md).

## Troubleshooting

The **Logs** tab is the first place to look: it shows every sync and delivery attempt, with the server's response. Tap a row for its details, and use **All**, **Health** and **Screen Time** to filter the list. [features.md](features.md#preview-export-and-logs) describes the rest of the tab.

[No new data](#sync-now-says-no-new-data) - [Where are my sensors](#where-are-my-sensors-and-what-are-they-called) - [Battery use](#how-much-battery-does-it-use) - [Steps counted twice](#samsung-health-and-garmin-both-write-steps-will-they-double) - [Background syncs stop](#background-syncs-stop-after-a-while) - [Code opens a web page](#scanning-the-code-opens-a-web-page-instead-of-the-app) - [Code from a newer version](#the-app-says-a-pairing-code-is-from-a-newer-version) - [Plain HTTP is blocked](#plain-http-is-blocked) - [Client certificate](#client-certificate-is-unavailable) - [Nightly metrics late](#nightly-metrics-hrv-respiratory-rate-sleep-arrive-hours-after-waking) - [Missing field](#a-nutrient-or-other-field-is-missing) - [Not in Samsung Health](#a-measurement-from-home-assistant-is-not-in-samsung-health) - [Weight twice](#weight-appears-twice) - [Weigh-in on the wrong day](#yesterdays-weigh-in-shows-up-today) - [Update the integration](#update-the-life-dashboard-integration-to-receive-measurements) - [Screen time vs Digital Wellbeing](#screen-time-does-not-match-digital-wellbeing)

### Sync Now says No new data

Or Home Assistant shows nothing after a sync. Check these in order:

1. **Is there data in Health Connect?** Tap **Open App** on the **Health** tab and look at the data there. If it's empty, your watch app doesn't write into Health Connect yet: see [Health Connect](#health-connect).
2. **Is the type switched on and granted?** Open **Data Types** on the **Health** tab. A dimmed type has no permission; switch it off and on to ask again.
3. **Was it already sent?** Each sync only sends what's new since the last one, and the first sync only reads the last 7 days. **View** shows what the next sync would send. For older data, run a [backfill](#backfill-older-history).
4. **Has the watch synced with the phone?** Garmin Connect writes into Health Connect after the watch syncs with the phone. Samsung Health writes Galaxy Watch data once the watch connects, or when you open Samsung Health.

### Where are my sensors, and what are they called?

With the integration: Settings > Devices & services > Life Dashboard, then your phone's device. A sensor only appears once the phone has sent that type. The entity IDs start with the device name, so a phone named "Owen's Pixel" gives `sensor.owen_s_pixel_steps_today`, `sensor.owen_s_pixel_heart_rate`, `sensor.owen_s_pixel_weight`, `sensor.owen_s_pixel_screen_time_today` and so on. The history isn't in the sensors but in the statistics: add a **Statistics graph** card and pick the phone's statistics from the list. The [integration's README](https://github.com/owen282000/life-dashboard-ha#what-you-get) lists every sensor.

With MQTT: Settings > Devices & services > MQTT, device **Life Dashboard Companion**, with sensors named like **Steps Today**, **Heart Rate**, **Weight** and **Screen Time Today**. [features.md](features.md#home-assistant-and-mqtt) lists them all.

### How much battery does it use?

Between syncs the app does nothing: no service keeps running and no connection stays open. Each sync is a short job that Android starts on your schedule, by default once an hour, and MQTT connects for that job and disconnects again. A shorter interval means more of these jobs. A backfill reads and sends much more at once, so it uses more while it runs.

### Samsung Health and Garmin both write steps: will they double?

Not in Home Assistant. When two apps record the same walk, Health Connect holds both copies, but it also calculates a daily total that counts the overlap once. The app sends that daily total, and the integration and MQTT use it for steps, distance and calories, in both the sensors and the history. Keep **Daily totals in payload** switched on under **Advanced** for this.

If you write your own receiver and add up the raw records, you'll count steps two or three times. Use the [`daily_totals`](webhook.md#daily-totals) in the payload instead. [DATA_SOURCES.md](DATA_SOURCES.md#several-sources-for-the-same-activity) has more on overlapping sources, and on [an app that writes the same record twice](DATA_SOURCES.md#urevo-comurevoapp).

### Background syncs stop after a while

If the app only syncs when you open it, the phone is stopping its background jobs to save battery. Go through [Let the app run in the background](#let-the-app-run-in-the-background), including the separate battery or startup manager that some brands have. Then check that you didn't switch off background reading in Health Connect: without it, only **Sync Now** works (see [Health Connect](#health-connect)).

The **Logs** tab shows when syncs actually ran, which tells you whether they're being held back.

### Scanning the code opens a web page instead of the app

Android checks the pairing link against the app's signing certificate when the app is installed, and remembers the result. The APK from GitHub Releases passes that check. An APK you built and signed yourself doesn't, and a phone that had no internet connection during the install may have failed the check.

The web page has an **Open in the app** button, which always works. Scanning from inside the app, with **Scan a pairing code** on the **Webhook** card, doesn't depend on this check at all.

To fix the camera route itself, reinstall the released APK, or ask Android to check again from a computer with `adb`:

```sh
adb shell pm verify-app-links --re-verify com.owen282000.lifedashboard
adb shell pm get-app-links com.owen282000.lifedashboard
```

The second command should say `owen282000.github.io: verified`.

### The app says a pairing code is from a newer version

The code was made by a newer integration than this version of the app can read. Update the app and scan again. The integration and the app change their code format together.

### "Plain HTTP is blocked"

The address starts with `http://`, and the app only sends to `https://` addresses unless you allow otherwise. For a server you only reach on your home network or over a VPN, switch on **Allow plain HTTP webhooks** under **Advanced** on the **Health** or **Screen Time** tab (it applies to both). The data, headers and signature then travel unencrypted on that network.

### Client certificate is unavailable

The certificate chosen under **Advanced > Client certificate (mTLS)** was removed from Android's certificate store, or the app lost access to it. Nothing is sent to your webhooks until you fix this. Install the certificate again if needed (Android settings > Security > Encryption & credentials > Install a certificate) and pick it again with **Choose**, or tap **Clear** when the server no longer needs one.

The same happens after moving to a new phone with Android's own backup or transfer: the settings come along, but the certificate itself never leaves the old phone.

### Nightly metrics (HRV, respiratory rate, sleep) arrive hours after waking

Watch apps such as Fitbit write the night's results into Health Connect only when they sync in the morning, sometimes an hour or more after you wake up. Until then the data isn't in Health Connect, so no app can read it. The app sends it with the first sync after the watch app writes it, and nothing is lost. Developers can confirm this with the [`_diagnostics`](webhook.md#diagnostics) block of the payload.

### A nutrient or other field is missing

The app sends every field Health Connect has, but only when the app that wrote the record filled it in. Cronometer, for example, doesn't write thiamin, folic acid, chloride or energy from fat. Salt isn't a Health Connect field at all. [DATA_SOURCES.md](DATA_SOURCES.md) lists what's known per source app.

### A measurement from Home Assistant is not in Samsung Health

Check the **Logs** tab first. A row **Health · from Home Assistant** with **Written** means the reading is in Health Connect, where it's listed under its type with Life Dashboard as the source. From there it's up to the other app. Samsung Health reads weight and body fat from Health Connect; whether it shows body composition or blood pressure written by another app varies by version, and this app can't promise it. Google Health reads weight and body fat but not blood pressure. Garmin Connect reads no weight at all. [features.md](features.md#receiving-from-home-assistant) has the table.

A row with **Failed** gives the reason per reading:

| Reason | What it means |
|---|---|
| `permission_denied` | The type's write permission is missing. Switch the type off and on under **Receive** to ask again. |
| `too_old` | The reading is older than 30 days and **Accept older measurements** is off. |
| `out_of_range` | The value is outside what the app accepts for that type. |
| `invalid` | The reading is incomplete, not a number, or dated in the future. Check the entity in Home Assistant. |
| `unsupported_type` | Home Assistant sent a type this app version doesn't know. Update the app. |
| `hc_unavailable`, `rate_limited` | Health Connect didn't take it this time. The integration offers it again. |

Only a reading refused for the reasons in the last row is offered again by itself. For every other reason, the integration drops the reading once the phone has refused it and doesn't offer it again, not even with **Send history to phone**. Fix the cause, and readings that come in after that go through.

### Weight appears twice

The scale's own app (Zepp, Mi Fitness, Withings) writes to Health Connect, and now Home Assistant does too. The app warns about this when you switch a type on. Pick one source: switch the type off under **Receive**, or stop the other app from writing to Health Connect in its own settings.

### Yesterday's weigh-in shows up today

A reading keeps the time it was measured, and Health Connect shows it at that time. Only the sync comes later, and the **Logs** tab shows both the measurement time and the sync that wrote it. If a reading really does land at the wrong time, the entity in Home Assistant probably has no timestamp of its own, so the integration used the moment its state last changed. Pick a timestamp entity in the integration's options to fix that.

### "Update the Life Dashboard integration to receive measurements"

The **Receive** row says this when the paired Home Assistant responds like an integration older than 0.7.0, or when the address isn't the integration's. Update the integration through HACS; nothing on the phone needs to change. Sending data to Home Assistant keeps working in the meantime.

### Screen time does not match Digital Wellbeing

The numbers come from the same source, but they can differ for a few reasons:

- With a day boundary other than midnight (such as 04:00, set on the **Screen Time** tab), the app's days aren't Digital Wellbeing's days.
- Apps used for one minute or less in a day are left out.
- Only time with the app on screen counts. Music playing in the background doesn't.

[DATA_SOURCES.md](DATA_SOURCES.md#screen-time-usagestatsmanager) explains how the minutes are counted.
