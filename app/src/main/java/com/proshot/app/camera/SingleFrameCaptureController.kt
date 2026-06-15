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

// Minimum callbacks before any focused state is accepted. Does not guarantee
// the trigger frame has been delivered; the trigger result may arrive at
// frame 3 or later on pipeline-depth-3 HALs. Null and passive states are
// rejected in active modes regardless, preventing premature exit on
// pre-trigger callbacks.
private const val AF_TRIGGER_MIN_FRAMES = 2
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
     * `AUTO` is preferred because it is the clearest mode for an explicit
     * `CONTROL_AF_TRIGGER_START` before still capture. `CONTINUOUS_PICTURE` is a
     * fallback for devices that do not expose `AUTO`. Returns null for fixed-focus
     * devices where no autofocus trigger is useful.
     */
    fun selectAutoFocusModeForStillCapture(availableModes: IntArray?): Int? {
        if (availableModes == null) {
            return null
        }
        val modes = availableModes.toSet()
        return when {
            CaptureRequest.CONTROL_AF_MODE_AUTO in modes -> CaptureRequest.CONTROL_AF_MODE_AUTO
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            else -> null
        }
    }

    /**
     * Returns true when an AF state is safe to leave the autofocus wait loop.
     *
     * For `CONTROL_AF_MODE_AUTO`, only `FOCUSED_LOCKED` is accepted after an explicit
     * `AF_TRIGGER_START`. `NOT_FOCUSED_LOCKED` means the HAL finished scanning without
     * focus, so the controller keeps waiting until the frame cap instead of immediately
     * saving a soft close-subject photo. `PASSIVE_FOCUSED` and `PASSIVE_UNFOCUSED` are
     * pre-trigger residual states from the warm-up phase and must not be accepted, or
     * the lock phase exits before the trigger takes effect.
     *
     * For `CONTROL_AF_MODE_CONTINUOUS_PICTURE` fallback, `PASSIVE_FOCUSED` is valid
     * because passive AF converges without an explicit trigger. `PASSIVE_UNFOCUSED`
     * keeps waiting until the frame cap for the same close-subject reason.
     *
     * Null AF state is not ready in active AF modes.
     */
    fun isAutoFocusReadyForStillCapture(frameCount: Int, afState: Int?, afMode: Int?): Boolean {
        if (afMode == null) {
            return true
        }
        if (frameCount < AF_TRIGGER_MIN_FRAMES) {
            return false
        }
        return when (afMode) {
            CaptureRequest.CONTROL_AF_MODE_AUTO -> {
                afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
            }
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> {
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
        tracker: CaptureTimingTracker? = null
    ): CopiedImageFrame = withContext(Dispatchers.Default) {
        withTimeout(CAPTURE_TIMEOUT_MS) {
            captureSingleFrameOnCurrentThread(context, tracker)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    private suspend fun captureSingleFrameOnCurrentThread(
        context: Context,
        tracker: CaptureTimingTracker? = null
    ): CopiedImageFrame {
        val totalStart = tracker?.let { System.nanoTime() }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: throw IllegalStateException("CameraManager is not available")

        // 1. Resolve primary physical back camera
        val cameraId = resolvePrimaryCameraId(manager)

        val characteristics = manager.getCameraCharacteristics(cameraId)
        val availableAutoFocusModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        if (availableAutoFocusModes == null) {
            Log.w(TAG, "CONTROL_AF_AVAILABLE_MODES characteristic is null; assuming fixed-focus")
        }
        val autoFocusMode = selectAutoFocusModeForStillCapture(availableAutoFocusModes)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()

        // Match the closest supported size to 1080p for single-frame stability.
        val mappedSizes = yuvSizes.map { CaptureSize(it.width, it.height) }
        val matchedSize = findClosestStableSize(mappedSizes)
        val targetSize = Size(matchedSize.width, matchedSize.height)

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
                autoFocusMode = autoFocusMode
            )
            if (warmupStart != null) {
                tracker?.aeWarmupMs = (System.nanoTime() - warmupStart) / 1_000_000L
            }

            // Only time AF when AF locking is active; fixed-focus cameras
            // skip locking and intentionally record no AF duration.
            val afStart = if (tracker != null && autoFocusMode != null) System.nanoTime() else null
            lockAutoFocusBeforeCapture(
                device = device,
                session = session,
                reader = reader,
                handler = handler,
                autoFocusMode = autoFocusMode
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

                session.capture(builder.build(), null, handler)

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
        autoFocusMode: Int?
    ) {
        suspendCancellableCoroutine<Unit> { cont ->
            val isWarmupDone = AtomicBoolean(false)
            var frameCount = 0

            fun finishWarmup(timedOut: Boolean, aeState: Int?) {
                if (!isWarmupDone.compareAndSet(false, true)) {
                    return
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
        autoFocusMode: Int?
    ) {
        if (autoFocusMode == null) {
            Log.d(TAG, "Skipping AF lock because camera reports fixed-focus/no triggerable AF")
            return
        }

        suspendCancellableCoroutine<Unit> { cont ->
            val isFocusDone = AtomicBoolean(false)
            var frameCount = 0

            fun finishFocusWait(timedOut: Boolean, afState: Int?) {
                if (!isFocusDone.compareAndSet(false, true)) {
                    return
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
                }
                session.setRepeatingRequest(repeatingRequest.build(), callback, handler)

                if (autoFocusMode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
                    val triggerRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, autoFocusMode)
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
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
