package com.owen282000.lifedashboard

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.SystemClock
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.LayoutRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.unit.ColorProvider
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Home screen widget: the state of the last sync, the records delivered today and when, as the iOS
 * widget has them. A tap opens the app, the round button syncs at the same limit as the tile.
 *
 * Exact size mode, because the line is drawn for the width it really gets. The texts are small
 * TextView layouts inside Glance's own, for autosizing and colours the launcher resolves itself.
 * They use system fonts in Space Grotesk's weights and a system mono: a launcher refuses to load an
 * app's res/font, in any layout.
 */
class SyncStatusWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Read in the composition: a session outlives one update, so what is read before it goes stale.
            val change by SyncStatusStore.changes.collectAsState()
            val status = remember(change) { SyncStatusStore.read(context) }
            val labels = remember(change) { labels(context, status) }
            WidgetContent(status.outcome, labels)
        }
    }

    private fun labels(context: Context, status: SyncStatusStore.Status) = WidgetLabels(
        packageName = context.packageName,
        againstLauncher = context.resources.configuration.layoutDirection != Resources.getSystem().configuration.layoutDirection,
        count = NumberFormat.getIntegerInstance().format(status.recordsToday),
        recordsToday = context.resources.getQuantityString(R.plurals.widget_records_today, status.recordsToday),
        lastSync = syncLine(context, status),
        outcome = SyncOutcomeText.widgetRes(status.outcome)?.let { context.getString(it) },
        syncNow = context.getString(R.string.sync_now),
        accent = context.dayNight(R.color.widget_accent),
        accentText = context.dayNight(R.color.widget_accent_text),
        warning = context.dayNight(R.color.widget_warning),
        flat = context.dayNight(R.color.widget_flat),
        onAccent = context.dayNight(R.color.widget_on_accent)
    )

    /** A tint for both themes from one colour resource, so the launcher flips it without a new render. */
    private fun Context.dayNight(@ColorRes color: Int): ColorProvider {
        fun inMode(night: Int): Color {
            val configuration = Configuration(resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
            return Color(createConfigurationContext(configuration).getColor(color))
        }
        return ColorProvider(day = inMode(Configuration.UI_MODE_NIGHT_NO), night = inMode(Configuration.UI_MODE_NIGHT_YES))
    }

    private fun syncLine(context: Context, status: SyncStatusStore.Status): String {
        val last = status.lastSyncMillis ?: return context.getString(R.string.widget_no_syncs)
        val today = syncedOnDay(last, LocalDate.now(), ZoneId.systemDefault())
        val moment = if (today) {
            DateFormat.getTimeFormat(context).format(Date(last))
        } else {
            DateUtils.formatDateTime(
                context,
                last,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
            )
        }
        return when {
            !status.lastSuccess -> context.getString(R.string.ld_home_failed_time, moment)
            today -> context.getString(R.string.widget_synced_at, moment)
            else -> context.getString(R.string.widget_synced_on, moment)
        }
    }

    companion object {
        /** Whether a time alone says when: a sync from another day has to name the day. */
        fun syncedOnDay(lastSyncMillis: Long, day: LocalDate, zone: ZoneId): Boolean =
            Instant.ofEpochMilli(lastSyncMillis).atZone(zone).toLocalDate() == day

        suspend fun updateAll(context: Context) {
            try {
                SyncStatusWidget().updateAll(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A stopped worker or a left screen must stop here, not carry on as if refreshed.
                throw e
            } catch (e: Exception) {
                // Widget may not be placed; never fail a sync over a widget refresh
            }
        }
    }
}

/** The round button: the same rate limited sync as the Quick Settings tile. */
class SyncNowWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        SyncTrigger.tryEnqueueImmediateSync(context, SystemClock.elapsedRealtime())
    }
}

/** The widget's text, resolved from resources before composing, in the app's language. */
private class WidgetLabels(
    val packageName: String,
    /**
     * The app's own language runs the other way than the phone's. The launcher lays the widget out,
     * in the phone's direction: the text can follow the language, Glance's rows and images cannot.
     */
    val againstLauncher: Boolean,
    val count: String,
    val recordsToday: String,
    val lastSync: String,
    /** What the status symbol says, for TalkBack; null before the first sync. */
    val outcome: String?,
    val syncNow: String,
    val accent: ColorProvider,
    val accentText: ColorProvider,
    val warning: ColorProvider,
    val flat: ColorProvider,
    val onAccent: ColorProvider
)

/** Below this height the button no longer fits above the number: it moves beside them, or gives way. */
private val ROOMY_HEIGHT = 140.dp

/** From this width the button fits beside everything on a short widget. */
private val WIDE_WIDTH = 220.dp

private val BUTTON_TOUCH = 48.dp
private const val LINE_ASPECT = 240f / 64f
private const val WIDE_LINE_ASPECT = 480f / 64f

