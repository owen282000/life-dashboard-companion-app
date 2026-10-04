# Fitbit, Pixel Watch and Google Health in Home Assistant via Health Connect

A Fitbit or a Pixel Watch syncs to the Google Health app (the Fitbit app until May 2026), and Google Health writes your data into Health Connect on the phone. Life Dashboard Companion reads it there and sends it to Home Assistant, with no Google Cloud project, OAuth client or Fitbit API involved:

```
Fitbit or Pixel Watch  ->  Google Health app  ->  Health Connect  ->  Life Dashboard Companion  ->  Home Assistant
```

The same data can also go to an MQTT broker or to any webhook that accepts a JSON POST.

## What arrives

From Google's own list of what Google Health writes to Health Connect, these are the types this app passes on:

| Area | Types |
|---|---|
| Activity | Steps, distance, total calories, exercise sessions, VO2 max |
| Sleep | Sleep sessions with stages |
| Heart and breathing | Heart rate, resting heart rate, heart rate variability, respiratory rate |
| Body | Weight, body fat, body temperature, skin temperature, blood glucose |
| Food and drink | Hydration, nutrition |
| Cycle tracking | Menstruation period, menstruation flow, intermenstrual bleeding |

In Home Assistant, daily totals and the latest readings become sensors, and each day is stored in long-term statistics on its own date. The app's backfill sends whatever history Google Health has written to Health Connect, up to a year, each day on its own date.

Google Health also writes floors, speed, step cadence, elevation gained and exercise routes, which aren't among this app's types.

## What stays with Fitbit

Not in Google's list (as of October 2026): oxygen saturation (SpO2) and active calories. One user did see Fitbit's nightly SpO2 arrive through Health Connect ([DATA_SOURCES.md](../DATA_SOURCES.md#fitbit-comfitbitfitbitmobile)), so check on your own phone under Health Connect > App permissions. Google can change the list with any app update.

## Setup

In the Google Health app ([Google's instructions](https://support.google.com/googlehealth/answer/14506680)):

1. At the top left, tap **Connections > Partner apps**.
2. Under **Add connections**, tap **Sync your favorite health apps > Set up**.
3. Accept, choose the data types and tap **Allow**.

To check it later, or to push a sync by hand: **Connections > Partner apps > Manage Health Connect > Sync**. Google asks for Android 9 or later and an adult Google Account.

For Home Assistant, follow [Phone to Home Assistant](../usage.md#with-the-integration): install the integration first, so the app's setup can scan its QR code. Then [install Life Dashboard Companion](../usage.md#install-the-app), scan the code and [grant its permissions](../usage.md#grant-permissions). Turn on the types above under **Data Types** on the **Health** tab, grant their Health Connect permissions, and tap **Sync Now**. The setup wizard's **The essentials** preset turns on only steps, sleep, heart rate, resting heart rate, distance, calories and weight, so switch on any other type from the table yourself. For a broker instead, see [MQTT](../usage.md#mqtt); for your own backend, [webhook.md](../webhook.md).

## Good to know

### When it arrives

Nightly metrics (sleep, HRV, respiratory rate) reach Health Connect when the Google Health app syncs after you wake up, often an hour or more later, and this app's next sync picks them up. Daytime data follows the Google Health app's own sync with the watch. See [Nightly metrics arrive hours after waking](../usage.md#nightly-metrics-hrv-respiratory-rate-sleep-arrive-hours-after-waking).

### Readings the other way

Google Health also reads from Health Connect, including weight and body fat. A scale that talks to Home Assistant, and not to Fitbit, can still reach Google Health: this app writes its readings into Health Connect, and Google Health reads them from there. See [Receiving measurements from Home Assistant](../usage.md#receiving-measurements-from-home-assistant). Google Health doesn't read blood pressure from Health Connect. [Your scale into Samsung Health or Google Health](../recipes/scale-to-health-connect.md) covers getting a scale into Home Assistant first, for each brand of scale.

### The old Fitbit integration

Home Assistant's Fitbit integration uses the legacy Fitbit Web API. Google ended support for that API on September 30, 2026 and turns it off on October 30, 2026, after which it no longer syncs ([Google's release notes](https://developers.google.com/health/release-notes)). Health Connect doesn't depend on that API.

## Compared with the Google Health integration

The [Google Health integration](https://www.home-assistant.io/integrations/google_health/), new in Home Assistant 2026.8, reads Google's cloud with the new Google Health API, the API that keeps working after the old Fitbit one is turned off.

| | Google Health integration | This app |
|---|---|---|
| Setup | Google Cloud project, OAuth client, account link | Install, scan a QR code |
| Where the data comes from | Google's cloud, polled every 15 minutes or hourly | Your phone, pushed by the app |
| History | Current values | Every day in long-term statistics, backfill up to a year |
| HRV, respiratory rate, skin temperature | No sensor | Yes |
| Other brands | Fitbit and Pixel Watch only | Any app that writes to Health Connect |

Both can run side by side.

Last checked: October 2026.
