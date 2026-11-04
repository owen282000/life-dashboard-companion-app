package com.owen282000.lifedashboard.screens.ld

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.ConfigBackup
import com.owen282000.lifedashboard.ConfigBackupManager
import com.owen282000.lifedashboard.ConfigCrypto
import com.owen282000.lifedashboard.ExportManager
import com.owen282000.lifedashboard.ImportNote
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.SyncScheduler
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDFieldRow
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDToggleRow
import com.owen282000.lifedashboard.ui.ld.rememberLDDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Export and import of all settings.
 *
 * Secrets are deliberately kept out of Android's cloud backup, so without this a new phone means
 * re-entering every webhook, header and broker by hand. An export that carries secrets is
 * encrypted with a password, because the file leaves the device through the share sheet. The
 * passwords and an opened backup are drafts, in memory only.
 */
@Composable
fun BackupRestoreScreen() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val health = ldHealthViewModel()
    val screenTime = ldScreenTimeViewModel()

    var includeSecrets by rememberLDDraft { true }
    var password by rememberLDDraft { "" }
    var repeatPassword by rememberLDDraft { "" }
    var exporting by remember { mutableStateOf(false) }
    var exportProblem by rememberLDDraft<String?> { null }

    var pendingImport by rememberLDDraft<ConfigBackup?> { null }
    var encryptedImport by rememberLDDraft<String?> { null }
    // What became of the last import, and whether it went wrong, shown under its row.
    var importStatus by rememberLDDraft<Pair<String, Boolean>?> { null }

    val problemFor: (Int, String?) -> Pair<String, Boolean> = { id, argument ->
        (if (argument != null) resources.getString(id, argument) else resources.getString(id)) to true
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importStatus = null
        scope.launch {
            val text = withContext(Dispatchers.IO) { readPicked(context, uri) }
            when {
                text.isNullOrBlank() -> importStatus = problemFor(R.string.backup_read_failed, null)
                // Ask for the password before we can show a preview of what it holds.
                ConfigCrypto.isEncrypted(text) -> encryptedImport = text
                else -> {
                    val backup = decodePlain(text)
                    if (backup != null) pendingImport = backup else importStatus = problemFor(R.string.backup_invalid_file, null)
                }
            }
        }
    }

    val passwordProblem = ConfigCrypto.passwordProblem(password, repeatPassword)
    // Shown once the user has typed something, not while the field is still empty.
    val tooShort = password.isNotEmpty() && passwordProblem == ConfigCrypto.PasswordProblem.TOO_SHORT
    val mismatch = repeatPassword.isNotEmpty() && passwordProblem == ConfigCrypto.PasswordProblem.MISMATCH

    LDScreen(title = stringResource(R.string.ld_title_more)) {
        LDHeading(stringResource(R.string.ld_title_backup_restore))
        LDNote(stringResource(R.string.ld_backup_intro), top = 6.dp)

        LDSectionLabel(stringResource(R.string.backup_export))
        LDCard {
            LDToggleRow(
                stringResource(R.string.backup_include_secrets),
                includeSecrets,
                { includeSecrets = it },
                subtitle = stringResource(R.string.backup_include_secrets_detail)
            )
            if (includeSecrets) {
                LDFieldRow(
                    stringResource(R.string.backup_password),
                    password,
                    onValueChange = { password = it },
                    isSecure = true,
                    error = if (tooShort) stringResource(R.string.backup_password_too_short, ConfigCrypto.MIN_PASSWORD_LENGTH) else null
                )
                LDFieldRow(
                    stringResource(R.string.backup_password_repeat),
                    repeatPassword,
                    onValueChange = { repeatPassword = it },
                    isSecure = true,
                    error = if (mismatch) stringResource(R.string.backup_password_mismatch) else null
                )
            }
        }
        LDNote(
            stringResource(if (includeSecrets) R.string.backup_password_hint else R.string.backup_no_secrets_hint),
            size = LDNoteSize.Small
        )
        LDGap(10.dp)
        LDButton(
            stringResource(R.string.backup_export_title),
            enabled = !includeSecrets || passwordProblem == null,
            busy = exporting
        ) {
            exporting = true
            exportProblem = null
            val secret = password.takeIf { includeSecrets }
            scope.launch {
                exportProblem = withContext(Dispatchers.IO) { exportBackup(context, secret) }
                exporting = false
                if (exportProblem == null) {
                    password = ""
                    repeatPassword = ""
                }
            }
        }
        exportProblem?.let { Problem(it) }

        LDSectionLabel(stringResource(R.string.backup_import))
        LDCard {
            LDRow(
                stringResource(R.string.ld_backup_choose_file),
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
            )
        }
        importStatus?.let { (text, wrong) ->
            if (wrong) {
                Problem(text)
            } else {
                LDNote(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, size = LDNoteSize.Small)
            }
        }
    }

    encryptedImport?.let { envelope ->
        UnlockDialog(
            envelope,
            onUnlocked = {
                encryptedImport = null
                pendingImport = it
            },
            onDismiss = { encryptedImport = null }
        )
    }

    // Preview before anything is overwritten: an import replaces live configuration.
    pendingImport?.let { backup ->
        ImportPreviewDialog(
            backup,
            onImported = {
                pendingImport = null
                // The imported schedule has to reach WorkManager now, not at the next app start,
                // and the screens underneath have to show what is stored now.
                SyncScheduler.rescheduleAll(context)
                health.reloadFromSettings()
                health.refreshPermissions()
                screenTime.reloadFromSettings()
                importStatus = resources.getString(R.string.ld_backup_imported) to false
            },
            onFailed = { reason ->
                pendingImport = null
                importStatus = problemFor(R.string.backup_import_failed, reason)
            },
            onDismiss = { pendingImport = null }
        )
    }
}

