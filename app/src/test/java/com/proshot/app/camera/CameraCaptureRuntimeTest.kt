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

        val capture = async {
            runtime.capture(isDebug = false) {
                captureStarted.complete(Unit)
                neverCompletes.await()
            }
        }
        captureStarted.await()

        runtime.detach()

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

    private class FakePreviewPort : PreviewLifecyclePort {
        val events = mutableListOf<String>()
        val invalidatedGenerations = mutableSetOf<Long>()
        val attachStarted = CompletableDeferred<Long>()
        var attachRelease: CompletableDeferred<Unit>? = null
        var attachFailure: Throwable? = null
        var unbindFailure: Throwable? = null
        var rebindFailure: Throwable? = null
        var onInvalidate: (() -> Unit)? = null

        override fun invalidate(generation: Long) {
            onInvalidate?.invoke()
            invalidatedGenerations += generation
        }

        override suspend fun attach(generation: Long, attachment: PreviewAttachment) {
            events += "attach:$generation"
            attachStarted.complete(generation)
            attachRelease?.await()
            if (invalidatedGenerations.contains(generation)) {
                throw IllegalStateException("Stale attachment")
            }
            attachFailure?.let { throw it }
            events += "bound:$generation"
        }

        override suspend fun unbind(generation: Long) {
            events += "unbind"
            unbindFailure?.let { throw it }
        }

        override suspend fun rebind(generation: Long) {
            events += "rebind"
            rebindFailure?.let { throw it }
        }

        override suspend fun detach(generation: Long) {
            invalidate(generation)
            events += "detach:$generation"
        }
    }
}
