# Keystore setup for signed release builds

The release workflow (`.github/workflows/release.yml`) builds a signed APK when you push a version tag. It needs a keystore, delivered via GitHub Secrets. This is a one-time setup.

## 1. Generate a keystore

```bash
keytool -genkey -v -keystore keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias life-dashboard
```

You will be prompted for a keystore password. Modern `keytool` creates a PKCS12 keystore, which does not support a key password that differs from the store password; a separate `-keypass` is ignored, with only a warning. Use the store password for both `KEYSTORE_PASSWORD` and `KEY_PASSWORD`. Use a strong password and store it in a password manager. Keep `keystore.jks` somewhere safe outside the repository: losing it means you can never publish an update that existing installs accept.

`keystore.jks` and `*.jks` are gitignored, but double-check you never commit it.

## 2. Add GitHub secrets

Go to **Settings > Secrets and variables > Actions** and add:

| Secret | Value |
|---|---|
| `KEYSTORE_FILE_BASE64` | Base64 of the keystore file (see below) |
| `KEYSTORE_PASSWORD` | The keystore password |
| `KEY_ALIAS` | `life-dashboard` (or the alias you chose) |
| `KEY_PASSWORD` | The same password as the keystore (see above) |

Encode the keystore on macOS:

```bash
base64 -i keystore.jks | tr -d '\n' | pbcopy
```

The base64 string is now on your clipboard; paste it into `KEYSTORE_FILE_BASE64`.

## 3. Cut a release

With the secrets in place, a pushed version tag produces a signed release. The git tag is the version: Gradle derives `versionName` and `versionCode` from it. Before tagging, `scripts/prepare-release.sh` writes the same version into `version.properties` and drafts the store release note, and the workflow refuses a tag those files do not match. Tags have no `v` prefix (`1.23.0`, not `v1.23.0`).

[releasing.md](releasing.md) has the full steps, from the changelog entry to the pushed tag.

The workflow validates the tag (strict `X.Y.Z`, higher than the previous release), builds and signs the APK, and creates a GitHub release with the APK attached. Its release notes are the tag's section of `CHANGELOG.md`, followed by the list of changes GitHub generates.

## Local signed build (optional)

```bash
export KEYSTORE_PATH=/path/to/keystore.jks
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=life-dashboard
export KEY_PASSWORD=...
./gradlew assembleRelease
```

Without these environment variables, `assembleRelease` still works but produces an unsigned APK (`app-release-unsigned.apk`).
