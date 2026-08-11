package com.proshot.app.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class DirectPreviewAttachment(
    val surfaceTexture: SurfaceTexture,
    val width: Int,
    val height: Int,
    val onTerminal: (DirectCamera2Failure) -> Unit
) : PreviewAttachment

internal data class DirectPreviewConfiguration(
    val generation: Long,
    val streamSize: CaptureSize,
    val sensorOrientationDegrees: Int
)

/** Selects the first physical back camera without front/external fallback. */
internal fun selectRequiredBackCameraId(
    cameraIds: List<String>,
    lensFacing: (String) -> Int?
): String = cameraIds.firstOrNull { cameraId ->
    lensFacing(cameraId) == CameraMetadata.LENS_FACING_BACK
} ?: throw UnsupportedOperationException(
    "Persistent Camera2 preview requires a physical back camera"
)

/** Classifies persistent-owner construction failures without message matching. */
internal fun classifyPersistentOwnerConstructionFailure(
    error: Exception
): DirectCamera2FailureKind = when (error) {
    is PersistentCamera2ConstructionBarrierException ->
        DirectCamera2FailureKind.OWNER_TERMINAL_BARRIER
    is SecurityException -> DirectCamera2FailureKind.PERMISSION_OR_SECURITY
    else -> DirectCamera2FailureKind.UNSUPPORTED_CONFIGURATION
}

/** Tracks close identity under state lock and performs physical close after release. */
internal fun <T> beginOwnerCloseOutsideStateLock(
    stateLock: ReentrantLock,
    closingOwners: MutableSet<T>,
    owner: T,
    closeOwner: (T) -> Unit
) {
    val shouldClose = stateLock.withLock { closingOwners.add(owner) }
    if (shouldClose) closeOwner(owner)
}

/** Production action performing terminal callback before physical owner close outside state lock. */
internal class DirectTerminalCloseAction<T>(
    val owner: T,
    val failure: DirectCamera2Failure,
    val onTerminal: (DirectCamera2Failure) -> Unit,
    val closeOwner: (T) -> Unit
) {
    fun execute() {
        try {
            onTerminal(failure)
        } finally {
            closeOwner(owner)
        }
    }
}

/** Runs terminal publication/close before callback-resource acknowledgement and completion. */
internal fun executeTerminalFailureCallback(
    publishTerminal: () -> Unit,
    registerFailureResource: () -> Unit,
    completeContinuation: () -> Unit
) {
    try {
        publishTerminal()
    } finally {
        try {
            registerFailureResource()
        } finally {
            completeContinuation()
        }
    }
}

/** Production seam preparing terminal publication under state lock for execution outside lock. */
internal fun <O : Any, S : Any> prepareTerminalCloseAction(
    lock: ReentrantLock,
    closingOwners: MutableSet<O>,
    owner: O,
    currentIdentity: S?,
    expectedIdentity: S,
    failure: DirectCamera2Failure,
    onTerminal: (DirectCamera2Failure) -> Unit,
    closeOwner: (O) -> Unit,
    clearState: () -> Unit
): DirectTerminalCloseAction<O>? {
    return lock.withLock {
        if (currentIdentity === null ||
            currentIdentity !== expectedIdentity ||
            closingOwners.contains(owner)
        ) {
            null
        } else {
            closingOwners.add(owner)
            clearState()
            DirectTerminalCloseAction(
                owner = owner,
                failure = failure,
                onTerminal = onTerminal,
                closeOwner = closeOwner
            )
        }
    }
}

private data class ActiveDirectOwner(
    val generation: Long,
    val surfaceTexture: SurfaceTexture,
    val owner: PersistentCamera2ResourceOwner,
    val streamSize: Size,
    val characteristics: CameraCharacteristics,
    val previewAutoFocusMode: Int?,
    val onTerminal: (DirectCamera2Failure) -> Unit
)

private data class DirectConfigTuple(
    val manager: CameraManager,
    val cameraId: String,
    val characteristics: CameraCharacteristics,
    val selectedSize: CaptureSize
)

/**
 * Retained controller for one generation-specific direct Camera2 preview owner.
 */
