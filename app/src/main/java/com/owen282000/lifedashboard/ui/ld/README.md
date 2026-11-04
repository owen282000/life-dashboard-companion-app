# LD design kit

The A2b look for the Android redesign. Screens live in `screens/ld/`, each in a file of its own; the kit here. The boards are the visual truth (`AndroidX.dc.html`); this list says what to reach for. The iOS app (`UI/LD/`, `Screens/LD/`) is the reference for wording and behaviour.

## How to build a screen

Replace the stub in `screens/ld/`. Keep the function's name and parameters, because `LDRouteTable.kt` calls them; that is the only file that knows them all, so you do not have to touch it, the shell or `MainActivity`.

```kotlin
@Composable
fun NotificationSettingsScreen() {
    val viewModel = ldHealthViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LDScreen(title = stringResource(R.string.health_notifications_title)) {
        LDHeading(stringResource(R.string.health_notifications_title))
        LDGap(12.dp)
        LDCard(Modifier.ldStaggerRise(0)) {
            LDToggleRow(stringResource(R.string.health_notifications_notify_after_failed), state.failureNotificationsEnabled, viewModel::setFailureNotifications)
        }
    }
}
```

Stack blocks directly in `LDScreen`: every block carries its own outer margin, as on the boards. Colours come from `LDTheme.colors`, text styles from `LDType`, never a literal, so Appearance and the Screen Time purple keep working. A screen needs nothing for the accent: the shell sets it per tab.

Text is `stringResource`, with `verbatim` data passed in. New strings go in `res/values/strings.xml`, in English, under your group's heading near the end of the file, so parallel work merges without a conflict. Reuse a key whose wording matches, so its ten translations stay. `scripts/check-hardcoded-strings.sh` must stay green, and it also reads `label = "..."`: leave out an animation's label.

The shell already does this for every screen, so do not do it again: show the view models' toasts, hand `permissionRequests` to the launcher, show the Health view model's prompts (`LDHealthPrompts`), open the usage access settings, `refreshPermissions` on resume, poll usage access on the Screen Time tab, `refreshSharedSettings` on a tab switch, `ReviewAfterSync`. `LDAccessBlockedLine(state)` goes on a page with a switch that needs a permission: it says why nothing happened when Health Connect is not there to ask. Dialogs stay plain Material `AlertDialog`s and pickers: `LDTheme` maps Material's colours and font to the tokens.

## Saving

There is no Save bar. `state.saved` is what is stored, and the only settings a view model holds.

### What applies at once

Call it from the switch or chip. There is nothing more to do, and it comes back through `state.saved`.

| Section | Actions |
| --- | --- |
| Health | `toggleType(type, enabled)`, which asks for the permission through `state.permissionPrompt` and `requestTypePermission()`; `applyTypes(types)` for Essentials, All types and None; `setResolution(type, resolution)`; `setIncludeDailyTotals`, `setIncludeRecordMetadata`, `setKeepFullPayloads`; `setReceiveEnabled` and the other Receive actions |
| Screen Time | `setAppFilterMode(mode)`, `toggleFilteredApp(package)` (keep the warning: a change rewrites the last seven days at the receiver); `setUseDayBoundary(enabled)` |
| Both | `setSchedule(scheduleDraft)` for mode, times, days and quiet hours; `removeDestination(id)`, `removeMqtt()`; `setAllowHttpWebhooks`, `setClientCertAlias`, `setServerTrust`, `setPhoneName`, `setFailureNotifications`, `setFailureThreshold` |

`setSchedule` takes `state.saved.schedule.copy(...)`, writes only when it differs and only then reschedules. A schedule that never runs is stored as it is: no times, no days. Show the warning when `state.saved.schedule.wouldNeverRun()`, as iOS does.

A chip of What to send counts as selected only when `state.saved.enabledTypes` equals its preset (`OnboardingSupport.ESSENTIAL_TYPES`, `HealthDataType.entries.toSet()`, empty). `applyTypes` turns on what has its permission at once, and asks for the rest together; those go on when granted, and what is refused stays off.

