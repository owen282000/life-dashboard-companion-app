package com.owen282000.lifedashboard.screens.ld

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.ServerTrust
import com.owen282000.lifedashboard.WebhookTrust
import com.owen282000.lifedashboard.viewmodel.HealthActions
import com.owen282000.lifedashboard.viewmodel.ScreenTimeActions

/**
 * The settings both tabs show, for the pages that can be reached from either. Each goes through both
 * view models, so neither shows an old value until the next tab switch.
 */
class LDSharedSettings(private val health: HealthActions, private val screenTime: ScreenTimeActions) {
    fun setAllowHttpWebhooks(enabled: Boolean) {
        health.setAllowHttpWebhooks(enabled)
        screenTime.setAllowHttpWebhooks(enabled)
    }

    fun setClientCertAlias(alias: String?) {
        health.setClientCertAlias(alias)
        screenTime.setClientCertAlias(alias)
    }

    fun setServerTrust(hostKey: String, trust: ServerTrust) {
        health.setServerTrust(hostKey, trust)
        screenTime.setServerTrust(hostKey, trust)
    }

    fun setPhoneName(name: String) {
        health.setPhoneName(name)
        screenTime.setPhoneName(name)
    }

    fun setFailureNotifications(enabled: Boolean) {
        health.setFailureNotifications(enabled)
        screenTime.setFailureNotifications(enabled)
    }

    fun setFailureThreshold(threshold: Int) {
        health.setFailureThreshold(threshold)
        screenTime.setFailureThreshold(threshold)
    }
}

/** The writers of [LDSharedSettings], with both view models brought up to date when the page opens. */
@Composable
fun ldSharedSettings(): LDSharedSettings {
    val health = ldHealthViewModel()
    val screenTime = ldScreenTimeViewModel()
    LaunchedEffect(Unit) {
        health.refreshSharedSettings()
        screenTime.refreshSharedSettings()
    }
    return remember(health, screenTime) { LDSharedSettings(health, screenTime) }
}

/** The https hosts of the webhooks of both sections, each once: what a server certificate can be trusted for. */
@Composable
fun ldTrustableHosts(): List<String> {
    val health by ldHealthViewModel().state.collectAsStateWithLifecycle()
    val screenTime by ldScreenTimeViewModel().state.collectAsStateWithLifecycle()
    return WebhookTrust.hostKeys(health.saved.webhook.urls + screenTime.saved.webhook.urls)
}
