package com.proshot.app.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests verifying the correctness of pre-capture AE/AF gates and convergence policies.
 */
class CapturePreCapturePolicyTest {

    @Test
    fun isAeReady_respectsMinimumGateFrameCount() {
        val converged = CaptureResult.CONTROL_AE_STATE_CONVERGED
        
        // Under the gate of 3 frames, AE should not be ready even if converged
        assertFalse(CapturePreCapturePolicy.isAeReady(0, converged))
        assertFalse(CapturePreCapturePolicy.isAeReady(1, converged))
        assertFalse(CapturePreCapturePolicy.isAeReady(2, converged))
        
        // At or above 3 frames, AE is ready
        assertTrue(CapturePreCapturePolicy.isAeReady(3, converged))
        assertTrue(CapturePreCapturePolicy.isAeReady(4, converged))
    }

    @Test
    fun isAeReady_acceptsValidStatesOnly() {
        val gateMet = 3
        
        // Converged, locked, flash required, and null are all ready
        assertTrue(CapturePreCapturePolicy.isAeReady(gateMet, CaptureResult.CONTROL_AE_STATE_CONVERGED))
        assertTrue(CapturePreCapturePolicy.isAeReady(gateMet, CaptureResult.CONTROL_AE_STATE_LOCKED))
        assertTrue(CapturePreCapturePolicy.isAeReady(gateMet, CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED))
        assertTrue(CapturePreCapturePolicy.isAeReady(gateMet, null))
        
        // Searching, inactive, pre-capture are NOT ready
        assertFalse(CapturePreCapturePolicy.isAeReady(gateMet, CaptureResult.CONTROL_AE_STATE_SEARCHING))
        assertFalse(CapturePreCapturePolicy.isAeReady(gateMet, CaptureResult.CONTROL_AE_STATE_INACTIVE))
    }

    @Test
    fun isAfReady_inAutoMode_respectsGateAndStateRules() {
        val autoMode = CaptureRequest.CONTROL_AF_MODE_AUTO
        val locked = CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        
        // Gated: Under 3 frames, not ready even if locked
        assertFalse(CapturePreCapturePolicy.isAfReady(0, locked, autoMode))
        assertFalse(CapturePreCapturePolicy.isAfReady(1, locked, autoMode))
        assertFalse(CapturePreCapturePolicy.isAfReady(2, locked, autoMode))
        
        // At or above gate (3), ready if locked
        assertTrue(CapturePreCapturePolicy.isAfReady(3, locked, autoMode))
        assertTrue(CapturePreCapturePolicy.isAfReady(4, locked, autoMode))
        
        // Null AF state is NOT ready in AUTO mode; null means the AF state machine
        // has not yet initialized or the trigger has not been processed.
        assertFalse(CapturePreCapturePolicy.isAfReady(3, null, autoMode))
        assertFalse(CapturePreCapturePolicy.isAfReady(10, null, autoMode))
        assertFalse(CapturePreCapturePolicy.isAfReady(29, null, autoMode))
        
        // Auto mode must NOT accept PASSIVE_FOCUSED, PASSIVE_UNFOCUSED, or NOT_FOCUSED_LOCKED
        assertFalse(CapturePreCapturePolicy.isAfReady(3, CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED, autoMode))
        assertFalse(CapturePreCapturePolicy.isAfReady(3, CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, autoMode))
        assertFalse(CapturePreCapturePolicy.isAfReady(3, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, autoMode))
    }

