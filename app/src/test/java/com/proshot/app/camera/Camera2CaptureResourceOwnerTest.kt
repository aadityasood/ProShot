package com.proshot.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2CaptureResourceOwnerTest {

    @Test
    fun close_isIdempotentAndClosesReaderOnce() {
        val harness = Harness()

        harness.gate.close()
        harness.gate.close()

        assertEquals(1, harness.reader.closeCount)
        assertEquals(1, harness.terminalCount)
        assertEquals(0, harness.scheduler.scheduledCount)
    }

    @Test
    fun lateOpenAfterClose_closesDeviceOnceAndCannotPublishIt() {
        val harness = Harness()
        val device = FakeResource()
        harness.gate.markOpenRequested()

        harness.gate.close()
        val accepted = harness.gate.registerDevice(device)
        val acceptedAgain = harness.gate.registerDevice(device)

        assertFalse(accepted)
        assertFalse(acceptedAgain)
        assertEquals(1, device.closeCount)
        assertNull(harness.gate.getDevice())
        assertEquals(1, harness.terminalCount)
        assertTrue(harness.scheduler.latestHandle.cancelled)
    }

    @Test
    fun lateConfiguredSessionAfterClose_closesSessionOnceAndCannotPublishIt() {
        val harness = Harness()
        val device = FakeResource()
        val session = FakeResource()
        harness.gate.markOpenRequested()
        assertTrue(harness.gate.registerDevice(device))
        harness.gate.markSessionRequested()

        harness.gate.close()
        val accepted = harness.gate.registerSession(session)
        val acceptedAgain = harness.gate.registerSession(session)

        assertFalse(accepted)
        assertFalse(acceptedAgain)
        assertEquals(1, session.closeCount)
        assertNull(harness.gate.getSession())
        assertEquals(1, harness.terminalCount)
        assertTrue(harness.scheduler.latestHandle.cancelled)
    }

    @Test
    fun registerThenClose_closesAcceptedDeviceAndSessionExactlyOnce() {
        val harness = Harness()
        val device = FakeResource()
        val session = FakeResource()
        harness.gate.markOpenRequested()
        assertTrue(harness.gate.registerDevice(device))
        harness.gate.markSessionRequested()
        assertTrue(harness.gate.registerSession(session))

        harness.gate.close()
        harness.gate.registerOpenFailure(device)
        harness.gate.registerSessionFailure(session)

        assertEquals(1, device.closeCount)
        assertEquals(1, session.closeCount)
        assertEquals(1, harness.reader.closeCount)
        assertEquals(1, harness.terminalCount)
    }

    @Test
    fun deliveredOpenFailureClosesDeviceExactlyOnce() {
        val harness = Harness()
        val device = FakeResource()
        harness.gate.markOpenRequested()
        assertTrue(harness.gate.registerDevice(device))

        harness.gate.registerOpenFailure(device)
        harness.gate.registerOpenFailure(device)
        harness.gate.close()

        assertEquals(1, device.closeCount)
        assertEquals(1, harness.reader.closeCount)
        assertEquals(1, harness.terminalCount)
    }

    @Test
    fun deliveredSessionFailureClosesSessionExactlyOnce() {
        val harness = Harness()
        val device = FakeResource()
        val session = FakeResource()
        harness.gate.markOpenRequested()
        assertTrue(harness.gate.registerDevice(device))
        harness.gate.markSessionRequested()
        assertTrue(harness.gate.registerSession(session))

        harness.gate.registerSessionFailure(session)
        harness.gate.registerSessionFailure(session)
        harness.gate.close()

        assertEquals(1, session.closeCount)
        assertEquals(1, device.closeCount)
        assertEquals(1, harness.terminalCount)
    }

    @Test
    fun synchronousOpenSubmissionFailureReachesImmediateTerminalCleanup() {
        val harness = Harness()
        harness.gate.markOpenRequested()

        harness.gate.markOpenSubmissionFailed()
        harness.gate.close()

        assertEquals(1, harness.reader.closeCount)
        assertEquals(1, harness.terminalCount)
        assertEquals(0, harness.scheduler.scheduledCount)
    }

    @Test
    fun synchronousSessionSubmissionFailureReachesImmediateTerminalCleanup() {
        val harness = Harness()
        val device = FakeResource()
        harness.gate.markOpenRequested()
        assertTrue(harness.gate.registerDevice(device))
        harness.gate.markSessionRequested()

        harness.gate.markSessionSubmissionFailed()
        harness.gate.close()

        assertEquals(1, device.closeCount)
        assertEquals(1, harness.reader.closeCount)
        assertEquals(1, harness.terminalCount)
        assertEquals(0, harness.scheduler.scheduledCount)
    }

    @Test
    fun missingOpenCallbackUsesDeterministicBoundedFallback() {
        val harness = Harness()
        harness.gate.markOpenRequested()

        harness.gate.close()

        assertEquals(CAMERA2_CALLBACK_TERMINAL_GRACE_MS, harness.scheduler.latestDelayMs)
        assertEquals(0, harness.terminalCount)
        harness.scheduler.triggerLatest()
        assertEquals(1, harness.terminalCount)
        harness.scheduler.triggerLatest()
        assertEquals(1, harness.terminalCount)
    }

    @Test
    fun missingSessionCallbackUsesDeterministicBoundedFallback() {
        val harness = Harness()
        val device = FakeResource()
        harness.gate.markOpenRequested()
        assertTrue(harness.gate.registerDevice(device))
        harness.gate.markSessionRequested()

        harness.gate.close()
        harness.scheduler.triggerLatest()

        assertEquals(CAMERA2_CALLBACK_TERMINAL_GRACE_MS, harness.scheduler.latestDelayMs)
        assertEquals(1, device.closeCount)
        assertEquals(1, harness.terminalCount)
    }

    @Test
    fun terminalCallbackBeforeDeadlineCancelsScheduledFallback() {
        val harness = Harness()
        val device = FakeResource()
        harness.gate.markOpenRequested()
        harness.gate.close()

        harness.gate.registerDevice(device)

        assertEquals(1, device.closeCount)
        assertEquals(1, harness.terminalCount)
        assertTrue(harness.scheduler.latestHandle.cancelled)
        harness.scheduler.triggerLatest()
        assertEquals(1, harness.terminalCount)
    }

    private class Harness {
        val reader = FakeResource()
        val scheduler = FakeScheduler()
        var terminalCount = 0
        val gate = Camera2ResourceGate<FakeResource, FakeResource, FakeResource>(
            reader = reader,
            scheduler = scheduler,
            closeDevice = { it.closeCount += 1 },
            closeSession = { it.closeCount += 1 },
            closeReader = { it.closeCount += 1 },
            onTerminal = { terminalCount += 1 }
        )
    }

    private class FakeResource {
        var closeCount = 0
    }

    private class FakeScheduler : TerminalActionScheduler {
        private val actions = mutableListOf<() -> Unit>()
        private val handles = mutableListOf<FakeScheduledAction>()
        private val delays = mutableListOf<Long>()

        val scheduledCount: Int
            get() = actions.size

        val latestHandle: FakeScheduledAction
            get() = handles.last()

        val latestDelayMs: Long
            get() = delays.last()

        override fun schedule(
            delayMs: Long,
            action: () -> Unit
        ): ScheduledTerminalAction {
            val handle = FakeScheduledAction()
            delays += delayMs
            actions += action
            handles += handle
            return handle
        }

        fun triggerLatest() {
            val index = actions.lastIndex
            if (index >= 0 && !handles[index].cancelled) {
                actions[index].invoke()
            }
        }
    }

    private class FakeScheduledAction : ScheduledTerminalAction {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }
}
