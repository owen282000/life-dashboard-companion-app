# Garmin in Home Assistant via Health Connect, without a Garmin login

Garmin Connect on your phone writes your watch's data into Health Connect. Life Dashboard Companion reads it there and sends it to Home Assistant, so Home Assistant never needs your Garmin username, password or MFA code:

```
Garmin watch  ->  Garmin Connect  ->  Health Connect  ->  Life Dashboard Companion  ->  Home Assistant
```

The same data can also go to an MQTT broker or to any webhook that accepts a JSON POST.

## What arrives

From Garmin's list of what it writes to Health Connect, these are the types this app passes on:

| Area | Types |
|---|---|
| Activity | Steps, distance, active calories, total calories, activities as exercise sessions |
| Sleep | Sleep sessions with stages |
| Heart | Heart rate |
| Body | Weight, body fat |

In Home Assistant, daily totals and the latest readings become sensors, and each day is stored in long-term statistics on its own date. On a phone where Samsung Health or another app also counts steps, the daily totals still count every step once: they come from Health Connect's own deduplicated figures ([why](../usage.md#samsung-health-and-garmin-both-write-steps-will-they-double)).

Garmin also writes floors, speed, elevation gained, cycling cadence and swimming strokes, which aren't among this app's types.

## What stays with Garmin

Not in Garmin's list (as of October 2026): Body Battery, stress, HRV status, training load, Pulse Ox, respiration and resting heart rate. Health Connect has no type for Body Battery, stress or training load at all. Garmin can change the list with any Garmin Connect update. If you want those in Home Assistant, the [Garmin Connect integration](https://github.com/cyberjunky/home-assistant-garmin_connect) reads them from Garmin's cloud; it can run side by side with this app.

## Setup

In Garmin Connect: **Settings > Connected Apps > Health Connect**, then grant the data types. It needs Garmin Connect 5.14.1 or later and Android 14 or later. ([Garmin's support page](https://support.garmin.com/en-US/?faq=JToBEy0jfe6pIygark2Ui5))

For Home Assistant, follow [Phone to Home Assistant](../usage.md#with-the-integration): install the integration first, so the app's setup can scan its QR code. Then [install Life Dashboard Companion](../usage.md#install-the-app), scan the code and [grant its permissions](../usage.md#grant-permissions). Turn on the types above under **Data Types** on the **Health** tab, grant their Health Connect permissions, and tap **Sync Now**. The setup wizard's **The essentials** preset turns on only steps, sleep, heart rate, resting heart rate, distance, calories and weight, so switch on any other type from the table yourself. For a broker instead, see [MQTT](../usage.md#mqtt); for your own backend, [webhook.md](../webhook.md).

## Good to know

### When it arrives

Garmin Connect writes to Health Connect after each successful sync with the watch. Data can therefore reach Health Connect hours after it was recorded, with its original time. This app still picks it up on the next sync, because it looks at when a record was written, not at the time it describes.

### Readings the other way

Garmin doesn't read anything from Health Connect; its support page calls it "a one-way transfer". Readings this app writes into Health Connect from a Home Assistant scale reach Samsung Health or Google Health, but not Garmin Connect. For that, the Garmin Connect integration has its own action to upload body composition. [Your scale into Samsung Health or Google Health](../recipes/scale-to-health-connect.md) covers getting a scale into Home Assistant first.

## Compared with the Garmin Connect integration

The [Garmin Connect integration](https://github.com/cyberjunky/home-assistant-garmin_connect) logs in to Garmin's cloud with your account. In March 2026 Garmin changed its login, and the integration's logins failed with MFA errors and "429 Too Many Requests" until a rewrite in April ([#420](https://github.com/cyberjunky/home-assistant-garmin_connect/issues/420), [#428](https://github.com/cyberjunky/home-assistant-garmin_connect/issues/428)).

| | Garmin Connect integration | This app |
|---|---|---|
| Setup | Garmin username, password and MFA in Home Assistant | Install, scan a QR code |
| Where the data comes from | Garmin's cloud | Your phone, pushed by the app |
| Body Battery, stress, training load | Yes | No, Garmin doesn't share them |
| History | Current values | Every day in long-term statistics, backfill up to a year |
| Breaks when Garmin changes its login | It can, and did in March 2026 | No |
| Other brands | Garmin only | Any app that writes to Health Connect |

Last checked: October 2026.
