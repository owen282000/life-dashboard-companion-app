# Privacy policy

Life Dashboard Companion is a self-hosting tool. It reads health and screen time data on your Android device and sends it to servers that you configure. The developer never receives, stores or sees any of your data.

Last updated 14 September 2026, for version 1.13.

## What the app reads

- **Health Connect records** for the data types you enable, through Android's Health Connect API. Nothing is read until you grant the permissions, and only the enabled types are read.
- **App usage statistics** (which apps were in the foreground and for how long), through Android's usage access permission, for the Screen Time feature.
- **Optionally, historical data**: with the Health Connect history permission the app can read records older than 30 days for a one-off backfill.

## Where it goes

Data leaves your device only to the destinations you enter yourself: your own webhook URLs (HTTPS by default; plain HTTP only after an explicit opt-in) and your own MQTT broker. The app does not contact any other server. There are no analytics, no crash reporting services and no advertising SDKs.

If you install the app from the Google Play Store, the store itself may collect installation and crash statistics under Google's own policies; the app does not add to that.

## What stays on the device

- Your settings (endpoints, headers, sync intervals, enabled types). Secrets such as auth headers, HMAC signing keys and MQTT passwords are stored in encrypted storage backed by the Android keystore, and are excluded from Android's automatic cloud backup.
- Sync watermarks, so each record is sent once.
- A delivery log (the Logs tab) with the last 100 deliveries. Payloads in this log are truncated unless you switch on "Keep full payloads". You can clear the log at any time.
- An outbox of payloads that could not be delivered yet, so a sync survives a server being down. It is emptied once delivery succeeds.

Uninstalling the app removes all of this. The settings export feature writes an encrypted file that only you can decrypt with the password you chose.

## Permissions

| Permission | Used for |
|---|---|
| Health Connect read permissions | Reading the health data types you enabled |
| Health Connect background read | Syncing on a schedule while the app is closed |
| Health Connect history read | The optional backfill beyond 30 days |
| Usage access (`PACKAGE_USAGE_STATS`) | Screen time per app |
| Notifications | Telling you when syncs keep failing (opt-in) |
| Internet | Sending data to your webhook or broker |

The app does not request access to contacts, location, the camera, the microphone or your files, and it does not use `QUERY_ALL_PACKAGES`.

## Your control

Everything is opt-in and reversible in the app: which types are read, where they go, how often, and whether anything is kept in the log. Revoking a permission in Android stops the corresponding reads immediately.

## Open source

The source code is public at [github.com/owen282000/life-dashboard-companion-app](https://github.com/owen282000/life-dashboard-companion-app) under the MIT licence, and releases are built reproducibly, so what runs on your device can be checked against this text.

## Contact

Questions about privacy: open an issue on GitHub or email the developer at the address on the GitHub profile.
