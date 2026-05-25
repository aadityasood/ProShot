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
    suspend fun captureSingleFrame(context: Context): CopiedImageFrame = withContext(Dispatchers.Default) {
        withTimeout(CAPTURE_TIMEOUT_MS) {
            captureSingleFrameOnCurrentThread(context)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    private suspend fun captureSingleFrameOnCurrentThread(context: Context): CopiedImageFrame {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: throw IllegalStateException("CameraManager is not available")

        // 1. Resolve primary physical back camera
        val cameraId = resolvePrimaryCameraId(manager)

        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()

        // Match the closest supported size to 1080p for stability in T03
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

            // 4. Initialize ImageReader
            imageReader = ImageReader.newInstance(targetSize.width, targetSize.height, ImageFormat.YUV_420_888, 4)

            // 5. Create CameraCaptureSession and wait for it to configure
            // TODO: Migrate to createCaptureSession(SessionConfiguration) before
            // burst-capture task. The deprecated overload is still functional on minSdk 26.
            @Suppress("DEPRECATION")
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

            // 6. Run a short YUV drain before still capture so Camera2 AE can settle.
            val reader = imageReader
            val device = cameraDevice
            val session = captureSession

            if (device == null || session == null) {
                throw IllegalStateException("Camera2 capture resources were not fully initialized")
            }

            warmUpAutoExposure(
                device = device,
                session = session,
                reader = reader,
                handler = handler
            )

            suspendCancellableCoroutine<CopiedImageFrame> { cont ->
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
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                session.capture(builder.build(), null, handler)

                cont.invokeOnCancellation {
                    isCompleted.set(true)
                    Log.d(TAG, "Single frame capture cancelled")
                }
            }
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
        handler: Handler
    ) {
        suspendCancellableCoroutine<Unit> { cont ->
            val isWarmupDone = AtomicBoolean(false)
            var frameCount = 0

            fun finishWarmup() {
                if (!isWarmupDone.compareAndSet(false, true)) {
                    return
                }
                try {
                    session.stopRepeating()
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to stop AE warmup repeating request", e)
                }
                // Drain while the listener is still active, so late-arriving warm-up
                // frames are handled by the drain callback rather than piling up
                // unacquired in the ImageReader. Remove listener only after drain.
                drainImageReader(reader)
                reader.setOnImageAvailableListener(null, null)
                if (cont.isActive) {
                    cont.resume(Unit)
                }
            }

            reader.setOnImageAvailableListener({ imageReaderRef ->
                drainImageReader(imageReaderRef)
            }, handler)

            val warmupRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
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

                    if ((frameCount >= AE_WARMUP_MIN_FRAMES && aeReady) ||
                        frameCount >= AE_WARMUP_MAX_FRAMES
                    ) {
                        finishWarmup()
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
                        finishWarmup()
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
