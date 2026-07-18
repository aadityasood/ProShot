package com.proshot.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BeginnerCameraControlsInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun shutterButton_whenWaiting_reflectsLoadingSemantics() {
        composeTestRule.setContent {
            ShutterButton(
                enabled = false,
                isCapturing = false,
                isWaiting = true,
                onClick = {}
            )
        }

        // Semantics: contentDescription="Camera loading", stateDescription="Waiting"
        composeTestRule.onNode(hasContentDescription("Camera loading") and hasStateDescription("Waiting"))
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun shutterButton_whenCapturing_reflectsCapturingSemantics() {
        composeTestRule.setContent {
            ShutterButton(
                enabled = false,
                isCapturing = true,
                isWaiting = false,
                onClick = {}
            )
        }

        // Semantics: contentDescription="Capturing photo", stateDescription="Capturing"
        composeTestRule.onNode(hasContentDescription("Capturing photo") and hasStateDescription("Capturing"))
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun shutterButton_whenEnabled_reflectsReadySemantics() {
        composeTestRule.setContent {
            ShutterButton(
                enabled = true,
                isCapturing = false,
                isWaiting = false,
                onClick = {}
            )
        }

        // Semantics: contentDescription="Take photo", stateDescription="Ready"
        composeTestRule.onNode(hasContentDescription("Take photo") and hasStateDescription("Ready"))
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun shutterButton_whenDisabled_reflectsDisabledSemantics() {
        composeTestRule.setContent {
            ShutterButton(
                enabled = false,
                isCapturing = false,
                isWaiting = false,
                onClick = {}
            )
        }

        // Semantics: contentDescription="Take photo", stateDescription="Disabled"
        composeTestRule.onNode(hasContentDescription("Take photo") and hasStateDescription("Disabled"))
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun gridToggleButton_reflectsToggleAndStateSemantics() {
        var gridVisible by mutableStateOf(false)
        var toggleCalls = 0

        composeTestRule.setContent {
            GridToggleButton(
                gridVisible = gridVisible,
                onToggle = {
                    toggleCalls++
                    gridVisible = !gridVisible
                }
            )
        }

        val switchRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

        composeTestRule.onNode(
            hasContentDescription("Toggle composition grid") and hasStateDescription("Grid is off")
        )
            .assertIsDisplayed()
            .assertIsOff()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assert(switchRole)
            .performClick()

        // Verify callback invoked once
        assertEquals(1, toggleCalls)

        // Verify on state is exposed after update
        composeTestRule.onNode(
            hasContentDescription("Toggle composition grid") and hasStateDescription("Grid is on")
        )
            .assertIsDisplayed()
            .assertIsOn()
            .assert(switchRole)
    }

    @Test
    fun ruleOfThirdsGrid_hasNoSeparateActionableSemanticsNode() {
        composeTestRule.setContent {
            RuleOfThirdsGrid()
        }

        // Verify that passive decorative grid Canvas does not publish any clickable semantics node
        composeTestRule.onAllNodes(hasClickAction())
            .assertCountEquals(0)
    }
}
