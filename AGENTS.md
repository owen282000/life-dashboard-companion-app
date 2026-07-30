# Claude Agents Guide

This project integrates with Claude Code for automated development tasks.

## Available Agents

### caveman:cavecrew-investigator
**Use for:** Locating code, finding symbols, mapping directories
- Find where X is defined
- List all uses of Y
- Map directory structure

Example:
```bash
# In Claude Code:
claude: Find all HealthConnectManager usages
```

### caveman:cavecrew-builder
**Use for:** 1-2 file edits, type fixes, function rewrites
- Mechanical renames
- Bug fixes in single/pair of files
- Format-preserving tweaks
- Comment removal

**Not for:** Multi-file refactors, new features (>1 file)

### caveman:cavecrew-reviewer
**Use for:** Code review, diff inspection, security audit
- Review PR changes
- Audit file for issues
- Check for violations

Output: caveman-compressed (one line per finding)

### Explore
**Use for:** Fast code search across codebase
- Find by pattern: `src/components/**/*.tsx`
- Grep for symbols/keywords
- Answer "where is X"

**Not for:** Deep analysis, consistency checks

## Project Structure

```
app/src/main/java/
├── HealthConnectManager.kt      # Health Connect API wrapper
├── HealthSyncManager.kt          # Sync logic, webhooks
├── screens/HealthConnectScreen.kt # UI configuration
├── ExportManager.kt              # Export to file
└── PreferencesManager.kt          # Settings storage

.github/workflows/
├── build.yml     # Auto APK build on push
├── release.yml   # Signed release APK on tags
└── security.yml  # Trivy + TruffleHog scans
```

## Common Tasks

### Fix crash: "App freezes when syncing"
→ cavecrew-builder: Health Connect record limits
- File: HealthConnectManager.kt, readFiltered()
- Issue: Unlimited records loaded → OOM
- Solution: Cap records per type

### Add webhook retry logic
→ cavecrew-builder: WebhookManager.kt
- Issue: Failed POST silently fails
- Solution: Exponential backoff + max retries

### Review security
→ cavecrew-reviewer: Audit for injection
- Check: SQL (none), XSS (none), credential handling
- Result: Safe (no external inputs processed raw)

### Search for high-volume data handling
→ Explore: "readHeartRateData OR readStepsData OR HeartRate"
- Find all sample-heavy reads
- Identify chunking opportunities

## Caveman Mode

All responses drop fluff (articles, pleasantries) for terse output:
- "Found 3 uses of X" not "I found 3 places where X is used"
- Fragments OK: "Auth fix. Token expiry check broken."
- Code/commits: write normal

Commands:
- `/caveman full` - activate (aggressive brevity)
- `/caveman lite` - softer (still terse)
- `stop caveman` - normal mode

## Memory & Context

Project uses `claude-mem` for persistent context across sessions:
- `/learn-codebase` - Index entire repo (one-time, ~5min)
- `@claude-mem:what-the` - "What's been done recently?"
- `/how-it-works` - Explain memory system

View live activity: http://localhost:37700

## Git Workflows

### Release APK
```bash
git checkout -b release/v1.X.Y
# Edit version in app/build.gradle.kts
git commit -m "release: Bump to v1.X.Y"
git push -u origin release/v1.X.Y
git tag v1.X.Y
git push origin v1.X.Y  # Triggers release.yml
```

### Feature branch
```bash
git checkout -b feat/feature-name
# Make changes...
# cavecrew-builder can auto-fix type errors
git push -u origin feat/feature-name
# Create PR, cavecrew-reviewer audits
```

### Security audit
```bash
# Trigger manually:
gh workflow run security.yml
# View results in GitHub Security tab
```

## Testing with Agent

Ask agent to:
- "Find all timeout-related code"
- "Fix deprecated API calls"
- "Audit Health Connect permissions for leaks"
- "Check if we handle 429 rate limits"

Agent will locate, analyze, propose fixes (if cavecrew-builder scope).

## Notes

- **Java 26 issue**: Build environment uses Java 26, Kotlin compiler may fail. Workaround: Downgrade to Java 21 locally or use Actions (CI/CD handles it).
- **No test framework**: Manual QA via Emulator or device. Add Compose test suite if needed.
- **Keystore**: Store locally (gitignored), add to GitHub Secrets for CI/CD signing.
