package com.owen282000.lifedashboard

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.text.DateFormat
import java.util.Date

/** Home screen widget: last sync result and records delivered today. */
class SyncStatusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val status = SyncStatusStore.read(context)
        val labels = WidgetLabels(
            title = context.getString(R.string.app_name),
            recordsToday = context.resources.getQuantityString(R.plurals.widget_records_today, status.recordsToday),
            lastSync = status.lastSyncMillis?.let {
                context.getString(R.string.widget_synced_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
            } ?: context.getString(R.string.widget_no_syncs),
            outcome = SyncOutcomeText.widgetRes(status.outcome)?.let { context.getString(it) }
        )
        provideContent {
            GlanceTheme {
                WidgetContent(status, labels)
            }
        }
    }

    companion object {
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

/** The widget's text, resolved from resources before composing, in the phone's language. */
private class WidgetLabels(
    val title: String,
    val recordsToday: String,
    val lastSync: String,
    /** What the dot's colour says, for TalkBack; null before the first sync. */
    val outcome: String?
)

@androidx.compose.runtime.Composable
private fun WidgetContent(status: SyncStatusStore.Status, labels: WidgetLabels) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = GlanceModifier
                    .size(8.dp)
                    .cornerRadius(4.dp)
                    .background(
                        ColorProvider(if (status.lastSuccess) Color(0xFF2E7D32) else Color(0xFFC62828))
                    )
                    .then(
                        labels.outcome?.let { outcome -> GlanceModifier.semantics { contentDescription = outcome } }
                            ?: GlanceModifier
                    )
            ) {}
            Spacer(modifier = GlanceModifier.size(6.dp))
            Text(
                text = labels.title,
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
            )
        }

        Spacer(modifier = GlanceModifier.height(8.dp))

        Text(
            text = "${status.recordsToday}",
            style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)
        )
        Text(
            text = labels.recordsToday,
            style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant)
        )

        Spacer(modifier = GlanceModifier.height(6.dp))

        Text(
            text = labels.lastSync,
            style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant)
        )
    }
}

class SyncStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SyncStatusWidget()
}
