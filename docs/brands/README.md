# Your watch or ring in Home Assistant via Health Connect

Most watch, ring and scale apps write their data into [Health Connect](https://developer.android.com/health-and-fitness/health-connect) on your phone. Life Dashboard Companion reads it there and sends it to Home Assistant directly, the same way for every brand:

```
watch  ->  brand app  ->  Health Connect  ->  Life Dashboard Companion  ->  Home Assistant
```

No developer account, no Google Cloud project, no password stored in Home Assistant, and nothing to break when a vendor changes its login or shuts down an API. Switching watches later changes nothing on the Home Assistant side. The same data can also go to an MQTT broker or to any webhook that accepts a JSON POST.

## Pick your brand

| Brand | Writes to Health Connect | Reads back from Health Connect | Page |
|---|---|---|---|
| Fitbit, Pixel Watch (Google Health app) | Yes | Yes, including weight and body fat | [fitbit.md](fitbit.md) |
| Garmin (Garmin Connect) | Yes | No | [garmin.md](garmin.md) |
| Samsung Galaxy Watch (Samsung Health) | Yes | Yes, including weight and body fat | [samsung.md](samsung.md) |
| Oura Ring (Oura app) | Yes | Yes, including weight and blood pressure | [oura.md](oura.md) |
| Withings (Withings app) | Yes | Yes | [withings.md](withings.md) |
| Gadgetbridge (Amazfit, Bangle.js, Pebble, Xiaomi and others, without the vendor app) | Yes | No | [gadgetbridge.md](gadgetbridge.md) |

Huawei Health doesn't write to Health Connect. For a Huawei watch that Gadgetbridge supports, [Gadgetbridge](gadgetbridge.md) is the way in.

Polar, Whoop, Zepp and Xiaomi Mi Fitness also write to Health Connect; what each one writes is in its own documentation.

"Reads back" matters for scales and blood pressure monitors that talk to Home Assistant and not to your phone: this app writes their readings into Health Connect, and any app that reads Health Connect can pick them up. [Your scale into Samsung Health or Google Health](../recipes/scale-to-health-connect.md) covers Renpho, Eufy, Withings and Xiaomi scales.

## Other ways into Home Assistant

The Home Assistant companion app also reads Health Connect. Some brands can be read from the vendor's cloud as well: Google Health and Withings through Home Assistant's own integrations, Garmin Connect and Oura through HACS. The Fitbit, Garmin, Oura and Withings pages compare this app with the brand's cloud integration; the comparison with the companion app is in [features.md](../features.md#how-this-compares-to-the-home-assistant-companion-app). You can also use them together: this app runs next to the companion app, and next to a cloud integration if you want the vendor-only numbers too.

## Setting up the Home Assistant side

The same for every brand: [Phone to Home Assistant](../usage.md#with-the-integration). Plan on 15 to 30 minutes the first time. After that, the brand page tells you which switch to flip in the brand's own app, and what to expect. For a broker instead, see [MQTT](../usage.md#mqtt); for your own backend, [webhook.md](../webhook.md).

Something missing or wrong for your brand? Corrections are welcome as an issue or pull request, and [DATA_SOURCES.md](../DATA_SOURCES.md) collects what users found per source app.

Last checked: October 2026.
