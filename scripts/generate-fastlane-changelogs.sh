#!/usr/bin/env bash
#
# Generates fastlane/metadata/android/en-US/changelogs/<versionCode>.txt from CHANGELOG.md.
#
# F-Droid and Google Play both read per-version changelogs from that directory, named after the
# app's versionCode. The versionCode is derived from the semver tag exactly as app/build.gradle.kts
# does it (major * 10000 + minor * 100 + patch), so the two never drift apart.
#
# Play truncates release notes at 500 characters, so entries are trimmed to fit with a pointer to
# the full changelog rather than being cut mid-sentence.
#
# Usage:
#   scripts/generate-fastlane-changelogs.sh            # every released version in CHANGELOG.md
#   scripts/generate-fastlane-changelogs.sh 1.11.0     # just this one
#
set -euo pipefail

cd "$(dirname "$0")/.."

CHANGELOG="CHANGELOG.md"
OUT_DIR="fastlane/metadata/android/en-US/changelogs"
MAX_CHARS=500

[ -f "$CHANGELOG" ] || { echo "No $CHANGELOG found" >&2; exit 1; }
mkdir -p "$OUT_DIR"

version_code() {
    echo "$1" | awk -F. '{ print $1 * 10000 + $2 * 100 + $3 }'
}

# Body of one "## [X.Y.Z]" section, stripped of headings and blank padding.
section_body() {
    awk -v tag="$1" '
        $0 ~ "^## \\[" tag "\\]" { found = 1; next }
        /^## \[/ { found = 0 }
        found { print }
    ' "$CHANGELOG"
}

# CHANGELOG.md uses "### Added" / "### Fixed" headings and "- " bullets. Play and F-Droid render
# plain text, so headings become a bare label line and markdown decoration is stripped.
to_plain_text() {
    sed -e 's/^### \(.*\)$/\1:/' \
        -e 's/`//g' \
        -e 's/\*\*//g' \
        -e 's/\[\([^]]*\)\](\([^)]*\))/\1/g' \
      | cat -s \
      | sed -e '/./,$!d'
}

trim_to_limit() {
    local text="$1"
    if [ "${#text}" -le "$MAX_CHARS" ]; then
        printf '%s\n' "$text"
        return
    fi
    local notice="... full changelog: https://github.com/owen282000/life-dashboard-companion-app/blob/main/CHANGELOG.md"
    local budget=$(( MAX_CHARS - ${#notice} - 1 ))

    # Keep whole lines while they fit. A bullet that alone exceeds the budget is cut at a
    # word boundary rather than dropped, so the entry is never empty.
    printf '%s\n' "$text" | awk -v budget="$budget" '
        function emit(line) { print line; total += length(line) + 1 }
        {
            if ($0 == "") { if (total > 0 && total + 1 <= budget) emit(""); next }
            if (total + length($0) + 1 <= budget) { emit($0); next }
            room = budget - total - 4
            if (room > 40 && substr($0, 1, 2) == "- ") {
                cut = substr($0, 1, room)
                sub(/[^ ]*$/, "", cut)
                sub(/[ ,;:]+$/, "", cut)
                if (length(cut) > 20) emit(cut " ...")
            }
            exit
        }
    '
    printf '%s\n' "$notice"
}

write_one() {
    local version="$1"
    local code
    code="$(version_code "$version")"

    local body
    body="$(section_body "$version" | to_plain_text)"

    if [ -z "$(printf '%s' "$body" | tr -d '[:space:]')" ]; then
        echo "skip $version (no changelog section)"
        return
    fi

    trim_to_limit "$body" > "$OUT_DIR/$code.txt"
    echo "wrote $OUT_DIR/$code.txt ($version, $(wc -c < "$OUT_DIR/$code.txt" | tr -d ' ') bytes)"
}

if [ $# -ge 1 ]; then
    write_one "$1"
    exit 0
fi

# Every released version in the changelog; "Unreleased" has no versionCode yet.
grep -oE '^## \[[0-9]+\.[0-9]+\.[0-9]+\]' "$CHANGELOG" \
  | tr -d '#[] ' \
  | while read -r version; do
        write_one "$version"
    done
