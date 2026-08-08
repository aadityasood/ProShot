package com.proshot.app.camera

import android.hardware.camera2.CaptureRequest

internal const val AF_LOCK_MAX_FRAMES = 30

internal enum class AutoFocusWaitOutcome {
    FOCUSED,
    FRAME_CAP_TIMEOUT,
    TRIGGER_FAILED,
    TRIGGER_ABORTED,
    TRIGGER_SUBMISSION_FAILED
}

internal class AutoFocusWaitPolicy(private val afMode: Int) {
    var triggerBoundaryObserved: Boolean = afMode != CaptureRequest.CONTROL_AF_MODE_AUTO
        private set
    var repeatingFrameCount: Int = 0
        private set
    var qualifyingRepeatingResultCount: Int = 0
        private set
    var outcome: AutoFocusWaitOutcome? = null
        private set

    fun onTriggerCompleted() {
        if (outcome == null && afMode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
            triggerBoundaryObserved = true
        }
    }

    fun onTriggerFailed(aborted: Boolean): AutoFocusWaitOutcome? {
        if (outcome != null || triggerBoundaryObserved) {
            return null
        }
        outcome = if (aborted) {
            AutoFocusWaitOutcome.TRIGGER_ABORTED
        } else {
            AutoFocusWaitOutcome.TRIGGER_FAILED
        }
        return outcome
    }

    fun onRepeatingCompleted(afState: Int?): AutoFocusWaitOutcome? {
        if (outcome != null || !triggerBoundaryObserved) {
            return null
        }
        repeatingFrameCount++
        qualifyingRepeatingResultCount++
        outcome = when {
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                qualifyingRepeatingResultCount,
                afState,
                afMode
            ) -> AutoFocusWaitOutcome.FOCUSED
            repeatingFrameCount >= AF_LOCK_MAX_FRAMES -> AutoFocusWaitOutcome.FRAME_CAP_TIMEOUT
            else -> null
        }
        return outcome
    }

    fun onRepeatingFailed(): AutoFocusWaitOutcome? {
        if (outcome != null || !triggerBoundaryObserved) {
            return null
        }
        repeatingFrameCount++
        if (repeatingFrameCount >= AF_LOCK_MAX_FRAMES) {
            outcome = AutoFocusWaitOutcome.FRAME_CAP_TIMEOUT
        }
        return outcome
    }
}
