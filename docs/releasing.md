# Releasing

For the maintainer: how a version is tagged, built, signed and published. Building and testing the app is in [building.md](building.md); the one-time signing setup is in [KEYSTORE_SETUP.md](KEYSTORE_SETUP.md).

## Version tags

The git tag is the version: `app/build.gradle.kts` derives `versionName` and `versionCode` from it at build time (see [building.md](building.md#versions-come-from-git-tags)). Tags are strict semver without a prefix (`1.23.0`, not `v1.23.0`) and must be higher than the previous one.

To catch a bad tag before it reaches CI, enable the repository's pre-push hook once per clone:

```bash
git config core.hooksPath .githooks
```

## Cutting a release

1. Add a `## [X.Y.Z]` section to [CHANGELOG.md](../CHANGELOG.md), moving the entries from `## [Unreleased]`.
2. Prepare the release files and commit them:

   ```bash
   scripts/prepare-release.sh 1.23.0
   ```

   This writes `version.properties`, drafts the store release note for that version and updates the version links at the bottom of the changelog.

3. Tag and push:

   ```bash
   git tag 1.23.0
   git push origin 1.23.0
   ```

The tag starts the [release workflow](../.github/workflows/release.yml). It checks that the tag is strict semver and higher than the last one, runs the hardcoded-strings check, the unit tests and Android Lint, builds and signs the APK, and attests its build provenance. It then creates the GitHub release with the APK, the provenance bundle (`app-release.apk.sigstore.json`) and the R8 mapping (`mapping-X.Y.Z.txt`) attached. The release notes are the tag's section of `CHANGELOG.md`, followed by the list of changes GitHub generates.

## Files that must match the tag

The workflow fails the release when `version.properties` does not match the tag, or when the store release note for the tag is missing:

- **`version.properties`** holds the version as plain values (`VERSION_NAME`, `VERSION_CODE`). The Gradle build does not need it on a tagged checkout, but F-Droid's update checker cannot run Gradle and reads this file instead. It is also the fallback for a clone without tags.
- **`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`** is the short release note that F-Droid and Play show. When it does not exist yet, `prepare-release.sh` writes a first draft from the version's section in `CHANGELOG.md` (its Highlights, or else its first list), kept under Play's 500-character limit with a link to the full changelog. Edit the draft before you commit it; an existing file is never overwritten.

The versionCode is `major * 10000 + minor * 100 + patch`, the same formula as `app/build.gradle.kts`.

## Release builds and R8

`assembleRelease` runs R8 with resource shrinking (`isMinifyEnabled` and `isShrinkResources` in `app/build.gradle.kts`). The keep rules live in `app/proguard-rules.pro`: the MQTT stack (the HiveMQ client and the Netty it bundles) and kotlinx.serialization resolve classes by reflection, enum names are stored in preferences, and WorkManager's Room database is instantiated by name, so those are kept whole.

R8 renames classes, so a stack trace from a release build is unreadable without the mapping. Every release build writes it to `app/build/outputs/mapping/release/mapping.txt`, and the release workflow attaches it to the GitHub release as `mapping-X.Y.Z.txt`.

Without the `KEYSTORE_PATH` variables (see [KEYSTORE_SETUP.md](KEYSTORE_SETUP.md#local-signed-build-optional)) the build produces `app-release-unsigned.apk`. For a quick device test of a release build, sign it with the debug key:

```bash
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --out app-release-debugsigned.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

## The Play build

The planned Google Play listing is paid, so its build leaves out the Ko-fi row in **About**. It is a build type of its own, `play`, made from the release build with the same application ID and signing key. `./gradlew bundlePlay` makes the bundle for Play. The GitHub release and the F-Droid recipe use `assembleRelease` and are not affected.