An empty destination list is fine. `state.saved.hasDestination` and `canSync` say what follows from it. The removal of a Receive source forgets the source: `state.receive.sourceUrl` becomes null, so say so in the confirm.

### What waits for Save

Webhook, MQTT, the interval and the day boundary hold a draft on the screen, with Save in the top bar or footer. Back discards it. Never store these per keystroke: a half typed host would clear a destination's headers and secret for good, and a half typed secret on the Receive source throws away its ledger.

`rememberLDDraft { }` holds the draft. The rule: it survives rotation, stays in memory only because it can hold a secret, so never in `rememberSaveable`, and goes when the page is popped, which is how Back discards it. A draft is for typed text that does damage half finished; a switch never has one. Every save returns the problem to show, or null when it was stored.

```kotlin
@Composable
fun WebhookDestinationScreen(section: LDSection, destinationId: String?, address: String?) {
    val ui = ldSection(section)
    val navigator = LocalLDNavigator.current
    val page = LocalLDEntryId.current
    val resources = LocalResources.current
    var draft by rememberLDDraft { ui.actions.destinationDraft(destinationId, address) }
    var problem by rememberLDDraft<UiMessage?> { null }
    val save = {
        problem = ui.actions.saveDestination(draft)
        if (problem == null) navigator.pop(page)
    }
    LDScreen(
        title = stringResource(R.string.ld_title_destination),
        actions = { LDTopBarAction(stringResource(R.string.ld_save), onClick = save) }
    ) {
        LDCard {
            LDFieldRow(
                "URL", draft.url,
                onValueChange = { draft = draft.withUrl(it); problem = null },
                error = (problem ?: draft.urlProblem?.takeIf { draft.url.isNotBlank() })?.text(resources)
            )
        }
    }
}
```

| Page | Start from | Edit with | Save with | Field problems |
| --- | --- | --- | --- | --- |
| Webhook | `destinationDraft(id, address)` | `withUrl`, `withSecret`, `withHeader`, `withoutHeader`, `withHeadersOf(id)`, `withSecretOf(id)`; `others` lists what to copy from | `saveDestination(draft)` | `draft.urlProblem`, `SettingsRules.headerProblem(name, value)` before `withHeader` |
| MQTT | `mqttDraft()` | `withActiveBroker`, `withShared`, `copy(section = ..., portText = ...)` | `saveMqtt(mqtt)` | `SettingsRules.mqttHostProblem`, `mqttPortProblem` |
| Interval | the text of `state.saved.schedule.intervalText` | your own String | `saveInterval(text)` | `SettingsRules.intervalOrNull(text) == null` is `UiMessage.IntervalTooShort` |
| Day boundary | `state.saved.dayBoundaryHour` | your own String | `saveDayBoundaryHour(text)` | `SettingsRules.dayBoundaryHourOrNull(text) == null` is `UiMessage.InvalidDayBoundaryHour` |

`saveMqtt` writes the shared broker only when this screen changed it, so a tab that looked a while ago does not put back an old one.

`UiMessage.text(resources)` in `screens/UiMessageText.kt` words a problem. Show it with `LDFieldRow(error = ...)`, not in a toast.

### When the secure storage refuses

`saveDestination` and `saveMqtt` return `UiMessage.SecretsNotReady` and store nothing. Stay on the screen and show `LDBanner` with it, and Try again is the same Save. `removeDestination` and `removeMqtt` have no draft to come back to, so they set `state.refused`: show `LDRefusedBanner(state.refused != null, actions::retryRefused, actions::dismissRefused)` where it can be seen, on Home and on the page that removes. `ldSection(section).refused` has it for either section.

### Reading

`syncNow`, `preview`, `export`, `testPing` and `openBackfillDialog` use what is stored, and a sync writes nothing first. `testDestination(id)` pings one stored destination, for Send test; a new one has to be saved first.

