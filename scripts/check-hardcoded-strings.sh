#!/usr/bin/env bash
#
# Fails when user-facing text is hardcoded in Compose code instead of living in strings.xml.
#
# Android's built-in HardcodedText lint check only looks at XML layouts, so it sees nothing in a
# Compose UI. This is the equivalent guard for `Text("...")`, `contentDescription = "..."`,
# `Toast.makeText(..., "...")` and friends.
#
# Files still being migrated are listed in ALLOWLIST. Shrink it, never grow it.
#
# Usage: scripts/check-hardcoded-strings.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."

SRC="app/src/main/java"

# Files whose UI strings have not been extracted yet. Every entry here is debt.
ALLOWLIST=(
    "app/src/main/java/com/owen282000/lifedashboard/HealthDataModels.kt"
    "app/src/main/java/com/owen282000/lifedashboard/HealthSyncManager.kt"
    "app/src/main/java/com/owen282000/lifedashboard/ScreenTimeSyncManager.kt"
    "app/src/main/java/com/owen282000/lifedashboard/SyncFailureNotifier.kt"
    "app/src/main/java/com/owen282000/lifedashboard/WebhookManager.kt"
    "app/src/main/java/com/owen282000/lifedashboard/screens/ConfigBackupSection.kt"
    "app/src/main/java/com/owen282000/lifedashboard/screens/SecretsUnavailableBanner.kt"
)

is_allowlisted() {
    local file="$1"
    for allowed in "${ALLOWLIST[@]}"; do
        [ "$file" = "$allowed" ] && return 0
    done
    return 1
}

# Composables and calls that put text on screen. Matches a double-quoted literal of two or more
# characters as the argument; stringResource(...) calls have no literal and never match.
PATTERN='(Text\(|contentDescription = |Toast\.makeText\([^,]+, )"[^"]{2,}"'

violations=0
report=""

while IFS= read -r file; do
    is_allowlisted "$file" && continue

    while IFS= read -r hit; do
        line="${hit%%:*}"
        text="${hit#*:}"
        # Skip things that are not translatable prose: a bare interpolation like Text("$x")
        # or Text("${days}d") carries no English to translate.
        case "$text" in
            *contentDescription*=*null*) continue ;;
            *'Text("$'*) continue ;;
        esac
        report+="  $file:$line"$'\n'
        report+="      ${text#"${text%%[![:space:]]*}"}"$'\n'
        violations=$((violations + 1))
    done < <(grep -nE "$PATTERN" "$file" || true)
done < <(find "$SRC" -name "*.kt" | sort)

if [ "$violations" -gt 0 ]; then
    echo "Hardcoded user-facing strings found ($violations):"
    echo ""
    printf '%s' "$report"
    echo ""
    echo "Move them to app/src/main/res/values/strings.xml and use stringResource(R.string.…)."
    echo "If a file is mid-migration, add it to ALLOWLIST in $0 and shrink that list later."
    exit 1
fi

echo "No hardcoded user-facing strings outside the allowlist (${#ALLOWLIST[@]} files allowlisted)."
