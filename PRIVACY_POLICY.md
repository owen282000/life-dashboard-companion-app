# Privacy Policy

Life Dashboard Companion is built on a simple principle: your data is yours. This policy describes what the app does with data, which is deliberately as little as possible.

## What the app collects

Nothing. The app contains no analytics, no crash reporting, no advertising, and no third-party SDKs. The developer receives no data from the app, ever.

## What the app reads on your device

- **Health data** from Google Health Connect, but only the data types you explicitly enable and grant permission for.
- **App usage statistics** (Screen Time), only if you grant the Usage Access permission.

## Where data goes

Data is sent exclusively to the webhook URLs you configure yourself. If you configure no webhooks, no data leaves your device. The developer has no access to your endpoints or your data.

Delivery details:

- Payloads are sent over the connection you specify; HTTPS endpoints are strongly recommended.
- You can optionally configure an HMAC signing secret so your server can verify that payloads genuinely come from your device.
- Recent payloads are kept on-device in the webhook logs so you can inspect what was sent; you can clear these at any time.

## What is stored on your device

Your configuration (webhook URLs, headers, signing secrets, enabled data types, sync settings) and webhook logs are stored locally in the app's private storage. They are never uploaded anywhere by the app.

## Permissions

- Health Connect read permissions: one per data type you enable, used only to read that data for syncing.
- Usage access: to read screen time statistics.
- Internet: to deliver payloads to your webhooks.
- Background access: so scheduled syncs work when the app is closed.

You can revoke any permission at any time in Android settings; the corresponding feature simply stops working.

## Changes

Changes to this policy are made via public commits to this repository, so the full history is auditable.

## Contact

Questions: open an issue at https://github.com/owen282000/life-dashboard-companion-app/issues or use the contact options in the repository.
