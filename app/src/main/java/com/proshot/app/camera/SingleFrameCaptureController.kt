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
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

private const val TAG = "SingleFrameCaptureController"
private const val CAPTURE_TIMEOUT_MS = 8_000L
private const val AE_WARMUP_MIN_FRAMES = 3
private const val AE_WARMUP_MAX_FRAMES = 12

// Minimum qualifying repeating results after the AUTO trigger result has
// established a causal boundary before FOCUSED_LOCKED is accepted.
private const val AF_TRIGGER_MIN_FRAMES = 2

// Minimum callbacks before PASSIVE_FOCUSED or FOCUSED_LOCKED is accepted
// in CONTINUOUS_PICTURE mode. In a fresh Camera2 session the HAL may carry
// PASSIVE_FOCUSED from the prior CameraX session's lens position. A gate
// of 8 frames (~267 ms at 30 fps) exceeds the typical Qualcomm CDAF scan
// initialization window and ensures the HAL has run at least one real scan
// cycle on the current scene before the result is trusted.
private const val AF_PASSIVE_MIN_FRAMES = 8

private const val AF_LOCK_MAX_FRAMES = 30

private data class TimestampCorrelatedCopiedFrame(
    val frame: CopiedImageFrame,
    val resultTimestamp: Long
)

internal enum class FocusTargetFallbackReason {
    NONE,
    AF_REGIONS_UNSUPPORTED,
    ACTIVE_ARRAY_UNAVAILABLE
}

internal data class EffectiveFocusTargetPolicy(
    val requestedSource: FocusTargetSource,
    val effectiveSource: FocusTargetSource,
    val fallbackReason: FocusTargetFallbackReason
)

internal fun resolveEffectiveFocusTargetPolicy(
    requestedSource: FocusTargetSource,
    maxAfRegions: Int,
    activeArrayAvailable: Boolean
): EffectiveFocusTargetPolicy {
    if (requestedSource != FocusTargetSource.USER_TAP) {
        return EffectiveFocusTargetPolicy(
            requestedSource = requestedSource,
            effectiveSource = requestedSource,
            fallbackReason = FocusTargetFallbackReason.NONE
        )
    }
    val fallbackReason = when {
        !activeArrayAvailable -> FocusTargetFallbackReason.ACTIVE_ARRAY_UNAVAILABLE
        maxAfRegions <= 0 -> FocusTargetFallbackReason.AF_REGIONS_UNSUPPORTED
        else -> FocusTargetFallbackReason.NONE
    }
    return EffectiveFocusTargetPolicy(
        requestedSource = requestedSource,
        effectiveSource = if (fallbackReason == FocusTargetFallbackReason.NONE) {
            requestedSource
        } else {
            FocusTargetSource.DEFAULT_CENTER
        },
        fallbackReason = fallbackReason
    )
}

internal enum class AutoFocusWaitOutcome {
    FOCUSED,
    FRAME_CAP_TIMEOUT,
    TRIGGER_FAILED,
    TRIGGER_ABORTED,
    TRIGGER_SUBMISSION_FAILED
}

internal class AutoFocusWaitPolicy(private val afMode: Int) {
    var triggerBoundaryObserved: Boolean = afMode != CaptureRequest.CONTROL_AF_MODE_AUTO
        private set
    var repeatingFrameCount: Int = 0
        private set
    var qualifyingRepeatingResultCount: Int = 0
        private set
    var outcome: AutoFocusWaitOutcome? = null
        private set

    fun onTriggerCompleted() {
        if (outcome == null && afMode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
            triggerBoundaryObserved = true
        }
    }

    fun onTriggerFailed(aborted: Boolean): AutoFocusWaitOutcome? {
        if (outcome != null || triggerBoundaryObserved) {
            return null
        }
        outcome = if (aborted) {
            AutoFocusWaitOutcome.TRIGGER_ABORTED
        } else {
            AutoFocusWaitOutcome.TRIGGER_FAILED
        }
        return outcome
    }

