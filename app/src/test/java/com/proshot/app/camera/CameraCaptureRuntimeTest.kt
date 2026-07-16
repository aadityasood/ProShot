package com.proshot.app.camera

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

        val result = runtime.capture(isDebug = false, tracker = null) {
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

        runtime.capture(isDebug = false, tracker = null) {
            CaptureResult.Success("Saved")
        }

        assertEquals(1, preview.events.count { it == "rebind" })
        assertTrue(runtime.previewReady.value)
    }

    @Test
    fun capture_mappedFailureRebindsExactlyOnce() = runBlocking {
        val preview = FakePreviewPort()
        val runtime = readyRuntime(preview)

        val result = runtime.capture(isDebug = false, tracker = null) {
            CaptureResult.Failure("Save failed")
        }

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

        val result = runtime.capture(isDebug = false, tracker = null) {
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
            runtime.capture(isDebug = false, tracker = null) {
                captureStarted.complete(Unit)
                neverCompletes.await()
            }
        }
        captureStarted.await()
        cancelledCapture.cancelAndJoin()

        val laterResult = runtime.capture(isDebug = false, tracker = null) {
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
            runtime.capture(isDebug = false, tracker = null) {
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
            runtime.capture(isDebug = false, tracker = null) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()
        val eventsBeforeBusyRequest = preview.events.toList()

        val busy = runtime.capture(isDebug = false, tracker = null) {
            losingCaptureCalls += 1
            CaptureResult.Success("Must not run")
        }

        assertTrue(busy is CaptureResult.Failure)
        assertEquals(
            "Camera is busy. Please try again.",
            (busy as CaptureResult.Failure).message
        )
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
            val result = runtime.capture(isDebug = false, tracker = null) {
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
        val preview = FakePreviewPort().apply {
            attachRelease = CompletableDeferred()
        }
        val runtime = CameraCaptureRuntimeCore(preview)
        var captureCalls = 0

        val attaching = async { runtime.attach(TestAttachment) }
        preview.attachStarted.await()

        val result = runtime.capture(isDebug = false, tracker = null) {
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
        val preview = FakePreviewPort().apply {
            attachRelease = CompletableDeferred()
        }
        val runtime = CameraCaptureRuntimeCore(preview)

        val attaching = async { runtime.attach(TestAttachment) }
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
        val runtime = CameraCaptureRuntimeCore(preview)
        val first = runtime.attach(TestAttachment) as PreviewAttachOutcome.Ready
        val second = runtime.attach(TestAttachment) as PreviewAttachOutcome.Ready
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

        val result = runtime.capture(isDebug = false, tracker = null) {
            CaptureResult.Success("Saved to gallery")
        }

        assertTrue(result is CaptureResult.Success)
        assertTrue((result as CaptureResult.Success).message.startsWith("Saved to gallery"))
        assertTrue(result.message.contains("preview could not restart"))
        assertFalse(runtime.previewReady.value)
    }

    private suspend fun readyRuntime(preview: FakePreviewPort): CameraCaptureRuntimeCore {
        val runtime = CameraCaptureRuntimeCore(preview)
        assertTrue(runtime.attach(TestAttachment) is PreviewAttachOutcome.Ready)
        preview.events.clear()
        return runtime
    }

    private object TestAttachment : PreviewAttachment

    private class FakePreviewPort : PreviewLifecyclePort {
        val events = mutableListOf<String>()
        val invalidatedGenerations = mutableSetOf<Long>()
        val attachStarted = CompletableDeferred<Long>()
        var attachRelease: CompletableDeferred<Unit>? = null
        var unbindFailure: Throwable? = null
        var rebindFailure: Throwable? = null

        override fun invalidate(generation: Long) {
            invalidatedGenerations += generation
        }

        override suspend fun attach(generation: Long, attachment: PreviewAttachment) {
            events += "attach:$generation"
            attachStarted.complete(generation)
            attachRelease?.await()
            if (invalidatedGenerations.contains(generation)) {
                throw IllegalStateException("Stale attachment")
            }
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
