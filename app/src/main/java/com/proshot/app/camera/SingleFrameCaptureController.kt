package com.proshot.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

private const val TAG = "SingleFrameCaptureController"
private const val CAPTURE_TIMEOUT_MS = 8_000L
private const val AF_TRIGGER_MIN_FRAMES = 2
private const val AF_PASSIVE_MIN_FRAMES = 8

/**
 * Represent standard image dimension in a pure Kotlin format to enable robust
 * JVM unit testing without depending on Android framework stubs.
 */
data class CaptureSize(val width: Int, val height: Int)

/**
 * Holds key metadata and plane byte counts for a captured frame, facilitating
 * presentation on visual screens and robust offline unit testing.
 */
data class CapturedFrameSummary(
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val formatName: String,
    val yPlaneSize: Int,
    val uPlaneSize: Int,
    val vPlaneSize: Int
) {
    /**
     * Formats the summary into a clean, concise, single-line representation.
     */
    fun getFormattedSummary(): String {
        return "Res: ${width}x${height} | Time: ${timestampNs}ns | Format: $formatName | Planes: [Y: $yPlaneSize, U: $uPlaneSize, V: $vPlaneSize] bytes"
    }
}

/**
 * Controller that coordinates Camera2 physical resources to capture a single YUV_420_888
 * frame and safely copy it to heap memory. Runs entirely on background threads.
 */
