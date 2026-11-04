package com.owen282000.lifedashboard.screens

import android.content.res.Resources
import com.owen282000.lifedashboard.BackfillTexts
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.viewmodel.UiMessage

/** Turns a [UiMessage] into the text the user sees; used for toasts and the sync line. */
fun UiMessage.text(res: Resources): String = when (this) {
    UiMessage.IntervalTooShort -> res.getString(R.string.webhook_min_interval)
    UiMessage.InvalidDayBoundaryHour -> res.getString(R.string.screentime_day_boundary_hour_invalid)
    UiMessage.InvalidUrl -> res.getString(R.string.webhook_enter_valid_url)
    UiMessage.DuplicateUrl -> res.getString(R.string.webhook_url_duplicate)
    UiMessage.SecretsNotReady -> res.getString(R.string.secrets_not_ready)
    is UiMessage.UrlHasCredentials -> parameter?.let { res.getString(R.string.webhook_url_credential_parameter, it) }
        ?: res.getString(R.string.webhook_url_user_info)
    UiMessage.HeaderNeedsNameAndValue -> res.getString(R.string.webhook_enter_header_name_and_value)
    UiMessage.InvalidHeaderName -> res.getString(R.string.webhook_header_name_invalid)
    UiMessage.InvalidHeaderValue -> res.getString(R.string.webhook_header_value_invalid)
    is UiMessage.HeaderBlocksDelivery -> res.getString(
        if (inValue) R.string.webhook_header_value_blocks_delivery else R.string.webhook_header_name_blocks_delivery,
        name
    )
    UiMessage.MqttHostMissing -> res.getString(R.string.mqtt_host_missing)
    UiMessage.MqttHostInvalid -> res.getString(R.string.mqtt_host_invalid)
    UiMessage.MqttPortInvalid -> res.getString(R.string.mqtt_port_invalid)
    UiMessage.NoNewData -> res.getString(R.string.sync_no_new_data)
    is UiMessage.SyncedRecords -> res.getQuantityString(R.plurals.health_synced_records, count, count)
    is UiMessage.QueuedRecords -> res.getQuantityString(R.plurals.health_queued_records, count, count)
    is UiMessage.SyncedApps -> res.getQuantityString(R.plurals.screentime_apps_synced, count, count)
    is UiMessage.QueuedApps -> res.getQuantityString(R.plurals.screentime_apps_queued, count, count)
    is UiMessage.SyncFailed -> res.getString(R.string.sync_failed_with_reason, reason)
    UiMessage.HealthConnectUnavailable -> res.getString(R.string.health_connect_not_available_short)
    UiMessage.UsageAccessMissing -> res.getString(R.string.screentime_permission_not_granted)
    is UiMessage.PreviewFailed -> reason?.let { res.getString(R.string.sync_preview_failed_with_reason, it) }
        ?: res.getString(R.string.sync_preview_failed)
    is UiMessage.ExportFailed -> reason?.let { res.getString(R.string.sync_export_failed_with_reason, it) }
        ?: res.getString(R.string.sync_export_failed)
    UiMessage.PingDelivered -> res.getString(R.string.health_test_ping_delivered)
    UiMessage.PingFailed -> res.getString(R.string.health_test_ping_failed)
    is UiMessage.PingFailedWith -> res.getString(R.string.health_test_ping_failed_with_reason, reason)
    is UiMessage.BackfillComplete -> res.getQuantityString(R.plurals.health_backfill_complete, count, count)
    UiMessage.BackfillNeedsWebhook -> res.getString(R.string.health_backfill_needs_webhook)
    is UiMessage.BackfillFailed -> BackfillTexts.failure(res, failure)
    UiMessage.BackfillAlreadyRunning -> res.getString(R.string.health_backfill_already_running)
    UiMessage.BackfillNoRecords -> res.getString(R.string.health_backfill_no_records)
    is UiMessage.SyncedRecordsWritten -> res.getString(
        R.string.receive_synced_and_written,
        res.getQuantityString(R.plurals.health_synced_records, count, count),
        res.getQuantityString(R.plurals.receive_written_count, written, written)
    )
    UiMessage.ReceiveNeedsIntegration -> res.getString(R.string.receive_needs_integration)
    is UiMessage.PartlyDelivered -> inner.text(res) + " " + res.getString(R.string.sync_partly_delivered, delivered, total)
    is UiMessage.OtherSourceWrites -> res.getString(R.string.receive_other_source_writes, source, res.getString(type.dataType.displayNameRes))
}
