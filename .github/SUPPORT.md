# Getting help

GitHub shows this page when you open a new issue. Most questions are answered faster by the pages below than by waiting for a reply.

## Setting it up

The [setup guide](../docs/usage.md) covers installation, permissions, and getting data into Home Assistant, a webhook or MQTT step by step. [docs/features.md](../docs/features.md) lists what the app can do, and [docs/webhook.md](../docs/webhook.md) documents every field it sends.

## Something is not working

The [troubleshooting section](../docs/usage.md#troubleshooting) answers the questions that come up most: a sync that sends nothing, syncs stopping after a while (almost always the phone's battery optimization), where the sensors are in Home Assistant, plain HTTP being refused, and nightly metrics arriving hours late. The **Logs** tab in the app shows every delivery attempt with the server's response, which is usually enough to tell what went wrong.

## Still stuck, or want to ask something

[Discussions](https://github.com/owen282000/life-dashboard-companion-app/discussions) is the place for questions, setup help and ideas. It is easier to have a conversation there, and the answer stays findable for whoever asks next.

## Reporting a bug

If something is broken, open an [issue](https://github.com/owen282000/life-dashboard-companion-app/issues/new/choose). The template asks which app wrote the data, whether the data goes to the integration, MQTT or a webhook, and where you installed from, because those three answers usually point to the cause.

A problem inside Home Assistant itself, such as a sensor or a statistic of the Life Dashboard integration, goes to the [integration's issues](https://github.com/owen282000/life-dashboard-ha/issues). The iOS app has [its own](https://github.com/owen282000/life-dashboard-companion-app-ios/issues).

Security problems go through [private advisories](https://github.com/owen282000/life-dashboard-companion-app/security/advisories/new) instead, never a public issue. See [SECURITY.md](../SECURITY.md).
