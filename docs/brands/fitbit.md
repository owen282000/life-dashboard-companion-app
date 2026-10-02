# Fitbit and Pixel Watch in Home Assistant, without a Google Cloud project

On 19 May 2026 the Fitbit app became the Google Health app, and in September 2026 Google turns down the legacy Fitbit Web API. Home Assistant's Fitbit integration is built on that API, so it stops syncing with it. Its successor, the [Google Health integration](https://www.home-assistant.io/integrations/google_health/) (Home Assistant 2026.8), works, but it polls Google's cloud and needs your own Google Cloud project and OAuth client to set up.

There is a shorter way. The Google Health app on your phone already writes your data into Health Connect, and from there this app sends it to Home Assistant directly.

## Step 1: let Google Health share with Health Connect

In the Google Health app: at the top left, tap **Connections > Partner apps**. Under **Add connections**, tap **Sync your favorite health apps > Set up**, accept, choose the data types and tap **Allow**. ([Google's instructions](https://support.google.com/googlehealth/answer/14506680))

To check it later, or to push a sync by hand: **Connections > Partner apps > Manage Health Connect > Sync**.

## Step 2: the app and the integration

Install Life Dashboard Companion and pair it with the Home Assistant integration: [Phone to Home Assistant](../usage.md#with-the-integration). Turn on the types below in the app, grant their Health Connect permissions, and tap **Sync Now**.

## What comes through

From Google's own list of what Google Health writes to Health Connect, these are the types this app sends on:

| Area | Types |
|---|---|
| Activity | Steps, distance, total calories, exercise sessions, VO2 max |
| Sleep | Sleep sessions with stages |
| Heart and breathing | Heart rate, resting heart rate, heart rate variability, respiratory rate |
| Body | Weight, body fat, body temperature, skin temperature, blood glucose |
| Food and drink | Hydration, nutrition |

In Home Assistant, day totals and the latest reading become sensors, and every day goes into long-term statistics on its own date. With the app's backfill, a year of Fitbit history shows up as a year.

Not in Google's list: **oxygen saturation (SpO2)** and **active calories**. One user did see Fitbit's nightly SpO2 arrive through Health Connect ([DATA_SOURCES.md](../DATA_SOURCES.md#fitbit-comfitbitfitbitmobile)), so check on your own phone under Health Connect > App permissions. Google Health also writes floors, speed and step cadence, which are not among this app's types.

## When it arrives

Nightly metrics (sleep, HRV, respiratory rate) reach Health Connect when the Google Health app syncs after you wake up, often an hour or more later, and the next sync of this app picks them up. Daytime data follows the Google Health app's own sync with the watch.

## Scale readings the other way

Google Health also reads from Health Connect, including weight and body fat. A scale that talks to Home Assistant, and not to Fitbit, can be written into Health Connect by this app, and then shows up in Google Health: see [Receiving measurements from Home Assistant](../usage.md#receiving-measurements-from-home-assistant). Blood pressure is not among what Google Health reads.

[Your scale into Samsung Health or Google Health](../recipes/scale-to-health-connect.md) covers getting a scale into Home Assistant first, per brand of scale.

## Compared with the Google Health integration

| | Google Health integration | This app |
|---|---|---|
| Setup | Google Cloud project, OAuth client, account link | Install, scan a QR code |
| Where the data comes from | Google's cloud, polled every 15 minutes or hourly | Your phone, pushed by the app |
| History | Current values | Every day in long-term statistics, backfill up to a year |
| HRV, respiratory rate, skin temperature | No sensor | Yes |
| Other brands | Fitbit and Pixel Watch only | Any app that writes to Health Connect |

Both can run side by side.
