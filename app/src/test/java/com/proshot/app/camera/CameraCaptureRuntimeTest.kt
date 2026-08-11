package com.proshot.app.camera

import android.content.Context
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCaptureRuntimeTest {

    @Test
    fun capture_ordersUnbindBeforeCaptureAndRebindAfterCompletion() = runBlocking {
        val preview = FakePreviewPort()
        val runtime = readyRuntime(preview)

        val result = runtime.capture(isDebug = false) {
            preview.events += "capture"
            CaptureResult.Success("Saved")
        }

        assertTrue(result is CaptureResult.Success)
        assertEquals(listOf("unbind", "capture", "rebind"), preview.events)
    }

    @Test
    fun capture_successRebindsExactlyOnce() = runBlocking {
        val preview = FakePreviewPort()
        val runtime = readyRuntime(preview)

        runtime.capture(isDebug = false) { CaptureResult.Success("Saved") }

        assertEquals(1, preview.events.count { it == "rebind" })
        assertTrue(runtime.previewReady.value)
    }

    @Test
    fun capture_mappedFailureRebindsExactlyOnce() = runBlocking {
        val preview = FakePreviewPort()
        val runtime = readyRuntime(preview)

        val result = runtime.capture(isDebug = false) { CaptureResult.Failure("Save failed") }

        assertTrue(result is CaptureResult.Failure)
        assertEquals("Save failed", (result as CaptureResult.Failure).message)
        assertEquals(1, preview.events.count { it == "rebind" })
        assertTrue(runtime.previewReady.value)
    }

    @Test
    fun unbindFailureAbortsCaptureAndAttemptsOnePreviewRecovery() = runBlocking {
        val preview = FakePreviewPort().apply {
            unbindFailure = IllegalStateException("Unbind failed")
        }
        val runtime = readyRuntime(preview)
        var captureCalls = 0

        val result = runtime.capture(isDebug = false) {
            captureCalls += 1
            CaptureResult.Success("Must not run")
        }

        assertTrue(result is CaptureResult.Failure)
        assertEquals(0, captureCalls)
        assertEquals(1, preview.events.count { it == "unbind" })
        assertEquals(1, preview.events.count { it == "rebind" })
        assertTrue(runtime.previewReady.value)
    }

    @Test
    fun cancellationReleasesCaptureLockAndLaterCaptureSucceeds() = runBlocking {
        val preview = FakePreviewPort()
        val runtime = readyRuntime(preview)
        val captureStarted = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<CaptureResult>()

        val cancelledCapture = async {
            runtime.capture(isDebug = false) {
                captureStarted.complete(Unit)
                neverCompletes.await()
            }
        }
        captureStarted.await()
        cancelledCapture.cancelAndJoin()

        val laterResult = runtime.capture(isDebug = false) {
            CaptureResult.Success("Later capture")
        }

        assertTrue(laterResult is CaptureResult.Success)
        assertEquals("Later capture", (laterResult as CaptureResult.Success).message)
        assertEquals(2, preview.events.count { it == "rebind" })
    }

    @Test
    fun detachDuringCaptureCancelsWorkAndPreventsRebind() = runBlocking {
        val preview = FakePreviewPort()
        val runtime = readyRuntime(preview)
        val captureStarted = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<CaptureResult>()
        var cancellationObservedAtInvalidation = false

        val capture = async {
            runtime.capture(isDebug = false) {
                captureStarted.complete(Unit)
                neverCompletes.await()
            }
        }
        captureStarted.await()
        preview.onInvalidate = {
            cancellationObservedAtInvalidation = capture.isCancelled
        }

        runtime.detach()

        assertTrue(cancellationObservedAtInvalidation)
        assertTrue(capture.isCancelled)
        assertEquals(0, preview.events.count { it == "rebind" })
        assertEquals(1, preview.events.count { it.startsWith("detach:") })
        assertFalse(runtime.previewReady.value)
    }

    @Test
    fun concurrentRequestReturnsBusyWithoutAnyLosingSideEffect() = runBlocking {
        val preview = FakePreviewPort()
        val runtime = readyRuntime(preview)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<CaptureResult>()
        var losingCaptureCalls = 0

        val first = async {
            runtime.capture(isDebug = false) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()
        val eventsBeforeBusyRequest = preview.events.toList()

        val busy = runtime.capture(isDebug = false) {
            losingCaptureCalls += 1
            CaptureResult.Success("Must not run")
        }

        assertTrue(busy is CaptureResult.Failure)
        assertEquals("Camera is busy. Please try again.", (busy as CaptureResult.Failure).message)
        assertEquals(0, losingCaptureCalls)
        assertEquals(eventsBeforeBusyRequest, preview.events)

        releaseFirst.complete(CaptureResult.Success("First"))
        assertTrue(first.await() is CaptureResult.Success)
    }

    @Test
    fun requestAfterCompletedTransactionSucceeds() = runBlocking {
        val preview = FakePreviewPort()
        val runtime = readyRuntime(preview)
        var captureCalls = 0

        repeat(2) {
            val result = runtime.capture(isDebug = false) {
                captureCalls += 1
                CaptureResult.Success("Saved")
            }
            assertTrue(result is CaptureResult.Success)
        }

        assertEquals(2, captureCalls)
        assertEquals(2, preview.events.count { it == "unbind" })
        assertEquals(2, preview.events.count { it == "rebind" })
    }

    @Test
    fun captureBeforeAttachIsReadyHasNoPreviewOrCaptureSideEffect() = runBlocking {
        val preview = FakePreviewPort().apply { attachRelease = CompletableDeferred() }
        val runtime = CameraCaptureRuntimeCore()
        var captureCalls = 0

        val attaching = async { attach(runtime, preview) }
        preview.attachStarted.await()

        val result = runtime.capture(isDebug = false) {
            captureCalls += 1
            CaptureResult.Success("Must not run")
        }

        assertTrue(result is CaptureResult.Failure)
        assertEquals("Camera is not ready. Please wait.", (result as CaptureResult.Failure).message)
        assertEquals(0, captureCalls)
        assertEquals(0, preview.events.count { it == "unbind" })
        assertFalse(runtime.previewReady.value)

        preview.attachRelease?.complete(Unit)
        assertTrue(attaching.await() is PreviewAttachOutcome.Ready)
    }

    @Test
    fun detachDuringPendingAttachPreventsStaleBinding() = runBlocking {
        val preview = FakePreviewPort().apply { attachRelease = CompletableDeferred() }
        val runtime = CameraCaptureRuntimeCore()

        val attaching = async { attach(runtime, preview) }
        val pendingGeneration = preview.attachStarted.await()

        runtime.detach()

        assertTrue(attaching.isCancelled)
        assertTrue(preview.invalidatedGenerations.contains(pendingGeneration))
        assertEquals(0, preview.events.count { it.startsWith("bound:") })
        assertFalse(runtime.previewReady.value)
    }

    @Test
    fun generationReservation_precedesAttachmentConstructionAndPortAttach() = runBlocking {
        val trace = mutableListOf<String>()
        val preview = FakePreviewPort(sharedTrace = trace)
        val runtime = CameraCaptureRuntimeCore()

        val outcome = runtime.attach(
            route = CameraOwnershipRoute.PERSISTENT_CAMERA2,
            previewPort = preview,
            frameSource = TestFrameSource,
            attachmentFactory = { generation ->
                trace += "factory:$generation"
                TestAttachment
            },
            onGenerationReserved = { generation ->
                trace += "reserved:$generation"
            }
        ) as PreviewAttachOutcome.Ready

        assertEquals(
            listOf(
                "reserved:${outcome.generation}",
                "factory:${outcome.generation}",
                "attach:${outcome.generation}",
                "bound:${outcome.generation}"
            ),
            trace
        )
    }

    @Test
    fun pendingAttachSurfaceInvalidation_cancelsAttachBeforePortInvalidation() = runBlocking {
        val preview = FakePreviewPort().apply {
            attachRelease = CompletableDeferred()
        }
        val runtime = CameraCaptureRuntimeCore()
        val reservedGeneration = CompletableDeferred<Long>()
        lateinit var attaching: kotlinx.coroutines.Deferred<PreviewAttachOutcome>
        var cancellationObservedAtInvalidation = false
        preview.onInvalidate = {
            cancellationObservedAtInvalidation = attaching.isCancelled
        }

        attaching = async {
            runtime.attach(
                route = CameraOwnershipRoute.PERSISTENT_CAMERA2,
                previewPort = preview,
                frameSource = TestFrameSource,
                attachmentFactory = { TestAttachment },
                onGenerationReserved = { generation ->
                    reservedGeneration.complete(generation)
                }
            )
        }
        val generation = reservedGeneration.await()
        preview.attachStarted.await()

        runtime.detachSync(generation)
        attaching.join()

        assertTrue(cancellationObservedAtInvalidation)
        assertTrue(attaching.isCancelled)
        assertTrue(preview.invalidatedGenerations.contains(generation))
        assertFalse(runtime.previewReady.value)
    }

    @Test
    fun replacementAttach_cancelsPendingOldAttachBeforeOldPortInvalidation() = runBlocking {
        val oldPreview = FakePreviewPort().apply {
            attachRelease = CompletableDeferred()
        }
        val newPreview = FakePreviewPort()
        val runtime = CameraCaptureRuntimeCore()
        lateinit var oldAttach: kotlinx.coroutines.Deferred<PreviewAttachOutcome>
        var cancellationObservedAtInvalidation = false
        oldPreview.onInvalidate = {
            cancellationObservedAtInvalidation = oldAttach.isCancelled
        }

        oldAttach = async {
            attach(runtime, oldPreview, CameraOwnershipRoute.PERSISTENT_CAMERA2)
        }
        oldPreview.attachStarted.await()

        val replacement = attach(
            runtime,
            newPreview,
            CameraOwnershipRoute.CAMERA_X_HANDOFF
        )
        oldAttach.join()

        assertTrue(cancellationObservedAtInvalidation)
        assertTrue(oldAttach.isCancelled)
        assertTrue(replacement is PreviewAttachOutcome.Ready)
    }

    @Test
    fun staleDetachGenerationCannotClearNewerAttachment() = runBlocking {
        val preview = FakePreviewPort()
        val runtime = CameraCaptureRuntimeCore()
        val first = attach(runtime, preview) as PreviewAttachOutcome.Ready
        val second = attach(runtime, preview) as PreviewAttachOutcome.Ready
        val eventsAfterReplacementReady = preview.events.toList()
        val invalidationsAfterReplacementReady = preview.invalidatedGenerations.toSet()

        runtime.detach(first.generation)

        assertTrue(second.generation > first.generation)
        assertTrue(runtime.previewReady.value)
        assertEquals(eventsAfterReplacementReady, preview.events)
        assertEquals(invalidationsAfterReplacementReady, preview.invalidatedGenerations)
        assertFalse(preview.invalidatedGenerations.contains(second.generation))
    }

    @Test
    fun savedResultRemainsSuccessWhenPreviewRecoveryFails() = runBlocking {
        val preview = FakePreviewPort().apply {
            rebindFailure = IllegalStateException("Bind failed")
        }
        val runtime = readyRuntime(preview)

        val result = runtime.capture(isDebug = false) {
            CaptureResult.Success("Saved to gallery")
        }

        assertTrue(result is CaptureResult.Success)
        assertTrue((result as CaptureResult.Success).message.startsWith("Saved to gallery"))
        assertTrue(result.message.contains("preview could not restart"))
        assertFalse(runtime.previewReady.value)
    }

    @Test
    fun persistentRouteCapturesWithoutUnbindOrRebindAndReturnsReady() = runBlocking {
        val preview = FakePreviewPort()
        val runtime = readyRuntime(preview, CameraOwnershipRoute.PERSISTENT_CAMERA2)

        val result = runtime.capture(isDebug = false) {
            preview.events += "capture"
            CaptureResult.Success("Saved")
        }

        assertTrue(result is CaptureResult.Success)
        assertEquals(listOf("capture"), preview.events)
        assertTrue(runtime.previewReady.value)
    }

    @Test
    fun routeReplacementDetachesOldOwnerBeforeBindingNewOwner() = runBlocking {
        val preview = FakePreviewPort()
        val runtime = CameraCaptureRuntimeCore()
        val first = attach(runtime, preview, CameraOwnershipRoute.CAMERA_X_HANDOFF)
            as PreviewAttachOutcome.Ready

        val second = attach(runtime, preview, CameraOwnershipRoute.PERSISTENT_CAMERA2)
            as PreviewAttachOutcome.Ready

        assertTrue(second.generation > first.generation)
        assertTrue(
            preview.events.indexOf("detach:${first.generation}") <
                preview.events.indexOf("attach:${second.generation}")
        )
        assertTrue(runtime.previewReady.value)
    }

    @Test
    fun terminalPortFailureInvalidatesGenerationAndCancelsActiveCapture() = runBlocking {
        val reports = mutableListOf<String>()
        val preview = FakePreviewPort()
        val runtime = CameraCaptureRuntimeCore(
            CameraRuntimeErrorReporter { message, _ -> reports += message }
        )
        val ready = attach(runtime, preview, CameraOwnershipRoute.PERSISTENT_CAMERA2)
            as PreviewAttachOutcome.Ready
        val captureStarted = CompletableDeferred<Unit>()
        val capture = async {
            runtime.capture(isDebug = false) {
                captureStarted.complete(Unit)
                CompletableDeferred<CaptureResult>().await()
            }
        }
        captureStarted.await()

        runtime.onPortTerminated(ready.generation, IllegalStateException("disconnect"))
        capture.join()

        assertTrue(capture.isCancelled)
        assertFalse(runtime.previewReady.value)
        assertEquals(listOf("Camera preview owner terminated"), reports)
    }

    @Test
    fun capture_terminalInvalidationImmediatelyBeforeSuccess_neverReturnsStoredSuccess() =
        runBlocking {
            val preview = FakePreviewPort()
            val runtime = CameraCaptureRuntimeCore()
            val ready = attach(
                runtime,
                preview,
                CameraOwnershipRoute.PERSISTENT_CAMERA2
            ) as PreviewAttachOutcome.Ready

            val capture = async {
                runtime.capture(isDebug = false) {
                    runtime.onPortTerminated(
                        ready.generation,
                        IllegalStateException("terminal before return")
                    )
                    CaptureResult.Success("Must not escape")
                }
            }
            capture.join()

            assertTrue(capture.isCancelled)
            val returned = runCatching { capture.await() }.getOrNull()
            assertFalse(returned is CaptureResult.Success)
        }

    @Test
    fun directTerminal_lifecycleInvalidationFirst_reportsEffectiveLifecycleWithoutFallback() {
        val failure = DirectCamera2Failure(
            kind = DirectCamera2FailureKind.CAMERA_DEVICE_OR_OPEN,
            cause = IllegalStateException("disconnect")
        )
        val latch = RetainedCameraXFallbackLatch()
        val events = mutableListOf<String>()

        handleDirectTerminalFailure(
            generation = 4L,
            failure = failure,
            isGenerationSynchronouslyInvalidated = { true },
            onPortTerminated = { _, _ ->
                events += "terminated"
                true
            },
            latchFallback = {
                latch.latchIfEligible(it)
                events += "latched"
            },
            notifyUi = { events += "ui:${it.kind}" }
        )

        assertEquals(listOf("ui:LIFECYCLE_OR_SUPERSESSION"), events)
        assertFalse(latch.mandatory.value)
    }

    @Test
    fun directTerminal_terminalFirst_latchesBeforeDeliveringRealEligibleFailure() {
        val failure = DirectCamera2Failure(
            kind = DirectCamera2FailureKind.CAMERA_SESSION,
            cause = IllegalStateException("session")
        )
        val latch = RetainedCameraXFallbackLatch()
        val events = mutableListOf<String>()

        handleDirectTerminalFailure(
            generation = 5L,
            failure = failure,
            isGenerationSynchronouslyInvalidated = { false },
            onPortTerminated = { _, _ ->
                events += "terminated"
                true
            },
            latchFallback = {
                latch.latchIfEligible(it)
                events += "latched:${latch.mandatory.value}"
            },
            notifyUi = { events += "ui:${it.kind}:${latch.mandatory.value}" }
        )

        assertEquals(
            listOf(
                "terminated",
                "latched:true",
                "ui:CAMERA_SESSION:true"
            ),
            events
        )
    }

    @Test
    fun directTerminal_lifecycleWinsAfterQueryButBeforeTerminalClaim() {
        val latch = RetainedCameraXFallbackLatch()
        val events = mutableListOf<String>()

        handleDirectTerminalFailure(
            generation = 6L,
            failure = DirectCamera2Failure(
                kind = DirectCamera2FailureKind.CAMERA_DEVICE_OR_OPEN,
                cause = IllegalStateException("disconnect")
            ),
            isGenerationSynchronouslyInvalidated = { false },
            onPortTerminated = { _, _ -> false },
            latchFallback = {
                latch.latchIfEligible(it)
                events += "latched"
            },
            notifyUi = { events += "ui:${it.kind}" }
        )

        assertEquals(listOf("ui:LIFECYCLE_OR_SUPERSESSION"), events)
        assertFalse(latch.mandatory.value)
    }

    @Test
    fun retainedFallbackLatch_isMonotonicAndIgnoresIneligibleFailures() {
        val latch = RetainedCameraXFallbackLatch()
        val ineligible = DirectCamera2Failure(
            kind = DirectCamera2FailureKind.OWNER_TERMINAL_BARRIER,
            cause = IllegalStateException("barrier")
        )
        val eligible = DirectCamera2Failure(
            kind = DirectCamera2FailureKind.TERMINAL_CAPTURE_OR_REPEATING,
            cause = IllegalStateException("repeating")
        )

        latch.latchIfEligible(ineligible)
        assertFalse(latch.mandatory.value)

        latch.latchIfEligible(eligible)
        assertTrue(latch.mandatory.value)

        latch.latchIfEligible(ineligible)
        assertTrue(latch.mandatory.value)
    }

    @Test
    fun detachSync_cancelsCaptureBeforePhysicalPortInvalidation() = runBlocking {
        val preview = FakePreviewPort()
        val runtime = CameraCaptureRuntimeCore()
        val ready = attach(runtime, preview, CameraOwnershipRoute.PERSISTENT_CAMERA2)
            as PreviewAttachOutcome.Ready
        val captureStarted = CompletableDeferred<Unit>()
        lateinit var capture: kotlinx.coroutines.Deferred<CaptureResult>
        var cancellationObservedAtInvalidation = false
        preview.onInvalidate = {
            cancellationObservedAtInvalidation = capture.isCancelled
        }
        capture = async {
            runtime.capture(isDebug = false) {
                captureStarted.complete(Unit)
                CompletableDeferred<CaptureResult>().await()
            }
        }
        captureStarted.await()

        runtime.detachSync(ready.generation)
        capture.join()

        assertTrue(cancellationObservedAtInvalidation)
        assertTrue(capture.isCancelled)
        assertFalse(runtime.previewReady.value)
    }

    @Test
    fun exactLifecycleInvalidation_rejectsLaterTerminalClaimAsStale() = runBlocking {
        val reports = mutableListOf<String>()
        val preview = FakePreviewPort()
        val runtime = CameraCaptureRuntimeCore(
            CameraRuntimeErrorReporter { message, _ -> reports += message }
        )
        val ready = attach(
            runtime,
            preview,
            CameraOwnershipRoute.PERSISTENT_CAMERA2
        ) as PreviewAttachOutcome.Ready

        runtime.detachSync(ready.generation)
        val terminalAccepted = runtime.onPortTerminated(
            ready.generation,
            IllegalStateException("late disconnect")
        )

        assertTrue(runtime.isGenerationSynchronouslyInvalidated(ready.generation))
        assertFalse(terminalAccepted)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun ownerCloseBoundary_releasesControllerStateLockBeforeCloseCallback() {
        val stateLock = ReentrantLock()
        val closingOwners = mutableSetOf<String>()
        var callbackReenteredState = false

        beginOwnerCloseOutsideStateLock(
            stateLock = stateLock,
            closingOwners = closingOwners,
            owner = "owner",
            closeOwner = { owner ->
                assertFalse(stateLock.isHeldByCurrentThread)
                stateLock.withLock {
                    callbackReenteredState = closingOwners.contains(owner)
                }
            }
        )

        assertTrue(callbackReenteredState)
        assertEquals(setOf("owner"), closingOwners)
    }

    @Test
    fun attachFailureCleansGenerationAndPreservesExactNotReadyMessage() = runBlocking {
        val preview = FakePreviewPort().apply {
            attachFailure = IllegalStateException("Hardware error")
        }
        val runtime = CameraCaptureRuntimeCore()

        val error = runCatching { attach(runtime, preview) }.exceptionOrNull()

        assertEquals("Hardware error", error?.message)
        assertFalse(runtime.previewReady.value)
        assertTrue(preview.events.any { it.startsWith("detach:") })
        val result = runtime.capture(isDebug = false) { CaptureResult.Success("Must not run") }
        assertEquals(
            "Camera is not ready. Please wait.",
            (result as CaptureResult.Failure).message
        )
    }

    @Test
    fun retainedTerminalRecord_withHostDetachSync_settlesOldPortBeforeNewAttach() = runBlocking {
        val sharedTrace = mutableListOf<String>()
        val oldPort = FakePreviewPort(name = "oldPort", sharedTrace = sharedTrace)
        val newPort = FakePreviewPort(name = "newPort", sharedTrace = sharedTrace)
        val runtime = CameraCaptureRuntimeCore()

        val ready = attach(runtime, oldPort, CameraOwnershipRoute.PERSISTENT_CAMERA2)
            as PreviewAttachOutcome.Ready

        runtime.onPortTerminated(ready.generation, IllegalStateException("Direct error"))
        runtime.detachSync(ready.generation)

        val secondReady = attach(runtime, newPort, CameraOwnershipRoute.CAMERA_X_HANDOFF)
            as PreviewAttachOutcome.Ready

        assertTrue(secondReady.generation > ready.generation)
        assertTrue(oldPort.events.contains("detach:${ready.generation}"))
        assertTrue(
            "Old port detach must precede replacement port attach in shared trace",
            sharedTrace.indexOf("oldPort:detach:${ready.generation}") <
                sharedTrace.indexOf("newPort:attach:${secondReady.generation}")
        )
    }

    @Test
    fun detachSync_followedByDetach_completesSuspendingDetachBeforeLaterAttach() = runBlocking {
        val sharedTrace = mutableListOf<String>()
        val oldPort = FakePreviewPort(name = "oldPort", sharedTrace = sharedTrace)
        val newPort = FakePreviewPort(name = "newPort", sharedTrace = sharedTrace)
        val runtime = CameraCaptureRuntimeCore()

        val ready = attach(runtime, oldPort, CameraOwnershipRoute.PERSISTENT_CAMERA2)
            as PreviewAttachOutcome.Ready

        runtime.detachSync(ready.generation)
        runtime.detach(ready.generation)

        assertTrue(
            "Suspending detach must complete old port detach before constructing/attaching replacement",
            oldPort.events.contains("detach:${ready.generation}")
        )
        val oldDetachCountAfterSuspendingDetach = oldPort.events.count { it == "detach:${ready.generation}" }
        assertTrue(oldDetachCountAfterSuspendingDetach > 0)

        val secondReady = attach(runtime, newPort, CameraOwnershipRoute.CAMERA_X_HANDOFF)
            as PreviewAttachOutcome.Ready

        assertTrue(secondReady.generation > ready.generation)
        assertEquals(
            "Replacement attach must not cause a second detach on the already-settled old port",
            oldDetachCountAfterSuspendingDetach,
            oldPort.events.count { it == "detach:${ready.generation}" }
        )
        assertTrue(
            "Old port detach must precede replacement port attach in shared trace",
            sharedTrace.indexOf("oldPort:detach:${ready.generation}") <
                sharedTrace.indexOf("newPort:attach:${secondReady.generation}")
        )
    }

    @Test
    fun retainedTerminalRecord_withHostDetachSync_barrierFailurePreventsNewAttach() = runBlocking {
        val oldPort = FakePreviewPort().apply {
            detachFailure = IllegalStateException("Barrier failure")
        }
        val newPort1 = FakePreviewPort()
        val newPort2 = FakePreviewPort()
        val runtime = CameraCaptureRuntimeCore()

        val ready = attach(runtime, oldPort, CameraOwnershipRoute.PERSISTENT_CAMERA2)
            as PreviewAttachOutcome.Ready

        runtime.onPortTerminated(ready.generation, IllegalStateException("Direct error"))
        runtime.detachSync(ready.generation)

        val error1 = runCatching {
            attach(runtime, newPort1, CameraOwnershipRoute.CAMERA_X_HANDOFF)
        }.exceptionOrNull()

        assertEquals("Barrier failure", error1?.message)
        assertEquals(0, newPort1.events.count { it.startsWith("attach:") })
        assertFalse(runtime.previewReady.value)

        val error2 = runCatching {
            attach(runtime, newPort2, CameraOwnershipRoute.CAMERA_X_HANDOFF)
        }.exceptionOrNull()

        assertEquals("Barrier failure", error2?.message)
        assertEquals(0, newPort2.events.count { it.startsWith("attach:") })
        assertFalse(runtime.previewReady.value)
        assertTrue("Old port detach must be attempted again on retry", oldPort.events.count { it == "detach:${ready.generation}" } >= 2)
    }

    @Test
    fun detachSync_isIdempotentForAlreadyInvalidatedGeneration() = runBlocking {
        val preview = FakePreviewPort()
        val runtime = CameraCaptureRuntimeCore()
        val ready = attach(runtime, preview, CameraOwnershipRoute.PERSISTENT_CAMERA2)
            as PreviewAttachOutcome.Ready

        runtime.detachSync(ready.generation)
        val initialInvalidations = preview.invalidatedGenerations.size

        runtime.detachSync(ready.generation)
        assertEquals(initialInvalidations, preview.invalidatedGenerations.size)
    }

    private data class IdentityToken(val id: String)

    @Test
    fun prepareTerminalCloseAction_structurallyEqualButDistinctIdentity_returnsNullWithoutSideEffects() {
        val lock = ReentrantLock()
        val closingOwners = mutableSetOf<String>()
        val events = mutableListOf<String>()
        val owner = "owner1"
        val failure = DirectCamera2Failure(
            kind = DirectCamera2FailureKind.CAMERA_SESSION,
            cause = IllegalStateException("Session failure")
        )

        val currentIdentity = IdentityToken("record1")
        val staleDistinctIdentity = IdentityToken("record1")

        val action = prepareTerminalCloseAction(
            lock = lock,
            closingOwners = closingOwners,
            owner = owner,
            currentIdentity = currentIdentity,
            expectedIdentity = staleDistinctIdentity,
            failure = failure,
            onTerminal = { events += "callback" },
            closeOwner = { events += "close" },
            clearState = { events += "clearState" }
        )

        org.junit.Assert.assertNull("Structurally equal but distinct identity must return null", action)
        assertTrue("State clear must not be called", events.isEmpty())
        assertFalse("Owner must not be added to closingOwners", closingOwners.contains(owner))
    }

    @Test
    fun prepareTerminalCloseAction_publishesOwner_runsOutsideLock_andPreventsDuplicate() {
        val lock = ReentrantLock()
        val closingOwners = mutableSetOf<String>()
        val events = mutableListOf<String>()
        val owner = "owner1"
        val identity = IdentityToken("record1")
        val failure = DirectCamera2Failure(
            kind = DirectCamera2FailureKind.CAMERA_SESSION,
            cause = IllegalStateException("Session failure")
        )

        var isOwnerPublishedDuringCallback = false

        val action1 = prepareTerminalCloseAction(
            lock = lock,
            closingOwners = closingOwners,
            owner = owner,
            currentIdentity = identity,
            expectedIdentity = identity,
            failure = failure,
            onTerminal = {
                assertFalse("Callback must run outside lock", lock.isHeldByCurrentThread)
                isOwnerPublishedDuringCallback = closingOwners.contains(owner)
                events += "callback:${it.kind}"
            },
            closeOwner = {
                assertFalse("Close must run outside lock", lock.isHeldByCurrentThread)
                events += "close:$it"
            },
            clearState = { events += "clearState" }
        )

        org.junit.Assert.assertNotNull("First preparation must succeed", action1)
        assertEquals(listOf("clearState"), events)

        action1?.execute()
        assertTrue("Owner must be published in closingOwners during callback", isOwnerPublishedDuringCallback)
        assertEquals(listOf("clearState", "callback:CAMERA_SESSION", "close:owner1"), events)

        var secondClearStateCalled = false
        val action2 = prepareTerminalCloseAction(
            lock = lock,
            closingOwners = closingOwners,
            owner = owner,
            currentIdentity = identity,
            expectedIdentity = identity,
            failure = failure,
            onTerminal = { events += "secondCallback" },
            closeOwner = { events += "secondClose" },
            clearState = { secondClearStateCalled = true }
        )

        org.junit.Assert.assertNull("Second preparation for published owner must return null", action2)
        assertFalse("Second preparation must not call clearState", secondClearStateCalled)
        assertEquals(listOf("clearState", "callback:CAMERA_SESSION", "close:owner1"), events)
    }

    @Test
    fun prepareTerminalCloseAction_callbackExceptionStillClosesOwnerInFinally() {
        val lock = ReentrantLock()
        val closingOwners = mutableSetOf<String>()
        val events = mutableListOf<String>()

        val action = prepareTerminalCloseAction(
            lock = lock,
            closingOwners = closingOwners,
            owner = "owner1",
            currentIdentity = "record1",
            expectedIdentity = "record1",
            failure = DirectCamera2Failure(
                kind = DirectCamera2FailureKind.CAMERA_DEVICE_OR_OPEN,
                cause = IllegalStateException("Open failure")
            ),
            onTerminal = {
                events += "callback"
                throw IllegalStateException("Callback exception")
            },
            closeOwner = { events += "close" },
            clearState = {}
        )

        val failure = runCatching { action?.execute() }.exceptionOrNull()
        assertEquals("Callback exception", failure?.message)
        assertEquals(listOf("callback", "close"), events)
    }

    @Test
    fun terminalFailureCallback_ordersPublicationCloseRegistrationAndCompletion() {
        val events = mutableListOf<String>()
        val action = DirectTerminalCloseAction(
            owner = "owner",
            failure = DirectCamera2Failure(
                kind = DirectCamera2FailureKind.CAMERA_DEVICE_OR_OPEN,
                cause = IllegalStateException("disconnect")
            ),
            onTerminal = { events += "publish" },
            closeOwner = { events += "close" }
        )

        executeTerminalFailureCallback(
            publishTerminal = action::execute,
            registerFailureResource = { events += "register" },
            completeContinuation = { events += "complete" }
        )

        assertEquals(listOf("publish", "close", "register", "complete"), events)
    }

    @Test
    fun terminalFailureCallback_notificationFailureStillClosesRegistersAndCompletes() {
        val events = mutableListOf<String>()
        val callbackFailure = IllegalStateException("notification")
        val action = DirectTerminalCloseAction(
            owner = "owner",
            failure = DirectCamera2Failure(
                kind = DirectCamera2FailureKind.CAMERA_SESSION,
                cause = IllegalStateException("session")
            ),
            onTerminal = {
                events += "publish"
                throw callbackFailure
            },
            closeOwner = { events += "close" }
        )

        val failure = runCatching {
            executeTerminalFailureCallback(
                publishTerminal = action::execute,
                registerFailureResource = { events += "register" },
                completeContinuation = { events += "complete" }
            )
        }.exceptionOrNull()

        assertTrue(failure === callbackFailure)
        assertEquals(listOf("publish", "close", "register", "complete"), events)
    }

    private suspend fun readyRuntime(
        preview: FakePreviewPort,
        route: CameraOwnershipRoute = CameraOwnershipRoute.CAMERA_X_HANDOFF
    ): CameraCaptureRuntimeCore {
        val runtime = CameraCaptureRuntimeCore()
        assertTrue(attach(runtime, preview, route) is PreviewAttachOutcome.Ready)
        preview.events.clear()
        return runtime
    }

    private suspend fun attach(
        runtime: CameraCaptureRuntimeCore,
        preview: FakePreviewPort,
        route: CameraOwnershipRoute = CameraOwnershipRoute.CAMERA_X_HANDOFF
    ): PreviewAttachOutcome = runtime.attach(
        route = route,
        previewPort = preview,
        frameSource = TestFrameSource,
        attachment = TestAttachment
    )

    private object TestAttachment : PreviewAttachment

    private object TestFrameSource : CameraFrameSource {
        override suspend fun captureFrame(
            context: Context,
            tracker: CaptureTimingTracker?,
            diagnosticsTracker: FocusLensDiagnosticsTracker?,
            focusTarget: FocusMeteringTarget
        ): CopiedImageFrame = throw UnsupportedOperationException("Pure runtime test")

        override fun resolveOutputRotationDegrees(context: Context): Int = 0

        override fun resolveSensorOrientation(context: Context): Int = 90
    }

    private class FakePreviewPort(
        val name: String = "",
        val sharedTrace: MutableList<String>? = null
    ) : PreviewLifecyclePort {
        val events = mutableListOf<String>()
        val invalidatedGenerations = mutableSetOf<Long>()
        val attachStarted = CompletableDeferred<Long>()
        var attachRelease: CompletableDeferred<Unit>? = null
        var attachFailure: Throwable? = null
        var unbindFailure: Throwable? = null
        var rebindFailure: Throwable? = null
        var detachFailure: Throwable? = null
        var onInvalidate: (() -> Unit)? = null

        private fun recordEvent(event: String) {
            events += event
            if (sharedTrace != null) {
                sharedTrace += if (name.isNotEmpty()) "$name:$event" else event
            }
        }

        override fun invalidate(generation: Long) {
            onInvalidate?.invoke()
            invalidatedGenerations += generation
        }

        override suspend fun attach(generation: Long, attachment: PreviewAttachment) {
            recordEvent("attach:$generation")
            attachStarted.complete(generation)
            attachRelease?.await()
            if (invalidatedGenerations.contains(generation)) {
                throw IllegalStateException("Stale attachment")
            }
            attachFailure?.let { throw it }
            recordEvent("bound:$generation")
        }

        override suspend fun unbind(generation: Long) {
            recordEvent("unbind")
            unbindFailure?.let { throw it }
        }

        override suspend fun rebind(generation: Long) {
            recordEvent("rebind")
            rebindFailure?.let { throw it }
        }

        override suspend fun detach(generation: Long) {
            invalidate(generation)
            recordEvent("detach:$generation")
            detachFailure?.let { throw it }
        }
    }
}