@Singleton
class SingleFrameCaptureController @Inject internal constructor(
    private val resourceOwnerFactory: Camera2CaptureResourceOwnerFactory,
    private val sessionCreator: Camera2CaptureSessionCreator
) : CameraFrameSource {

    override suspend fun captureFrame(
        context: Context,
        tracker: CaptureTimingTracker?,
        diagnosticsTracker: FocusLensDiagnosticsTracker?,
        focusTarget: FocusMeteringTarget
    ): CopiedImageFrame {
        return captureSingleFrame(context, tracker, diagnosticsTracker, focusTarget)
    }

    companion object {
        /**
         * Extracts plane byte array sizes from a copied heap frame and returns a formatted summary.
         */
        @JvmStatic
        fun summarizeFrame(frame: CopiedImageFrame): CapturedFrameSummary {
            val ySize = frame.planes.getOrNull(0)?.data?.size ?: 0
            val uSize = frame.planes.getOrNull(1)?.data?.size ?: 0
            val vSize = frame.planes.getOrNull(2)?.data?.size ?: 0
            return CapturedFrameSummary(
                width = frame.width,
                height = frame.height,
                timestampNs = frame.timestamp,
                formatName = "YUV_420_888",
                yPlaneSize = ySize,
                uPlaneSize = uSize,
                vPlaneSize = vSize
            )
        }

        /**
         * Resolves the [CaptureSize] from the provided list that is closest in area to 1920x1080.
         * Guaranteed to return a non-null, stable fallback if the list is empty.
         */
        @JvmStatic
        fun findClosestStableSize(sizes: List<CaptureSize>): CaptureSize {
            val targetArea = 1920 * 1080
            return sizes.minByOrNull { size ->
                abs((size.width * size.height) - targetArea)
            } ?: CaptureSize(1920, 1080)
        }

        /**
         * Selects the autofocus mode to use for the one-shot still-capture lock sequence.
         */
        @JvmStatic
        fun selectAutoFocusModeForStillCapture(availableModes: IntArray?, source: FocusTargetSource): Int? {
            if (availableModes == null) {
                return null
            }
            val modes = availableModes.toSet()
            return when (source) {
                FocusTargetSource.DEFAULT_CENTER -> {
                    when {
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes ->
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        CaptureRequest.CONTROL_AF_MODE_AUTO in modes ->
                            CaptureRequest.CONTROL_AF_MODE_AUTO
                        else -> null
                    }
                }
                FocusTargetSource.USER_TAP -> {
                    when {
                        CaptureRequest.CONTROL_AF_MODE_AUTO in modes ->
                            CaptureRequest.CONTROL_AF_MODE_AUTO
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes ->
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        else -> null
                    }
                }
            }
        }

        /**
         * Returns true if the selected autofocus mode requires an explicit trigger start command.
         */
        @JvmStatic
        fun shouldTriggerAutoFocus(afMode: Int?): Boolean {
            return afMode == CaptureRequest.CONTROL_AF_MODE_AUTO
        }

        /**
         * Returns true when an AF state is safe to leave the autofocus wait loop.
         */
        @JvmStatic
        fun isAutoFocusReadyForStillCapture(frameCount: Int, afState: Int?, afMode: Int?): Boolean {
            if (afMode == null) {
                return true
            }
            return when (afMode) {
                CaptureRequest.CONTROL_AF_MODE_AUTO -> {
                    if (frameCount < AF_TRIGGER_MIN_FRAMES) return false
                    afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                }
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> {
                    if (frameCount < AF_PASSIVE_MIN_FRAMES) return false
                    afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                }
                else -> false
            }
        }

    }

    /**
     * Resolves the clockwise pixel rotation needed for saved output to match device orientation.
     */
    override fun resolveOutputRotationDegrees(context: Context): Int {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: throw IllegalStateException("CameraManager is not available")
        val cameraId = resolvePrimaryCameraId(manager)
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val displayRotationDegrees = displayRotationDegrees(context)

        return if (lensFacing == CameraMetadata.LENS_FACING_FRONT) {
            (sensorOrientation + displayRotationDegrees) % 360
        } else {
            (sensorOrientation - displayRotationDegrees + 360) % 360
        }
    }

    /** Returns the primary back camera's physical sensor orientation in degrees. */
    override fun resolveSensorOrientation(context: Context): Int {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: throw IllegalStateException("CameraManager is not available")
        val cameraId = resolvePrimaryCameraId(manager)
        return manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    }

    private fun resolvePrimaryCameraId(manager: CameraManager): String {
        return manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
          ?: throw IllegalStateException("No physical camera detected on this device")
    }

    private fun displayRotationDegrees(context: Context): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * Captures a single YUV_420_888 frame from the primary back camera and returns a safe heap-allocated [CopiedImageFrame].
     *
     * Ensures known CameraDevice, CameraCaptureSession, and ImageReader resources
     * are closed on success, failure, and cancellation while pending callbacks use
     * the resource owner's bounded callback-thread terminal policy.
     *
     * @throws SecurityException if [android.Manifest.permission.CAMERA] is not held by the caller.
     * @throws IllegalStateException if no camera is available or capture resources cannot be initialized.
     */
    @SuppressLint("MissingPermission")
    suspend fun captureSingleFrame(
        context: Context,
        tracker: CaptureTimingTracker? = null,
        diagnosticsTracker: FocusLensDiagnosticsTracker? = null,
        focusTarget: FocusMeteringTarget = FocusMeteringTarget.center()
    ): CopiedImageFrame = withContext(Dispatchers.Default) {
        withTimeout(CAPTURE_TIMEOUT_MS) {
            captureSingleFrameOnCurrentThread(context, tracker, diagnosticsTracker, focusTarget)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureSingleFrameOnCurrentThread(
        context: Context,
        tracker: CaptureTimingTracker? = null,
        diagnosticsTracker: FocusLensDiagnosticsTracker? = null,
        focusTarget: FocusMeteringTarget = FocusMeteringTarget.center()
    ): CopiedImageFrame {
        val totalStart = tracker?.let { System.nanoTime() }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: throw IllegalStateException("CameraManager is not available")

        // 1. Resolve primary physical back camera
        val cameraId = resolvePrimaryCameraId(manager)
        if (diagnosticsTracker != null) {
            diagnosticsTracker.clearAfWaitOutcome()
            diagnosticsTracker.logicalCameraId = cameraId
            diagnosticsTracker.afWaitExitReason = "NOT_RUN"
        }

        val characteristics = manager.getCameraCharacteristics(cameraId)
        val availableAutoFocusModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        if (availableAutoFocusModes == null) {
            Log.w(TAG, "CONTROL_AF_AVAILABLE_MODES characteristic is null; assuming fixed-focus")
        }
        val maxRegionsAfRaw = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
        val maxRegionsAeRaw = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)
        if (maxRegionsAfRaw == null) {
            Log.w(TAG, "CONTROL_MAX_REGIONS_AF is null; treating as zero-region device")
        }
        if (maxRegionsAeRaw == null) {
            Log.w(TAG, "CONTROL_MAX_REGIONS_AE is null; treating as zero-region device")
        }
        val maxRegionsAf = maxRegionsAfRaw ?: 0
        val maxRegionsAe = maxRegionsAeRaw ?: 0
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val effectiveFocusPolicy = resolveEffectiveFocusTargetPolicy(
            requestedSource = focusTarget.source,
            maxAfRegions = maxRegionsAf,
            activeArrayAvailable = activeArray != null
        )
        val autoFocusMode = selectAutoFocusModeForStillCapture(
            availableModes = availableAutoFocusModes,
            source = effectiveFocusPolicy.effectiveSource
        )

        if (diagnosticsTracker != null) {
            diagnosticsTracker.physicalCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
            diagnosticsTracker.focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()
            diagnosticsTracker.minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            diagnosticsTracker.hyperfocalDistance = characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
            diagnosticsTracker.availableAfModes =
                characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList()?.mapNotNull {
                    FocusLensDiagnosticsHelper.mapAfMode(it)
            }
            diagnosticsTracker.selectedAfMode = FocusLensDiagnosticsHelper.mapAfMode(autoFocusMode)
            diagnosticsTracker.focusTargetSource = effectiveFocusPolicy.requestedSource.name
            diagnosticsTracker.effectiveFocusTargetSource = effectiveFocusPolicy.effectiveSource.name
            diagnosticsTracker.focusTargetFallback = effectiveFocusPolicy.fallbackReason.name
            diagnosticsTracker.normalizedTargetX = focusTarget.x
            diagnosticsTracker.normalizedTargetY = focusTarget.y
            diagnosticsTracker.normalizedAfSize = focusTarget.afSize
            diagnosticsTracker.normalizedAeSize = focusTarget.aeSize
            diagnosticsTracker.afMaxRegions = maxRegionsAf
            diagnosticsTracker.aeMaxRegions = maxRegionsAe
        }

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()

        // Match the closest supported size to 1080p for single-frame stability.
        val mappedSizes = yuvSizes.map { CaptureSize(it.width, it.height) }
        val matchedSize = findClosestStableSize(mappedSizes)
        val targetSize = Size(matchedSize.width, matchedSize.height)

        // Map normalized focus target to metering rectangles relative to active array.
        val pureActive = activeArray?.let {
            PureRect(it.left, it.top, it.right, it.bottom)
        }

        val cropRegion = if (pureActive != null) {
            ActiveArrayCropCalculator.calculateCenterCrop(pureActive, matchedSize)
        } else {
            null
        }

        if (diagnosticsTracker != null) {
            diagnosticsTracker.meteringCropRegion = if (cropRegion != null) {
                "Rect(${cropRegion.left}, ${cropRegion.top}, ${cropRegion.right - cropRegion.left}x${cropRegion.bottom - cropRegion.top})"
            } else {
                if (activeArray == null) "NONE_ACTIVE_ARRAY_NULL" else "NONE"
            }
        }

        val afRegionsToApply: Array<android.hardware.camera2.params.MeteringRectangle>? =
            if (activeArray == null) {
                if (diagnosticsTracker != null) {
                    diagnosticsTracker.afRegionApplied = "NONE_ACTIVE_ARRAY_NULL"
                }
                null
            } else if (maxRegionsAf <= 0) {
                if (diagnosticsTracker != null) {
                    diagnosticsTracker.afRegionApplied = "NONE_UNSUPPORTED"
                }
                null
            } else {
                val mapped = FocusMeteringCoordinateMapper.mapToActiveArray(
                    target = focusTarget,
                    size = focusTarget.afSize,
                    activeArray = pureActive!!,
                    cropRegion = cropRegion
                )
                val rect = android.hardware.camera2.params.MeteringRectangle(
                    android.graphics.Rect(mapped.left, mapped.top, mapped.right, mapped.bottom),
                    focusTarget.afWeight
                )
                if (diagnosticsTracker != null) {
                    diagnosticsTracker.afRegionApplied = "Rect(${mapped.left}, ${mapped.top}, ${mapped.right - mapped.left}x${mapped.bottom - mapped.top})"
                }
                arrayOf(rect)
            }

        val aeRegionsToApply: Array<android.hardware.camera2.params.MeteringRectangle>? =
            if (activeArray == null) {
                if (diagnosticsTracker != null) {
                    diagnosticsTracker.aeRegionApplied = "NONE_ACTIVE_ARRAY_NULL"
                }
                null
            } else if (maxRegionsAe <= 0) {
                if (diagnosticsTracker != null) {
                    diagnosticsTracker.aeRegionApplied = "NONE_UNSUPPORTED"
                }
                null
            } else {
                val mapped = FocusMeteringCoordinateMapper.mapToActiveArray(
                    target = focusTarget,
                    size = focusTarget.aeSize,
                    activeArray = pureActive!!,
                    cropRegion = cropRegion
                )
                val rect = android.hardware.camera2.params.MeteringRectangle(
                    android.graphics.Rect(mapped.left, mapped.top, mapped.right, mapped.bottom),
                    focusTarget.aeWeight
                )
                if (diagnosticsTracker != null) {
                    diagnosticsTracker.aeRegionApplied = "Rect(${mapped.left}, ${mapped.top}, ${mapped.right - mapped.left}x${mapped.bottom - mapped.top})"
                }
                arrayOf(rect)
            }

        Log.d(TAG, "Selected YUV_420_888 target capture size: ${targetSize.width}x${targetSize.height}")

        val resourceOwner = resourceOwnerFactory.create(targetSize)
        val handler = resourceOwner.handler
        val reader = resourceOwner.getReader() ?: run {
            resourceOwner.close()
            throw IllegalStateException("ImageReader is null")
        }
        val imageRouter = Camera2ImageReaderRouter()

        return try {
            reader.setOnImageAvailableListener(imageRouter, handler)
            // 3. Open CameraDevice asynchronously
            val openStart = tracker?.let { System.nanoTime() }
            resourceOwner.markOpenRequested()

            val cameraDevice = suspendCancellableCoroutine<CameraDevice> { cont ->
                try {
                    manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            Log.d(TAG, "CameraDevice opened successfully: ${camera.id}")
                            if (resourceOwner.registerDevice(camera)) {
                                if (cont.isActive) {
                                    cont.resume(camera)
                                }
                            } else if (cont.isActive) {
                                cont.cancel(CancellationException("Camera open cancelled"))
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            Log.w(TAG, "CameraDevice disconnected: ${camera.id}")
                            resourceOwner.registerOpenFailure(camera)
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    IllegalStateException("Camera device was disconnected")
                                )
                            }
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            Log.e(TAG, "CameraDevice open error: ${camera.id}, code: $error")
                            resourceOwner.registerOpenFailure(camera)
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    RuntimeException(
                                        "Failed to open camera device. Error code: $error"
                                    )
                                )
                            }
                        }
                    }, handler)
                } catch (error: Exception) {
                    resourceOwner.markOpenSubmissionFailed()
                    if (cont.isActive) {
                        cont.resumeWithException(error)
                    }
                }

                cont.invokeOnCancellation {
                    Log.d(TAG, "Camera open cancelled")
                }
            }
            if (openStart != null) {
                tracker?.cameraOpenMs = (System.nanoTime() - openStart) / 1_000_000L
            }

            // 5. Create CameraCaptureSession and wait for it to configure
            val configStart = tracker?.let { System.nanoTime() }
            resourceOwner.markSessionRequested()
            val sessionReadyGate = CameraSessionReadyGate()

            val captureSession = suspendCancellableCoroutine<CameraCaptureSession> { cont ->
                val readerSurface = reader.surface
                try {
                    sessionCreator.createCaptureSession(
                        device = cameraDevice,
                        surfaces = listOf(readerSurface),
                        callback = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                Log.d(TAG, "CameraCaptureSession configured successfully")
                                if (resourceOwner.registerSession(session)) {
                                    if (cont.isActive) {
                                        cont.resume(session)
                                    }
                                } else if (cont.isActive) {
                                    cont.cancel(
                                        CancellationException("Session configuration cancelled")
                                    )
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "CameraCaptureSession configuration failed")
                                resourceOwner.registerSessionFailure(session)
                                if (cont.isActive) {
                                    cont.resumeWithException(
                                        RuntimeException("Failed to configure capture session")
                                    )
                                }
                            }

                            override fun onActive(session: CameraCaptureSession) {
                                imageRouter.onSessionActive()
                            }

                            override fun onReady(session: CameraCaptureSession) {
                                imageRouter.onSessionReady()
                            }
                        },
                        handler = handler
                    )
                } catch (error: Exception) {
                    resourceOwner.markSessionSubmissionFailed()
                    if (cont.isActive) {
                        cont.resumeWithException(error)
                    }
                }

                cont.invokeOnCancellation {
                    Log.d(TAG, "Session configuration cancelled")
                }
            }
            if (configStart != null) {
                tracker?.sessionConfigMs = (System.nanoTime() - configStart) / 1_000_000L
            }

            val engine = Camera2StillCaptureEngine(
                device = cameraDevice,
                session = captureSession,
                handler = handler,
                characteristics = characteristics,
                selectedSize = Size(targetSize.width, targetSize.height),
                repeatingSurface = reader.surface,
                stillSurface = reader.surface,
                imageReader = reader,
                imageRouter = imageRouter,
                submissionGate = AlwaysOpenCamera2RequestSubmissionGate
            )

            engine.executeStillCapture(
                tracker = tracker,
                diagnosticsTracker = diagnosticsTracker,
                focusTarget = focusTarget,
                sessionReadyGate = sessionReadyGate
            )
        } finally {
            try {
                reader.setOnImageAvailableListener(null, null)
            } catch (_: Exception) {
                // Resource owner close remains authoritative.
            }
            imageRouter.close()
            resourceOwner.close()
        }
    }
}
