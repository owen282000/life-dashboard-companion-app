#!/usr/bin/env bash
#
# Writes fastlane/metadata/android/en-US/changelogs/<versionCode>.txt, the release notes F-Droid
# and Google Play show for each version.
#
# The versionCode is derived from the semver tag exactly as app/build.gradle.kts does it
# (major * 10000 + minor * 100 + patch), so the two never drift apart.
#
# Store notes are short, plain text and written for the person updating the app, so a file that
# already exists is treated as hand-written and is never overwritten. For a version without one,
# the script writes a first draft from CHANGELOG.md to edit before committing:
#
#   1. the "### Highlights" (or "#### Highlights") list of the version's section, if it has one;
#   2. otherwise the first list in the section, under its heading as "New", "Improved" or "Fixed".
#
# Bullets are unwrapped to one line each and stripped of markdown. Play cuts release notes at
# 500 characters, so the draft keeps whole bullets while they fit and then ends with a link to
# the full notes. It never cuts inside a sentence: a first bullet that is too long on its own is
# shortened at a sentence end, and when not even one sentence fits the draft is only the link.
#
# Hand-written notes group bullets under "New", "Improved" and "Fixed" lines; sort the draft's
# bullets under those before committing.
#
# Usage:
#   scripts/generate-fastlane-changelogs.sh            # every released version in CHANGELOG.md
#   scripts/generate-fastlane-changelogs.sh 1.11.0     # just this one
#
# Environment:
#   REGENERATE=1   overwrite existing files with a fresh draft (discards hand-written notes)
#   DRY_RUN=1      print each draft to stdout and write nothing
#
set -euo pipefail

cd "$(dirname "$0")/.."

[ -f CHANGELOG.md ] || { echo "No CHANGELOG.md found" >&2; exit 1; }

python3 - "$@" << 'PYTHON'
import os, re, sys

CHANGELOG = "CHANGELOG.md"
OUT_DIR = "fastlane/metadata/android/en-US/changelogs"
MAX_CHARS = 499  # Play's limit is 500, and the file ends with a newline
RELEASES = "https://github.com/owen282000/life-dashboard-companion-app/releases/tag/"

regenerate = os.environ.get("REGENERATE") == "1"
dry_run = os.environ.get("DRY_RUN") == "1"

text = open(CHANGELOG, encoding="utf-8").read()


def version_code(version):
    major, minor, patch = (int(part) for part in version.split("."))
    return major * 10000 + minor * 100 + patch


def section(version):
    match = re.search(r"^## \[" + re.escape(version) + r"\][^\n]*\n(.*?)(?=^## \[|\Z)", text, re.M | re.S)
    return match.group(1) if match else ""


def plain(line):
    """Markdown to the plain text Play and F-Droid render."""
    line = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", line)        # [text](url) -> text
    line = line.replace("`", "").replace("**", "")
    line = re.sub(r"\s*\((?:#\d+(?:,\s*)?)+\)", "", line)        # orphan issue refs: (#71, #72)
    return re.sub(r"\s+", " ", line).strip()


def bullets(block):
    """The "- " items of a markdown block, each unwrapped to one line."""
    items = []
    for line in block.splitlines():
        if line.startswith("- "):
            items.append(line[2:])
        elif items and line.startswith((" ", "\t")) and line.strip():
            items[-1] += " " + line.strip()
        elif items and not line.strip():
            continue
        elif items:
            break
    return ["- " + plain(item) for item in items if plain(item)]


def draft_lines(body):
    """Highlights when the section has them, otherwise its first list under its heading."""
    blocks = re.split(r"^(#{3,4} [^\n]*)\n", body, flags=re.M)
    # blocks = [preamble, heading1, body1, heading2, body2, ...]
    pairs = list(zip(blocks[1::2], blocks[2::2]))
    for heading, block in pairs:
        if re.fullmatch(r"#{3,4} Highlights\s*", heading):
            return [], bullets(block)
    for heading, block in pairs:
        items = bullets(block)
        if items:
            name = heading.lstrip("#").strip()
            return [{"Added": "New", "Changed": "Improved", "Security": "Fixed"}.get(name, name)], items
    return [], bullets(blocks[0])


def first_sentences(item, budget):
    """The longest run of whole sentences of one bullet that fits the budget, or None."""
    best = None
    for match in re.finditer(r"[.!?](?=\s+[A-Z(\"]|$)", item):
        candidate = item[: match.end()]
        if len(candidate) <= budget:
            best = candidate
    return best


def draft(version):
    header, items = draft_lines(section(version))
    if not items:
        return None
    notice = "Full release notes: " + RELEASES + version
    full = "\n".join(header + items)
    if len(full) <= MAX_CHARS:
        return full
    budget = MAX_CHARS - len(notice) - 1
    kept = list(header)
    for item in items:
        if len("\n".join(kept + [item])) <= budget:
            kept.append(item)
        else:
            break
    if len(kept) == len(header):
        room = budget - len("\n".join(header + [""]))
        shortened = first_sentences(items[0], room)
        if shortened is None:
            return notice
        kept.append(shortened)
    return "\n".join(kept + [notice])


def write_one(version):
    path = os.path.join(OUT_DIR, f"{version_code(version)}.txt")
    exists = os.path.exists(path)
    note = draft(version)
    if dry_run:
        state = "exists, kept" if exists and not regenerate else "would write"
        print(f"== {path} ({version}, {state})")
        print(note if note is not None else "(no changelog section)")
        return
    if exists and not regenerate:
        print(f"keep {path} ({version}, already written; REGENERATE=1 replaces it)")
        return
    if note is None:
        print(f"skip {version} (no changelog section)")
        return
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(path, "w", encoding="utf-8") as out:
        out.write(note + "\n")
    print(f"wrote {path} ({version}, {len(note)} characters): review it before committing")


args = sys.argv[1:]
if args:
    write_one(args[0])
else:
    # Every released version in the changelog; "Unreleased" has no versionCode yet.
    for version in re.findall(r"^## \[(\d+\.\d+\.\d+)\]", text, re.M):
        write_one(version)
PYTHON
