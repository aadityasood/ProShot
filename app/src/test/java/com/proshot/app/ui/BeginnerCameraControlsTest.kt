package com.proshot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeginnerCameraControlsTest {

    @Test
    fun placementPolicy_withPortraitDimensions_selectsPortrait() {
        val placement = CapturePlacementPolicy.resolve(width = 1080, height = 1920)
        assertEquals(CaptureControlsPlacement.PORTRAIT, placement)
    }

    @Test
    fun placementPolicy_withLandscapeDimensions_selectsLandscape() {
        val placement = CapturePlacementPolicy.resolve(width = 1920, height = 1080)
        assertEquals(CaptureControlsPlacement.LANDSCAPE, placement)
    }

    @Test
    fun placementPolicy_withSquareDimensions_selectsPortraitFallback() {
        val placement = CapturePlacementPolicy.resolve(width = 1080, height = 1080)
        assertEquals(CaptureControlsPlacement.PORTRAIT, placement)
    }

    @Test
    fun volumeKeyRouter_withVolumeUpInitialDown_acceptedRequestsOneShutterAction() {
        var shutterRequests = 0
        val router = VolumeKeyRouter {
            shutterRequests++
            true
        }

        val consumed = router.dispatchKeyEvent(
            keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
            action = VolumeKeyRouter.ACTION_DOWN,
            repeatCount = 0
        )

        assertTrue(consumed)
        assertEquals(1, shutterRequests)
    }

    @Test
    fun volumeKeyRouter_withVolumeDownInitialDown_acceptedRequestsOneShutterAction() {
        var shutterRequests = 0
        val router = VolumeKeyRouter {
            shutterRequests++
            true
        }

        val consumed = router.dispatchKeyEvent(
            keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_DOWN,
            action = VolumeKeyRouter.ACTION_DOWN,
            repeatCount = 0
        )

        assertTrue(consumed)
        assertEquals(1, shutterRequests)
    }

    @Test
    fun volumeKeyRouter_repeatedDownEventsForAcceptedPress_areConsumedWithoutExtraRequests() {
        var shutterRequests = 0
        val router = VolumeKeyRouter {
            shutterRequests++
            true
        }

        router.dispatchKeyEvent(
            keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
            action = VolumeKeyRouter.ACTION_DOWN,
            repeatCount = 0
        )

        val consumedRepeat1 = router.dispatchKeyEvent(
            keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
            action = VolumeKeyRouter.ACTION_DOWN,
            repeatCount = 1
        )
        val consumedRepeat2 = router.dispatchKeyEvent(
            keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
            action = VolumeKeyRouter.ACTION_DOWN,
            repeatCount = 2
        )

        assertTrue(consumedRepeat1)
        assertTrue(consumedRepeat2)
        assertEquals(1, shutterRequests)
    }

    @Test
    fun volumeKeyRouter_matchingKeyUpForAcceptedPress_isConsumedAndClearsState() {
        var shutterRequests = 0
        val router = VolumeKeyRouter {
            shutterRequests++
            true
        }

        router.dispatchKeyEvent(
            keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
            action = VolumeKeyRouter.ACTION_DOWN,
            repeatCount = 0
        )

        val consumedUp = router.dispatchKeyEvent(
            keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
            action = VolumeKeyRouter.ACTION_UP,
            repeatCount = 0
        )
        assertTrue(consumedUp)

        val consumedSecond = router.dispatchKeyEvent(
            keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
            action = VolumeKeyRouter.ACTION_DOWN,
            repeatCount = 0
        )
        assertTrue(consumedSecond)
        assertEquals(2, shutterRequests)
    }

    @Test
    fun volumeKeyRouter_withRejectedInitialPress_isNotQueuedAndFallsThrough() {
        var shutterRequests = 0
        val router = VolumeKeyRouter {
            shutterRequests++
            false // Reject
        }

        val consumed = router.dispatchKeyEvent(
            keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
            action = VolumeKeyRouter.ACTION_DOWN,
            repeatCount = 0
        )

        assertFalse(consumed)
        assertEquals(1, shutterRequests)

        val consumedRepeat = router.dispatchKeyEvent(
            keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
            action = VolumeKeyRouter.ACTION_DOWN,
            repeatCount = 1
        )
        val consumedUp = router.dispatchKeyEvent(
            keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
            action = VolumeKeyRouter.ACTION_UP,
            repeatCount = 0
        )

        assertFalse(consumedRepeat)
        assertFalse(consumedUp)
    }

    @Test
    fun volumeKeyRouter_callbackReplacement_preservesAcceptedPressState() {
        var firstCallbackRequests = 0
        var replacementCallbackRequests = 0
        var currentRequest: () -> Boolean = {
            firstCallbackRequests++
            true
        }
        val router = VolumeKeyRouter { currentRequest() }

        assertTrue(
            router.dispatchKeyEvent(
                keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
                action = VolumeKeyRouter.ACTION_DOWN,
                repeatCount = 0
            )
        )
        currentRequest = {
            replacementCallbackRequests++
            true
        }

        assertTrue(
            router.dispatchKeyEvent(
                keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
                action = VolumeKeyRouter.ACTION_DOWN,
                repeatCount = 1
            )
        )
        assertTrue(
            router.dispatchKeyEvent(
                keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
                action = VolumeKeyRouter.ACTION_UP,
                repeatCount = 0
            )
        )
        assertEquals(1, firstCallbackRequests)
        assertEquals(0, replacementCallbackRequests)

        assertTrue(
            router.dispatchKeyEvent(
                keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
                action = VolumeKeyRouter.ACTION_DOWN,
                repeatCount = 0
            )
        )
        assertEquals(1, replacementCallbackRequests)
    }

    @Test
    fun volumeKeyRouter_twoVolumeKeyInterleaving_consumesBothPressesWithoutSecondRequest() {
        var shutterRequests = 0
        val router = VolumeKeyRouter {
            shutterRequests++
            true
        }

        assertTrue(
            router.dispatchKeyEvent(
                keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
                action = VolumeKeyRouter.ACTION_DOWN,
                repeatCount = 0
            )
        )
        assertTrue(
            router.dispatchKeyEvent(
                keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_DOWN,
                action = VolumeKeyRouter.ACTION_DOWN,
                repeatCount = 0
            )
        )
        assertTrue(
            router.dispatchKeyEvent(
                keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_DOWN,
                action = VolumeKeyRouter.ACTION_DOWN,
                repeatCount = 1
            )
        )
        assertTrue(
            router.dispatchKeyEvent(
                keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_DOWN,
                action = VolumeKeyRouter.ACTION_UP,
                repeatCount = 0
            )
        )
        assertTrue(
            router.dispatchKeyEvent(
                keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_UP,
                action = VolumeKeyRouter.ACTION_UP,
                repeatCount = 0
            )
        )
        assertEquals(1, shutterRequests)

        assertTrue(
            router.dispatchKeyEvent(
                keyCode = VolumeKeyRouter.KEY_CODE_VOLUME_DOWN,
                action = VolumeKeyRouter.ACTION_DOWN,
                repeatCount = 0
            )
        )
        assertEquals(2, shutterRequests)
    }

    @Test
    fun volumeKeyRouter_withUnrelatedKeys_alwaysFallsThrough() {
        val router = VolumeKeyRouter { true }

        assertFalse(
            router.dispatchKeyEvent(keyCode = 27, action = VolumeKeyRouter.ACTION_DOWN, repeatCount = 0)
        )
        assertFalse(
            router.dispatchKeyEvent(keyCode = 164, action = VolumeKeyRouter.ACTION_DOWN, repeatCount = 0)
        )
        assertFalse(
            router.dispatchKeyEvent(keyCode = 4, action = VolumeKeyRouter.ACTION_UP, repeatCount = 0)
        )
    }
}
