# Gadgetbridge in Home Assistant via Health Connect, no vendor account

[Gadgetbridge](https://gadgetbridge.org/) talks to Amazfit, Bangle.js, Pebble, Xiaomi and many other watches and bands without the vendor's app or cloud, and writes their data into Health Connect. Life Dashboard Companion reads it there and sends it to Home Assistant. The whole chain stays on your own devices, and on Android 14 and newer, where Health Connect is part of Android, every step is open source:

```
watch  ->  Gadgetbridge  ->  Health Connect  ->  Life Dashboard Companion  ->  Home Assistant
```

The same data can also go to an MQTT broker or to any webhook that accepts a JSON POST.

## What arrives

From Gadgetbridge's list of what it writes to Health Connect, these are the types this app passes on:

| Area | Types |
|---|---|
| Activity | Steps, distance, exercise sessions with active and total calories, VO2 max |
| Sleep | Sleep sessions |
| Heart and breathing | Heart rate, resting heart rate, heart rate variability, oxygen saturation, respiratory rate |
| Body | Weight, body temperature, skin temperature, blood glucose |

What your watch actually records depends on the model and on what Gadgetbridge supports for it. Gadgetbridge also writes cadence, elevation gained, exercise routes, power and speed, which aren't among this app's types.

In Home Assistant, daily totals and the latest readings become sensors, and each day is stored in long-term statistics on its own date. To get your older data in as well, let Gadgetbridge send its history on its first sync, then run a backfill in this app.

## What stays with Gadgetbridge

Anything outside Gadgetbridge's Health Connect list stays in Gadgetbridge, for example stress and the scores some watches compute themselves. Health Connect has no type for stress at all. The list grows with Gadgetbridge releases, so check [Gadgetbridge's documentation](https://gadgetbridge.org/basics/integrations/health-connect/) for the current one.

## Setup

In Gadgetbridge 0.89.0 or later: **Settings > External Integrations > Health Connect**, enable the sync and grant the permissions. Turn on **Sync after device sync** so new data goes out after every watch sync. On the first sync you choose how much history to send: everything, or a recent period. ([Gadgetbridge's documentation](https://gadgetbridge.org/basics/integrations/health-connect/))

For Home Assistant, follow [Phone to Home Assistant](../usage.md#with-the-integration): install the integration first, so the app's setup can scan its QR code. Then [install Life Dashboard Companion](../usage.md#install-the-app), scan the code and [grant its permissions](../usage.md#grant-permissions). Turn on the types above under **Data Types** on the **Health** tab, grant their Health Connect permissions, and tap **Sync Now**. The setup wizard's **The essentials** preset turns on only steps, sleep, heart rate, resting heart rate, distance, calories and weight, so switch on any other type from the table yourself. For a broker instead, see [MQTT](../usage.md#mqtt); for your own backend, [webhook.md](../webhook.md).

## Good to know

### When it arrives

With **Sync after device sync** on, Gadgetbridge writes to Health Connect right after each sync with the watch, and this app's next sync picks it up. Data that arrives late keeps its original time, and is still picked up. A large first sync of history can take a while; Gadgetbridge retries by itself when Health Connect slows it down.

### One minute twice with a Huawei watch

With a Huawei watch, Gadgetbridge can leave one minute's steps, distance and calories twice in Health Connect after a sync, with the copy dated one minute later than the original. Daily totals, in Home Assistant and in the Health Connect app alike, then include that minute twice. It's a small error, at most a minute per sync; [DATA_SOURCES.md](../DATA_SOURCES.md#gadgetbridge-nodomainfreeyourgadgetgadgetbridge) explains where it comes from.

### Readings the other way

Gadgetbridge only writes to Health Connect; it doesn't read from it. Readings this app writes into Health Connect from a Home Assistant scale won't show up in Gadgetbridge, but they will in any app that does read Health Connect.

## Compared with the vendor apps

| | Vendor app (Zepp, Mi Fitness and others) | Gadgetbridge with this app |
|---|---|---|
| Vendor account and cloud | Required | None |
| Watch features | All of them | What Gadgetbridge supports for your model |
| Open source | No | Gadgetbridge, this app and the integration (Health Connect too on Android 14 and newer) |

Last checked: October 2026.