After a backup import or anything else that writes the settings outside a view model, call `reloadFromSettings()` on both.

## Today

`ldHealthViewModel().refreshToday()` reads the panel's figures into `state.today`: `LaunchedEffect(state.refreshKey, state.saved.enabledTypes) { viewModel.refreshToday() }` on Home.

`state.refreshKey` moves with everything a sync, a backfill or a ping leaves behind, also when a worker in the background did it (`syncDataChanges`): key on it whatever comes from the log. `state.isSyncBusy` is Sync Now's state, for a sync started here or in the worker.

- `today.stats`: a `TodayStat` per figure that is enabled and has data, in the order steps, sleep, resting heart rate, weight. `kind`, `series` as dated `TodayDay`s oldest first with only the days that have a value, `latest`, `values` for a `SparkLine`, `valueOn(date)`.
- Units: steps, minutes, bpm, kilograms. Format in the screen, as iOS `TodayPanel` does, pounds where the locale measures in them.
- `today.recordsToday` is the fallback when no figure fits, `today.hasLoaded` false until the first read is back, so the panel does not flash that fallback.
- Sensitive types cannot get here: `TodayKind` does not list them. A figure the quota kept out keeps its last value; one without permission drops off.

In a view model test: `FakeHealthOps.today` and `FakeAppSettings.recordsToday`.

## Going somewhere

`LDRoute` lists every page, in `LDRoute.kt`. `LocalLDNavigator.current` has `push(route)`, `pop(LocalLDEntryId.current)`, `replaceTop(route)`, `popToRoot()`, `selectTab(tab)` and `open(route)`, which lands on the route's own tab over its root.

```kotlin
val navigator = LocalLDNavigator.current
LDRow(stringResource(R.string.sync_advanced_title), value = summary, onClick = { navigator.push(LDRoute.Advanced()) })
LDTile(title, LDIcons.more, subtitle = subtitle, onClick = { navigator.push(LDRoute.More) })
LDButton(stringResource(R.string.ld_title_what_to_send), onClick = { navigator.push(LDRoute.WhatToSend) })
LocalLDPairing.current.startScan()
```

A page of both sections takes `LDSection`; `ldSection(section)` gives its stored settings and `SectionActions` without a branch. Pushed pages cover the tab bar. A back arrow comes with `LDScreen(title = ...)`, and predictive back works by itself. State is kept per page: `rememberSaveable` and the scroll position survive a tab switch and coming back. Each page's `remember` does not, because a covered page leaves the composition.

Routes hold ids, never payloads or secrets, because the stack is saved in the Bundle.

`PairedResult` reads `LocalLDPairing.current.result` (link, written sections, `ping`) and `tryAgain()`. MainActivity pushes it after Pair and `PairingViewModel` runs the ping, so a rotation loses neither. The result is in memory only: when it is null, after a process death, pop the page. `ScanScreen` and `PairingDialog` stay overlays owned by MainActivity, over the shell and over onboarding; pass `onBack` to `LDScreen` there, because they are outside the shell. `OnboardingFlowScreen` is the gate in MainActivity with `onFinished`, `onScanRequested` and `paired`, also outside the shell: no `LocalLDNavigator` there.

### Opening a page directly, in a debug build

For screenshots, as `-ld.route` on iOS. The extra is only read in a debuggable build, and it skips the onboarding gate for that run without changing what is stored.

```
adb shell am start -S -n com.owen282000.lifedashboard/.MainActivity --es ld.route more
```

`--ei ld.step 2` with `onboarding` opens that step of the flow, counted from 0, as `-ld.step` on iOS. Also debug builds only.

