package com.owen282000.lifedashboard.screens.ld

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDCode
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.ldCodeStyle
import com.owen282000.lifedashboard.ui.ld.ldKeepingSeparators
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise

/** The typefaces by name, the same in every language, with their licence in assets/licences. */
private val TYPEFACES = listOf(
    "Space Grotesk" to "licences/SpaceGrotesk-OFL.txt",
    "Space Mono" to "licences/SpaceMono-OFL.txt"
)

/** The short name of the licence, the same in every language. */
private const val OFL = "OFL"

@Composable
fun AboutOpenSourceScreen() {
    var shownTypeface by rememberSaveable { mutableStateOf<String?>(null) }

    LDScreen(title = stringResource(R.string.about_title)) {
        LDHeading(stringResource(R.string.ld_title_open_source))
        LDNote(stringResource(R.string.ld_about_source_intro), top = 6.dp)
        LDGap(12.dp)
        LDCard(Modifier.ldStaggerRise(0)) {
            AboutLinkRow(stringResource(R.string.about_github), AboutLinks.REPOSITORY)
            AboutLinkRow(stringResource(R.string.about_licence), AboutLinks.LICENCE, subtitle = stringResource(R.string.about_licence_sub).ldKeepingSeparators)
            AboutLinkRow(stringResource(R.string.about_website), AboutLinks.WEBSITE)
            AboutLinkRow(stringResource(R.string.ld_about_receiver), AboutLinks.PAIRING_GUIDE, subtitle = stringResource(R.string.ld_about_receiver_sub))
        }
        LDSectionLabel(stringResource(R.string.ld_about_typefaces_title))
        LDCard(Modifier.ldStaggerRise(1)) {
            TYPEFACES.forEach { (name, _) ->
                LDRow(name, value = OFL, onClick = { shownTypeface = name })
            }
        }
        LDNote(stringResource(R.string.ld_about_typefaces), size = LDNoteSize.Small)
    }

    TYPEFACES.firstOrNull { it.first == shownTypeface }?.let { (name, asset) ->
        LicenceDialog(name, asset) { shownTypeface = null }
    }
}

@Composable
private fun LicenceDialog(name: String, asset: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // A few KB from the app's own assets.
    val licence = remember(asset) { context.assets.open(asset).bufferedReader().use { it.readText() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            LDCode {
                Text(licence, style = LDType.monoValue.ldCodeStyle(), modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}
