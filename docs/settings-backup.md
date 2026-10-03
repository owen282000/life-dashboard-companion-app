# Settings backup and restore

Export your configuration to a file and import it on another device. Find it under **About > Backup & restore**.

## Why this exists

Webhook auth headers, HMAC signing secrets and MQTT passwords are stored encrypted with a key that never leaves the device, and both the encrypted store and the webhook logs are deliberately excluded from Android's cloud backup. That keeps credentials and raw health data off Google's servers, but it also means a phone-to-phone transfer will not carry them. This export is the supported way to move a setup.

## What is included

| Included | Not included |
|---|---|
| Webhook URLs, per-section | Sync watermarks (last-sync timestamps), client certificate choice |
| Custom headers, and which URLs get none (the ones QR pairing added) | Webhook logs and raw payloads |
| HMAC signing secrets | Lifetime statistics |
| Sync intervals | Health Connect permissions |
| MQTT brokers, topics and switches | Usage access permission |
| The 33 data-type toggles | Which entities Home Assistant sends (that choice lives in the integration) |
| Daily totals, plain HTTP, full payloads, day boundary, failure threshold | The Receive ledger (which readings were written, and their Health Connect ids) |
| Receive: the switch, the types, "Accept older measurements" and the source URL | |
| Phone name (MQTT) | |

Sync state is left out on purpose. Those watermarks describe how far *this* install has read from Health Connect; restoring them on another device would make the next sync skip everything written before the imported timestamp. After an import the new device syncs from its own starting point. The Receive ledger stays behind for the same reason: on a new phone the integration offers again what was not acknowledged, and since every reading is an upsert on its own id that is harmless. The write permissions are Android's and are asked for again per type.

The client certificate lives in Android's credential store and never leaves it, so an export could only carry its name, which means nothing on another phone. Install the certificate on the new device and pick it again under Advanced settings. Android's own backup and device transfer do carry the name along with the other settings; until you pick the certificate again, the webhook log says it is unavailable and no webhook is sent.

Permissions are granted by Android, not by the app, so you still grant Health Connect access and usage access on the new device.

## Exporting

1. Open **About > Backup & restore > Export**
2. Choose whether to **include secrets**
3. When secrets are included, enter a password of at least 8 characters, and once more to repeat it
4. Share or save the file through the Android share sheet

**With secrets** the file is encrypted with AES-256-GCM under a key derived from your password (PBKDF2-HMAC-SHA256, 210,000 iterations). It is saved as `life-dashboard-config.encrypted.json`. There is no recovery if you lose the password: without it the file cannot be decrypted, which is why Export stays off until the two password fields match. A long password, such as four random words, is best.

**Without secrets** the file is plain JSON (`life-dashboard-config.json`) holding URLs, MQTT hosts, topics and options, but no passwords, headers or HMAC secrets. It still contains your webhook URLs, and a URL can be a credential in itself: a Home Assistant `/api/webhook/<id>` address accepts anything posted to it. Share the file only with someone you would give that access to.

## Importing

1. Open **About > Backup & restore > Import**
2. Pick the file
3. Enter the password when the file is encrypted
4. Check the preview, which lists what will be replaced
5. Confirm

An import replaces your current configuration, so the preview shows the webhook counts, data types and broker count first, and below them what the file leaves as it is. A setting the file does not mention keeps the value on the device; it is never reset to its default because a key is missing. A file without secrets keeps the credentials already on the device rather than clearing them, so you can import a shared setup and fill in your own tokens. A broker keeps its username and password only when the file points at the same broker, meaning the same host, port and TLS setting; otherwise they are left empty, so they never go to a server they were not set for, or out in plain text where they had TLS.

Custom headers follow the same rule. A file with headers restores them together with its own list of URLs that get none. A file without them keeps the headers on the device, and those go only to the URLs they went to before the import: a URL that is new to the device, or one that QR pairing added there, gets none of them.

Reopen the app after importing so every screen reads the new values.

## File format

Plain exports are readable JSON:

