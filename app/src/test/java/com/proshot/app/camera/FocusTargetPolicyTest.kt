package com.proshot.app.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusTargetPolicyTest {

    @Test
    fun resolveEffectiveFocusTargetPolicy_userTapWithoutAfRegionsUsesDefaultCenterStrategy() {
        val policy = resolveEffectiveFocusTargetPolicy(
            requestedSource = FocusTargetSource.USER_TAP,
            maxAfRegions = 0,
            activeArrayAvailable = true
        )

        assertEquals(FocusTargetSource.USER_TAP, policy.requestedSource)
        assertEquals(FocusTargetSource.DEFAULT_CENTER, policy.effectiveSource)
        assertEquals(FocusTargetFallbackReason.AF_REGIONS_UNSUPPORTED, policy.fallbackReason)
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            SingleFrameCaptureController.selectAutoFocusModeForStillCapture(
                intArrayOf(
                    CaptureRequest.CONTROL_AF_MODE_AUTO,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                ),
                policy.effectiveSource
            )
        )
    }

    @Test
    fun resolveEffectiveFocusTargetPolicy_nullActiveArrayHasDistinctFallback() {
        val policy = resolveEffectiveFocusTargetPolicy(
            requestedSource = FocusTargetSource.USER_TAP,
            maxAfRegions = 0,
            activeArrayAvailable = false
        )

        assertEquals(FocusTargetSource.DEFAULT_CENTER, policy.effectiveSource)
        assertEquals(FocusTargetFallbackReason.ACTIVE_ARRAY_UNAVAILABLE, policy.fallbackReason)
    }

    @Test
    fun resolveEffectiveFocusTargetPolicy_supportedUserTapRemainsUserTap() {
        val policy = resolveEffectiveFocusTargetPolicy(
            requestedSource = FocusTargetSource.USER_TAP,
            maxAfRegions = 1,
            activeArrayAvailable = true
        )

        assertEquals(FocusTargetSource.USER_TAP, policy.effectiveSource)
        assertEquals(FocusTargetFallbackReason.NONE, policy.fallbackReason)
    }
}
