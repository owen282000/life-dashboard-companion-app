#!/usr/bin/env python3
"""The three blocks that every Life Dashboard README shares: nav, built and footer.

This file is the source of those blocks. To change one, change it here, print the
result per repository and paste it into that repository's README.md:

    python3 docs/brand/readme-blocks.py --print android
    python3 docs/brand/readme-blocks.py --check

--check downloads README.md from the main branch of all nine repositories and
compares each block, between its <!-- life-dashboard:X --> markers, with the one
generated here. It prints a diff and exits 1 when a block differs or is missing.

Python 3, standard library only.
"""

import argparse
import difflib
import sys
import urllib.error
import urllib.request

OWNER = "owen282000"

# key, repository, label in the nav
REPOS = [
    ("android", "life-dashboard-companion-app", "Android app"),
    ("ios", "life-dashboard-companion-app-ios", "iPhone app"),
    ("ha", "life-dashboard-ha", "Home Assistant integration"),
    ("stack", "life-dashboard-stack", "Grafana stack"),
    ("sleep-card", "life-dashboard-sleep-card", "Sleep card"),
    ("screen-time-card", "life-dashboard-screen-time-card", "Screen time cards"),
    ("year-card", "life-dashboard-year-card", "Year card"),
    ("activity-card", "life-dashboard-activity-card", "Activity card"),
    ("before-bed-card", "life-dashboard-before-bed-card", "Before bed card"),
]

BLOCKS = ("nav", "built", "footer")

SEP = " &nbsp;·&nbsp; "

BUILT = """<!-- life-dashboard:built -->
## How this is built

Life Dashboard is a one-person project, and [Claude Code](https://claude.com/claude-code) writes a large part of the code. I decide what gets built, read every change before it is merged, and test it on my own phones and Home Assistant before a release. Automated checks run on every pull request, and the [AI policy](https://github.com/owen282000/life-dashboard-companion-app/blob/main/AI_POLICY.md) explains what the AI does and what I check.
<!-- /life-dashboard:built -->"""

FOOTER = """<!-- life-dashboard:footer -->
<p align="center">
  <sub>Made by <a href="https://github.com/owen282000">Owen Vogelaar</a> for the self-hosting and quantified self crowd.</sub><br>
  <sub>Google Play and the Google Play logo are trademarks of Google LLC.</sub><br>
  <sub>Apple, the Apple logo and App Store are trademarks of Apple Inc.</sub>
</p>
<!-- /life-dashboard:footer -->"""


def nav(key):
    """The nav for one repository: its own entry in bold, the other eight as links."""
    items = [
        f"<b>{label}</b>"
        if other == key
        else f'<a href="https://github.com/{OWNER}/{repo}">{label}</a>'
        for other, repo, label in REPOS
    ]
    return (
        "<!-- life-dashboard:nav -->\n"
        '<p align="center">\n'
        f"  <sub>Life Dashboard: {SEP.join(items)}</sub>\n"
        "</p>\n"
        "<!-- /life-dashboard:nav -->"
    )


def blocks(key):
    """The three blocks for one repository, markers included."""
    return {"nav": nav(key), "built": BUILT, "footer": FOOTER}


def extract(readme, name):
    """One block out of a README, markers included, or None when it is not there."""
    start = readme.find(f"<!-- life-dashboard:{name} -->")
    close = f"<!-- /life-dashboard:{name} -->"
    end = readme.find(close, start)
    if start < 0 or end < 0:
        return None
    return readme[start : end + len(close)]


def fetch(repo):
    url = f"https://raw.githubusercontent.com/{OWNER}/{repo}/main/README.md"
    request = urllib.request.Request(url, headers={"User-Agent": "readme-blocks-check"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode("utf-8").replace("\r\n", "\n")


def print_blocks(key):
    wanted = blocks(key)
    print("\n\n".join(wanted[name] for name in BLOCKS))


def check():
    problems = 0
    for key, repo, _ in REPOS:
        try:
            readme = fetch(repo)
        except (urllib.error.URLError, TimeoutError) as error:
            print(f"FAIL  {repo}: README.md could not be downloaded ({error})")
            problems += 1
            continue
        wanted = blocks(key)
        for name in BLOCKS:
            found = extract(readme, name)
            if found is None:
                print(f"FAIL  {repo}: block '{name}' is missing")
                problems += 1
            elif found != wanted[name]:
                print(f"FAIL  {repo}: block '{name}' differs")
                diff = difflib.unified_diff(
                    found.splitlines(),
                    wanted[name].splitlines(),
                    fromfile=f"{repo}/README.md",
                    tofile=f"readme-blocks.py --print {key}",
                    lineterm="",
                )
                for line in diff:
                    print(f"    {line}")
                problems += 1
            else:
                print(f"ok    {repo}: {name}")
    if problems:
        print(
            f"\n{problems} block(s) out of step. Print the right ones with "
            "'python3 docs/brand/readme-blocks.py --print <key>' and paste them into that README."
        )
        return 1
    print("\nAll nine READMEs carry the current blocks.")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument(
        "--print",
        dest="key",
        choices=[key for key, _, _ in REPOS],
        help="print the three blocks for one repository, ready to paste",
    )
    group.add_argument(
        "--check",
        action="store_true",
        help="compare the blocks in all nine READMEs on main with the generated ones",
    )
    args = parser.parse_args()
    if args.key:
        print_blocks(args.key)
        return 0
    return check()


if __name__ == "__main__":
    sys.exit(main())
