# Keystore Setup for Release Builds

## Generate Keystore

Generate a new keystore for signing release APKs:

```bash
keytool -genkey -v -keystore keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias life-dashboard -storepass YOUR_PASSWORD -keypass YOUR_PASSWORD
```

Replace `YOUR_PASSWORD` with a strong password. Keep the keystore file secure.

## Configure GitHub Actions

Add these GitHub Secrets for CI/CD signing:

1. **KEYSTORE_FILE_BASE64**: Base64-encoded keystore (workflow decodes this)
2. **KEYSTORE_PASSWORD**: Password for the keystore
3. **KEY_ALIAS**: Key alias (e.g., `life-dashboard`)
4. **KEY_PASSWORD**: Password for the key

### Export keystore as base64:

```bash
base64 -w 0 keystore.jks > keystore.b64
cat keystore.b64
```

Copy the output to GitHub Secrets as `KEYSTORE_FILE_BASE64`.

### Add secrets via GitHub UI:

1. Go to **Settings → Secrets and variables → Actions**
2. Add each secret with exact names above
3. Do NOT commit `keystore.jks` to git (add to .gitignore)

## Build release APK locally

With keystore in project root:

```bash
export KEYSTORE_PATH=./keystore.jks
export KEYSTORE_PASSWORD=your_password
export KEY_ALIAS=life-dashboard
export KEY_PASSWORD=your_password

./gradlew assembleRelease
```

Signed APK: `app/build/outputs/apk/release/app-release.apk`

## CI/CD Release Build

Push a version tag to trigger release build:

```bash
git tag v1.2.3
git push origin v1.2.3
```

GitHub Actions will:
1. Decrypt keystore from base64
2. Build release APK with signing
3. Scan for security issues
4. Upload APK as artifact