@ActivityRetainedScoped
internal class DirectCamera2PreviewController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resourceOwnerFactory: PersistentCamera2ResourceOwnerFactory,
    private val sessionCreator: Camera2CaptureSessionCreator
) : PreviewLifecyclePort, CameraFrameSource {
    private val lock = ReentrantLock()
    private val mutablePreviewConfiguration =
        MutableStateFlow<DirectPreviewConfiguration?>(null)

    private var requestedGeneration: Long? = null
    private var requestedSurfaceTexture: SurfaceTexture? = null
    private var activeOwner: ActiveDirectOwner? = null
    private val closingOwners = mutableSetOf<PersistentCamera2ResourceOwner>()

    val previewConfiguration: StateFlow<DirectPreviewConfiguration?> =
        mutablePreviewConfiguration.asStateFlow()

    val isAttached: Boolean
        get() = lock.withLock { activeOwner != null }

    internal val hasClosingOwners: Boolean
        get() = lock.withLock { closingOwners.isNotEmpty() }

    override fun invalidate(generation: Long) {
        val ownerToClose = lock.withLock {
            if (requestedGeneration != generation && activeOwner?.generation != generation) {
                null
            } else {
                requestedGeneration = null
                requestedSurfaceTexture = null
                mutablePreviewConfiguration.value = null
                activeOwner?.takeIf { it.generation == generation }
                    .also { if (it != null) activeOwner = null }
            }
        }
        ownerToClose?.let { record -> beginOwnerClose(record.owner) }
    }

    fun invalidateSurface(surfaceTexture: SurfaceTexture) {
        val ownerToClose = lock.withLock {
            val matchesRequest = requestedSurfaceTexture === surfaceTexture
            val matchesOwner = activeOwner?.surfaceTexture === surfaceTexture
            if (!matchesRequest && !matchesOwner) {
                null
            } else {
                requestedGeneration = null
                requestedSurfaceTexture = null
                mutablePreviewConfiguration.value = null
                activeOwner?.takeIf { it.surfaceTexture === surfaceTexture }
                    .also { if (it != null) activeOwner = null }
            }
        }
        ownerToClose?.let { record -> beginOwnerClose(record.owner) }
    }

    override suspend fun attach(generation: Long, attachment: PreviewAttachment) {
        val directAttachment = attachment as? DirectPreviewAttachment
            ?: throw IllegalArgumentException("Unsupported preview attachment")
        require(directAttachment.width > 0 && directAttachment.height > 0) {
            "Direct preview surface dimensions must be positive"
        }

        withContext(Dispatchers.Default) {
            val previousOwner = lock.withLock {
                requestedGeneration = generation
                requestedSurfaceTexture = directAttachment.surfaceTexture
                mutablePreviewConfiguration.value = null
                activeOwner.also { activeOwner = null }
            }
            previousOwner?.let { record -> beginOwnerClose(record.owner) }

            try {
                awaitClosingOwners()
            } catch (barrierError: Exception) {
                if (barrierError is CancellationException) throw barrierError
                val failure = DirectCamera2Failure(
                    kind = DirectCamera2FailureKind.OWNER_TERMINAL_BARRIER,
                    cause = barrierError
                )
                throw DirectCamera2RouteException(failure)
            }

            currentCoroutineContext().ensureActive()
            val requestIsCurrent = lock.withLock {
                requestedGeneration == generation &&
                    requestedSurfaceTexture === directAttachment.surfaceTexture
            }
            if (!requestIsCurrent) {
                throw CancellationException("Direct preview attachment was superseded")
            }

            val config = try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    ?: throw IllegalStateException("CameraManager is not available")
                val cameraId = resolvePrimaryCameraId(manager)
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val streamMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                ) ?: throw IllegalStateException("Stream configuration map unavailable")

                val previewSizes = streamMap.getOutputSizes(SurfaceTexture::class.java)
                    ?.map { CaptureSize(it.width, it.height) }
                    .orEmpty()
                val yuvSizes = streamMap.getOutputSizes(ImageFormat.YUV_420_888)
                    ?.map { CaptureSize(it.width, it.height) }
                    .orEmpty()
                val selectedSize = Camera2StreamPairSelector.selectCommonStreamSize(
                    previewSizes = previewSizes,
                    yuvSizes = yuvSizes
                )
                DirectConfigTuple(manager, cameraId, characteristics, selectedSize)
            } catch (configError: Exception) {
                if (configError is CancellationException) throw configError
                val kind = when (configError) {
                    is SecurityException -> DirectCamera2FailureKind.PERMISSION_OR_SECURITY
                    is CameraAccessException -> DirectCamera2FailureKind.CAMERA_DEVICE_OR_OPEN
                    else -> DirectCamera2FailureKind.UNSUPPORTED_CONFIGURATION
                }
                val failure = DirectCamera2Failure(kind = kind, cause = configError)
                throw DirectCamera2RouteException(failure)
            }

            val targetSize = Size(config.selectedSize.width, config.selectedSize.height)
            val sensorOrientation = config.characteristics.get(
                CameraCharacteristics.SENSOR_ORIENTATION
            ) ?: 0
            val previewAfMode = SingleFrameCaptureController
                .selectAutoFocusModeForStillCapture(
                    availableModes = config.characteristics.get(
                        CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
                    ),
                    source = FocusTargetSource.DEFAULT_CENTER
                )

            val physicalOwner = try {
                directAttachment.surfaceTexture.setDefaultBufferSize(
                    targetSize.width,
                    targetSize.height
                )
                resourceOwnerFactory.create(
                    directAttachment.surfaceTexture,
                    targetSize
                )
            } catch (createError: Exception) {
                if (createError is CancellationException) throw createError
                val kind = classifyPersistentOwnerConstructionFailure(createError)
                val failure = DirectCamera2Failure(kind = kind, cause = createError)
                throw DirectCamera2RouteException(failure)
            }

            val record = ActiveDirectOwner(
                generation = generation,
                surfaceTexture = directAttachment.surfaceTexture,
                owner = physicalOwner,
                streamSize = targetSize,
                characteristics = config.characteristics,
                previewAutoFocusMode = previewAfMode,
                onTerminal = directAttachment.onTerminal
            )

            val published = lock.withLock {
                if (requestedGeneration == generation &&
                    requestedSurfaceTexture === directAttachment.surfaceTexture &&
                    activeOwner == null
                ) {
                    activeOwner = record
                    true
                } else {
                    false
                }
            }
            if (!published) {
                beginOwnerClose(physicalOwner)
                throw CancellationException("Direct preview attachment was superseded")
            }

            try {
                physicalOwner.markOpenRequested()
                val cameraDevice = awaitCameraOpen(
                    manager = config.manager,
                    cameraId = config.cameraId,
                    record = record
                )

                physicalOwner.markSessionRequested()
                val readerSurface = physicalOwner.getReader()?.surface
                    ?: throw IllegalStateException("ImageReader surface is null")
                val session = awaitSessionConfiguration(
                    device = cameraDevice,
                    readerSurface = readerSurface,
                    record = record
                )
                try {
                    startPreviewRepeating(cameraDevice, session, record)
                } catch (repeatError: Exception) {
                    if (repeatError is CancellationException) throw repeatError
                    val kind = if (repeatError is SecurityException) {
                        DirectCamera2FailureKind.PERMISSION_OR_SECURITY
                    } else {
                        DirectCamera2FailureKind.CAMERA_SESSION
                    }
                    val failure = DirectCamera2Failure(kind = kind, cause = repeatError)
                    terminateRecord(record, failure)
                    throw DirectCamera2RouteException(failure)
                }

                val becameReady = lock.withLock {
                    if (activeOwner === record && requestedGeneration == generation) {
                        mutablePreviewConfiguration.value = DirectPreviewConfiguration(
                            generation = generation,
                            streamSize = config.selectedSize,
                            sensorOrientationDegrees = sensorOrientation
                        )
                        true
                    } else {
                        false
                    }
                }
                if (!becameReady) {
                    beginOwnerClose(physicalOwner)
                    throw CancellationException("Direct preview became stale before readiness")
                }
            } catch (error: Throwable) {
                if (error !is DirectCamera2RouteException) {
                    closeRecord(record, notifyTerminal = false)
                }
                throw error
            }
        }
    }

    override suspend fun unbind(generation: Long) = Unit

    override suspend fun rebind(generation: Long) = Unit

    override suspend fun detach(generation: Long) {
        withContext(Dispatchers.Default) {
            invalidate(generation)
            awaitClosingOwners()
        }
    }

    override suspend fun captureFrame(
        context: Context,
        tracker: CaptureTimingTracker?,
        diagnosticsTracker: FocusLensDiagnosticsTracker?,
        focusTarget: FocusMeteringTarget
    ): CopiedImageFrame {
        val record = lock.withLock { activeOwner }
            ?: throw IllegalStateException("Direct camera preview is not attached")
        val device = record.owner.getDevice()
            ?: throw IllegalStateException("CameraDevice is null")
        val session = record.owner.getSession()
            ?: throw IllegalStateException("CameraCaptureSession is null")
        val reader = record.owner.getReader()
            ?: throw IllegalStateException("ImageReader is null")

        var captureFailure: Throwable? = null
        try {
            val stillSurface = record.owner.submissionCloseGate.withOpenSubmission {
                reader.surface
            }
            val engine = Camera2StillCaptureEngine(
                device = device,
                session = session,
                handler = record.owner.handler,
                characteristics = record.characteristics,
                selectedSize = record.streamSize,
                repeatingSurface = record.owner.previewSurface,
                stillSurface = stillSurface,
                imageReader = reader,
                imageRouter = record.owner.imageRouter,
                submissionGate = record.owner.submissionCloseGate
            )
            return engine.executeStillCapture(
                tracker = tracker,
                diagnosticsTracker = diagnosticsTracker,
                focusTarget = focusTarget
            )
        } catch (error: Throwable) {
            captureFailure = error
            if (error is Exception && isTerminalCaptureFailure(error)) {
                val kind = if (error is SecurityException) {
                    DirectCamera2FailureKind.PERMISSION_OR_SECURITY
                } else {
                    DirectCamera2FailureKind.TERMINAL_CAPTURE_OR_REPEATING
                }
                val failure = DirectCamera2Failure(kind = kind, cause = error)
                terminateRecord(record, failure)
            }
            throw error
        } finally {
            val remainsCurrent = lock.withLock { activeOwner === record }
            if (remainsCurrent &&
                (captureFailure == null || !isTerminalCaptureFailure(captureFailure))
            ) {
                try {
                    startPreviewRepeating(device, session, record)
                } catch (restoreError: Exception) {
                    if (restoreError !is CancellationException) {
                        val restoreKind = if (restoreError is SecurityException) {
                            DirectCamera2FailureKind.PERMISSION_OR_SECURITY
                        } else {
                            DirectCamera2FailureKind.TERMINAL_CAPTURE_OR_REPEATING
                        }
                        val failure = DirectCamera2Failure(
                            kind = restoreKind,
                            cause = restoreError
                        )
                        terminateRecord(record, failure)
                        if (captureFailure == null) {
                            throw restoreError
                        }
                    }
                }
            }
        }
    }

    override fun resolveOutputRotationDegrees(context: Context): Int {
        val characteristics = lock.withLock { activeOwner?.characteristics }
            ?: run {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                manager.getCameraCharacteristics(resolvePrimaryCameraId(manager))
            }
        val sensorOrientation = characteristics.get(
            CameraCharacteristics.SENSOR_ORIENTATION
        ) ?: 0
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val displayRotation = displayRotationDegrees(context)
        return if (lensFacing == CameraMetadata.LENS_FACING_FRONT) {
            (sensorOrientation + displayRotation) % 360
        } else {
            (sensorOrientation - displayRotation + 360) % 360
        }
    }

    override fun resolveSensorOrientation(context: Context): Int {
        val current = mutablePreviewConfiguration.value
        if (current != null) {
            return current.sensorOrientationDegrees
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return manager.getCameraCharacteristics(resolvePrimaryCameraId(manager))
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    }

    private suspend fun awaitCameraOpen(
        manager: CameraManager,
        cameraId: String,
        record: ActiveDirectOwner
    ): CameraDevice = suspendCancellableCoroutine { continuation ->
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val accepted = record.owner.registerDevice(camera)
                    if (accepted && isCurrent(record)) {
                        if (continuation.isActive) continuation.resume(camera)
                    } else {
                        if (accepted) beginOwnerClose(record.owner)
                        if (continuation.isActive) {
                            continuation.cancel(
                                CancellationException("Camera open callback was stale")
                            )
                        }
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    val cause = IllegalStateException("Camera device was disconnected")
                    val failure = DirectCamera2Failure(
                        kind = DirectCamera2FailureKind.CAMERA_DEVICE_OR_OPEN,
                        cause = cause
                    )
                    executeTerminalFailureCallback(
                        publishTerminal = { terminateRecord(record, failure) },
                        registerFailureResource = {
                            record.owner.registerOpenFailure(camera)
                        },
                        completeContinuation = {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    DirectCamera2RouteException(failure)
                                )
                            }
                        }
                    )
                }

                override fun onError(camera: CameraDevice, errorCode: Int) {
                    val cause = CameraAccessException(
                        CameraAccessException.CAMERA_ERROR,
                        "Camera device error: $errorCode"
                    )
                    val failure = DirectCamera2Failure(
                        kind = DirectCamera2FailureKind.CAMERA_DEVICE_OR_OPEN,
                        cause = cause
                    )
                    executeTerminalFailureCallback(
                        publishTerminal = { terminateRecord(record, failure) },
                        registerFailureResource = {
                            record.owner.registerOpenFailure(camera)
                        },
                        completeContinuation = {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    DirectCamera2RouteException(failure)
                                )
                            }
                        }
                    )
                }

                override fun onClosed(camera: CameraDevice) {
                    record.owner.registerDeviceClosed(camera)
                }
            }, record.owner.handler)
        } catch (error: SecurityException) {
            record.owner.markOpenSubmissionFailed()
            val failure = DirectCamera2Failure(
                kind = DirectCamera2FailureKind.PERMISSION_OR_SECURITY,
                cause = error
            )
            terminateRecord(record, failure)
            if (continuation.isActive) {
                continuation.resumeWithException(DirectCamera2RouteException(failure))
            }
        } catch (error: Exception) {
            record.owner.markOpenSubmissionFailed()
            val failure = DirectCamera2Failure(
                kind = DirectCamera2FailureKind.CAMERA_DEVICE_OR_OPEN,
                cause = error
            )
            terminateRecord(record, failure)
            if (continuation.isActive) {
                continuation.resumeWithException(DirectCamera2RouteException(failure))
            }
        } catch (error: Throwable) {
            record.owner.markOpenSubmissionFailed()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private suspend fun awaitSessionConfiguration(
        device: CameraDevice,
        readerSurface: Surface,
        record: ActiveDirectOwner
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        try {
            sessionCreator.createCaptureSession(
                device = device,
                surfaces = listOf(record.owner.previewSurface, readerSurface),
                callback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        val accepted = record.owner.registerSession(session)
                        if (accepted && isCurrent(record)) {
                            if (continuation.isActive) continuation.resume(session)
                        } else {
                            if (accepted) beginOwnerClose(record.owner)
                            if (continuation.isActive) {
                                continuation.cancel(
                                    CancellationException("Session callback was stale")
                                )
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        val cause = IllegalStateException("Camera session configuration failed")
                        val failure = DirectCamera2Failure(
                            kind = DirectCamera2FailureKind.CAMERA_SESSION,
                            cause = cause
                        )
                        executeTerminalFailureCallback(
                            publishTerminal = { terminateRecord(record, failure) },
                            registerFailureResource = {
                                record.owner.registerSessionFailure(session)
                            },
                            completeContinuation = {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        DirectCamera2RouteException(failure)
                                    )
                                }
                            }
                        )
                    }

                    override fun onActive(session: CameraCaptureSession) {
                        if (isCurrent(record)) record.owner.imageRouter.onSessionActive()
                    }

                    override fun onReady(session: CameraCaptureSession) {
                        if (isCurrent(record)) record.owner.imageRouter.onSessionReady()
                    }
                },
                handler = record.owner.handler
            )
        } catch (error: SecurityException) {
            record.owner.markSessionSubmissionFailed()
            val failure = DirectCamera2Failure(
                kind = DirectCamera2FailureKind.PERMISSION_OR_SECURITY,
                cause = error
            )
            terminateRecord(record, failure)
            if (continuation.isActive) {
                continuation.resumeWithException(DirectCamera2RouteException(failure))
            }
        } catch (error: Exception) {
            record.owner.markSessionSubmissionFailed()
            val failure = DirectCamera2Failure(
                kind = DirectCamera2FailureKind.CAMERA_SESSION,
                cause = error
            )
            terminateRecord(record, failure)
            if (continuation.isActive) {
                continuation.resumeWithException(DirectCamera2RouteException(failure))
            }
        } catch (error: Throwable) {
            record.owner.markSessionSubmissionFailed()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun startPreviewRepeating(
        device: CameraDevice,
        session: CameraCaptureSession,
        record: ActiveDirectOwner
    ) {
        check(isCurrent(record)) { "Direct preview owner is no longer current" }
        record.owner.submissionCloseGate.withOpenSubmission {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(record.owner.previewSurface)
                record.previewAutoFocusMode?.let {
                    set(CaptureRequest.CONTROL_AF_MODE, it)
                }
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()
            session.setRepeatingRequest(request, null, record.owner.handler)
        }
    }

    private fun isCurrent(record: ActiveDirectOwner): Boolean {
        return lock.withLock {
            activeOwner === record && requestedGeneration == record.generation
        }
    }

    private fun closeRecord(record: ActiveDirectOwner, notifyTerminal: Boolean) {
        val callback = lock.withLock {
            if (activeOwner !== record) {
                null
            } else {
                activeOwner = null
                requestedGeneration = null
                requestedSurfaceTexture = null
                mutablePreviewConfiguration.value = null
                record.onTerminal.takeIf { notifyTerminal }
            }
        }
        beginOwnerClose(record.owner)
        callback?.invoke(
            DirectCamera2Failure(
                kind = DirectCamera2FailureKind.LIFECYCLE_OR_SUPERSESSION,
                cause = IllegalStateException("Direct Camera2 owner closed")
            )
        )
    }

    private fun terminateRecord(
        record: ActiveDirectOwner,
        failure: DirectCamera2Failure
    ): DirectTerminalCloseAction<PersistentCamera2ResourceOwner>? {
        val action = prepareTerminalCloseAction(
            lock = lock,
            closingOwners = closingOwners,
            owner = record.owner,
            currentIdentity = activeOwner,
            expectedIdentity = record,
            failure = failure,
            onTerminal = record.onTerminal,
            closeOwner = PersistentCamera2ResourceOwner::close,
            clearState = {
                activeOwner = null
                requestedGeneration = null
                requestedSurfaceTexture = null
                mutablePreviewConfiguration.value = null
            }
        )
        action?.execute()
        return action
    }

    private fun isTerminalCaptureFailure(error: Throwable): Boolean {
        return error is SecurityException ||
            error is CameraAccessException ||
            error is PersistentCamera2OwnerClosedException
    }

    private fun beginOwnerClose(owner: PersistentCamera2ResourceOwner) {
        beginOwnerCloseOutsideStateLock(
            stateLock = lock,
            closingOwners = closingOwners,
            owner = owner,
            closeOwner = PersistentCamera2ResourceOwner::close
        )
    }

    private suspend fun awaitClosingOwners() {
        while (true) {
            val owners = lock.withLock { closingOwners.toList() }
            if (owners.isEmpty()) return
            owners.forEach { owner ->
                owner.awaitClosed()
                lock.withLock { closingOwners.remove(owner) }
            }
        }
    }

    private fun resolvePrimaryCameraId(manager: CameraManager): String {
        return selectRequiredBackCameraId(manager.cameraIdList.toList()) { cameraId ->
            manager.getCameraCharacteristics(cameraId).get(
                CameraCharacteristics.LENS_FACING
            )
        }
    }

    private fun displayRotationDegrees(context: Context): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        return when (windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}
