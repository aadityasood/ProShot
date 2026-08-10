package com.proshot.app.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentCamera2ResourceOwnerTest {

    @Test
    fun closeCoordinator_clearsListenerBeforeRouterGateAndSurfaceExactlyOnce() {
        val events = mutableListOf<String>()
        val coordinator = PersistentOwnerCloseCoordinator(
            clearImageListener = { events += "listener" },
            closeRouter = { events += "router" },
            closeGate = { events += "gate" },
            releasePreviewSurface = { events += "surface" }
        )

        coordinator.close()
        coordinator.close()

        assertEquals(listOf("listener", "router", "gate", "surface"), events)
    }

    @Test
    fun closeCoordinator_continuesRemainingStepsAfterOneFailure() {
        val events = mutableListOf<String>()
        val coordinator = PersistentOwnerCloseCoordinator(
            clearImageListener = { events += "listener" },
            closeRouter = {
                events += "router"
                throw IllegalStateException("router close failed")
            },
            closeGate = { events += "gate" },
            releasePreviewSurface = { events += "surface" }
        )

        val failures = coordinator.close()

        assertEquals(listOf("listener", "router", "gate", "surface"), events)
        assertEquals(1, failures.size)
        assertEquals("router close failed", failures.single().message)
    }

    @Test
    fun resourceGate_deviceRegistrationBeforeClose_isAccepted() {
        var deviceClosed = false
        var terminalSignalled = false

        val gate = Camera2ResourceGate<Any, Any, Any>(
            reader = Any(),
            scheduler = ImmediateTerminalScheduler,
            closeDevice = { deviceClosed = true },
            closeSession = {},
            closeReader = {},
            onTerminal = { terminalSignalled = true }
        )

        gate.markOpenRequested()
        val device = Any()
        val accepted = gate.registerDevice(device)

        assertTrue(accepted)
        assertEquals(device, gate.getDevice())
        assertFalse(deviceClosed)

        gate.close()

        assertTrue(deviceClosed)
        assertTrue(terminalSignalled)
    }

    @Test
    fun resourceGate_deviceRegistrationAfterClose_isRejectedAndClosed() {
        var deviceClosed = false
        var terminalSignalled = false

        val gate = Camera2ResourceGate<Any, Any, Any>(
            reader = Any(),
            scheduler = ImmediateTerminalScheduler,
            closeDevice = { deviceClosed = true },
            closeSession = {},
            closeReader = {},
            onTerminal = { terminalSignalled = true }
        )

        gate.markOpenRequested()
        gate.close()

        val lateDevice = Any()
        val accepted = gate.registerDevice(lateDevice)

        assertFalse(accepted)
        assertTrue(deviceClosed)
        assertTrue(terminalSignalled)
    }

    @Test
    fun resourceGate_closeIsIdempotent() {
        var closeCount = 0

        val gate = Camera2ResourceGate<Any, Any, Any>(
            reader = Any(),
            scheduler = ImmediateTerminalScheduler,
            closeDevice = { closeCount++ },
            closeSession = {},
            closeReader = {},
            onTerminal = {}
        )

        gate.markOpenRequested()
        val device = Any()
        gate.registerDevice(device)

        gate.close()
        assertEquals(1, closeCount)

        gate.close()
        assertEquals(1, closeCount)
    }

    @Test
    fun submissionCloseGate_waitsForEnteredCallRejectsLaterAndSeparatesPhysicalClose() {
        val gate = Camera2SubmissionCloseGate()
        val submissionEntered = CountDownLatch(1)
        val allowSubmissionExit = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val submissionsDrained = CountDownLatch(1)
        val physicalCloseRan = CountDownLatch(1)
        val insideSubmission = AtomicBoolean(false)
        val physicalCloseOverlapped = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val submission = executor.submit {
                gate.withOpenSubmission {
                    insideSubmission.set(true)
                    submissionEntered.countDown()
                    allowSubmissionExit.await()
                    insideSubmission.set(false)
                }
            }
            submissionEntered.await()

            val close = executor.submit<Boolean> {
                val firstClose = gate.closeAndAwaitSubmissions(
                    onCloseStarted = { closeStarted.countDown() },
                    onSubmissionsDrained = { submissionsDrained.countDown() }
                )
                physicalCloseOverlapped.set(insideSubmission.get())
                physicalCloseRan.countDown()
                firstClose
            }
            closeStarted.await()

            val rejectedFailure = runCatching {
                gate.withOpenSubmission { "must not run" }
            }.exceptionOrNull()

            assertTrue(rejectedFailure is PersistentCamera2OwnerClosedException)
            assertEquals(1L, submissionsDrained.count)
            assertEquals(1L, physicalCloseRan.count)

            allowSubmissionExit.countDown()
            submission.get()

            assertTrue(close.get())
            assertEquals(0L, submissionsDrained.count)
            assertEquals(0L, physicalCloseRan.count)
            assertFalse(physicalCloseOverlapped.get())
            assertFalse(gate.closeAndAwaitSubmissions())
        } finally {
            allowSubmissionExit.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun deviceCloseBarrier_exactAcceptedDeviceAcknowledgementTerminatesOnce() {
        val scheduler = RecordingTerminalScheduler()
        var terminationCount = 0
        val barrier = PersistentDeviceCloseBarrier<Any>(scheduler, 1_000L) {
            terminationCount++
        }
        val acceptedDevice = Any()

        barrier.recordDeliveredDevice(acceptedDevice)
        barrier.onGateTerminal()
        barrier.registerDeviceClosed(Any())

        assertEquals(0, terminationCount)
        assertEquals(1, scheduler.scheduleCount)

        barrier.registerDeviceClosed(acceptedDevice)
        barrier.registerDeviceClosed(acceptedDevice)
        scheduler.fire()

        assertEquals(1, terminationCount)
        assertEquals(1, scheduler.cancelCount)
    }

    @Test
    fun deviceCloseBarrier_scheduledTimeoutTerminatesExactlyOnce() {
        val scheduler = RecordingTerminalScheduler()
        var terminationCount = 0
        val barrier = PersistentDeviceCloseBarrier<Any>(scheduler, 1_000L) {
            terminationCount++
        }

        barrier.recordDeliveredDevice(Any())
        barrier.onGateTerminal()
        scheduler.fire()
        scheduler.fire()
        barrier.forceTerminate()

        assertEquals(1, scheduler.scheduleCount)
        assertEquals(1, scheduler.cancelCount)
        assertEquals(1, terminationCount)
    }

    private object ImmediateTerminalScheduler : TerminalActionScheduler {
        override fun schedule(delayMs: Long, action: () -> Unit): ScheduledTerminalAction {
            action()
            return ScheduledTerminalAction {}
        }
    }

    private class RecordingTerminalScheduler : TerminalActionScheduler {
        var scheduleCount = 0
            private set
        var cancelCount = 0
            private set
        private var action: (() -> Unit)? = null

        override fun schedule(delayMs: Long, action: () -> Unit): ScheduledTerminalAction {
            scheduleCount++
            this.action = action
            return ScheduledTerminalAction { cancelCount++ }
        }

        fun fire() {
            checkNotNull(action).invoke()
        }
    }
}
