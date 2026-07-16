package com.proshot.app.camera

import android.content.Context
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.proshot.app.processing.style.LookProfile
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "CameraCaptureRuntime"

internal interface PreviewAttachment

internal data class AndroidPreviewAttachment(
    val lifecycleOwner: LifecycleOwner,
    val previewView: PreviewView
) : PreviewAttachment

internal interface PreviewLifecyclePort {
    fun invalidate(generation: Long)

    suspend fun attach(generation: Long, attachment: PreviewAttachment)

    suspend fun unbind(generation: Long)

    suspend fun rebind(generation: Long)

    suspend fun detach(generation: Long)
}

internal fun interface CameraRuntimeErrorReporter {
    fun report(message: String, error: Throwable)
}

internal sealed interface CameraRuntimeState {
    val generation: Long

    data class Detached(override val generation: Long) : CameraRuntimeState

    data class Attaching(override val generation: Long) : CameraRuntimeState

    data class Ready(override val generation: Long) : CameraRuntimeState

    data class Capturing(override val generation: Long) : CameraRuntimeState

    data class Rebinding(override val generation: Long) : CameraRuntimeState

    data class Detaching(override val generation: Long) : CameraRuntimeState

    data class PreviewUnavailable(override val generation: Long) : CameraRuntimeState
}

internal sealed interface PreviewAttachOutcome {
    data class Ready(val generation: Long) : PreviewAttachOutcome

    object Superseded : PreviewAttachOutcome
}

private data class AttachmentTransition(
    val generation: Long,
    val previousGeneration: Long?,
    val previousAttachJob: Job?,
    val previousCaptureJob: Job?
)

private data class DetachTransition(
    val generation: Long,
    val attachJob: Job?,
    val captureJob: Job?
)

/**
 * Pure coroutine state machine for configuration-retained, activity-owned preview
 * and capture ownership.
 *
 * Physical preview work is delegated through [PreviewLifecyclePort], allowing JVM
 * tests to exercise ordering and cancellation without CameraX or Android views.
 * This core must remain free of direct Android framework calls; Android integration,
 * including production logging, belongs in the outer [CameraCaptureRuntime] wrapper
 * or an injected port.
 */
