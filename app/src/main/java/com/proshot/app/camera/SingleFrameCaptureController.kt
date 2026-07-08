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
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

private const val TAG = "SingleFrameCaptureController"
private const val CAPTURE_TIMEOUT_MS = 8_000L
private const val AE_WARMUP_MIN_FRAMES = 3
private const val AE_WARMUP_MAX_FRAMES = 12

// Minimum callbacks before FOCUSED_LOCKED is accepted in AUTO mode.
// Does not guarantee the trigger frame has been delivered; the trigger
// result may arrive at frame 3 or later on pipeline-depth-3 HALs.
private const val AF_TRIGGER_MIN_FRAMES = 2

// Minimum callbacks before PASSIVE_FOCUSED or FOCUSED_LOCKED is accepted
// in CONTINUOUS_PICTURE mode. In a fresh Camera2 session the HAL may carry
// PASSIVE_FOCUSED from the prior CameraX session's lens position. A gate
// of 8 frames (~267 ms at 30 fps) exceeds the typical Qualcomm CDAF scan
// initialization window and ensures the HAL has run at least one real scan
// cycle on the current scene before the result is trusted.
private const val AF_PASSIVE_MIN_FRAMES = 8

private const val AF_LOCK_MAX_FRAMES = 30

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
 *
 * TODO: Convert to Hilt-injectable class before burst-capture routing is added.
 */
object SingleFrameCaptureController {

    /**
     * Extracts plane byte array sizes from a copied heap frame and returns a formatted summary.
     */
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
    fun findClosestStableSize(sizes: List<CaptureSize>): CaptureSize {
        val targetArea = 1920 * 1080
        return sizes.minByOrNull { size ->
            abs((size.width * size.height) - targetArea)
        } ?: CaptureSize(1920, 1080)
    }

    /**
     * Selects the autofocus mode to use for the one-shot still-capture lock sequence.
     *
     * `CONTINUOUS_PICTURE` is preferred for the still-capture experiment because it
     * lets the HAL converge passively on the center metering region before capture.
     * `AUTO` remains the fallback for devices that do not expose continuous picture
     * AF and is the only mode that receives an explicit `CONTROL_AF_TRIGGER_START`.
     * Returns null for fixed-focus devices where no autofocus trigger is useful.
     */
    fun selectAutoFocusModeForStillCapture(availableModes: IntArray?): Int? {
        if (availableModes == null) {
            return null
        }
        val modes = availableModes.toSet()
        return when {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            CaptureRequest.CONTROL_AF_MODE_AUTO in modes -> CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> null
        }
    }

