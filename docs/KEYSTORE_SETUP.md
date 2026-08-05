# Keystore Setup for Signed Release Builds

The release workflow (`.github/workflows/release.yml`) builds a signed APK when you push a version tag. It needs a keystore, delivered via GitHub Secrets. This is a one-time setup.

## 1. Generate a keystore

```bash
keytool -genkey -v -keystore keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias life-dashboard
```

You will be prompted for a keystore password. Note: modern `keytool` creates a PKCS12 keystore, which does not support a key password that differs from the store password; a separate `-keypass` is silently ignored. Use the store password for both `KEYSTORE_PASSWORD` and `KEY_PASSWORD`. Use a strong password and store it in a password manager. Keep `keystore.jks` somewhere safe outside the repository: losing it means you can never publish an update that existing installs accept.

`keystore.jks` and `*.jks` are gitignored, but double-check you never commit it.

## 2. Add GitHub Secrets

Go to **Settings > Secrets and variables > Actions** and add:

| Secret | Value |
|---|---|
| `KEYSTORE_FILE_BASE64` | Base64 of the keystore file (see below) |
| `KEYSTORE_PASSWORD` | The keystore password |
| `KEY_ALIAS` | `life-dashboard` (or the alias you chose) |
| `KEY_PASSWORD` | The key password |

Encode the keystore on macOS:

```bash
base64 -i keystore.jks | tr -d '\n' | pbcopy
```

The base64 string is now on your clipboard; paste it into `KEYSTORE_FILE_BASE64`.

## 3. Cut a release

The git tag is the single source of truth for the version: Gradle derives `versionName` and `versionCode` from the latest semver tag, so there is nothing to bump in any file. Tags follow the existing convention without a `v` prefix (`1.3.0`, not `v1.3.0`).

```bash
git tag 1.3.0
git push origin 1.3.0
```

The workflow validates the tag (strict `X.Y.Z`, higher than the previous release), builds the signed APK, uploads it as a workflow artifact, and creates a GitHub Release with the APK attached and auto-generated release notes. The "Download APK" badge in the README always points to the latest release.

To catch bad tags before they reach CI, enable the repo's pre-push hook once per clone:

```bash
git config core.hooksPath .githooks
```

## Local signed build (optional)

```bash
export KEYSTORE_PATH=/path/to/keystore.jks
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=life-dashboard
export KEY_PASSWORD=...
./gradlew assembleRelease
```

Without these environment variables, `assembleRelease` still works but produces an unsigned APK (`app-release-unsigned.apk`).
