# Your alarm and your night, together

The Home Assistant companion app knows when your alarm goes off; this app knows how you slept. Together they answer three questions: when should I go to bed for the sleep I want, how did my nights go this week, and do I wake up before my alarm. This page has the Home Assistant configuration for all three. Checked on October 2, 2026, with Home Assistant 2026.9.

This recipe is Android-only: the companion app for iPhone has no next alarm sensor.

## What you need

- **The Home Assistant companion app** on the phone, with its **Next alarm** sensor switched on: in the companion app, **Settings > Companion app > Manage sensors > Next alarm**. It is off by default. Under **Allow list** in the same place, pick your clock app, so a calendar or a reminder app can't move the alarm.
- **Life Dashboard Companion** with the [Life Dashboard integration](https://github.com/owen282000/life-dashboard-ha) and **Sleep** switched on: [Phone to Home Assistant](../usage.md#with-the-integration).
- **A fixed sync time in the morning**, such as 09:00, so last night is in Home Assistant at a time you know (**Health** tab, **Sync Schedule**). A watch app writes the night to Health Connect when it syncs after you wake up, not at the moment you wake.

The YAML below uses three placeholders. Replace each with the ID from your own Home Assistant:

- `sensor.<phone>_next_alarm`: the companion app's next alarm, named after the companion app's device.
- `sensor.<phone>_last_sleep_duration`: this app's last sleep, named after the Life Dashboard device. The two devices can have different names, so look up both IDs under **Settings > Devices & services > Entities**.
- `notify.mobile_app_<phone>`: the companion app's notification action for that phone.

**With MQTT instead of the integration,** use the MQTT sensor **Last Sleep Duration** of the Life Dashboard Companion device; its entity ID starts with `sensor.life_dashboard_companion_`. It is in minutes and carries the same `measured_at` attribute, so the templates and the reminder work unchanged. The week of sleep in part 2 needs the integration's statistics.

**On a Samsung phone,** Samsung's Routines and Sleep mode set their own times through the same alarm system, under the same package as the Samsung Clock, so the allow list can't tell them apart and the "next alarm" can be a routine. Use Google Clock and allow only that app to avoid this.

## What the sleep number is

The integration's **Last sleep duration** is the whole sleep session as your tracker recorded it, from when it saw you fall asleep to when it saw you wake, with time awake in between included. Its `measured_at` attribute is the end of the session: when you woke. It isn't time asleep, and it isn't time in bed either, because the session starts when the tracker notices sleep, not when you lie down. Time asleep, the session minus its awake stages, isn't in Home Assistant today.

In the history, each day holds every session that **ended** that day, naps included: the night from Thursday to Friday is Friday's bar.

## The configuration

There are three parts: a helper for your sleep goal, four template sensors, and the reminder automation.

### The sleep goal helper

In Home Assistant, go to **Settings > Devices & services > Helpers > Create helper > Number** and fill in:

- Name: `Sleep goal`
- Minimum `5`, maximum `10`, step size `0.25`
- Unit of measurement: `h`

It gets the ID `input_number.sleep_goal`. Set it to the hours of sleep you want.

### The template sensors

Two of these sensors are trigger-based, which the template helper in the UI can't make, so all four go in YAML:

- When `configuration.yaml` has no `template:` line, paste the block below at the end of it.
- When it already has `template:` with entries under it, add everything from the first `- sensor:` on under that line.
- When it says `template: !include templates.yaml`, put everything from the first `- sensor:` on in `templates.yaml`.

Then reload under **Developer tools > YAML > Template entities**, or restart Home Assistant.

```yaml
template:
  # When to be in bed: the next alarm minus your sleep goal.
  - sensor:
      - name: Bedtime
        unique_id: recipe_bedtime
        device_class: timestamp
        state: >
          {% set alarm = states('sensor.<phone>_next_alarm') | as_datetime(none) %}
          {% if alarm is not none and alarm > now() %}
            {{ (alarm - timedelta(hours=states('input_number.sleep_goal') | float(8))).isoformat() }}
          {% else %}
            {{ none }}
          {% endif %}

  # The alarm meant for this morning, kept after it has gone off.
  - triggers:
      - trigger: state
        entity_id: sensor.<phone>_next_alarm
      - trigger: time_pattern
        minutes: "/15"
      - trigger: homeassistant
        event: start
    sensor:
      - name: Morning alarm
        unique_id: recipe_morning_alarm
        device_class: timestamp
        state: >
          {% set next = states('sensor.<phone>_next_alarm') | as_datetime(none) %}
          {% set kept = this.state | as_datetime(none) %}
          {% if next is not none and now() < next <= now() + timedelta(hours=12)
                and (kept is none or kept > now() or kept < now() - timedelta(hours=6)) %}
            {{ next.isoformat() }}
          {% elif kept is not none and kept > now() + timedelta(hours=2) %}
            {{ none }}
          {% elif kept is not none %}
            {{ kept.isoformat() }}
          {% else %}
            {{ none }}
          {% endif %}

  # Minutes between waking and the alarm: positive is before it, negative after.
  - triggers:
      - trigger: state
        entity_id: sensor.<phone>_last_sleep_duration
        attribute: measured_at
    sensor:
      - name: Awake before the alarm
        unique_id: recipe_awake_before_alarm
        unit_of_measurement: min
        state_class: measurement
        state: >
          {% set woke = state_attr('sensor.<phone>_last_sleep_duration', 'measured_at') | as_datetime(none) %}
          {% set alarm = states('sensor.morning_alarm') | as_datetime(none) %}
          {% if woke is not none and alarm is not none
                and alarm - timedelta(hours=4) < woke < alarm + timedelta(hours=2) %}
            {{ ((alarm - woke).total_seconds() / 60) | round(0) | int }}
          {% else %}
            {{ this.state if this.state | is_number else none }}
          {% endif %}

  # Optional: the goal in minutes, to show next to the nights in one graph.
  - sensor:
      - name: Sleep goal minutes
        unique_id: recipe_sleep_goal_minutes
        unit_of_measurement: min
        state_class: measurement
        state: "{{ (states('input_number.sleep_goal') | float(8) * 60) | round(0) }}"
```

### The automation

Go to **Settings > Automations & scenes > Create automation > Create new automation**, open the three-dot menu, choose **Edit in YAML**, replace what is there with this, and save:

```yaml
alias: Bedtime reminder
triggers:
  - trigger: time
    at:
      entity_id: sensor.bedtime
      offset: "-00:30:00"
conditions:
  - condition: template
    value_template: >
      {{ this.attributes.last_triggered is none
         or now() - this.attributes.last_triggered > timedelta(hours=12) }}
actions:
  - action: notify.mobile_app_<phone>
    data:
      title: Bed in half an hour
      message: >
        {% set alarm = states('sensor.<phone>_next_alarm') | as_datetime | as_local %}
        {% set bedtime = states('sensor.bedtime') | as_datetime | as_local %}
        {% set last = state_attr('sensor.<phone>_last_sleep_duration', 'measured_at') | as_datetime(none) %}
        Your alarm is at {{ alarm.strftime('%H:%M') }}. For {{ '%g' | format(states('input_number.sleep_goal') | float(8)) }} hours of sleep, be in bed at {{ bedtime.strftime('%H:%M') }}.
        {%- if last is not none and now() - last < timedelta(hours=20) %}
        Last night: {{ (states('sensor.<phone>_last_sleep_duration') | float(0) // 60) | int }} h {{ (states('sensor.<phone>_last_sleep_duration') | float(0) % 60) | int }} min.
        {%- endif %}
```

**All in one file.** With [packages](https://www.home-assistant.io/docs/configuration/packages/) switched on, the helper, the templates and the automation can live together in one package file, under the keys `input_number:`, `template:` and `automation:`. In a package the helper is YAML too: `sleep_goal:` with `name`, `min`, `max`, `step` and `unit_of_measurement`, and the automation goes in a list with an `id`.

## 1. A bedtime reminder

**Bedtime** is the next alarm minus your sleep goal, and the automation sends a notification half an hour before it: "Your alarm is at 07:00. For 8 hours of sleep, be in bed at 23:00. Last night: 6 h 52 min." The last line only appears when last night's sleep is already in Home Assistant.

- No alarm set: Bedtime is empty and nothing is sent.
- An alarm more than a day away: the reminder comes the evening before that alarm.
- An alarm set after its bedtime has passed: no reminder that night.
- One reminder per night, even if you move the alarm after it went out.

## 2. A week of sleep

A **Statistics graph** card with the integration's sleep history, one bar per night, next to your goal:

```yaml
type: statistics-graph
title: Sleep per night
chart_type: bar
period: day
days_to_show: 7
stat_types:
  - change
  - mean
entities:
  - life_dashboard:<entry>_sleep_minutes   # "<phone> sleep minutes" in the picker
  - sensor.sleep_goal_minutes              # optional; its history starts the day you add it
```

Pick the sleep statistic from the card editor's picker rather than typing the ID. A backfill in the app fills the weeks before you set this up.

## 3. Awake before the alarm

**Morning alarm** keeps the alarm that was meant for this morning, even after it has gone off, been snoozed or been switched off: the companion app's own sensor moves on to the next alarm the moment one rings. **Awake before the alarm** compares the end of last night's sleep with it: 25 means you woke 25 minutes before the alarm, -12 that you got up 12 minutes after it. An alarm that wakes you gives about 0.

Its limits:

- **The end of the session is the tracker's estimate.** Lying awake in bed can still count as sleep, which makes waking early look later than it was.
- **Last night has to arrive in time.** Morning alarm moves on to tomorrow's alarm about 12 hours before it, so around 19:00 for a 07:00 alarm. A night that reaches Home Assistant later than that isn't compared.
- **Only one alarm is visible:** the next one. With an alarm switched off more than two hours ahead, there is no morning alarm to compare with.

## Where it is kept

The sleep sensor and the two alarm sensors stay in Home Assistant's recorder for 10 days by default. The sleep history, **Awake before the alarm** and **Sleep goal minutes** go into long-term statistics, which Home Assistant keeps without a time limit. Your alarm times show your daily routine to everyone with access to Home Assistant and its backups; leave the three recipe sensors out of the recorder if you don't want that history.

## When something is off

- **The alarm time is wrong.** Check the allow list; on a Samsung phone, a routine or Sleep mode may be the "next alarm". A next alarm that is in the past means the allow list held back an update.
- **"Last night" is missing from the reminder.** The night hadn't been synced yet: move the morning sync time later, or check the **Logs** tab in the app.
- **An entity isn't found.** Look up the exact ID under **Settings > Devices & services > Entities**. The companion app's device and the Life Dashboard device can have different names.
