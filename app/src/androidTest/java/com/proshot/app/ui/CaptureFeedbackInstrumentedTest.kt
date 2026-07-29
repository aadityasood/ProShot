package com.proshot.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureFeedbackInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun captureFeedbackPill_whenCapturing_exposesOneNodeWithExactContentDescriptionAndPoliteLiveRegion() {
        composeTestRule.setContent {
            CaptureFeedbackPill(message = "Taking photo...")
        }

        val politeLiveRegion = SemanticsMatcher.expectValue(
            SemanticsProperties.LiveRegion,
            LiveRegionMode.Polite
        )

        composeTestRule.onNode(hasContentDescription("Taking photo..."))
            .assertIsDisplayed()
            .assert(politeLiveRegion)

        composeTestRule.onAllNodes(hasContentDescription("Taking photo..."))
            .assertCountEquals(1)
    }

    @Test
    fun captureFeedbackPill_whenSuccess_exposesOneNodeWithExactContentDescriptionAndPoliteLiveRegion() {
        composeTestRule.setContent {
            CaptureFeedbackPill(message = "Photo saved to Pictures/ProShot")
        }

        val politeLiveRegion = SemanticsMatcher.expectValue(
            SemanticsProperties.LiveRegion,
            LiveRegionMode.Polite
        )

        composeTestRule.onNode(hasContentDescription("Photo saved to Pictures/ProShot"))
            .assertIsDisplayed()
            .assert(politeLiveRegion)

        composeTestRule.onAllNodes(hasContentDescription("Photo saved to Pictures/ProShot"))
            .assertCountEquals(1)
    }

    @Test
    fun captureFeedbackPill_whenFailure_exposesOneNodeWithExactContentDescriptionAndPoliteLiveRegion() {
        composeTestRule.setContent {
            CaptureFeedbackPill(message = "Capture failed: Device error")
        }

        val politeLiveRegion = SemanticsMatcher.expectValue(
            SemanticsProperties.LiveRegion,
            LiveRegionMode.Polite
        )

        composeTestRule.onNode(hasContentDescription("Capture failed: Device error"))
            .assertIsDisplayed()
            .assert(politeLiveRegion)

        composeTestRule.onAllNodes(hasContentDescription("Capture failed: Device error"))
            .assertCountEquals(1)
    }

    @Test
    fun captureFeedbackPill_whenHidden_removesNode() {
        composeTestRule.setContent {
            CaptureFeedbackPill(message = "")
        }

        val politeLiveRegion = SemanticsMatcher.expectValue(
            SemanticsProperties.LiveRegion,
            LiveRegionMode.Polite
        )

        composeTestRule.onAllNodes(politeLiveRegion, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun captureFeedbackPlacement_portrait_sitsFullyAboveShutterWithGap() {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                CaptureFeedbackPlacement(
                    state = CaptureFeedbackState.Capturing(
                        eventToken = 1L,
                        text = "Taking photo..."
                    ),
                    focusMessage = "Focus set",
                    onDismiss = {}
                )
                BeginnerCameraControls(
                    isCapturing = true,
                    isWaiting = false,
                    enabled = false,
                    gridVisible = false,
                    onGridToggle = {},
                    onShutterClick = {},
                    placement = CaptureControlsPlacement.PORTRAIT_BOTTOM,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                )
            }
        }

        val feedbackBounds = composeTestRule
            .onNode(hasContentDescription("Taking photo..."))
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val shutterBounds = composeTestRule
            .onNode(hasContentDescription("Capturing photo"))
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(
            "Feedback must have a nonzero vertical gap above the portrait shutter",
            feedbackBounds.bottom < shutterBounds.top
        )
    }

    @Test
    fun captureFeedbackHost_terminalStateAutoDismissesAfter5000ms_usingTestClock() {
        composeTestRule.mainClock.autoAdvance = false

        var reducerState by mutableStateOf<CaptureFeedbackState>(CaptureFeedbackState.Hidden())
        val reducer = CaptureFeedbackReducer()

        composeTestRule.setContent {
            CaptureFeedbackHost(
                state = reducerState,
                focusMessage = "Focus set",
                onDismiss = { eventToken ->
                    reducerState = reducer.dismiss(eventToken)
                }
            )
        }

        composeTestRule.runOnIdle {
            reducer.startCapture("Taking photo...")
            reducerState = reducer.completeCapture("Photo saved")
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasContentDescription("Photo saved"))
            .assertIsDisplayed()
        composeTestRule.onAllNodes(hasContentDescription("Focus set"))
            .assertCountEquals(0)

        composeTestRule.mainClock.advanceTimeBy(4_999L, ignoreFrameDuration = true)

        composeTestRule.onNode(hasContentDescription("Photo saved"))
            .assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(1L, ignoreFrameDuration = true)
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodes(hasContentDescription("Photo saved"))
            .assertCountEquals(0)
    }

    @Test
    fun captureFeedbackHost_staleDismissRace_doesNotClearNewerCapturingState() {
        composeTestRule.mainClock.autoAdvance = false

        var reducerState by mutableStateOf<CaptureFeedbackState>(CaptureFeedbackState.Hidden())
        val reducer = CaptureFeedbackReducer()

        composeTestRule.setContent {
            CaptureFeedbackHost(
                state = reducerState,
                focusMessage = "Focus set",
                onDismiss = { eventToken ->
                    reducerState = reducer.dismiss(eventToken)
                }
            )
        }

        composeTestRule.runOnIdle {
            reducer.startCapture("Taking photo 1")
            reducerState = reducer.completeCapture("Photo saved 1")
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasContentDescription("Photo saved 1"))
            .assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(2_000L, ignoreFrameDuration = true)

        composeTestRule.runOnIdle {
            reducerState = reducer.startCapture("Taking photo 2...")
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasContentDescription("Taking photo 2..."))
            .assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(3_000L, ignoreFrameDuration = true)
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasContentDescription("Taking photo 2..."))
            .assertIsDisplayed()
        composeTestRule.onAllNodes(hasContentDescription("Focus set"))
            .assertCountEquals(0)
        composeTestRule.onAllNodes(hasContentDescription("Photo saved 1"))
            .assertCountEquals(0)
    }
}