    fun onRepeatingCompleted(afState: Int?): AutoFocusWaitOutcome? {
        if (outcome != null || !triggerBoundaryObserved) {
            return null
        }
        repeatingFrameCount++
        qualifyingRepeatingResultCount++
        outcome = when {
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                qualifyingRepeatingResultCount,
                afState,
                afMode
            ) -> AutoFocusWaitOutcome.FOCUSED
            repeatingFrameCount >= AF_LOCK_MAX_FRAMES -> AutoFocusWaitOutcome.FRAME_CAP_TIMEOUT
            else -> null
        }
        return outcome
    }

    fun onRepeatingFailed(): AutoFocusWaitOutcome? {
        if (outcome != null || !triggerBoundaryObserved) {
            return null
        }
        repeatingFrameCount++
        if (repeatingFrameCount >= AF_LOCK_MAX_FRAMES) {
            outcome = AutoFocusWaitOutcome.FRAME_CAP_TIMEOUT
        }
        return outcome
    }
}

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
) {

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
    fun resolveOutputRotationDegrees(context: Context): Int {
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
    fun resolveSensorOrientation(context: Context): Int {
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
    @OptIn(ExperimentalCoroutinesApi::class)
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

    @OptIn(ExperimentalCoroutinesApi::class)
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

        return try {
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
                val readerSurface = resourceOwner.getReader()?.surface
                if (readerSurface == null) {
                    resourceOwner.markSessionSubmissionFailed()
                    cont.resumeWithException(IllegalStateException("ImageReader surface is null"))
                    return@suspendCancellableCoroutine
                }
                try {
                    sessionCreator.createCaptureSession(
                        device = cameraDevice,
                        surface = readerSurface,
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
                                sessionReadyGate.onActive()
                            }

                            override fun onReady(session: CameraCaptureSession) {
                                sessionReadyGate.onReady()
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

            val reader = resourceOwner.getReader() ?: throw IllegalStateException("ImageReader is null")

            val warmupStart = tracker?.let { System.nanoTime() }
            warmUpAutoExposure(
                device = cameraDevice,
                session = captureSession,
                reader = reader,
                handler = handler,
                autoFocusMode = autoFocusMode,
                diagnosticsTracker = diagnosticsTracker,
                afRegions = afRegionsToApply,
                aeRegions = aeRegionsToApply
            )
            if (warmupStart != null) {
                tracker?.aeWarmupMs = (System.nanoTime() - warmupStart) / 1_000_000L
            }

            // Only time and lock autofocus if camera has a triggerable AF mode
            val afStart = if (tracker != null && autoFocusMode != null) System.nanoTime() else null
            lockAutoFocusBeforeCapture(
                device = cameraDevice,
                session = captureSession,
                reader = reader,
                handler = handler,
                autoFocusMode = autoFocusMode,
                diagnosticsTracker = diagnosticsTracker,
                afRegions = afRegionsToApply,
                aeRegions = aeRegionsToApply,
                sessionReadyGate = sessionReadyGate
            )
            if (afStart != null) {
                tracker?.afWaitMs = (System.nanoTime() - afStart) / 1_000_000L
            }

            val stillStart = tracker?.let { System.nanoTime() }
            val requestTag = Any()

            var correlatorForCleanup: CaptureTimestampCorrelator<Image>? = null

            val correlatedFrame = try {
                suspendCancellableCoroutine<TimestampCorrelatedCopiedFrame> { cont ->
                    val requestCorrelator = CaptureTimestampCorrelator<Image>(
                        requestTag = requestTag,
                        timestampExtractor = { image -> image.timestamp },
                        releaser = { image ->
                            try {
                                image.close()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error closing candidate image", e)
                            }
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
                                        Log.e(TAG, "Failed to copy frame from matched image", failure)
                                        transferFailure = failure
                                    }

                                    try {
                                        image.close()
                                    } catch (closeFailure: Throwable) {
                                        Log.e(TAG, "Failed to close matched image", closeFailure)
                                        val copyFailure = transferFailure
                                        if (copyFailure == null) {
                                            transferFailure = closeFailure
                                        } else if (copyFailure !== closeFailure) {
                                            copyFailure.addSuppressed(closeFailure)
                                        }
                                    }

                                    val completedFrame = copiedFrame
                                    val terminalFailure = transferFailure
                                    if (terminalFailure != null) {
                                        cont.resumeWithException(terminalFailure)
                                    } else if (completedFrame != null) {
                                        Log.d(TAG, "Exact timestamp correlation matched: $sensorTs ns")
                                        cont.resume(
                                            TimestampCorrelatedCopiedFrame(completedFrame, sensorTs)
                                        )
                                    } else {
                                        cont.resumeWithException(
                                            IllegalStateException(
                                                "Matched image transfer produced neither a frame nor a failure"
                                            )
                                        )
                                    }
                                }
                                is CorrelationOutcome.Failure -> {
                                    Log.e(TAG, "Timestamp correlation failed", outcome.cause)
                                    cont.resumeWithException(outcome.cause)
                                }
                            }
                        }
                    )
                    correlatorForCleanup = requestCorrelator

                    reader.setOnImageAvailableListener({ imageReaderRef ->
                        while (true) {
                            val image = try {
                                imageReaderRef.acquireNextImage()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error acquiring next image from ImageReader", e)
                                requestCorrelator.onCandidateAcquisitionError(e)
                                null
                            } ?: break

                            requestCorrelator.onCandidateAvailable(image)
                        }
                    }, handler)

                    val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    builder.setTag(requestTag)
                    builder.addTarget(reader.surface)
                    autoFocusMode?.let { builder.set(CaptureRequest.CONTROL_AF_MODE, it) }
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(
                        CaptureRequest.CONTROL_CAPTURE_INTENT,
                        CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
                    )
                    afRegionsToApply?.let { builder.set(CaptureRequest.CONTROL_AF_REGIONS, it) }
                    aeRegionsToApply?.let { builder.set(CaptureRequest.CONTROL_AE_REGIONS, it) }

                    val stillCallback = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: android.hardware.camera2.TotalCaptureResult
                        ) {
                            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                            Log.d(TAG, "Still capture completed for sequence ${result.sequenceId}, sensor timestamp: $ts ns")
                            requestCorrelator.onCaptureCompleted(
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
                            Log.e(TAG, "Still capture failed for sequence ${failure.sequenceId}, reason: ${failure.reason}")
                            requestCorrelator.onCaptureFailed(
                                sequenceId = failure.sequenceId,
                                tag = request.tag
                            )
                        }

                        override fun onCaptureBufferLost(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            target: Surface,
                            frameNumber: Long
                        ) {
                            Log.e(TAG, "Still capture buffer lost for request tag ${request.tag}, frame: $frameNumber")
                            requestCorrelator.onCaptureBufferLost(
                                tag = request.tag,
                                frameNumber = frameNumber
                            )
                        }

                        override fun onCaptureSequenceCompleted(
                            session: CameraCaptureSession,
                            sequenceId: Int,
                            frameNumber: Long
                        ) {
                            Log.d(TAG, "Still capture sequence $sequenceId completed at frame $frameNumber")
                            requestCorrelator.onCaptureSequenceCompleted(sequenceId = sequenceId)
                        }

                        override fun onCaptureSequenceAborted(
                            session: CameraCaptureSession,
                            sequenceId: Int
                        ) {
                            Log.e(TAG, "Still capture sequence $sequenceId aborted")
                            requestCorrelator.onCaptureSequenceAborted(sequenceId = sequenceId)
                        }
                    }

                    try {
                        val sequenceId = captureSession.capture(builder.build(), stillCallback, handler)
                        requestCorrelator.registerSequenceId(sequenceId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to submit still capture request", e)
                        requestCorrelator.onSubmissionOrCopyFailed(e)
                    }

                    cont.invokeOnCancellation {
                        Log.d(TAG, "Single frame capture cancelled")
                        try {
                            reader.setOnImageAvailableListener(null, null)
                        } catch (_: Throwable) {}
                        requestCorrelator.close()
                    }
                }
            } finally {
                try {
                    reader.setOnImageAvailableListener(null, null)
                } catch (_: Throwable) {}
                correlatorForCleanup?.close()
            }

            val frame = correlatedFrame.frame
            if (diagnosticsTracker != null) {
                diagnosticsTracker.stillCaptureResultTimestamp = correlatedFrame.resultTimestamp
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
        } finally {
            resourceOwner.close()
        }
    }

    private suspend fun warmUpAutoExposure(
        device: CameraDevice,
        session: CameraCaptureSession,
        reader: ImageReader,
        handler: Handler,
        autoFocusMode: Int?,
        diagnosticsTracker: FocusLensDiagnosticsTracker? = null,
        afRegions: Array<android.hardware.camera2.params.MeteringRectangle>?,
        aeRegions: Array<android.hardware.camera2.params.MeteringRectangle>?
    ) {
        suspendCancellableCoroutine<Unit> { cont ->
            val isWarmupDone = AtomicBoolean(false)
            var frameCount = 0

            fun finishWarmup(timedOut: Boolean, aeState: Int?) {
                if (!isWarmupDone.compareAndSet(false, true)) {
                    return
                }
                if (diagnosticsTracker != null) {
                    diagnosticsTracker.aeWarmupExitState = FocusLensDiagnosticsHelper.mapAeState(aeState)
                        ?: if (timedOut) "NULL_TIMEOUT" else "NULL_CONVERGED"
                    diagnosticsTracker.aeWarmupFrameCount = frameCount
                }
                try {
                    session.stopRepeating()
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to stop AE warmup repeating request", e)
                }
                drainImageReader(reader)
                reader.setOnImageAvailableListener(null, null)

                if (timedOut) {
                    Log.w(TAG, "AE warmup hit frame cap of $AE_WARMUP_MAX_FRAMES frames (AE state=$aeState)")
                } else {
                    Log.d(TAG, "AE warmup completed successfully in $frameCount frames")
                }

                if (cont.isActive) {
                    cont.resume(Unit)
                }
            }

            reader.setOnImageAvailableListener({ imageReaderRef ->
                drainImageReader(imageReaderRef)
            }, handler)

            val warmupRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                autoFocusMode?.let { set(CaptureRequest.CONTROL_AF_MODE, it) }
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                afRegions?.let { set(CaptureRequest.CONTROL_AF_REGIONS, it) }
                aeRegions?.let { set(CaptureRequest.CONTROL_AE_REGIONS, it) }
            }

            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult
                ) {
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
                    Log.w(TAG, "AE warmup frame failed: ${failure.reason}")
                    frameCount++
                    if (frameCount >= AE_WARMUP_MAX_FRAMES) {
                        finishWarmup(timedOut = true, aeState = null)
                    }
                }
            }

            try {
                session.setRepeatingRequest(warmupRequest.build(), callback, handler)
            } catch (e: Exception) {
                reader.setOnImageAvailableListener(null, null)
                if (cont.isActive) {
                    cont.resumeWithException(e)
                }
            }

            cont.invokeOnCancellation {
                if (isWarmupDone.compareAndSet(false, true)) {
                    try {
                        session.stopRepeating()
                    } catch (e: Exception) {
                        Log.w(TAG, "Unable to stop cancelled AE warmup", e)
                    }
                    drainImageReader(reader)
                    reader.setOnImageAvailableListener(null, null)
                }
            }
        }
    }

    private suspend fun lockAutoFocusBeforeCapture(
        device: CameraDevice,
        session: CameraCaptureSession,
        reader: ImageReader,
        handler: Handler,
        autoFocusMode: Int?,
        diagnosticsTracker: FocusLensDiagnosticsTracker? = null,
        afRegions: Array<android.hardware.camera2.params.MeteringRectangle>?,
        aeRegions: Array<android.hardware.camera2.params.MeteringRectangle>?,
        sessionReadyGate: CameraSessionReadyGate
    ) {
        suspendCancellableCoroutine<Unit> { cont ->
            val isFocusBoundaryStarted = AtomicBoolean(false)
            val isFocusCompletionDelivered = AtomicBoolean(false)
            val triggerIssued = AtomicBoolean(false)
            val triggerSubmissionLock = Any()
            val policy = autoFocusMode?.let(::AutoFocusWaitPolicy)

            fun combineFailures(primary: Throwable?, additional: Throwable): Throwable {
                if (primary == null) {
                    return additional
                }
                if (primary !== additional) {
                    primary.addSuppressed(additional)
                }
                return primary
            }

            fun drainAndClearListener(primaryFailure: Throwable?): Throwable? {
                var terminalFailure = primaryFailure
                try {
                    drainImageReader(reader)
                } catch (cleanupFailure: Throwable) {
                    terminalFailure = combineFailures(terminalFailure, cleanupFailure)
                }
                try {
                    reader.setOnImageAvailableListener(null, null)
                } catch (cleanupFailure: Throwable) {
                    terminalFailure = combineFailures(terminalFailure, cleanupFailure)
                }
                return terminalFailure
            }

            fun logFocusWaitOutcome(
                outcome: AutoFocusWaitOutcome?,
                repeatingFrameCount: Int,
                failure: Throwable?
            ) {
                when (outcome) {
                    null -> {
                        Log.d(TAG, "Skipping AF lock because camera reports fixed-focus/no triggerable AF")
                    }
                    AutoFocusWaitOutcome.FOCUSED -> {
                        Log.d(
                            TAG,
                            "AF lock completed successfully in $repeatingFrameCount repeating frames"
                        )
                    }
                    AutoFocusWaitOutcome.FRAME_CAP_TIMEOUT -> {
                        Log.w(TAG, "AF lock hit frame cap of $AF_LOCK_MAX_FRAMES repeating frames")
                    }
                    else -> Log.w(TAG, "AF lock failed: $outcome", failure)
                }
            }

            fun completeFocusWaitAfterBoundary(
                outcome: AutoFocusWaitOutcome?,
                result: android.hardware.camera2.TotalCaptureResult?,
                requestProvenance: String,
                failure: Throwable?
            ) {
                if (!isFocusCompletionDelivered.compareAndSet(false, true)) {
                    return
                }

                sessionReadyGate.disarm()
                val completionFailure = drainAndClearListener(failure)
                val repeatingFrameCount = policy?.repeatingFrameCount ?: 0
                diagnosticsTracker?.publishAfWaitOutcome(
                    if (outcome == null) {
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
                    } else {
                        createFocusWaitDiagnosticSample(
                            result = result,
                            outcome = outcome,
                            repeatingFrameCount = repeatingFrameCount,
                            afTriggerIssued = triggerIssued.get(),
                            requestProvenance = requestProvenance
                        )
                    }
                )
                logFocusWaitOutcome(outcome, repeatingFrameCount, completionFailure)

                if (cont.isActive) {
                    if (completionFailure != null) {
                        cont.resumeWithException(completionFailure)
                    } else {
                        cont.resume(Unit)
                    }
                }
            }

            fun failFocusSetup(failure: Throwable) {
                if (!isFocusBoundaryStarted.compareAndSet(false, true) ||
                    !isFocusCompletionDelivered.compareAndSet(false, true)
                ) {
                    return
                }

                sessionReadyGate.disarm()
                var terminalFailure: Throwable = failure
                try {
                    session.stopRepeating()
                } catch (cleanupFailure: Throwable) {
                    terminalFailure = combineFailures(terminalFailure, cleanupFailure)
                }
                terminalFailure = drainAndClearListener(terminalFailure) ?: terminalFailure
                if (cont.isActive) {
                    cont.resumeWithException(terminalFailure)
                }
            }

            fun finishFocusWait(
                outcome: AutoFocusWaitOutcome?,
                result: android.hardware.camera2.TotalCaptureResult?,
                requestProvenance: String,
                failure: Throwable? = null
            ) {
                synchronized(triggerSubmissionLock) {
                    if (!isFocusBoundaryStarted.compareAndSet(false, true)) {
                        return
                    }

                    val armResult = sessionReadyGate.arm {
                        completeFocusWaitAfterBoundary(
                            outcome = outcome,
                            result = result,
                            requestProvenance = requestProvenance,
                            failure = failure
                        )
                    }
                    if (armResult == CameraSessionReadyArmResult.ALREADY_READY_AFTER_ACTIVITY) {
                        val completionFailure = if (autoFocusMode == null) {
                            failure
                        } else {
                            IllegalStateException(
                                "AF work reached an already-ready session boundary after submission"
                            ).also { invariantFailure ->
                                failure?.let { combineFailures(invariantFailure, it) }
                            }
                        }
                        completeFocusWaitAfterBoundary(
                            outcome = outcome,
                            result = result,
                            requestProvenance = requestProvenance,
                            failure = completionFailure
                        )
                        return
                    }
                    if (armResult != CameraSessionReadyArmResult.ARMED) {
                        val armFailure = IllegalStateException(
                            "Unable to arm Camera2 session-ready boundary: $armResult"
                        )
                        failure?.let { combineFailures(armFailure, it) }
                        completeFocusWaitAfterBoundary(
                            outcome = outcome,
                            result = result,
                            requestProvenance = requestProvenance,
                            failure = armFailure
                        )
                        return
                    }

                    if (isFocusCompletionDelivered.get()) {
                        sessionReadyGate.disarm()
                        return
                    }

                    try {
                        session.stopRepeating()
                    } catch (stopFailure: Throwable) {
                        sessionReadyGate.disarm()
                        failure?.let { combineFailures(stopFailure, it) }
                        completeFocusWaitAfterBoundary(
                            outcome = outcome,
                            result = result,
                            requestProvenance = requestProvenance,
                            failure = stopFailure
                        )
                    }
                }
            }

            reader.setOnImageAvailableListener({ imageReaderRef ->
                drainImageReader(imageReaderRef)
            }, handler)

            cont.invokeOnCancellation {
                synchronized(triggerSubmissionLock) {
                    if (isFocusCompletionDelivered.compareAndSet(false, true)) {
                        isFocusBoundaryStarted.set(true)
                        sessionReadyGate.disarm()
                        try {
                            session.stopRepeating()
                        } catch (e: Exception) {
                            Log.w(TAG, "Unable to stop cancelled AF lock", e)
                        }
                        try {
                            drainImageReader(reader)
                        } catch (cleanupFailure: Throwable) {
                            Log.w(TAG, "Unable to drain cancelled AF lock images", cleanupFailure)
                        }
                        try {
                            reader.setOnImageAvailableListener(null, null)
                        } catch (cleanupFailure: Throwable) {
                            Log.w(TAG, "Unable to clear cancelled AF lock listener", cleanupFailure)
                        }
                    }
                }
            }

            if (autoFocusMode == null) {
                finishFocusWait(
                    outcome = null,
                    result = null,
                    requestProvenance = "NONE"
                )
                return@suspendCancellableCoroutine
            }

            val activeAutoFocusMode = checkNotNull(autoFocusMode)
            val activePolicy = checkNotNull(policy)

            val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult
                ) {
                    if (isFocusBoundaryStarted.get()) {
                        return
                    }
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    val outcome = activePolicy.onRepeatingCompleted(afState)
                    if (outcome != null) {
                        finishFocusWait(
                            outcome = outcome,
                            result = result,
                            requestProvenance = "REPEATING"
                        )
                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure
                ) {
                    if (isFocusBoundaryStarted.get()) {
                        return
                    }
                    Log.w(TAG, "AF lock frame failed: ${failure.reason}")
                    val outcome = activePolicy.onRepeatingFailed()
                    if (outcome != null) {
                        finishFocusWait(
                            outcome = outcome,
                            result = null,
                            requestProvenance = "REPEATING"
                        )
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
                        if (isFocusBoundaryStarted.get()) {
                            return
                        }
                        val outcome = activePolicy.onTriggerFailed(aborted = false) ?: return
                        finishFocusWait(
                            outcome = outcome,
                            result = null,
                            requestProvenance = "TRIGGER",
                            failure = IllegalStateException(
                                "Autofocus trigger request failed with reason ${failure.reason}"
                            )
                        )
                    }
                }

                override fun onCaptureSequenceAborted(
                    session: CameraCaptureSession,
                    sequenceId: Int
                ) {
                    synchronized(triggerSubmissionLock) {
                        if (isFocusBoundaryStarted.get()) {
                            return
                        }
                        val outcome = activePolicy.onTriggerFailed(aborted = true) ?: return
                        finishFocusWait(
                            outcome = outcome,
                            result = null,
                            requestProvenance = "TRIGGER",
                            failure = IllegalStateException("Autofocus trigger request was aborted")
                        )
                    }
                }
            }

            val repeatingRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, activeAutoFocusMode)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                afRegions?.let { set(CaptureRequest.CONTROL_AF_REGIONS, it) }
                aeRegions?.let { set(CaptureRequest.CONTROL_AE_REGIONS, it) }
            }
            try {
                synchronized(triggerSubmissionLock) {
                    if (!cont.isActive || isFocusBoundaryStarted.get()) {
                        return@suspendCancellableCoroutine
                    }
                    session.setRepeatingRequest(repeatingRequest.build(), repeatingCallback, handler)
                }
            } catch (failure: Throwable) {
                failFocusSetup(failure)
                return@suspendCancellableCoroutine
            }

            if (shouldTriggerAutoFocus(activeAutoFocusMode)) {
                try {
                    val triggerRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, activeAutoFocusMode)
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        afRegions?.let { set(CaptureRequest.CONTROL_AF_REGIONS, it) }
                        aeRegions?.let { set(CaptureRequest.CONTROL_AE_REGIONS, it) }
                    }
                    synchronized(triggerSubmissionLock) {
                        if (!cont.isActive || isFocusBoundaryStarted.get()) {
                            return@synchronized
                        }
                        session.capture(triggerRequest.build(), triggerCallback, handler)
                        triggerIssued.set(true)
                    }
                } catch (e: Exception) {
                    finishFocusWait(
                        outcome = AutoFocusWaitOutcome.TRIGGER_SUBMISSION_FAILED,
                        result = null,
                        requestProvenance = "TRIGGER",
                        failure = e
                    )
                }
            }
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
            resultAfMode = FocusLensDiagnosticsHelper.mapAfMode(
                result?.get(CaptureResult.CONTROL_AF_MODE)
            ),
            resultAfRegions = formatMeteringRegions(rawAfRegions),
            resultAeRegions = formatMeteringRegions(rawAeRegions),
            resultScalerCrop = crop?.let {
                "Rect(${it.left}, ${it.top}, ${it.width()}x${it.height()})"
            },
            afState = FocusLensDiagnosticsHelper.mapAfState(
                result?.get(CaptureResult.CONTROL_AF_STATE)
            ),
            repeatingFrameCount = repeatingFrameCount,
            exitReason = outcome.name,
            afTriggerIssued = afTriggerIssued,
            requestProvenance = requestProvenance
        )
    }

    private fun formatMeteringRegions(
        regions: Array<android.hardware.camera2.params.MeteringRectangle>?
    ): String? {
        if (regions.isNullOrEmpty()) {
            return null
        }
        return regions.joinToString(", ") { region ->
            "Rect(${region.rect.left}, ${region.rect.top}, ${region.rect.width()}x${region.rect.height()} wt=${region.meteringWeight})"
        }
    }

    private fun drainImageReader(reader: ImageReader) {
        while (true) {
            val image = try {
                // acquireNextImage() drains FIFO without auto-discarding intermediate
                // frames, making the drain deterministic regardless of queue depth.
                reader.acquireNextImage()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Unable to drain ImageReader", e)
                null
            } ?: break
            image.close()
        }
    }

}