internal class CameraCaptureRuntimeCore(
    private val previewPort: PreviewLifecyclePort,
    private val errorReporter: CameraRuntimeErrorReporter =
        CameraRuntimeErrorReporter { _, _ -> }
) {
    private val stateMutex = Mutex()
    private val captureMutex = Mutex()
    private val previewTransitionMutex = Mutex()
    private val mutablePreviewReady = MutableStateFlow(false)

    private var generationCounter = 0L
    private var state: CameraRuntimeState = CameraRuntimeState.Detached(generation = 0L)
    private var activeAttachJob: Job? = null
    private var activeCaptureJob: Job? = null

    val previewReady: StateFlow<Boolean> = mutablePreviewReady.asStateFlow()

    suspend fun attach(attachment: PreviewAttachment): PreviewAttachOutcome {
        val callerJob = currentCoroutineContext()[Job]
        val transition = stateMutex.withLock {
            generationCounter += 1L
            val previousState = state
            val nextGeneration = generationCounter
            val previousGeneration = previousState
                .takeUnless { it is CameraRuntimeState.Detached }
                ?.generation
            state = CameraRuntimeState.Attaching(nextGeneration)
            previousGeneration?.let(previewPort::invalidate)
            val previousAttachJob = activeAttachJob
            activeAttachJob = callerJob
            mutablePreviewReady.value = false
            AttachmentTransition(
                generation = nextGeneration,
                previousGeneration = previousGeneration,
                previousAttachJob = previousAttachJob,
                previousCaptureJob = activeCaptureJob
            )
        }

        try {
            settlePreviousAttachment(transition)
            previewPort.attach(transition.generation, attachment)
        } catch (error: Throwable) {
            val wasCurrent = stateMutex.withLock {
                val current = state
                if (current is CameraRuntimeState.Attaching &&
                    current.generation == transition.generation
                ) {
                    state = CameraRuntimeState.Detached(transition.generation)
                    previewPort.invalidate(transition.generation)
                    if (activeAttachJob === callerJob) {
                        activeAttachJob = null
                    }
                    mutablePreviewReady.value = false
                    true
                } else {
                    false
                }
            }
            withContext(NonCancellable) {
                try {
                    previewPort.detach(transition.generation)
                } catch (detachError: Throwable) {
                    errorReporter.report(
                        "Failed to clean up rejected preview attachment",
                        detachError
                    )
                }
            }
            if (error is CancellationException) {
                throw error
            }
            if (!wasCurrent) {
                return PreviewAttachOutcome.Superseded
            }
            throw error
        }

        val becameReady = stateMutex.withLock {
            val current = state
            if (current is CameraRuntimeState.Attaching &&
                current.generation == transition.generation
            ) {
                state = CameraRuntimeState.Ready(transition.generation)
                if (activeAttachJob === callerJob) {
                    activeAttachJob = null
                }
                mutablePreviewReady.value = true
                true
            } else {
                false
            }
        }
        if (!becameReady) {
            previewPort.invalidate(transition.generation)
            withContext(NonCancellable) {
                previewPort.detach(transition.generation)
            }
            return PreviewAttachOutcome.Superseded
        }
        return PreviewAttachOutcome.Ready(transition.generation)
    }

    suspend fun detach(expectedGeneration: Long? = null) {
        val transition = stateMutex.withLock {
            val current = state
            if (current is CameraRuntimeState.Detached ||
                (expectedGeneration != null && current.generation != expectedGeneration)
            ) {
                null
            } else {
                state = CameraRuntimeState.Detaching(current.generation)
                previewPort.invalidate(current.generation)
                mutablePreviewReady.value = false
                DetachTransition(current.generation, activeAttachJob, activeCaptureJob)
            }
        } ?: return

        val callerJob = currentCoroutineContext()[Job]
        var detachFailure: Throwable? = null
        withContext(NonCancellable) {
            if (transition.attachJob !== callerJob) {
                transition.attachJob?.cancel(
                    CancellationException("Camera attachment was closed")
                )
            }
            transition.captureJob?.cancel(
                CancellationException("Camera closed before capture completed")
            )
            previewTransitionMutex.withLock {
                try {
                    previewPort.detach(transition.generation)
                } catch (error: Throwable) {
                    detachFailure = error
                }
            }
            if (transition.captureJob != null && transition.captureJob !== callerJob) {
                transition.captureJob.join()
            }
            if (transition.attachJob != null && transition.attachJob !== callerJob) {
                transition.attachJob.join()
            }
            stateMutex.withLock {
                val current = state
                if (current is CameraRuntimeState.Detaching &&
                    current.generation == transition.generation
                ) {
                    state = CameraRuntimeState.Detached(transition.generation)
                }
                if (activeCaptureJob === transition.captureJob) {
                    activeCaptureJob = null
                }
                if (activeAttachJob === transition.attachJob) {
                    activeAttachJob = null
                }
                mutablePreviewReady.value = state is CameraRuntimeState.Ready
            }
        }
        detachFailure?.let { throw it }
    }

    suspend fun capture(
        isDebug: Boolean,
        tracker: CaptureTimingTracker?,
        operation: suspend () -> CaptureResult
    ): CaptureResult {
        if (!captureMutex.tryLock()) {
            return CaptureResult.Failure("Camera is busy. Please try again.")
        }

        val callerJob = currentCoroutineContext()[Job]
        var captureGeneration: Long? = null
        var result: CaptureResult? = null
        var cancellation: CancellationException? = null
        var rebindFailure: Throwable? = null
        var pipelineStart: Long? = null

        try {
            captureGeneration = stateMutex.withLock {
                val current = state
                if (current is CameraRuntimeState.Ready) {
                    state = CameraRuntimeState.Capturing(current.generation)
                    activeCaptureJob = callerJob
                    mutablePreviewReady.value = false
                    current.generation
                } else {
                    null
                }
            }

            val generation = captureGeneration
            if (generation == null) {
                result = CaptureResult.Failure("Camera is not ready. Please wait.")
            } else {
                pipelineStart = if (isDebug) System.nanoTime() else null
                val unbindStart = if (isDebug) System.nanoTime() else null
                previewPort.unbind(generation)
                if (tracker != null && unbindStart != null) {
                    tracker.previewUnbindMs =
                        (System.nanoTime() - unbindStart) / 1_000_000L
                }

                val stillCurrent = stateMutex.withLock {
                    val current = state
                    current is CameraRuntimeState.Capturing &&
                        current.generation == generation
                }
                if (!stillCurrent) {
                    throw CancellationException("Camera closed before capture started")
                }
                currentCoroutineContext().ensureActive()
                result = operation()
            }
        } catch (error: CancellationException) {
            cancellation = error
        } catch (error: Exception) {
            errorReporter.report("Camera transaction failed", error)
            result = CaptureResult.Failure(
                message = "Camera could not take a photo. Please try again.",
                cause = error
            )
        } finally {
            val generation = captureGeneration
            if (generation != null) {
                withContext(NonCancellable) {
                    previewTransitionMutex.withLock {
                        val shouldRebind = stateMutex.withLock {
                            val current = state
                            if (current is CameraRuntimeState.Capturing &&
                                current.generation == generation
                            ) {
                                state = CameraRuntimeState.Rebinding(generation)
                                true
                            } else {
                                false
                            }
                        }
                        if (shouldRebind) {
                            val rebindStart = if (isDebug) System.nanoTime() else null
                            try {
                                previewPort.rebind(generation)
                            } catch (error: Throwable) {
                                errorReporter.report(
                                    "Failed to restore camera preview",
                                    error
                                )
                                rebindFailure = error
                            }
                            if (tracker != null && rebindStart != null) {
                                tracker.previewRebindMs =
                                    (System.nanoTime() - rebindStart) / 1_000_000L
                            }
                            stateMutex.withLock {
                                val current = state
                                if (current is CameraRuntimeState.Rebinding &&
                                    current.generation == generation
                                ) {
                                    state = if (rebindFailure == null) {
                                        CameraRuntimeState.Ready(generation)
                                    } else {
                                        CameraRuntimeState.PreviewUnavailable(generation)
                                    }
                                }
                            }
                        }
                    }
                    stateMutex.withLock {
                        if (activeCaptureJob === callerJob) {
                            activeCaptureJob = null
                        }
                        mutablePreviewReady.value = state is CameraRuntimeState.Ready
                    }
                }
            }
            if (tracker != null && pipelineStart != null) {
                tracker.totalCapturePipelineMs =
                    (System.nanoTime() - pipelineStart) / 1_000_000L
            }
            captureMutex.unlock()
        }

        cancellation?.let { throw it }
        val captureResult = result
            ?: CaptureResult.Failure("Camera could not take a photo. Please try again.")
        return withPreviewRecovery(captureResult, rebindFailure)
    }

    private suspend fun settlePreviousAttachment(transition: AttachmentTransition) {
        val callerJob = currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            if (transition.previousAttachJob !== callerJob) {
                transition.previousAttachJob?.cancel(
                    CancellationException("Camera attachment was replaced")
                )
            }
            transition.previousCaptureJob?.cancel(
                CancellationException("Camera attachment was replaced")
            )
            transition.previousGeneration?.let { previousGeneration ->
                previewTransitionMutex.withLock {
                    previewPort.detach(previousGeneration)
                }
            }
            if (transition.previousCaptureJob != null &&
                transition.previousCaptureJob !== callerJob
            ) {
                transition.previousCaptureJob.join()
            }
            if (transition.previousAttachJob != null &&
                transition.previousAttachJob !== callerJob
            ) {
                transition.previousAttachJob.join()
            }
            stateMutex.withLock {
                if (activeCaptureJob === transition.previousCaptureJob) {
                    activeCaptureJob = null
                }
                if (activeAttachJob === transition.previousAttachJob) {
                    activeAttachJob = null
                }
            }
        }
    }

    private fun withPreviewRecovery(
        captureResult: CaptureResult,
        previewFailure: Throwable?
    ): CaptureResult {
        if (previewFailure == null) {
            return captureResult
        }
        return when (captureResult) {
            is CaptureResult.Success -> CaptureResult.Success(
                "${captureResult.message}. Camera preview could not restart; reopen the camera."
            )
            is CaptureResult.Failure -> CaptureResult.Failure(
                message = "${captureResult.message} Camera preview could not restart; reopen the camera.",
                cause = captureResult.cause ?: previewFailure
            )
        }
    }
}

