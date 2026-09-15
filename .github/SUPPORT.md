# Getting help

GitHub shows this page when you open a new issue. Most questions have a faster answer than
waiting for a reply.

## Setting it up

[docs/usage.md](../docs/usage.md) covers installation, the first-run wizard, and getting data
into Home Assistant in about two minutes. [docs/features.md](../docs/features.md) lists what
the app can do, and [docs/webhook.md](../docs/webhook.md) documents every field it sends.

## Something is not working

The [troubleshooting section](../docs/usage.md#troubleshooting) answers the questions that
come up most: syncs stopping after a while (almost always the manufacturer's battery
optimisation), plain HTTP being refused, step counts that look far too high, and nightly
metrics arriving hours late. The Logs tab in the app shows every delivery attempt with the
server's answer, which is usually enough to tell what went wrong.

## Still stuck, or want to ask something

[Discussions](https://github.com/owen282000/life-dashboard-companion-app/discussions) is the
place for questions, setup help and ideas. It is easier to have a conversation there, and the
answer stays findable for whoever asks next.

## Reporting a bug

If something is genuinely broken, open an
[issue](https://github.com/owen282000/life-dashboard-companion-app/issues/new/choose). The
template asks which app wrote the data, whether you use a webhook or MQTT, and where you
installed from, because those three answers usually determine the cause.

Security problems go through
[private advisories](https://github.com/owen282000/life-dashboard-companion-app/security/advisories/new)
instead, never a public issue. See [SECURITY.md](../SECURITY.md).
