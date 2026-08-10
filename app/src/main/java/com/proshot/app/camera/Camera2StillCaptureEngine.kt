package com.proshot.app.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Size
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

private const val STILL_CAPTURE_TIMEOUT_MS = 8_000L
private const val AE_WARMUP_MIN_FRAMES = 3
private const val AE_WARMUP_MAX_FRAMES = 12

internal data class TimestampCorrelatedCopiedFrame(
    val frame: CopiedImageFrame,
    val resultTimestamp: Long
)

/** One phase-scoped registration pairing active work with its exact ready wait. */
internal class Camera2SessionReadyPhase(
    private val imageRouter: Camera2ImageReaderRouter,
    private val gate: CameraSessionReadyGate = CameraSessionReadyGate()
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val token = imageRouter.registerSessionReadyGate(gate)

    fun markActive() {
        check(!closed.get()) { "Session-ready phase is closed" }
        imageRouter.onSessionActive()
    }

    fun armAndStop(onReady: () -> Unit, stopRepeating: () -> Unit): CameraSessionReadyArmResult {
        check(!closed.get()) { "Session-ready phase is closed" }
        val armResult = gate.arm(onReady)
        if (armResult == CameraSessionReadyArmResult.ARMED) {
            try {
                stopRepeating()
            } catch (failure: Throwable) {
                gate.disarm()
                throw failure
            }
        }
        return armResult
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        gate.disarm()
        imageRouter.unregisterSessionReadyGate(token)
    }
}

/** Fixed-focus cameras reuse the completed warm-up boundary without an AF phase. */
internal fun requiresAutoFocusReadinessPhase(autoFocusMode: Int?): Boolean =
    autoFocusMode != null

/**
 * Shared session-bound engine executing AE warmup, AF lock, timestamp correlation,
 * and single-frame YUV capture for both direct Camera2 and rollback Camera2 routes.
 */
