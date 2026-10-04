# Samsung Health and Galaxy Watch in Home Assistant via Health Connect

Samsung Health syncs with Health Connect on the phone, in both directions. Life Dashboard Companion reads your Samsung Health and Galaxy Watch data there and sends it to Home Assistant. Samsung has no cloud API you can connect Home Assistant to, and its own data SDK is open only to [registered partners](https://developer.samsung.com/health/data/guide/app-verification.html), so Health Connect is the open route:

```
Galaxy Watch  ->  Samsung Health  ->  Health Connect  ->  Life Dashboard Companion  ->  Home Assistant
```

The same data can also go to an MQTT broker or to any webhook that accepts a JSON POST.

## What arrives

From Samsung's list of what it syncs with Health Connect, these are the types this app passes on:

| Area | Types |
|---|---|
| Activity | Steps, exercise sessions, total calories and distance from exercise, VO2 max from exercise |
| Sleep | Sleep sessions with stages |
| Heart and blood | Heart rate, blood pressure, blood glucose, oxygen saturation |
| Body | Weight, body fat, height, basal metabolic rate |
| Food | Nutrition |

In Home Assistant, daily totals and the latest readings become sensors, and each day is stored in long-term statistics on its own date. Daily totals come from Health Connect's own deduplicated figures, so a phone and a watch counting the same steps don't add up to twice the number.

Samsung also writes exercise power and speed, which aren't among this app's types.

## What stays with Samsung

Not in Samsung's list (as of October 2026): stress, ECG, Energy Score, heart rate variability, skin temperature, respiratory rate, resting heart rate and cycle tracking. What Samsung Health shares can change from one Samsung Health version to the next, so check on your own phone under Health Connect > App permissions > Samsung Health. Health Connect has no type for stress, ECG or Energy Score at all.

## Setup

In Samsung Health: **Settings > Health Connect**, tap **Get started**, choose the data and tap **Allow**. Reading and writing are granted separately. ([Samsung's guide](https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect))

For Home Assistant, follow [Phone to Home Assistant](../usage.md#with-the-integration): install the integration first, so the app's setup can scan its QR code. Then [install Life Dashboard Companion](../usage.md#install-the-app), scan the code and [grant its permissions](../usage.md#grant-permissions). Turn on the types above under **Data Types** on the **Health** tab, grant their Health Connect permissions, and tap **Sync Now**. The setup wizard's **The essentials** preset turns on only steps, sleep, heart rate, resting heart rate, distance, calories and weight, so switch on any other type from the table yourself. For a broker instead, see [MQTT](../usage.md#mqtt); for your own backend, [webhook.md](../webhook.md).

## Good to know

### When it arrives

Samsung Health writes to Health Connect as soon as data is created or changed on the phone. Data from a Galaxy Watch first has to reach the phone: that happens when the watch reconnects, or when you open or pull down the Samsung Health home screen. Continuous heart rate from the watch is held back for a while to save battery. ([Samsung's guide](https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect))

### Readings the other way

Because the sync goes both ways, readings from a scale that talks to Home Assistant, and not to Samsung Health, can end up in Samsung Health: this app writes them into Health Connect, and Samsung Health reads them from there. Samsung has confirmed reading weight and body fat that another app (a Withings scale) wrote to Health Connect. See [Receiving measurements from Home Assistant](../usage.md#receiving-measurements-from-home-assistant), and [A measurement from Home Assistant is not in Samsung Health](../usage.md#a-measurement-from-home-assistant-is-not-in-samsung-health) if one doesn't show up. [Your scale into Samsung Health or Google Health](../recipes/scale-to-health-connect.md) covers getting a scale into Home Assistant first, for each brand of scale.

## Compared with the companion app

Samsung has no cloud integration for Home Assistant. The other way in is the Home Assistant companion app, which can also read Health Connect on a Galaxy phone.

| | Companion app sensors | This app |
|---|---|---|
| Types | 25 | 33 |
| History | Latest value, reads the last 30 days | Every day in long-term statistics, backfill up to a year |
| Exercise sessions, nutrition, sleep stages | No | Yes |
| Destination | Your Home Assistant | Home Assistant, an MQTT broker or any webhook |

The two run side by side. The full comparison is in [features.md](../features.md#how-this-compares-to-the-home-assistant-companion-app).

Last checked: October 2026.
