# Samsung Health in Home Assistant

Samsung has no cloud API that you can connect Home Assistant to, and its own data SDK is only open to [registered partners](https://developer.samsung.com/health/data/guide/app-verification.html). The one open door is Health Connect: Samsung Health synchronises with it in both directions, and from there this app sends your data to Home Assistant.

## Step 1: let Samsung Health sync with Health Connect

In Samsung Health: **Settings > Health Connect**, tap **Get started**, choose the data and tap **Allow**. Reading and writing are granted separately. ([Samsung's guide](https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect))

## Step 2: the app and the integration

Install Life Dashboard Companion and pair it with the Home Assistant integration: [Phone to Home Assistant](../usage.md#with-the-integration). Turn on the types below in the app, grant their Health Connect permissions, and tap **Sync Now**.

## What comes through

From Samsung's list of what it syncs with Health Connect, these are the types this app sends on:

| Area | Types |
|---|---|
| Activity | Steps, exercise sessions with calories and distance |
| Sleep | Sleep sessions with stages |
| Heart and blood | Heart rate, blood pressure, blood glucose, oxygen saturation |
| Body | Weight, body fat, height, basal metabolic rate |
| Food | Nutrition |

In Home Assistant, day totals and the latest reading become sensors, and every day goes into long-term statistics on its own date. Day totals come from Health Connect's own deduplicated figures, so a phone and a watch counting the same steps do not add up to twice the number.

**What Samsung keeps to itself:** stress, ECG, Energy Score, heart rate variability, skin temperature, respiratory rate, resting heart rate and cycle tracking are not shared with Health Connect, so no app can pass them on.

## When it arrives

Samsung Health writes to Health Connect as soon as data is created or changed on the phone. Data from a Galaxy Watch first has to reach the phone: that happens when the watch reconnects, or when you open or pull down the Samsung Health home screen. Continuous heart rate from the watch is held back for a while to save battery.

## Scale readings the other way

Because the sync goes both ways, a scale that talks to Home Assistant, and not to Samsung Health, can end up in Samsung Health: this app writes the reading into Health Connect, and Samsung Health reads it from there. Samsung has confirmed reading weight and body fat that another app (a Withings scale) wrote to Health Connect; see [Receiving measurements from Home Assistant](../usage.md#receiving-measurements-from-home-assistant), and [A measurement from Home Assistant is not in Samsung Health](../usage.md#a-measurement-from-home-assistant-is-not-in-samsung-health) if one does not show up.

[Your scale into Samsung Health or Google Health](../recipes/scale-to-health-connect.md) covers getting a scale into Home Assistant first, per brand of scale.

## Compared with the companion app

The Home Assistant companion app can also read Health Connect on a Galaxy. It keeps the latest value as a sensor and reads the last 30 days. This app adds the history: every day in long-term statistics, with a backfill of up to a year, and 33 types instead of about 25. The two run side by side.