```json
{
  "version": 1,
  "exported_at": "2026-09-13T12:00:00Z",
  "app_version": "1.12.0",
  "health": {
    "webhook_urls": ["https://example.com/health", "https://ha.example.com/api/webhook/abc"],
    "headers": {},
    "signing_secret": null,
    "sync_interval_minutes": 60,
    "urls_without_headers": ["https://ha.example.com/api/webhook/abc"]
  },
  "screen_time": { "webhook_urls": [], "headers": {}, "sync_interval_minutes": 60 },
  "mqtt": {
    "shared": { "host": "mqtt.local", "port": 1883, "use_tls": false },
    "health_enabled": true,
    "health_base_topic": "lifedash/health"
  },
  "options": {
    "enabled_data_types": ["STEPS", "HEART_RATE"],
    "include_daily_totals": true,
    "allow_http_webhooks": false,
    "phone_name": "Pixel 8",
    "receive_enabled": true,
    "receive_types": ["weight", "blood_pressure"],
    "receive_older_measurements": false,
    "receive_source_url": "https://example.com/health"
  }
}
```

`urls_without_headers` lists the webhook URLs of that section that QR pairing added, which get none of its custom headers. A backup written before this list existed has none, and imports as it always did: the app sent the headers to every URL then.

`receive_source_url` is only applied when it is one of the phone's health webhook URLs after the import: the file's own when it has `webhook_urls`, otherwise the ones already on the phone. Any other URL clears the source. A backup written before 1.20.0 has none of the `phone_name` and `receive_*` keys, and importing it leaves the phone name, the Receive switches and the ledger as they are.

A key that is absent leaves its setting as it is on import, and so does a whole section: a file without `screen_time` keeps the Screen Time webhooks, and one without `mqtt` keeps every broker. Unknown keys are ignored on import, so a file from a newer version still restores what the installed build understands. Data types are stored by name, and names this build does not know are skipped rather than failing the import.

Encrypted exports wrap the same JSON in an envelope that records the parameters needed to decrypt it:

```json
{
  "type": "life-dashboard-encrypted-config",
  "version": 1,
  "kdf": "PBKDF2WithHmacSHA256",
  "iterations": 210000,
  "salt": "<base64>",
  "iv": "<base64>",
  "ciphertext": "<base64>"
}
```

Salt and IV are random per export, so exporting the same settings twice produces different files. The GCM authentication tag means a wrong password or an edited file is rejected outright instead of producing garbage.

The iteration count comes from the file, so it is bounded: a file asking for fewer than 100,000 or more than 2,000,000 is refused before any key is derived, as on the iPhone. Without the ceiling a crafted file could keep the import busy for minutes; without the floor it could ask for a key that is cheap to guess.

## Files from the iPhone app

The [iOS app](https://github.com/owen282000/life-dashboard-companion-app-ios) writes the same format and encrypts it the same way, so a file moves between the two apps in either direction. Its files carry `"platform": "ios"`, which this app does not write but reads to tell the two apart, and `failure_notifications_enabled`, the failure notification switch, which this app has too but does not back up yet. This app ignores that one, like any key it does not know, so its own switch stays as it is.

An iPhone file has no Screen Time section, no Screen Time MQTT keys and none of the Android-only options (full payloads, the day boundary, resolutions, Receive). Like any key a file leaves out, they keep the values on the phone, so importing one on a phone that also syncs Screen Time leaves that setup as it is. The preview says so.

Two values in an iPhone file name the iPhone, and this app does not take them over, the way the iPhone app treats a file from here:

- **The MQTT base topic** `lifedashboard-ios`, the iPhone app's default. Taking it would put this phone's sensors on the iPhone's in Home Assistant, so the phone keeps its own topic. A topic the iPhone user chose is copied as it is.
- **The phone name** (`phone_name`), which names the iPhone. This phone keeps its own name.

The preview lists both when the file has them.

The data types in an iPhone file are the ones enabled on the iPhone, so the file decides only the types the iPhone app has: those are switched on or off as the file says. The types it does not have (bone mass, body water mass, basal metabolic rate and skin temperature) keep their state on this phone. The preview counts the types the phone ends up with and says this. Receive and the series resolutions are not in an iPhone file at all, so they stay as they are too. What carries over the other way is in the iOS app's [settings-backup.md](https://github.com/owen282000/life-dashboard-companion-app-ios/blob/main/docs/settings-backup.md#moving-between-android-and-iphone).

## Keeping an export safe

An export with secrets grants full access to your webhook endpoints and MQTT broker. Treat the file like a password: prefer a strong password, avoid leaving it in a chat thread or a shared drive, and delete it once the new device is set up. When you only need to move non-secret settings, export without secrets instead.