Names are in `LDDebugRoute.kt`: `home`, `screenTimeHome`, `logsList`, `addDestination`, `webhookNew`, `webhookDestination` (the first listed one), `mqttDestination`, `pairedResult`, `whatToSend`, `typeCategory.Activity` (`Body`, `Heart`, `SleepMind`, `FoodDrink`, `Cycle`), `dataResolution`, `backfill`, `backfillRun`, `payloadPreview`, `whenToSend`, `receive`, `more`, `advanced`, `serverCertificates`, `notifications`, `exportData`, `backupRestore`, `logDetail`, `logDetail.failed`, `logDetail.ok`, `screenTimeApps`, `screenTimeDay`, `screenTimeWhen`, `screenTimeMore`, `about`, `aboutHelp`, `aboutSupport`, `aboutPrivacy`, `aboutOpenSource`, `aboutNerd`, `onboarding`, `scanner`, `pairing`. Add `.screenTime` to `addDestination`, `webhookNew`, `webhookDestination`, `mqttDestination`, `payloadPreview`, `advanced` or `exportData` for that section.

## Layout

| Name | What it does |
| --- | --- |
| `LDScreen(title, onBack, background, actions, scrollState, pinned, footer) { }` | The ground and the scrolling, the insets and the keyboard. `title` gives the 56 high top bar; no title on a tab's root. `pinned` sits at the bottom while all fits and scrolls along when it does not. `footer` stays fixed below. A Spacer in the content does nothing: use `pinned`. |
| `LDListScreen(isRefreshing, onRefresh, footer, state) { }` | `LDScreen` for a tab's root that lists many rows: the same ground, insets and fixed `footer`, with a LazyColumn, which `LDScreen`'s scrolling cannot hold, and pull to refresh. The content is a `LazyListScope`. |
| `LDTopBar`, `LDTopBarAction(title, enabled) { }` | The bar `LDScreen` draws, and the text action for its `actions`, as Save. |
| `LDFooterBar { }` | The buttons in a `footer`, with the hairline above. Its buttons need no inset. Equal widths while the labels fit, otherwise by label, otherwise on more lines. |
| `LDGap(12.dp)` | Fixed space between blocks. |
| `LDDivider`, `LDHairline` | The two line colours. `LDCard` draws its own dividers. |

## Text

| Name | What it does |
| --- | --- |
| `LDHeading(text)` | 30/34 bold heading, 18 from the top. |
| `LDSectionLabel(text)` | The small label above a card. |
| `LDNote(text, color, top, size)` | Body text: `LDNoteSize.Regular` 14, `Small` 13 for footnotes, `Large` 16 for intros. |
| `LDCode { }`, `TextStyle.ldCodeStyle()` | For code outside a code block: JSON, addresses, topics. Left to right against the left edge in every language. |
| `version.ldLeftToRight` | One left to right run inside text of any direction, for a version or an address. |
| `"a · b".ldKeepingSeparators` | The dot stays with the word before it when the line wraps. |
| `ldLargeText()` | The font sizes where side by side stops working. |

## Cards and rows

| Name | What it does |
| --- | --- |
| `LDCard(bottom = 0.dp) { }` | Rows on a tile surface with dividers between them. `if` and a loop in it count per row. |
| `LDRow(title, subtitle, value, valueColor, valueMono, showChevron, enabled, onClick, trailing)` | 50 high, 62 with a subtitle, taller with larger text. `onClick` makes it a button; without one pass `showChevron = false`. `trailing` has a control or a spinner instead of value and chevron. The value goes under the name at the largest font sizes. |
| `LDRowSpinner()` | What `LDRow`'s `trailing` holds while the row is at work. |
| `LDToggleRow(title, checked, onCheckedChange, subtitle, enabled, locked)` | A real Switch in the accent. `locked` is a type without its permission: a lock and half strength, and it still calls `onCheckedChange`. TalkBack says On or Off, and the lock as part of the name. |
| `LDFieldRow(label, value, onValueChange, placeholder, mono, isSecure, isCode, error, keyboardOptions, onDone, focusRequester, trailing)` | Small label over a value. Editable with `onValueChange`, read only and selectable without. `error` goes under the value. `isCode = false` for a field that takes words or a plain number, which follow the language. `onDone` is what the keyboard's Done runs: the screen's Add, Use or Save, also guarded as that button is. `focusRequester` to put the focus in it when a form opens. `trailing` for a reveal button; `common_show_password` and `common_hide_password` exist. |
| `LDDestinationRow(name, status, statusColor, onClick)` | A destination on Home, full width. Name and status stack when they do not fit. |
| `LDOption(title, selected, onClick, subtitle, mark, bottom)` | A card to pick, `LDOptionMark.Radio` or `Check`. TalkBack says selected for a radio and checked for a check. |
| `LDChevron()` | The row chevron. It follows the reading direction. |

