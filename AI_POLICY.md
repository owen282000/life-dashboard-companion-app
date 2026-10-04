# AI policy

This app handles health data, so how it is made is a fair question, and some app stores ask it too. This page answers it, and sets the rules for contributions.

## How this project is built

I build Life Dashboard Companion with substantial help from [Claude Code](https://claude.com/claude-code), Anthropic's coding assistant. A large part of the code was drafted with it. I say so openly, in the README and here, because you should be able to decide with the facts in hand.

What that does not change:

- **I decide what gets built.** Features come from issues, user reports and my own use of the app, not from what a tool suggests.
- **I am responsible for every change**, exactly as if I had typed it myself. Nothing lands on `main` that I have not read and understood.
- **Everything is tested before it ships.** Unit tests, ktlint, Android lint and a check for hardcoded strings run in CI on every pull request and every push to `main`. Every pull request also runs an instrumented suite on an Android emulator, which exercises the real sync: Health Connect, the webhook, the outbox, MQTT and Receive. Pushes to `main` run it in full, slow tests included. The unit tests and lint run again before any release is built. Changes to syncing are also tested by hand on a real phone and on an emulator, against a Home Assistant instance in Docker, before they are released.
- **Changes to the sync path and security get extra review rounds**, some of them AI-assisted, looking specifically for what could go wrong. Findings are dealt with before the release, not after it.
- **What you install is what is in this repository.** Every release is built by public CI and carries a [provenance attestation](SECURITY.md#verifying-a-release) tying it to the exact commit. The build is reproducible, so anyone can [build it from source](docs/building.md#reproduce-a-release-build) and get the same APK. The F-Droid submission, still in review, is set up to do exactly that check.

## What AI does not do

- **There is no AI in the app.** It contains no model, calls no AI service and sends your data nowhere but the webhook or MQTT broker you configure. See [PRIVACY.md](PRIVACY.md).
- **No autonomous agents.** No AI agent merges code, publishes releases or posts in issues on its own. The only bot opening pull requests is Dependabot, for dependency updates, and those are reviewed like any other change.

## Contributing with AI assistance

AI-assisted contributions are welcome, on the same terms I hold myself to:

- **Say so in the pull request.** Which tool, and roughly how much of the change it wrote. This is not held against you; not saying it is.
- **No AI co-author trailers.** Leave `Co-Authored-By` lines for AI tools out of your commits; the disclosure belongs in the pull request description. Trailers like that are removed before merging. My own commits do not carry them either: this page is the disclosure for the project as a whole.
- **Understand what you submit.** You should be able to explain every line and answer review questions yourself.
- **Test it.** Run the tests and try the change on a device or emulator where that makes sense.
- **No unreviewed agent output.** Pull requests opened by an autonomous agent, or large generated changes nobody has read, will be closed.

Issues: describe what you actually saw on your own device. Using a translator or a tool to tidy up your English is fine; a generated bug report with no real device behind it will be closed.
