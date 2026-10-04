# Withings scales, watches and blood pressure monitors in Home Assistant via Health Connect

The Withings app on Android exports your scale, blood pressure monitor and watch data into Health Connect. Life Dashboard Companion reads it there and sends it to Home Assistant, with no Withings developer account, OAuth credentials or public webhook involved:

```
Withings device  ->  Withings app  ->  Health Connect  ->  Life Dashboard Companion  ->  Home Assistant
```

The same data can also go to an MQTT broker or to any webhook that accepts a JSON POST.

## What arrives

Withings doesn't publish a list of the types it exports. You pick them on the Health Connect permission screen during setup, and Health Connect > App permissions > Withings shows them afterward. Samsung has confirmed reading weight and body fat that the Withings app wrote to Health Connect ([features.md](../features.md#receiving-from-home-assistant)).

These are the types this app reads that match what Withings devices measure. Look for them on that permission screen:

| Device | Types |
|---|---|
| Scales | Weight, body fat, lean body mass, bone mass, body water mass |
| Blood pressure monitors | Blood pressure, heart rate |
| Watches | Steps, distance, heart rate, sleep sessions with stages, oxygen saturation |
| Thermometer | Body temperature |

In Home Assistant, daily totals and the latest readings become sensors, and each day is stored in long-term statistics on its own date.

## What stays with Withings

Scores and analyses, such as the Sleep score, Vascular Age, Nerve Health Score and ECG recordings, stay in the Withings app, because Health Connect has no type for them. The Withings app and Health Connect also count steps with different algorithms, so their step totals can differ.

## Setup

In the Withings app ([Withings' support page](https://support.withings.com/hc/en-us/articles/27322856325905-Partner-Apps-Health-Connect-Exporting-Withings-data-into-Health-Connect)):

1. Open your **Profile** and tap **Settings** (the gear icon) at the top right.
2. Tap **Export health data to Health Connect**, then **Next**.
3. In the phone's Health Connect permissions for Withings, switch on the data you want and tap **Allow**.
4. Wait for the connection to finish and tap **Done**.

If the Health Connect slider stays off, Withings advises restarting the phone.

For Home Assistant, follow [Phone to Home Assistant](../usage.md#with-the-integration): install the integration first, so the app's setup can scan its QR code. Then [install Life Dashboard Companion](../usage.md#install-the-app), scan the code and [grant its permissions](../usage.md#grant-permissions). Turn on the types above under **Data Types** on the **Health** tab, grant their Health Connect permissions, and tap **Sync Now**. The setup wizard's **The essentials** preset turns on only steps, sleep, heart rate, resting heart rate, distance, calories and weight, so switch on any other type from the table yourself. For a broker instead, see [MQTT](../usage.md#mqtt); for your own backend, [webhook.md](../webhook.md).

## Good to know

### When it arrives

Withings asks you to wait at least 15 minutes after a measurement before looking for it, because the sync can lag. A reading has to reach Withings' servers first; the Withings web dashboard shows whether it did. This app's next sync after that picks it up, with the time it was measured.

### A scale through Home Assistant instead

There is a second route for a Withings scale: Home Assistant's own [Withings integration](https://www.home-assistant.io/integrations/withings/) reads it from the Withings cloud, and this app writes the reading into Health Connect for Samsung Health or Google Health. [Your scale into Samsung Health or Google Health](../recipes/scale-to-health-connect.md#withings) has the mapping. Use one route, not both: with the Withings app writing to Health Connect as well, a weighing lands twice ([Weight appears twice](../usage.md#weight-appears-twice)).

### Readings the other way

The Withings app also imports from Health Connect, under **Share > Apps > Health Connect** ([Withings' support page](https://support.withings.com/hc/en-us/articles/47487495293585-Partner-Apps-Health-Connect-Importing-Health-Connect-data-into-the-Withings-App)). Steps entered by hand in Health Connect aren't imported.

## Compared with the Withings integration

| | Withings integration | This app |
|---|---|---|
| Setup | Withings developer account, client ID and secret, OAuth link | Install, scan a QR code |
| Where the data comes from | Withings' cloud, by webhook (public HTTPS on port 443) or polling | Your phone, pushed by the app |
| Sleep score | Yes | No, Health Connect has no type for it |
| History | Current values | Every day in long-term statistics, backfill up to a year |
| Other brands | Withings only | Any app that writes to Health Connect |

Both can run side by side. Hand the integration's readings to the phone only when the Withings app doesn't write to Health Connect itself.

Last checked: October 2026.
