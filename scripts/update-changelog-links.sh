#!/usr/bin/env bash
#
# Rewrites the compare links at the bottom of CHANGELOG.md from the git tags.
#
# Keep a Changelog asks every version heading to be a link, which is easy to forget by hand:
# the references stopped at 1.6.0 while the app was on 1.14.0, so eight releases had none and
# [Unreleased] compared against a year-old tag. Run from scripts/prepare-release.sh, so the
# links are correct by construction rather than by remembering.
#
# Usage: scripts/update-changelog-links.sh
set -euo pipefail

cd "$(dirname "$0")/.."

python3 - << 'PYTHON'
import re, subprocess

REPO = "https://github.com/owen282000/life-dashboard-companion-app"
PATH = "CHANGELOG.md"

def order(tag):
    return [int(part) for part in tag.split(".")]

tags = subprocess.run(["git", "tag", "--list"], capture_output=True, text=True, check=True).stdout.split()
released = sorted([t for t in tags if re.fullmatch(r"\d+\.\d+\.\d+", t)], key=order)
if not released:
    raise SystemExit("no semver tags found")

text = open(PATH).read()

# Drop the existing reference block; it is regenerated in full below.
text = re.sub(r"\n\[Unreleased\]:.*?(?:\n\[\d+\.\d+\.\d+\]:.*?)*\n*$", "\n", text, flags=re.S)

# Only link versions that actually have a heading, so a tag without notes is not invented.
documented = re.findall(r"^## \[(\d+\.\d+\.\d+)\]", text, re.M)
missing = [v for v in documented if v not in released]
if missing:
    raise SystemExit(f"CHANGELOG has headings without a tag: {', '.join(missing)}")

lines = [f"[Unreleased]: {REPO}/compare/{released[-1]}...HEAD"]
for version in sorted(documented, key=order, reverse=True):
    index = released.index(version)
    if index > 0:
        lines.append(f"[{version}]: {REPO}/compare/{released[index - 1]}...{version}")
    else:
        lines.append(f"[{version}]: {REPO}/releases/tag/{version}")

open(PATH, "w").write(text.rstrip("\n") + "\n\n" + "\n".join(lines) + "\n")
print(f"CHANGELOG.md -> {len(lines) - 1} version links, Unreleased against {released[-1]}")
PYTHON
