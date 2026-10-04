# Screen time limits in Home Assistant

The app sends your screen time per app to Home Assistant, so Home Assistant can tell you when a day gets long. This page has three automations: a notification when today's screen time passes a limit, one when a single app does, and a flag for phone use late at night. The YAML uses the `triggers:`, `conditions:` and `actions:` syntax of Home Assistant 2024.10 and later.

## What you need

- **Life Dashboard Companion** with Screen Time set up (usage access granted) and sending to the [Life Dashboard integration](https://github.com/owen282000/life-dashboard-ha) or to MQTT: [Phone to Home Assistant](../usage.md#phone-to-home-assistant).
- **A short Screen Time interval.** The sensors only update when the app syncs, so Home Assistant notices a limit at the first sync after you cross it. On the **Screen Time** tab, set **Sync Schedule** to **Every X minutes** and pick 15, the shortest Android allows.
- **The companion app**, for the notifications. `notify.mobile_app_<phone>` below is its notification action for your phone.

## The sensors

The YAML below uses the integration's entity IDs. `<phone>` is the device name as it appears in your entity IDs, so "Pixel 8" gives `sensor.pixel_8_screen_time_today`.

| Integration | Over MQTT | What it holds |
|---|---|---|
| `sensor.<phone>_screen_time_today` | **Screen Time Today** | Minutes on the phone today, with the attributes `date`, `app_count` and `top_apps` |
| `sensor.<phone>_screen_time_yesterday` | **Screen Time Yesterday** | Yesterday's final total, with the same attributes |
| `sensor.<phone>_most_used_app_today` | **Screen Time Top App Today** | The name of today's most used app, with the attributes `package` and `minutes` |
| `sensor.<phone>_<app>_screen_time` | Not over MQTT | One app's minutes today, such as `sensor.pixel_8_youtube_screen_time`, with the attributes `app`, `package`, `date` and `week_minutes`. One per app, created disabled (integration 0.9.0 or newer) |

Over MQTT the sensors sit on the Life Dashboard Companion device, and their entity IDs start with `sensor.life_dashboard_companion_`. Look up the exact IDs under **Settings > Devices & services > Entities** and use those in place of the integration's.

`top_apps` holds today's five most used apps with their minutes, as one line of text: `YouTube (42 min), Chrome (18 min), WhatsApp (12 min)`.

"Today" follows the day boundary you set in the app, on by default at 04:00: until 04:00, phone use still counts toward the day before.

The sensors only see the apps you send. An app left out under **Apps to send** never reaches Home Assistant, so it can't have a limit there. With such a filter on, the minutes are those of the apps that are sent, and the attribute `all_apps_minutes` holds the total of every app.

## 1. A daily limit

A notification when today's screen time passes three hours:

```yaml
alias: Screen time limit
triggers:
  - trigger: numeric_state
    entity_id: sensor.<phone>_screen_time_today
    above: 180
actions:
  - action: notify.mobile_app_<phone>
    data:
      title: Screen time
      message: >
        {% set minutes = states('sensor.<phone>_screen_time_today') | int(0) %}
        {{ minutes // 60 }} h {{ minutes % 60 }} min on the phone today.
        Most used: {{ state_attr('sensor.<phone>_screen_time_today', 'top_apps') }}.
```

Add it under **Settings > Automations & scenes > Create automation > Create new automation**: open the three-dot menu, choose **Edit in YAML**, replace what is there with the block above, and save. Do the same for the other automations on this page.

A `numeric_state` trigger fires when the value goes from below the limit to above it, so this sends one notification a day. When the next day starts, the sensor drops back to a few minutes, and the trigger is ready again.

- To change the limit from a dashboard, create a **Number** helper (**Settings > Devices & services > Helpers > Create helper**) and write its ID in place of the number: `above: input_number.screen_time_limit`.
- With an app filter on, a limit on every app's time uses the attribute instead: add `attribute: all_apps_minutes` under the trigger's `entity_id`.

## 2. A limit for one app

The integration gives every app a sensor of its own with its minutes today. They are created disabled, so enable the one you need first: open your phone's device under **Settings > Devices & services > Life Dashboard**, show the disabled entities, open the app's sensor and switch on **Enabled**. Home Assistant reloads the integration about 30 seconds later, and the sensor shows today's minutes.

A notification after an hour of YouTube:

```yaml
alias: YouTube limit
triggers:
  - trigger: numeric_state
    entity_id: sensor.<phone>_youtube_screen_time
    above: 60
actions:
  - action: notify.mobile_app_<phone>
    data:
      title: Screen time
      message: An hour of YouTube today.
```

- Like the daily limit, it fires once a day. The sensor reads 0 when the next day starts, also for an app you don't open that day, so the trigger is ready again.
- An app gets a sensor once it has 5 minutes over the days the last sync carried, and a phone gets 50 app sensors at most, the most used apps first. An app you stop using keeps its sensor, so the automation keeps working.
- The sensor belongs to the app's package, so it stays the same when the app is renamed.

### Over MQTT

MQTT has no sensor per app. There, `top_apps` on Screen Time Today lists today's five most used apps, and this template trigger reads one app's minutes from it:

```yaml
alias: YouTube limit
triggers:
  - trigger: template
    value_template: >
      {% set app = 'YouTube' %}
      {% set limit = 60 %}
      {% set apps = state_attr('sensor.<phone>_screen_time_today', 'top_apps') or '' %}
      {% set found = apps | regex_findall('(?:^|, )' ~ app ~ ' [(]([0-9]+) min[)]') %}
      {{ found | count > 0 and found[0] | int(0) >= limit }}
actions:
  - action: notify.mobile_app_<phone>
    data:
      title: Screen time
      message: An hour of YouTube today.
```

- Write the app's name exactly as it appears in `top_apps`. A name with characters that mean something in a regular expression, such as `+`, `.` or brackets, needs those in square brackets: `Disney[+]`.
- An app outside today's top five isn't in `top_apps`, so the trigger can't see it. An app over its limit is usually among the five.
- Like the daily limit, it fires once a day: the template turns false when the new day starts, and true again when the app passes the limit that day.
- Over MQTT, look up the entity ID of Screen Time Today under **Settings > Devices & services > Entities** and use it in place of `sensor.<phone>_screen_time_today`.

When the limit is for whichever app you use most, the **Most used app today** sensor is simpler:

```yaml
triggers:
  - trigger: numeric_state
    entity_id: sensor.<phone>_most_used_app_today
    attribute: minutes
    above: 90
```

Put `{{ states('sensor.<phone>_most_used_app_today') }}` in the message to show the app's name.

## 3. Late-night phone use

The day boundary is what makes this one work. With the boundary at 04:00, today's minutes keep counting after midnight instead of starting over, so the time on the phone since 23:00 is today's minutes now minus today's minutes at 23:00.

It takes a helper and two automations. First create a **Number** helper named `Screen time at bedtime`, with minimum `0`, maximum `1440` and unit `min`. It gets the ID `input_number.screen_time_at_bedtime`.

The first automation notes today's minutes at 23:00:

```yaml
alias: Note screen time at bedtime
triggers:
  - trigger: time
    at: "23:00:00"
actions:
  - action: input_number.set_value
    target:
      entity_id: input_number.screen_time_at_bedtime
    data:
      value: "{{ states('sensor.<phone>_screen_time_today') | int(0) }}"
```

The second sends a notification, once a night, when more than 20 minutes have been added between 23:00 and 04:00:

```yaml
alias: Late-night phone use
triggers:
  - trigger: state
    entity_id: sensor.<phone>_screen_time_today
conditions:
  - condition: time
    after: "23:00:00"
    before: "04:00:00"
  - condition: template
    value_template: >
      {{ states('sensor.<phone>_screen_time_today') | int(0)
         - states('input_number.screen_time_at_bedtime') | int(0) > 20 }}
  - condition: template
    value_template: >
      {{ this.attributes.last_triggered is none
         or now() - this.attributes.last_triggered > timedelta(hours=12) }}
actions:
  - action: notify.mobile_app_<phone>
    data:
      title: Still up?
      message: >
        {{ states('sensor.<phone>_screen_time_today') | int(0)
           - states('input_number.screen_time_at_bedtime') | int(0) }} minutes
        on the phone since 23:00.
```

- Keep `before:` the same as the day boundary in the app. If your boundary is 03:00, use `"03:00:00"`.
- With the day boundary switched off, today's minutes start over at midnight, and use after midnight isn't counted here.
- The first sync after 04:00 starts the new day, so the late night shows up in `sensor.<phone>_screen_time_yesterday`, and in the integration's history under the day before.
- To see these nights on a dashboard instead of on your phone, replace the notification with `input_boolean.turn_on` on a helper of your own, and turn it off in the morning.

## Where it is kept

Every sync updates these sensors, and Home Assistant's recorder keeps their history for 10 days by default, with the `top_apps` text. That includes the sensors per app you enabled; they add nothing to long-term statistics. The integration also keeps total screen time per day in long-term statistics, without a time limit. Everyone with access to Home Assistant and its backups can see which apps you used and when. Apps you leave out in the app never get there.
