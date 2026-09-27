# Gadgetbridge in Home Assistant: no vendor account anywhere

[Gadgetbridge](https://gadgetbridge.org/) talks to Amazfit, Bangle.js, Pebble, Xiaomi and many other watches and bands without the vendor's app or cloud. Since release 0.89.0 (February 2026) it writes to Health Connect, and with this app on top the whole chain stays on your own devices:

```
watch  ->  Gadgetbridge  ->  Health Connect  ->  Life Dashboard Companion  ->  Home Assistant
```

No vendor account, no cloud, open source at every step.

## Step 1: let Gadgetbridge write to Health Connect

In Gadgetbridge: **Settings > External Integrations > Health Connect**, enable the sync and grant the permissions. Turn on **Sync after device sync** so new data goes out after every watch sync. On the first sync you choose how much history to send. ([Gadgetbridge's documentation](https://gadgetbridge.org/basics/integrations/health-connect/))

## Step 2: the app and the integration

Install Life Dashboard Companion and pair it with the Home Assistant integration: [Phone to Home Assistant](../usage.md#with-the-integration). Turn on the types below in the app, grant their Health Connect permissions, and tap **Sync Now**.

## What comes through

From Gadgetbridge's list of what it writes to Health Connect, these are the types this app sends on:

| Area | Types |
|---|---|
| Activity | Steps, distance, exercise sessions with calories, VO2 max |
| Sleep | Sleep sessions |
| Heart and breathing | Heart rate, resting heart rate, heart rate variability, oxygen saturation, respiratory rate |
| Body | Weight, body temperature, skin temperature, blood glucose |

What your watch actually records depends on the model and on what Gadgetbridge supports for it.

In Home Assistant, day totals and the latest reading become sensors, and every day goes into long-term statistics on its own date. Sending Gadgetbridge's history on the first sync, and running the app's backfill after it, gives you that history in Home Assistant too.

## When it arrives

With **Sync after device sync** on, Gadgetbridge writes to Health Connect right after each sync with the watch, and the next sync of this app picks it up. Data that arrives late keeps its original time, and is still picked up.

## Scale readings the other way

Gadgetbridge only writes to Health Connect, it does not read from it. Readings this app writes into Health Connect from a Home Assistant scale will not show up in Gadgetbridge, but they will in any app that does read Health Connect.
