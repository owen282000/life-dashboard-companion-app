package com.owen282000.lifedashboard.screens.ld

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise

private const val GOOGLE_PLAY = "Google Play"

@Composable
fun AboutSupportScreen() {
    LDScreen(title = stringResource(R.string.about_title)) {
        LDHeading(stringResource(R.string.ld_title_support))
        LDNote(stringResource(R.string.ld_about_support_intro), top = 6.dp)
        LDGap(12.dp)
        LDCard(Modifier.ldStaggerRise()) {
            // Only in the Google Play build (P1-28): the listing, to rate it.
            if (booleanResource(R.bool.show_rate_app)) {
                AboutLinkRow(stringResource(R.string.about_rate), AboutLinks.RATE, value = GOOGLE_PLAY)
            }
            AboutLinkRow(stringResource(R.string.ld_about_star), AboutLinks.REPOSITORY)
            // Only in the GitHub and F-Droid builds (P1-27): the Play build is that listing.
            if (booleanResource(R.bool.show_play_listing)) {
                AboutLinkRow(stringResource(R.string.about_play), AboutLinks.PLAY_LISTING, subtitle = stringResource(R.string.about_play_sub))
            }
            // Not in the Google Play build, which is paid (P1-13, src/play/res/values/bools.xml).
            if (booleanResource(R.bool.show_donation)) {
                AboutLinkRow(stringResource(R.string.about_coffee), AboutLinks.COFFEE, subtitle = stringResource(R.string.about_coffee_sub))
            }
        }
    }
}
