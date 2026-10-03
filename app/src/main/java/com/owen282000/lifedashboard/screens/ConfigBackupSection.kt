package com.owen282000.lifedashboard.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
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
import com.owen282000.lifedashboard.ui.theme.ink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Export and import of all settings.
 *
 * Secrets are deliberately kept out of Android's cloud backup, so without this a new phone
 * means re-entering every webhook, header and broker by hand. An export that carries secrets
 * can be password-protected, because the file leaves the device through the share sheet.
 */
@Composable
fun ConfigBackupSection() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    var showExportDialog by remember { mutableStateOf(false) }
    var includeSecrets by remember { mutableStateOf(true) }
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordRepeat by remember { mutableStateOf("") }

    var pendingImport by remember { mutableStateOf<ConfigBackup?>(null) }
    var encryptedImport by remember { mutableStateOf<String?>(null) }
    var importPassword by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<Int?>(null) }
    var unlocking by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

        if (text.isNullOrBlank()) {
            Toast.makeText(context, resources.getString(R.string.backup_read_failed), Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }

        if (ConfigCrypto.isEncrypted(text)) {
            // Ask for the password before we can show a preview of what it holds.
            encryptedImport = text
            importPassword = ""
            importError = null
        } else {
            runCatching { ConfigBackup.decode(text) }
                .onSuccess { pendingImport = it }
                .onFailure {
                    Toast.makeText(context, resources.getString(R.string.backup_invalid_file), Toast.LENGTH_LONG).show()
                }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.backup_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    includeSecrets = true
                    exportPassword = ""
                    exportPasswordRepeat = ""
                    showExportDialog = true
                },
                modifier = Modifier.weight(1f),
                shape = androidx.compose.foundation.shape.CircleShape,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = com.owen282000.lifedashboard.ui.theme.HealthPrimary, contentColor = com.owen282000.lifedashboard.ui.theme.onAccent(com.owen282000.lifedashboard.ui.theme.HealthPrimary))
            ) { Text(stringResource(R.string.backup_export)) }

            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.weight(1f),
                shape = androidx.compose.foundation.shape.CircleShape,
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = com.owen282000.lifedashboard.ui.theme.HealthPrimary.ink())
            ) { Text(stringResource(R.string.backup_import)) }
        }
    }

    if (showExportDialog) {
        val passwordProblem = ConfigCrypto.passwordProblem(exportPassword, exportPasswordRepeat)
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.backup_export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val secretsInteraction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier.switchRow(includeSecrets, secretsInteraction) { includeSecrets = it },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.backup_include_secrets), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.backup_include_secrets_detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RowSwitch(includeSecrets, secretsInteraction)
                    }

                    if (includeSecrets) {
                        // Shown once the user has typed something, not while the field is still empty.
                        val tooShort = exportPassword.isNotEmpty() && passwordProblem == ConfigCrypto.PasswordProblem.TOO_SHORT
                        val mismatch = exportPasswordRepeat.isNotEmpty() && passwordProblem == ConfigCrypto.PasswordProblem.MISMATCH
                        OutlinedTextField(
                            value = exportPassword,
                            onValueChange = { exportPassword = it },
                            label = { Text(stringResource(R.string.backup_password)) },
                            singleLine = true,
                            isError = tooShort,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = exportPasswordRepeat,
                            onValueChange = { exportPasswordRepeat = it },
                            label = { Text(stringResource(R.string.backup_password_repeat)) },
                            singleLine = true,
                            isError = mismatch,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                        val problemText = when {
                            tooShort -> stringResource(R.string.backup_password_too_short, ConfigCrypto.MIN_PASSWORD_LENGTH)
                            mismatch -> stringResource(R.string.backup_password_mismatch)
                            else -> null
                        }
                        problemText?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            stringResource(R.string.backup_password_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            stringResource(R.string.backup_no_secrets_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !includeSecrets || passwordProblem == null,
                    onClick = {
                        showExportDialog = false
                        val backup = ConfigBackupManager(context).export()
                        val payload = if (includeSecrets) backup else backup.withoutSecrets()

                        val (content, filename) = if (includeSecrets) {
                            ConfigCrypto.encrypt(payload.encode(), exportPassword) to
                                ConfigBackupManager.ENCRYPTED_FILENAME
                        } else {
                            payload.encode() to ConfigBackupManager.FILENAME
                        }

                        exportPassword = ""
                        exportPasswordRepeat = ""
                        runCatching {
                            ExportManager(context).shareFile(
                                content, filename, "application/json", resources.getString(R.string.backup_export_title)
                            )
                        }.onFailure {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.sync_export_failed_with_reason, it.message.orEmpty()),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                ) { Text(stringResource(R.string.backup_export)) }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    encryptedImport?.let { envelope ->
        AlertDialog(
            onDismissRequest = { if (!unlocking) encryptedImport = null },
            title = { Text(stringResource(R.string.backup_encrypted_title)) },
            text = {
                val importErrorText = importError?.let { stringResource(it) }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.backup_encrypted_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it; importError = null },
                        label = { Text(stringResource(R.string.backup_password)) },
                        singleLine = true,
                        isError = importError != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        // TalkBack reads the reason with the field, not a generic "invalid input".
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { importErrorText?.let { error(it) } }
                    )
                    importErrorText?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.announced())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = importPassword.isNotBlank() && !unlocking,
                    onClick = {
                        unlocking = true
                        scope.decryptInBackground(envelope, importPassword) { result ->
                            unlocking = false
                            result.onSuccess {
                                encryptedImport = null
                                importPassword = ""
                                pendingImport = it
                            }.onFailure {
                                importError = unlockErrorFor(it)
                            }
                        }
                    }
                ) { Text(stringResource(R.string.backup_unlock)) }
            },
            dismissButton = {
                TextButton(enabled = !unlocking, onClick = { encryptedImport = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // Preview before anything is overwritten: an import replaces live configuration.
    pendingImport?.let { backup ->
        val summary = remember(backup) { backup.summarise(context.appPreferences().getHealthEnabledDataTypes()) }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.backup_import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.backup_import_replaces),
                        style = MaterialTheme.typography.bodyMedium
                    )
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
                        Text(
                            stringResource(R.string.backup_from_iphone),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    backup.exportedAt?.let {
                        Text(
                            stringResource(R.string.backup_exported_at, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    backup.importNotes().forEach { note ->
                        Text(stringResource(noteText(note)), style = MaterialTheme.typography.bodySmall)
                    }
                    if (!backup.containsSecrets()) {
                        Text(
                            stringResource(R.string.backup_keeps_credentials),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        stringResource(R.string.backup_history_unaffected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching { ConfigBackupManager(context).import(backup) }
                            .onSuccess {
                                // The imported schedule has to reach WorkManager now, not at the next app start.
                                SyncScheduler.rescheduleAll(context)
                                Toast.makeText(context, resources.getString(R.string.backup_imported), Toast.LENGTH_LONG).show()
                            }
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    resources.getString(R.string.backup_import_failed, it.message.orEmpty()),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        pendingImport = null
                    }
                ) { Text(stringResource(R.string.backup_import)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/**
 * Decrypts and reads an encrypted export off the main thread, since the key derivation takes a
 * moment, and hands the result to [done] back on the main thread.
 */
private fun CoroutineScope.decryptInBackground(envelope: String, password: String, done: (Result<ConfigBackup>) -> Unit) {
    launch { done(withContext(Dispatchers.Default) { decryptBackup(envelope, password) }) }
}

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
}
