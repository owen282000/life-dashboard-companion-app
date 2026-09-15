#!/usr/bin/env bash
#
# Prepares a release for tagging: writes version.properties and generates the
# per-version store changelog, both of which the release workflow verifies
# against the tag being built.
#
# Usage: scripts/prepare-release.sh 1.12.1
#
set -euo pipefail
cd "$(dirname "$0")/.."

V="${1:?usage: prepare-release.sh <version X.Y.Z>}"
[[ "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Not strict semver: $V" >&2; exit 1; }

IFS=. read -r MA MI PA <<< "$V"
CODE=$((MA * 10000 + MI * 100 + PA))

cat > version.properties << PROPS
# Literal version values for tools that cannot evaluate Gradle, most notably
# F-Droid's checkupdates (UpdateCheckData): the build itself derives these
# from the git tag, so nothing else in the repo states them literally.
# Kept in sync by scripts/prepare-release.sh; the release workflow fails the
# tag when this file does not match it.
VERSION_NAME=$V
VERSION_CODE=$CODE
PROPS

./scripts/generate-fastlane-changelogs.sh "$V"
./scripts/update-changelog-links.sh
echo "version.properties -> $V ($CODE)"
echo "Now commit, then: git tag $V && git push --tags"
