package com.proshot.app.camera

import android.media.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2ImageReaderRouterTest {

    @Test
    fun initialModeIsDraining() {
        val router = Camera2ImageReaderRouter()

        assertEquals(Camera2ImageReaderRouter.Mode.DRAINING, router.currentMode)
        assertFalse(router.hasActiveCorrelator)
        assertFalse(router.hasActiveReadyGate)
    }

    @Test
    fun armCorrelator_finalDrainCompletesBeforePublishAndStaleTokenCannotDisarm() {
        val router = Camera2ImageReaderRouter()
        val first = correlator()
        var finalDrainObserved = false

        val firstToken = router.armCorrelator(first) {
            assertEquals(Camera2ImageReaderRouter.Mode.ARMING, router.currentMode)
            assertFalse(router.hasActiveCorrelator)
            finalDrainObserved = true
        }

        assertTrue(finalDrainObserved)
        assertEquals(Camera2ImageReaderRouter.Mode.CORRELATING, router.currentMode)
        assertTrue(router.hasActiveCorrelator)

        val secondToken = router.armCorrelator(correlator()) {}
        router.disarmCorrelator(firstToken)
        assertTrue(router.hasActiveCorrelator)

        router.disarmCorrelator(secondToken)
        assertFalse(router.hasActiveCorrelator)
        assertEquals(Camera2ImageReaderRouter.Mode.DRAINING, router.currentMode)
    }

    @Test
    fun closeDuringFinalDrain_preventsPublicationAndClosesCorrelator() {
        val router = Camera2ImageReaderRouter()
        var outcomeCount = 0
        val correlator = CaptureTimestampCorrelator<Image>(
            requestTag = Any(),
            timestampExtractor = { image -> image.timestamp },
            releaser = { image -> image.close() },
            onOutcome = { outcomeCount++ }
        )

        val failure = runCatching {
            router.armCorrelator(correlator) { router.close() }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(Camera2ImageReaderRouter.Mode.CLOSED, router.currentMode)
        assertFalse(router.hasActiveCorrelator)
        assertEquals(1, outcomeCount)
    }

    @Test
    fun sessionGateRegistration_ignoresActivityBeforeItsEpoch() {
        val router = Camera2ImageReaderRouter()
        router.onSessionActive()

        val gate = CameraSessionReadyGate()
        val token = router.registerSessionReadyGate(gate)
        router.onSessionReady()

        assertEquals(
            CameraSessionReadyArmResult.NO_UNMATCHED_ACTIVE_GENERATION,
            gate.arm {}
        )

        router.onSessionActive()
        router.onSessionReady()
        assertEquals(
            CameraSessionReadyArmResult.ALREADY_READY_AFTER_ACTIVITY,
            gate.arm {}
        )

        router.unregisterSessionReadyGate(token)
        assertFalse(router.hasActiveReadyGate)
    }

    @Test
    fun sessionGateReplacement_disarmsPriorGateAndKeepsNewEpochActive() {
        val router = Camera2ImageReaderRouter()
        val firstGate = CameraSessionReadyGate()
        val firstToken = router.registerSessionReadyGate(firstGate)
        val secondGate = CameraSessionReadyGate()
        val secondToken = router.registerSessionReadyGate(secondGate)

        assertTrue(secondToken.epoch > firstToken.epoch)
        assertEquals(CameraSessionReadyArmResult.DISARMED, firstGate.arm {})
        assertTrue(router.hasActiveReadyGate)

        router.unregisterSessionReadyGate(firstToken)
        assertTrue(router.hasActiveReadyGate)
        router.unregisterSessionReadyGate(secondToken)
        assertFalse(router.hasActiveReadyGate)
    }

    @Test
    fun readinessPhases_waitForWarmupReadyBeforeStartingIndependentAfPhase() {
        val router = Camera2ImageReaderRouter()
        val events = mutableListOf<String>()
        var afPhase: Camera2SessionReadyPhase? = null
        val warmupPhase = Camera2SessionReadyPhase(router)

        events += "warmup-active"
        warmupPhase.markActive()
        val warmupArm = warmupPhase.armAndStop(
            onReady = {
                events += "warmup-ready"
                warmupPhase.close()
                afPhase = Camera2SessionReadyPhase(router).also { phase ->
                    events += "af-active"
                    phase.markActive()
                }
            },
            stopRepeating = { events += "warmup-stop" }
        )

        assertEquals(CameraSessionReadyArmResult.ARMED, warmupArm)
        assertEquals(listOf("warmup-active", "warmup-stop"), events)
        assertEquals(null, afPhase)

        router.onSessionReady()

        assertEquals(
            listOf("warmup-active", "warmup-stop", "warmup-ready", "af-active"),
            events
        )
        val activeAfPhase = checkNotNull(afPhase)
        val afArm = activeAfPhase.armAndStop(
            onReady = { events += "af-ready" },
            stopRepeating = { events += "af-stop" }
        )

        assertEquals(CameraSessionReadyArmResult.ARMED, afArm)
        assertEquals("af-stop", events.last())

        router.onSessionReady()

        assertEquals("af-ready", events.last())
        activeAfPhase.close()
        assertFalse(router.hasActiveReadyGate)
    }

    @Test
    fun fixedFocus_reusesWarmupBoundaryWithoutCreatingAfReadinessPhase() {
        val router = Camera2ImageReaderRouter()
        val events = mutableListOf<String>()
        val warmupPhase = Camera2SessionReadyPhase(router)

        warmupPhase.markActive()
        assertEquals(
            CameraSessionReadyArmResult.ARMED,
            warmupPhase.armAndStop(
                onReady = { events += "warmup-ready" },
                stopRepeating = { events += "warmup-stop" }
            )
        )
        router.onSessionReady()
        warmupPhase.close()

        var afPhaseCreated = false
        if (requiresAutoFocusReadinessPhase(null)) {
            afPhaseCreated = true
            Camera2SessionReadyPhase(router).close()
        }

        assertEquals(listOf("warmup-stop", "warmup-ready"), events)
        assertFalse(afPhaseCreated)
        assertFalse(router.hasActiveReadyGate)
        assertFalse(requiresAutoFocusReadinessPhase(null))
        assertTrue(requiresAutoFocusReadinessPhase(0))
    }

    @Test
    fun drainAndCloseAll_acquiresFifoAndClosesEveryCandidate() {
        val router = Camera2ImageReaderRouter()
        val first = CloseCountingCandidate()
        val second = CloseCountingCandidate()
        val candidates = ArrayDeque(listOf(first, second))

        router.drainAndCloseAll(FrameCandidateProvider { candidates.removeFirstOrNull() })

        assertEquals(1, first.closeCount)
        assertEquals(1, second.closeCount)
        assertTrue(candidates.isEmpty())
    }

    private fun correlator(): CaptureTimestampCorrelator<Image> =
        CaptureTimestampCorrelator(
            requestTag = Any(),
            timestampExtractor = { image -> image.timestamp },
            releaser = { image -> image.close() },
            onOutcome = {}
        )

    private class CloseCountingCandidate : AutoCloseable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
        }
    }
}
