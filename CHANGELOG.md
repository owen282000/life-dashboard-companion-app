# Changelog

All notable changes to this project are documented in this file. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/). For older releases, see the [GitHub Releases](https://github.com/owen282000/life-dashboard-companion-app/releases).

## [Unreleased]

## [1.22.0] - 2026-10-03

### Added

- With two or more webhooks in a section, a sync that reached some of them and not the others
  counted as delivered without a word, so the one that missed it never got that data: it is not
  queued for a single address yet. The line under Sync Now now says "Delivered to 1 of 2
  destinations, see Logs", and after as many of those in a row as the failure notification
  waits for, a notification names the address that keeps missing out (its host only). And when
  pairing with Home Assistant replaces the section's signing secret while the section has other
  addresses, the pairing dialog names them: they get payloads signed with the new secret from
  then on, so one that checks signatures, such as the stack, starts refusing until it gets the
  new secret too.
- Pairing with a QR code or a pairing link now checks itself: right after the pairing is
  written, the app sends a test ping to the paired address, signed with the paired secret, and
  says "Paired with <host>, test ping delivered", or why the ping failed. A failed ping leaves
  the pairing in place. This used to be a toast asking you to tap Test ping yourself.

### Changed

- Clear logs on the Logs tab asks first, and says how many logs it removes. One tap used to
  wipe them, and they are the only record of what was delivered.
- The Quick Settings tile starts at most one sync a minute, like the Tasker and MacroDroid
  broadcast, and the two share that minute: a tap right after an automation started a sync is
  ignored, and on Android 10 and later the tile says "Try again in a minute". Tapping it five
  times used to queue five syncs.
- Backup & restore under About is now in Dutch and German too.
- Backfill runs as a background job of its own instead of on the Health tab. It used to stop
  when you left the screen or Android ended the app, and running it again started at the first
  3-day chunk. Now it carries on after you leave, the tab shows where it is when you come back,
  Stop ends it and a new one can be started right after. The app remembers the last payload
  that went through, also halfway through a busy chunk: a backfill that Android stops, that runs
  out of Health Connect's read quota or whose delivery fails continues right after it, by itself
  after a stop or the quota (a few minutes later), and when you pick the same length again
  within a day after a failure; the Backfill dialog says so. After six runs in a row that sent
  nothing it stops and says why, instead of trying again for hours. A chunk that a type could
  not be read for still does not count as done. Switching data types on or off while a backfill
  is under way makes it start over at the first chunk, and the tab says so. The Logs tab gets
  one row per run with how far it got and how many records it sent, instead of one per chunk; a
  delivery that failed keeps its own row. Its messages, on the tab and in the Logs tab, are in
  your language. On Android 8 to 11 a notification shows while it runs.

### Fixed

- The names of the 33 data types were English on a Dutch or German phone, in the Data Types
  list, the resolution settings, Receive and the permission prompts. They now follow the
  phone's language, with the same names the iOS app uses, for example "Stappen" and
  "Schritte"; the payload, MQTT and your saved settings keep the names they had. The
  notification for failed syncs was English too, and called Screen Time "Screen Time" where the
  app says "Schermtijd" or "Bildschirmzeit": it is translated now, and like on iOS it ends with
  the last error, such as "Last error: HTTP 502", short and without the webhook's path, query
  or any secret.
- Importing a settings file from the iPhone app no longer resets what the file does not have.
  It cleared the Screen Time webhooks, switched Screen Time MQTT off and put full payloads and
  the day boundary back to their defaults; a setting the file does not mention now keeps the
  value on the phone, for any file. It also turned off the data types the iPhone does not have
  (bone mass, body water mass, basal metabolic rate, skin temperature); those now keep their
  state, and the file decides only the types the iPhone has. And it no longer takes over what
  names the iPhone: its default MQTT topic `lifedashboard-ios`, which put this phone's sensors
  on the iPhone's in Home Assistant, and its phone name. The preview says what stays as it is.
- A broker that took the connection and then never answered held an MQTT publish, and with it
  the sync and every sync queued behind it, for good. The connection, the TLS handshake and the
  broker's greeting now get 10 seconds each, every message 10 seconds and the whole publish two
  minutes; after that the publish fails with "No answer within ... s" on the MQTT status line
  and in the Logs tab, and a sync that is stopped stops the publish with it. A connection the
  publish gave up on is closed, also when it only comes up afterwards.
- With MQTT as the only destination (no webhook), a sync whose broker was down still showed as
  synced, green on the dashboard and never counted towards the failure notification. It now
  fails like a webhook that is down: the line under Sync Now says "Sync failed: MQTT broker:"
  and the reason, the dashboard shows the failure, and the failure notification counts it. With
  a webhook as well the webhook still decides, and the MQTT status line now shows the broker's
  error right after Sync Now instead of only after reopening the app.
- The test ping carried no `app_version`, which every payload in docs/webhook-schema.json has
  to carry, so a receiver that validates against the schema refused it. It now has one, the
  wizard's ping names its section (`health_connect` or `screen_time`) instead of `onboarding`,
  and the schema and docs/webhook.md describe the ping. A failed Send Test Ping in the setup
  wizard now says why, "Test ping failed:" and the reason, like Test ping on the tabs, instead
  of only "check the logs".
- TalkBack left a lot to the eye. A switch was read as "switch, off" with no name, because its
  label was a separate text, and only the small switch itself could be tapped. Every switch row
  is now one control, on the tabs, in the Receive and data type lists, in the pairing dialog,
  in the setup wizard and on the Logs header: TalkBack reads "Allow plain HTTP, off, switch",
  and a tap anywhere on the row flips it. The wizard's cards say whether they are checked or
  selected, the schedule's days are read as whole days with checked or not, and the interval or
  times choice, the Logs filter, the resolution choices and the bottom tabs say which one is
  selected; the tab names are no longer read twice. The line under Sync Now is read out when a
  sync finishes, and so is the wizard's test ping result. Status that was only a colour is now
  also said: the home screen widget's dot reads "Last sync succeeded" or "Last sync failed", the
  dashboard reads "Last sync, failed" with the time, and a type whose permission is missing
  reads "Steps, permission missing". The cross that removes a webhook URL or header was 32dp
  and now takes the full 48dp touch target.
- Three German texts on the sync schedule addressed you as "Sie" while the rest of the app says
  "du"; they now say "du" too. The notification setting for a single failed sync read
  "fehlgeschlagenen" and now reads "fehlgeschlagener".
- A payload kept in full, a few hundred KB for a busy sync, was put on screen whole when you
  opened its row in the Logs tab or the data preview, which could stall the screen or run the
  app out of memory, the more so with an accessibility service on, such as a password manager.
  Both now show the first 12,000 characters and say how many there are and where the rest is:
  the JSON log export, or Export on the tab. A payload that was stored shortened says so, since
  the export does not have the rest either. TalkBack reads "Payload" and the number of
  characters instead of the whole payload, and a row's payload is formatted without holding up
  the screen.
- Text in the app's accent and status colours was hard to read. In the light theme green, purple,
  blue, amber and red words on white came out at 2 to 3.9:1, under the 4.5:1 that small text
  needs, and the white titles on the tab headers were 2.6:1 on green. Words in those colours
  now use a darker shade of the same colour in the light theme and a lighter one where the dark
  theme needed it, so every one reads at 4.5:1 or more, also in status pills and selected
  chips. The tab headers and filled buttons carry dark text, black on Screen Time's purple.
  Text buttons, a focused field's label and error messages follow, since the light theme's
  primary and error colours are now those darker shades. Tiles, icons, switches and the brand
  green itself are unchanged, and the iOS app already worked this way.
- A sync that Android ended at the wrong moment could lose what it had read. The app stored how
  far it had read the moment the post returned, but only put a payload that failed in the outbox
  after that, once Receive was done, so an app killed in between had moved past those records
  without keeping them anywhere: they never reached Home Assistant and no later sync read them
  again. That went for a sync's records, a payload with only deletions, and the Screen Time
  week. A sync now writes its payload to the phone before it moves anything, sends it, and
  deletes that copy once a webhook accepted it; when the post fails or the sync is stopped or
  killed, the copy waits in the outbox and the next sync delivers it. A stopped sync therefore
  now leaves its payload in the outbox instead of reading the same records again next time. The
  other sync that the Quick Settings tile starts alongside does not send a payload that is still
  on its way. The iOS app already worked this way.

### Security

- An encrypted settings file says how many PBKDF2 rounds its key takes, and the app took any
  number: a crafted file asking for billions could keep the import busy for an hour or more.
  A file asking for fewer than 100,000 or more than 2,000,000 is now refused before any key is
  derived, as on the iPhone (both apps write 210,000). Unlocking a file also no longer holds up
  the screen.
- The password of a settings export needs at least 8 characters and has to be typed twice.
  A typo in a password typed once, behind dots, left a file nobody could open.

## [1.21.2] - 2026-09-28

### Fixed

- A sync could use up Health Connect's read quota, after which it failed to read most data
  types. Health Connect counts every page the app reads. When a source keeps rewriting
  records, as Fitbit does with a day of calorie minutes, a sync sent them in up to eight
  payloads and read every data type again for each one. Now only the types still sending read
  again, the daily totals are asked for once per sync, records that hold one value each are
  read in pages of 5000 instead of 1000, and total calories go out 1000 at a time instead of
  200. A sync that still meets the quota stops reading there, and the next one reads on
  ([#73](https://github.com/owen282000/life-dashboard-companion-app/issues/73)).
- A deletion for a data type that was still sending a backlog waited until the backlog was
  sent, and for a type that never caught up, such as Fitbit's calories, it never went out.
  It now goes out with the first payload, unless Health Connect still holds the record.

## [1.21.1] - 2026-09-27

### Fixed

- A night of sleep or a day of calories that Fitbit revised could disappear from a receiver.
  Fitbit revises by deleting records and writing them again under the same ids, and the app
  sent the records and also named them in `deleted_records`. A record that exists again is
  no longer named deleted
  ([#71](https://github.com/owen282000/life-dashboard-companion-app/issues/71), [#72](https://github.com/owen282000/life-dashboard-companion-app/issues/72)).
- The home screen widget showed its text in English only. "Records today", the time of the
  last sync and "No syncs yet" now follow the phone's language (Dutch and German).
- Two log rows written at the same moment, for example by a Health Connect sync and a Screen
  Time sync, could lose one of them, and sometimes the stored payload of the other. The
  Logs tab now keeps every row.

## [1.21.0] - 2026-09-27

### Changed

- The setup wizard, the About page and the destination step recommend the Life Dashboard
  integration: install it from HACS, scan its code, and Home Assistant keeps the history in
  its long-term statistics. The MQTT option is now called "MQTT broker", is meant for setups
  that already run one, and says that only the latest value of each type is sent.
- Grant in the Health Connect tab asks for the data types you switched on, for example the 8
  from the setup wizard, instead of all 35, together with background access, so a sync from
  the Quick Settings tile, the automation broadcast or a schedule set up later can read. If
  you chose "later" in the wizard, Grant still lists every type so you can pick them in
  Health Connect. History access is only asked for by the "Grant history access" button in
  the Backfill dialog. Nothing changes for anyone who already granted everything.
- Switching on a data type you have not granted yet asks for that type's permission only, and
  the switch turns on by itself once you grant it. The data type list shows a lock on every
  type without permission; before, all types looked unlocked as soon as any permission was
  granted.
- The outbox holds up to 700 undelivered Health Connect syncs, where it held 50 shared with
  Screen Time: a week of failed syncs at the 15 minute interval, and more at longer
  intervals. After a long outage the backlog is sent in turns of at most two minutes per
  sync, so it can take a few syncs to clear.
- During an outage Screen Time keeps only its newest week in the outbox, which covers all 7
  days. When the server is back it gets one Screen Time payload instead of a stack of the
  same week.
- Each type's query window reaches back a week before the last sync that read the whole type,
  instead of a week before now. A phone that did not sync for more than a week (deep sleep,
  force-stopped, Health Connect not answering) therefore still sends what other apps wrote
  during the pause for the days before it, up to 30 days back, and a backlog that takes
  several syncs keeps that window until it is empty. When syncs run normally the window is
  one sync interval longer than before; records already sent are not sent again. A type
  switched on again after a long break, or given its permission back, catches up on 30 days
  of changes instead of 7. The first sync after the update reads as before.
- `_diagnostics` gives every type `read_from`, where its window started, and
  `lookback_gap_from`, which is null unless a pause was longer than 30 days: it then names
  the start of the range that may be missing, so it can be backfilled. A payload also names
  records that another app wrote or edited long after their own time, too far back for the
  window, in the new `records_outside_window`, per type with the count and the time range, so
  a backfill of that range can send them. When nothing else goes out it is sent in a payload
  of its own, as deletions are, and that sync reports success with 0 records.
- Screen Time payloads now carry a top-level `sequence`, from the same counter Health Connect
  payloads use, so it goes up across everything the phone sends. A receiver can tell from it
  which Screen Time week is the newest when a week from the outbox arrives late. Because both
  sources share the counter, the numbers in Health Connect payloads now skip wherever a
  Screen Time payload went out in between; they still only go up, so keep the highest number
  per source. Screen Time payloads from 1.20.0 and older have no `sequence`, and MQTT and the
  in-app preview are unchanged.
- The Tasker and MacroDroid broadcast `com.owen282000.lifedashboard.ACTION_SYNC` starts at
  most one sync a minute: a second broadcast within a minute of the last accepted one is
  ignored. The Quick Settings tile is not limited.
- A settings export has a new per-section key, `urls_without_headers`, for the addresses that
  get no custom headers (see Security).

### Fixed

- A sync that Android stopped while the webhook was slow to answer showed up in the Logs tab
  as a failed delivery ("Job was cancelled"), and a stopped Screen Time sync did the same. A
  stopped sync now simply stops: no failed row, no step towards the failure notification,
  and what it had not delivered yet is sent by the next run.
- A stopped sync could keep the worker waiting for up to ten seconds, until the webhook's
  read timeout, before it let go. The request is now cancelled together with the sync.
- A scheduled sync that found Health Connect not answering, as can happen while the phone
  dozes, waited for it without limit while reading records or the day totals, the same way
  the deletion step did before 1.18.1. Every Health Connect call in the read step now gives up
  after ten seconds and the whole step after two minutes; a type that did not fit keeps its
  place and is read by the next sync, and `_diagnostics` says why in its `error`.
- A sync interrupted while the webhook had not answered yet could, with a data resolution
  set, count the samples of a still-open window twice: once from what it had stored and once
  from the next read. That window is now stored at the same moment as the sync's progress, so
  an interrupted sync leaves both as they were.
- After an outage, a sync that only delivered what had been queued left the failure
  notification, the red status and the old "Last sync" in place, although the data had
  arrived. Delivering queued data now counts as a successful sync.
- A payload the webhook refused sat at the front of the outbox and held back every payload
  queued after it. A refusal of the payload itself (HTTP 400, 413 or 422) is now skipped, so
  the rest is delivered. The refused payload stays queued for a week, in case a fix on the
  receiving side makes it welcome, and is then dropped with a log row.
- A webhook that answered HTTP 408 (request timeout) got six requests per sync instead of
  the three the retry rule allows, because the HTTP library repeated each one by itself.
- Importing a settings file exported without secrets emptied the MQTT broker's username and
  password, although such an import promises to keep the credentials on the device. They are
  now kept, for the same broker (host, port and TLS).
- A watch that uploaded a large backlog in one go could still produce one oversized payload,
  the situation the crash in #38 came from. Health Connect gives every record of one upload
  the same modification time, and the limit per sync could not stop inside such a group. The
  sync now also remembers the last record it sent, so every payload stays within the limit
  and the rest follows in the next pass. Nothing is sent twice or skipped across the update.
- A sync stopped by Android while Receive was still fetching measurements sent the windows of
  its data resolution again on the next sync. It now stores its progress as soon as the
  payload is delivered.
- Deletions read just before Android stopped a sync could be lost: the app had moved on in
  Health Connect's list of changes without storing them. Each type's deletions are now stored
  before the app moves on.
- A backfill window that a data type could not be read for was still marked complete, which
  tells a receiver to drop that type's records in the window. The backfill now stops there
  with a message, and a rerun sends the window again.
- A sync in which Health Connect answered for no data type at all reported "no new data". It
  now reports the failure, and a run of them raises the failure notification.
- Time spent in the Settings app never counted as screen time. The app leaves launchers out,
  as Digital Wellbeing does, and Settings answers the same request as a launcher for the
  screen Android shows before the phone is unlocked after a restart.
- Time in an app during a session that crossed midnight, or the day boundary you set, counted
  only on the old day: a session from 23:50 to 00:10 gave 10 minutes to the old day and none
  to the new one. It now gives 10 minutes to each. An app opened up to 6 hours before the day
  starts is taken into account, and its "last used" on the new day is the start of that day.
- The outbox could drop undelivered Health Connect syncs without a word once it was full.
  Each dropped sync is now a failed row in the Logs tab, and a notification, "Health Connect
  data was lost", counts them. It stays until you swipe it away, also after the next
  successful delivery, and appears even with "Notify after failed syncs" off. The same goes
  for Screen Time days that fall out of the queued week before they were delivered.
- A crash or power loss while the outbox was being written could lose the payload being
  queued.
- Tapping the Quick Settings tile while a scheduled Screen Time sync was running sent the
  week twice at the same time. The second sync now waits and then runs.
- Starting a backfill while a sync ran (Sync Now, a scheduled sync, the tile or the
  broadcast) ran both side by side. The backfill now shows "Backfill waits for the running
  sync to finish..." and starts by itself when the sync ends. Sync Now is disabled while a
  backfill runs, and a scheduled or tile sync waits until the backfill is done.
- Scanning a Home Assistant code in the setup wizard did not look like it worked. The scan
  card now says "Code read", the Webhook card opens with the paired address and a working
  Send Test Ping button, and the summary shows the address instead of "Destination: not yet".
  The test ping is signed with the paired secret, and the message after pairing names the
  real button: "Send Test Ping" in the wizard, "Test ping" on the tabs.
- Finishing the wizard added the paired address to a section you had left unchecked in the
  pairing dialog. It now keeps the sections you chose there.
- The privacy policy link on Health Connect's permission screen opened the app's home screen.
  It now opens a Privacy policy screen in the app, in English, Dutch or German: what the app
  reads and writes, where the data goes, what stays on the phone, your control and a contact,
  with a link to the full policy on GitHub. Back returns to Health Connect. The Privacy
  policy card in About opens the same screen.
- On the nights summer time starts or ends, fixed sync times such as 07:00 ran an hour early
  or late, and "every N minutes" schedules with a weekday filter or quiet hours got uneven
  gaps. Fixed times now keep their time, a time in the hour that is skipped in March, such as
  02:30, runs at 03:30, and a time in the hour that repeats in October runs once.
- Adding a webhook address by hand that was already in the section's list added it a second
  time, so every payload went there twice. It is now kept once.

### Security

- A webhook that answers with a redirect is followed only on the same host, for example from
  http to https or to add a trailing slash, and the payload goes there as the same POST with
  its signature and headers; before, a 301 or 302 turned it into a GET without the payload
  that still counted as delivered. The log notes the new address so you can enter it and
  skip the extra request. A redirect to another host, or from https down to http, is not
  followed, so a payload and your custom headers never reach an address you did not enter:
  that delivery fails without retries, the payload stays in the outbox, and the log says
  where the redirect pointed.
- A webhook address added by QR pairing no longer gets the section's custom headers, such as
  API keys; addresses you typed yourself get them as before. While the section has headers,
  the Webhook card says so under a paired address. To send the headers there anyway, remove
  the address and type it in by hand. Addresses paired before this update are not marked and
  keep their headers. Importing a settings file without secrets no longer sends the headers
  already on the phone to addresses they did not go to before.
- The MQTT card and the setup wizard show a red hint when TLS is off and the broker is not on
  your LAN or VPN, such as broker.hivemq.com or a public IP address. Addresses like
  192.168.x.x, homeassistant, *.local and Tailscale do not trigger it.
- Exported logs, data and settings backups no longer pile up in the app's cache: each export
  replaces the previous file, and one older than a day is removed when the app starts.

## [1.20.0] - 2026-09-26

### Added

- Receive: measurements from Home Assistant into Health Connect, for a scale or blood
  pressure monitor that talks to Home Assistant and not to the phone
  ([#62](https://github.com/owen282000/life-dashboard-companion-app/issues/62)). The Receive
  row on the Health tab switches it on per type (weight, height, body fat, lean body mass,
  bone mass, body water mass, blood pressure), and each type asks for its own Health Connect
  write permission the moment its switch goes on; nothing else is declared, and the bulk
  permission request stays read-only. The measurements ride back in the Life Dashboard
  integration's answer (0.7.0 or later) to the webhook the app already sends, signed under a
  key derived from the shared secret and bound to that request, so a proxy or a cloudhook in
  between cannot inject a reading. Readings keep their own time, a resend is an upsert on the
  integration's id and version, readings older than 30 days need "Accept older measurements",
  and the app tells the integration per reading what happened. Every round is a row in the
  Logs tab, "Health · from Home Assistant", folding out to each reading; the line under Sync
  Now says how much was written. A sync with nothing to send still asks the integration once.
- Phone name, under Advanced on both tabs, for two phones on one MQTT broker. A named phone
  publishes under its own device, "Life Dashboard Companion (name)", and its own topics
  under the base topic; a phone without a name publishes exactly as before.

### Changed

- Records the app wrote itself through Receive are left out of the outgoing payload and of
  `deleted_records`, so what came from Home Assistant never goes back to it. `_diagnostics`
  counts them per type as `own_records_skipped`.
- The release manifest declares write permissions for the seven Receive types. They are
  requested one at a time, when a type is switched on under Receive.

### Fixed

- The dashboard card on the Health tab showed the result of Sync Now only after switching
  tabs; it now refreshes as soon as the sync is done, as the Screen Time card already did.
- The privacy policy said the app does not use the camera. The pairing scanner of 1.16.0
  does, and the policy now says what for: only while the scanner is open, with every frame
  decoded on the device and never stored or sent.

## [1.19.0] - 2026-09-26

### Added

- Client certificates (mTLS) for webhooks, for a Home Assistant or receiver behind a
  reverse proxy that requires one. Install the certificate in Android's credential store,
  then pick it under Advanced settings in Health Connect or Screen Time; the choice applies
  to both, and the Advanced row's summary names it. Every webhook request presents it,
  including background syncs, and the picker only appears again when you change it. The
  choice is device specific, so it is not part of the settings export; on a phone restored
  from Android's backup the webhook log asks you to choose the certificate again. MQTT is
  unaffected. Contributed by [majorcs](https://github.com/majorcs)
  ([#69](https://github.com/owen282000/life-dashboard-companion-app/pull/69)).

### Fixed

- The plain HTTP switch and the client certificate are one setting for both tabs, but
  a change on one tab only showed on the other after a restart. Both tabs now re-read
  those two settings every time they come into view; an unsaved edit on the tab you
  return to is kept.
- A failure to set up the webhook client, other than a missing certificate, ended the
  sync without a row in the webhook log. Every such failure is now logged per URL.

## [1.18.1] - 2026-09-21

### Fixed

- Background syncs could stall after 1.18.0 while a sync started from the app worked, and
  opening the app delivered the backlog within a minute or two. The deletion step added
  in 1.18.0 asks Health Connect about every enabled type before anything is delivered,
  and a scheduled run that started with the phone dozing could sit in that step until
  Android stopped the worker; Android then retried it with a growing delay, and it hung
  again, until the app was in the foreground and Health Connect answered. The step now
  has a limit of five seconds per type and twenty in total. A type that does not fit
  keeps its place in the change feed, is named in `deletions_unavailable` for that
  payload, and the records go out regardless.
- A schedule with fixed times, chosen weekdays or quiet hours queues each run as the
  previous one finishes. That enqueue was handed to a helper thread, which left a small
  window in which the process could end before it happened. It is now done on the
  worker's own thread, before the worker returns.

## [1.18.0] - 2026-09-18

### Added

- Deleting a record in Health Connect now reaches your webhook. The app follows Health
  Connect's own change tracking and names the records that are gone in a
  `deleted_records` list, so a receiver can drop exactly those. Apps that edit by
  replacing, such as Cronometer, previously left both the old and the new record on the
  receiver ([#61](https://github.com/owen282000/life-dashboard-companion-app/issues/61)).
- Types whose deletion tracking was interrupted, which happens after 30 days without a
  sync, are named in `deletions_unavailable`. Reconcile those against a backfill window
  instead of the incremental payload.
- The last payload of a backfill window carries `window_complete`, which makes the
  window a snapshot: a receiver may treat records it holds in that range that were not
  in the window as deleted. An empty window now sends one payload as well, so an empty
  window is distinguishable from an unreported one.
- Every Health Connect payload carries a `sequence` counter that only goes up, so a
  retry that arrives after a newer payload can be recognised as stale instead of undoing
  it. Previewing data does not take a number: looking is not sending.
- A sync whose only change is a deletion, which is what removing a meal without adding
  one looks like, now sends a payload carrying the deletion and no records, and reports
  it like any other delivery instead of saying there was no new data. Deletions are kept
  until a payload has actually been delivered or stored in the outbox, so a sync that
  finds nothing to send, or one that is interrupted, hands them to the next sync instead
  of losing them.

## [1.17.1] - 2026-09-16

### Fixed

- A backfill sent the daily totals only in the first payload of each window, so a
  receiver saw the later chunks of a busy window as a window without totals and warned
  about it. Every payload of a window now carries them; the figures are identical, and
  a receiver that keeps history ignores a repeat.

## [1.17.0] - 2026-09-16

### Added

- A backfill now carries the daily totals for every day it covers, in the first
  payload of each window. A receiver that keeps history, such as the Home Assistant
  integration from 0.3.0, gets each past day's real step, distance and calorie total
  from Health Connect's own deduplicated figures, instead of only today's. A day cut
  by a window boundary is sent by both windows with the same numbers.

## [1.16.4] - 2026-09-16

### Fixed

- The scanner could not read the pairing code Home Assistant shows in its dark theme, which draws light modules on a dark card. The decoder read dark-on-light only; it now tries both. This, not distance or resolution, is why the phone's own camera app read the same code instantly

## [1.16.3] - 2026-09-16

### Fixed

- The scanner still read nothing where the phone's own camera app read the same code instantly. Measured against blurred frames rather than guessed at: what decides it is how much of the frame the code covers, not the resolution and not the length of the code. A code filling a quarter of the frame is unreadable at any resolution; one filling most of it survives several pixels of camera softness
- The scanner now shows an outline for the code to fill and says that closer is better, pins autofocus to the middle of the frame rather than letting it settle on the text under the code, applies a modest zoom, and analyses at 960p

## [1.16.2] - 2026-09-16

### Fixed

- The scanner in the app analysed camera frames at 640x480, which is not enough to read a pairing code through the softness of a hand-held camera: the phone's own camera app reads the same code instantly because it works at full resolution. Frames are now analysed at 720p, which roughly doubles the blur the code survives

## [1.16.1] - 2026-09-15

### Fixed

- The scanner in the app found nothing while the phone's own camera read the same pairing code instantly. A camera buffer routinely ends before the padding of its last row, and the decoder demanded a full padded rectangle, so every real frame was discarded before it could be read

## [1.16.0] - 2026-09-15

### Added

- **Pairing by QR code.** The Life Dashboard integration for Home Assistant shows a code; point the phone's camera at it and the app opens with the address and the signing secret ready to confirm. The link is an Android App Link verified against the release signing certificate, so the browser is skipped entirely and no other app can claim it. Without the app installed the code opens a page that says where to get it
- **A scanner in the app**, on the Webhook card of both tabs and as the first option in the setup wizard, for a code on a screen you are already looking at. CameraX with ZXing, no Play Services: the APK still carries none. The camera permission is asked for at the moment of scanning, never at startup, and the camera is released as soon as the scanner closes
- One confirmation dialog for every route in: it names the receiver and its host, offers the sections the receiver accepts, says when a section's existing secret will be replaced, and turns on plain HTTP only when the address needs it and you agree. Nothing is written until you tap **Pair**, and a scanned code never decides which data types are synced or when
- Pairing appends the address rather than replacing the list, so a second receiver you configured by hand survives

### Changed

- The setup wizard stores a signing secret, which it never did: it wrote addresses only, so a scanned secret would have been dropped on a first run
- On a webhook card with no receiver yet, scanning leads: a full-width button above the address field. Once one exists the button is gone and the scanner is a QR icon inside the field, so the card is no taller than before

## [1.15.0] - 2026-09-15

### Added

- A **Generate** button next to the HMAC signing secret: 32 bytes from SecureRandom, hex encoded, so nobody has to invent a passphrase. Copy puts it on the clipboard flagged as sensitive on Android 13 and later

### Changed

- The Screen Time day boundary is chosen with a clock instead of typed as a number, shown in the device's own 12 or 24 hour format. Stored exactly as before, as a whole hour
- Three labels that were still English for Dutch and German users (the logs and about screens in the app switcher, the Quick Settings tile) are translated
- The plain HTTP switch says plainly that it covers both tabs and leaves MQTT alone

## [1.14.0] - 2026-09-14

### Added

- Sync at fixed times of day per tab, such as 08:00 and 21:00, so the night's sleep reaches Home Assistant before you get up. With an optional weekday filter and quiet hours, next to the interval the app has always had
- Data resolution per type: send dense series (heart rate, HRV, steps, calories and five more) as one value per 1, 5 or 15 minutes, or per hour, instead of every record. Measured values are averaged with their min and max, quantities are summed. Defaults to every record, so existing receivers are unaffected
- Both settings travel with the settings backup

Details on the scheduling rules and the bucketed payload shape are in [docs/features.md](docs/features.md) and [docs/webhook.md](docs/webhook.md#data-resolution).

### Changed

- The line under the save button reports the real schedule and destination ("At 08:00, 21:00 to MQTT") instead of assuming an interval and ignoring MQTT

## [1.13.4] - 2026-09-14

### Fixed

- MQTT states for weight, temperatures, percentages and other decimal values are rounded to one or two decimals instead of the raw double (`78.2006048685296`)
- Home Assistant forced two decimals on distance, weight and duration sensors (`5,921.00 m`); the discovery config now sets `suggested_display_precision` to match the state

### Added

- Emulator seeder for a week of Health Connect data, and a Docker compose file with Mosquitto and Home Assistant (docs/building.md)
- A screenshot of the Home Assistant device in the README and a two-minute "phone to Home Assistant" quickstart in docs/usage.md

## [1.13.3] - 2026-09-14

### Changed

- Home Assistant sensors for steps, distance, active and total calories now carry today's total from the deduplicated daily aggregate instead of the last record. "Steps (latest record): 7 steps" was true and useless; "Steps Today: 6,412" is what a dashboard wants. Their entity ids change accordingly (`steps_today` and so on); the old `steps`, `distance`, `active_calories` and `total_calories` sensors are removed from the broker and from Home Assistant on the next publish
- Every MQTT publish sends the full set of sensors the app has mapped so far, not only the types that had new records in that sync. Pointing the app at a new broker, or adding Home Assistant later, now shows the whole device after one sync instead of one sensor at a time

### Fixed

- A backfill now counts on the Health Connect dashboard: "today" and "last sync" said nothing while thousands of records went out, because only regular syncs recorded the status

## [1.13.2] - 2026-09-14

### Added

- `scripts/webhook-receiver.py`: a zero-dependency receiver for a laptop on the same network that prints every POST the app sends, appends it to a JSON Lines file and can verify the `X-Signature` header. The quickest way to see what the app sends before building a real receiver

### Fixed

- Screen time payloads named an app the phone would not let us look up after the last segment of its package, so `org.wakingup.android` became "android". The fallback now skips generic segments and picks the one that says something: `wakingup`
- The Health Connect dashboard could show more records today than in its lifetime: "today" counted every sync including screen time apps, "lifetime" only webhook deliveries. Both counters are now kept per source, so the Health tab counts Health Connect records only, and MQTT-only syncs count too. The widget keeps the app-wide numbers; per-source lifetime totals start counting from this version

## [1.13.1] - 2026-09-14

### Fixed

- A Health Connect sync with MQTT as the only destination failed with "No webhook URLs configured". The wizard and the tabs have accepted MQTT alone since 1.13.0, and Screen Time already synced that way; the Health Connect sync manager still insisted on a webhook URL. It now reads and publishes without one, exactly like Screen Time
- Backfill on an MQTT-only setup failed with the same message. Backfill posts history to webhooks and MQTT only carries the latest value of each type, so the button now says exactly that instead of opening the dialog

## [1.13.0] - 2026-09-14

### Added

- A first-run wizard. A fresh install used to open on the Health tab with 33 toggles and no destination; the wizard now asks what to sync (Health Connect, Screen Time or both), where the data should go (a webhook URL with a test ping, an MQTT broker, or both, applied only to the sources you picked) and which health data types to start with (the essentials, all 33, or none yet), then ends with a summary and the two permissions that remain. Every choice stays editable on the tabs, the whole thing can be skipped, and it is available in English, Dutch and German
- The wizard shows the plain-HTTP opt-in switch as soon as an `http://` URL is typed, so a receiver on the LAN can be tested from the first screen instead of failing with a pointer to the logs
- MQTT publishes now appear in the Logs tab next to webhook deliveries, with the broker, sensor count and the error when the broker could not be reached. Until now a failing broker was only visible as a one-line status inside the MQTT settings
- A test ping on the Screen Time tab, which has its own webhook URLs but had no way to test them
- The failure-notification setting is reachable from both tabs; it was always app-wide but only shown on Health Connect
- `PRIVACY.md`, a plain-language privacy policy, linked from the About screen together with the documentation, the changelog of the running version, the issue tracker and the licence

### Changed

- The three tabs and the About screen share one design: a coloured status banner per tab (green Health Connect, purple Screen Time, blue Logs) with the permission state and one action in it, a stat card, settings grouped in cards of icon rows with the webhook and MQTT destinations side by side, one sync button with the secondary actions as tiles, and the About hero on the brand's dark ground with the mark. The red "permissions required" card is gone; the banner says it instead. Logs rows show the destination as an icon and the outcome as one word, and the log filter is a segmented control
- The two sync tabs are backed by view models (`HealthConnectViewModel`, `ScreenTimeViewModel`) exposing `StateFlow`, with the screens as pure functions of state and callbacks and the shared sections (webhook, MQTT, notifications, data types) as separate composables. One `PreferencesManager` is shared through the Application instead of being constructed per screen. The models are unit tested against in-memory fakes: validation, change tracking, sync outcomes and permission flows
- A destination is now either a webhook URL or MQTT: saving and syncing no longer insist on a webhook URL when MQTT is enabled
- The release build is minified with R8 and resource shrinking, which takes the APK from 19 MB to about 4 MB. Keep rules cover HiveMQ/Netty, kotlinx.serialization, enum names stored in preferences and WorkManager's Room database; `mapping.txt` is kept with the build outputs
- The About screen is fully translated (English, Dutch, German); it was the last screen with hardcoded English
- The Screen Time dashboard shows the icon of today's most used app instead of its package name
- `docs/features.md` compares the app with the Home Assistant companion app's Health Connect sensors on verifiable points (data types, history window, Android versions, screen time), each checked against the companion app's documentation and issue tracker on 14 September 2026. The README links to it

## [1.12.2] - 2026-09-14

### Fixed

- Reproducible builds: the version name is now the exact tag whenever HEAD sits on one, regardless of the state of the working tree. F-Droid's builder modifies the tree before building (it strips signing configs and removes the Gradle wrapper jar), which made `git describe --dirty` stamp `1.12.1-dirty` into the manifest while the released APK says `1.12.1`; that one word was the only difference between the two builds and failed the verification

### Changed

- The APK no longer carries Google's dependency-info block, a dependency-tree blob in the signing block encrypted with a Google public key that only Google can read. IzzyOnDroid's scanner flagged it and F-Droid checks for the same; it served no purpose outside Google Play
- The brand mark gained broadcast arcs: the pulse ends in a dot that radiates the signal, chosen from three explored directions because measuring AND publishing is what the app does. Applied to the launcher icon, the store icon, the feature graphic and the repository banner, all rendered from the sources in `docs/brand/`

## [1.12.1] - 2026-09-14

### Added

- `version.properties` with the literal version name and code, generated by `scripts/prepare-release.sh` and verified against the tag by the release workflow. F-Droid's update checker cannot evaluate a Gradle build that derives its version from git tags, so this file is what lets F-Droid detect new releases automatically
- Store listing assets for IzzyOnDroid and F-Droid: a 512x512 icon and a 1024x500 feature graphic in the banner's visual identity, plus four fresh screenshots showing real syncs. The sources live in `docs/brand/` as HTML and render with headless Chrome, the same way the repository banner was made, so they can be regenerated instead of only existing as pixels
- The repository banner itself is regenerated from a committed source (`docs/brand/banner.html`) in the same identity

### Changed

- The launcher icon is now the brand's heartbeat mark on the dark ground from the banner, replacing the Android Studio template robot that had shipped since the first commit. The adaptive icon carries a monochrome layer for themed icons, and the legacy density PNGs are rendered from the same source
- The README shows four screenshots instead of three, adding the MQTT and sync-actions view
- The store description now leads with MQTT and Home Assistant Discovery, which it previously did not mention at all, and covers the outbox, settings backup, backfill and the three interface languages

## [1.12.0] - 2026-09-13

### Added

- Settings backup and restore under About: export every webhook URL, header, signing secret, MQTT broker and toggle as a JSON file and import it on another device. Exports that carry secrets are encrypted with a password (AES-256-GCM, PBKDF2-HMAC-SHA256); a secret-free export can be shared without handing over access. Importing shows a preview of what will be replaced first, and sync watermarks and logs are deliberately left out. Documented in `docs/settings-backup.md`
- Per-version release notes for F-Droid, IzzyOnDroid and Play under `fastlane/metadata/android/en-US/changelogs/`, generated from `CHANGELOG.md` by `scripts/generate-fastlane-changelogs.sh`. The release workflow fails when the file for the tag being released is missing or stale, so store notes cannot drift from the changelog

### Changed

- Dutch and German translations of the whole interface. Android picks them up from the system language; other locales fall back to English, and translations for more are welcome as a pull request
- Every user-facing string on the Health Connect, Screen Time, Logs and MQTT screens now lives in `strings.xml`, so the app can be translated. Counts use plurals rather than a hardcoded "(s)", and sentences are built with format arguments instead of concatenation. MQTT sensor names stay English on purpose: they are published to Home Assistant. A CI check fails the build on new hardcoded UI text, since Android's own lint only inspects XML layouts and cannot see Compose
- Webhook logs moved out of the main settings file into their own store, with raw payloads kept as separate files. Retention is now capped by total size (5 MB) as well as entry count, so a run of large payloads can no longer grow storage without bound; previously 100 busy syncs could retain roughly 25 MB in a single value that was rewritten on every delivery
- Raw payloads are truncated to 16 KB by default, with a "Keep full payloads" switch on the logs screen for debugging. Payloads are raw health data, so they are also excluded from backup
- Webhook delivery and log writing now run on the IO dispatcher; the test ping previously did both on the main thread
- `targetSdk` raised to 36 (Android 16), which Google Play requires for new apps and updates from 31 August 2026

### Fixed

- Section headings no longer collide with the label beside them when a translation is long: "Sync Interval" is 13 characters in English but 26 in German, and the title and subtitle overlapped

### Security

- Webhook auth headers, HMAC signing secrets and MQTT credentials are excluded from Android cloud backup and device transfer. They are stored with a key that never leaves the device, so a restored copy could not be decrypted anyway; excluding them also removes any chance of a keystore outage shipping them off-device
- A keystore outage no longer falls back to plain, backup-eligible storage. Secrets are now kept in memory for that process only, so they are never written unencrypted; the affected screens show a banner explaining why saved credentials are temporarily unavailable

### Notes

- Existing webhook logs are carried over, but their stored payloads are dropped on first launch after the update. New syncs store payloads as usual

## [1.11.0] - 2026-09-13

### Added

- Plain HTTP webhooks for private LAN/VPN receivers, behind an explicit "Allow plain HTTP webhooks" switch; `http://` URLs stay refused with a clear log message until it is on, and HTTPS remains the default (#51)
- MQTT publishing for Screen Time: today's and yesterday's total minutes and today's most used app, with the top five apps as attributes, under the same Home Assistant device; screen time syncs also run with MQTT alone and no webhook configured (#52)
- MQTT settings are now symmetric: Health Connect and Screen Time each have their own switch and base topic, share one broker connection by default (existing settings carry over), and either section can switch to its own broker
- Collapsible "Advanced" and "Notifications" cards, with the daily totals and plain HTTP switches under Advanced
- At-a-glance card at the top of the Screen Time tab: today's minutes, today's most used app, last sync, and a 7-day minutes sparkline, matching the Health Connect dashboard card
- `raw_min_time`, `raw_max_time` and `raw_latest_modified_time` in `_diagnostics`, describing everything Health Connect returned before the incremental filter, so a receiver can tell "the source app has not written it yet" from "the filter dropped it" (#53)
- `docs/DATA_SOURCES.md`: what individual source apps (Fitbit, Cronometer, Health Sync, Zepp, Garmin) do and do not write, and how screen time is measured

### Changed

- README documents `daily_totals`, server-side deduplication on `uuid`, the `_diagnostics` block, the screen time method, and adds troubleshooting entries for inflated totals, late nightly metrics, missing nutrients and inflated screen time

## [1.10.2] - 2026-09-12

### Fixed

- Screen time per-app minutes could run far above Digital Wellbeing (a weather app at 15 hours on a 4-hour day): a session whose ACTIVITY_PAUSED was never recorded was counted until the end of the day, and activities of the same app overwrote each other's start time. Sessions are now tracked per activity and also closed by ACTIVITY_STOPPED, screen off, keyguard and shutdown events, and System UI and the launcher are excluded as Digital Wellbeing does

## [1.10.1] - 2026-09-12

### Added

- Regression test that pins chloride, energy from fat, folic acid and thiamin through the full `NutritionRecord` to JSON path, after a user report showed them absent from an export: the fields were never written by the source app, and the test guarantees they appear as soon as Health Connect carries them

## [1.10.0] - 2026-09-09

### Added

- Full `NutritionRecord` export: food name, meal type and all 38 further nutrients Health Connect exposes (fibre, sugars, fat subtypes, cholesterol, minerals, vitamins, caffeine), with units in the key suffix; the four original keys are unchanged and every new field is optional (#50)

## [1.9.0] - 2026-09-08

### Fixed

- Heart Rate Variability could never be granted: the manifest declared a nonexistent permission name (#40); the permission request list is now derived from the data type enum, which also restores the 10 newest types that had silently dropped out of the permission dialog
- Heart-rate backlogs no longer grow faster than they drain: a sync run now delivers up to 8 capped batches instead of one (#38)
- Records sharing the cap-boundary modification time are no longer skipped: the oldest-first cap extends across timestamp ties, keeping the strict watermark filter safe (#38)
- Backfill drains each 3-day window until exhausted instead of dropping dense data past the per-type cap (#39)
- The pagination loop treats an empty page token as completion, matching Health Connect behavior (#38)

### Added

- `READ_HEALTH_DATA_HISTORY` permission, requested with the normal flow and surfaced in the backfill dialog: without it Health Connect caps reads at 30 days before the first grant, so long backfills silently returned only recent data (#39)

## [1.8.0] - 2026-08-27

### Added

- MQTT publishing with Home Assistant Discovery: every enabled data type appears automatically as a sensor in Home Assistant
- Store-and-forward outbox: failed webhook deliveries are queued on disk and drained on the next sync, so no data is lost when the receiver is down
- Historical backfill of up to a year of data (30/90/365 days) in small windows with progress feedback
- Optional deduplicated daily totals (steps, distance, calories) in the payload via Health Connect's aggregate API
- At-a-glance dashboard card on the Health screen: records today, lifetime records, last sync status, and a 7-day steps sparkline
- Published JSON Schema for the webhook payload (`docs/webhook-schema.json`)
- Ready-made self-hosted receiving stack (Postgres, receiver, Grafana) linked from the README

### Fixed

- Sync watermark now uses `lastModifiedTime` instead of the record timestamp, so records backfilled by watch apps are never skipped
- Data preview now also shows the daily totals when enabled

## [1.7.0] - 2026-08-27

### Added

- 8 new data types (33 total): basal metabolic rate, VO2 max, skin temperature, basal body temperature, intermenstrual bleeding, ovulation test, cervical mucus, and sexual activity
- Fastlane metadata and privacy policy for F-Droid/IzzyOnDroid distribution
- OpenSSF Best Practices passing badge

## [1.6.1] - 2026-08-26

### Added

- Build provenance attestation published with every release (verifiable via `gh attestation verify`)

### Changed

- Gradle wrapper checksum validation in CI and dependency updates (AGP 9.3.2, OkHttp 5.5.0, Gradle 9.7.1)

## [1.6.0] - 2026-08-26

### Added

- Record `uuid` on every payload record (stable Health Connect id) for server-side deduplication, matching the iOS companion app
- Local notification after repeated sync failures, with an in-app toggle and threshold (3/5/10)
- Home screen widget with last sync result and records delivered today
- Quick Settings tile to trigger an immediate sync
- Broadcast intent (`com.owen282000.lifedashboard.ACTION_SYNC`) so Tasker/MacroDroid can trigger syncs
- Send Test Ping button to verify webhook configuration without waiting for real data
- About screen easter eggs
- CodeQL analysis, OpenSSF Scorecard, and Android Lint in CI

### Changed

- Webhook headers and HMAC signing secrets moved from plain SharedPreferences to Keystore-backed EncryptedSharedPreferences, with silent migration

## [1.5.0] and earlier

See the [GitHub Releases](https://github.com/owen282000/life-dashboard-companion-app/releases) for full notes. Highlights: HMAC payload signing and smart retries (1.4.x), menstruation data types and resilient reads (1.3.x), payload pagination and bounded batches (1.2.x), initial Health Connect and Screen Time sync (1.0.0).

[Unreleased]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.21.2...HEAD
[1.21.2]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.21.1...1.21.2
[1.21.1]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.21.0...1.21.1
[1.21.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.20.0...1.21.0
[1.20.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.19.0...1.20.0
[1.19.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.18.1...1.19.0
[1.18.1]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.18.0...1.18.1
[1.18.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.17.1...1.18.0
[1.17.1]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.17.0...1.17.1
[1.17.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.16.4...1.17.0
[1.16.4]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.16.3...1.16.4
[1.16.3]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.16.2...1.16.3
[1.16.2]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.16.1...1.16.2
[1.16.1]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.16.0...1.16.1
[1.16.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.15.0...1.16.0
[1.15.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.14.0...1.15.0
[1.14.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.13.4...1.14.0
[1.13.4]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.13.3...1.13.4
[1.13.3]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.13.2...1.13.3
[1.13.2]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.13.1...1.13.2
[1.13.1]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.13.0...1.13.1
[1.13.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.12.2...1.13.0
[1.12.2]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.12.1...1.12.2
[1.12.1]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.12.0...1.12.1
[1.12.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.11.0...1.12.0
[1.11.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.10.2...1.11.0
[1.10.2]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.10.1...1.10.2
[1.10.1]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.10.0...1.10.1
[1.10.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.9.0...1.10.0
[1.9.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.8.0...1.9.0
[1.8.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.7.0...1.8.0
[1.7.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.6.1...1.7.0
[1.6.1]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.6.0...1.6.1
[1.6.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.5.0...1.6.0
[1.5.0]: https://github.com/owen282000/life-dashboard-companion-app/compare/1.4.1...1.5.0
