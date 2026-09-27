# Privacy policy

Life Dashboard Companion is a self-hosting tool. It reads health and screen time data on your Android device and sends it to servers that you configure. It can also take measurements that your own Home Assistant sends back and write them into Health Connect, for the types you switch on. The developer never receives, stores or sees any of your data.

Last updated 26 September 2026, for version 1.20.

## What the app reads

- **Health Connect records** for the data types you enable, through Android's Health Connect API. Nothing is read until you grant the permissions, and only the enabled types are read.
- **App usage statistics** (which apps were in the foreground and for how long), through Android's usage access permission, for the Screen Time feature.
- **Optionally, historical data**: with the Health Connect history permission the app can read records older than 30 days for a one-off backfill.

## What the app writes

Nothing, unless you switch on **Receive** on the Health Connect tab. That feature takes measurements from your own Home Assistant, through the Life Dashboard integration, and writes them into Health Connect: weight, height, body fat, lean body mass, bone mass, body water mass and blood pressure, each behind its own switch and its own Health Connect write permission. Only the entities you chose in Home Assistant are sent, and only readings that arrive in a response signed with the same secret the app uses for its own requests are written. Records the app writes carry the app as their source in Health Connect, and the app never sends them back out again. Health Connect scopes what an app may overwrite to its own records, so the app cannot change or delete records that other apps wrote.

## Where it goes

Data leaves your device only to the destinations you enter yourself: your own webhook URLs (HTTPS by default; plain HTTP only after an explicit opt-in) and your own MQTT broker. The app does not contact any other server. There are no analytics, no crash reporting services and no advertising SDKs.

If you install the app from the Google Play Store, the store itself may collect installation and crash statistics under Google's own policies; the app does not add to that.

## What stays on the device

- Your settings (endpoints, headers, sync intervals, enabled types). Secrets such as auth headers, HMAC signing keys and MQTT passwords are stored in encrypted storage backed by the Android keystore, and are excluded from Android's automatic cloud backup.
- Sync watermarks, so each record is sent once.
- With Receive on: a small ledger of the measurement ids the app has written and their versions, so a measurement Home Assistant sends again is not written twice. It is excluded from Android's backup and cleared when the address or the secret changes.
- A delivery log (the Logs tab) with the last 100 deliveries. Payloads in this log are truncated unless you switch on "Keep full payloads". For measurements received from Home Assistant the log keeps the entity id, the type, the measurement time and the outcome; the values only with "Keep full payloads". You can clear the log at any time.
- An outbox of payloads that could not be delivered yet, so a sync survives a server being down. A payload leaves it once delivered, after a week of refusals by the server, or as the oldest when 700 Health Connect payloads are waiting (both noted in the Logs tab); Screen Time keeps only its newest snapshot, which covers the full 7 days.

Uninstalling the app removes all of this. The settings export feature writes an encrypted file that only you can decrypt with the password you chose.

## Permissions

| Permission | Used for |
|---|---|
| Health Connect read permissions | Reading the health data types you enabled |
| Health Connect background read | Syncing on a schedule while the app is closed |
| Health Connect history read | The optional backfill beyond 30 days |
| Health Connect write permissions (per type you enable) | Writing the measurements your Home Assistant sends: weight, height, body fat, lean body mass, bone mass, body water mass, blood pressure. Asked for one type at a time, when you switch that type on under Receive |
| Usage access (`PACKAGE_USAGE_STATS`) | Screen time per app |
| Notifications | Telling you when syncs keep failing (opt-in) |
| Camera | Scanning the pairing code that Home Assistant shows. Asked for only when you open the scanner, and the camera runs only while that screen is open. Frames are decoded on the device and are never stored or sent. Pairing works without it, by entering the address and secret by hand |
| Internet | Sending data to your webhook or broker |

The app does not request access to contacts, location, the microphone or your files, and it does not use `QUERY_ALL_PACKAGES`. The camera is used only by the pairing scanner, as described above.

## Your control

Everything is opt-in and reversible in the app: which types are read, where they go, how often, which types may be written from Home Assistant, and whether anything is kept in the log. Revoking a permission in Android stops the corresponding reads and writes immediately.

## Open source

The source code is public at [github.com/owen282000/life-dashboard-companion-app](https://github.com/owen282000/life-dashboard-companion-app) under the MIT licence, and releases are built reproducibly, so what runs on your device can be checked against this text.

## Contact

Questions about privacy: open an issue on GitHub or email the developer at the address on the GitHub profile.