internal class Camera2StillCaptureEngine(
    private val device: CameraDevice,
    private val session: CameraCaptureSession,
    private val handler: Handler,
    private val characteristics: CameraCharacteristics,
    private val selectedSize: Size,
    private val repeatingSurface: Surface,
    private val stillSurface: Surface,
    private val imageReader: ImageReader,
    private val imageRouter: Camera2ImageReaderRouter,
    private val submissionGate: Camera2RequestSubmissionGate =
        AlwaysOpenCamera2RequestSubmissionGate
) {
    suspend fun executeStillCapture(
        tracker: CaptureTimingTracker? = null,
        diagnosticsTracker: FocusLensDiagnosticsTracker? = null,
        focusTarget: FocusMeteringTarget = FocusMeteringTarget.center(),
        sessionReadyGate: CameraSessionReadyGate = CameraSessionReadyGate()
    ): CopiedImageFrame {
        return withTimeout(STILL_CAPTURE_TIMEOUT_MS) {
            val totalStart = tracker?.let { System.nanoTime() }
            val availableAutoFocusModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            val maxRegionsAfRaw = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
            val maxRegionsAeRaw = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)
            val maxRegionsAf = maxRegionsAfRaw ?: 0
            val maxRegionsAe = maxRegionsAeRaw ?: 0
            val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

            val effectiveFocusPolicy = resolveEffectiveFocusTargetPolicy(
                requestedSource = focusTarget.source,
                maxAfRegions = maxRegionsAf,
                activeArrayAvailable = activeArray != null
            )
            val autoFocusMode = SingleFrameCaptureController.selectAutoFocusModeForStillCapture(
                availableModes = availableAutoFocusModes,
                source = effectiveFocusPolicy.effectiveSource
            )

            if (diagnosticsTracker != null) {
                diagnosticsTracker.clearAfWaitOutcome()
                diagnosticsTracker.logicalCameraId = device.id
                diagnosticsTracker.physicalCameraIds =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        characteristics.physicalCameraIds.toList()
                    } else {
                        emptyList()
                    }
                diagnosticsTracker.lensFacing = FocusLensDiagnosticsHelper.mapLensFacing(
                    characteristics.get(CameraCharacteristics.LENS_FACING)
                )
                diagnosticsTracker.hardwareLevel = FocusLensDiagnosticsHelper.mapHardwareLevel(
                    characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                )
                diagnosticsTracker.focalLengths = characteristics.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                )?.toList()
                diagnosticsTracker.minFocusDistance = characteristics.get(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                )
                diagnosticsTracker.hyperfocalDistance = characteristics.get(
                    CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE
                )
                diagnosticsTracker.availableAfModes = availableAutoFocusModes
                    ?.toList()
                    ?.mapNotNull { mode -> FocusLensDiagnosticsHelper.mapAfMode(mode) }
                diagnosticsTracker.selectedAfMode =
                    FocusLensDiagnosticsHelper.mapAfMode(autoFocusMode)
                diagnosticsTracker.focusTargetSource = effectiveFocusPolicy.requestedSource.name
                diagnosticsTracker.effectiveFocusTargetSource =
                    effectiveFocusPolicy.effectiveSource.name
                diagnosticsTracker.focusTargetFallback =
                    effectiveFocusPolicy.fallbackReason.name
                diagnosticsTracker.normalizedTargetX = focusTarget.x
                diagnosticsTracker.normalizedTargetY = focusTarget.y
                diagnosticsTracker.normalizedAfSize = focusTarget.afSize
                diagnosticsTracker.normalizedAeSize = focusTarget.aeSize
                diagnosticsTracker.afMaxRegions = maxRegionsAf
                diagnosticsTracker.aeMaxRegions = maxRegionsAe
            }

            val captureSize = CaptureSize(selectedSize.width, selectedSize.height)
            val pureActive = activeArray?.let { PureRect(it.left, it.top, it.right, it.bottom) }
            val cropRegion = if (pureActive != null) {
                ActiveArrayCropCalculator.calculateCenterCrop(pureActive, captureSize)
            } else null

            diagnosticsTracker?.meteringCropRegion = when {
                cropRegion != null ->
                    "Rect(${cropRegion.left}, ${cropRegion.top}, " +
                        "${cropRegion.right - cropRegion.left}x" +
                        "${cropRegion.bottom - cropRegion.top})"
                activeArray == null -> "NONE_ACTIVE_ARRAY_NULL"
                else -> "NONE"
            }

            val afRegionsToApply = if (pureActive == null) {
                diagnosticsTracker?.afRegionApplied = "NONE_ACTIVE_ARRAY_NULL"
                null
            } else if (maxRegionsAf <= 0) {
                diagnosticsTracker?.afRegionApplied = "NONE_UNSUPPORTED"
                null
            } else {
                val mapped = FocusMeteringCoordinateMapper.mapToActiveArray(
                    target = focusTarget,
                    size = focusTarget.afSize,
                    activeArray = pureActive,
                    cropRegion = cropRegion
                )
                arrayOf(android.hardware.camera2.params.MeteringRectangle(
                    android.graphics.Rect(mapped.left, mapped.top, mapped.right, mapped.bottom),
                    focusTarget.afWeight
                )).also {
                    diagnosticsTracker?.afRegionApplied =
                        "Rect(${mapped.left}, ${mapped.top}, " +
                            "${mapped.right - mapped.left}x${mapped.bottom - mapped.top})"
                }
            }

            val aeRegionsToApply = if (pureActive == null) {
                diagnosticsTracker?.aeRegionApplied = "NONE_ACTIVE_ARRAY_NULL"
                null
            } else if (maxRegionsAe <= 0) {
                diagnosticsTracker?.aeRegionApplied = "NONE_UNSUPPORTED"
                null
            } else {
                val mapped = FocusMeteringCoordinateMapper.mapToActiveArray(
                    target = focusTarget,
                    size = focusTarget.aeSize,
                    activeArray = pureActive,
                    cropRegion = cropRegion
                )
                arrayOf(android.hardware.camera2.params.MeteringRectangle(
                    android.graphics.Rect(mapped.left, mapped.top, mapped.right, mapped.bottom),
                    focusTarget.aeWeight
                )).also {
                    diagnosticsTracker?.aeRegionApplied =
                        "Rect(${mapped.left}, ${mapped.top}, " +
                            "${mapped.right - mapped.left}x${mapped.bottom - mapped.top})"
                }
            }

            val warmupStart = tracker?.let { System.nanoTime() }
            warmUpAutoExposure(
                autoFocusMode = autoFocusMode,
                diagnosticsTracker = diagnosticsTracker,
                afRegions = afRegionsToApply,
                aeRegions = aeRegionsToApply,
                sessionReadyGate = sessionReadyGate
            )
            if (warmupStart != null) {
                tracker?.aeWarmupMs = (System.nanoTime() - warmupStart) / 1_000_000L
            }

            val afStart = if (tracker != null && autoFocusMode != null) System.nanoTime() else null
            lockAutoFocusBeforeCapture(
                autoFocusMode,
                diagnosticsTracker,
                afRegionsToApply,
                aeRegionsToApply
            )
            if (afStart != null) {
                tracker?.afWaitMs = (System.nanoTime() - afStart) / 1_000_000L
            }

            val stillStart = tracker?.let { System.nanoTime() }
            val requestTag = Any()

            var armToken: RouterArmToken? = null
            val resultHolder = try {
                suspendCancellableCoroutine<TimestampCorrelatedCopiedFrame> { cont ->
                    val correlator = CaptureTimestampCorrelator<Image>(
                        requestTag = requestTag,
                        timestampExtractor = { image -> image.timestamp },
                        releaser = { image ->
                            try { image.close() } catch (_: Exception) {}
                        },
                        onOutcome = { outcome ->
                            when (outcome) {
                                is CorrelationOutcome.Success -> {
                                    val image = outcome.candidate
                                    val sensorTs = outcome.timestamp
                                    var copiedFrame: CopiedImageFrame? = null
                                    var transferFailure: Throwable? = null
                                    try {
                                        copiedFrame = CopiedImageFrame.copyFrom(image)
                                    } catch (failure: Throwable) {
                                        transferFailure = failure
                                    } finally {
                                        try { image.close() } catch (closeFailure: Throwable) {
                                            if (transferFailure == null) transferFailure = closeFailure
                                        }
                                    }

                                    if (transferFailure != null) {
                                        cont.resumeWithException(transferFailure)
                                    } else if (copiedFrame != null) {
                                        cont.resume(TimestampCorrelatedCopiedFrame(copiedFrame, sensorTs))
                                    } else {
                                        cont.resumeWithException(IllegalStateException("Matched frame transfer failed"))
                                    }
                                }
                                is CorrelationOutcome.Failure -> {
                                    cont.resumeWithException(outcome.cause)
                                }
                            }
                        }
                    )

                    val stillCallback = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: android.hardware.camera2.TotalCaptureResult
                        ) {
                            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                            correlator.onCaptureCompleted(
                                sequenceId = result.sequenceId,
                                sensorTimestamp = ts,
                                tag = request.tag
                            )
                        }

                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: android.hardware.camera2.CaptureFailure
                        ) {
                            correlator.onCaptureFailed(sequenceId = failure.sequenceId, tag = request.tag)
                        }

                        override fun onCaptureBufferLost(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            target: Surface,
                            frameNumber: Long
                        ) {
                            correlator.onCaptureBufferLost(tag = request.tag, frameNumber = frameNumber)
                        }

                        override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                            correlator.onCaptureSequenceCompleted(sequenceId = sequenceId)
                        }

                        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                            correlator.onCaptureSequenceAborted(sequenceId = sequenceId)
                        }
                    }

                    // Cancellation owns the correlator before arming or any platform call.
                    cont.invokeOnCancellation {
                        armToken?.let { token ->
                            imageRouter.disarmCorrelator(token, imageReader)
                        }
                        correlator.close()
                    }

                    try {
                        submissionGate.withOpenSubmission {
                            armToken = imageRouter.armCorrelator(correlator, imageReader)
                            val request = device.createCaptureRequest(
                                CameraDevice.TEMPLATE_STILL_CAPTURE
                            ).apply {
                                setTag(requestTag)
                                addTarget(stillSurface)
                                autoFocusMode?.let { set(CaptureRequest.CONTROL_AF_MODE, it) }
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(
                                    CaptureRequest.CONTROL_CAPTURE_INTENT,
                                    CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
                                )
                                afRegionsToApply?.let {
                                    set(CaptureRequest.CONTROL_AF_REGIONS, it)
                                }
                                aeRegionsToApply?.let {
                                    set(CaptureRequest.CONTROL_AE_REGIONS, it)
                                }
                            }.build()
                            val sequenceId = session.capture(request, stillCallback, handler)
                            correlator.registerSequenceId(sequenceId)
                        }
                    } catch (failure: Throwable) {
                        correlator.onSubmissionOrCopyFailed(failure)
                    }
                }
            } finally {
                armToken?.let { token -> imageRouter.disarmCorrelator(token, imageReader) }
            }

            val frame = resultHolder.frame
            if (diagnosticsTracker != null) {
                diagnosticsTracker.stillCaptureResultTimestamp = resultHolder.resultTimestamp
                diagnosticsTracker.copiedImageTimestamp = frame.timestamp
                diagnosticsTracker.captureWidth = frame.width
                diagnosticsTracker.captureHeight = frame.height
                diagnosticsTracker.imageFormat = "YUV_420_888"
            }
            if (stillStart != null) {
                tracker?.stillCaptureMs = (System.nanoTime() - stillStart) / 1_000_000L
            }
            if (totalStart != null) {
                tracker?.totalCamera2CaptureMs = (System.nanoTime() - totalStart) / 1_000_000L
            }

            frame
        }
    }

    private suspend fun warmUpAutoExposure(
        autoFocusMode: Int?,
        diagnosticsTracker: FocusLensDiagnosticsTracker?,
        afRegions: Array<android.hardware.camera2.params.MeteringRectangle>?,
        aeRegions: Array<android.hardware.camera2.params.MeteringRectangle>?,
        sessionReadyGate: CameraSessionReadyGate
    ) {
        val readyPhase = Camera2SessionReadyPhase(imageRouter, sessionReadyGate)
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val isWarmupDone = AtomicBoolean(false)
                var frameCount = 0

                fun finishWarmup(timedOut: Boolean, aeState: Int?) {
                    if (!isWarmupDone.compareAndSet(false, true)) return
                    diagnosticsTracker?.aeWarmupExitState =
                        FocusLensDiagnosticsHelper.mapAeState(aeState)
                            ?: if (timedOut) "NULL_TIMEOUT" else "NULL_CONVERGED"
                    diagnosticsTracker?.aeWarmupFrameCount = frameCount

                    try {
                        val armResult = readyPhase.armAndStop(
                            onReady = { if (cont.isActive) cont.resume(Unit) },
                            stopRepeating = {
                                submissionGate.withOpenSubmission {
                                    session.stopRepeating()
                                }
                            }
                        )
                        if (armResult != CameraSessionReadyArmResult.ARMED && cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException(
                                    "Unable to arm AE warm-up ready boundary: $armResult"
                                )
                            )
                        }
                    } catch (failure: Throwable) {
                        if (cont.isActive) cont.resumeWithException(failure)
                    }
                }

                val callback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult
                    ) {
                        if (isWarmupDone.get()) return
                        frameCount++
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        val aeReady = aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED

                        if (frameCount >= AE_WARMUP_MIN_FRAMES && aeReady) {
                            finishWarmup(timedOut = false, aeState = aeState)
                        } else if (frameCount >= AE_WARMUP_MAX_FRAMES) {
                            finishWarmup(timedOut = true, aeState = aeState)
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        if (isWarmupDone.get()) return
                        frameCount++
                        if (frameCount >= AE_WARMUP_MAX_FRAMES) {
                            finishWarmup(timedOut = true, aeState = null)
                        }
                    }
                }

                // Cleanup exists before the first request construction/submission.
                cont.invokeOnCancellation {
                    if (isWarmupDone.compareAndSet(false, true)) {
                        readyPhase.close()
                        try {
                            submissionGate.withOpenSubmission {
                                session.stopRepeating()
                            }
                        } catch (_: Throwable) {
                            // Owner close or session teardown is already authoritative.
                        }
                    }
                }

                try {
                    submissionGate.withOpenSubmission {
                        val request = device.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW
                        ).apply {
                            addTarget(repeatingSurface)
                            autoFocusMode?.let { set(CaptureRequest.CONTROL_AF_MODE, it) }
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            afRegions?.let { set(CaptureRequest.CONTROL_AF_REGIONS, it) }
                            aeRegions?.let { set(CaptureRequest.CONTROL_AE_REGIONS, it) }
                        }.build()
                        session.setRepeatingRequest(request, callback, handler)
                        readyPhase.markActive()
                    }
                } catch (failure: Throwable) {
                    if (isWarmupDone.compareAndSet(false, true) && cont.isActive) {
                        cont.resumeWithException(failure)
                    }
                }
            }
        } finally {
            readyPhase.close()
        }
    }

    private suspend fun lockAutoFocusBeforeCapture(
        autoFocusMode: Int?,
        diagnosticsTracker: FocusLensDiagnosticsTracker?,
        afRegions: Array<android.hardware.camera2.params.MeteringRectangle>?,
        aeRegions: Array<android.hardware.camera2.params.MeteringRectangle>?
    ) {
        if (!requiresAutoFocusReadinessPhase(autoFocusMode)) {
            diagnosticsTracker?.publishAfWaitOutcome(
                FocusWaitDiagnosticSample(
                    resultAfMode = null,
                    resultAfRegions = null,
                    resultAeRegions = null,
                    resultScalerCrop = null,
                    afState = null,
                    repeatingFrameCount = null,
                    exitReason = "FIXED_FOCUS",
                    afTriggerIssued = false,
                    requestProvenance = "NONE"
                )
            )
            return
        }

        val activeAutoFocusMode = checkNotNull(autoFocusMode)
        val readyPhase = Camera2SessionReadyPhase(imageRouter)
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val isFocusBoundaryStarted = AtomicBoolean(false)
                val isFocusCompletionDelivered = AtomicBoolean(false)
                val triggerIssued = AtomicBoolean(false)
                val triggerSubmissionLock = Any()
                val activePolicy = AutoFocusWaitPolicy(activeAutoFocusMode)

                fun completeFocusWaitAfterBoundary(
                    outcome: AutoFocusWaitOutcome,
                    result: android.hardware.camera2.TotalCaptureResult?,
                    requestProvenance: String,
                    failure: Throwable?
                ) {
                    if (!isFocusCompletionDelivered.compareAndSet(false, true)) return
                    diagnosticsTracker?.publishAfWaitOutcome(
                        createFocusWaitDiagnosticSample(
                            result = result,
                            outcome = outcome,
                            repeatingFrameCount = activePolicy.repeatingFrameCount,
                            afTriggerIssued = triggerIssued.get(),
                            requestProvenance = requestProvenance
                        )
                    )
                    if (cont.isActive) {
                        if (failure != null) {
                            cont.resumeWithException(failure)
                        } else {
                            cont.resume(Unit)
                        }
                    }
                }

                fun finishFocusWait(
                    outcome: AutoFocusWaitOutcome,
                    result: android.hardware.camera2.TotalCaptureResult?,
                    requestProvenance: String,
                    failure: Throwable? = null
                ) {
                    synchronized(triggerSubmissionLock) {
                        if (!isFocusBoundaryStarted.compareAndSet(false, true)) return
                        try {
                            val armResult = readyPhase.armAndStop(
                                onReady = {
                                    completeFocusWaitAfterBoundary(
                                        outcome,
                                        result,
                                        requestProvenance,
                                        failure
                                    )
                                },
                                stopRepeating = {
                                    submissionGate.withOpenSubmission {
                                        session.stopRepeating()
                                    }
                                }
                            )
                            if (armResult != CameraSessionReadyArmResult.ARMED) {
                                completeFocusWaitAfterBoundary(
                                    outcome = outcome,
                                    result = result,
                                    requestProvenance = requestProvenance,
                                    failure = IllegalStateException(
                                        "Unable to arm final AF ready boundary: $armResult"
                                    )
                                )
                            }
                        } catch (stopFailure: Throwable) {
                            completeFocusWaitAfterBoundary(
                                outcome = outcome,
                                result = result,
                                requestProvenance = requestProvenance,
                                failure = failure ?: stopFailure
                            )
                        }
                    }
                }

                val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult
                    ) {
                        if (isFocusBoundaryStarted.get()) return
                        val outcome = activePolicy.onRepeatingCompleted(
                            result.get(CaptureResult.CONTROL_AF_STATE)
                        )
                        if (outcome != null) {
                            finishFocusWait(outcome, result, "REPEATING")
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        if (isFocusBoundaryStarted.get()) return
                        val outcome = activePolicy.onRepeatingFailed()
                        if (outcome != null) {
                            finishFocusWait(outcome, null, "REPEATING")
                        }
                    }
                }

                val triggerCallback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult
                    ) {
                        synchronized(triggerSubmissionLock) {
                            if (!isFocusBoundaryStarted.get()) {
                                activePolicy.onTriggerCompleted()
                            }
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        synchronized(triggerSubmissionLock) {
                            if (isFocusBoundaryStarted.get()) return
                            val outcome = activePolicy.onTriggerFailed(aborted = false) ?: return
                            finishFocusWait(
                                outcome = outcome,
                                result = null,
                                requestProvenance = "TRIGGER",
                                failure = IllegalStateException(
                                    "AF trigger request failed with reason ${failure.reason}"
                                )
                            )
                        }
                    }
                }

                // Cleanup exists before AF repeating or trigger submission.
                cont.invokeOnCancellation {
                    synchronized(triggerSubmissionLock) {
                        if (isFocusCompletionDelivered.compareAndSet(false, true)) {
                            isFocusBoundaryStarted.set(true)
                            readyPhase.close()
                            try {
                                submissionGate.withOpenSubmission {
                                    session.stopRepeating()
                                }
                            } catch (_: Throwable) {
                                // Owner close or session teardown is already authoritative.
                            }
                        }
                    }
                }

                try {
                    synchronized(triggerSubmissionLock) {
                        if (!cont.isActive || isFocusBoundaryStarted.get()) {
                            return@suspendCancellableCoroutine
                        }
                        submissionGate.withOpenSubmission {
                            val repeatingRequest = device.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW
                            ).apply {
                                addTarget(repeatingSurface)
                                set(CaptureRequest.CONTROL_AF_MODE, activeAutoFocusMode)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                afRegions?.let { set(CaptureRequest.CONTROL_AF_REGIONS, it) }
                                aeRegions?.let { set(CaptureRequest.CONTROL_AE_REGIONS, it) }
                            }.build()
                            session.setRepeatingRequest(
                                repeatingRequest,
                                repeatingCallback,
                                handler
                            )
                            readyPhase.markActive()
                        }
                    }
                } catch (failure: Throwable) {
                    if (isFocusBoundaryStarted.compareAndSet(false, true) &&
                        isFocusCompletionDelivered.compareAndSet(false, true)
                    ) {
                        readyPhase.close()
                        if (cont.isActive) cont.resumeWithException(failure)
                    }
                    return@suspendCancellableCoroutine
                }

                if (SingleFrameCaptureController.shouldTriggerAutoFocus(activeAutoFocusMode)) {
                    try {
                        synchronized(triggerSubmissionLock) {
                            if (!cont.isActive || isFocusBoundaryStarted.get()) {
                                return@synchronized
                            }
                            submissionGate.withOpenSubmission {
                                val triggerRequest = device.createCaptureRequest(
                                    CameraDevice.TEMPLATE_PREVIEW
                                ).apply {
                                    addTarget(repeatingSurface)
                                    set(CaptureRequest.CONTROL_AF_MODE, activeAutoFocusMode)
                                    set(
                                        CaptureRequest.CONTROL_AF_TRIGGER,
                                        CaptureRequest.CONTROL_AF_TRIGGER_START
                                    )
                                    set(
                                        CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON
                                    )
                                    afRegions?.let { set(CaptureRequest.CONTROL_AF_REGIONS, it) }
                                    aeRegions?.let { set(CaptureRequest.CONTROL_AE_REGIONS, it) }
                                }.build()
                                session.capture(triggerRequest, triggerCallback, handler)
                                triggerIssued.set(true)
                            }
                        }
                    } catch (failure: Throwable) {
                        finishFocusWait(
                            outcome = AutoFocusWaitOutcome.TRIGGER_SUBMISSION_FAILED,
                            result = null,
                            requestProvenance = "TRIGGER",
                            failure = failure
                        )
                    }
                }
            }
        } finally {
            readyPhase.close()
        }
    }

    private fun createFocusWaitDiagnosticSample(
        result: android.hardware.camera2.TotalCaptureResult?,
        outcome: AutoFocusWaitOutcome,
        repeatingFrameCount: Int,
        afTriggerIssued: Boolean,
        requestProvenance: String
    ): FocusWaitDiagnosticSample {
        val rawAfRegions = result?.get(CaptureResult.CONTROL_AF_REGIONS)
        val rawAeRegions = result?.get(CaptureResult.CONTROL_AE_REGIONS)
        val crop = result?.get(CaptureResult.SCALER_CROP_REGION)
        return FocusWaitDiagnosticSample(
            resultAfMode = FocusLensDiagnosticsHelper.mapAfMode(result?.get(CaptureResult.CONTROL_AF_MODE)),
            resultAfRegions = formatMeteringRegions(rawAfRegions),
            resultAeRegions = formatMeteringRegions(rawAeRegions),
            resultScalerCrop = crop?.let { "Rect(${it.left}, ${it.top}, ${it.width()}x${it.height()})" },
            afState = FocusLensDiagnosticsHelper.mapAfState(result?.get(CaptureResult.CONTROL_AF_STATE)),
            repeatingFrameCount = repeatingFrameCount,
            exitReason = outcome.name,
            afTriggerIssued = afTriggerIssued,
            requestProvenance = requestProvenance
        )
    }

    private fun formatMeteringRegions(
        regions: Array<android.hardware.camera2.params.MeteringRectangle>?
    ): String? {
        if (regions.isNullOrEmpty()) return null
        return regions.joinToString(", ") { region ->
            "Rect(${region.rect.left}, ${region.rect.top}, " +
                "${region.rect.width()}x${region.rect.height()} " +
                "wt=${region.meteringWeight})"
        }
    }
}
