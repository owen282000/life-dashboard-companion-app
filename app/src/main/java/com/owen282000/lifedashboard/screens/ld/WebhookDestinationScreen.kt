package com.owen282000.lifedashboard.screens.ld

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.Destination
import com.owen282000.lifedashboard.Destinations
import com.owen282000.lifedashboard.FailureReason
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.TestPing
import com.owen282000.lifedashboard.WebhookLog
import com.owen282000.lifedashboard.WebhookSecret
import com.owen282000.lifedashboard.WebhookSupport
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.screens.copySensitive
import com.owen282000.lifedashboard.screens.text
import com.owen282000.lifedashboard.ui.ld.LDBanner
import com.owen282000.lifedashboard.ui.ld.LDBannerAction
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonKind
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDFieldRow
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDIcons
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDNoticeLine
import com.owen282000.lifedashboard.ui.ld.LDRefusedBanner
import com.owen282000.lifedashboard.ui.ld.LDRoute
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSection
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDTopBarAction
import com.owen282000.lifedashboard.ui.ld.LocalLDEntryId
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.rememberLDDraft
import com.owen282000.lifedashboard.viewmodel.DestinationDraft
import com.owen282000.lifedashboard.viewmodel.SettingsRules
import com.owen282000.lifedashboard.viewmodel.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One address with its own signing secret and headers (P2-13). Saved as a whole, or not at all: the
 * draft stays on this page, because a half typed host would clear the headers and the secret for
 * good. Generate and the copies from another address are offered only while the field is empty, so
 * nothing typed is overwritten by a tap.
 */
@Composable
fun WebhookDestinationScreen(section: LDSection, destinationId: String?, address: String?) {
    val ui = ldSection(section)
    val navigator = LocalLDNavigator.current
    val page = LocalLDEntryId.current
    val refusedNow = rememberRefusedNow(section)
    var draft by rememberLDDraft { ui.actions.destinationDraft(destinationId, address) }
    var problem by rememberLDDraft<UiMessage?> { null }
    var confirmRemove by remember { mutableStateOf(false) }
    var testRuns by remember { mutableIntStateOf(0) }

    val save = {
        problem = ui.actions.saveDestination(draft)
        if (problem == null) navigator.pop(page)
    }
    val stored = ui.webhook.destinations.firstOrNull { it.id == draft.id }
    val homeAssistant = stringResource(R.string.ld_home_assistant)

    LDScreen(
        title = stringResource(R.string.ld_title_destination),
        actions = { LDTopBarAction(stringResource(R.string.ld_save), enabled = draft.urlProblem == null, onClick = save) }
    ) {
        if (draft.isNew || stored == null) {
            LDHeading(stringResource(R.string.webhook_title))
        } else {
            LDHeading(DestinationNames.name(stored, ui.webhook.destinations, homeAssistant))
            LastDeliveryLine(section, stored.url, testRuns, ui.refreshKey)
        }
        if (problem is UiMessage.SecretsNotReady) {
            LDBanner(stringResource(R.string.ld_not_saved), message = stringResource(R.string.secrets_not_ready)) {
                LDBannerAction(stringResource(R.string.ld_try_again), onClick = save)
            }
        }
        LDRefusedBanner(
            ui.refused != null,
            onRetry = {
                ui.actions.retryRefused()
                if (!refusedNow()) navigator.pop(page)
            },
            onDismiss = ui.actions::dismissRefused
        )

        // Done runs what the top bar's Save runs, and not while it is dim.
        val saveIfValid = { if (draft.urlProblem == null) save() }
        AddressSection(draft, problem, ui.allowHttpWebhooks, section, saveIfValid) {
            draft = draft.withUrl(it)
            problem = null
        }
        SigningSection(draft, saveIfValid) { draft = it }
        HeadersSection(draft) { draft = it }

        if (stored != null) {
            TestSection(section, draft, stored, onDone = { testRuns++ })
            LDGap(8.dp)
            LDButton(stringResource(R.string.ld_dest_remove), kind = LDButtonKind.Danger) { confirmRemove = true }
        }
    }

    if (confirmRemove && stored != null) {
        val isReceiveSource = receiveSource(section) == stored.url
        RemoveDestinationDialog(
            stringResource(if (isReceiveSource) R.string.ld_dest_remove_receive_body else R.string.ld_dest_remove_body),
            onConfirm = {
                confirmRemove = false
                ui.actions.removeDestination(stored.id)
                if (!refusedNow()) navigator.pop(page)
            },
            onDismiss = { confirmRemove = false }
        )
    }
}

