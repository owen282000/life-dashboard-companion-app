# Your watch in Home Assistant, without a cloud login

Most watch and health apps now write their data into [Health Connect](https://developer.android.com/health-and-fitness/health-connect) on your phone. That makes the phone the one place where Fitbit, Garmin, Samsung and Gadgetbridge data meet, and from there Life Dashboard Companion hands it to Home Assistant directly:

```
watch  ->  brand app  ->  Health Connect  ->  Life Dashboard Companion  ->  Home Assistant
```

No developer account, no Google Cloud project, no password stored in Home Assistant, and nothing to break when a vendor changes its login or shuts down an API. The same route works for every brand, so switching watches later changes nothing on the Home Assistant side.

## Pick your brand

| Brand | Writes to Health Connect | Page |
|---|---|---|
| Fitbit, Pixel Watch (Google Health app) | Yes, and reads too | [fitbit.md](fitbit.md) |
| Garmin (Garmin Connect) | Yes, one way | [garmin.md](garmin.md) |
| Samsung Galaxy Watch (Samsung Health) | Yes, both ways | [samsung.md](samsung.md) |
| Gadgetbridge (Amazfit, Bangle.js, Pebble, Xiaomi and others, without the vendor app) | Yes, one way | [gadgetbridge.md](gadgetbridge.md) |

Oura, Withings, Polar, Whoop, Zepp and Xiaomi Mi Fitness also write to Health Connect; what each writes is in their own documentation.

## Three ways into Home Assistant

| | Companion app sensors | A cloud integration (Google Health, Garmin Connect) | Life Dashboard Companion |
|---|---|---|---|
| Brands | Any that writes to Health Connect | One per integration | Any that writes to Health Connect |
| Cloud account in Home Assistant | No | Yes, and for Google Health your own Cloud project | No |
| History | Latest value, last 30 days read | Depends on the integration | Every day in long-term statistics, backfill up to a year |
| Types | About 25 | Per integration | 33 |
| Vendor-only metrics (Body Battery, stress scores) | No | Yes, where the vendor API has them | No |
| Scale readings back to the phone | No | Garmin Connect only | Yes, into Health Connect |

The companion app is already installed for most people and is enough for "current heart rate on a dashboard". A cloud integration is the only way to metrics a vendor keeps to itself. This app is for history, for more types, and for not depending on a vendor's API. They also combine: the app runs next to the companion app, and next to a cloud integration if you want the vendor-only numbers too.

## Setting up the Home Assistant side

The same for every brand: [Phone to Home Assistant](../usage.md#with-the-integration). Count on 15 to 30 minutes the first time. After that, the brand page tells you which switch to flip in the brand's own app, and what to expect.

Something missing or wrong for your brand? Corrections are welcome as an issue or pull request, and [DATA_SOURCES.md](../DATA_SOURCES.md) collects what users found per source app.
