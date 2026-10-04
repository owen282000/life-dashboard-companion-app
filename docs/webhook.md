# Webhook payload reference

Life Dashboard Companion sends Health Connect data and screen time to any URL that accepts a JSON POST. This page is what you need to build a receiver for it: the request and how to verify it, a working receiver in Node.js and in Python, every field per data type, and the rules for deletions, buckets, backfills and ordering. A machine-readable [JSON Schema](webhook-schema.json) (Draft 7) covers every body the app sends; validate your receiver against it. The [setup guide](usage.md#your-own-webhook) shows where to enter the URL, headers and secret in the app. To see a real payload before you write any code, run [webhook-receiver.py](../scripts/webhook-receiver.py) on a laptop, as described there.

If you would rather start from a working backend, [life-dashboard-stack](https://github.com/owen282000/life-dashboard-stack) is a Docker Compose setup with an HMAC-verifying receiver, Postgres and a provisioned Grafana dashboard.

## Contents

- [Quick start for receivers](#quick-start-for-receivers)
  - [The request](#the-request) - [Verifying the signature](#verifying-the-signature) - [Receiver in Node.js](#receiver-in-nodejs-express) - [Receiver in Python](#receiver-in-python-flask) - [n8n and Node-RED](#n8n-and-node-red)
  - [Responses, retries and timeouts](#responses-retries-and-timeouts) - [Payload size](#payload-size) - [Deduplication and ordering](#deduplication-and-ordering) - [Time and number formats](#time-and-number-formats)
- [Payload overview](#payload-overview)
- [Health Connect data types](#health-connect-data-types)
  - [Activity](#activity) - [Body](#body) - [Body composition](#body-composition) - [Vitals](#vitals) - [Sleep](#sleep)
  - [Nutrition](#nutrition) - [Mindfulness](#mindfulness) - [Cycle tracking](#cycle-tracking) - [Metabolic and fitness](#metabolic-and-fitness)
- [Screen Time payload](#screen-time-payload)
- [Test ping](#test-ping)
- [Advanced semantics](#advanced-semantics)
  - [Daily totals](#daily-totals) - [Data resolution](#data-resolution) - [Deletions](#deletions) - [Records outside the read window](#records-outside-the-read-window) - [Backfill windows](#backfill-windows)
  - [Outbox and sequence](#outbox-and-sequence) - [Record metadata](#record-metadata) - [Diagnostics](#diagnostics) - [Inbound: what the integration may send back](#inbound-what-the-integration-may-send-back)
- [Older app versions](#older-app-versions)
- [Home Assistant and other backends](#home-assistant-and-other-backends)

<a id="delivery-retries-and-signing"></a>

## Quick start for receivers

### The request

The **Health** tab and the **Screen Time** tab each have their own **Webhook URLs**, **Webhook Headers** and **HMAC signing secret (optional)**. Every payload of a section goes to every URL of that section.

| Part | Value |
|---|---|
| Method | `POST` to the URL as entered |
| `Content-Type` | `application/json; charset=utf-8` |
| Body | One JSON object, UTF-8 |
| `X-Signature` | `sha256=<lowercase hex>`, only when the section has a signing secret |
| Custom headers | The section's **Webhook Headers**, for example an `Authorization` header. A URL that QR pairing added gets none of them, so an address from a pairing code never receives the API keys typed for the others. |
| Client certificate | Optional mTLS. **Client certificate (mTLS)** under **Advanced** picks a certificate installed in Android's credential store; it is used for every webhook. |
| Transport | HTTPS. Plain `http://` is refused unless **Allow plain HTTP webhooks** is on. |

`source` in the body says what kind of payload it is: `health_connect` or `screen_time`. A body with `"test": true` is a [test ping](#test-ping) and carries no data. The iOS app posts the same shape from Apple Health with `"source": "healthkit_ios"`, so one receiver can take both; its [payload reference](https://github.com/owen282000/life-dashboard-companion-app-ios/blob/main/docs/webhook.md) documents where it differs.

### Verifying the signature

With a signing secret set, every request carries:

```
X-Signature: sha256=<hex of HMAC-SHA256(key = secret, message = raw request body)>
```

- The key is the secret as the app saved it (spaces at either end are removed), as UTF-8 bytes. A secret that looks like hex is still used as text: do not hex-decode or base64-decode it.
- The message is the body bytes as they arrived. Compute the HMAC before parsing, never over JSON you parsed and serialized again: key order and number formatting would differ.
- Compare in constant time. Node's `crypto.timingSafeEqual` throws on inputs of different length, so compare the lengths first.
- Respond to a bad signature with 401. The app does not retry it, keeps the payload and the ones after it in its outbox, and delivers all of them once the secret is corrected.

The signature covers the body only. Use `uuid` and `sequence` (see [Deduplication and ordering](#deduplication-and-ordering)) to make a repeated request harmless.

<a id="example-backend-integrations"></a>

<a id="simple-expressjs-server"></a>

### Receiver in Node.js (Express)

```javascript
const crypto = require('crypto');
const express = require('express');

const SECRET = process.env.LIFE_DASHBOARD_SECRET; // as typed in the app, not decoded
if (!SECRET) throw new Error('Set LIFE_DASHBOARD_SECRET to the secret from the app');
const app = express();

// Keep the raw bytes for the signature, and accept large payloads: a backfill payload
// easily passes Express's 100 kB default, and the 413 that follows tells the app
// that you refuse this payload.
app.use(express.json({
  limit: '10mb',
  verify: (req, _res, buf) => { req.rawBody = buf; },
}));

function signatureIsValid(req) {
  if (!req.rawBody) return false;
  const expected = Buffer.from(
    'sha256=' + crypto.createHmac('sha256', SECRET).update(req.rawBody).digest('hex')
  );
  const presented = Buffer.from(req.get('X-Signature') || '');
  return presented.length === expected.length && crypto.timingSafeEqual(presented, expected);
}

app.post('/life-dashboard', (req, res) => {
  if (!signatureIsValid(req)) return res.sendStatus(401);
  res.sendStatus(204); // respond first: the app gives up waiting after 10 seconds
  setImmediate(() => {
    try { store(req.body); } catch (e) { console.error('store failed', e); }
  });
});

function store(payload) {
  if (payload.test) return; // a test ping carries no data
  // Deduplicate records on uuid; see "Deduplication and ordering".
  console.log(payload.source, payload.sequence, Object.keys(payload));
}

app.listen(3000);
```

### Receiver in Python (Flask)

```python
import hashlib
import hmac
import json
import logging
import os
import queue
import threading

from flask import Flask, request

SECRET = os.environ["LIFE_DASHBOARD_SECRET"].encode("utf-8")  # as typed in the app, not decoded
app = Flask(__name__)
jobs = queue.Queue()


def signature_is_valid(body: bytes, presented: str) -> bool:
    expected = "sha256=" + hmac.new(SECRET, body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected.encode(), presented.encode())


@app.post("/life-dashboard")
def receive():
    body = request.get_data()  # the raw bytes the signature covers
    if not signature_is_valid(body, request.headers.get("X-Signature", "")):
        return "", 401
    jobs.put(body)  # respond first, store on the worker thread
    return "", 204


def worker():
    while True:
        try:
            payload = json.loads(jobs.get())
            if payload.get("test"):
                continue  # a test ping carries no data
            # Deduplicate records on uuid; see "Deduplication and ordering".
            print(payload["source"], payload.get("sequence"), sorted(payload))
        except Exception:
            logging.exception("store failed")  # keep the worker alive for the next payload


threading.Thread(target=worker, daemon=True).start()

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=3000)
```

Both receivers expect a signing secret. Without one in the app the requests carry no `X-Signature`; then remove the check and protect the URL with a header under **Webhook Headers**, such as `Authorization`, instead.

Flask sets no body limit of its own. Both receivers respond before they store, so a crash in between loses that payload; if that matters, write the raw body to disk or a durable queue first and respond after that.

To try a receiver from the phone on your own network, turn on **Allow plain HTTP webhooks** under **Advanced** (on either tab, it applies to both) and enter `http://<computer's address>:3000/life-dashboard`. **Test ping** then sends one request without data, signed when the section has a secret. Use HTTPS for anything beyond your own network.

### n8n and Node-RED

In n8n, use a Webhook node with the HTTP method `POST`, publish the workflow, and put the node's production URL in the app. The production URL only exists while the workflow is published, and the test URL only listens while the editor waits for a test event. A sync sent to either one at another time gets a 404.

To check the signature in n8n:

1. Add the Webhook node's **Raw Body** option. The bytes as they arrived are then in the binary property `data`, next to the parsed JSON in `body`.
2. A **Crypto** node with the action **Hmac** computes the HMAC over them: switch on **Binary File**, set **Type** to SHA256 and **Encoding** to hex, and put the secret in its Crypto credential as **Hmac Secret**.
3. An **If** node checks that `{{ 'sha256=' + $json.data }}` is equal to `{{ $json.headers['x-signature'] }}`. The Crypto node writes its result to `data`, its **Property Name**.
4. Set the Webhook node's **Respond** to **Using 'Respond to Webhook' Node**, and end each branch of the **If** node in its own **Respond to Webhook** node with **Respond With** **No Data**. Under **Options**, give the one on the true branch a **Response Code** of 204 and the one on the false branch 401. Put the slow part of the workflow after the 204: the app waits at most 10 seconds.

With **Respond** on **Immediately**, n8n responds with 200 before the check runs, so a payload with a wrong signature counts as delivered.

In Node-RED, the **http in** node parses JSON before your flow sees it. Check **Do not parse request body** (Node-RED 4.1 or later) to get the raw bytes as a Buffer for the HMAC, and parse them yourself after the check. In a **function** node, add `crypto` under **Setup** > **Modules** and compare `'sha256=' + crypto.createHmac('sha256', secret).update(msg.payload).digest('hex')` with `msg.req.headers['x-signature']`. Respond with an **http response** node right away, before the slow part of the flow. A flow that lets the node parse is limited to 5 MB per request; raise `apiMaxLength` in `settings.js` to `'10mb'`.

### Responses, retries and timeouts

Any 2xx status means delivered. The response body is ignored, except at the one URL that [Receive](#inbound-what-the-integration-may-send-back) talks to.

| Status | Retried within the sync | Afterward |
|---|---|---|
| 2xx | - | Delivered |
| 3xx | A redirect on the same host is followed, at most 5 in a row, with the same POST, body, signature and headers. Any other 3xx fails without a retry. | Kept in the outbox, like a 401 |
| 400, 413, 422 | No | Kept in the outbox, but payloads queued after it are sent anyway: these say the receiver refuses this payload, not every payload. Offered again on every sync for a week, then dropped with a log row. |
| 401, 403, 404, 410 and other 4xx | No | Kept in the outbox. The outbox stops at it, so later payloads wait until the setup is fixed, then all arrive in order. |
| 408, 429, 5xx, network errors, timeouts | Yes, 3 attempts in total, 1 s and then 2 s apart | Kept in the outbox, which stops there, as with a 401 |

`Retry-After` is not read. The app waits at most 10 seconds to connect, 10 seconds without progress while sending, and 10 seconds without a byte of the response. A receiver that takes longer gets the same payload again on the next attempt, and once more from the outbox if all three time out, so respond within a few seconds and do the work afterward. A receiver that deduplicates on `uuid` and `sequence` takes such a repeat without harm.

With several URLs, a payload counts as delivered when at least one URL took it. The app does not queue it again for the others. The sync line then reads "Delivered to 1 of 2 destinations" and the **Logs** tab shows the result per URL. The outbox, redirects and the drain order are described in [Outbox and sequence](#outbox-and-sequence).

### Payload size

One sync reads at most this many records per type into one payload, oldest change first:

| Types | Records per payload |
|---|---|
| Heart rate, steps, total calories | 1000 |
| Heart rate variability, respiratory rate, skin temperature | 500 |
| Every other type | 200 |

Heart rate and skin temperature count samples and keep each record whole, so the last record can take a payload past the cap. A type with more waiting is read again for another payload in the same sync, up to 8 payloads with records per sync; the rest follows on the next sync. A backfill sends as many payloads as a 3-day window needs, up to 400. All 33 types at their caps, with [record metadata](#record-metadata) on, come to a few megabytes. Allow at least 10 MB per request, in the framework and in any proxy in front of it (nginx allows 1 MB by default, through `client_max_body_size`). A 413 is a refusal of the payload (see above), so a body limit that is too low drops data after a week.

### Deduplication and ordering

- **`uuid`** is Health Connect's own ID for a record and stays the same when the record is sent again. Store records keyed on it. The same record arrives again after a failed delivery, after a timeout, when its source edits it, and in a backfill. Heart rate and skin temperature records hold many samples, so each sample goes out with `<record id>#<epoch milliseconds>` as its `uuid`.
- **`source`** is the package name of the app that wrote the record to Health Connect, such as `com.fitbit.FitbitMobile`. Phone and watch can each write their own copy of the same activity: use [daily totals](#daily-totals) instead of adding up records of different sources.
- **`sequence`** only goes up per install, so a payload that arrives late after a newer one can be recognized, and a payload that arrives twice has the same number both times. Skip a Health Connect payload whose `sequence` you already applied: [buckets](#data-resolution) have no `uuid`, and one that is combined twice counts its samples twice. See [Outbox and sequence](#outbox-and-sequence).

Deduplicating on `uuid` ignores a retransmitted copy of the same record. It does not collapse separate records that share source, type, interval and value; some sources write those, with different IDs, and the app sends both because they are two rows in Health Connect. [DATA_SOURCES.md](DATA_SOURCES.md#urevo-comurevoapp) has an observed case.

### Time and number formats

- Every timestamp is UTC in ISO 8601 with `Z`, such as `2026-10-04T06:12:00Z`. The fraction varies: none, or 3, 6 or 9 digits (`2026-10-04T08:41:17.203Z`). Parse with a parser that accepts all of them.
- `date` fields are a local day as `YYYY-MM-DD`: midnight to midnight in `daily_totals`, and from the Screen Time day boundary (04:00 by default) in `screen_time`. In `daily_totals`, today's entry is a running total that grows with every sync.
- A record over a period has `start_time` and `end_time`; a record at one moment has `time`. A sleep session has `session_end_time` and `duration_seconds`: its start is `session_end_time` minus `duration_seconds`.
- To put a record on the local day it happened on, turn on [record metadata](#record-metadata): it carries the zone offset the source wrote.
- Fields listed below as number are doubles and always have a decimal point, even for whole values (`75.0`, `1840.0`). Very small or large values can come in exponent notation (`1.0E-4`). Integers never have a decimal point.

<a id="health-connect-payload"></a>

## Payload overview

A sync payload with steps and heart rate, as it arrives:

```json
{
  "timestamp": "2026-10-04T07:15:02.481Z",
  "app_version": "1.23.0",
  "source": "health_connect",
  "sequence": 1842,
  "daily_totals": [
    { "date": "2026-10-04", "steps": 1204, "distance_meters": 911.6, "active_calories": 58.2, "total_calories": 702.9 }
  ],
  "steps": [
    { "count": 412, "start_time": "2026-10-04T06:00:00Z", "end_time": "2026-10-04T07:00:00Z", "uuid": "eb52b2c4-e3f7-49f3-8f92-34ca86640305", "source": "com.fitbit.FitbitMobile" }
  ],
  "heart_rate": [
    { "bpm": 61, "time": "2026-10-04T06:58:00Z", "uuid": "708a76cb-45d3-45ea-9f8b-26d6d55d2692#1791097080000", "source": "com.fitbit.FitbitMobile" }
  ],
  "_diagnostics": {
    "steps": {
      "permission_granted": true, "page_count": 1, "raw_record_count": 96,
      "raw_min_time": "2026-09-27T07:00:00Z", "raw_max_time": "2026-10-04T07:00:00Z",
      "raw_latest_modified_time": "2026-10-04T07:01:12.904Z",
      "filtered_record_count": 1, "min_time": "2026-10-04T06:00:00Z", "max_time": "2026-10-04T06:00:00Z",
      "last_sync": "2026-10-04T06:46:30.112Z", "error": null, "own_records_skipped": 0,
      "read_from": "2026-09-27T06:46:30.112Z", "lookback_gap_from": null
    },
    "heart_rate": {
      "permission_granted": true, "page_count": 1, "raw_record_count": 2210,
      "raw_min_time": "2026-09-27T06:47:00Z", "raw_max_time": "2026-10-04T06:58:00Z",
      "raw_latest_modified_time": "2026-10-04T07:01:12.904Z",
      "filtered_record_count": 1, "min_time": "2026-10-04T06:58:00Z", "max_time": "2026-10-04T06:58:00Z",
      "last_sync": "2026-10-04T06:46:30.112Z", "error": null, "own_records_skipped": 0,
      "read_from": "2026-09-27T06:46:30.112Z", "lookback_gap_from": null
    }
  }
}
```

A record array is present only when it holds at least one record; the app never sends an empty one. The same goes for most other fields:

| Field | Type | Present |
|---|---|---|
| `timestamp` | date-time | Always: when the payload was built |
| `app_version` | string | Always, such as `"1.23.0"` |
| `source` | string | Always: `health_connect`, `screen_time`, or `healthkit_ios` from the iOS app |
| `sequence` | integer | Health Connect and Screen Time payloads; not on test pings and heartbeats |
| Record arrays | array | One per [data type](#health-connect-data-types), only with records in it |
| `daily_totals` | array | Health Connect payloads with records, unless switched off: [Daily totals](#daily-totals) |
| `_resolutions` | object | When a series is bucketed: [Data resolution](#data-resolution) |
| `deleted_records`, `deletions_unavailable` | array | When there is something to report: [Deletions](#deletions) |
| `records_outside_window` | object | When there is something to report: [Records outside the read window](#records-outside-the-read-window) |
| `backfill`, `window_start`, `window_end`, `window_complete` | | Backfill payloads only: [Backfill windows](#backfill-windows) |
| `writeback` | object | Only to the Receive URL: [Inbound](#inbound-what-the-integration-may-send-back) |
| `_diagnostics` | object | Health Connect payloads with records, and the payload of an empty backfill window: [Diagnostics](#diagnostics) |
| `device`, `app_filter`, `screen_time` | | Screen Time payloads only: [Screen Time payload](#screen-time-payload) |
| `test`, `message` | | Test pings only: [Test ping](#test-ping) |

Every request is one of these:

| Request | How to recognize it |
|---|---|
| Sync payload | `source: health_connect`, record arrays. One sync can send several, see [Payload size](#payload-size). |
| Deletion payload | `source: health_connect` with `deleted_records`, `deletions_unavailable` or `records_outside_window` and no record arrays: a sync whose only news was a deletion. |
| Backfill payload | `"backfill": true` |
| Screen Time payload | `source: screen_time` |
| Test ping | `"test": true` |
| Heartbeat | `writeback` and nothing else besides `timestamp`, `app_version` and `source`; only to the Receive URL |

Every record carries `uuid` and `source` (see [Deduplication and ordering](#deduplication-and-ordering)), and with **Record metadata in payload** on also a [`metadata`](#record-metadata) object. The examples below leave them out unless they matter.

## Health Connect data types

One table per group. Interval types carry `start_time` and `end_time`; instant types carry `time`. All values are in the unit named, whatever unit the source app wrote them in. The types marked bucketable can be sent as one value per time window instead of per record; see [Data resolution](#data-resolution).

### Activity

| Key | Fields | Time | Bucketable |
|---|---|---|---|
| `steps` | `count` integer | interval | sum |
| `distance` | `meters` number | interval | sum |
| `active_calories` | `calories` number, kcal | interval | sum |
| `total_calories` | `calories` number, kcal | interval | sum |
| `exercise` | `type` string, `duration_seconds` integer | interval | no |

```json
"steps": [ { "count": 1234, "start_time": "2026-10-04T08:00:00Z", "end_time": "2026-10-04T09:00:00Z" } ],
"distance": [ { "meters": 1523.5, "start_time": "2026-10-04T08:00:00Z", "end_time": "2026-10-04T09:00:00Z" } ],
"active_calories": [ { "calories": 245.3, "start_time": "2026-10-04T08:00:00Z", "end_time": "2026-10-04T09:00:00Z" } ],
"total_calories": [ { "calories": 1850.0, "start_time": "2026-10-04T00:00:00Z", "end_time": "2026-10-04T09:00:00Z" } ],
"exercise": [ { "type": "56", "start_time": "2026-10-04T07:00:00Z", "end_time": "2026-10-04T08:00:00Z", "duration_seconds": 3600 } ]
```

`exercise.type` is Health Connect's exercise type constant as a string of digits: `"56"` is running, `"79"` walking. The full list is in [`ExerciseSessionRecord`](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/ExerciseSessionRecord). The iOS app sends a name instead, such as `"running"`.

### Body

| Key | Fields | Time |
|---|---|---|
| `weight` | `kilograms` number | instant |
| `height` | `meters` number | instant |
| `body_temperature` | `celsius` number | instant |

```json
"weight": [ { "kilograms": 75.5, "time": "2026-10-04T07:00:00Z" } ],
"height": [ { "meters": 1.82, "time": "2026-10-04T07:00:00Z" } ],
"body_temperature": [ { "celsius": 36.6, "time": "2026-10-04T07:00:00Z" } ]
```

### Body composition

| Key | Fields | Time |
|---|---|---|
| `body_fat` | `percentage` number, 0 to 100 | instant |
| `lean_body_mass` | `kilograms` number | instant |
| `bone_mass` | `kilograms` number | instant |
| `body_water_mass` | `kilograms` number | instant |

```json
"body_fat": [ { "percentage": 18.5, "time": "2026-10-04T07:00:00Z" } ],
"lean_body_mass": [ { "kilograms": 61.5, "time": "2026-10-04T07:00:00Z" } ],
"bone_mass": [ { "kilograms": 3.2, "time": "2026-10-04T07:00:00Z" } ],
"body_water_mass": [ { "kilograms": 42.0, "time": "2026-10-04T07:00:00Z" } ]
```

### Vitals

| Key | Fields | Time | Bucketable |
|---|---|---|---|
| `heart_rate` | `bpm` integer | instant, one object per sample | average |
| `resting_heart_rate` | `bpm` integer | instant | no |
| `heart_rate_variability` | `heart_rate_variability_millis` number, RMSSD in ms | instant | average |
| `blood_pressure` | `systolic` number, `diastolic` number, mmHg | instant | no |
| `blood_glucose` | `mmol_per_liter` number | instant | no |
| `oxygen_saturation` | `percentage` number, 0 to 100 | instant | average |
| `respiratory_rate` | `rate` number, breaths per minute | instant | average |

```json
"heart_rate": [ { "bpm": 72, "time": "2026-10-04T10:30:00Z", "uuid": "51b0d6a3-bf65-4a90-95dd-e6197e8104c6#1791109800000" } ],
"resting_heart_rate": [ { "bpm": 58, "time": "2026-10-04T07:00:00Z" } ],
"heart_rate_variability": [ { "heart_rate_variability_millis": 42.5, "time": "2026-10-04T03:10:00Z" } ],
"blood_pressure": [ { "systolic": 120.0, "diastolic": 80.0, "time": "2026-10-04T07:00:00Z" } ],
"blood_glucose": [ { "mmol_per_liter": 5.5, "time": "2026-10-04T07:00:00Z" } ],
"oxygen_saturation": [ { "percentage": 98.0, "time": "2026-10-04T07:00:00Z" } ],
"respiratory_rate": [ { "rate": 16.0, "time": "2026-10-04T07:00:00Z" } ]
```

A heart rate record in Health Connect holds many samples; each sample is its own object, with `<record id>#<epoch milliseconds>` as its `uuid`. Heart rate variability is RMSSD, the measure Health Connect stores. The iOS app sends SDNN, the one Apple Health stores, under the same key; the two are not the same number.

### Sleep

| Key | Fields | Time |
|---|---|---|
| `sleep` | `session_end_time` date-time, `duration_seconds` integer, `stages` array | session end plus duration |

Each stage has `stage`, `start_time`, `end_time` and `duration_seconds`. `stage` is one of `unknown`, `awake`, `sleeping`, `out_of_bed`, `light`, `deep`, `rem`, `awake_in_bed`. A session without stages has an empty `stages` array.

```json
"sleep": [
  {
    "session_end_time": "2026-10-04T06:30:00Z",
    "duration_seconds": 28800,
    "stages": [
      { "stage": "deep", "start_time": "2026-10-03T23:00:00Z", "end_time": "2026-10-04T01:00:00Z", "duration_seconds": 7200 }
    ]
  }
]
```

The session started at `session_end_time` minus `duration_seconds`, here 22:30 UTC.

### Nutrition

| Key | Fields | Time |
|---|---|---|
| `hydration` | `liters` number | interval |
| `nutrition` | `calories` (kcal), `protein_grams`, `carbs_grams`, `fat_grams`, `name`, `meal_type` and 38 nutrient fields, all optional | interval |

```json
"hydration": [ { "liters": 0.5, "start_time": "2026-10-04T08:00:00Z", "end_time": "2026-10-04T08:00:00Z" } ],
"nutrition": [
  {
    "calories": 450.0, "protein_grams": 25.0, "carbs_grams": 60.0, "fat_grams": 12.0,
    "name": "Oatmeal with berries", "meal_type": "breakfast",
    "dietary_fibre_g": 6.2, "sugars_g": 9.1, "saturated_fat_g": 2.0, "trans_fat_g": 0.0,
    "sodium_mg": 180.0, "potassium_mg": 320.0, "iron_mg": 1.8,
    "vitamin_c_mg": 8.0, "vitamin_d_mcg": 2.5, "vitamin_b12_mcg": 1.2,
    "start_time": "2026-10-04T07:30:00Z", "end_time": "2026-10-04T07:45:00Z"
  }
]
```

Every nutrient Health Connect's `NutritionRecord` holds is sent: energy from fat, fiber, sugars, the fat subtypes, cholesterol, 14 minerals and trace elements, 14 vitamins and related nutrients, and caffeine. The unit is in the key's suffix (`_g`, `_mg`, `_mcg`, `_kcal`); the fiber key is spelled `dietary_fibre_g`. A field the source app did not write is left out, while a real zero (`"trans_fat_g": 0.0`) is kept. `meal_type` is one of `breakfast`, `lunch`, `dinner`, `snack`, `unknown`. The full key list is in [webhook-schema.json](webhook-schema.json). Nutrition records carry no `metadata`, even with **Record metadata in payload** on.

### Mindfulness

| Key | Fields | Time |
|---|---|---|
| `mindfulness` | `title` string, optional; `duration_seconds` integer | interval |

```json
"mindfulness": [ { "title": "Morning meditation", "start_time": "2026-10-04T06:00:00Z", "end_time": "2026-10-04T06:15:00Z", "duration_seconds": 900 } ]
```

`title` is left out when the source app wrote none.

### Cycle tracking

| Key | Fields | Time |
|---|---|---|
| `menstruation_period` | none besides the time | interval |
| `menstruation_flow` | `flow`: `light`, `medium`, `heavy`, `unknown` | instant |
| `intermenstrual_bleeding` | none besides the time | instant |
| `ovulation_test` | `result`: `positive`, `high`, `negative`, `inconclusive`, `unknown` | instant |
| `cervical_mucus` | `appearance`: `dry`, `sticky`, `creamy`, `watery`, `egg_white`, `unusual`, `unknown`; `sensation`: `light`, `medium`, `heavy`, `unknown` | instant |
| `sexual_activity` | `protection_used`: `protected`, `unprotected`, `unknown` | instant |
| `basal_body_temperature` | `celsius` number | instant |

```json
"menstruation_period": [ { "start_time": "2026-10-01T00:00:00Z", "end_time": "2026-10-05T00:00:00Z" } ],
"menstruation_flow": [ { "flow": "medium", "time": "2026-10-03T00:00:00Z" } ],
"intermenstrual_bleeding": [ { "time": "2026-10-10T00:00:00Z", "source": "com.example.cycleapp" } ],
"ovulation_test": [ { "result": "positive", "time": "2026-10-12T08:00:00Z" } ],
"cervical_mucus": [ { "appearance": "egg_white", "sensation": "medium", "time": "2026-10-12T08:00:00Z" } ],
"sexual_activity": [ { "protection_used": "protected", "time": "2026-10-11T00:00:00Z" } ],
"basal_body_temperature": [ { "celsius": 36.4, "time": "2026-10-12T06:30:00Z" } ]
```

### Metabolic and fitness

| Key | Fields | Time | Bucketable |
|---|---|---|---|
| `basal_metabolic_rate` | `kilocalories_per_day` number | instant | no |
| `vo2_max` | `vo2_ml_per_min_per_kg` number | instant | no |
| `skin_temperature` | `delta_celsius` number; `baseline_celsius` number, optional | instant, one object per sample | average |

```json
"basal_metabolic_rate": [ { "kilocalories_per_day": 1650.0, "time": "2026-10-04T00:00:00Z" } ],
"vo2_max": [ { "vo2_ml_per_min_per_kg": 42.5, "time": "2026-10-04T09:00:00Z" } ],
"skin_temperature": [ { "delta_celsius": -0.3, "baseline_celsius": 33.5, "time": "2026-10-04T03:10:00Z", "uuid": "01f1561e-0590-4560-9e3d-152b3a4d6048#1791083400000" } ]
```

Skin temperature is a delta from a baseline, as wearables write it to Health Connect. A record holds many deltas, sent one object each like heart rate samples, with `<record id>#<epoch milliseconds>` as `uuid`; `baseline_celsius` is left out when the source wrote none.

## Screen Time payload

```json
{
  "timestamp": "2026-10-04T12:00:00Z",
  "app_version": "1.23.0",
  "device": "Google Pixel 8",
  "source": "screen_time",
  "sequence": 1843,
  "screen_time": [
    {
      "date": "2026-10-04",
      "total_screen_time_minutes": 180,
      "apps": [
        { "package": "com.instagram.android", "name": "Instagram", "minutes": 45, "last_used": "2026-10-04T11:30:00Z" }
      ]
    }
  ]
}
```

`device` is the phone's manufacturer and model. `date` is the day as the app counts it: by default from 04:00 to 04:00 local time, so phone use after midnight still counts toward the day before. The hour can be changed, or the boundary switched off for midnight to midnight, under **Day Boundary** on the **Screen Time** tab. Minutes are whole minutes, rounded down.

Minutes are foreground time per app, derived from Android's activity resume, pause and stop events; background time is not counted. A session also ends on screen off, keyguard and shutdown. System UI and the launcher are left out, and so are apps with one minute or less that day, so totals are comparable to Digital Wellbeing. Digital Wellbeing counts from midnight, so with the day boundary on (the default) the days will not match exactly.

Every sync recomputes and sends the last 7 days again from the device's event log, so store per date and let the newest payload win for that date. The newest is the one with the highest `sequence`, not the one that arrived last: a week that failed waits in the outbox and can arrive after a newer one. Store with each date the `sequence` of the payload that wrote it, and apply a day only from a payload with a higher one. Do not ignore a late week as a whole: its oldest date may be one that no newer week covers anymore.

### Which apps are sent

Under **Apps to send** on the **Screen Time** tab the user can leave apps out (**All except**) or send only a few (**Only**); by default every app goes out. An app that is filtered out never leaves the phone: it is not in `apps`, not on MQTT and not in the `top_apps` of the Home Assistant sensors, and its name is not in the payload in any form. With a filter on, the payload says so and each day carries one more figure:

```json
{
  "timestamp": "2026-10-04T12:00:00Z",
  "app_version": "1.23.0",
  "device": "Google Pixel 8",
  "source": "screen_time",
  "sequence": 1844,
  "app_filter": "blocklist",
  "screen_time": [
    {
      "date": "2026-10-04",
      "total_screen_time_minutes": 180,
      "filtered_screen_time_minutes": 135,
      "apps": [
        { "package": "com.whatsapp", "name": "WhatsApp", "minutes": 135, "last_used": "2026-10-04T11:52:00Z" }
      ]
    }
  ]
}
```

- `app_filter` is `blocklist` or `allowlist`, and absent without a filter.
- `total_screen_time_minutes` still counts every app, so a stored figure keeps meaning screen time; with a filter the apps no longer add up to it.
- `filtered_screen_time_minutes` is the time of the apps that are sent, absent without a filter. A day whose apps were all filtered out is still sent, with its total, no apps and 0 here.

The Home Assistant sensors follow the filter. The MQTT sensors and the [Life Dashboard integration](https://github.com/owen282000/life-dashboard-ha) (0.8.0 or newer) show the time of the apps that are sent, with the real total in the attribute `all_apps_minutes`. The most used app is picked among the sent apps, and is `none` when the filter left no app for today. Every sync sends the last seven days again, so changing the list changes those days on a receiver too: apps sent before disappear from days it already stored. The app says so next to the list.

## Test ping

**Test ping** on either tab, **Send Test Ping** in the setup wizard, and every QR pairing (right after it is applied, to the paired address with the paired secret) send one small payload with no records in it:

```json
{
  "test": true,
  "message": "Test ping from Life Dashboard Companion",
  "timestamp": "2026-10-04T12:00:00Z",
  "app_version": "1.23.0",
  "source": "health_connect"
}
```

`source` is the section the address belongs to, `health_connect` or `screen_time`, so a receiver can tell which of its last-sync times the ping answers for (the iOS app sends `healthkit_ios`). It is signed like any other request. A ping is retried like a sync but never queued in the outbox, and the pairing stays in place when it fails: the toast names the reason.

## Advanced semantics

### Daily totals

When several apps write the same activity to Health Connect (phone and watch, or a mirroring app such as Health Sync), the raw records contain each copy and adding them up double-counts. The payload therefore also carries `daily_totals`, computed with Health Connect's aggregate API, which deduplicates across sources and matches what the Health Connect app shows.

```json
"daily_totals": [
  { "date": "2026-10-03", "steps": 9874, "distance_meters": 7120.0, "active_calories": 498.4, "total_calories": 2390.1 },
  { "date": "2026-10-04", "steps": 8421, "distance_meters": 6210.4, "active_calories": 412.0, "total_calories": 2231.5 }
]
```

- A sync carries today and the two days before, each the phone's local day from midnight to midnight. Today's entry is a running total up to the moment of the sync; replace the stored day with each new figure.
- Only enabled types appear: `steps`, `distance_meters` (meters), `active_calories` and `total_calories` (kcal). A day without any of them is left out.
- Every payload of one sync carries the same `daily_totals`. Deletion payloads and heartbeats carry none.
- A backfill carries a total for every day its window touches, in every payload of the window, so a receiver that keeps history gets the real total for each past day. A day cut by a window boundary appears in both windows with the same figures.
- **Daily totals in payload** under **Advanced** on the **Health** tab switches them off.

Health Connect counts every stretch of time once. Where records of different apps overlap, the app highest in Health Connect's priority list for that category counts. Where records of one app overlap, the one written last counts. Records that do not overlap all count, so a copy that a source writes into the wrong minute is in the total too (see [DATA_SOURCES.md](DATA_SOURCES.md#gadgetbridge-nodomainfreeyourgadgetgadgetbridge)). An app that is not in that priority list does not count at all; Health Connect normally adds an app there when it is allowed to write.

Use `daily_totals` for the totals per day and the raw records for detail. They are not measurements of a single exercise session: a day can include other activities and other sources. Records that arrive late, for example a watch that uploads hours later with the original timestamps, are still delivered: the sync filters on each record's modification time, not on its timestamp.

### Data resolution

A chest strap writes a heart rate sample every second, which is 86,400 records a day that no dashboard reads one by one. Any of the bucketable types can be sent as one value per time window instead: 1, 5 or 15 minutes, or hourly, set per type under **Data Resolution** on the **Health** tab. Everything defaults to every record.

A bucketed series replaces its raw array under the same key, and the objects inside have a different shape. They never carry the raw field name, so `"bucket_start" in obj` is a reliable test and a parser looking for `bpm` cannot mistake an average for a measurement.

```json
"heart_rate": [
  { "bucket_start": "2026-10-04T08:00:00Z", "bucket_end": "2026-10-04T08:01:00Z",
    "sample_count": 58, "avg": 72.4, "min": 66.0, "max": 81.0, "sources": ["com.garmin.android.apps.connectmobile"] }
],
"steps": [
  { "bucket_start": "2026-10-04T08:00:00Z", "bucket_end": "2026-10-04T09:00:00Z",
    "sample_count": 12, "total": 1840.0, "complete": true }
],
"_resolutions": { "heart_rate": "1m", "steps": "1h" }
```

Measured values (heart rate, heart rate variability, oxygen saturation, respiratory rate, skin temperature) are averaged into `avg`, with `min` and `max` kept, because an average alone cannot tell a night's sleep from a sprint. Accumulated quantities (steps, distance, active and total calories) are summed into `total` and carry no average. `avg`, `min`, `max` and `total` are numbers with a decimal point, even for steps. `sources` lists the apps whose records went in, and is left out when there are none. `_resolutions` names the window per bucketed series (`1m`, `5m`, `15m` or `1h`), so a receiver can store the data correctly without being configured separately.

When you store buckets:

- **Windows are aligned to the clock**, not to the first sample. A 15-minute window starts at :00, :15, :30 or :45 in UTC, so buckets from different syncs line up.
- **`sample_count` says how complete a bucket is.** A window with two samples and one with sixty are both one object; without the count you cannot tell them apart or merge them.
- **A window is normally sent once, complete.** Bucketed series arrive in the last payload of a sync, even when a large backlog made the sync deliver its raw records in several payloads. A window that is still filling when a sync runs is not sent yet; its samples are kept and bucketed together with the next sync's records, so the bucket goes out whole.
- **Empty windows produce nothing.** No bucket means nothing was measured, which is not the same as a measured zero.

A window can still go out again: a record arrives late for a window already sent (a watch uploading hours after the fact), a record is edited, or a source writes records again that it wrote before. Some sources export their last hour again on every sync under the same record IDs, which Health Connect stores as new versions of the same records. So a receiver keys on the series and `bucket_start`, and what it does with a bucket for a window it already holds depends on one field:

- **`"complete": true`: replace the stored window.** The bucket holds every record Health Connect had in the window when it was built, not only the ones that changed. Adding it to the stored one would count a rewritten record twice. Only accumulated series carry it.
- **No `complete`: combine.** The bucket holds only the samples that changed: add the `sample_count`s, add the `total`s, take the smaller `min` and the larger `max`, and weight the `avg` by `sample_count` (`(avg1 * n1 + avg2 * n2) / (n1 + n2)`). Measured series always go out this way: a sample that comes again because its source wrote it again leaves the window's `avg`, `min` and `max` as they were and only raises `sample_count`. An accumulated series goes out this way when the app could not read the whole window: a window reaching back past the range the sync read, or a read that had to skip part of its range.

In a backfill, an accumulated series is read from bucket bound to bucket bound rather than from the 3-day window's own bounds, so the windows' read ranges meet at bucket bounds, and every bucket is read by exactly one backfill window and goes out once, complete. The bucket that contains the moment the backfill started is left to the normal sync.

Bucketing applies to webhook payloads only. The Home Assistant sensors always publish the latest value or today's total, and `daily_totals` is unaffected because it comes from Health Connect's own aggregate. Buckets carry no `metadata`.

### Deletions

A record that is deleted in Health Connect leaves nothing behind for a sync to read, so a receiver that stores records would keep it forever. Apps that edit by replacing make this visible: Cronometer, for instance, deletes a meal and inserts a new one, which arrives as a second record with a different `uuid` while the original is still on the receiver.

The app follows Health Connect's change feed and names the records that are gone:

```json
"deleted_records": [
  { "type": "nutrition", "uuid": "abde87cd-1097-47ca-a262-0fdfe0a0b795" }
]
```

- `type` is the payload key the record arrived under, so a receiver drops that `uuid` from that collection.
- A heart rate or skin temperature deletion names the record, while its samples arrived as `<record uuid>#<epoch millis>`: drop every sample whose `uuid` starts with that `uuid` followed by `#`.
- The field is absent when nothing was deleted. Deletions ride along on the first payload of a sync, and when nothing else changed they get a [deletion payload](#payload-overview) of their own.
- The app never names a record as deleted that exists again, and never sends one `uuid` as a record and as a deletion in the same payload.

A `uuid` in `deleted_records` means the record was gone when the app read the change feed, not that the ID is retired. Some sources revise a record by deleting it and writing it again under their own client record ID, as Fitbit does with sleep and calories. The new record gets the same `uuid`, so a deleted `uuid` can arrive again later as a record. Store it again.

Two limits to build around:

- **Tracking starts when the app first syncs a type**, so deletions from before that were never observable.
- **Some syncs cannot vouch for a type.** Those are named in `deletions_unavailable`, a list of payload keys. It happens when Health Connect has forgotten the type's change feed after 30 days without a read, when a type has more changes than one sync can read, when a type cannot be read at all, and when Health Connect does not answer within the time the sync allows the deletion step (five seconds per type, twenty in total). In the last case the type keeps its place in the change feed and is read on the next sync. In each case the app does not know what was deleted, so reconcile those types against a [backfill window](#backfill-windows) instead of trusting the incremental payload.

```json
"deletions_unavailable": ["hydration", "nutrition"]
```

### Records outside the read window

Each sync reads a type starting a week before the last sync that read the whole type (30 days back at most), and keeps only what changed since that sync. The same change feed shows records that a source wrote or edited long after their own timestamp: a watch that was away from the phone for more than a week uploads its readings with their original times. Those that fall before the read range are not in the payload. They are named per payload key in `records_outside_window`:

```json
"records_outside_window": {
  "heart_rate": { "count": 412, "from": "2026-09-14T06:02:11Z", "until": "2026-09-20T12:00:03.114Z" }
}
```

`count` is how many such changes Health Connect reported, `from` the timestamp of the oldest of them, and `until` where the range the sync read started (`read_from` in [`_diagnostics`](#diagnostics)), not the time of the newest record. A [backfill](#backfill-windows) of `from` to `until` sends them. Like deletions, the field is absent when there are none and rides along on the next payload that goes out, or on one of its own.

### Backfill windows

**Backfill** on the **Health** tab sends the last 30, 90 or 365 days in 3-day windows, oldest first. A backfill is also the fallback for deletions, and marks each window it has sent in full. Every backfill payload carries:

```json
"backfill": true,
"window_start": "2026-09-01T07:15:02Z",
"window_end": "2026-09-04T07:15:02Z",
"window_complete": true
```

Windows are 72 hours long. The first starts 30, 90 or 365 days before the moment the backfill started, so the bounds fall at that time of day, not at midnight. The last window ends at that moment, and for 365 days it is shorter than the others.

- `window_complete: true` is on the last payload of a window. It means every record the phone holds for that window has now been sent, so a receiver may treat any `uuid` it holds for that window, but that the backfill did not send, as deleted.
- A window split into several payloads carries `window_complete: false` on all but the last.
- A window can also end on `false` with nothing after it. When a type could not be read, or the window needed more than 400 payloads, the backfill stops there with an error. Run it again and it sends that window again from its first payload. Never drop records on `false`.
- A window that holds nothing still sends one payload with `window_complete: true`, which is what distinguishes an empty window from an unreported one.
- Backfill payloads carry `sequence` and `daily_totals` like sync payloads, and never `writeback`. A backfill does not touch what the regular sync keeps track of.

### Outbox and sequence

A payload that failed is kept in an outbox on the phone and sent again, oldest first, at the start of the next sync, with the settings the app has by then. The drain stops at the first payload that fails again, so the order holds while a receiver is down or misconfigured. A payload refused with 400, 413 or 422 is skipped instead (see [Responses, retries and timeouts](#responses-retries-and-timeouts)), so the payloads queued after it may arrive before it.

The outbox holds up to 700 Health Connect payloads, a week of 15-minute syncs; beyond that the oldest is dropped, with a log row and a notification. Screen Time keeps only its newest failed week, which replaces the one queued before it, so expect gaps in its `sequence`; after more than a week without a delivery, days that fall out of that week are lost, again with a log row and a notification. One sync drains for at most 2 minutes and leaves the rest to the next.

Every Health Connect and Screen Time payload carries `sequence`, from one counter per install that only goes up. Payloads normally arrive in order, but a receiver behind several webhook URLs, a proxy or a retrying load balancer can still see an older one land after a newer one, and so can one that refused a payload with 400. Recording the highest `sequence` applied per install and `source` lets a receiver ignore the late one instead of letting it restore a record that was deleted since. The two sources share the counter, so per source the numbers only go up but can skip. Compare Screen Time per date rather than per payload, see [Screen Time payload](#screen-time-payload). The iOS app does not send `sequence`, so treat a missing one as unknown rather than zero.

Redirects are followed only on the same host: the same port, or `http` on port 80 moving up to `https` on 443, at most 5 in a row. The log notes the new address so you can enter it and skip the extra request. A redirect to another host, from `https` down to `http`, or to plain `http` without **Allow plain HTTP webhooks** is not followed: it would send the body, the signature and your custom headers to an address you did not enter. The log names the host it pointed at.

### Record metadata

With **Record metadata in payload** on (**Health** tab, **Advanced**; off by default), every record carries Health Connect's metadata for it under `metadata`:

```json
"sleep": [
  { "session_end_time": "2026-10-04T06:12:00Z", "duration_seconds": 30120, "stages": [],
    "uuid": "75efaf66-a70d-4f27-b6a8-ef6d529d4cbf", "source": "com.fitbit.FitbitMobile",
    "metadata": {
      "last_modified": "2026-10-04T08:41:17.203Z",
      "client_record_id": "sleep-2026-10-03",
      "client_record_version": 4,
      "recording_method": "automatic",
      "device": { "manufacturer": "Google", "model": "Pixel Watch 3", "type": "watch" },
      "start_zone_offset": "+02:00",
      "end_zone_offset": "+02:00"
    } }
]
```

- `last_modified` is when the source last wrote the record. A band that keeps revising a night after waking moves it with every revision, so a receiver can tell an intermediate duration from the settled one, and keep the copy with the newest `last_modified` when two arrive out of order.
- `client_record_id` and `client_record_version` are the writing app's own ID and version for the record, when it gave them. A source that writes a record again under the same ID raises the version.
- `recording_method` is `active` (a workout the user started), `automatic` (recorded in the background), `manual` (typed in) or `unknown`.
- `device` is the device the source says it recorded on; `type` is one of `watch`, `phone`, `scale`, `ring`, `head_mounted`, `fitness_band`, `chest_strap`, `smart_display` or `unknown`.
- `zone_offset` belongs to a record at one moment (a weight, a heart rate variability reading), `start_zone_offset` and `end_zone_offset` to a record over a period (steps, a night, a workout, and the heart rate and skin temperature records whose samples are sent). They are the offsets the source wrote, such as `+02:00`, with `Z` for UTC. With them a receiver puts a record on the local day it happened on, even after traveling.

Anything Health Connect does not have is left out, so `metadata` holds at least `last_modified` and `recording_method`. Heart rate and skin temperature samples carry the metadata of the record they belong to. Nutrition records carry none, and neither do buckets: a window has no single record. Records the app wrote itself through Receive never go out, with or without metadata. Metadata makes payloads larger, by roughly 150 to 250 bytes per record, which matters most for dense heart rate; [Data resolution](#data-resolution) is the way to keep those small.

### Diagnostics

A Health Connect payload with records, and the payload of an empty backfill window, ends with a `_diagnostics` object: one entry per type this payload read, keyed by payload key, so a receiver can see what Health Connect returned before and after the incremental filter. Screen Time payloads, test pings, heartbeats and deletion payloads have none. When one sync sends a backlog over several payloads, the payloads after the first read only the types still draining, so their `_diagnostics` has entries for those types only.

```json
"_diagnostics": {
  "heart_rate_variability": {
    "permission_granted": true,
    "page_count": 1,
    "raw_record_count": 472,
    "raw_min_time": "2026-09-11T22:10:00Z",
    "raw_max_time": "2026-09-12T05:20:00Z",
    "raw_latest_modified_time": "2026-09-12T05:43:39.120Z",
    "filtered_record_count": 0,
    "min_time": null,
    "max_time": null,
    "last_sync": "2026-09-12T05:44:06.439Z",
    "error": null,
    "own_records_skipped": 0,
    "read_from": "2026-09-05T05:29:02.112Z",
    "lookback_gap_from": null
  }
}
```

| Field | Meaning |
|---|---|
| `permission_granted` | Whether the app holds the read permission for the type |
| `page_count` | Pages Health Connect returned |
| `raw_record_count` | Records Health Connect returned for the read range (samples, for heart rate and skin temperature) |
| `raw_min_time`, `raw_max_time` | Timestamp range of those records |
| `raw_latest_modified_time` | Newest modification time among them |
| `filtered_record_count` | Records newer than the watermark, that is, delivered in this payload |
| `min_time`, `max_time` | Timestamp range of the delivered records |
| `last_sync` | The watermark this read filtered against |
| `error` | Why the type was not read, or read only in part; null otherwise |
| `own_records_skipped` | Records the app wrote itself through Receive, left out of the payload |
| `read_from` | Start of the read range |
| `lookback_gap_from` | Null unless a pause was too long for the read range, see below |

When `raw_latest_modified_time` is older than `last_sync`, the source app has not written anything new yet. `read_from` is a week before the last sync that read the whole type, so a phone that was off or asleep for a while still picks up what a watch wrote before the pause, reaching back 30 days at most. When a pause was longer than that, `lookback_gap_from` says from where: records timestamped between it and `read_from` that were written or edited during the pause were not read, and a backfill of that range sends them. A sync that sends nothing keeps the gap for the next payload, so it is named at least once.

`error` messages a receiver can act on:

- "Health Connect did not return ... within 10 s" and "skipped: the read step used its budget of 120 s": Health Connect did not answer in time. The type keeps its place and the next sync reads it.
- "skipped: Health Connect's read quota is used up": Health Connect refused a read because the quota, which it counts per call and per app, was used up. The sync stopped reading there and the next one continues from there.

See [DATA_SOURCES.md](DATA_SOURCES.md) for what individual source apps do and do not write.

<a id="inbound-what-the-integration-may-answer"></a>

### Inbound: what the integration may send back

The app can also take measurements the other way: readings from a scale or a blood pressure monitor in Home Assistant go into Health Connect, and from there to Samsung Health or Google Health. This is **Receive** on the **Health** tab, and it needs the [Life Dashboard integration](https://github.com/owen282000/life-dashboard-ha) 0.7.0 or later. There is no second channel: the measurements ride back in the integration's response to the POST the app already makes. A receiver that is not the integration is not affected in any way; its response is never read.

#### What the app adds to its request

When Receive is on, the payload for one webhook URL, the source URL (the section's URL that contains `/api/webhook/`; the app asks which when there are several), carries a `writeback` block. Every other URL of the section gets the plain payload, and the block never appears in Screen Time or backfill payloads. Receive needs a signing secret.

```json
"writeback": {
  "protocol": 1,
  "types": ["weight", "body_fat", "blood_pressure"],
  "history": false,
  "ack": ["sensor.bathroom_scale_weight@1790405400000"],
  "failed": [
    { "id": "sensor.bathroom_scale_body_fat@1790405400000", "code": "permission_denied" }
  ]
}
```

- `protocol` is always `1`.
- `types` are the types the user switched on and holds the Health Connect write permission for; the integration sends readings of those types only.
- `history` is `true` when **Accept older measurements** is on. It is informational: the app enforces the 30-day window itself.
- `ack` names the readings written since the previous request (inserted, or already present at the same or a higher version and therefore left alone).
- `failed` names the readings that were not written, each with a code from the table below, never with its value.

A sync that has nothing to send still makes one request to the source URL when Receive is on, a heartbeat: `timestamp`, `app_version`, `source` and the `writeback` block, no record arrays, no `daily_totals`, no `sequence`, no `_diagnostics`. It is signed like any other request and never queued in the outbox.

<a id="the-answer"></a>

#### The response

The integration responds to every accepted POST (status 200) with a JSON body and a signature over it:

```
X-Signature: sha256=<hex of HMAC-SHA256(k_resp, raw response body)>
k_resp = HMAC-SHA256(key = secret as UTF-8 bytes, message = "life-dashboard-response-v1" as UTF-8 bytes)
```

The response key is derived from the shared secret and is never the secret itself, so a request the app signed can never be played back to it as a response. The app checks, in this order, and stops at the first fault without writing anything:

1. The body is at most 262144 bytes.
2. The header is present and equal (in constant time) to its own computation.
3. `life_dashboard.writeback` is `1`.
4. `writeback.in_reply_to` equals the `X-Signature` the app put on this very request.
5. `writeback.issued_at` is within 10 minutes of the phone's clock.
6. There are at most 200 readings.

A rejected response is one row in the **Logs** tab. A response without the protocol block at all, which is what an integration older than 0.7.0 sends, makes the **Receive** row ask to update the Life Dashboard integration, or to check that this webhook is the integration's; an app with Receive off never reads the body.

```json
{
  "life_dashboard": { "version": "0.7.0", "writeback": 1 },
  "writeback": {
    "in_reply_to": "sha256=9f2c41d8...",
    "issued_at": "2026-09-27T06:35:01Z",
    "configured": ["weight", "body_fat", "blood_pressure", "height"],
    "pending": [
      {
        "id": "sensor.bathroom_scale_weight@1790490600000",
        "version": 1,
        "type": "weight",
        "kilograms": 81.35,
        "time": "2026-09-27T06:30:00Z",
        "zone_offset": "+02:00",
        "recording_method": "auto",
        "device": { "type": "scale", "manufacturer": "Xiaomi", "model": "Mi Body Composition Scale 2" }
      },
      {
        "id": "sensor.omron_systolic@1790490720000",
        "version": 1,
        "type": "blood_pressure",
        "systolic": 128.0,
        "diastolic": 82.0,
        "time": "2026-09-27T06:32:00Z",
        "zone_offset": "+02:00",
        "recording_method": "active",
        "body_position": "sitting_down",
        "measurement_location": "left_upper_arm",
        "device": { "type": "unknown", "manufacturer": "Omron", "model": "M7 Intelli IT" }
      }
    ],
    "more": false
  }
}
```

`configured` lists the types the integration has a mapping for, so the app offers a switch for exactly those. `pending` holds at most 200 readings, oldest first, and only of the types the request asked for; `more: true` says there are more waiting, and the app asks again in the same sync (in heartbeat form, at most five times) or on the next one. `pending` and `more` are absent when the request carried no `writeback.types`.

#### A reading

| Field | Required | Content |
|---|---|---|
| `id` | yes | `{entity_id}@{measured_at_ms}`; becomes the Health Connect `clientRecordId` |
| `version` | yes | integer from 1; becomes `clientRecordVersion`. A correction of the same measurement is the same `id` with `version + 1` |
| `type` | yes | one of the types below |
| type fields | yes | the field names of the outbound payload, in the units Health Connect wants |
| `time` | yes | the measurement time in UTC with `Z`, never the sync time |
| `zone_offset` | no | `"+02:00"`; absent means the phone's zone |
| `recording_method` | no | `auto` (default), `active` or `manual` |
| `device` | no | `{"type", "manufacturer", "model"}`; `type` is one of `unknown`, `watch`, `phone`, `scale`, `ring`, `head_mounted`, `fitness_band`, `chest_strap`, `smart_display`, anything else counts as `unknown` |
| `time_source` | no | `"state"` when the integration used the entity's last change for lack of a timestamp entity; informational, it goes to the app's log |

`recording_method` is spelled differently in each direction: inbound it is `auto`, while the outbound [record metadata](#record-metadata) says `automatic` for the same thing. Any value the app does not know, `automatic` included, is read as `auto`, so the mismatch changes nothing for a reading, but do not copy an outbound value into an inbound reading and expect it to be checked.

| `type` | Fields | Health Connect record |
|---|---|---|
| `weight` | `kilograms` | WeightRecord |
| `height` | `meters` | HeightRecord |
| `body_fat` | `percentage` | BodyFatRecord |
| `lean_body_mass` | `kilograms` | LeanBodyMassRecord |
| `bone_mass` | `kilograms` | BoneMassRecord |
| `body_water_mass` | `kilograms` | BodyWaterMassRecord |
| `blood_pressure` | `systolic`, `diastolic`, optional `body_position` (`unknown`, `standing_up`, `sitting_down`, `lying_down`, `reclining`) and `measurement_location` (`unknown`, `left_wrist`, `right_wrist`, `left_upper_arm`, `right_upper_arm`) | BloodPressureRecord |

Unknown fields in a reading are ignored; an unknown `type` is reported as `unsupported_type`. BMI, muscle mass and visceral fat have no Health Connect record and are not offered.

#### What the app checks before it writes

Every reading is validated on the phone, and a reading that fails is reported back under `failed` with one of these codes:

| Code | Meaning | What the integration does |
|---|---|---|
| `permission_denied` | the write permission for this type is missing or was revoked | drops it and raises a repair issue asking for the permission |
| `unsupported_type` | a type this app version does not know | drops it and asks to update the app |
| `out_of_range` | outside the bounds below | drops it and warns in the Home Assistant log, naming the entity and not the value |
| `too_old` | older than 30 days while `history` is off | drops it and warns; the **Send history to phone** button explains the switch |
| `invalid` | a field missing or not a number, or a time more than 5 minutes in the future | drops it and warns |
| `rate_limited` | Health Connect's quota | keeps it and offers it again on the next request |
| `hc_unavailable` | Health Connect did not answer in time, or answered with an error | keeps it and offers it again on the next request |

Bounds, inclusive: weight 1 to 500 kg; height 0.3 to 2.8 m; body fat 1 to 80 %; lean body mass 1 to 300 kg; bone mass 0.1 to 30 kg; body water mass 1 to 300 kg; systolic 30 to 300 mmHg; diastolic 10 to 250 mmHg and below systolic. Exactly zero is always out of range.

#### Idempotence

The `id` becomes the record's `clientRecordId` and the `version` its `clientRecordVersion`, which Health Connect scopes to the writing app. Sending the same reading again is therefore harmless (the app acknowledges it without a write once it knows the ID at that version, and Health Connect ignores an equal or lower version anyway), and a correction with a higher version replaces the earlier record. The app cannot touch records other apps wrote. Records the app writes carry the app's own package as `source`, and the app leaves them out of its outgoing payloads and out of `deleted_records`, so what came from Home Assistant never goes back to it; `_diagnostics` counts them per type as `own_records_skipped`.

Three edge cases an integration should expect:

- **A lost reply from Health Connect.** When the insert succeeds but the confirmation arrives after the app's time budget, the records are in Health Connect while the app reports `hc_unavailable` and offers them again on the next request; the repeat is the same ID at the same version and changes nothing. Until that repeat, the app does not yet know those records as its own, so a deletion of one of them in between would appear in `deleted_records` with a uuid the integration never handed out; ignore uuids you do not know.
- **A forgotten ledger.** The app remembers the last 5000 readings it wrote, and forgets all of them when the source URL or the secret changes. A record beyond that is still the app's own in Health Connect (it never goes back out as a record), but a later deletion of it can appear in `deleted_records` for the same reason as above.
- **Blood pressure above 200 mmHg.** The bounds the app checks (30 to 300 systolic, 10 to 250 diastolic) are wider than Health Connect's own constructor limits (20 to 200 systolic, 10 to 180 diastolic). A reading in between passes the app's validation, Health Connect then refuses it, and the app reports `out_of_range` for that reading alone. Do not test the upper bounds with values above 200 and 180.

## Older app versions

A receiver that only talks to the current app can skip this section. Payloads that waited in an outbox, or phones that were not updated, can still come from older versions; `app_version` says which. [CHANGELOG.md](../CHANGELOG.md) has the full history.

| Versions | Difference |
|---|---|
| Before 1.14.0 | No bucketed series and no `_resolutions` |
| Before 1.17.0 | Backfill payloads carry no `daily_totals`; in 1.17.0 only the first payload of each window does |
| Before 1.18.0 | No `deleted_records`, `deletions_unavailable`, `sequence` or `window_complete` |
| 1.18.0 through 1.21.0 | Could name a record in `deleted_records` that exists again, and send one `uuid` as a record and as a deletion in the same payload. For these payloads, apply `deleted_records` before the records of the same payload, and let a record that arrives in a later payload restore a `uuid` that was deleted. |
| Before 1.20.0 | No `writeback` and no `own_records_skipped` |
| Before 1.21.0 | No `records_outside_window`, `read_from` or `lookback_gap_from`; Screen Time payloads carry no `sequence` |
| Before 1.22.0 | Test pings carry no `app_version` |
| Before 1.23.0 | No `metadata`, `app_filter` or `filtered_screen_time_minutes`, and no bucket carries `complete`. A window they sent again holds only the late records, so combine it with the stored window as described under [Data resolution](#data-resolution); a record they read again because its source wrote it again is counted twice by that rule, and nothing in the payload tells that case apart. In a backfill, the window that straddled each 3-day chunk bound went out with only its part after the bound, and a measured series with a backlog could lose the samples of the window its chunk ended in. |

<a id="home-assistant-webhook"></a>

## Home Assistant and other backends

The [Life Dashboard integration](https://github.com/owen282000/life-dashboard-ha) is a receiver for Home Assistant that pairs by QR code and keeps the history in long-term statistics. Home Assistant's own webhook trigger can also take the payload into an automation. For n8n and Node-RED, see [n8n and Node-RED](#n8n-and-node-red). For sensors without any server-side wiring, use the built-in MQTT publishing with Home Assistant Discovery instead; see [features.md](features.md#home-assistant-and-mqtt).
