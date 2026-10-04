# Oura Ring in Home Assistant via Health Connect

The Oura app on Android writes your ring's sleep, heart rate, HRV and activity into Health Connect. Life Dashboard Companion reads it there and sends it to Home Assistant, with no Oura API token or developer app involved:

```
Oura Ring  ->  Oura app  ->  Health Connect  ->  Life Dashboard Companion  ->  Home Assistant
```

The same data can also go to an MQTT broker or to any webhook that accepts a JSON POST.

## What arrives

From Oura's list of what it exports to Health Connect, these are the types this app passes on:

| Area | Types |
|---|---|
| Activity | Steps, distance, active calories, exercise sessions |
| Sleep | Sleep sessions with stages |
| Heart | Heart rate, heart rate variability |
| Body | Height, weight |

Oura's support page lists sleep without detail; Google's [Health Connect announcement](https://android-developers.googleblog.com/2023/08/health-connect-brings-together-peloton-oura-lifesum-for-deeper-health-and-fitness-insights.html) with Oura describes the shared sleep data as stages, duration, heart rate and HRV.

In Home Assistant, daily totals and the latest readings become sensors, and each day is stored in long-term statistics on its own date.

## What stays with Oura

The Readiness, Sleep and Activity scores stay in the Oura app: Health Connect has no type for a score. Also not in Oura's list (as of October 2026): blood oxygen, temperature, resting heart rate and respiratory rate. Oura can change the list with any app update, so check on your own phone under Health Connect > App permissions > Oura. If you want the scores, blood oxygen or temperature in Home Assistant, the [Oura Ring integration](https://github.com/louispires/Oura-Home-Assistant-Integration) reads them from Oura's cloud; it can run side by side with this app.

## Setup

Oura lists the Health Connect integration for Android only, for Gen2 rings, and for Gen3 or later rings with an active membership. In the Oura app ([Oura's support page](https://support.ouraring.com/hc/en-us/articles/10786105824531-Health-Connect-by-Android-Integration)):

1. Open the menu at the top left of the home screen and tap **Settings**.
2. Under **Data Sharing**, tap **Health Connect**.
3. Use the toggles to choose which data to share, and allow them in Health Connect.

For Home Assistant, follow [Phone to Home Assistant](../usage.md#with-the-integration): install the integration first, so the app's setup can scan its QR code. Then [install Life Dashboard Companion](../usage.md#install-the-app), scan the code and [grant its permissions](../usage.md#grant-permissions). Turn on the types above under **Data Types** on the **Health** tab, grant their Health Connect permissions, and tap **Sync Now**. The setup wizard's **The essentials** preset turns on only steps, sleep, heart rate, resting heart rate, distance, calories and weight, so switch on any other type from the table yourself. For a broker instead, see [MQTT](../usage.md#mqtt); for your own backend, [webhook.md](../webhook.md).

## Good to know

### When it arrives

The ring syncs with the Oura app first, and the Oura app writes to Health Connect after that. So a night's sleep reaches Health Connect only after the ring has synced with the app in the morning, and this app's next sync picks it up, with the night's original times.

### Readings the other way

Oura also reads from Health Connect: activity, exercise, steps, VO2 max, heart rate, weight and blood pressure. A scale or blood pressure monitor in Home Assistant can reach Oura that way: this app writes the readings into Health Connect, and Oura reads them from there. See [Receiving measurements from Home Assistant](../usage.md#receiving-measurements-from-home-assistant) and, for the scale side, [Your scale into Samsung Health or Google Health](../recipes/scale-to-health-connect.md). Oura advises opening both the Oura app and the app that wrote the data at least once a day; if they weren't both opened before midnight, Oura may not import that day's data.

### If the sync stops

Oura's own fix is to switch all the toggles under **Health Connect** off and on again, then restart the phone.

## Compared with the Oura integration

The [Oura Ring integration](https://github.com/louispires/Oura-Home-Assistant-Integration) in HACS reads Oura's cloud through the Oura API.

| | Oura Ring integration | This app |
|---|---|---|
| Setup | Oura developer application, client ID and secret, OAuth link | Install, scan a QR code |
| Where the data comes from | Oura's cloud, polled every 5 minutes by default | Your phone, pushed by the app |
| History | Imported into long-term statistics, 3 months by default, up to 4 years | Every day in long-term statistics, backfill up to a year |
| Readiness, Sleep and Activity scores | Yes | No, Health Connect has no type for them |
| Blood oxygen, temperature, stress | Yes | No, they don't reach Health Connect |
| Other brands | Oura only | Any app that writes to Health Connect |

Both can run side by side.

Last checked: October 2026.