/** The last row in the log for this address, whatever kind of delivery it was. */
@Composable
private fun LastDeliveryLine(section: LDSection, url: String, testRuns: Int, refreshKey: Int) {
    val context = LocalContext.current
    var row by remember { mutableStateOf<WebhookLog?>(null) }
    LaunchedEffect(url, testRuns, refreshKey) {
        row = withContext(Dispatchers.IO) {
            context.appPreferences().getWebhookLogsWithoutPayloads(section.logType).firstOrNull { it.url == url }
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(row) {
        while (true) {
            now = System.currentTimeMillis()
            delay(DateUtils.MINUTE_IN_MILLIS)
        }
    }
    val last = row ?: return
    val colors = LDTheme.colors
    val whenText = timeAgo(last.timestamp, now)
    if (last.success) {
        val records = last.recordCount ?: 0
        LDNote(
            if (records == 0) stringResource(R.string.ld_dest_delivered, whenText)
            else pluralStringResource(R.plurals.ld_dest_delivered_records, records, whenText, records),
            color = colors.accentTextSoft,
            top = 4.dp
        )
    } else {
        val reason = last.errorMessage
        LDNote(
            if (reason != null) stringResource(R.string.ld_dest_last_failed_reason, whenText, reason)
            else stringResource(R.string.ld_dest_last_failed, whenText),
            color = colors.warning,
            top = 4.dp
        )
    }
}

@Composable
private fun AddressSection(
    draft: DestinationDraft,
    problem: UiMessage?,
    allowHttp: Boolean,
    section: LDSection,
    onDone: () -> Unit,
    onUrlChange: (String) -> Unit
) {
    val resources = LocalResources.current
    val navigator = LocalLDNavigator.current
    val shown = problem?.takeIf { it !is UiMessage.SecretsNotReady } ?: draft.urlProblem?.takeIf { draft.url.isNotBlank() }
    LDSectionLabel(stringResource(R.string.webhook_destination_url))
    LDCard {
        LDFieldRow(
            stringResource(R.string.ld_dest_url),
            draft.url,
            onValueChange = onUrlChange,
            placeholder = stringResource(R.string.ld_add_webhook_example),
            error = shown?.text(resources),
            keyboardOptions = urlKeyboard,
            onDone = onDone
        )
    }
    // Edited in place, so the secret and headers stay with it when http becomes https; on another
    // host they are cleared (WebhookDraft.withEditedUrl), and this says why.
    val saved = draft.saved
    if (saved != null && SettingsRules.isValidUrl(draft.url) && !Destinations.sameHost(draft.url, saved.url)) {
        LDNote(stringResource(R.string.webhook_destination_moved, Destinations.label(saved, listOf(saved))), size = LDNoteSize.Small)
    }
    if (!allowHttp && draft.url.trim().startsWith("http://", ignoreCase = true)) {
        LDBanner(stringResource(R.string.ld_dest_plain_http), message = stringResource(R.string.ld_dest_plain_http_body)) {
            LDBannerAction(stringResource(R.string.sync_advanced_title)) { navigator.push(LDRoute.Advanced(section)) }
        }
    }
}

@Composable
private fun SigningSection(draft: DestinationDraft, onDone: () -> Unit, onChange: (DestinationDraft) -> Unit) {
    val context = LocalContext.current
    var revealed by remember { mutableStateOf(false) }
    // Shown only right after generating: this is the moment to put it on the server, not later.
    var generated by remember { mutableStateOf(false) }
    val copied = stringResource(R.string.webhook_secret_copied)
    val others = draft.others
    LDSectionLabel(stringResource(R.string.ld_dest_signing))
    LDCard {
        LDFieldRow(
            stringResource(R.string.webhook_hmac_secret_title),
            draft.secret,
            onValueChange = {
                onChange(draft.withSecret(it.trim()))
                generated = false
            },
            placeholder = stringResource(R.string.webhook_shared_secret),
            isSecure = !revealed,
            onDone = onDone,
            trailing = { RevealButton(revealed) { revealed = !revealed } }
        )
        if (draft.secret.isEmpty()) {
            LDRow(stringResource(R.string.webhook_secret_generate), showChevron = false, onClick = {
                onChange(draft.withSecret(WebhookSecret.generate()))
                generated = true
            })
            others.filter { !it.secret.isNullOrBlank() }.forEach { other ->
                key(other.id) {
                    LDRow(
                        stringResource(R.string.webhook_use_secret_of, Destinations.label(other, draft.webhook.destinations)),
                        showChevron = false,
                        onClick = {
                            onChange(draft.withSecretOf(other.id))
                            generated = false
                        }
                    )
                }
            }
        } else {
            LDRow(stringResource(R.string.ld_dest_copy_secret), showChevron = false, onClick = {
                copySensitive(context, "secret", draft.secret, copied)
            })
        }
    }
    if (generated && draft.secret.isNotEmpty()) {
        LDNote(stringResource(R.string.webhook_secret_generated), size = LDNoteSize.Small)
    }
    LDNote(stringResource(R.string.webhook_hmac_secret_description), size = LDNoteSize.Small)
}

@Composable
private fun HeadersSection(draft: DestinationDraft, onChange: (DestinationDraft) -> Unit) {
    val resources = LocalResources.current
    var showsForm by remember { mutableStateOf(false) }
    // In memory only, like the draft: a header often holds an API key.
    var newName by rememberLDDraft { "" }
    var newValue by rememberLDDraft { "" }
    var problem by rememberLDDraft<UiMessage?> { null }
    val nameFocus = remember { FocusRequester() }
    val add = {
        problem = SettingsRules.headerProblem(newName, newValue)
        if (problem == null) {
            onChange(draft.withHeader(newName, newValue))
            newName = ""
            newValue = ""
            showsForm = false
        }
    }
    LaunchedEffect(showsForm) {
        if (showsForm) nameFocus.requestFocus()
    }
    LDSectionLabel(stringResource(R.string.ld_dest_headers))
    LDCard {
        draft.headers.toSortedMap().forEach { (name, value) ->
            key(name) {
                HeaderRow(name, value, onRemove = { onChange(draft.withoutHeader(name)) })
                val valid = WebhookSupport.isValidHeaderName(name) && WebhookSupport.isValidHeaderValue(value)
                if (!valid) CardRowError(stringResource(R.string.ld_dest_header_invalid))
            }
        }
        if (showsForm) {
            LDFieldRow(
                stringResource(R.string.webhook_header_name),
                newName,
                onValueChange = {
                    newName = it
                    problem = null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                focusRequester = nameFocus
            )
            LDFieldRow(
                stringResource(R.string.webhook_header_value),
                newValue,
                onValueChange = {
                    newValue = it
                    problem = null
                },
                error = problem?.text(resources),
                onDone = { if (newName.isNotBlank()) add() }
            )
            LDRow(stringResource(R.string.common_add), showChevron = false, enabled = newName.isNotBlank(), onClick = add)
        } else {
            LDRow(stringResource(R.string.ld_dest_add_header), showChevron = false, onClick = { showsForm = true })
        }
        // Only while this address has none: beside headers of its own, copying would replace them.
        if (draft.headers.isEmpty()) {
            draft.others.filter { it.headers.isNotEmpty() }.forEach { other ->
                key(other.id) {
                    LDRow(
                        stringResource(R.string.webhook_copy_headers_from, Destinations.label(other, draft.webhook.destinations)),
                        showChevron = false,
                        onClick = { onChange(draft.withHeadersOf(other.id)) }
                    )
                }
            }
        }
    }
    LDNote(stringResource(R.string.ld_dest_headers_note), size = LDNoteSize.Small)
}

/** A header with its value masked until tapped, since it often holds an API key. */
@Composable
internal fun HeaderRow(name: String, value: String, onRemove: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val removeLabel = stringResource(R.string.ld_remove_named, name)
    // The first word stays readable: "Bearer" says what kind of value it is.
    val masked = value.split(' ', limit = 2).let { parts -> if (parts.size == 2) "${parts[0]} ••••" else "••••" }
    LDRow(
        name,
        value = if (shown) value else masked,
        valueMono = true,
        showChevron = false,
        onClick = { shown = !shown },
        trailing = {
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = removeLabel },
                contentAlignment = Alignment.Center
            ) {
                Icon(LDIcons.close, contentDescription = null, tint = LDTheme.colors.muted, modifier = Modifier.size(16.dp))
            }
        }
    )
}

/**
 * Send test, with what it found on this page. A delivery reads the secret and headers from storage,
 * so a test cannot use what is only typed here yet. It goes through [TestPing] itself and not the
 * view model, whose ping says its outcome in a toast over the same line.
 */
@Composable
private fun TestSection(section: LDSection, draft: DestinationDraft, stored: Destination, onDone: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<UiMessage?>(null) }
    LDGap(12.dp)
    LDButton(
        stringResource(if (testing) R.string.ld_dest_sending else R.string.ld_dest_send_test),
        kind = LDButtonKind.Ghost,
        busy = testing
    ) {
        testing = true
        outcome = null
        scope.launch {
            val result = TestPing.send(context.applicationContext, listOf(stored.copy(url = stored.url.trim())), section.logType)
            outcome = result.fold(
                onSuccess = { UiMessage.PingDelivered },
                onFailure = { UiMessage.headerProblem(it) ?: UiMessage.PingFailedWith(FailureReason.of(it).orEmpty()) }
            )
            testing = false
            onDone()
        }
    }
    if (draft.destination != stored) {
        LDNote(stringResource(R.string.ld_dest_test_uses_saved), size = LDNoteSize.Small)
    }
    outcome?.let { LDNoticeLine(it.text(resources), it.noticeTone()) }
}