    @Test
    fun isAfReady_inContinuousPictureMode_acceptsPassiveFocusedAndFocusedLocked() {
        val continuousMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        val passiveFocused = CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
        val focusedLocked = CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        
        // Gated: Under 3 frames, not ready even if passive focused
        assertFalse(CapturePreCapturePolicy.isAfReady(0, passiveFocused, continuousMode))
        assertFalse(CapturePreCapturePolicy.isAfReady(1, passiveFocused, continuousMode))
        assertFalse(CapturePreCapturePolicy.isAfReady(2, passiveFocused, continuousMode))
        
        // At or above gate (3), ready if passive focused
        assertTrue(CapturePreCapturePolicy.isAfReady(3, passiveFocused, continuousMode))
        
        // FOCUSED_LOCKED is a terminal converged state, accepted in all AF modes
        assertTrue(CapturePreCapturePolicy.isAfReady(3, focusedLocked, continuousMode))
        
        // Null AF state is NOT ready in CONTINUOUS_PICTURE mode
        assertFalse(CapturePreCapturePolicy.isAfReady(3, null, continuousMode))
        assertFalse(CapturePreCapturePolicy.isAfReady(10, null, continuousMode))
        
        // Continuous mode must NOT accept PASSIVE_UNFOCUSED or other non-focused states
        assertFalse(CapturePreCapturePolicy.isAfReady(3, CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, continuousMode))
        assertFalse(CapturePreCapturePolicy.isAfReady(3, CaptureResult.CONTROL_AF_STATE_INACTIVE, continuousMode))
    }

    @Test
    fun isAfReady_withFixedFocus_returnsTrueImmediately() {
        // Gated count doesn't apply to fixed-focus because no AF check is done
        assertTrue(CapturePreCapturePolicy.isAfReady(0, null, null))
        assertTrue(CapturePreCapturePolicy.isAfReady(1, null, null))
        assertTrue(CapturePreCapturePolicy.isAfReady(2, null, null))
    }

    @Test
    fun isAfReady_nullAfStateNeverReadyInActiveAfModes() {
        // Regression guard: null AF state must never be accepted as ready when an
        // active AF mode is set. This prevents premature pre-capture exit on
        // Qualcomm HALs where null appears during trigger processing.
        val autoMode = CaptureRequest.CONTROL_AF_MODE_AUTO
        val continuousMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        
        // Test at various frame counts including well past the gate
        for (frame in listOf(0, 1, 2, 3, 5, 10, 20, 29)) {
            assertFalse(
                "Null AF state must not be ready in AUTO mode at frame $frame",
                CapturePreCapturePolicy.isAfReady(frame, null, autoMode)
            )
            assertFalse(
                "Null AF state must not be ready in CONTINUOUS_PICTURE mode at frame $frame",
                CapturePreCapturePolicy.isAfReady(frame, null, continuousMode)
            )
        }
    }

    @Test
    fun isPreCaptureFinished_evaluatesReadinessAndFrameCaps() {
        val cap = CapturePreCapturePolicy.MAX_PRE_CAPTURE_FRAMES // 30
        
        // Finished if both ready
        assertTrue(CapturePreCapturePolicy.isPreCaptureFinished(5, aeReady = true, afReady = true))
        
        // NOT finished if only one is ready before cap
        assertFalse(CapturePreCapturePolicy.isPreCaptureFinished(5, aeReady = true, afReady = false))
        assertFalse(CapturePreCapturePolicy.isPreCaptureFinished(5, aeReady = false, afReady = true))
        
        // Finished at or above cap, even if neither is ready
        assertTrue(CapturePreCapturePolicy.isPreCaptureFinished(cap, aeReady = false, afReady = false))
        assertTrue(CapturePreCapturePolicy.isPreCaptureFinished(cap + 1, aeReady = false, afReady = false))
    }

    @Test
    fun isPreCaptureFinished_atFrameCapWithPartialReadiness() {
        val cap = CapturePreCapturePolicy.MAX_PRE_CAPTURE_FRAMES // 30
        
        // AE ready but AF never resolved; frame cap fires anyway
        assertTrue(CapturePreCapturePolicy.isPreCaptureFinished(cap, aeReady = true, afReady = false))
        
        // AF ready but AE never resolved; frame cap fires anyway
        assertTrue(CapturePreCapturePolicy.isPreCaptureFinished(cap, aeReady = false, afReady = true))
    }
}
