# Security Policy

## Supported Versions

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

A different fingerprint means the APK was not signed by this project, whatever the file is called. F-Droid pins the same value as `AllowedAPKSigningKeys`.

**The provenance attestation** links the APK to the workflow run and the commit that produced it. With the [GitHub CLI](https://cli.github.com/):

```bash
gh attestation verify app-release.apk --owner owen282000
```

It exits quietly with status 0 when the APK is genuine and fails when the file was modified or came from somewhere else. To see what it actually proves, ask for the details:

```bash
gh attestation verify app-release.apk --owner owen282000 --format json
```

For release 1.14.0 that names `.github/workflows/release.yml`, the tag `refs/tags/1.14.0` and commit `619dfb30f257362c26d842cc803ea402046fcd3c`, which is the same commit F-Droid builds from.

The `app-release.apk.sigstore.json` published next to each APK is the same attestation for checking without a network round trip:

```bash
gh attestation verify app-release.apk --owner owen282000 --bundle app-release.apk.sigstore.json
```

## Reporting a Vulnerability

Please do not open a public issue for security vulnerabilities.

Instead, use [GitHub's private vulnerability reporting](https://github.com/owen282000/life-dashboard-companion-app/security/advisories/new) for this repository. You will get a response as soon as possible, and a fix will be prioritized based on severity.

Since this app handles health and app usage data, reports about the following are especially welcome:

- Leaking health or screen time data to anywhere other than the user-configured webhooks
- Weaknesses in the HMAC signing implementation
- Insecure storage of secrets or payloads on the device
