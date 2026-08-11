package com.proshot.app.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface PreviewAttachment

internal interface PreviewLifecyclePort {
    fun invalidate(generation: Long)

    suspend fun attach(generation: Long, attachment: PreviewAttachment)

    suspend fun unbind(generation: Long)

    suspend fun rebind(generation: Long)

    suspend fun detach(generation: Long)
}

internal data class GenerationRouteRecord(
    val generation: Long,
    val route: CameraOwnershipRoute,
    val previewPort: PreviewLifecyclePort,
    val frameSource: CameraFrameSource
)

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
    val record: GenerationRouteRecord,
    val previousRecord: GenerationRouteRecord?,
    val previousAttachJob: Job?,
    val previousCaptureJob: Job?
)

private data class DetachTransition(
    val record: GenerationRouteRecord,
    val attachJob: Job?,
    val captureJob: Job?
)

/**
 * Pure coroutine state machine for one retained preview/capture generation domain.
 *
 * The selected route, preview port, and frame source are immutable members of a
 * generation record. Physical Android work stays behind the supplied ports.
 */
internal class CameraCaptureRuntimeCore(
    private val errorReporter: CameraRuntimeErrorReporter =
        CameraRuntimeErrorReporter { _, _ -> }
) {
    private val stateLock = Any()
    private val captureMutex = Mutex()
    private val previewTransitionMutex = Mutex()
    private val mutablePreviewReady = kotlinx.coroutines.flow.MutableStateFlow(false)

    private var generationCounter = 0L
    private var state: CameraRuntimeState = CameraRuntimeState.Detached(generation = 0L)
    private var activeRecord: GenerationRouteRecord? = null
    private var activeAttachJob: Job? = null
    private var activeCaptureJob: Job? = null

    @Volatile
    private var synchronouslyInvalidatedThroughGeneration = 0L

    @Volatile
    private var synchronouslyVisibleRecord: GenerationRouteRecord? = null

    val previewReady: StateFlow<Boolean> = mutablePreviewReady

    suspend fun attach(
        route: CameraOwnershipRoute,
        previewPort: PreviewLifecyclePort,
        frameSource: CameraFrameSource,
        attachment: PreviewAttachment
    ): PreviewAttachOutcome = attach(
        route = route,
        previewPort = previewPort,
        frameSource = frameSource,
        attachmentFactory = { attachment }
    )

    suspend fun attach(
        route: CameraOwnershipRoute,
        previewPort: PreviewLifecyclePort,
        frameSource: CameraFrameSource,
        attachmentFactory: (Long) -> PreviewAttachment,
        onGenerationReserved: (Long) -> Unit = {}
    ): PreviewAttachOutcome {
        val callerJob = currentCoroutineContext()[Job]
        val transition = synchronized(stateLock) {
            generationCounter += 1L
            val record = GenerationRouteRecord(
                generation = generationCounter,
                route = route,
                previewPort = previewPort,
                frameSource = frameSource
            )
            val previousRecord = activeRecord
            val previousAttachJob = activeAttachJob
            val previousCaptureJob = activeCaptureJob
            state = CameraRuntimeState.Attaching(record.generation)
            activeRecord = record
            activeAttachJob = callerJob
            markSynchronouslyInvalidatedThrough(record.generation - 1L)
            synchronouslyVisibleRecord = record
            mutablePreviewReady.value = false
            AttachmentTransition(
                record = record,
                previousRecord = previousRecord,
                previousAttachJob = previousAttachJob,
                previousCaptureJob = previousCaptureJob
            )
        }

        try {
            onGenerationReserved(transition.record.generation)
        } catch (error: Throwable) {
            cancelJobsBeforePortInvalidation(
                attachJob = transition.previousAttachJob,
                captureJob = transition.previousCaptureJob,
                callerJob = callerJob,
                attachMessage = "Camera attachment reservation failed",
                captureMessage = "Camera attachment reservation failed during capture"
            )
            rejectAttachment(
                record = transition.record,
                callerJob = callerJob,
                error = error,
                previousRecordToRestore = transition.previousRecord
            )
            throw error
        }

        cancelJobsBeforePortInvalidation(
            attachJob = transition.previousAttachJob,
            captureJob = transition.previousCaptureJob,
            callerJob = callerJob,
            attachMessage = "Camera attachment was replaced",
            captureMessage = "Camera attachment was replaced during capture"
        )
        transition.previousRecord?.previewPort?.invalidate(
            transition.previousRecord.generation
        )

        val attachment = try {
            attachmentFactory(transition.record.generation)
        } catch (error: Throwable) {
            rejectAttachment(
                record = transition.record,
                callerJob = callerJob,
                error = error,
                previousRecordToRestore = transition.previousRecord
            )
            throw error
        }

        try {
            settlePreviousAttachment(transition)
        } catch (error: Throwable) {
            val wasCurrent = rejectAttachment(
                record = transition.record,
                callerJob = callerJob,
                error = error,
                previousRecordToRestore = transition.previousRecord
            )
            if (error is CancellationException) {
                throw error
            }
            if (!wasCurrent) {
                return PreviewAttachOutcome.Superseded
            }
            throw error
        }

        try {
            currentCoroutineContext().ensureActive()
            previewTransitionMutex.withLock {
                transition.record.previewPort.attach(
                    transition.record.generation,
                    attachment
                )
            }
        } catch (error: Throwable) {
            val wasCurrent = rejectAttachment(
                record = transition.record,
                callerJob = callerJob,
                error = error,
                previousRecordToRestore = null
            )
            if (error is CancellationException) {
                throw error
            }
            if (!wasCurrent) {
                return PreviewAttachOutcome.Superseded
            }
            throw error
        }

        val becameReady = synchronized(stateLock) {
            val current = state
            if (current is CameraRuntimeState.Attaching &&
                current.generation == transition.record.generation &&
                !isGenerationSynchronouslyInvalidated(transition.record.generation)
            ) {
                state = CameraRuntimeState.Ready(transition.record.generation)
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
            transition.record.previewPort.invalidate(transition.record.generation)
            withContext(NonCancellable) {
                previewTransitionMutex.withLock {
                    transition.record.previewPort.detach(transition.record.generation)
                }
            }
            clearVisibleRecordIfMatching(transition.record)
            return PreviewAttachOutcome.Superseded
        }
        return PreviewAttachOutcome.Ready(transition.record.generation)
    }

    suspend fun detach(expectedGeneration: Long? = null) {
        val transition = synchronized(stateLock) {
            val record = activeRecord
            if (record == null ||
                state is CameraRuntimeState.Detached ||
                (expectedGeneration != null && record.generation != expectedGeneration)
            ) {
                null
            } else {
                state = CameraRuntimeState.Detaching(record.generation)
                mutablePreviewReady.value = false
                markSynchronouslyInvalidatedThrough(record.generation)
                synchronouslyVisibleRecord = null
                DetachTransition(record, activeAttachJob, activeCaptureJob)
            }
        } ?: return

        val callerJob = currentCoroutineContext()[Job]
        cancelJobsBeforePortInvalidation(
            attachJob = transition.attachJob,
            captureJob = transition.captureJob,
            callerJob = callerJob,
            attachMessage = "Camera attachment was closed",
            captureMessage = "Camera closed before capture completed"
        )
        transition.record.previewPort.invalidate(transition.record.generation)
        var detachFailure: Throwable? = null
        withContext(NonCancellable) {
            previewTransitionMutex.withLock {
                try {
                    transition.record.previewPort.detach(transition.record.generation)
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
            synchronized(stateLock) {
                val current = state
                if (current is CameraRuntimeState.Detaching &&
                    current.generation == transition.record.generation
                ) {
                    state = CameraRuntimeState.Detached(transition.record.generation)
                    activeRecord = null
                }
                if (activeCaptureJob === transition.captureJob) {
                    activeCaptureJob = null
                }
                if (activeAttachJob === transition.attachJob) {
                    activeAttachJob = null
                }
                mutablePreviewReady.value =
                    state is CameraRuntimeState.Ready &&
                    !isGenerationSynchronouslyInvalidated(state.generation)
            }
        }
        detachFailure?.let { throw it }
    }

    /**
     * Immediately invalidates readiness and cancels jobs without clearing the logical
     * retained record needed for subsequent suspending settlement during replacement attach.
     */
    fun detachSync(expectedGeneration: Long) {
        val transition = synchronized(stateLock) {
            val record = activeRecord
            if (record == null ||
                record.generation != expectedGeneration ||
                isGenerationSynchronouslyInvalidated(expectedGeneration)
            ) {
                null
            } else {
                markSynchronouslyInvalidatedThrough(expectedGeneration)
                synchronouslyVisibleRecord = null
                if (state !is CameraRuntimeState.PreviewUnavailable) {
                    state = CameraRuntimeState.Detaching(expectedGeneration)
                }
                mutablePreviewReady.value = false
                DetachTransition(record, activeAttachJob, activeCaptureJob).also {
                    activeAttachJob = null
                    activeCaptureJob = null
                }
            }
        } ?: return

        transition.attachJob?.cancel(
            CancellationException("Camera surface was destroyed")
        )
        transition.captureJob?.cancel(
            CancellationException("Camera surface was destroyed during capture")
        )
        transition.record.previewPort.invalidate(expectedGeneration)
    }

    /** Records a terminal physical-port failure for the matching generation. */
    fun onPortTerminated(generation: Long, error: Throwable): Boolean {
        val jobs = synchronized(stateLock) {
            val record = activeRecord
            if (record == null ||
                record.generation != generation ||
                isGenerationSynchronouslyInvalidated(generation)
            ) {
                null
            } else {
                markSynchronouslyInvalidatedThrough(generation)
                synchronouslyVisibleRecord = null
                state = CameraRuntimeState.PreviewUnavailable(generation)
                mutablePreviewReady.value = false
                (activeAttachJob to activeCaptureJob).also {
                    activeAttachJob = null
                    activeCaptureJob = null
                }
            }
        } ?: return false

        jobs.first?.cancel(cancellationException("Camera preview terminated", error))
        jobs.second?.cancel(cancellationException("Camera capture owner terminated", error))
        errorReporter.report("Camera preview owner terminated", error)
        return true
    }

    /** Returns whether the exact generation has already lost synchronous runtime ownership. */
    fun isGenerationSynchronouslyInvalidated(generation: Long): Boolean =
        generation <= synchronouslyInvalidatedThroughGeneration

    suspend fun capture(
        isDebug: Boolean,
        tracker: CaptureTimingTracker? = null,
        operation: suspend (CameraFrameSource) -> CaptureResult
    ): CaptureResult {
        if (!captureMutex.tryLock()) {
            return CaptureResult.Failure("Camera is busy. Please try again.")
        }

        val callerJob = currentCoroutineContext()[Job]
        var record: GenerationRouteRecord? = null
        var result: CaptureResult? = null
        var cancellation: CancellationException? = null
        var rebindFailure: Throwable? = null
        var pipelineStart: Long? = null

        try {
            record = synchronized(stateLock) {
                val current = state
                val active = activeRecord
                if (current is CameraRuntimeState.Ready &&
                    active != null &&
                    current.generation == active.generation &&
                    !isGenerationSynchronouslyInvalidated(active.generation)
                ) {
                    state = CameraRuntimeState.Capturing(active.generation)
                    activeCaptureJob = callerJob
                    mutablePreviewReady.value = false
                    active
                } else {
                    null
                }
            }

            val captureRecord = record
            if (captureRecord == null) {
                result = CaptureResult.Failure("Camera is not ready. Please wait.")
            } else {
                pipelineStart = if (isDebug) System.nanoTime() else null
                if (captureRecord.route == CameraOwnershipRoute.CAMERA_X_HANDOFF) {
                    val unbindStart = if (isDebug) System.nanoTime() else null
                    captureRecord.previewPort.unbind(captureRecord.generation)
                    if (tracker != null && unbindStart != null) {
                        tracker.previewUnbindMs =
                            (System.nanoTime() - unbindStart) / 1_000_000L
                    }
                }

                val stillCurrent = synchronized(stateLock) {
                    val current = state
                    current is CameraRuntimeState.Capturing &&
                        current.generation == captureRecord.generation &&
                        !isGenerationSynchronouslyInvalidated(captureRecord.generation)
                }
                if (!stillCurrent) {
                    throw CancellationException("Camera closed before capture started")
                }
                currentCoroutineContext().ensureActive()
                result = operation(captureRecord.frameSource)
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
            val captureRecord = record
            if (captureRecord != null) {
                withContext(NonCancellable) {
                    if (captureRecord.route == CameraOwnershipRoute.CAMERA_X_HANDOFF) {
                        previewTransitionMutex.withLock {
                            val shouldRebind = synchronized(stateLock) {
                                val current = state
                                if (current is CameraRuntimeState.Capturing &&
                                    current.generation == captureRecord.generation &&
                                    !isGenerationSynchronouslyInvalidated(captureRecord.generation)
                                ) {
                                    state = CameraRuntimeState.Rebinding(captureRecord.generation)
                                    true
                                } else {
                                    false
                                }
                            }
                            if (shouldRebind) {
                                val rebindStart = if (isDebug) System.nanoTime() else null
                                try {
                                    captureRecord.previewPort.rebind(captureRecord.generation)
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
                                synchronized(stateLock) {
                                    val current = state
                                    if (current is CameraRuntimeState.Rebinding &&
                                        current.generation == captureRecord.generation
                                    ) {
                                        state = if (rebindFailure == null) {
                                            CameraRuntimeState.Ready(captureRecord.generation)
                                        } else {
                                            CameraRuntimeState.PreviewUnavailable(
                                                captureRecord.generation
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        synchronized(stateLock) {
                            val current = state
                            if (current is CameraRuntimeState.Capturing &&
                                current.generation == captureRecord.generation &&
                                !isGenerationSynchronouslyInvalidated(captureRecord.generation)
                            ) {
                                state = CameraRuntimeState.Ready(captureRecord.generation)
                            }
                        }
                    }

                    synchronized(stateLock) {
                        if (activeCaptureJob === callerJob) {
                            activeCaptureJob = null
                        }
                        val current = state
                        mutablePreviewReady.value =
                            current is CameraRuntimeState.Ready &&
                            !isGenerationSynchronouslyInvalidated(current.generation)
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
        currentCoroutineContext().ensureActive()
        record?.let { captureRecord ->
            if (isGenerationSynchronouslyInvalidated(captureRecord.generation)) {
                throw CancellationException(
                    "Camera generation was invalidated before capture completed"
                )
            }
        }
        val captureResult = result
            ?: CaptureResult.Failure("Camera could not take a photo. Please try again.")
        return withPreviewRecovery(captureResult, rebindFailure)
    }

    private suspend fun settlePreviousAttachment(transition: AttachmentTransition) {
        val previousRecord = transition.previousRecord ?: return
        val callerJob = currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            if (transition.previousAttachJob !== callerJob) {
                transition.previousAttachJob?.cancel(
                    CancellationException("Camera attachment was replaced")
                )
            }
            if (transition.previousCaptureJob !== callerJob) {
                transition.previousCaptureJob?.cancel(
                    CancellationException("Camera attachment was replaced")
                )
            }
            previewTransitionMutex.withLock {
                previousRecord.previewPort.detach(previousRecord.generation)
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
            synchronized(stateLock) {
                if (activeCaptureJob === transition.previousCaptureJob) {
                    activeCaptureJob = null
                }
                if (activeAttachJob === transition.previousAttachJob) {
                    activeAttachJob = null
                }
            }
        }
    }

    private suspend fun rejectAttachment(
        record: GenerationRouteRecord,
        callerJob: Job?,
        error: Throwable,
        previousRecordToRestore: GenerationRouteRecord? = null
    ): Boolean {
        val wasCurrent = synchronized(stateLock) {
            val current = state
            if (current is CameraRuntimeState.Attaching &&
                current.generation == record.generation
            ) {
                if (previousRecordToRestore != null) {
                    state = CameraRuntimeState.PreviewUnavailable(previousRecordToRestore.generation)
                    activeRecord = previousRecordToRestore
                } else {
                    state = CameraRuntimeState.Detached(record.generation)
                    activeRecord = null
                }
                markSynchronouslyInvalidatedThrough(record.generation)
                synchronouslyVisibleRecord = null
                if (activeAttachJob === callerJob) {
                    activeAttachJob = null
                }
                mutablePreviewReady.value = false
                true
            } else {
                false
            }
        }
        record.previewPort.invalidate(record.generation)
        withContext(NonCancellable) {
            try {
                previewTransitionMutex.withLock {
                    record.previewPort.detach(record.generation)
                }
            } catch (detachError: Throwable) {
                errorReporter.report(
                    "Failed to clean up rejected preview attachment",
                    detachError
                )
            }
        }
        if (error !is CancellationException && wasCurrent) {
            errorReporter.report("Failed to attach camera preview", error)
        }
        return wasCurrent
    }

    private fun cancelJobsBeforePortInvalidation(
        attachJob: Job?,
        captureJob: Job?,
        callerJob: Job?,
        attachMessage: String,
        captureMessage: String
    ) {
        if (attachJob !== callerJob) {
            attachJob?.cancel(CancellationException(attachMessage))
        }
        if (captureJob !== callerJob) {
            captureJob?.cancel(CancellationException(captureMessage))
        }
    }

    private fun markSynchronouslyInvalidatedThrough(generation: Long) {
        if (generation > synchronouslyInvalidatedThroughGeneration) {
            synchronouslyInvalidatedThroughGeneration = generation
        }
    }

    private fun clearVisibleRecordIfMatching(record: GenerationRouteRecord) {
        synchronized(stateLock) {
            if (synchronouslyVisibleRecord === record) {
                synchronouslyVisibleRecord = null
            }
        }
    }

    private fun cancellationException(message: String, cause: Throwable): CancellationException =
        CancellationException(message).also { cancellation -> cancellation.initCause(cause) }

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
