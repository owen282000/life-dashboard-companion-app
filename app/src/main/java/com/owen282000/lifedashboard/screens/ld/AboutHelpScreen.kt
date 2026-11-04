package com.owen282000.lifedashboard.screens.ld

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.ldLeftToRight
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise

@Composable
fun AboutHelpScreen() {
    val version = ldVersionName()
    LDScreen(title = stringResource(R.string.about_title)) {
        LDHeading(stringResource(R.string.ld_title_help))
        LDGap(12.dp)
        LDCard(Modifier.ldStaggerRise()) {
            AboutLinkRow(stringResource(R.string.ld_about_setup_guide), AboutLinks.WEBSITE, value = stringResource(R.string.about_website))
            AboutLinkRow(stringResource(R.string.about_docs), AboutLinks.DOCUMENTATION, value = GITHUB)
            AboutLinkRow(stringResource(R.string.about_whats_new), AboutLinks.release(version), value = version.ldLeftToRight)
            AboutLinkRow(stringResource(R.string.about_report), AboutLinks.NEW_ISSUE)
            AboutLinkRow(stringResource(R.string.ld_about_questions), AboutLinks.QUESTIONS, value = stringResource(R.string.ld_about_mail))
        }
    }
}

/** A name, the same in every language. */
internal const val GITHUB = "GitHub"