@Composable
private fun Problem(text: String) {
    LDNote(
        text,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = LDTheme.colors.warning,
        size = LDNoteSize.Small
    )
}

@Composable
private fun UnlockDialog(envelope: String, onUnlocked: (ConfigBackup) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by rememberLDDraft { "" }
    var error by remember { mutableStateOf<Int?>(null) }
    var unlocking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {
            if (!unlocking) {
                password = ""
                onDismiss()
            }
        },
        title = { Text(stringResource(R.string.backup_encrypted_title)) },
        text = {
            val errorText = error?.let { stringResource(it) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_encrypted_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.backup_password)) },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    // TalkBack reads the reason with the field, not a generic "invalid input".
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { errorText?.let { error(it) } }
                )
                errorText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotBlank() && !unlocking,
                onClick = {
                    unlocking = true
                    val typed = password
                    scope.launch {
                        // The key derivation takes a moment, so off the main thread.
                        val result = withContext(Dispatchers.Default) { decryptBackup(envelope, typed) }
                        unlocking = false
                        result.onSuccess {
                            password = ""
                            onUnlocked(it)
                        }.onFailure {
                            error = unlockErrorFor(it)
                        }
                    }
                }
            ) { Text(stringResource(R.string.backup_unlock)) }
        },
        dismissButton = {
            TextButton(enabled = !unlocking, onClick = {
                password = ""
                onDismiss()
            }) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun ImportPreviewDialog(
    backup: ConfigBackup,
    onImported: () -> Unit,
    onFailed: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val summary = remember(backup) { backup.summarise(context.appPreferences().getHealthEnabledDataTypes()) }
    // Nothing was written when the store refuses; the dialog stays so the import can be tried again.
    var notReady by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_import_title)) },
        text = {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_import_replaces), style = MaterialTheme.typography.bodyMedium)
                listOfNotNull(
                    summary.healthWebhooks?.let { stringResource(R.string.backup_summary_health_webhooks, it) },
                    summary.screenTimeWebhooks?.let { stringResource(R.string.backup_summary_screen_time_webhooks, it) },
                    summary.enabledDataTypes?.let { stringResource(R.string.backup_summary_data_types, it) },
                    stringResource(R.string.backup_summary_brokers, summary.brokers),
                    stringResource(if (summary.includesSecrets) R.string.backup_summary_secrets else R.string.backup_summary_no_secrets)
                ).forEach {
                    Text(stringResource(R.string.backup_bullet, it), style = MaterialTheme.typography.bodySmall)
                }
                if (backup.isFromIPhone) {
                    Text(stringResource(R.string.backup_from_iphone), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                backup.exportedAt?.let {
                    Text(stringResource(R.string.backup_exported_at, it), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                backup.importNotes().forEach { note ->
                    Text(stringResource(noteText(note)), style = MaterialTheme.typography.bodySmall)
                }
                if (!backup.containsSecrets()) {
                    Text(stringResource(R.string.backup_keeps_credentials), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Text(stringResource(R.string.backup_history_unaffected), style = MaterialTheme.typography.bodySmall, color = muted)
                if (notReady) {
                    Text(
                        stringResource(R.string.secrets_not_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val outcome = importBackup(context, backup)) {
                    ImportOutcome.Done -> onImported()
                    ImportOutcome.NotReady -> notReady = true
                    is ImportOutcome.Failed -> onFailed(outcome.reason)
                }
            }) { Text(stringResource(if (notReady) R.string.ld_try_again else R.string.backup_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

private sealed interface ImportOutcome {
    data object Done : ImportOutcome
    data object NotReady : ImportOutcome
    data class Failed(val reason: String) : ImportOutcome
}

private fun importBackup(context: Context, backup: ConfigBackup): ImportOutcome =
    runCatching { ConfigBackupManager(context).import(backup) }.fold(
        onSuccess = { imported -> if (imported) ImportOutcome.Done else ImportOutcome.NotReady },
        onFailure = { ImportOutcome.Failed(it.message.orEmpty()) }
    )

/** The problem to show, or null when the file went to the share sheet. [password] encrypts it and keeps the secrets in. */
private fun exportBackup(context: Context, password: String?): String? =
    runCatching {
        val backup = ConfigBackupManager(context).export()
        val (content, filename) = if (password != null) {
            ConfigCrypto.encrypt(backup.encode(), password) to ConfigBackupManager.ENCRYPTED_FILENAME
        } else {
            backup.withoutSecrets().encode() to ConfigBackupManager.FILENAME
        }
        ExportManager(context).shareFile(content, filename, "application/json", context.getString(R.string.backup_export_title))
    }.exceptionOrNull()?.let { context.getString(R.string.sync_export_failed_with_reason, it.message.orEmpty()) }

private fun readPicked(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()

private fun decodePlain(text: String): ConfigBackup? = runCatching { ConfigBackup.decode(text) }.getOrNull()

private fun decryptBackup(envelope: String, password: String): Result<ConfigBackup> =
    runCatching { ConfigBackup.decode(ConfigCrypto.decrypt(envelope, password)) }

/** What the password dialog says when a file does not open. */
@StringRes
private fun unlockErrorFor(error: Throwable): Int = when (error) {
    is ConfigCrypto.WrongPasswordException -> R.string.backup_wrong_password
    is ConfigCrypto.UnsupportedEnvelopeException -> R.string.backup_unsupported_file
    else -> R.string.backup_invalid_file
}

@StringRes
private fun noteText(note: ImportNote): Int = when (note) {
    ImportNote.SCREEN_TIME_KEPT -> R.string.backup_note_screen_time_kept
    ImportNote.IPHONE_ANDROID_TYPES_KEPT -> R.string.backup_note_iphone_types_kept
    ImportNote.IPHONE_BASE_TOPIC_KEPT -> R.string.backup_note_iphone_topic_kept
    ImportNote.IPHONE_PHONE_NAME_KEPT -> R.string.backup_note_iphone_name_kept
    ImportNote.UNSENDABLE_HEADERS_SKIPPED -> R.string.backup_note_unsendable_headers_skipped
    ImportNote.BROKER_HOST_UNUSABLE -> R.string.backup_note_broker_host_unusable
    ImportNote.BROKER_PORT_UNUSABLE -> R.string.backup_note_broker_port_unusable
}
