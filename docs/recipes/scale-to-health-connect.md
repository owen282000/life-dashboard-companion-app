# Your scale into Samsung Health or Google Health

A scale that Home Assistant can read, over Bluetooth or through a cloud account, has no way into Samsung Health or Google Health on its own: those apps read Health Connect, and something on the phone has to write the reading there. Life Dashboard Companion does that. The [Life Dashboard integration](https://github.com/owen282000/life-dashboard-ha) hands the reading to the phone in its response to the app's next sync, and the app writes it into Health Connect with the time it was taken. Samsung Health or Google Health picks it up from there.

This page covers the common ways a scale gets into Home Assistant, which entity goes into which Health Connect type, and the template sensors some routes need. Checked on October 2, 2026.

## What you need

- The app (1.20.0 or later) paired with the Life Dashboard integration (0.7.0 or later): [Phone to Home Assistant](../usage.md#with-the-integration).
- An Android phone. The iOS app doesn't receive measurements.
- The scale's readings as sensors in Home Assistant, from one of the routes below. For a Bluetooth scale that means Bluetooth within a few meters of the scale: a USB adapter on the Home Assistant machine, or an [ESPHome Bluetooth proxy](https://esphome.io/components/bluetooth_proxy.html) near the bathroom. A NAS in a closet usually needs the proxy.

## Pick your route

### Xiaomi: Xiaomi BLE and bodymiscale

For the Mi Body Composition Scale 2 and the other Xiaomi scales Home Assistant finds over Bluetooth. The core [Xiaomi BLE](https://www.home-assistant.io/integrations/xiaomi_ble/) integration gives the weight and, on the body composition models, the impedance: the weak current through your feet that body composition is calculated from. [bodymiscale](https://github.com/dckiller51/bodymiscale) (in HACS) turns those into body fat, lean body mass and the rest, per person; it asks for your height, birthday and gender plus the weight and impedance sensors.

- **Home Assistant 2026.10 or later.** On 2026.9.x Xiaomi BLE drops every scale update ([#181546](https://github.com/home-assistant/core/issues/181546), fixed by [#182328](https://github.com/home-assistant/core/pull/182328) in 2026.10). Until then, [ble-scale-sync](#renpho-eufy-yunmai-and-others-ble-scale-sync) also reads Xiaomi scales.
- **The S400** still fails in the core integration, even on 2026.10 ([#183853](https://github.com/home-assistant/core/issues/183853); the fix, [#173260](https://github.com/home-assistant/core/pull/173260), is open). ble-scale-sync reads it with its bind key.
- **Several people on one scale:** add bodymiscale once per person. Each person gets their own `sensor.<person>_...` entities and chooses how a weighing is recognized as theirs: by weight range, nearest weight, a notification, the S400's profile ID, or not at all. Map each person's entities to that person's phone.

| Health Connect type | Entity |
|---|---|
| Weight | bodymiscale's Weight |
| Body fat | bodymiscale's Body fat (%) |
| Lean body mass | bodymiscale's Lean body mass (kg) |
| Bone mass | bodymiscale's Bone mass (kg) |
| Body water mass | The template [water in kg](#water-in-kg) from bodymiscale's Water (%) |
| Measured at | bodymiscale's Last measurement, in each type's slot |

Body fat, lean body mass, bone mass and water only exist when bodymiscale has an impedance sensor; on a scale without one, map weight alone. Map bodymiscale's own lean body mass as it is; the lean body mass template further down is for scales that give none.

### Renpho, Eufy, Yunmai and others: ble-scale-sync

[ble-scale-sync](https://github.com/KristianP26/ble-scale-sync) knows more than 30 Bluetooth scale protocols, among them Renpho, Eufy's C1 and P1, Yunmai, Etekcity and Xiaomi. It runs on a Raspberry Pi, in Docker, or as a Home Assistant app (add-on). It publishes to MQTT with Home Assistant Discovery, so it needs a broker: the Mosquitto broker app and the MQTT integration ([MQTT](../usage.md#mqtt) has the steps). It needs Bluetooth near the scale: an adapter on the machine it runs on, or an ESP32 proxy that Home Assistant hasn't adopted. For a scale that broadcasts its reading (the Xiaomi scales, the S400 among them, and Eufy's P2), its Home Assistant Bluetooth transport (1.27.0 or later) can use the adapter and proxies Home Assistant already has instead. Renpho, Yunmai, Eufy's C1 and P1 and most others need a connection, which that transport can't make.

Water comes as a percentage and there is no lean body mass, so two values have to be computed. The cleanest way is one trigger-based template on ble-scale-sync's own MQTT message: one weighing is one message, so it gives exactly one reading per type. See [ble-scale-sync, one weighing at a time](#ble-scale-sync-one-weighing-at-a-time), then map the template's sensors, not the ones ble-scale-sync's Discovery creates:

| Health Connect type | Entity |
|---|---|
| Weight | The template's weight |
| Body fat | The template's body fat |
| Lean body mass | The template's lean body mass |
| Bone mass | The template's bone mass |
| Body water mass | The template's body water mass |
| Measured at | Leave empty; the message arrives as you weigh |

Two settings matter:

- **Set `retain: false`** for the MQTT exporter. With the default the broker keeps the last weighing and hands it to Home Assistant again after every restart; the template's trigger fires on it, and the old weighing becomes a second reading. The add-on always retains, so there this needs its custom configuration (a `config.yaml` of your own, described in the add-on's documentation).
- **A scale that only measures weight** still gets fat, water and bone values in the message, estimated from BMI. Map weight alone for those, or Health Connect fills up with numbers the scale never measured.

### Eufy: EufyLife BLE

The core [EufyLife BLE](https://www.home-assistant.io/integrations/eufylife_ble/) integration gives weight only, even on the models with body composition. Map its **Weight** to Weight, not **Real-time weight**, which moves while you step on. For body fat and the rest from a C1 or P1, use ble-scale-sync.

### Withings

The core [Withings](https://www.home-assistant.io/integrations/withings/) integration needs no templates: weight, fat ratio (%), fat free mass (lean body mass), bone mass and hydration (body water, in kg) map directly, and so do systolic and diastolic from a Withings blood pressure monitor. Hydration is disabled by default; enable that entity first.

Usually you don't need this route. The Withings app writes to Health Connect itself, and it also links to Samsung Health directly; with this route on top, a weighing can land twice. Use it when you keep the Withings app away from Health Connect. Readings come through the Withings cloud, so their time is when Home Assistant fetched them.

### A cloud account: SmartScaleConnect

[SmartScaleConnect](https://github.com/AlexxIT/SmartScaleConnect) copies weighings out of Mi Fitness, Zepp Life, Xiaomi Home, Garmin and a few others. It sends them to Home Assistant through a webhook automation that fills `input_number` helpers; give each helper its unit (kg, %), map them, and build the [water in kg](#water-in-kg) template on its water percentage. Readings arrive when it runs, not when you weighed, and Health Connect records them as entered by hand. The project has been quiet since December 2025.

## Templates

A trigger-based template updates only when its trigger fires, which is what keeps one weighing one reading. Where the block goes:

- When `configuration.yaml` has no `template:` line, add one and paste the block from `- triggers:` down under it.
- When it already has `template:`, paste the block from `- triggers:` down under that line.
- When it says `template: !include templates.yaml`, paste the block from `- triggers:` down in `templates.yaml`.

Replace the placeholders in angle brackets with your own entity IDs and topic, and reload under **Developer tools > YAML > Template entities**. The [template documentation](https://www.home-assistant.io/integrations/template/) has the details. The templates assume a weight in kg.

### Water in kg

For bodymiscale and SmartScaleConnect, which give water as a percentage of body weight:

```yaml
template:
  - triggers:
      - trigger: state
        entity_id:
          - sensor.<person>_weight
          - sensor.<person>_water
          - sensor.<person>_last_measurement
        not_to: [unknown, unavailable]
    conditions:
      - condition: template
        value_template: >
          {{ states('sensor.<person>_water') | is_number
             and states('sensor.<person>_weight') | is_number }}
    sensor:
      - name: "Body water mass"
        unique_id: recipe_body_water_mass
        device_class: weight
        state_class: measurement
        unit_of_measurement: kg
        state: >
          {{ (states('sensor.<person>_weight') | float
              * states('sensor.<person>_water') | float / 100) | round(2) }}
```

It fires on the weight, the water and bodymiscale's Last measurement, so a weighing whose values happen to equal the last one still gets its water mass. When the weight arrives a moment before the water, the second update is a correction: with **Measured at** mapped to bodymiscale's Last measurement it replaces the first, so Health Connect keeps one record.

SmartScaleConnect has no measurement time to map, so there two updates would be two readings. Trigger on the water helper alone (keep only it in `entity_id`), and set the weight helper before the water helper in its webhook automation.

### ble-scale-sync, one weighing at a time

ble-scale-sync publishes every weighing as one JSON message on `scale/body-composition/<slug>`, where `<slug>` is the user's slug (the add-on makes it from the user's name). The `state_topic` of any of its Discovery sensors shows the exact topic. Set up the MQTT integration before you add this template, or its trigger can't subscribe until the next restart. One trigger on that topic gives every type from the same weighing:

```yaml
template:
  - triggers:
      - trigger: mqtt
        topic: scale/body-composition/<slug>
    conditions:
      - condition: template
        value_template: >
          {{ trigger.payload_json is defined
             and (trigger.payload_json.weight | default(0) | float(0)) > 0 }}
    sensor:
      - name: "Scale weight"
        unique_id: recipe_scale_weight
        device_class: weight
        state_class: measurement
        unit_of_measurement: kg
        state: "{{ trigger.payload_json.weight }}"
      - name: "Scale body fat"
        unique_id: recipe_scale_body_fat
        state_class: measurement
        unit_of_measurement: "%"
        state: "{{ trigger.payload_json.bodyFatPercent | default(none) }}"
      - name: "Scale bone mass"
        unique_id: recipe_scale_bone_mass
        device_class: weight
        state_class: measurement
        unit_of_measurement: kg
        state: "{{ trigger.payload_json.boneMass | default(none) }}"
      - name: "Scale body water mass"
        unique_id: recipe_scale_body_water_mass
        device_class: weight
        state_class: measurement
        unit_of_measurement: kg
        state: >
          {% set water = trigger.payload_json.waterPercent | default(none) %}
          {{ (trigger.payload_json.weight * water / 100) | round(2) if water is number else none }}
      - name: "Scale lean body mass"
        unique_id: recipe_scale_lean_body_mass
        device_class: weight
        state_class: measurement
        unit_of_measurement: kg
        state: >
          {% set fat = trigger.payload_json.bodyFatPercent | default(none) %}
          {{ (trigger.payload_json.weight * (1 - fat / 100)) | round(2) if fat is number else none }}
```

Lean body mass here is weight minus fat mass, which is what Health Connect's lean body mass means. A value missing from a message leaves its sensor unknown, which the integration skips, until the next weighing that has it. Leave out the sensors your scale doesn't measure. With several people on one scale, use one block per person, each with its own topic, names and `unique_id`s.

## Map it and switch it on

1. In Home Assistant, open **Settings > Devices & services > Life Dashboard**, choose **Configure** for the phone, and pick one entity per type. Each type also has an optional **Measured at** slot for a sensor that holds when the weighing happened.
2. In the app, on the **Health** tab, open **Receive**, switch it on and switch on the types you mapped. Each type asks for its own Health Connect write permission.
3. Step on the scale, and tap **Sync Now** or wait for the next sync. The **Logs** tab shows a row "Health · from Home Assistant" with every reading and whether it was written.

[Receiving measurements from Home Assistant](../usage.md#receiving-measurements-from-home-assistant) has the app side in full, and the integration's [Sending measurements to the phone](https://github.com/owen282000/life-dashboard-ha#sending-measurements-to-the-phone) covers the Home Assistant side.

## What each type accepts

| Type | The entity must give | Range |
|---|---|---|
| Weight | A mass (kg, g, lb, st, oz) | 1 to 500 kg |
| Body fat | A percentage | 1 to 80 % |
| Lean body mass | A mass | 1 to 300 kg. Not the same as muscle mass |
| Bone mass | A mass | 0.1 to 30 kg |
| Body water mass | A mass | 1 to 300 kg. Most scales give a percentage, so this needs [a template](#water-in-kg) |
| Height | A length (m, cm, ft, in) | 0.3 to 2.8 m |
| Blood pressure | Two entities, systolic and diastolic, in mmHg or kPa | Systolic 30 to 300, diastolic 10 to 250 mmHg, and below the systolic |

Units are converted to what Health Connect wants; a percentage where a mass is expected is refused when you save, not guessed at. A value outside the range, or exactly 0, is skipped. BMI, muscle mass, visceral fat and metabolic age have no Health Connect record, so they stay in Home Assistant.

**The time of the weighing.** A sensor in the **Measured at** slot has to be a timestamp sensor (device class `timestamp`), and it only counts when it changed within 90 seconds of the value. Without such a sensor, or when it didn't change in time, the reading gets the moment its value changed in Home Assistant. For a Bluetooth scale that is the moment you stood on it. For a cloud route it is when the cloud was polled, which can be hours later.

**One weighing, one reading.** The same value reported again within ten minutes is the same measurement, and every repeat starts those ten minutes over, so a cloud integration that keeps reporting an unchanged value stays one reading. The same weight the next morning is a new one.

**How it is labeled.** An entity with `device_class: weight` is recorded in Health Connect as a reading from a scale; an `input_number` helper as entered by hand.

**Where the readings are kept.** Readings that wait for the phone sit in Home Assistant's `.storage/life_dashboard.<entry>.writeback`, which goes into Home Assistant backups and is deleted when you remove the phone from the integration. Template sensors with `state_class: measurement` also keep your weight and body composition in Home Assistant's long-term statistics, with no time limit. Over MQTT on port 1883 without TLS, every weighing is readable on your network by any client allowed to subscribe to its topic.

## What shows up in the other apps

The app and the integration can only promise that a reading reaches Health Connect; whether another app shows it is up to that app. Samsung Health and Google Health show weight and body fat from Health Connect; lean body mass, bone mass and body water usually don't appear, and Google Health doesn't read blood pressure. [features.md](../features.md#receiving-from-home-assistant) keeps the dated table, and [A measurement from Home Assistant is not in Samsung Health](../usage.md#a-measurement-from-home-assistant-is-not-in-samsung-health) says what to check when one is missing.

## When something is off

- **Nothing arrives.** Check the **Logs** tab for the row from Home Assistant. No row means the reading hasn't reached the phone yet, or Home Assistant skipped it: a wrong unit is a warning in the Home Assistant log, a value out of range shows only with debug logging on for Life Dashboard. The type also has to be on under **Receive**.
- **The same weighing twice:** [Weight appears twice](../usage.md#weight-appears-twice).
- **The wrong time:** [Yesterday's weigh-in shows up today](../usage.md#yesterdays-weigh-in-shows-up-today).
- **Readings from before you set this up.** The integration's **Send history to phone** button queues up to 30 days, as far back as the recorder keeps states (10 days by default); the `life_dashboard.queue_history` action goes back up to 90 days. Readings older than 30 days also need **Accept older measurements** on in the app.
