# Security policy

Life Dashboard Companion moves health data from your phone to servers you run. This page explains how to [check that an APK is genuine](#verifying-a-release), and how to [report a security problem](#reporting-a-vulnerability).

## Supported versions

Only the latest release receives security fixes.

| Version | Supported |
| ------- | --------- |
| Latest release | Yes |
| Older versions | No |

## Verifying a release

Every release APK is signed with the same key and carries a [sigstore](https://www.sigstore.dev/) provenance attestation proving it was built by this repository's release workflow from a specific commit. Both are worth checking for an app that asks you to trust it with health data.

**The signing certificate** has this SHA-256 fingerprint, and it does not change between releases:

```
27:14:06:D5:BA:F7:90:50:6E:91:4D:82:AC:A2:53:33:36:AE:08:3D:01:C7:9F:BA:CB:00:15:F4:2E:4F:F6:1F
```

Compare it against a downloaded APK with `apksigner` from the Android SDK build tools:

```bash
apksigner verify --print-certs app-release.apk | grep "SHA-256 digest"
```

That prints the same value without separators and in lowercase, which is the other common way of writing it:

```
271406d5baf790506e914d82aca2533336ae083d01c79fbacb0015f42e4ff61f
```

A different fingerprint means the APK was not signed by this project, whatever the file is called. The F-Droid submission, which is still in review, pins the same value as `AllowedAPKSigningKeys`.

**The provenance attestation** links the APK to the workflow run and the commit that produced it. With the [GitHub CLI](https://cli.github.com/):

```bash
gh attestation verify app-release.apk --repo owen282000/life-dashboard-companion-app
```

Use `--repo` rather than `--owner`: the owner form accepts an attestation from any repository under the account, so an APK built by a different project of the same owner would pass.

When the APK is genuine, it prints `Verification succeeded!` with the workflow and the tag that built it, and exits with status 0. It fails when the file was modified or came from somewhere else. For the full record, including the commit, ask for JSON:

```bash
gh attestation verify app-release.apk --repo owen282000/life-dashboard-companion-app --format json
```

The output names the workflow (`.github/workflows/release.yml`), the tag the release was built from and the commit behind that tag. For 1.23.0, for example, that is `refs/tags/1.23.0` and commit `81c6b74d7fd1a619b7d08c582c3cf94a17e3586e`. Once the app is listed on F-Droid, F-Droid builds from that same tagged commit.

The `app-release.apk.sigstore.json` published next to each APK is the same attestation for checking without a network round trip:

```bash
gh attestation verify app-release.apk --repo owen282000/life-dashboard-companion-app --bundle app-release.apk.sigstore.json
```

## Reporting a vulnerability

Please do not open a public issue for a security vulnerability.

Report it through [GitHub's private vulnerability reporting](https://github.com/owen282000/life-dashboard-companion-app/security/advisories/new) for this repository. If you do not have a GitHub account, email owenvogelaar@hotmail.com instead, with "Security" in the subject.

Useful in a report: the app version, the Android version, what an attacker needs (another app on the phone, a position on the network, access to the receiving server), and the steps to reproduce it.

### What happens next

- You get a reply within 7 days, confirming the report and saying whether it is in scope.
- The target for a fix depends on severity: a release within 14 days for a problem that exposes health data or secrets, within 30 days for a moderate problem, and in a regular release for a minor one. If a fix needs longer, you hear why and when to expect it.
- The fix is published as a [GitHub security advisory](https://github.com/owen282000/life-dashboard-companion-app/security/advisories) once the release is out, with a CVE where one applies.
- You are credited in the advisory and the changelog by the name you choose, unless you prefer not to be named.

The project has one maintainer and no bug bounty.

## Scope

The app handles health and app usage data, so reports about any of these are especially welcome:

- **Data leaving for the wrong place.** Health or screen time data reaching anything other than the webhooks and the MQTT broker the user configured, or a Screen Time app that was filtered out leaving the phone.
- **Pairing links and App Links.** The `https://owen282000.github.io/life-dashboard-companion-app/pair` App Link and the `lifedashboard://pair` link: a link that changes a destination without the confirmation dialog, switches on a data type, or leaks the secret in the link.
- **The exported sync receiver.** `SyncBroadcastReceiver` is exported without a permission on purpose, so automation apps such as Tasker can start a sync. It should do nothing more than start a sync to the configured destinations, at most once a minute. Anything more is in scope.
- **Receive.** The path that writes measurements from Home Assistant into Health Connect: a response accepted without a valid signature, a replayed response, a write of a type the user did not switch on, or a change to a record another app wrote.
- **Stored secrets.** Auth headers, HMAC signing keys and MQTT passwords, which are encrypted with an Android Keystore key and kept out of Android's backup; the encrypted settings export; and secrets or health data ending up in the system log.
- **Transport.** Webhook TLS, the plain HTTP opt-in, client certificates (mTLS), MQTT over TLS, and the HMAC request signature.

Out of scope: problems that need a rooted phone or a malicious app with permissions the user granted to it, and the receiving side. The Home Assistant integration has its own policy in [owen282000/life-dashboard-ha](https://github.com/owen282000/life-dashboard-ha/security/policy), and the iOS app in [owen282000/life-dashboard-companion-app-ios](https://github.com/owen282000/life-dashboard-companion-app-ios/security/policy).
