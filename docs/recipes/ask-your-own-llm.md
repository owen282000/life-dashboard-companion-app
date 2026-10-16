# Ask your own LLM about your health data

"How did my sleep change since I started running?" is a question a language model can answer, if it can read your history. This page shows three ways to let a model on your own hardware read that history, through a read-only connection to the place this app already sends your data.

The app itself never talks to a model. It does what it always does: push your Health Connect data to a receiver you run. The receiver side offers a read-only [MCP](https://modelcontextprotocol.io) server, the standard way for a model to call tools, and the model asks that server questions.

```
phone  ->  Home Assistant, life-dashboard-stack or your own Postgres  ->  read-only MCP server  ->  your model
```

If you send to the Life Dashboard integration, use [Route 1](#route-1-home-assistant); if you run life-dashboard-stack, use [Route 2](#route-2-life-dashboard-stack); if n8n or a receiver of your own already writes to a Postgres you run, use [Route 3](#route-3-your-own-postgres). None of this is needed to use the app. Route 1 was checked on October 2, 2026. Route 2 follows life-dashboard-stack's own MCP documentation as of October 16, 2026, for version 1.0.0. DBHub 1.4.0, which Route 3 uses, was checked on October 7, 2026 against the stack's database, with its tools called over MCP and without a model in the loop. These tools move fast, so check their own docs for the current version.

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

[life-dashboard-stack](https://github.com/owen282000/life-dashboard-stack) stores the records the app sends in Postgres, in one table, `records`, with the record type (`steps`, `heart_rate`, `sleep`, `weight`, `daily_totals` and so on), its time, and the record itself as JSON in `data`. It applies the deletions the app reports, and a late payload can't overwrite a newer one. On top of that, its `dash` schema has views that turn records into days, nights, hours and workouts in your time zone, all listed in its [views.md](https://github.com/owen282000/life-dashboard-stack/blob/main/docs/views.md). The stack has an MCP server of its own that reads those views, so a model never has to write SQL against nested JSON, which small models often get wrong.

The stack keeps series sent per time window in a separate `buckets` table, but only its heart rate views read them so far. Leave **Data Resolution** on the **Health** tab at **Every record**, the default, for the types you want to ask about.

**1. Start the server.** It's an optional part of the stack, the Compose service `mcp` in the profile `mcp`, so a plain `docker compose up` leaves it out. Give it a token of its own in `.env`, at least 16 characters and not the same as `API_TOKEN`, since the server refuses to start without one:

```bash
echo "MCP_TOKEN=$(openssl rand -hex 32)" >> .env
docker compose --profile mcp up -d
```

If your `docker-compose.yml` has no `mcp` service yet, it's from a release before the server: download the file of the latest release again and keep your `.env`, as the stack's [Upgrading](https://github.com/owen282000/life-dashboard-stack/blob/main/README.md#upgrading) section describes.

Docker publishes the port on every network interface of the machine. To keep the server to the machine itself, set `MCP_PORT=127.0.0.1:8090` in `.env`. Add `COMPOSE_PROFILES=mcp` there too, so later commands such as `docker compose pull` include the server; without it, keep `--profile mcp` on them, or the server stays on the old version.

On Kubernetes, the stack's Helm chart runs the same server with `mcp.enabled: true`, with `MCP_TOKEN` in the release's Secret; its [kubernetes.md](https://github.com/owen282000/life-dashboard-stack/blob/main/docs/kubernetes.md#the-api-and-the-mcp-server) shows how to reach it.

**2. What the model can read.** The server reads the views as `dash_ro`, a database user that can only read the `dash` views: not the raw records, not the payload ledger. Its sessions are read-only and a query stops after 15 seconds. It has six tools, none that writes and none that takes SQL, and six tools with fixed parameters are far easier for a small model than SQL it writes itself:

- `list_phones`: the phones with their last sync and their first and last day with data, the time zone and today's date. The model should call it first.
- `daily_summary`: one row per phone and day with steps, distance, calories, sleep, exercise minutes and workouts, screen time, resting heart rate and weight.
- `sleep_nights`: one night per phone on the date you woke up, with bedtime, wake time, hours asleep and in bed, deep, light, REM and awake, and the overnight low heart rate.
- `workouts`: every workout with its activity, start, end and minutes.
- `trend`: one metric, such as `steps`, `sleep_h` or `exercise_min`, over the last 7 to 365 complete days (28 by default) against the same number of days before, with the mean, the lowest and highest day and the change in percent.
- `screen_time`: screen time on one day per phone with the top 10 apps. Android only.

`daily_summary`, `sleep_nights` and `workouts` take `from_date` and `to_date` as `YYYY-MM-DD`, both inclusive, for at most 92 days a call. Every tool takes an optional `phone`, the label from your webhook URL; without it the answer has a row per phone. Days are local days in the stack's `TZ`. Steps, distance and calories come from the `daily_totals` the app computes with Health Connect, which count a walk once even when both your phone and your watch recorded it, and a workout counts once when the watch and a phone app both recorded it. A wrong argument, such as a range over 92 days, comes back as an error the model can read and correct. The stack's [mcp.md](https://github.com/owen282000/life-dashboard-stack/blob/main/docs/mcp.md#the-tools) lists every parameter, and its [api.md](https://github.com/owen282000/life-dashboard-stack/blob/main/docs/api.md#metrics) every metric `trend` takes.

**3. Connect a client.** The server speaks MCP over Streamable HTTP at `http://127.0.0.1:8090/mcp` on the machine itself, and every request needs the header `Authorization: Bearer <MCP_TOKEN>`:

- **Open WebUI:** under **Admin settings > Integrations > External tool servers**, add a server of the type MCP (Streamable HTTP) with that URL and the token as bearer authentication, then switch the tools on in a chat with your model.
- **LM Studio:** add it to `mcp.json`:

  ```json
  {
    "mcpServers": {
      "life-dashboard": {
        "url": "http://127.0.0.1:8090/mcp",
        "headers": {"Authorization": "Bearer <your MCP_TOKEN>"}
      }
    }
  }
  ```

- **A client in Docker,** such as Open WebUI in a container: `127.0.0.1` is its own container there. On Docker Desktop, use `http://host.docker.internal:8090/mcp`. On Linux, attach the client's container to the stack's network (`<project>_default`) and use `http://mcp:8090/mcp`.
- **A client on another machine:** use this machine's address on your network, with the same token, and leave `MCP_PORT` on all interfaces. Over plain HTTP the token and every answer cross your network readable, so put TLS in front, or use a VPN, for anything beyond your own network.
- **Claude Desktop on the same machine** can start the server itself and talk to it over stdio, without the port or the token. Fetch the image once with `docker compose --profile mcp pull mcp`, then add this under **Settings > Developer > Edit Config**, with the folder that holds your `docker-compose.yml` and `.env` in place of the path, and restart Claude Desktop:

  ```json
  {
    "mcpServers": {
      "life-dashboard": {
        "command": "docker",
        "args": ["compose", "--project-directory", "/path/to/life-dashboard-stack", "--profile", "mcp",
                 "run", "--rm", "--no-deps", "-T", "mcp", "python", "server.py", "--stdio"]
      }
    }
  }
  ```

  Claude is a cloud model: the server runs on your computer, but the conversation, tool results included, goes to Anthropic. See [With a cloud model](#with-a-cloud-model). The stack's [mcp.md](https://github.com/owen282000/life-dashboard-stack/blob/main/docs/mcp.md#claude-desktop-on-the-same-machine) covers a stack started under another project name and Claude Desktop on another computer.

Then ask, with a direct question and a period, which small models handle best: "Use trend for sleep_h over 28 days, then workouts from the first day of its previous period to the last day of its period. Did I work out more in the last 28 days or in the 28 days before, and did I sleep more or less?" `trend` names both periods, and the 56 days fit in one `workouts` call.

The stack's Grafana (`admin` / `lifedash`, on all interfaces), its database (`lifedash` / `lifedash`), `grafana_ro` (`lifedash-ro`) and `dash_ro` (`lifedash-dash-ro`, `DASH_DB_PASSWORD` in `.env`) start with default passwords and read the same data, so change them before you add another way in. Change Grafana's password in its own settings, since `GRAFANA_PASSWORD` only counts on its first start, and the database password together with the receiver's `DATABASE_URL`.

## Route 3: your own Postgres

If n8n or a receiver of your own already stores the payloads in a Postgres you run, a general MCP server for Postgres can read it the same way. Three things in how you store them decide whether a model's answers hold up:

- Records keyed on `uuid`, in a `text` column. Heart rate and skin temperature samples carry `#<epoch milliseconds>` after the record's ID, so it isn't a Postgres `uuid`. A record sent again then replaces itself instead of counting twice.
- `daily_totals` as one row per date, replaced only by a payload with a higher `sequence`. These are the totals to ask about for steps, distance and calories.
- The deletions in `deleted_records` applied, by `type` and `uuid`, and for heart rate and skin temperature every sample whose `uuid` starts with that `uuid` and `#`. Otherwise a record you deleted on the phone is still there to read.

[webhook.md](../webhook.md#deduplication-and-ordering) has the rules, including how `sequence` settles deletions and daily totals when a payload arrives late, and the rest of that page what every record type holds. Then:

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

**2. Views for days and nights.** A model does far better with a view that has one row per night than with sleep sessions and stages as JSON. The stack's [views.sql](https://github.com/owen282000/life-dashboard-stack/blob/main/receiver/views.sql) is a template: `dash.sleep_nights` turns sleep sessions into nights on the date you woke up, `dash.activity_days` reads `daily_totals`, and `dash.workouts` counts a workout once when watch and phone both recorded it. It reads the stack's `records` table, so adapt the table and column names to yours and keep the helper functions at the top that the views call. Replace `__TZ__` with your time zone in quotes, such as `'Europe/Amsterdam'`, and leave out the `DROP SCHEMA dash CASCADE` line, which would also drop anything else you keep in `dash`. Then give `llm_ro` `USAGE` on `dash` and `SELECT` on the views.

**3. An MCP server.** [DBHub](https://github.com/bytebase/dbhub) is maintained, enforces read-only per tool, and serves streamable HTTP. Use this `docker-compose.yml` in a folder of its own, with `LLM_RO_PASSWORD=` and the role's password in a `.env` beside it:

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

And `dbhub.toml`, with the host name of your database as the container sees it. `localhost` is the container itself: on Docker Desktop use `host.docker.internal`, on Linux the server's address on your network or a Docker network the two share. Next to a general read-only query tool it defines three ready-made questions on the views from step 2, which small models get right far more often than SQL they write themselves. The columns are those of the stack's views; change them if yours differ.

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

[[tools]]
name = "activity_days"
description = "Steps, distance (meters) and calories (kcal) per day, counted once even when phone and watch both recorded them. One row per phone (install) and day. Dates YYYY-MM-DD, end exclusive. complete is false for today, a running total."
source = "health"
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
source = "health"
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
source = "health"
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

Start it with `docker compose up -d`.

**4. Connect the client.** Add `http://127.0.0.1:8081/mcp` in Open WebUI or LM Studio on the same machine. The port is bound to the machine itself, and DBHub only responds to the host names it knows:

- **Open WebUI in Docker:** `127.0.0.1` is its own container there. On Docker Desktop, use `http://host.docker.internal:8081/mcp` and add `--allowed-hosts host.docker.internal` to DBHub's command; clients on the machine itself keep working. On Linux, put Open WebUI on a Docker network with DBHub instead, use `http://dbhub:8080/mcp` and add `--allowed-hosts dbhub`.
- **A client on another machine:** bind the port to the machine's LAN address, add that host name with `--allowed-hosts`, set `DBHUB_AUTH_TOKEN` and give the client that token. Over plain HTTP the token and every record cross your network readable, so put TLS in front for anything beyond your own network.

Then ask: "Using `sleep_nights` and `workouts`, how did my deep sleep compare in weeks with three or more runs against weeks with fewer?"

life-dashboard-stack's README doesn't cover running only its receiver with Docker against a Postgres you already run. The receiver does take its database from `DATABASE_URL`, and at every start it runs its migrations, rebuilds the `dash` schema and sets up the `grafana_ro` and `dash_ro` roles, so if you try that, give it a database of its own and a user that may create roles. Its Helm chart has a mode for an external Postgres, described in its [kubernetes.md](https://github.com/owen282000/life-dashboard-stack/blob/main/docs/kubernetes.md#external).

## With a cloud model

Nothing above stops you from pointing a hosted model at the same server. The app talks to no model at all, and neither does the server: the client you connect decides where the conversation goes. Know what goes where: your questions, every tool result the model asks for, as often as it likes, and its answers go to the provider and are kept under its retention and training settings, which you should check. On the stack a result covers at most 92 days a call, and `trend` 7 to 365 days; DBHub's query tool returns up to 500 rows a call. The server itself stays at home, and that's true for Claude Desktop too: it starts the stack's server on your computer, but the conversation, tool results included, goes to Anthropic. A connector in a web app also needs the server reachable from the internet, so the provider's servers can call it: on the stack that means the server on the internet behind its token and TLS, which its docs don't cover, on ha-mcp an administrator URL on the internet. A desktop client that reaches the server on your own network avoids that exposure, not the upload.

Hosted assistants that offer health features of their own decide for themselves which countries and data sources they support. This route works anywhere and covers the history you backfilled.

## What a model can and cannot do here

- It reads. With the stack's server, which has no tool that writes, a read-only role, or ha-mcp's **Read Only Mode**, it can't change a setting, delete a record or switch a device.
- It's only as good as the data. Types you didn't turn on in the app, or days the phone didn't sync, aren't there to read.
- It can't read what your watch's app keeps to itself, and that decides which questions it can answer. Samsung Health doesn't share HRV, resting heart rate or respiratory rate with Health Connect, and Garmin Connect writes no HRV and no resting heart rate to Health Connect at all. The [brand guides](../brands/README.md) list what each app shares.
- It isn't a doctor. A model that sums your steps correctly can still draw a wrong conclusion from them.
