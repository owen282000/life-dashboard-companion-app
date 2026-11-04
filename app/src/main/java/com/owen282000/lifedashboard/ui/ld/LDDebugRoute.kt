package com.owen282000.lifedashboard.ui.ld

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/** What `--es ld.route <name>` opens, in a debug build. See README.md. */
sealed interface LDDebugDestination {
    data class Page(val route: LDRoute) : LDDebugDestination

    /** The onboarding flow, whatever the settings say. */
    data object Onboarding : LDDebugDestination

    /** The QR scanner, over the shell. */
    data object Scanner : LDDebugDestination

    /** The pairing confirmation, with an example code. */
    data object Pairing : LDDebugDestination
}

object LDDebugRoute {
    const val EXTRA = "ld.route"

    /** The code the `pairing` and `pairedResult` names show. Example data, debug builds only. */
    const val EXAMPLE_PAIRING_LINK =
        "lifedashboard://pair#v=1&url=http%3A%2F%2Fhomeassistant.local%3A8123%2Fapi%2Fwebhook%2F3f9c2a7e51b84d06a1e2c9d8b7f4e5a1&secret=0123456789abcdef&name=Home%20Assistant"

    /** Every name but the ones that need a row of the log or a listed destination. */
    val plain: Map<String, LDDebugDestination> = buildMap {
        fun page(name: String, route: LDRoute) = put(name, LDDebugDestination.Page(route))
        page("home", LDRoute.Home)
        page("screenTimeHome", LDRoute.ScreenTimeHome)
        page("logsList", LDRoute.LogsList)
        page("addDestination", LDRoute.AddDestination())
        page("addDestination.screenTime", LDRoute.AddDestination(LDSection.ScreenTime))
        page("webhookNew", LDRoute.WebhookDestination())
        page("webhookNew.screenTime", LDRoute.WebhookDestination(LDSection.ScreenTime))
        page("mqttDestination", LDRoute.MqttDestination())
        page("mqttDestination.screenTime", LDRoute.MqttDestination(LDSection.ScreenTime))
        page("pairedResult", LDRoute.PairedResult)
        page("whatToSend", LDRoute.WhatToSend)
        LDTypeCategory.entries.forEach { page("typeCategory.${it.name}", LDRoute.TypeCategory(it)) }
        page("dataResolution", LDRoute.DataResolution)
        page("backfill", LDRoute.Backfill())
        page("backfillRun", LDRoute.Backfill(LDBackfillStage.Running))
        page("payloadPreview", LDRoute.PayloadPreview())
        page("payloadPreview.screenTime", LDRoute.PayloadPreview(LDSection.ScreenTime))
        page("whenToSend", LDRoute.WhenToSend)
        page("receive", LDRoute.Receive)
        page("more", LDRoute.More)
        page("advanced", LDRoute.Advanced())
        page("advanced.screenTime", LDRoute.Advanced(LDSection.ScreenTime))
        page("serverCertificates", LDRoute.ServerCertificates)
        page("notifications", LDRoute.NotificationSettings)
        page("exportData", LDRoute.ExportData())
        page("exportData.screenTime", LDRoute.ExportData(LDSection.ScreenTime))
        page("backupRestore", LDRoute.BackupRestore)
        page("screenTimeApps", LDRoute.ScreenTimeApps)
        page("screenTimeDay", LDRoute.ScreenTimeDay)
        page("screenTimeWhen", LDRoute.ScreenTimeWhen)
        page("screenTimeMore", LDRoute.ScreenTimeMore)
        page("about", LDRoute.AboutHome)
        page("aboutHelp", LDRoute.AboutHelp)
        page("aboutSupport", LDRoute.AboutSupport)
        page("aboutPrivacy", LDRoute.AboutPrivacy)
        page("aboutOpenSource", LDRoute.AboutOpenSource)
        page("aboutNerd", LDRoute.AboutNerd)
        put("onboarding", LDDebugDestination.Onboarding)
        put("scanner", LDDebugDestination.Scanner)
        put("pairing", LDDebugDestination.Pairing)
    }

    /**
     * `logDetail` is the newest row, `logDetail.failed` and `logDetail.ok` the newest of that kind;
     * `webhookDestination` is the first listed destination of the section.
     */
    fun named(
        name: String,
        newestLog: String? = null,
        newestFailedLog: String? = null,
        newestOkLog: String? = null,
        firstHealthDestination: String? = null,
        firstScreenTimeDestination: String? = null
    ): LDDebugDestination? {
        fun log(id: String?) = id?.let { LDDebugDestination.Page(LDRoute.LogDetail(it)) }
        return when (name) {
            "logDetail" -> log(newestLog)
            "logDetail.failed" -> log(newestFailedLog)
            "logDetail.ok" -> log(newestOkLog)
            "webhookDestination" -> firstHealthDestination?.let { LDDebugDestination.Page(LDRoute.WebhookDestination(LDSection.Health, it)) }
            "webhookDestination.screenTime" -> firstScreenTimeDestination?.let { LDDebugDestination.Page(LDRoute.WebhookDestination(LDSection.ScreenTime, it)) }
            else -> plain[name]
        }
    }

    /** The name in [intent], only in a debuggable build: MainActivity is exported, so anything can send it. */
    fun nameIn(context: Context, intent: Intent?): String? =
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) intent?.getStringExtra(EXTRA) else null
}
