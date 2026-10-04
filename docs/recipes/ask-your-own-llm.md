# Ask your own LLM about your health data

"How did my sleep change since I started running?" is a question a language model can answer, if it can read your history. This page shows two ways to let a model on your own hardware read that history, through a read-only connection to the place this app already sends your data.

The app itself never talks to a model. It does what it always does: push your Health Connect data to a receiver you run. The receiver side offers a read-only [MCP](https://modelcontextprotocol.io) server, the standard way for a model to call tools, and the model asks that server questions.

```
phone  ->  Home Assistant or life-dashboard-stack  ->  read-only MCP server  ->  your model
```

If you send to the Life Dashboard integration, use [Route 1](#route-1-home-assistant); if you run life-dashboard-stack, use [Route 2](#route-2-life-dashboard-stack). None of this is needed to use the app. Checked on October 2, 2026; these tools move fast, so check their own docs for the current version.

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

[life-dashboard-stack](https://github.com/owen282000/life-dashboard-stack) stores the raw records the app sends in Postgres, in one table: `records`, with the record type (`steps`, `heart_rate`, `sleep`, `weight`, `daily_totals` and so on), its time, and the record itself as JSON in `data`. Bucketed series aren't stored, so leave **Data Resolution** at every record for the types you want to ask about. Deletions aren't applied either. A read-only database role and a read-only MCP server on top are enough.

**1. A role that can only read.** Save this as `readonly.sql`, replace `change-me` with a password of your own (letters and digits: it goes into a connection URL below), and set your time zone:

```sql
CREATE ROLE llm_reader LOGIN PASSWORD 'change-me' CONNECTION LIMIT 10;
GRANT CONNECT ON DATABASE lifedash TO llm_reader;
GRANT USAGE ON SCHEMA public TO llm_reader;
GRANT SELECT ON TABLE records TO llm_reader;
REVOKE TEMP ON DATABASE lifedash FROM PUBLIC;
ALTER ROLE llm_reader SET default_transaction_read_only = on;
ALTER ROLE llm_reader SET statement_timeout = '15s';
ALTER ROLE llm_reader SET timezone = 'Europe/Amsterdam';
```

```sh
docker compose exec -T db psql -U lifedash -d lifedash < readonly.sql
```

The role has SELECT on `records` and on no other table, so a write fails on permissions even if a client switches the read-only default off. It can still see the catalog (table names, role names, settings), which holds no health data. The timeout catches runaway queries, not a client that raises it again.

**2. An MCP server.** [DBHub](https://github.com/bytebase/dbhub) is maintained, enforces read-only per tool, and serves streamable HTTP. Add it next to the stack in `docker-compose.override.yml`, so the stack's own file stays as it is, and put `LLM_READER_PASSWORD=` with the same password in `.env`:

```yaml
services:
  dbhub:
    image: bytebase/dbhub:1.4.0
    init: true
    command: ["--transport", "http", "--port", "8080", "--config", "/config/dbhub.toml"]
    environment:
      LLM_READER_PASSWORD: ${LLM_READER_PASSWORD:?set it in .env}
    volumes:
      - ./dbhub.toml:/config/dbhub.toml:ro
    ports:
      - "127.0.0.1:8081:8080"
    depends_on:
      db:
        condition: service_healthy
```

And `dbhub.toml` beside it. Next to a general read-only query tool it defines one ready-made question, the daily totals, which small models get right far more often than SQL they write themselves. It reads the `daily_totals` the app computes with Health Connect, which count a walk once even when both your phone and your watch recorded it. Adding up the raw `steps` records would count it twice.

```toml
[[sources]]
id = "lifedash"
dsn = "postgres://llm_reader:${LLM_READER_PASSWORD}@db:5432/lifedash?sslmode=disable"
query_timeout = 15

[[tools]]
name = "execute_sql"
source = "lifedash"
readonly = true
max_rows = 500

[[tools]]
name = "day_totals"
description = "One value per day from the phone's deduplicated daily totals. field: steps, distance_meters, active_calories or total_calories. Dates YYYY-MM-DD, end exclusive."
source = "lifedash"
readonly = true
statement = "SELECT DISTINCT ON (data->>'date') (data->>'date')::date AS day, (data->>$1)::numeric AS total FROM records WHERE type = 'daily_totals' AND data->>'date' >= $2 AND data->>'date' < $3 ORDER BY data->>'date', received_at DESC"

[[tools.parameters]]
name = "field"
type = "string"
description = "steps, distance_meters, active_calories or total_calories"

[[tools.parameters]]
name = "start"
type = "string"
description = "First day, YYYY-MM-DD"

[[tools.parameters]]
name = "end"
type = "string"
description = "The day after the last day, YYYY-MM-DD"
```

Start it with `docker compose up -d dbhub`.

**3. Connect the client.** Add `http://127.0.0.1:8081/mcp` in Open WebUI or LM Studio on the same machine. The port is bound to the machine itself, and DBHub only responds to the host names it knows:

- **Open WebUI in Docker:** `127.0.0.1` is its own container there. On Docker Desktop, use `http://host.docker.internal:8081/mcp` and add `--allowed-hosts host.docker.internal` to DBHub's command; clients on the machine itself keep working. On Linux, put Open WebUI on the stack's network instead, use `http://dbhub:8080/mcp` and add `--allowed-hosts dbhub`.
- **A client on another machine:** bind the port to the machine's LAN address, add that host name with `--allowed-hosts`, set `DBHUB_AUTH_TOKEN` and give the client that token. Over plain HTTP the token and every record cross your network readable, so put TLS in front for anything beyond your own network.

Then ask: "Using `day_totals`, what was my average daily step count per month this year?" [webhook.md](../webhook.md) lists what every record type holds, for questions about a field by name.

The stack's Grafana (`admin` / `lifedash`, on all interfaces) and database (`lifedash` / `lifedash`) start with default passwords and read the same data, so change both before you add another way in. Change Grafana's password in its own settings, since `GRAFANA_PASSWORD` only counts on its first start, and the database password together with the receiver's `DATABASE_URL`.

## With a cloud model

Nothing above stops you from pointing a hosted model at the same server. Know what goes where: your questions, every query result the model asks for (up to 500 rows a call, as often as it likes) and its answers go to the provider and are kept under its retention and training settings, which you should check. The server itself stays at home. A connector in a web app also needs it reachable from the internet, so the provider's servers can call it: on the stack that means a token and TLS, on ha-mcp an administrator URL on the internet. A desktop client that reaches the server on your own network avoids that exposure, not the upload.

The large assistants have health features of their own: Claude's health connectors are a US-only beta, and ChatGPT Health isn't offered in the EU, the UK or Switzerland. This route works anywhere and covers the history you backfilled.

## What a model can and cannot do here

- It reads. With the read-only role, or ha-mcp's **Read Only Mode**, it can't change a setting, delete a record or switch a device.
- It's only as good as the data. Types you didn't turn on in the app, or days the phone didn't sync, aren't there to read.
- It isn't a doctor. A model that sums your steps correctly can still draw a wrong conclusion from them.