@Composable
private fun WidgetContent(outcome: SyncStatusStore.Outcome, labels: WidgetLabels) {
    val size = LocalSize.current
    val roomy = size.height >= ROOMY_HEIGHT
    val buttonBeside = !roomy && size.width >= WIDE_WIDTH
    val inset = if (roomy) 14.dp else 10.dp
    val room = size.width - inset * 2 - if (roomy || buttonBeside) BUTTON_TOUCH else 0.dp
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_ground))
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = inset, vertical = if (roomy) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WidgetPulse(outcome, labels, room, maxHeight = if (roomy) 28.dp else 18.dp)
                if (roomy) {
                    Spacer(GlanceModifier.defaultWeight())
                    SyncNowButton(labels)
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            WidgetText(labels, R.layout.widget_text_number, labels.count, GlanceModifier.fillMaxWidth().height(if (roomy) 40.dp else 30.dp))
            WidgetText(labels, R.layout.widget_text_label, labels.recordsToday, GlanceModifier.fillMaxWidth().height(18.dp))
            Spacer(GlanceModifier.defaultWeight())
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!labels.againstLauncher) StatusSymbol(outcome, labels)
                WidgetText(labels, R.layout.widget_text_status, labels.lastSync, GlanceModifier.defaultWeight().height(16.dp))
                if (labels.againstLauncher) StatusSymbol(outcome, labels)
            }
        }
        // On the smallest size the button gives way: a tap still opens the app.
        if (buttonBeside) SyncNowButton(labels)
    }
}

/** The app's heartbeat ending in a dot, flat before the first sync, amber after a failed one. */
@Composable
private fun RowScope.WidgetPulse(outcome: SyncStatusStore.Outcome, labels: WidgetLabels, room: Dp, maxHeight: Dp) {
    val aspect = if (room.value / WIDE_LINE_ASPECT >= 20f) WIDE_LINE_ASPECT else LINE_ASPECT
    val height = minOf(room / aspect, maxHeight)
    val line = when {
        outcome == SyncStatusStore.Outcome.NEVER && aspect == WIDE_LINE_ASPECT -> R.drawable.widget_pulse_flat_wide
        outcome == SyncStatusStore.Outcome.NEVER -> R.drawable.widget_pulse_flat
        aspect == WIDE_LINE_ASPECT -> R.drawable.widget_pulse_beat_wide
        else -> R.drawable.widget_pulse_beat
    }
    val color = when (outcome) {
        SyncStatusStore.Outcome.NEVER -> labels.flat
        SyncStatusStore.Outcome.SUCCEEDED -> labels.accent
        SyncStatusStore.Outcome.FAILED -> labels.warning
    }
    Image(
        provider = ImageProvider(line),
        contentDescription = null,
        modifier = GlanceModifier.size(height * aspect, height),
        contentScale = ContentScale.FillBounds,
        colorFilter = ColorFilter.tint(color)
    )
}

@Composable
private fun SyncNowButton(labels: WidgetLabels) {
    Box(
        modifier = GlanceModifier
            .size(BUTTON_TOUCH)
            .clickable(actionRunCallback<SyncNowWidgetAction>())
            .semantics { contentDescription = labels.syncNow },
        contentAlignment = Alignment.CenterEnd
    ) {
        Box(
            modifier = GlanceModifier.size(36.dp).background(ImageProvider(R.drawable.widget_sync_button)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_tile_sync),
                contentDescription = null,
                modifier = GlanceModifier.size(20.dp),
                colorFilter = ColorFilter.tint(labels.onAccent)
            )
        }
    }
}

/** A symbol as well as a colour, so the state does not rest on colour alone. */
@Composable
private fun StatusSymbol(outcome: SyncStatusStore.Outcome, labels: WidgetLabels) {
    val (symbol, color) = when (outcome) {
        SyncStatusStore.Outcome.NEVER -> return
        SyncStatusStore.Outcome.SUCCEEDED -> R.drawable.widget_status_ok to labels.accentText
        SyncStatusStore.Outcome.FAILED -> R.drawable.widget_status_warning to labels.warning
    }
    Image(
        provider = ImageProvider(symbol),
        contentDescription = labels.outcome,
        modifier = GlanceModifier.padding(horizontal = 2.dp).size(16.dp),
        colorFilter = ColorFilter.tint(color)
    )
}

@Composable
private fun WidgetText(labels: WidgetLabels, @LayoutRes layout: Int, text: String, modifier: GlanceModifier) {
    val views = RemoteViews(labels.packageName, layout).apply {
        setTextViewText(R.id.widget_text, text)
        if (labels.againstLauncher) setInt(R.id.widget_text, "setGravity", Gravity.END or Gravity.CENTER_VERTICAL)
    }
    AndroidRemoteViews(views, modifier)
}

class SyncStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SyncStatusWidget()
}
