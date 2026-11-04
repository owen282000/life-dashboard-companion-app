package com.owen282000.lifedashboard.a11y

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
import com.owen282000.lifedashboard.screens.ld.HeaderRow
import com.owen282000.lifedashboard.screens.ld.LDSyncMessageLine
import com.owen282000.lifedashboard.ui.ld.LDOption
import com.owen282000.lifedashboard.ui.ld.LDOptionMark
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDToggleRow
import com.owen282000.lifedashboard.viewmodel.UiMessage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What TalkBack is given by the kit's shared pieces: a switch row is one named switch, a sync
 * result is a live region, a type without permission says so and still says On or Off, a check
 * option is a checkbox, and a remove cross is a full-size target. Asserted on the semantics tree,
 * which is what TalkBack reads.
 */
@RunWith(AndroidJUnit4::class)
class TalkBackSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val res = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun aSwitchRowIsOneNamedSwitchThatTheWholeRowFlips() {
        var checked by mutableStateOf(false)
        compose.setContent {
            LDTheme {
                LDToggleRow("Allow plain HTTP", checked, { checked = it }, subtitle = "Only on your own network")
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
        compose.setContent { LDTheme { LDSyncMessageLine(message) } }
        message = UiMessage.PingDelivered
        compose.onNodeWithText(res.getString(R.string.health_test_ping_delivered))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun aTypeWithoutPermissionSaysSoAndIsStillOneSwitch() {
        val type = HealthDataType.entries.first()
        val name = res.getString(type.displayNameRes)
        var checked by mutableStateOf(true)
        compose.setContent {
            LDTheme {
                LDToggleRow(name, checked, { checked = it }, locked = true)
            }
        }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        // On and Off stay TalkBack's own, so a locked row that is on is announced as on.
        compose.onNodeWithText(name)
            .assert(hasRole(Role.Switch))
            .assert(hasContentDescription(res.getString(R.string.ld_type_locked)))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .assertIsOn()
        compose.onNodeWithText(name).performClick()
        compose.onNodeWithText(name).assertIsOff()
    }

    @Test
    fun aCheckOptionIsACheckbox() {
        var selected by mutableStateOf(false)
        compose.setContent {
            LDTheme {
                LDOption("Health Connect", selected, { selected = !selected }, mark = LDOptionMark.Check)
            }
        }
        compose.onNodeWithText("Health Connect")
            .assert(hasRole(Role.Checkbox))
            .assertIsOff()
            .performClick()
        compose.onNodeWithText("Health Connect").assertIsOn()
    }

    @Test
    fun theRemoveCrossOfAHeaderIsAFullTouchTarget() {
        val label = res.getString(R.string.ld_remove_named, "X-Api-Key")
        compose.setContent {
            LDTheme {
                HeaderRow("X-Api-Key", "Bearer secret", onRemove = {})
            }
        }
        compose.onAllNodesWithContentDescription(label).assertCountEquals(1)
        compose.onNodeWithContentDescription(label)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assertTouchWidthIsEqualTo(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
    }
}