/**
 * Configuration-retained, activity-owned owner of CameraX preview attachment and
 * the complete one-photo unbind, Camera2 capture, processing/save, and
 * preview-rebind transaction.
 *
 * Hilt retains this owner across configuration recreation so old and replacement
 * Activity instances share one generation domain. It is not process-global and is
 * released when the logical Activity finishes.
 */
@ActivityRetainedScoped
class CameraCaptureRuntime @Inject internal constructor(
    previewController: CameraXPreviewController,
    private val captureCoordinator: CaptureCoordinator
) {
    private val core = CameraCaptureRuntimeCore(
        previewPort = previewController,
        errorReporter = CameraRuntimeErrorReporter { message, error ->
            Log.e(TAG, message, error)
        }
    )

    /** Whether the current activity attachment has a successfully bound preview. */
    val isPreviewReady: StateFlow<Boolean> = core.previewReady

    /**
     * Attaches and awaits the current activity's CameraX preview binding.
     *
     * @return the ready attachment generation, or `null` if a newer owner superseded it.
     */
    suspend fun attach(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ): Long? {
        return when (val outcome = core.attach(
            AndroidPreviewAttachment(lifecycleOwner, previewView)
        )) {
            is PreviewAttachOutcome.Ready -> outcome.generation
            PreviewAttachOutcome.Superseded -> null
        }
    }

    /**
     * Detaches the matching owner and coordinates cancellation of its active capture.
     * A stale generation cannot detach a newer preview attachment.
     */
    suspend fun detach(attachmentGeneration: Long) {
        core.detach(attachmentGeneration)
    }

    /**
     * Executes one fail-fast capture transaction for the current ready attachment.
     */
    suspend fun capture(
        context: Context,
        lookProfile: LookProfile,
        isDebug: Boolean,
        tracker: CaptureTimingTracker? = null,
        diagnosticsTracker: FocusLensDiagnosticsTracker? = null,
        focusTarget: FocusMeteringTarget = FocusMeteringTarget.center(),
        statusCallback: StatusCallback
    ): CaptureResult {
        return core.capture(isDebug = isDebug, tracker = tracker) {
            captureCoordinator.executeCapture(
                context = context,
                lookProfile = lookProfile,
                isDebug = isDebug,
                tracker = tracker,
                diagnosticsTracker = diagnosticsTracker,
                focusTarget = focusTarget,
                statusCallback = statusCallback
            )
        }
    }

    /** Resolves the physical back-camera sensor orientation off the main thread. */
    suspend fun resolveSensorOrientation(context: Context): Int {
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            captureCoordinator.resolveSensorOrientation(context)
        }
    }
}
