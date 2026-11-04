package com.owen282000.lifedashboard.sync

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.health.connect.client.records.StepsRecord
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.ConfigBackup
import com.owen282000.lifedashboard.ConfigBackupManager
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.MainActivity
import com.owen282000.lifedashboard.OnboardingSupport
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Home after Sync now (T52): the heading and the row reload with the sync's result without a tab
 * switch (the refreshKey of 1.20.0). Texts come from the app's resources, so the test holds in
 * any locale.
 */
@RunWith(AndroidJUnit4::class)
class DashboardUiTest {

    private val receiver = Receiver()
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver)).around(compose)

    private val context = TestSetup.context

    @Test
    fun syncNowRefreshesDashboard() {
        HcFixture.awayFromMidnight()
        TestSetup.health(receiver, setOf(STEPS))
        val fixture = HcFixture(context)
        fixture.assertNoForeignRecords(StepsRecord::class)
        fixture.insert(fixture.steps(64, ago(2), ago(1)))
        context.appPreferences().setOnboardingCompleted()
        val waiting = context.getString(R.string.ld_home_heading_waiting)
        val arriving = context.getString(R.string.ld_home_heading_arriving)
        val syncNow = context.getString(R.string.sync_now)

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodes(hasText(waiting)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(syncNow).performScrollTo().performClick()

            compose.waitUntil(30_000) { compose.onAllNodes(hasText(arriving)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("the sync went out", 1, receiver.exchanges.size)
        }
    }

    /**
     * T53. The first-run wizard, driven like a person would with a webhook and the essential
     * types, writes settings that a backup carries whole: its export imports into the same
     * settings again. Texts come from the app's resources.
     */
    @Test
    fun onboardingEqualsImport() {
        val url = "https://example.invalid/api/webhook/ci-wizard"
        fun text(id: Int) = context.getString(id)

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodes(hasText(text(R.string.onboarding_get_started))).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text(R.string.onboarding_get_started)).performClick()
            compose.onNodeWithText(text(R.string.onboarding_feature_screen)).performClick() // health only
            compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
            compose.onNode(hasSetTextAction()).performScrollTo().performTextInput(url)
            compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
            compose.onNodeWithText(text(R.string.onboarding_types_essentials)).performClick()
            compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
            compose.onNodeWithText(text(R.string.onboarding_open_app)).performClick()
            compose.waitUntil(10_000) { context.appPreferences().onboardingCompleted() }
        }

        val prefs = context.appPreferences()
        assertEquals(listOf(url), prefs.getHealthWebhookUrls())
        assertEquals(emptyList<String>(), prefs.getScreenTimeWebhookUrls())
        assertEquals(OnboardingSupport.typesFor(OnboardingSupport.TypePreset.ESSENTIALS), prefs.getHealthEnabledDataTypes())

        val wizard = ConfigBackupManager(context).export().copy(exportedAt = null)
        AppStateRule.reset()
        ConfigBackupManager(context).import(ConfigBackup.decode(wizard.encode()))
        assertEquals(wizard, ConfigBackupManager(context).export().copy(exportedAt = null))
    }
}
