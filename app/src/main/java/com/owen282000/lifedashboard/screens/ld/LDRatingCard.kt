package com.owen282000.lifedashboard.screens.ld

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ReviewMoment
import com.owen282000.lifedashboard.ui.ld.LDTextButton
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.ldSurface

/**
 * A quiet line with one link, for when all is well (P1-43); [ReviewMoment.card] decides. In the
 * Google Play build it opens the Play listing, for whoever Play's own dialog did not reach,
 * after weeks in which the syncs went well. In the GitHub and F-Droid builds, which have no
 * store to rate in, it asks for a star on GitHub and opens the repository in the browser. Gone
 * for good once it is used or closed. It offers nothing in return and looks nothing up, and the
 * Play link opens the listing rather than Play's dialog, which may not hang on a button.
 */
@Composable
fun LDRatingCard() {
    val context = LocalContext.current
    var card by remember { mutableStateOf(ReviewMoment.Card.NONE) }
    LifecycleResumeEffect(context) {
        card = ReviewMoment.card(context)
        onPauseOrDispose { }
    }
    val star = when (card) {
        ReviewMoment.Card.NONE -> return
        ReviewMoment.Card.RATE -> false
        ReviewMoment.Card.STAR -> true
    }
    val close = {
        ReviewMoment.closeCard(context)
        card = ReviewMoment.Card.NONE
    }
    Column(
        Modifier
            .padding(start = LDTheme.cardInset, end = LDTheme.cardInset, top = 10.dp, bottom = 8.dp)
            .fillMaxWidth()
            .ldSurface(LDTheme.cardRadius)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp)
    ) {
        Text(
            stringResource(if (star) R.string.review_star_card else R.string.ld_about_support_intro),
            style = LDType.note,
            color = LDTheme.colors.secondary
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LDTextButton(stringResource(if (star) R.string.ld_about_star else R.string.about_rate)) {
                close()
                context.openLink(if (star) AboutLinks.REPOSITORY else AboutLinks.RATE)
            }
            LDTextButton(stringResource(R.string.battery_hint_dismiss), color = LDTheme.colors.secondary, onClick = close)
        }
    }
}
