package com.proshot.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSessionReadyGateTest {

    @Test
    fun initialReadyCannotSatisfyLaterActiveGenerationArm() {
        val gate = CameraSessionReadyGate()
        var completionCount = 0

        gate.onReady()
        gate.onActive()

        assertEquals(
            CameraSessionReadyArmResult.ARMED,
            gate.arm { completionCount++ }
        )
        assertEquals(0, completionCount)

        gate.onReady()

        assertEquals(1, completionCount)
    }

    @Test
    fun activeArmReadyInvokesExactlyOnce() {
        val gate = CameraSessionReadyGate()
        var completionCount = 0

        gate.onActive()
        assertEquals(
            CameraSessionReadyArmResult.ARMED,
            gate.arm { completionCount++ }
        )

        gate.onReady()

        assertEquals(1, completionCount)
    }

    @Test
    fun priorActiveReadyCyclesCannotSatisfyNewerActiveGeneration() {
        val gate = CameraSessionReadyGate()
        var completionCount = 0

        gate.onActive()
        gate.onReady()
        gate.onActive()

        assertEquals(
            CameraSessionReadyArmResult.ARMED,
            gate.arm { completionCount++ }
        )
        assertEquals(0, completionCount)

        gate.onReady()

        assertEquals(1, completionCount)
    }

    @Test
    fun repeatedReadyCallbacksCannotDoubleInvoke() {
        val gate = CameraSessionReadyGate()
        var completionCount = 0

        gate.onActive()
        assertEquals(
            CameraSessionReadyArmResult.ARMED,
            gate.arm { completionCount++ }
        )

        gate.onReady()
        gate.onReady()
        gate.onReady()

        assertEquals(1, completionCount)
    }

    @Test
    fun disarmIsIdempotentAndBlocksLateReadyCompletion() {
        val gate = CameraSessionReadyGate()
        var completionCount = 0

        gate.onActive()
        assertEquals(
            CameraSessionReadyArmResult.ARMED,
            gate.arm { completionCount++ }
        )

        gate.disarm()
        gate.disarm()
        gate.onReady()

        assertEquals(0, completionCount)
        assertEquals(
            CameraSessionReadyArmResult.DISARMED,
            gate.arm { completionCount++ }
        )
    }

    @Test
    fun neverActiveAndInitialReadyOnlyFailWithoutUnmatchedActivity() {
        val neverActive = CameraSessionReadyGate()
        assertEquals(
            CameraSessionReadyArmResult.NO_UNMATCHED_ACTIVE_GENERATION,
            neverActive.arm {}
        )

        val initialReadyOnly = CameraSessionReadyGate()
        initialReadyOnly.onReady()
        assertEquals(
            CameraSessionReadyArmResult.NO_UNMATCHED_ACTIVE_GENERATION,
            initialReadyOnly.arm {}
        )
    }

    @Test
    fun completedActivityReturnsAlreadyReadyWithoutConsumingLaterArm() {
        val gate = CameraSessionReadyGate()
        var completedStateCallbackCount = 0
        var laterActiveCallbackCount = 0

        gate.onActive()
        gate.onReady()

        assertEquals(
            CameraSessionReadyArmResult.ALREADY_READY_AFTER_ACTIVITY,
            gate.arm { completedStateCallbackCount++ }
        )
        gate.onReady()
        assertEquals(0, completedStateCallbackCount)

        gate.onActive()
        assertEquals(
            CameraSessionReadyArmResult.ARMED,
            gate.arm { laterActiveCallbackCount++ }
        )
        assertEquals(0, laterActiveCallbackCount)

        gate.onReady()

        assertEquals(1, laterActiveCallbackCount)
    }

    @Test
    fun callbackCanSafelyReenterGateAfterStateIsReleased() {
        val gate = CameraSessionReadyGate()
        var completionCount = 0
        var rearmResult: CameraSessionReadyArmResult? = null

        gate.onActive()
        assertEquals(
            CameraSessionReadyArmResult.ARMED,
            gate.arm {
                completionCount++
                gate.onActive()
                rearmResult = gate.arm { completionCount++ }
                gate.disarm()
            }
        )

        gate.onReady()

        assertEquals(1, completionCount)
        assertEquals(CameraSessionReadyArmResult.ALREADY_ARMED, rearmResult)
    }

    @Test
    fun secondSimultaneousArmCannotReplaceAcceptedCallback() {
        val gate = CameraSessionReadyGate()
        var firstCompletionCount = 0
        var replacementCompletionCount = 0

        gate.onActive()
        assertEquals(
            CameraSessionReadyArmResult.ARMED,
            gate.arm { firstCompletionCount++ }
        )
        assertEquals(
            CameraSessionReadyArmResult.ALREADY_ARMED,
            gate.arm { replacementCompletionCount++ }
        )

        gate.onReady()

        assertEquals(1, firstCompletionCount)
        assertEquals(0, replacementCompletionCount)
        assertEquals(
            CameraSessionReadyArmResult.ALREADY_ARMED,
            gate.arm { replacementCompletionCount++ }
        )
    }
}
