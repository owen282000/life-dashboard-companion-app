# Privacy policy

Life Dashboard Companion is a self-hosting tool. It reads health and screen time data on your Android phone and sends it to servers that you set up yourself: your Home Assistant, your MQTT broker or your own webhook. If you switch it on, it can also take measurements from your own Home Assistant and write them into Health Connect. The developer has no server for this app and never receives, stores or sees any of your data.

Last updated October 4, 2026, for version 1.23.0.

## In short

- The app reads only the Health Connect data types you switch on, and screen time only if you grant usage access.
- It sends that data only to the destinations you enter. It contacts no other server.
- There are no analytics, no crash reporting services, no advertising SDKs and no AI services in the app.
- It writes to Health Connect only when you switch on **Receive**, and only the measurement types you allow, one permission at a time.
- Passwords, auth headers and signing secrets are encrypted on the phone and left out of Android's backup.
- Every feature is opt-in, and switching it off or revoking the permission stops it.

The rest of this page gives the details, and every statement can be checked against the [source code](#open-source).

The app shows a summary of this policy under **About > Privacy policy**. Health Connect opens the same screen from the privacy policy link on its permission screen.

## Contents

- [What the app reads](#what-the-app-reads)
- [What the app writes](#what-the-app-writes)
- [Where it goes](#where-it-goes)
- [What a payload contains](#what-a-payload-contains)
- [What stays on the device](#what-stays-on-the-device)
- [Permissions](#permissions)
- [Other apps on your phone](#other-apps-on-your-phone)
- [App stores](#app-stores)
- [Your control](#your-control)
- [Open source](#open-source)
- [Contact](#contact)

## What the app reads

- **Health Connect records** for the data types you enable, through Android's Health Connect API. Nothing is read until you grant the permissions, and only the enabled types are read.
- **App usage statistics** (which apps were in the foreground and for how long), through Android's usage access permission, for the Screen Time feature.
- **Older data, if you ask for it.** With the Health Connect history permission the app can read records from more than 30 days before you first gave it access, for a backfill you start yourself.

## What the app writes

Nothing, unless you switch on **Receive** on the **Health** tab. That feature takes measurements from your own Home Assistant, through the Life Dashboard integration, and writes them into Health Connect: weight, height, body fat, lean body mass, bone mass, body water mass and blood pressure. Each type has its own switch and its own Health Connect write permission.

Only the entities you chose in Home Assistant are sent. The app writes a reading only if the response that carried it is signed with the same secret the app uses for its own requests. Records the app writes carry the app as their source in Health Connect, and the app never sends them back out. Health Connect lets an app change only its own records, so the app cannot change or delete records that other apps wrote.

## Where it goes

Data leaves your phone only to the destinations you enter yourself:

- Your own webhook URLs, including the Life Dashboard integration in Home Assistant. HTTPS is the default; plain HTTP works only after you switch on **Allow plain HTTP webhooks**.
- Your own MQTT broker.

The app does not contact any other server. Android itself makes one request on the app's behalf: when the app is installed or updated, Android fetches a small file from `owen282000.github.io` to check that the pairing links belong to this app. That request carries none of your data.

MQTT travels in plain text unless you switch on **TLS** for the broker. On the default port 1883, anyone on the network between the phone and the broker can read the values, and the broker's username and password. States are published retained, so the broker keeps the latest value of every sensor until the next publish replaces it. For Screen Time that includes the most used apps. Any client allowed to subscribe to those topics can read them. Switching MQTT off in the app does not remove them, so clear the topics on the broker if you stop using it.

## What a payload contains

A payload is one message the app sends to your destination. The exact format is documented field by field in [docs/webhook.md](docs/webhook.md).

**Health Connect payloads** contain:

- The records of the data types you enabled, with their values and times.
- For every record, a `uuid` (the record's ID in Health Connect, so your server can drop duplicates) and a `source`: the package name of the app that wrote it, for example `com.fitbit.FitbitMobile`. This tells your server which apps you use. A series you chose to send as averages or totals per time window carries the list of source apps per window (`sources`) instead.
- Technical fields: the sync time, the app version, a counter (`sequence`), daily totals, the IDs of records that were deleted from Health Connect, and `_diagnostics`, which counts what Health Connect returned per type without any values.
- With **Receive** on: which measurement types you accept, and the IDs of the readings that were written or failed. Never their values.

**Record metadata in payload** is off by default. When you switch it on, every record also carries what Health Connect knows about it, when available: when it was last changed, the writing app's own ID and version for it, how it was recorded (active, automatic or manual), the device's manufacturer, model and type (for example a watch or a scale), and the time zone offset.

**Screen Time payloads** contain, for each of the last 7 days, the total screen time and, for each app, its name, package name, foreground minutes and when it was last used. Apps used for one minute or less that day are left out, and System UI and the launcher are always left out. The payload also carries the app version and the phone's manufacturer and model in a `device` field, for example `Google Pixel 8`.

Under **Apps to send** on the **Screen Time** tab you can leave apps out (**All except**) or send only some (**Only**). An app that is filtered out never leaves the phone: it is not in the payload, not on MQTT and not in the Home Assistant sensors, and its name is not sent in any form. The day's total still counts the apps you left out, so your total screen time is still sent. The payload says that a filter is on, but not which apps are on the list.

## What stays on the device

- Your settings: destinations, headers, sync intervals and enabled types. Secrets (auth headers, HMAC signing keys and MQTT passwords) are encrypted with a key in the Android Keystore that never leaves the phone.
- Sync progress, so each record is sent once.
- With **Receive** on, a small list of the measurement IDs the app has written and their versions, so a measurement Home Assistant sends again is not written twice. It is cleared when the address or the secret changes.
- A delivery log (the **Logs** tab) with up to the last 100 deliveries. Payloads in this log are cut short unless you switch on **Keep full payloads**. For measurements received from Home Assistant, the log keeps the entity ID, the type, the measurement time and the outcome, and the values only with **Keep full payloads**. You can clear it at any time with **Clear logs** on the **Logs** tab.
- An outbox of payloads that could not be delivered yet, so a sync survives a server being down. A payload leaves the outbox once it is delivered, after a week of refusals by the server, or when it is the oldest and 700 Health Connect payloads are waiting. Screen Time keeps only its newest unsent week. Anything dropped without being delivered is noted in the **Logs** tab and in a notification.

Android's own backup (to your Google account, or to a new phone) includes your settings if you have it switched on, but never the secrets, the delivery log, the outbox payloads or the Receive list. After a restore you enter the secrets again.

Uninstalling the app removes everything it stored on the phone.

### Exports

Exports are files you create yourself: health data, screen time, logs, or your settings. The app writes each one to its own cache folder and hands it to the Android share sheet. The copy in the cache stays until your next export replaces it, or until the app starts more than a day later and removes it. The app you share it with keeps its own copy under its own rules.

A settings export with secrets is encrypted with the password you choose. Without secrets it is plain JSON, and that still includes your webhook URLs and MQTT hosts. A webhook URL can be a credential in itself: a Home Assistant automation's `/api/webhook/<id>` address, for instance, accepts anything posted to it. Share such a file only with someone you would give that access to.

## Permissions

| Permission | What it is for |
|---|---|
| Health Connect read permissions | Reading the health data types you enabled |
| Health Connect background read | Syncing on a schedule while the app is closed |
| Health Connect history read | The optional backfill of data from more than 30 days before you first gave the app access |
| Health Connect write permissions | Writing the measurements your Home Assistant sends: weight, height, body fat, lean body mass, bone mass, body water mass, blood pressure. Asked for one type at a time, when you switch that type on under **Receive** |
| Usage access (`PACKAGE_USAGE_STATS`) | Screen time per app |
| Notifications (`POST_NOTIFICATIONS`) | Telling you when syncs keep failing, when one destination keeps missing syncs, when measurements from Home Assistant keep failing, and when undelivered data had to be dropped |
| Camera | Scanning the pairing code that Home Assistant shows. Asked for only when you open the scanner, and the camera runs only while that screen is open. Frames are decoded on the phone and are never stored or sent. Pairing also works without it, by entering the address and secret by hand |
| Internet (`INTERNET`) | Sending data to your webhook or broker |
| Network state (`ACCESS_NETWORK_STATE`) | Checking that the phone is online before a scheduled job runs, so a backfill waits for a connection instead of failing |
| Keep awake (`WAKE_LOCK`) | Keeping the phone from going to sleep in the middle of a running sync |
| Run at startup (`RECEIVE_BOOT_COMPLETED`) | Putting the sync schedule back in place after the phone restarts |
| Foreground service (`FOREGROUND_SERVICE`) | On Android 11 and older only: running a long backfill with a silent notification, so Android does not stop it partway through |

The last four come from Android's WorkManager library, which the app uses to schedule its syncs. AndroidX also declares `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a permission only this app can hold, which keeps its internal broadcasts private. None of them gives access to any data.

The app does not request access to contacts, location, the microphone or your files, and it does not use `QUERY_ALL_PACKAGES`.

## Other apps on your phone

**The sync trigger.** Automation apps such as Tasker or MacroDroid can start a sync by sending the app a broadcast (`com.owen282000.lifedashboard.ACTION_SYNC`). Any installed app can send it, because automation apps cannot hold a permission that belongs to this app. The broadcast only starts a normal sync of Health Connect and Screen Time, the same one the Quick Settings tile starts, to the destinations you configured. The app reads nothing from the broadcast, so another app cannot use it to read your data, change a destination or switch on a data type. A trigger that comes less than a minute after the previous one is ignored.

**Pairing links.** A pairing code from Home Assistant can also open the app as a link. A link never changes anything by itself: the app shows the address it would send to and asks you to confirm first. A pairing sets only the address and the secret. It does not switch on any data type or change the schedule. If a pairing link opens in a browser instead, the page on `owen282000.github.io` finds the address and secret after the `#` in the link, a part that a browser never sends to any server.

## App stores

The app is distributed as a signed APK through GitHub Releases, which you can also install and keep up to date with Obtainium. GitHub handles the download under its own policy; the app adds nothing to that.

When the app is on Google Play (planned as a paid listing) or listed on F-Droid (the submission is in review), the store may keep its own installation statistics under its own policy.

## Your control

Everything is opt-in and can be undone in the app: which types are read, where they go, how often, which apps Screen Time sends, which types Home Assistant may write, and whether full payloads are kept in the log. Revoking a permission in Android stops the matching reads and writes immediately.

## Open source

The source code is public at [github.com/owen282000/life-dashboard-companion-app](https://github.com/owen282000/life-dashboard-companion-app) under the MIT license. Every release APK is built by the repository's public release workflow and carries a provenance attestation that ties it to the exact commit ([how to verify it](SECURITY.md#verifying-a-release)). The build is reproducible: [docs/building.md](docs/building.md#reproduce-a-release-build) explains how to build the same APK yourself and compare it with the release.

## Contact

Ask privacy questions in [Discussions](https://github.com/owen282000/life-dashboard-companion-app/discussions), where the answer stays findable for the next person. For anything personal, email owenvogelaar@hotmail.com instead. A security problem goes through the private channel in [SECURITY.md](SECURITY.md#reporting-a-vulnerability), not Discussions.