    /**
     * Returns true when an AF state is safe to leave the autofocus wait loop.
     *
     * For `CONTROL_AF_MODE_AUTO`, only `FOCUSED_LOCKED` is accepted after
     * [AF_TRIGGER_MIN_FRAMES] (2 frames). The trigger forces a deterministic
     * scan cycle, so the gate only needs to exceed pipeline depth.
     *
     * For `CONTROL_AF_MODE_CONTINUOUS_PICTURE`, `PASSIVE_FOCUSED` and
     * `FOCUSED_LOCKED` are accepted only after [AF_PASSIVE_MIN_FRAMES]
     * (8 frames). In a fresh Camera2 session the HAL may carry
     * `PASSIVE_FOCUSED` from the prior CameraX lens position — a stale
     * state that does not reflect a scan of the current scene. The higher
     * gate ensures the HAL has run at least one real passive scan cycle.
     * `NOT_FOCUSED_LOCKED` is rejected because no `AF_TRIGGER_START` is
     * sent in this mode — the state should not arise, but if it does
     * (e.g., from a prior session or OEM HAL quirk), conservatively
     * waiting for the frame cap is safer than capturing known-failed focus.
     *
     * `PASSIVE_UNFOCUSED` keeps waiting in both modes for the same
     * close-subject reason. Null AF state is never ready in active modes.
     */
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
            // Unknown active AF modes fail closed: wait for the bounded
            // frame cap rather than silently accepting unfocused output.
            else -> false
        }
    }

    /**
     * Resolves the clockwise pixel rotation needed for saved output to match device orientation.
     *
     * For front cameras, this returns the correct rotation angle, but the caller must also
     * apply a horizontal flip before encoding, as Camera2 front-camera buffers are not
     * pre-mirrored. See Camera2 documentation on LENS_FACING_FRONT sensor orientation.
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

    /**
     * Returns the back camera's physical sensor orientation in degrees.
     *
     * Unlike [resolveOutputRotationDegrees], this value is constant for a given
     * camera (it does not change with display rotation). It represents the
     * clockwise angle by which the sensor is rotated relative to the device's
     * natural (portrait) orientation. This is the correct input for mapping
     * preview tap coordinates into sensor-normalized space via
     * [PreviewTapFocusMapper].
     */
    fun resolveSensorOrientation(context: Context): Int {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: throw IllegalStateException("CameraManager is not available")
        val cameraId = resolvePrimaryCameraId(manager)
        return manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    }

    /**
     * Captures a single YUV_420_888 frame from the primary back camera and returns a safe heap-allocated [CopiedImageFrame].
     *
     * Ensures all native and physical resources (CameraDevice, CameraCaptureSession, ImageReader, HandlerThread)
     * are aggressively and robustly closed upon success, failure, and cancellation.
     *
     * @throws SecurityException if [android.Manifest.permission.CAMERA] is not held by the caller.
     * @throws IllegalStateException if no camera is available or capture resources cannot be initialized.
     */
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
            diagnosticsTracker.logicalCameraId = cameraId
            diagnosticsTracker.afWaitExitReason = "NOT_RUN"
        }

        val characteristics = manager.getCameraCharacteristics(cameraId)
        val availableAutoFocusModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        if (availableAutoFocusModes == null) {
            Log.w(TAG, "CONTROL_AF_AVAILABLE_MODES characteristic is null; assuming fixed-focus")
        }
        val autoFocusMode = selectAutoFocusModeForStillCapture(availableAutoFocusModes)
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
            diagnosticsTracker.focusTargetSource = focusTarget.source.name
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
                // "NONE" is currently unreachable: cropRegion is non-null whenever
                // pureActive is non-null (see calculateCenterCrop call above).
                // Retained for defensive coverage if crop logic changes.
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

        // 2. Spawn dedicated callback handler thread
        val handlerThread = HandlerThread("SingleFrameCaptureControllerThread").apply { start() }
        val handler = Handler(handlerThread.looper)

        var cameraDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        var imageReader: ImageReader? = null
        val isCompleted = AtomicBoolean(false)

        // AtomicReference tracks the camera device across the callback/cancellation boundary.
        // The invokeOnCancellation lambda cannot rely on the outer `cameraDevice` var because
        // it may still be null when cancellation fires before onOpened delivers the device.
        val pendingDevice = AtomicReference<CameraDevice?>(null)
        val pendingSession = AtomicReference<CameraCaptureSession?>(null)

        return try {
            // 3. Open CameraDevice asynchronously
            val openStart = tracker?.let { System.nanoTime() }
            cameraDevice = suspendCancellableCoroutine { cont ->
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        Log.d(TAG, "CameraDevice opened successfully: ${camera.id}")
                        pendingDevice.set(camera)
                        if (cont.isActive) {
                            cont.resume(camera) {
                                // Cancellation raced with resume; close the dropped resource.
                                camera.close()
                            }
                        } else {
                            camera.close()
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(TAG, "CameraDevice disconnected: ${camera.id}")
                        camera.close()
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("Camera device was disconnected"))
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "CameraDevice open error: ${camera.id}, code: $error")
                        camera.close()
                        if (cont.isActive) {
                            cont.resumeWithException(RuntimeException("Failed to open camera device. Error code: $error"))
                        }
                    }
                }, handler)

                cont.invokeOnCancellation {
                    Log.d(TAG, "Camera open cancelled, closing camera device")
                    pendingDevice.get()?.close()
                    // Do NOT quit the handler thread here. The Camera2 open callback
                    // may still need the looper to deliver and close a late-opened device.
                    // The finally block handles handlerThread.quitSafely().
                }
            }
            if (openStart != null) {
                tracker?.cameraOpenMs = (System.nanoTime() - openStart) / 1_000_000L
            }

            // 4. Initialize ImageReader
            imageReader = ImageReader.newInstance(targetSize.width, targetSize.height, ImageFormat.YUV_420_888, 4)

            // 5. Create CameraCaptureSession and wait for it to configure
            // TODO: Migrate to createCaptureSession(SessionConfiguration) before
            // burst-capture task. The deprecated overload is still functional on minSdk 26.
            @Suppress("DEPRECATION")
            val configStart = tracker?.let { System.nanoTime() }
            captureSession = suspendCancellableCoroutine { cont ->
                val device = cameraDevice
                if (device == null) {
                    cont.resumeWithException(IllegalStateException("CameraDevice is null"))
                    return@suspendCancellableCoroutine
                }
                val readerSurface = imageReader?.surface
                if (readerSurface == null) {
                    cont.resumeWithException(IllegalStateException("ImageReader surface is null"))
                    return@suspendCancellableCoroutine
                }
                device.createCaptureSession(listOf(readerSurface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "CameraCaptureSession configured successfully")
                        pendingSession.set(session)
                        if (cont.isActive) {
                            cont.resume(session) {
                                // Cancellation raced with resume; close the dropped session.
                                session.close()
                            }
                        } else {
                            session.close()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "CameraCaptureSession configuration failed")
                        session.close()
                        if (cont.isActive) {
                            cont.resumeWithException(RuntimeException("Failed to configure capture session"))
                        }
                    }
                }, handler)

                cont.invokeOnCancellation {
                    Log.d(TAG, "Session configuration cancelled, closing session")
                    pendingSession.get()?.close()
                }
            }
            if (configStart != null) {
                tracker?.sessionConfigMs = (System.nanoTime() - configStart) / 1_000_000L
            }

            // 6. Run a short YUV drain before still capture so Camera2 AE can settle.
            val reader = imageReader
            val device = cameraDevice
            val session = captureSession

            if (device == null || session == null) {
                throw IllegalStateException("Camera2 capture resources were not fully initialized")
            }

            val warmupStart = tracker?.let { System.nanoTime() }
            warmUpAutoExposure(
                device = device,
                session = session,
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
                device = device,
                session = session,
                reader = reader,
                handler = handler,
                autoFocusMode = autoFocusMode,
                diagnosticsTracker = diagnosticsTracker,
                afRegions = afRegionsToApply,
                aeRegions = aeRegionsToApply
            )
            if (afStart != null) {
                tracker?.afWaitMs = (System.nanoTime() - afStart) / 1_000_000L
            }

            val stillStart = tracker?.let { System.nanoTime() }
            val copiedFrame = suspendCancellableCoroutine<CopiedImageFrame> { cont ->
                reader.setOnImageAvailableListener({ imageReaderRef ->
                    // image declared outside try so finally can always close it.
                    // acquireLatestImage is inside try to catch IllegalStateException
                    // if the ImageReader was closed concurrently by the timeout path.
                    var image: Image? = null
                    try {
                        image = imageReaderRef.acquireLatestImage()
                        if (image != null) {
                            Log.d(TAG, "Native frame acquired, copying immediately")
                            // Copy must be completed before native close or resume handoff
                            val frame = CopiedImageFrame.copyFrom(image)
                            if (diagnosticsTracker != null) {
                                diagnosticsTracker.copiedImageTimestamp = frame.timestamp
                                diagnosticsTracker.captureWidth = frame.width
                                diagnosticsTracker.captureHeight = frame.height
                                diagnosticsTracker.imageFormat = "YUV_420_888"
                            }

                            if (isCompleted.compareAndSet(false, true) && cont.isActive) {
                                Log.d(TAG, "YUV_420_888 frame successfully heap-copied")
                                cont.resume(frame)
                            }
                        } else {
                            if (isCompleted.compareAndSet(false, true) && cont.isActive) {
                                cont.resumeWithException(IllegalStateException("Acquired null image from reader"))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error acquiring or copying image", e)
                        if (isCompleted.compareAndSet(false, true) && cont.isActive) {
                            cont.resumeWithException(e)
                        }
                    } finally {
                        image?.close()
                    }
                }, handler)

                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
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
                        Log.d(TAG, "Still capture completed, sensor timestamp: $ts ns")
                        if (diagnosticsTracker != null && ts != null) {
                            diagnosticsTracker.stillCaptureResultTimestamp = ts
                        }
                    }
                }

                session.capture(builder.build(), stillCallback, handler)

                cont.invokeOnCancellation {
                    isCompleted.set(true)
                    Log.d(TAG, "Single frame capture cancelled")
                }
            }
            if (stillStart != null) {
                tracker?.stillCaptureMs = (System.nanoTime() - stillStart) / 1_000_000L
            }
            if (totalStart != null) {
                tracker?.totalCamera2CaptureMs = (System.nanoTime() - totalStart) / 1_000_000L
            }

            copiedFrame
        } finally {
            // 7. Guaranteed resource cleanup on success, failure, and cancellation
            Log.d(TAG, "Aggressively closing physical and native capture resources")
            try {
                captureSession?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing capture session", e)
            }
            try {
                cameraDevice?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing camera device", e)
            }
            try {
                imageReader?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing ImageReader", e)
            }
            // TODO: The quitSafely() here can race with a late Camera2 onOpened callback
            // that hasn't been delivered yet. If the looper exits before the callback runs,
            // the late-opened CameraDevice may never receive its close(). The AtomicReference
            // + onOpened guard handles most cases, but a truly delayed HAL delivery could be
            // suppressed. A full fix requires a resource owner that defers looper shutdown
            // until the open callback is confirmed delivered or timed out.
            handlerThread.quitSafely()
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
        aeRegions: Array<android.hardware.camera2.params.MeteringRectangle>?
    ) {
        if (autoFocusMode == null) {
            Log.d(TAG, "Skipping AF lock because camera reports fixed-focus/no triggerable AF")
            if (diagnosticsTracker != null) {
                diagnosticsTracker.afWaitExitReason = "FIXED_FOCUS"
            }
            return
        }

        suspendCancellableCoroutine<Unit> { cont ->
            val isFocusDone = AtomicBoolean(false)
            var frameCount = 0

            fun finishFocusWait(timedOut: Boolean, afState: Int?) {
                if (!isFocusDone.compareAndSet(false, true)) {
                    return
                }
                if (diagnosticsTracker != null) {
                    diagnosticsTracker.afWaitExitState = FocusLensDiagnosticsHelper.mapAfState(afState) ?: "UNKNOWN"
                    diagnosticsTracker.afWaitFrameCount = frameCount
                    diagnosticsTracker.afWaitExitReason = if (timedOut) "FRAME_CAP_TIMEOUT" else "FOCUSED"
                }
                try {
                    session.stopRepeating()
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to stop AF lock repeating request", e)
                }
                drainImageReader(reader)
                reader.setOnImageAvailableListener(null, null)

                if (timedOut) {
                    Log.w(TAG, "AF lock hit frame cap of $AF_LOCK_MAX_FRAMES frames (AF state=$afState)")
                } else {
                    Log.d(TAG, "AF lock completed successfully in $frameCount frames (AF state=$afState)")
                }

                if (cont.isActive) {
                    cont.resume(Unit)
                }
            }

            reader.setOnImageAvailableListener({ imageReaderRef ->
                drainImageReader(imageReaderRef)
            }, handler)

            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult
                ) {
                    frameCount++
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (isAutoFocusReadyForStillCapture(frameCount, afState, autoFocusMode)) {
                        finishFocusWait(timedOut = false, afState = afState)
                    } else if (frameCount >= AF_LOCK_MAX_FRAMES) {
                        finishFocusWait(timedOut = true, afState = afState)
                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure
                ) {
                    frameCount++
                    Log.w(TAG, "AF lock frame failed: ${failure.reason}")
                    if (frameCount >= AF_LOCK_MAX_FRAMES) {
                        finishFocusWait(timedOut = true, afState = null)
                    }
                }
            }

            try {
                val repeatingRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, autoFocusMode)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    afRegions?.let { set(CaptureRequest.CONTROL_AF_REGIONS, it) }
                    aeRegions?.let { set(CaptureRequest.CONTROL_AE_REGIONS, it) }
                }
                session.setRepeatingRequest(repeatingRequest.build(), callback, handler)

                if (autoFocusMode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
                    val triggerRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, autoFocusMode)
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        afRegions?.let { set(CaptureRequest.CONTROL_AF_REGIONS, it) }
                        aeRegions?.let { set(CaptureRequest.CONTROL_AE_REGIONS, it) }
                    }
                    session.capture(triggerRequest.build(), callback, handler)
                }
            } catch (e: Exception) {
                reader.setOnImageAvailableListener(null, null)
                if (cont.isActive) {
                    cont.resumeWithException(e)
                }
            }

            cont.invokeOnCancellation {
                if (isFocusDone.compareAndSet(false, true)) {
                    try {
                        session.stopRepeating()
                    } catch (e: Exception) {
                        Log.w(TAG, "Unable to stop cancelled AF lock", e)
                    }
                    drainImageReader(reader)
                    reader.setOnImageAvailableListener(null, null)
                }
            }
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
}
