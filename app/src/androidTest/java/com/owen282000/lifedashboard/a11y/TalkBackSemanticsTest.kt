package com.owen282000.lifedashboard.a11y

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.screens.DataTypesRow
import com.owen282000.lifedashboard.screens.SwitchLine
import com.owen282000.lifedashboard.screens.SyncMessageLine
import com.owen282000.lifedashboard.screens.WebhookRow
import com.owen282000.lifedashboard.ui.theme.HealthPrimary
import com.owen282000.lifedashboard.viewmodel.UiMessage
import com.owen282000.lifedashboard.viewmodel.WebhookDraft
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What TalkBack is given by the shared pieces of the tabs: a switch row is one named switch,
 * a sync result is a live region, a type without permission says so, and a remove cross is a
 * full-size target. Asserted on the semantics tree, which is what TalkBack reads.
 */
@RunWith(AndroidJUnit4::class)
class TalkBackSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val res = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun aSwitchLineIsOneNamedSwitchThatTheWholeRowFlips() {
        var checked by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                SwitchLine("Allow plain HTTP", "Only on your own network", checked, HealthPrimary) { checked = it }
            }
        }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        // The label, the description and the state are one node, so they are read together.
        compose.onNodeWithText("Allow plain HTTP")
            .assert(hasRole(Role.Switch))
            .assertIsOff()
        compose.onNodeWithText("Only on your own network").performClick()
        compose.onNodeWithText("Allow plain HTTP").assertIsOn()
        assertTrue(checked)
    }

    @Test
    fun theSyncResultIsReadOutWhenItAppears() {
        var message by mutableStateOf<UiMessage?>(null)
        compose.setContent { MaterialTheme { SyncMessageLine(message, HealthPrimary) } }
        message = UiMessage.PingDelivered
        compose.onNodeWithText(res.getString(R.string.health_test_ping_delivered))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun aTypeWithoutPermissionSaysSoAndIsStillOneSwitch() {
        val type = HealthDataType.entries.first()
        compose.setContent {
            MaterialTheme {
                DataTypesRow(
                    accent = HealthPrimary,
                    enabledTypes = emptySet(),
                    grantedPermissions = emptySet(),
                    expanded = true,
                    onToggle = {},
                    onToggleType = { _, _ -> }
                )
            }
        }
        val label = res.getString(R.string.health_type_permission_missing_a11y, type.displayName)
        compose.onNode(hasContentDescription(label))
            .assert(hasRole(Role.Switch))
            .assertIsOff()
    }

    @Test
    fun theRemoveCrossOfAWebhookIsAFullTouchTarget() {
        compose.setContent {
            MaterialTheme {
                WebhookRow(
                    accent = HealthPrimary,
                    webhook = WebhookDraft(urls = listOf("https://ha.example/api/webhook/x")),
                    expanded = true,
                    onToggle = {},
                    onAddUrl = {},
                    onRemoveUrl = {},
                    onAddHeader = { _, _ -> },
                    onRemoveHeader = {},
                    onSecretChange = {},
                    onScanRequested = {}
                )
            }
        }
        compose.onAllNodesWithContentDescription(res.getString(R.string.common_remove)).assertCountEquals(1)
        // The visible button is no longer squeezed to 32dp, and it is touched at the full 48dp.
        compose.onNodeWithContentDescription(res.getString(R.string.common_remove))
            .assertWidthIsAtLeast(40.dp)
            .assertHeightIsAtLeast(40.dp)
            .assertTouchWidthIsEqualTo(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
    }
}
