# Coros in Home Assistant via Health Connect

The Coros app on your phone can write your watch's data into Health Connect. Life Dashboard Companion reads it there and sends it to Home Assistant, so Home Assistant never needs your Coros login:

```
Coros watch  ->  Coros app  ->  Health Connect  ->  Life Dashboard Companion  ->  Home Assistant
```

The same data can also go to an MQTT broker or to any webhook that accepts a JSON POST.

## What arrives

Whatever the Coros app writes to Health Connect, this app passes on, each record with Coros as its `source`. Which types that are is up to Coros and can change with any update of its app; **Preview payload** under **What to send** shows what your phone really has.

One of them needs attention: total calories. The rest of this page is about that.

## Total calories come out too low

Seen on a phone with a Coros watch in October 2026: the Coros app writes one total calories record per hour, and the figure in it is the active energy of that hour only. A quiet hour, such as one at night, is written as 0 kcal.

Health Connect normally fills in the energy your body burns at rest for the hours no app wrote a total for. Here every hour has a total already, so it adds nothing. The day's total calories are then your active calories alone, several hundred to more than a thousand kcal below what you burned.

That shows up everywhere the day total goes:

- `total_calories` in `daily_totals` of the payload
- the **Total calories today** sensor, in the integration and over MQTT
- the total calories statistic of each day in Home Assistant

The app sends what Health Connect holds and doesn't correct it. It writes no estimated records into Health Connect either: those would end up in the data every other app reads, and they only count while the app priority in Health Connect is set just right.

## A template that adds the resting part

In Home Assistant you can add the resting part yourself. This template sensor takes the total calories of today, which for Coros hold the active part, and adds the share of your basal metabolic rate for the hours of the day that have passed:

```yaml
template:
  - sensor:
      - name: "Calories burned today"
        unique_id: coros_calories_burned_today
        unit_of_measurement: "kcal"
        state: >
          {% set active = states('sensor.your_phone_total_calories_today') | float(0) %}
          {% set per_day = states('sensor.your_phone_basal_metabolic_rate') | float(1650) %}
          {% set part_of_day = (now() - today_at('00:00')).total_seconds() / 86400 %}
          {{ (active + per_day * part_of_day) | round(0) }}
```

Replace `your_phone` with the name of your phone in Home Assistant. Two things to check:

- **Basal metabolic rate.** The sensor exists when an app writes that type to Health Connect and you switched on **Basal metabolic rate** under **What to send**. It is in kcal per day. Without it, the template uses the `1650` in the `float(...)`: put your own figure there.
- **Today only.** The template follows the running day. For a finished day, add a full day of basal metabolic rate to that day's total calories; the statistic in Home Assistant keeps the figure Health Connect gave.

Over MQTT the same template works with the entity IDs of the MQTT device.

## Setup

In the Coros app, connect Health Connect under the third-party apps in the settings, and grant the data types.

For Home Assistant, follow [Phone to Home Assistant](../usage.md#with-the-integration): install the integration first, so the app's setup can scan its QR code. Then [install Life Dashboard Companion](../usage.md#install-the-app), scan the code and [grant its permissions](../usage.md#grant-permissions). Turn on the types you want under **What to send** on the **Health** tab, grant their Health Connect permissions, and tap **Sync Now**. For a broker instead, see [MQTT](../usage.md#mqtt); for your own backend, [webhook.md](../webhook.md).

Do you have a Coros watch and see something else? Corrections are welcome as an issue or pull request, and [DATA_SOURCES.md](../DATA_SOURCES.md) collects what users found per source app.

Last checked: October 2026.
