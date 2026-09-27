# Garmin in Home Assistant, without a Garmin login

The usual way to get Garmin data into Home Assistant is the [Garmin Connect integration](https://github.com/cyberjunky/home-assistant-garmin_connect), which logs in to Garmin's cloud with your account. That works until Garmin changes its login: in March 2026 it did, logins failed with MFA errors and "429 Too Many Requests" ([#420](https://github.com/cyberjunky/home-assistant-garmin_connect/issues/420), [#428](https://github.com/cyberjunky/home-assistant-garmin_connect/issues/428)), and the library underneath was [deprecated](https://github.com/matin/garth/discussions/222). The integration was rewritten and works again since April, but the dependency on Garmin's login remains.

Since July 2025 (Garmin Connect 5.14.1), Garmin Connect also writes your data into Health Connect on the phone. From there this app sends it to Home Assistant with no Garmin account involved.

## Step 1: let Garmin Connect write to Health Connect

In Garmin Connect: **Settings > Connected Apps > Health Connect**, then grant the data types. It needs Android 14 or later. ([Garmin's support page](https://support.garmin.com/en-US/?faq=JToBEy0jfe6pIygark2Ui5))

## Step 2: the app and the integration

Install Life Dashboard Companion and pair it with the Home Assistant integration: [Phone to Home Assistant](../usage.md#with-the-integration). Turn on the types below in the app, grant their Health Connect permissions, and tap **Sync Now**.

## What comes through

From Garmin's list of what it writes to Health Connect, these are the types this app sends on:

| Area | Types |
|---|---|
| Activity | Steps, distance, active calories, total calories, activities as exercise sessions |
| Sleep | Sleep with stages |
| Heart | Heart rate |
| Body | Weight, body fat |

In Home Assistant, day totals and the latest reading become sensors, and every day goes into long-term statistics on its own date.

**What Garmin keeps to itself:** Body Battery, stress, HRV status, training load, Pulse Ox, respiration and resting heart rate are not written to Health Connect. If you want those in Home Assistant, the Garmin Connect integration is the only way; the two can run side by side. Floors, speed and cadence are written but are not among this app's types.

## When it arrives

Garmin Connect writes to Health Connect after each successful sync with the watch. Data can therefore reach Health Connect hours after it was recorded, with its original time, and this app still picks it up on the next sync: it looks at when a record was written, not at the time it describes.

## Scale readings the other way

Garmin does not read anything from Health Connect: "this is a one-way transfer". Readings this app writes into Health Connect from a Home Assistant scale will reach Samsung Health or Google Health, but not Garmin Connect. For that, the Garmin Connect integration has its own action to upload body composition.

## Compared with the Garmin Connect integration

| | Garmin Connect integration | This app |
|---|---|---|
| Setup | Garmin username, password and MFA in Home Assistant | Install, scan a QR code |
| Where the data comes from | Garmin's cloud | Your phone, pushed by the app |
| Body Battery, stress, training load | Yes | No, Garmin does not share them |
| History | Current values | Every day in long-term statistics, backfill up to a year |
| Breaks when Garmin changes its login | Can | No |
| Other brands | Garmin only | Any app that writes to Health Connect |
