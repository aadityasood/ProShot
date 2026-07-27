package com.proshot.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BeginnerCameraControlsInstrumentedTest {

    private companion object {
        const val LANDSCAPE_CONTAINER_TAG = "landscape-controls-container"
        const val EDGE_INSET_TOLERANCE_DP = 0.5f
    }

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

    @Test
    fun beginnerCameraControls_landscapeLeft_retainsPhysicalLeftInset_underLtr() {
        assertLandscapePhysicalInset(
            placement = CaptureControlsPlacement.LANDSCAPE_LEFT,
            layoutDirection = LayoutDirection.Ltr
        )
    }

    @Test
    fun beginnerCameraControls_landscapeLeft_retainsPhysicalLeftInset_underRtl() {
        assertLandscapePhysicalInset(
            placement = CaptureControlsPlacement.LANDSCAPE_LEFT,
            layoutDirection = LayoutDirection.Rtl
        )
    }

    @Test
    fun beginnerCameraControls_landscapeRight_retainsPhysicalRightInset_underLtr() {
        assertLandscapePhysicalInset(
            placement = CaptureControlsPlacement.LANDSCAPE_RIGHT,
            layoutDirection = LayoutDirection.Ltr
        )
    }

    @Test
    fun beginnerCameraControls_landscapeRight_retainsPhysicalRightInset_underRtl() {
        assertLandscapePhysicalInset(
            placement = CaptureControlsPlacement.LANDSCAPE_RIGHT,
            layoutDirection = LayoutDirection.Rtl
        )
    }

    @Test
    fun observedDisplayRotation_direct90To270_repositionsInFixedLandscapeAndCleansUp() {
        val source = FakeDisplayRotationSource(
            initial = CurrentDisplayRotation(displayId = 7, rotationDegrees = 90)
        )
        var showControls by mutableStateOf(true)

        composeTestRule.setContent {
            if (showControls) {
                ObservedLandscapeControls(source)
            }
        }

        assertCurrentLandscapePhysicalInset(CaptureControlsPlacement.LANDSCAPE_RIGHT)

        composeTestRule.runOnIdle {
            source.notifyDisplayChanged(displayId = 99)
        }
        assertCurrentLandscapePhysicalInset(CaptureControlsPlacement.LANDSCAPE_RIGHT)

        composeTestRule.runOnIdle {
            source.changeCurrentRotation(rotationDegrees = 270)
        }
        assertCurrentLandscapePhysicalInset(CaptureControlsPlacement.LANDSCAPE_LEFT)

        composeTestRule.runOnIdle {
            showControls = false
        }
        composeTestRule.waitForIdle()
        source.assertExactCleanup()
    }

    @Test
    fun observedDisplayRotation_direct270To90_repositionsInFixedLandscapeAndCleansUp() {
        val source = FakeDisplayRotationSource(
            initial = CurrentDisplayRotation(displayId = 11, rotationDegrees = 270)
        )
        var showControls by mutableStateOf(true)

        composeTestRule.setContent {
            if (showControls) {
                ObservedLandscapeControls(source)
            }
        }

        assertCurrentLandscapePhysicalInset(CaptureControlsPlacement.LANDSCAPE_LEFT)

        composeTestRule.runOnIdle {
            source.notifyDisplayChanged(displayId = 99)
        }
        assertCurrentLandscapePhysicalInset(CaptureControlsPlacement.LANDSCAPE_LEFT)

        composeTestRule.runOnIdle {
            source.changeCurrentRotation(rotationDegrees = 90)
        }
        assertCurrentLandscapePhysicalInset(CaptureControlsPlacement.LANDSCAPE_RIGHT)

        composeTestRule.runOnIdle {
            showControls = false
        }
        composeTestRule.waitForIdle()
        source.assertExactCleanup()
    }

    private fun assertLandscapePhysicalInset(
        placement: CaptureControlsPlacement,
        layoutDirection: LayoutDirection
    ) {
        val alignment = when (placement) {
            CaptureControlsPlacement.LANDSCAPE_LEFT -> AbsoluteAlignment.CenterLeft
            CaptureControlsPlacement.LANDSCAPE_RIGHT -> AbsoluteAlignment.CenterRight
            CaptureControlsPlacement.PORTRAIT_BOTTOM -> error("Landscape placement required")
        }

        composeTestRule.setContent {
            LandscapeControlsContainer(
                placement = placement,
                layoutDirection = layoutDirection,
                alignment = alignment
            )
        }

        assertCurrentLandscapePhysicalInset(placement)
    }

    @Composable
    private fun ObservedLandscapeControls(source: DisplayRotationSource) {
        val rotationDegrees = rememberCurrentDisplayRotationDegrees(source)
        val placement = CapturePlacementPolicy.resolve(
            width = 800,
            height = 400,
            displayRotationDegrees = rotationDegrees
        )
        val alignment = when (placement) {
            CaptureControlsPlacement.LANDSCAPE_LEFT -> AbsoluteAlignment.CenterLeft
            CaptureControlsPlacement.LANDSCAPE_RIGHT -> AbsoluteAlignment.CenterRight
            CaptureControlsPlacement.PORTRAIT_BOTTOM -> error("Fixed container must be landscape")
        }

        LandscapeControlsContainer(
            placement = placement,
            layoutDirection = LayoutDirection.Ltr,
            alignment = alignment
        )
    }

    @Composable
    private fun LandscapeControlsContainer(
        placement: CaptureControlsPlacement,
        layoutDirection: LayoutDirection,
        alignment: Alignment
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            Box(
                modifier = Modifier
                    .size(800.dp, 400.dp)
                    .testTag(LANDSCAPE_CONTAINER_TAG)
            ) {
                BeginnerCameraControls(
                    isCapturing = false,
                    isWaiting = false,
                    enabled = true,
                    gridVisible = false,
                    onGridToggle = {},
                    onShutterClick = {},
                    placement = placement,
                    modifier = Modifier.align(alignment)
                )
            }
        }
    }

    private fun assertCurrentLandscapePhysicalInset(placement: CaptureControlsPlacement) {
        val containerBounds = composeTestRule
            .onNodeWithTag(LANDSCAPE_CONTAINER_TAG)
            .getUnclippedBoundsInRoot()
        val shutterBounds = composeTestRule
            .onNode(hasContentDescription("Take photo"))
            .getUnclippedBoundsInRoot()
        val actualInset = when (placement) {
            CaptureControlsPlacement.LANDSCAPE_LEFT -> shutterBounds.left - containerBounds.left
            CaptureControlsPlacement.LANDSCAPE_RIGHT -> containerBounds.right - shutterBounds.right
            CaptureControlsPlacement.PORTRAIT_BOTTOM -> error("Landscape placement required")
        }

        assertEquals(
            "Physical edge inset for $placement",
            32f,
            actualInset.value,
            EDGE_INSET_TOLERANCE_DP
        )
    }

    private class FakeDisplayRotationSource(
        initial: CurrentDisplayRotation
    ) : DisplayRotationSource {
        private var current = initial
        private var activeListener: ((Int) -> Unit)? = null
        private var registeredListener: ((Int) -> Unit)? = null
        private var disposedListener: ((Int) -> Unit)? = null
        private var registrationCount = 0
        private var disposalCount = 0

        override fun currentDisplayRotation(): CurrentDisplayRotation = current

        override fun observeDisplayChanges(
            onDisplayChanged: (displayId: Int) -> Unit
        ): DisplayRotationSubscription {
            check(activeListener == null)
            activeListener = onDisplayChanged
            registeredListener = onDisplayChanged
            registrationCount++
            return DisplayRotationSubscription {
                disposedListener = onDisplayChanged
                if (activeListener === onDisplayChanged) {
                    activeListener = null
                }
                disposalCount++
            }
        }

        fun notifyDisplayChanged(displayId: Int) {
            activeListener?.invoke(displayId)
        }

        fun changeCurrentRotation(rotationDegrees: Int) {
            current = current.copy(rotationDegrees = rotationDegrees)
            notifyDisplayChanged(current.displayId)
        }

        fun assertExactCleanup() {
            assertEquals(1, registrationCount)
            assertEquals(1, disposalCount)
            assertSame(registeredListener, disposedListener)
            assertNull(activeListener)
        }
    }
}