## Controls

| Name | What it does |
| --- | --- |
| `LDSegmented(selected, listOf(LDSegment(value, title)), onSelect, label = ...)` | The segmented control. `label` is what TalkBack calls the group. Stacks at the largest font sizes. |
| `LDChips { LDChip(title, selected = ...) { } }` | Chips that wrap. `LDRemovableChip(title) { }` has an x. 44 high with a clear edge that makes the tap area 48. |
| `LDDayChips(listOf(LDDayChip(letter, fullName, selected) { }))` | The seven days on one line, wrapping only when 44 no longer fits. |
| `LDButton(title, kind, size, fullWidth, inset, enabled, busy) { }` | `LDButtonKind.Primary`, `Ghost` or `Danger` (amber), `LDButtonSize.Regular` 50, `Medium` 44, `Small` 44 with 14 text. `busy` adds a spinner and disables it. |
| `LDTextButton(title, color) { }` | Text as a button, as Add destination. |

## Showing things

| Name | What it does |
| --- | --- |
| `LDBanner(title, message = ...) { LDBannerAction(title) { } }` | The warning card, actions optional. |
| `LDRefusedBanner(visible, onRetry, onDismiss)` | `state.refused` as that card. |
| `LDNoticeLine(text, tone, top)` | A result or a hold in one line, `LDNoticeTone.Success`, `Warning` or `Info`: a symbol as well as a colour, announced by TalkBack when it appears or changes. For what a button just found, where the user is still on that button. |
| `LDKeyValueList(listOf(LDKeyValue(key, value, color, isCode)))` | Labels and Space Mono values on a card. Labels share the width of the longest, at most 45%. The value goes under its label at the largest font sizes. |
| `LDCodeBlock(text, color, wraps, selectable)` | Text as it is in Space Mono, long lines scroll sideways or wrap. A payload takes `selectable = false`: its Copy button is what moves it. |
| `LDProgressBar(value)` | The 8 high bar of a backfill, 0 to 1. Give it a label for TalkBack through `Modifier.semantics`. |
| `LDTile(title, icon, subtitle = ...) { }` | 118 high or taller. Icons are in `LDIcons`, the boards' own. |
| `LDTileGrid { }` | Two columns, equal height per row, one column at the largest font sizes. Goes in `pinned`. |

## Line and motion

| Name | What it does |
| --- | --- |
| `PulseLine(variant = LDPulseVariant.Normal)` | The heartbeat from the edge where reading starts, `Warning` or `Flat`. Draws in on appear and on a new variant. |
| `SparkLine(values, color, height)` | A series ending in a dot, running with the reading direction. Empty without values. Hidden from TalkBack: say the trend in words on the figure. |
| `PulseProgress(step, of)` | The onboarding line, drawn on from the previous step. Say "Step N of 5" in words next to it. |
| `Modifier.ldStaggerRise(index)` | Rises and fades in on appear, with `index` as its place in the stagger. |
| `LDTheme.motionEnabled` | False with the animator scale at 0, as the kit's own motion goes by. Compose's animations stop by themselves then. |

Tabs slide in the direction of the switch and the tab indicator moves along, in the shell. Pushed pages slide as a layer.

## Fonts

`res/font` holds Space Grotesk Regular, Medium and Bold and Space Mono Regular and Bold, copied from the iOS app, with each family's OFL licence in `assets/licences`. They go through `LDType`. The licences are named in the repository's README, and `ld_about_typefaces` says it on the Open source page.
