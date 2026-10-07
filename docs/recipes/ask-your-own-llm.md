# Ask your own LLM about your health data

"How did my sleep change since I started running?" is a question a language model can answer, if it can read your history. This page shows three ways to let a model on your own hardware read that history, through a read-only connection to the place this app already sends your data.

The app itself never talks to a model. It does what it always does: push your Health Connect data to a receiver you run. The receiver side offers a read-only [MCP](https://modelcontextprotocol.io) server, the standard way for a model to call tools, and the model asks that server questions.

```
phone  ->  Home Assistant, life-dashboard-stack or your own Postgres  ->  read-only MCP server  ->  your model
```

If you send to the Life Dashboard integration, use [Route 1](#route-1-home-assistant); if you run life-dashboard-stack, use [Route 2](#route-2-life-dashboard-stack); if n8n or a receiver of your own already writes to a Postgres you run, use [Route 3](#route-3-your-own-postgres). None of this is needed to use the app. Route 1 was checked on October 2, 2026. Route 2 was checked on October 7, 2026 against life-dashboard-stack as of October 5, with DBHub 1.4.0 and its tools called over MCP, without a model in the loop. These tools move fast, so check their own docs for the current version.

## Before you start

- **A model that runs locally**, with tool support. [Ollama](https://ollama.com) runs them, on any machine on your network (set `OLLAMA_HOST=0.0.0.0` when the client is on another one); models such as `qwen3` (8B or 14B) or `gpt-oss:20b` handle tool calls. Pick one without a `-cloud` tag: those run on Ollama's servers, not yours. Rough guide: an 8B model wants 16 GB of memory and runs slowly without a graphics card, 20 to 30B wants a 24 GB graphics card or a Mac with 32 GB or more. A NAS without a graphics card is too slow.
- **A chat client that speaks MCP**, unless you use Home Assistant's own assistant: [Open WebUI](https://docs.openwebui.com/features/extensibility/mcp/) (connects over streamable HTTP) or [LM Studio](https://lmstudio.ai/docs/app/mcp). Ollama itself has no MCP client.
- **History to ask about.** A backfill in the app (**Health** tab, **Backfill**) sends up to a year. Data from more than 30 days before you first gave the app access needs Health Connect's history access, which the backfill dialog asks for.

## Route 1: Home Assistant

With the [Life Dashboard integration](https://github.com/owen282000/life-dashboard-ha), the history lives in Home Assistant's **long-term statistics**, the summaries behind the Statistics graph card that Home Assistant keeps forever: daily totals for steps, distance, calories, sleep, exercise, mindfulness, hydration and screen time on their own date, and heart rate, weight and the other measurements as hourly mean, minimum and maximum. They aren't entities but statistics with IDs like `life_dashboard:<entry id>_steps`; **Developer tools > Statistics** lists them.

Which MCP server you use decides whether a model can read them:

- **Home Assistant's built-in [Model Context Protocol Server](https://www.home-assistant.io/integrations/mcp_server/)** gives a model the current state of the entities you [exposed to Assist](https://www.home-assistant.io/voice_control/voice_remote_expose_devices/). It has no tool for history or statistics, so it can answer "how many steps today", not "how did this month compare to the last".
- **[ha-mcp](https://github.com/homeassistant-ai/ha-mcp)**, a community project, has a history tool that reads long-term statistics by day, week or month. That is the one for this.

**Do not raise `purge_keep_days`** to give a model more history. The recorder keeps state history for 10 days by default, and keeping more makes the database large and slow; the integration's daily figures are in the long-term statistics regardless.

### Set it up

1. In HACS, add `homeassistant-ai/ha-mcp-integration` as a custom repository and install it; it needs Home Assistant 2026.8 or later and runs inside it. Restart Home Assistant, then add it under **Settings > Devices & services > Add integration > HA-MCP Custom Component** and choose **HA-MCP Server**. Its [README](https://github.com/homeassistant-ai/ha-mcp) shows the settings below.
2. Lock it down:
   - In its **HA-MCP** panel in the sidebar, switch on **Read Only Mode**. This is the setting that covers every way in, Home Assistant's own assistant included.
   - Under **Configure** on the integration, turn off **Remote access via webhook** and set **Network access** to `127.0.0.1`, unless a client on another machine needs it. A webhook that stays on is reachable wherever Home Assistant is, through Home Assistant Cloud or a reverse proxy; then set its authentication to `ha_auth`.
   - Know what it can see. ha-mcp works with an administrator's access. Its **Entity Visibility** filter, in the same panel, limits entities, but the integration's statistics aren't entities: only an ID listed under its denied IDs is hidden.
3. Pick how the model talks to it:
   - **Inside Home Assistant, fully local:** ha-mcp offers its tools to Home Assistant's own assistant. Add the [Ollama](https://www.home-assistant.io/integrations/ollama/) integration, create a conversation agent with a tool-capable model, and under **Control Home Assistant** choose ha-mcp. Everyone who can talk to that agent can ask about your health data with ha-mcp's access, including through a voice satellite or another household member's account: give it its own agent, outside shared voice pipelines. A question asked by voice also goes through the speech engine you chose, which with Home Assistant Cloud isn't local.
   - **From a chat client:** add ha-mcp's URL, which it shows after setup, in Open WebUI (**Admin settings > Integrations > External tool servers**) or in LM Studio's `mcp.json`. Treat that URL as an administrator password. Read-only, ha-mcp still offers around 40 tools, far more than a small model can keep in mind; switch on its tool search in the **HA-MCP** panel, which offers them on demand.

ha-mcp has no tool that lists statistic IDs, so give the model the ID. With `change`, the history tool returns how much a daily total added per period:

> Use ha_get_history with source statistics, statistic types change, period month and start_time 2026-01-01 for life_dashboard:01k6..._steps, and tell me my average daily step count per month this year.

Without a start time the tool looks back 30 days.

## Route 2: life-dashboard-stack

[life-dashboard-stack](https://github.com/owen282000/life-dashboard-stack) stores the records the app sends in Postgres, in one table, `records`, with the record type (`steps`, `heart_rate`, `sleep`, `weight`, `daily_totals` and so on), its time, and the record itself as JSON in `data`. It applies the deletions the app reports, and a late payload can't overwrite a newer one. On top of that, its `dash` schema has views that turn records into days, nights, hours and workouts in your time zone: `dash.activity_days`, `dash.sleep_nights`, `dash.workouts`, `dash.measurement_days` and more, all listed in its [views.md](https://github.com/owen282000/life-dashboard-stack/blob/main/docs/views.md). With those, a model doesn't have to write SQL against nested JSON, which small models often get wrong.

The stack keeps series sent per time window in a separate `buckets` table, but only its heart rate views read them so far. Leave **Data Resolution** on the **Health** tab at **Every record**, the default, for the types you want to ask about.

**1. The read-only role.** The stack already has one: `grafana_ro`, the user Grafana connects with. It can read `records`, `buckets` and the `dash` views and nothing else, not the payload ledger. It's read-only by default and its queries stop after 30 seconds. The receiver drops and rebuilds `dash` at every start and grants `grafana_ro` access again right after, so a role of your own would lose its grants on the views; this one keeps them. Its password is `GRAFANA_DB_PASSWORD` in `.env`. Set it to letters and digits, since it goes into a connection URL below, and run `docker compose up -d` after you change it, so the receiver and Grafana pick it up.

Even with the read-only default switched off by a client, a write fails on permissions. The role can still see the catalog (table names, role names, settings), which holds no health data.

**2. An MCP server.** [DBHub](https://github.com/bytebase/dbhub) is maintained, enforces read-only per tool, and serves streamable HTTP. Add it next to the stack in `docker-compose.override.yml`, so the stack's own file stays as it is:

```yaml
services:
  dbhub:
    image: bytebase/dbhub:1.4.0
    init: true
    command: ["--transport", "http", "--port", "8080", "--config", "/config/dbhub.toml"]
    environment:
      GRAFANA_DB_PASSWORD: ${GRAFANA_DB_PASSWORD:?set it in .env}
    volumes:
      - ./dbhub.toml:/config/dbhub.toml:ro
    ports:
      - "127.0.0.1:8081:8080"
    depends_on:
      receiver:
        condition: service_healthy
```

It waits for the receiver, because the receiver sets up `grafana_ro` and the views when it starts. And `dbhub.toml` beside it. Next to a general read-only query tool it defines three ready-made questions, which small models get right far more often than SQL they write themselves. `activity_days` reads the `daily_totals` the app computes with Health Connect, which count a walk once even when both your phone and your watch recorded it; adding up the raw `steps` records would count it twice. `workouts` lists a workout once when the watch and a phone app both recorded it.

```toml
[[sources]]
id = "lifedash"
dsn = "postgres://grafana_ro:${GRAFANA_DB_PASSWORD}@db:5432/lifedash?sslmode=disable"
query_timeout = 15

[[tools]]
name = "execute_sql"
source = "lifedash"
readonly = true
max_rows = 500

[[tools]]
name = "activity_days"
description = "Steps, distance (meters) and calories (kcal) per day, counted once even when phone and watch both recorded them. One row per phone (install) and day. Dates YYYY-MM-DD, end exclusive. complete is false for today, a running total."
source = "lifedash"
readonly = true
statement = "SELECT day, install, steps, distance_m, active_kcal, total_kcal, complete FROM dash.activity_days WHERE day >= $1::date AND day < $2::date ORDER BY install, day"

[[tools.parameters]]
name = "start"
type = "string"
description = "First day, YYYY-MM-DD"

[[tools.parameters]]
name = "end"
type = "string"
description = "The day after the last day, YYYY-MM-DD"

[[tools]]
name = "sleep_nights"
description = "One night of sleep per phone (install), on the date you woke up. Hours asleep, in bed, deep, light, REM and awake (NULL without stages), bedtime and wake time, and the overnight low heart rate. Dates YYYY-MM-DD, end exclusive."
source = "lifedash"
readonly = true
statement = "SELECT day, install, start_time, end_time, asleep_h, in_bed_h, deep_h, light_h, rem_h, awake_h, hr_low FROM dash.sleep_nights WHERE day >= $1::date AND day < $2::date ORDER BY install, day"

[[tools.parameters]]
name = "start"
type = "string"
description = "First wake date, YYYY-MM-DD"

[[tools.parameters]]
name = "end"
type = "string"
description = "The day after the last wake date, YYYY-MM-DD"

[[tools]]
name = "workouts"
description = "Every workout with its activity (such as running or walking) and minutes, counted once when watch and phone both recorded it. Dates YYYY-MM-DD, end exclusive."
source = "lifedash"
readonly = true
statement = "SELECT day, install, time AS start_time, activity, minutes FROM dash.workouts WHERE day >= $1::date AND day < $2::date ORDER BY time"

[[tools.parameters]]
name = "start"
type = "string"
description = "First day, YYYY-MM-DD"

[[tools.parameters]]
name = "end"
type = "string"
description = "The day after the last day, YYYY-MM-DD"
```

Start it with `docker compose up -d dbhub`. Days in these views are local days in the stack's `TZ`; times come back in UTC, marked `+00`.

**3. Connect the client.** Add `http://127.0.0.1:8081/mcp` in Open WebUI or LM Studio on the same machine. The port is bound to the machine itself, and DBHub only responds to the host names it knows:

- **Open WebUI in Docker:** `127.0.0.1` is its own container there. On Docker Desktop, use `http://host.docker.internal:8081/mcp` and add `--allowed-hosts host.docker.internal` to DBHub's command; clients on the machine itself keep working. On Linux, put Open WebUI on the stack's network instead, use `http://dbhub:8080/mcp` and add `--allowed-hosts dbhub`.
- **A client on another machine:** bind the port to the machine's LAN address, add that host name with `--allowed-hosts`, set `DBHUB_AUTH_TOKEN` and give the client that token. Over plain HTTP the token and every record cross your network readable, so put TLS in front for anything beyond your own network.

Then ask: "Using `sleep_nights` and `workouts`, how did my deep sleep compare in weeks with three or more runs against weeks with fewer?" The stack's [views.md](https://github.com/owen282000/life-dashboard-stack/blob/main/docs/views.md) lists every view and column, and [webhook.md](../webhook.md) what every record type holds, for questions about a field by name.

The stack's Grafana (`admin` / `lifedash`, on all interfaces), its database (`lifedash` / `lifedash`) and `grafana_ro` (`lifedash-ro`) start with default passwords and read the same data, so change them before you add another way in. Change Grafana's password in its own settings, since `GRAFANA_PASSWORD` only counts on its first start, and the database password together with the receiver's `DATABASE_URL`.

## Route 3: your own Postgres

If n8n or a receiver of your own already stores the payloads in a Postgres you run, the same pattern works there. Three things in how you store them decide whether a model's answers hold up:

- Records keyed on `uuid`, in a `text` column. Heart rate and skin temperature samples carry `#<epoch milliseconds>` after the record's ID, so it isn't a Postgres `uuid`. A record sent again then replaces itself instead of counting twice.
- `daily_totals` as one row per date, replaced only by a payload with a higher `sequence`. These are the totals to ask about for steps, distance and calories.
- The deletions in `deleted_records` applied, by `type` and `uuid`, and for heart rate and skin temperature every sample whose `uuid` starts with that `uuid` and `#`. Otherwise a record you deleted on the phone is still there to read.

[webhook.md](../webhook.md#deduplication-and-ordering) has the rules, including how `sequence` settles deletions and daily totals when a payload arrives late. Then:

**1. A read-only role.** Run this as the owner of your tables, with your own table names in place of `records` and `daily_totals` and your database's name in place of `health`. Keep the password to letters and digits, since it goes into a connection URL.

```sql
CREATE ROLE llm_ro LOGIN PASSWORD 'change-me';
ALTER ROLE llm_ro SET default_transaction_read_only = on;
ALTER ROLE llm_ro SET statement_timeout = '30s';
GRANT CONNECT ON DATABASE health TO llm_ro;
GRANT USAGE ON SCHEMA public TO llm_ro;
GRANT SELECT ON records, daily_totals TO llm_ro;
```

Grant `SELECT` on every view you add later as well, and `USAGE` on its schema if that isn't `public`. Leave out anything that holds raw payloads or secrets.

**2. An MCP server.** Route 2's Compose service waits for the stack's receiver and needs the stack's `.env`, so it doesn't start on its own. Use this `docker-compose.yml` in a folder of its own instead, with `LLM_RO_PASSWORD=` and the role's password in a `.env` beside it:

```yaml
services:
  dbhub:
    image: bytebase/dbhub:1.4.0
    init: true
    command: ["--transport", "http", "--port", "8080", "--config", "/config/dbhub.toml"]
    env_file: .env
    volumes:
      - ./dbhub.toml:/config/dbhub.toml:ro
    ports:
      - "127.0.0.1:8081:8080"
```

And `dbhub.toml`, with the host name of your database as the container sees it. `localhost` is the container itself: on Docker Desktop use `host.docker.internal`, on Linux the server's address on your network or a Docker network the two share.

```toml
[[sources]]
id = "health"
dsn = "postgres://llm_ro:${LLM_RO_PASSWORD}@db.example.lan:5432/health?sslmode=disable"
query_timeout = 15

[[tools]]
name = "execute_sql"
source = "health"
readonly = true
max_rows = 500
```

Start it with `docker compose up -d`, and add ready-made tools against your own tables in the same form as Route 2's.

**3. Views for days and nights.** A model does far better with a view that has one row per night than with sleep sessions and stages as JSON. The stack's [views.sql](https://github.com/owen282000/life-dashboard-stack/blob/main/receiver/views.sql) is a template: `dash.sleep_nights` turns sleep sessions into nights on the date you woke up, `dash.activity_days` reads `daily_totals`, and `dash.workouts` counts a workout once when watch and phone both recorded it. It reads the stack's `records` table, so adapt the table and column names to yours and keep the helper functions at the top that the views call. Replace `__TZ__` with your time zone in quotes, such as `'Europe/Amsterdam'`, and leave out the `DROP SCHEMA dash CASCADE` line, which would also drop anything else you keep in `dash`.

**4. Connect the client** as in Route 2.

life-dashboard-stack's README doesn't cover running only its receiver against a Postgres you already run. The receiver does take its database from `DATABASE_URL`, and at every start it runs its migrations, rebuilds the `dash` schema and sets up the `grafana_ro` role, so if you try that, give it a database of its own and a user that may create roles.

## With a cloud model

Nothing above stops you from pointing a hosted model at the same server. Know what goes where: your questions, every query result the model asks for (up to 500 rows a call, as often as it likes) and its answers go to the provider and are kept under its retention and training settings, which you should check. The server itself stays at home. A connector in a web app also needs it reachable from the internet, so the provider's servers can call it: on the stack that means a token and TLS, on ha-mcp an administrator URL on the internet. A desktop client that reaches the server on your own network avoids that exposure, not the upload.

Hosted assistants that offer health features of their own decide for themselves which countries and data sources they support. This route works anywhere and covers the history you backfilled.

## What a model can and cannot do here

- It reads. With a read-only role, or ha-mcp's **Read Only Mode**, it can't change a setting, delete a record or switch a device.
- It's only as good as the data. Types you didn't turn on in the app, or days the phone didn't sync, aren't there to read.
- It can't read what your watch's app keeps to itself, and that decides which questions it can answer. Samsung Health doesn't share HRV, resting heart rate or respiratory rate with Health Connect, and Garmin Connect writes no HRV and no resting heart rate to Health Connect at all. The [brand guides](../brands/README.md) list what each app shares.
- It isn't a doctor. A model that sums your steps correctly can still draw a wrong conclusion from them.
