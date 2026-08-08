package com.proshot.app.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFocusWaitPolicyTest {

    @Test
    fun autoFocusWaitPolicy_preTriggerLockedResultsAreIgnored() {
        val policy = AutoFocusWaitPolicy(CaptureRequest.CONTROL_AF_MODE_AUTO)
        val locked = CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED

        assertEquals(null, policy.onRepeatingCompleted(locked))
        assertEquals(null, policy.onRepeatingCompleted(locked))
        assertEquals(0, policy.repeatingFrameCount)
        assertFalse(policy.triggerBoundaryObserved)
        assertEquals(null, policy.outcome)
    }

    @Test
    fun autoFocusWaitPolicy_readinessStartsAfterTriggerBoundary() {
        val policy = AutoFocusWaitPolicy(CaptureRequest.CONTROL_AF_MODE_AUTO)
        val locked = CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED

        policy.onTriggerCompleted()
        assertTrue(policy.triggerBoundaryObserved)
        assertEquals(null, policy.onRepeatingCompleted(locked))
        assertEquals(1, policy.repeatingFrameCount)
        assertEquals(AutoFocusWaitOutcome.FOCUSED, policy.onRepeatingCompleted(locked))
        assertEquals(2, policy.repeatingFrameCount)
    }

    @Test
    fun autoFocusWaitPolicy_failedRepeatingCallbackDoesNotSatisfyTriggerGate() {
        val policy = AutoFocusWaitPolicy(CaptureRequest.CONTROL_AF_MODE_AUTO)
        val locked = CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED

        policy.onTriggerCompleted()
        assertEquals(null, policy.onRepeatingFailed())
        assertEquals(1, policy.repeatingFrameCount)
        assertEquals(0, policy.qualifyingRepeatingResultCount)
        assertEquals(null, policy.onRepeatingCompleted(locked))
        assertEquals(1, policy.qualifyingRepeatingResultCount)
        assertEquals(AutoFocusWaitOutcome.FOCUSED, policy.onRepeatingCompleted(locked))
        assertEquals(2, policy.qualifyingRepeatingResultCount)
    }

    @Test
    fun autoFocusWaitPolicy_triggerFailureCannotBecomeSuccessful() {
        val policy = AutoFocusWaitPolicy(CaptureRequest.CONTROL_AF_MODE_AUTO)

        assertEquals(AutoFocusWaitOutcome.TRIGGER_FAILED, policy.onTriggerFailed(aborted = false))
        policy.onTriggerCompleted()
        assertEquals(
            null,
            policy.onRepeatingCompleted(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)
        )
        assertEquals(AutoFocusWaitOutcome.TRIGGER_FAILED, policy.outcome)
        assertEquals(0, policy.repeatingFrameCount)
    }

    @Test
    fun autoFocusWaitPolicy_triggerAbortCannotBecomeSuccessful() {
        val policy = AutoFocusWaitPolicy(CaptureRequest.CONTROL_AF_MODE_AUTO)

        assertEquals(AutoFocusWaitOutcome.TRIGGER_ABORTED, policy.onTriggerFailed(aborted = true))
        policy.onTriggerCompleted()
        assertEquals(
            null,
            policy.onRepeatingCompleted(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)
        )
        assertEquals(AutoFocusWaitOutcome.TRIGGER_ABORTED, policy.outcome)
    }

    @Test
    fun autoFocusWaitPolicy_continuousPictureRetainsEightFrameGate() {
        val policy = AutoFocusWaitPolicy(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        val focused = CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED

        repeat(7) {
            assertEquals(null, policy.onRepeatingCompleted(focused))
        }
        assertEquals(AutoFocusWaitOutcome.FOCUSED, policy.onRepeatingCompleted(focused))
        assertEquals(8, policy.repeatingFrameCount)
    }
}
