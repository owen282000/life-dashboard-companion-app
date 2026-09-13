# Settings backup and restore

Export your configuration to a file and import it on another device. Find it under **About > Backup & restore**.

## Why this exists

Webhook auth headers, HMAC signing secrets and MQTT passwords are stored encrypted with a key that never leaves the device, and both the encrypted store and the webhook logs are deliberately excluded from Android's cloud backup. That keeps credentials and raw health data off Google's servers, but it also means a phone-to-phone transfer will not carry them. This export is the supported way to move a setup.

## What is included

| Included | Not included |
|---|---|
| Webhook URLs, per-section | Sync watermarks (last-sync timestamps) |
| Custom headers | Webhook logs and raw payloads |
| HMAC signing secrets | Lifetime statistics |
| Sync intervals | Health Connect permissions |
| MQTT brokers, topics and switches | Usage access permission |
| The 33 data-type toggles | |
| Daily totals, plain HTTP, full payloads, day boundary, failure threshold | |

Sync state is left out on purpose. Those watermarks describe how far *this* install has read from Health Connect; restoring them on another device would make the next sync skip everything written before the imported timestamp. After an import the new device syncs from its own starting point.

Permissions are granted by Android, not by the app, so you still grant Health Connect access and usage access on the new device.

## Exporting

1. Open **About > Backup & restore > Export**
2. Choose whether to **include secrets**
3. When secrets are included, enter a password
4. Share or save the file through the Android share sheet

**With secrets** the file is encrypted with AES-256-GCM under a key derived from your password (PBKDF2-HMAC-SHA256, 210,000 iterations). It is saved as `life-dashboard-config.encrypted.json`. There is no recovery if you lose the password: without it the file cannot be decrypted.

**Without secrets** the file is plain JSON (`life-dashboard-config.json`) holding URLs, MQTT hosts, topics and options but no credentials. This is the one to share when you want to hand someone your setup without handing over access to your endpoints.

## Importing

1. Open **About > Backup & restore > Import**
2. Pick the file
3. Enter the password when the file is encrypted
4. Check the preview, which lists what will be replaced
5. Confirm

An import replaces your current configuration, so the preview shows the webhook counts, data types and broker count first. A file without secrets keeps the credentials already on the device rather than clearing them, so you can import a shared setup and fill in your own tokens.

Reopen the app after importing so every screen reads the new values.

## File format

Plain exports are readable JSON:

```json
{
  "version": 1,
  "exported_at": "2026-09-13T12:00:00Z",
  "app_version": "1.12.0",
  "health": {
    "webhook_urls": ["https://example.com/health"],
    "headers": {},
    "signing_secret": null,
    "sync_interval_minutes": 60
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
    "allow_http_webhooks": false
  }
}
```

Unknown keys are ignored on import, so a file from a newer version still restores what the installed build understands. Data types are stored by name, and names this build does not know are skipped rather than failing the import.

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

## Keeping an export safe

An export with secrets grants full access to your webhook endpoints and MQTT broker. Treat the file like a password: prefer a strong password, avoid leaving it in a chat thread or a shared drive, and delete it once the new device is set up. When you only need to move non-secret settings, export without secrets instead.
