package com.owen282000.lifedashboard.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.ConfigBackup
import com.owen282000.lifedashboard.ConfigBackupManager
import com.owen282000.lifedashboard.ConfigCrypto
import com.owen282000.lifedashboard.ExportManager

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

    var showExportDialog by remember { mutableStateOf(false) }
    var includeSecrets by remember { mutableStateOf(true) }
    var exportPassword by remember { mutableStateOf("") }

    var pendingImport by remember { mutableStateOf<ConfigBackup?>(null) }
    var encryptedImport by remember { mutableStateOf<String?>(null) }
    var importPassword by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

        if (text.isNullOrBlank()) {
            Toast.makeText(context, "Could not read that file", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(context, "Not a valid settings file", Toast.LENGTH_LONG).show()
                }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Settings are not part of Android's cloud backup, because the secrets they contain " +
                "never leave this device. Export them here to move to a new phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    includeSecrets = true
                    exportPassword = ""
                    showExportDialog = true
                },
                modifier = Modifier.weight(1f)
            ) { Text("Export") }

            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.weight(1f)
            ) { Text("Import") }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Include secrets", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Auth headers, signing secrets and MQTT passwords",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = includeSecrets, onCheckedChange = { includeSecrets = it })
                    }

                    if (includeSecrets) {
                        OutlinedTextField(
                            value = exportPassword,
                            onValueChange = { exportPassword = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "The file is encrypted with this password. Without it the export " +
                                "cannot be restored, so store it somewhere safe.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "The export will contain your webhook URLs, MQTT hosts and options, " +
                                "but no credentials.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !includeSecrets || exportPassword.isNotBlank(),
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
                        runCatching {
                            ExportManager(context).shareFile(
                                content, filename, "application/json", "Export settings"
                            )
                        }.onFailure {
                            Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                ) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("Cancel") }
            }
        )
    }

    encryptedImport?.let { envelope ->
        AlertDialog(
            onDismissRequest = { encryptedImport = null },
            title = { Text("Encrypted export") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This file is password-protected.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it; importError = null },
                        label = { Text("Password") },
                        singleLine = true,
                        isError = importError != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    importError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = importPassword.isNotBlank(),
                    onClick = {
                        runCatching {
                            ConfigBackup.decode(ConfigCrypto.decrypt(envelope, importPassword))
                        }.onSuccess {
                            encryptedImport = null
                            importPassword = ""
                            pendingImport = it
                        }.onFailure {
                            importError = it.message ?: "Could not decrypt this file"
                        }
                    }
                ) { Text("Unlock") }
            },
            dismissButton = {
                TextButton(onClick = { encryptedImport = null }) { Text("Cancel") }
            }
        )
    }

    // Preview before anything is overwritten: an import replaces live configuration.
    pendingImport?.let { backup ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import settings?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This replaces your current configuration:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    backup.summarise().forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                    backup.exportedAt?.let {
                        Text(
                            "Exported $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!backup.containsSecrets()) {
                        Text(
                            "This file has no credentials, so the ones already on this device " +
                                "are kept.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "Sync history and logs are not affected.",
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
                                Toast.makeText(
                                    context,
                                    "Settings imported, reopen the app to see them",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            .onFailure {
                                Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        pendingImport = null
                    }
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
            }
        )
    }
}
