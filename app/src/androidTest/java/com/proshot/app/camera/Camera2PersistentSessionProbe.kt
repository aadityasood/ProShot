package com.proshot.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

private const val TAG = "Camera2PersistentSessionProbe"
private const val CAMERA_OPEN_TIMEOUT_MS = 5_000L
private const val SESSION_CONFIG_TIMEOUT_MS = 5_000L
private const val STILL_CORRELATION_TIMEOUT_MS = 5_000L
private const val DEVICE_CLOSE_ACK_TIMEOUT_MS = 5_000L
private const val THREAD_JOIN_TIMEOUT_MS = 2_000L

/**
 * Diagnostic summary produced by a successful [Camera2PersistentSessionProbe] run.
 *
 * @property cameraId The physical or logical camera ID used for capture.
 * @property sdkInt Android API level of the host device.
 * @property hardwareLevel Camera2 hardware support level classification string.
 * @property previewSize Selected preview buffer size.
 * @property yuvSize Selected still capture YUV_420_888 buffer size.
 * @property previewFramesBeforeFirstStill Count of preview frames observed before triggering first still.
 * @property previewFramesAfterStills List of preview frame counts observed after each still capture.
 * @property correlatedStillTimestampsNs Matched sensor timestamps in nanoseconds for each still capture.
 */
data class Camera2ProbeRunSummary(
    val cameraId: String,
    val sdkInt: Int,
    val hardwareLevel: String,
    val previewSize: Size,
    val yuvSize: Size,
    val previewFramesBeforeFirstStill: Int,
    val previewFramesAfterStills: List<Int>,
    val correlatedStillTimestampsNs: List<Long>
) {
    /**
     * Formats the summary into a compact diagnostic string for test assertions and log verification.
     */
    fun toCompactString(): String =
        "CameraID: $cameraId | SDK: $sdkInt | HWLevel: $hardwareLevel | PreviewSize: ${previewSize.width}x${previewSize.height} | YuvSize: ${yuvSize.width}x${yuvSize.height} | FramesBeforeFirstStill: $previewFramesBeforeFirstStill | FramesAfterStills: $previewFramesAfterStills | TimestampsNs: $correlatedStillTimestampsNs"
}

/**
 * Observable teardown evidence retained after a probe run closes.
 *
 * @property cameraDeviceDelivered Whether this run accepted a CameraDevice.
 * @property cameraDeviceCloseAcknowledged Whether that exact device delivered onClosed.
 * @property captureSessionDelivered Whether this run accepted a configured CameraCaptureSession.
 * @property captureSessionCloseAcknowledged Whether that exact session delivered onClosed.
 * @property callbackThreadStarted Whether the run successfully started its callback thread.
 * @property callbackThreadTerminated Whether the captured callback thread was no longer alive after join.
 * @property cleanupFailureMessages Material cleanup failures collected while releasing run resources.
 */
data class Camera2ProbeTeardownEvidence(
    val cameraDeviceDelivered: Boolean,
    val cameraDeviceCloseAcknowledged: Boolean,
    val captureSessionDelivered: Boolean,
    val captureSessionCloseAcknowledged: Boolean,
    val callbackThreadStarted: Boolean,
    val callbackThreadTerminated: Boolean,
    val cleanupFailureMessages: List<String>
) {
    /** Returns true only when every owned resource closed and the callback thread terminated. */
    val isClean: Boolean
        get() =
            cameraDeviceDelivered &&
                cameraDeviceCloseAcknowledged &&
                captureSessionDelivered &&
                callbackThreadTerminated &&
                cleanupFailureMessages.isEmpty()

    /** Formats teardown evidence for assertion diagnostics. */
    fun toCompactString(): String =
        "DeviceDelivered: $cameraDeviceDelivered | DeviceClosedAck: $cameraDeviceCloseAcknowledged | SessionDelivered: $captureSessionDelivered | SessionClosedAck: $captureSessionCloseAcknowledged | ThreadStarted: $callbackThreadStarted | ThreadTerminated: $callbackThreadTerminated | CleanupFailures: $cleanupFailureMessages"
}

/**
 * Thread-safe tracker for TextureView preview frame callbacks delivered via [onSurfaceTextureUpdated].
 */
class ProbePreviewFrameTracker {
    private val lock = Any()
    private var totalFrames = 0

    /**
     * Records a single preview frame update.
     */
    fun onFrameUpdated() {
        synchronized(lock) {
            totalFrames++
            (lock as Object).notifyAll()
        }
    }

    /**
     * Returns the total count of preview frames observed so far.
     */
    fun currentFrameCount(): Int = synchronized(lock) { totalFrames }

    /**
     * Awaits until the total observed preview frames reaches or exceeds [targetTotal].
     *
     * @throws IllegalStateException if [timeoutMs] expires before [targetTotal] is reached.
     */
    fun awaitFrameCount(targetTotal: Int, timeoutMs: Long = 5_000L): Int {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (totalFrames < targetTotal) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    throw IllegalStateException(
                        "Timed out waiting for preview frames. Current: $totalFrames, Target: $targetTotal"
                    )
                }
                try {
                    (lock as Object).wait(remaining)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Interrupted waiting for preview frames", e)
                }
            }
            return totalFrames
        }
    }
}

/**
 * Thread-safe single sequential still capture correlator matching TotalCaptureResult
 * SENSOR_TIMESTAMP to Image.timestamp with exact equality.
 *
 * @param expectedTag Request tag identifying the active still capture.
 * @param timeoutMs Maximum wait duration in milliseconds for correlation completion.
 */
class SingleStillCorrelator(
    private val expectedTag: String,
    private val timeoutMs: Long = STILL_CORRELATION_TIMEOUT_MS
) {
    private val lock = Any()
    private var submittedSequenceId: Int? = null
    private var completedSequenceId: Int? = null
    private var resultCount = 0
    private var imageCount = 0
    private var resultTimestamp: Long? = null
    private var pendingImage: Image? = null
    private var matchedTimestamp: Long? = null
    private var failure: IllegalStateException? = null
    private val imageCleanupFailures = mutableListOf<Throwable>()

    /**
     * Registers the sequence ID returned by the still request submission.
     */
    fun registerSubmittedSequence(sequenceId: Int) {
        synchronized(lock) {
            if (submittedSequenceId != null) {
                failLocked("Duplicate submitted sequence ID for tag $expectedTag")
                return
            }
            submittedSequenceId = sequenceId
            completedSequenceId?.let { completed ->
                if (completed != sequenceId) {
                    failLocked(
                        "Capture sequence mismatch for tag $expectedTag: submitted=$sequenceId completed=$completed"
                    )
                }
            }
            notifyIfDoneLocked()
        }
    }

    /**
     * Registers a delivered [TotalCaptureResult] and requires a non-null sensor timestamp.
     */
    fun onCaptureCompleted(tag: Any?, timestamp: Long?) {
        synchronized(lock) {
            if (failure != null) return
            if (resultCount > 0) {
                failLocked("Duplicate capture result for tag $expectedTag")
                return
            }
            resultCount++
            if (tag != expectedTag) {
                failLocked("Unexpected capture result tag: expected=$expectedTag actual=$tag")
                return
            }
            if (timestamp == null) {
                failLocked("Capture result SENSOR_TIMESTAMP was null for tag $expectedTag")
                return
            }
            resultTimestamp = timestamp
            checkMatchLocked()
        }
    }

    /** Records a terminal framework capture failure for the active request. */
    fun onCaptureFailed(tag: Any?, captureFailure: CaptureFailure) {
        synchronized(lock) {
            failLocked(
                "Still capture failed for tag $expectedTag: actualTag=$tag reason=${captureFailure.reason} frame=${captureFailure.frameNumber} sequence=${captureFailure.sequenceId}"
            )
        }
    }

    /** Records completion of the framework capture sequence. */
    fun onCaptureSequenceCompleted(sequenceId: Int) {
        synchronized(lock) {
            if (completedSequenceId != null) {
                failLocked("Duplicate capture sequence completion for tag $expectedTag")
                return
            }
            completedSequenceId = sequenceId
            submittedSequenceId?.let { submitted ->
                if (submitted != sequenceId) {
                    failLocked(
                        "Capture sequence mismatch for tag $expectedTag: submitted=$submitted completed=$sequenceId"
                    )
                }
            }
            notifyIfDoneLocked()
        }
    }

    /** Records terminal abortion of the framework capture sequence. */
    fun onCaptureSequenceAborted(sequenceId: Int) {
        synchronized(lock) {
            failLocked("Capture sequence $sequenceId aborted for tag $expectedTag")
        }
    }

    /** Records a terminal submission or run-ownership failure. */
    fun fail(reason: String, cause: Throwable? = null) {
        synchronized(lock) {
            failLocked(reason, cause)
        }
    }

    /**
     * Acquires and registers the next image delivered by [ImageReader].
     */
    fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            fail("Failed to acquire next image for tag $expectedTag: ${e.message}", e)
            return
        }

        if (image == null) {
            fail("ImageReader callback produced no image for tag $expectedTag")
            return
        }

        synchronized(lock) {
            if (failure != null) {
                closeImageOnceLocked(image, "late image for tag $expectedTag")
                return
            }
            if (isSuccessfulLocked()) {
                closeImageOnceLocked(image, "duplicate image after completed tag $expectedTag")
                failLocked("Duplicate image delivered after completed correlation for tag $expectedTag")
                return
            }

            if (imageCount > 0 || pendingImage != null) {
                val priorImage = pendingImage
                pendingImage = null
                closeImageOnceLocked(priorImage, "first image for duplicate delivery tag $expectedTag")
                closeImageOnceLocked(image, "duplicate image for tag $expectedTag")
                failLocked("Second image delivered while one image was pending for tag $expectedTag")
                return
            }

            imageCount++
            pendingImage = image
            checkMatchLocked()
        }
    }

    private fun checkMatchLocked() {
        val resTs = resultTimestamp
        val img = pendingImage
        if (resTs != null && img != null) {
            val imgTs = img.timestamp
            pendingImage = null
            if (resTs == imgTs) {
                matchedTimestamp = resTs
            } else {
                failLocked(
                    "Exact timestamp mismatch for tag $expectedTag: TotalCaptureResult SENSOR_TIMESTAMP ($resTs ns) != Image.timestamp ($imgTs ns)"
                )
            }
            closeImageOnceLocked(img, "correlated image for tag $expectedTag")
            notifyIfDoneLocked()
        }
    }

    private fun isSuccessfulLocked(): Boolean =
        matchedTimestamp != null &&
            resultCount == 1 &&
            imageCount == 1 &&
            submittedSequenceId != null &&
            completedSequenceId == submittedSequenceId

    private fun isDoneLocked(): Boolean = failure != null || isSuccessfulLocked()

    private fun notifyIfDoneLocked() {
        if (isDoneLocked()) {
            (lock as Object).notifyAll()
        }
    }

    private fun failLocked(reason: String, cause: Throwable? = null) {
        if (failure == null) {
            failure = IllegalStateException(reason, cause)
        }
        val image = pendingImage
        pendingImage = null
        closeImageOnceLocked(image, "pending image after failure for tag $expectedTag")
        (lock as Object).notifyAll()
    }

    private fun closeImageOnceLocked(image: Image?, description: String) {
        if (image == null) return
        try {
            image.close()
        } catch (e: Exception) {
            val closeFailure = IllegalStateException("Failed to close $description", e)
            imageCleanupFailures.add(closeFailure)
            val existingFailure = failure
            if (existingFailure == null) {
                failure = closeFailure
            } else {
                existingFailure.addSuppressed(closeFailure)
            }
        }
    }

    /**
     * Awaits exact timestamp correlation and returns the matched sensor timestamp in nanoseconds.
     *
     * @throws IllegalStateException on mismatch, timeout, or acquisition error.
     */
    fun awaitResult(): Long {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!isDoneLocked()) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    failLocked(
                        "Timed out waiting for still capture correlation (expected tag: $expectedTag)"
                    )
                    break
                }
                try {
                    (lock as Object).wait(remaining)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    failLocked("Interrupted waiting for still capture correlation for tag $expectedTag", e)
                    break
                }
            }
            failure?.let { throw it }
            return matchedTimestamp
                ?: throw IllegalStateException("Correlation completed without matched timestamp")
        }
    }

    /** Throws if a callback arriving before the caller's barrier terminally invalidated the match. */
    fun throwIfFailed() {
        synchronized(lock) {
            failure?.let { throw it }
            check(isSuccessfulLocked()) {
                "Correlation is not complete for tag $expectedTag"
            }
        }
    }

    /**
     * Aborts correlation and closes any pending image reference.
     */
    fun close() {
        val newCleanupFailures = synchronized(lock) {
            val failureCountBeforeClose = imageCleanupFailures.size
            if (!isDoneLocked()) {
                failLocked("Correlator closed before completion for tag $expectedTag")
            } else {
                val image = pendingImage
                pendingImage = null
                closeImageOnceLocked(image, "pending image during close for tag $expectedTag")
                (lock as Object).notifyAll()
            }
            imageCleanupFailures.drop(failureCountBeforeClose)
        }
        if (newCleanupFailures.isNotEmpty()) {
            throw IllegalStateException(
                "Failed to close correlator image resources for tag $expectedTag"
            ).also { aggregate ->
                newCleanupFailures.forEach(aggregate::addSuppressed)
            }
        }
    }
}

private class ResourceSignal<T : Any> {
    val latch = CountDownLatch(1)
    private val lock = Any()
    private var resolved = false
    private var value: T? = null
    private var failure: Throwable? = null

    fun complete(value: T) {
        synchronized(lock) {
            if (resolved) return
            this.value = value
            resolved = true
            latch.countDown()
        }
    }

    fun fail(failure: Throwable) {
        synchronized(lock) {
            if (resolved) return
            this.failure = failure
            resolved = true
            latch.countDown()
        }
    }

    fun getOrThrow(): T = synchronized(lock) {
        failure?.let { throw it }
        value ?: throw IllegalStateException("Resource signal completed without a value")
    }
}

private data class ProbeCleanupSnapshot(
    val session: CameraCaptureSession?,
    val device: CameraDevice?,
    val captureSessionDelivered: Boolean,
    val cameraDeviceDelivered: Boolean,
    val handler: Handler?,
    val reader: ImageReader?,
    val correlator: SingleStillCorrelator?,
    val surface: Surface?,
    val thread: HandlerThread?,
    val threadStarted: Boolean
)

/**
 * Controller executing the Compose-hosted Camera2 persistent two-surface feasibility probe.
 *
 * @param runId Unique identifier for logging and request tagging.
 */
class Camera2PersistentSessionProbe(
    private val runId: String = "Run_${System.currentTimeMillis()}"
) {
    private val ownershipLock = Any()
    private var callbackThread: HandlerThread? = null
    private var callbackThreadStarted = false
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var acceptedCameraDevice: CameraDevice? = null
    private var acceptedCaptureSession: CameraCaptureSession? = null
    private val cameraDeviceClosedLatch = CountDownLatch(1)
    private var cameraDeviceCloseAcknowledged = false
    private var captureSessionCloseAcknowledged = false
    private val closeCallbackFailures = mutableListOf<Throwable>()
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var activeCorrelator: SingleStillCorrelator? = null
    private var openSignal: ResourceSignal<CameraDevice>? = null
    private var sessionSignal: ResourceSignal<CameraCaptureSession>? = null
    private var terminal = false
    private var terminalFailure: Throwable? = null
    private var closeStarted = false
    private var closeFinished = false
    private var cleanupFailure: IllegalStateException? = null
    private var retainedTeardownEvidence: Camera2ProbeTeardownEvidence? = null

    companion object {
        /**
         * Resolves the primary back camera ID from [CameraManager].
         */
        @JvmStatic
        fun selectPrimaryBackCamera(manager: CameraManager): String {
            return manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: throw IllegalStateException("No LENS_FACING_BACK camera exists on this device")
        }

        /**
         * Resolves the [Size] from [sizes] closest in area to 1920x1080.
         */
        @JvmStatic
        fun findClosestSizeTo1080p(sizes: Array<Size>): Size {
            require(sizes.isNotEmpty()) { "Supported output sizes must not be empty" }
            val targetArea = 1920L * 1080L
            return sizes.minWithOrNull(
                compareBy<Size>(
                    { size -> abs(size.width.toLong() * size.height.toLong() - targetArea) },
                    { size -> abs(size.width.toLong() - 1920L) },
                    { size -> abs(size.height.toLong() - 1080L) },
                    { size -> size.width },
                    { size -> size.height }
                )
            ) ?: throw IllegalStateException("Supported output sizes unexpectedly became empty")
        }

        /**
         * Selects a deterministic supported preview/YUV pair, preferring a shared dimension.
         */
        @JvmStatic
        fun selectSupportedOutputPair(
            previewSizes: Array<Size>,
            yuvSizes: Array<Size>
        ): Pair<Size, Size> {
            require(previewSizes.isNotEmpty()) { "Supported preview sizes must not be empty" }
            require(yuvSizes.isNotEmpty()) { "Supported YUV sizes must not be empty" }

            val yuvDimensions = yuvSizes.map { it.width to it.height }.toSet()
            val commonSizes = previewSizes
                .filter { (it.width to it.height) in yuvDimensions }
                .toTypedArray()

            if (commonSizes.isNotEmpty()) {
                val common = findClosestSizeTo1080p(commonSizes)
                return common to common
            }

            return findClosestSizeTo1080p(previewSizes) to findClosestSizeTo1080p(yuvSizes)
        }

        /**
         * Maps Camera2 hardware support level integer to a human-readable classification string.
         */
        @JvmStatic
        fun mapHardwareLevel(level: Int?): String = when (level) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN ($level)"
        }

        /**
         * Drains and closes all available images in [reader] using [ImageReader.acquireNextImage].
         */
        @JvmStatic
        fun drainImageReader(reader: ImageReader) {
            while (true) {
                val image = reader.acquireNextImage() ?: break
                image.close()
            }
        }
    }

    /**
     * Executes the two-surface feasibility probe run.
     *
     * @param context Application or test target context.
     * @param surfaceTexture Active SurfaceTexture hosted by Compose TextureView.
     * @param frameTracker Preview frame observer.
     * @param stillCount Number of sequential still captures to execute and correlate.
     * @return [Camera2ProbeRunSummary] detailing probe outcomes and correlated timestamps.
     */
    @SuppressLint("MissingPermission")
    fun runProbe(
        context: Context,
        surfaceTexture: SurfaceTexture,
        frameTracker: ProbePreviewFrameTracker,
        stillCount: Int = 3
    ): Camera2ProbeRunSummary {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: throw IllegalStateException("CameraManager is unavailable")

        val cameraId = selectPrimaryBackCamera(manager)
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val hardwareLevelStr = mapHardwareLevel(
            characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        )

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("SCALER_STREAM_CONFIGURATION_MAP is null for camera $cameraId")

        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)
        if (previewSizes.isNullOrEmpty()) {
            throw IllegalStateException("No supported preview sizes for SurfaceTexture on camera $cameraId")
        }

        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        if (yuvSizes.isNullOrEmpty()) {
            throw IllegalStateException("No supported YUV_420_888 sizes on camera $cameraId")
        }

        val (previewSize, yuvSize) = selectSupportedOutputPair(previewSizes, yuvSizes)
        require(stillCount > 0) { "stillCount must be positive" }

        val correlatedTimestamps = mutableListOf<Long>()
        val framesAfterStills = mutableListOf<Int>()
        var summary: Camera2ProbeRunSummary? = null
        var runFailure: Throwable? = null

        try {
            synchronized(ownershipLock) {
                check(!terminal && !closeStarted) { "Probe instance $runId cannot be reused" }
            }

            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val localPreviewSurface = Surface(surfaceTexture)
            synchronized(ownershipLock) {
                previewSurface = localPreviewSurface
            }

            val localThread = HandlerThread("ProbeThread_$runId")
            synchronized(ownershipLock) {
                callbackThread = localThread
            }
            localThread.start()
            synchronized(ownershipLock) {
                callbackThreadStarted = true
            }

            val localHandler = Handler(localThread.looper)
            synchronized(ownershipLock) {
                handler = localHandler
            }

            val localImageReader = ImageReader.newInstance(
                yuvSize.width,
                yuvSize.height,
                ImageFormat.YUV_420_888,
                4
            )
            synchronized(ownershipLock) {
                imageReader = localImageReader
            }
            localImageReader.setOnImageAvailableListener(
                { reader -> routeRunOwnedImage(reader) },
                localHandler
            )

            val localOpenSignal = ResourceSignal<CameraDevice>()
            synchronized(ownershipLock) {
                openSignal = localOpenSignal
            }

            val deviceCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    registerOpenedCamera(camera, localOpenSignal)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    val failure = IllegalStateException(
                        "Camera device $cameraId disconnected during run $runId"
                    )
                    try {
                        camera.close()
                    } catch (closeFailure: Exception) {
                        failure.addSuppressed(
                            IllegalStateException("Failed to close disconnected CameraDevice", closeFailure)
                        )
                    }
                    clearDeliveredCamera(camera)
                    markTerminal(failure)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val failure = RuntimeException(
                        "Camera device $cameraId failed with error code $error during run $runId"
                    )
                    try {
                        camera.close()
                    } catch (closeFailure: Exception) {
                        failure.addSuppressed(
                            IllegalStateException("Failed to close errored CameraDevice", closeFailure)
                        )
                    }
                    clearDeliveredCamera(camera)
                    markTerminal(failure)
                }

                override fun onClosed(camera: CameraDevice) {
                    recordCameraDeviceClosed(camera)
                }
            }

            try {
                manager.openCamera(cameraId, deviceCallback, localHandler)
            } catch (e: Exception) {
                val failure = IllegalStateException(
                    "CameraDevice open submission failed for camera $cameraId ($runId)",
                    e
                )
                markTerminal(failure)
                throw failure
            }

            val device = awaitResource(
                signal = localOpenSignal,
                timeoutMs = CAMERA_OPEN_TIMEOUT_MS,
                timeoutMessage = "Timed out waiting for CameraDevice open ($runId)"
            )

            val localSessionSignal = ResourceSignal<CameraCaptureSession>()
            synchronized(ownershipLock) {
                sessionSignal = localSessionSignal
            }

            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    registerConfiguredSession(session, localSessionSignal)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    val failure = RuntimeException(
                        "CameraCaptureSession configuration failed ($runId)"
                    )
                    try {
                        session.close()
                    } catch (closeFailure: Exception) {
                        failure.addSuppressed(
                            IllegalStateException(
                                "Failed to close unconfigured CameraCaptureSession",
                                closeFailure
                            )
                        )
                    }
                    markTerminal(failure)
                }

                override fun onClosed(session: CameraCaptureSession) {
                    recordCaptureSessionClosed(session)
                }
            }

            try {
                createTwoSurfaceSession(
                    device = device,
                    previewSurface = localPreviewSurface,
                    readerSurface = localImageReader.surface,
                    callback = sessionCallback,
                    handler = localHandler
                )
            } catch (e: Exception) {
                val failure = IllegalStateException(
                    "CameraCaptureSession submission failed ($runId)",
                    e
                )
                markTerminal(failure)
                throw failure
            }

            val session = awaitResource(
                signal = localSessionSignal,
                timeoutMs = SESSION_CONFIG_TIMEOUT_MS,
                timeoutMessage = "Timed out waiting for CameraCaptureSession configuration ($runId)"
            )

            val previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(localPreviewSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            session.setRepeatingRequest(previewBuilder.build(), null, localHandler)

            val initialPreviewFrames = try {
                frameTracker.awaitFrameCount(targetTotal = 3, timeoutMs = 5_000L)
            } catch (failure: Throwable) {
                throw preferTerminalFailure(failure)
            }
            throwIfTerminalFailure()

            for (i in 1..stillCount) {
                runCallbackBarrier(localHandler, "pre-still YUV drain for request $i") {
                    drainImageReader(localImageReader)
                }
                throwIfTerminalFailure()

                val tag = "STILL_REQ_${runId}_$i"
                val correlator = SingleStillCorrelator(expectedTag = tag)
                publishActiveCorrelator(correlator)
                var matchedTimestamp: Long? = null
                var stillFailure: Throwable? = null

                try {
                    val stillBuilder =
                        device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(localImageReader.surface)
                            set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(
                                CaptureRequest.CONTROL_CAPTURE_INTENT,
                                CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
                            )
                            setTag(tag)
                        }

                    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            correlator.onCaptureCompleted(
                                request.tag,
                                result.get(TotalCaptureResult.SENSOR_TIMESTAMP)
                            )
                        }

                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure
                        ) {
                            correlator.onCaptureFailed(request.tag, failure)
                        }

                        override fun onCaptureSequenceCompleted(
                            session: CameraCaptureSession,
                            sequenceId: Int,
                            frameNumber: Long
                        ) {
                            correlator.onCaptureSequenceCompleted(sequenceId)
                        }

                        override fun onCaptureSequenceAborted(
                            session: CameraCaptureSession,
                            sequenceId: Int
                        ) {
                            correlator.onCaptureSequenceAborted(sequenceId)
                        }
                    }

                    val sequenceId = try {
                        session.capture(stillBuilder.build(), captureCallback, localHandler)
                    } catch (e: Exception) {
                        val failure = IllegalStateException(
                            "Still request submission failed for tag $tag: ${e.message}",
                            e
                        )
                        correlator.fail(failure.message ?: "Still request submission failed", failure)
                        throw failure
                    }
                    correlator.registerSubmittedSequence(sequenceId)

                    matchedTimestamp = correlator.awaitResult()
                    runCallbackBarrier(localHandler, "post-correlation callback barrier for $tag") {}
                    correlator.throwIfFailed()
                } catch (failure: Throwable) {
                    stillFailure = failure
                } finally {
                    clearActiveCorrelator(correlator)
                    try {
                        correlator.close()
                    } catch (cleanup: Throwable) {
                        val primary = stillFailure
                        if (primary == null) {
                            stillFailure = cleanup
                        } else {
                            primary.addSuppressed(cleanup)
                        }
                    }
                }

                stillFailure?.let { throw it }
                correlatedTimestamps.add(
                    matchedTimestamp
                        ?: throw IllegalStateException("Still $tag completed without a matched timestamp")
                )

                val frameCountAtCorrelationCompletion = frameTracker.currentFrameCount()
                val laterFrameCount = try {
                    frameTracker.awaitFrameCount(
                        targetTotal = frameCountAtCorrelationCompletion + 1,
                        timeoutMs = 5_000L
                    )
                } catch (failure: Throwable) {
                    throw preferTerminalFailure(failure)
                }
                framesAfterStills.add(laterFrameCount - frameCountAtCorrelationCompletion)
                throwIfTerminalFailure()
            }

            summary = Camera2ProbeRunSummary(
                cameraId = cameraId,
                sdkInt = Build.VERSION.SDK_INT,
                hardwareLevel = hardwareLevelStr,
                previewSize = previewSize,
                yuvSize = yuvSize,
                previewFramesBeforeFirstStill = initialPreviewFrames,
                previewFramesAfterStills = framesAfterStills,
                correlatedStillTimestampsNs = correlatedTimestamps
            )
        } catch (failure: Throwable) {
            runFailure = failure
            markTerminal(failure)
        } finally {
            try {
                close()
            } catch (failure: Throwable) {
                val primaryFailure = runFailure
                if (primaryFailure == null) {
                    runFailure = failure
                } else if (primaryFailure !== failure) {
                    primaryFailure.addSuppressed(failure)
                }
            }
        }

        if (runFailure == null) {
            synchronized(ownershipLock) {
                terminalFailure?.let { runFailure = it }
            }
        }
        runFailure?.let { throw it }

        val successfulSummary = summary
            ?: throw IllegalStateException("Probe run $runId completed without a summary")
        val successfulTeardown = teardownEvidence()
        Log.i(TAG, successfulSummary.toCompactString())
        Log.i(TAG, successfulTeardown.toCompactString())
        return successfulSummary
    }

    private fun routeRunOwnedImage(reader: ImageReader) {
        val correlator = synchronized(ownershipLock) { activeCorrelator }
        if (correlator != null) {
            correlator.onImageAvailable(reader)
            return
        }

        try {
            drainImageReader(reader)
        } catch (e: Exception) {
            markTerminal(
                IllegalStateException("Failed to drain an inactive ImageReader ($runId)", e)
            )
        }
    }

    private fun publishActiveCorrelator(correlator: SingleStillCorrelator) {
        synchronized(ownershipLock) {
            terminalFailure?.let { throw it }
            check(!terminal) { "Probe run $runId is terminal" }
            check(activeCorrelator == null) { "Another still correlator is already active ($runId)" }
            activeCorrelator = correlator
        }
    }

    private fun clearActiveCorrelator(correlator: SingleStillCorrelator) {
        synchronized(ownershipLock) {
            if (activeCorrelator === correlator) {
                activeCorrelator = null
            }
        }
    }

    private fun registerOpenedCamera(
        camera: CameraDevice,
        signal: ResourceSignal<CameraDevice>
    ) {
        var rejection: Throwable? = null
        var accepted = false
        synchronized(ownershipLock) {
            when {
                terminal -> Unit
                openSignal !== signal -> {
                    rejection = IllegalStateException("Stale CameraDevice open callback ($runId)")
                }
                cameraDevice != null || acceptedCameraDevice != null -> {
                    rejection = IllegalStateException("Duplicate CameraDevice open callback ($runId)")
                }
                else -> {
                    cameraDevice = camera
                    acceptedCameraDevice = camera
                    signal.complete(camera)
                    accepted = true
                }
            }
        }

        if (!accepted) {
            try {
                camera.close()
            } catch (e: Exception) {
                val closeFailure = IllegalStateException(
                    "Failed to close rejected CameraDevice callback ($runId)",
                    e
                )
                rejection?.addSuppressed(closeFailure) ?: run { rejection = closeFailure }
            }
            rejection?.let { markTerminal(it) }
        }
    }

    private fun clearDeliveredCamera(camera: CameraDevice) {
        synchronized(ownershipLock) {
            if (cameraDevice === camera) {
                cameraDevice = null
            }
        }
    }

    private fun recordCameraDeviceClosed(camera: CameraDevice) {
        var runFailure: Throwable? = null
        synchronized(ownershipLock) {
            val accepted = acceptedCameraDevice
            val callbackFailure = when {
                accepted == null -> {
                    IllegalStateException(
                        "Unexpected CameraDevice onClosed without an accepted device ($runId)"
                    )
                }
                accepted !== camera -> {
                    IllegalStateException(
                        "CameraDevice onClosed identity did not match the accepted device ($runId)"
                    )
                }
                cameraDeviceCloseAcknowledged -> {
                    IllegalStateException("Duplicate CameraDevice onClosed callback ($runId)")
                }
                else -> {
                    cameraDeviceCloseAcknowledged = true
                    cameraDeviceClosedLatch.countDown()
                    if (!closeStarted) {
                        IllegalStateException(
                            "Accepted CameraDevice closed before probe teardown began ($runId)"
                        )
                    } else {
                        null
                    }
                }
            }

            if (callbackFailure != null) {
                closeCallbackFailures.add(callbackFailure)
                if (!closeStarted) {
                    runFailure = callbackFailure
                }
            }
        }
        runFailure?.let { markTerminal(it) }
    }

    private fun registerConfiguredSession(
        session: CameraCaptureSession,
        signal: ResourceSignal<CameraCaptureSession>
    ) {
        var rejection: Throwable? = null
        var accepted = false
        synchronized(ownershipLock) {
            when {
                terminal -> Unit
                sessionSignal !== signal -> {
                    rejection = IllegalStateException("Stale CameraCaptureSession callback ($runId)")
                }
                captureSession != null || acceptedCaptureSession != null -> {
                    rejection = IllegalStateException("Duplicate CameraCaptureSession callback ($runId)")
                }
                else -> {
                    captureSession = session
                    acceptedCaptureSession = session
                    signal.complete(session)
                    accepted = true
                }
            }
        }

        if (!accepted) {
            try {
                session.close()
            } catch (e: Exception) {
                val closeFailure = IllegalStateException(
                    "Failed to close rejected CameraCaptureSession callback ($runId)",
                    e
                )
                rejection?.addSuppressed(closeFailure) ?: run { rejection = closeFailure }
            }
            rejection?.let { markTerminal(it) }
        }
    }

    private fun recordCaptureSessionClosed(session: CameraCaptureSession) {
        var runFailure: Throwable? = null
        synchronized(ownershipLock) {
            val accepted = acceptedCaptureSession
            val callbackFailure = when {
                accepted == null -> {
                    IllegalStateException(
                        "Unexpected CameraCaptureSession onClosed without an accepted session ($runId)"
                    )
                }
                accepted !== session -> {
                    IllegalStateException(
                        "CameraCaptureSession onClosed identity did not match the accepted session ($runId)"
                    )
                }
                captureSessionCloseAcknowledged -> {
                    IllegalStateException("Duplicate CameraCaptureSession onClosed callback ($runId)")
                }
                else -> {
                    captureSessionCloseAcknowledged = true
                    if (!closeStarted) {
                        IllegalStateException(
                            "Accepted CameraCaptureSession closed before probe teardown began ($runId)"
                        )
                    } else {
                        null
                    }
                }
            }

            if (callbackFailure != null) {
                closeCallbackFailures.add(callbackFailure)
                if (!closeStarted) {
                    runFailure = callbackFailure
                }
            }
        }
        runFailure?.let { markTerminal(it) }
    }

    private fun markTerminal(failure: Throwable) {
        val correlator: SingleStillCorrelator?
        val effectiveFailure: Throwable
        synchronized(ownershipLock) {
            if (terminalFailure == null) {
                terminalFailure = failure
            }
            effectiveFailure = terminalFailure ?: failure
            terminal = true
            openSignal?.fail(effectiveFailure)
            sessionSignal?.fail(effectiveFailure)
            correlator = activeCorrelator
        }
        correlator?.fail(
            "Probe run $runId became terminal: ${effectiveFailure.message}",
            effectiveFailure
        )
    }

    private fun throwIfTerminalFailure() {
        synchronized(ownershipLock) {
            terminalFailure?.let { throw it }
        }
    }

    private fun preferTerminalFailure(fallback: Throwable): Throwable =
        synchronized(ownershipLock) { terminalFailure ?: fallback }

    private fun <T : Any> awaitResource(
        signal: ResourceSignal<T>,
        timeoutMs: Long,
        timeoutMessage: String
    ): T {
        val completed = try {
            signal.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            val failure = IllegalStateException("Interrupted: $timeoutMessage", e)
            markTerminal(failure)
            throw failure
        }

        if (!completed) {
            val failure = IllegalStateException(timeoutMessage)
            markTerminal(failure)
            throw failure
        }
        return signal.getOrThrow()
    }

    private fun runCallbackBarrier(
        callbackHandler: Handler,
        description: String,
        action: () -> Unit
    ) {
        val latch = CountDownLatch(1)
        val actionFailure = AtomicReference<Throwable?>(null)
        val posted = try {
            callbackHandler.post {
                try {
                    action()
                } catch (failure: Throwable) {
                    actionFailure.set(failure)
                } finally {
                    latch.countDown()
                }
            }
        } catch (e: Exception) {
            val failure = IllegalStateException(
                "Callback handler rejected $description ($runId)",
                e
            )
            markTerminal(failure)
            throw failure
        }

        if (!posted) {
            val failure = IllegalStateException(
                "Callback handler rejected $description ($runId)"
            )
            markTerminal(failure)
            throw failure
        }

        val completed = try {
            latch.await(STILL_CORRELATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            val failure = IllegalStateException(
                "Interrupted waiting for $description ($runId)",
                e
            )
            markTerminal(failure)
            throw failure
        }

        if (!completed) {
            val failure = IllegalStateException("Timed out waiting for $description ($runId)")
            markTerminal(failure)
            throw failure
        }

        actionFailure.get()?.let { cause ->
            val failure = IllegalStateException("Failed during $description ($runId)", cause)
            markTerminal(failure)
            throw failure
        }
    }

    private fun awaitCloseAcknowledgement(
        resourceName: String,
        resourceDelivered: Boolean,
        acknowledgementLatch: CountDownLatch,
        timeoutMs: Long,
        acknowledgementObserved: () -> Boolean,
        failures: MutableList<Throwable>
    ) {
        if (!resourceDelivered) return

        val acknowledged = try {
            acknowledgementLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            failures.add(
                IllegalStateException(
                    "Interrupted waiting for $resourceName onClosed acknowledgement ($runId)",
                    e
                )
            )
            return
        }

        if (!acknowledged) {
            failures.add(
                IllegalStateException(
                    "Timed out after ${timeoutMs}ms waiting for $resourceName onClosed acknowledgement ($runId)"
                )
            )
            return
        }

        if (!acknowledgementObserved()) {
            failures.add(
                IllegalStateException(
                    "$resourceName close latch completed without exact-identity acknowledgement ($runId)"
                )
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun createTwoSurfaceSession(
        device: CameraDevice,
        previewSurface: Surface,
        readerSurface: Surface,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = Executor { command ->
                if (!handler.post(command)) {
                    throw RejectedExecutionException("Handler thread rejected command")
                }
            }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(
                    OutputConfiguration(previewSurface),
                    OutputConfiguration(readerSurface)
                ),
                executor,
                callback
            )
            device.createCaptureSession(config)
        } else {
            device.createCaptureSession(
                listOf(previewSurface, readerSurface),
                callback,
                handler
            )
        }
    }

    /**
     * Fully releases all probe resources and joins the callback thread.
     */
    fun close() {
        val snapshot = synchronized(ownershipLock) {
            if (closeFinished) {
                cleanupFailure?.let { throw it }
                return
            }
            check(!closeStarted) { "Concurrent close is not supported for probe $runId" }

            closeStarted = true
            terminal = true
            val terminalClose = terminalFailure
                ?: IllegalStateException("Probe run $runId entered teardown")
            openSignal?.fail(terminalClose)
            sessionSignal?.fail(terminalClose)

            ProbeCleanupSnapshot(
                session = captureSession,
                device = cameraDevice,
                captureSessionDelivered = acceptedCaptureSession != null,
                cameraDeviceDelivered = acceptedCameraDevice != null,
                handler = handler,
                reader = imageReader,
                correlator = activeCorrelator,
                surface = previewSurface,
                thread = callbackThread,
                threadStarted = callbackThreadStarted
            ).also {
                captureSession = null
                cameraDevice = null
                imageReader = null
                activeCorrelator = null
                previewSurface = null
                handler = null
            }
        }

        val failures = mutableListOf<Throwable>()
        fun attempt(description: String, action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                failures.add(
                    IllegalStateException("$description failed for probe $runId", failure)
                )
            }
        }

        snapshot.session?.let { session ->
            attempt("stopRepeating") { session.stopRepeating() }
            attempt("abortCaptures") { session.abortCaptures() }
            attempt("CameraCaptureSession.close") { session.close() }
        }
        snapshot.device?.let { device ->
            attempt("CameraDevice.close") { device.close() }
        }
        snapshot.reader?.let { reader ->
            attempt("ImageReader listener removal") {
                reader.setOnImageAvailableListener(null, null)
            }
        }
        snapshot.correlator?.let { correlator ->
            attempt("active correlator close") { correlator.close() }
        }
        snapshot.reader?.let { reader ->
            attempt("ImageReader.close") { reader.close() }
        }
        snapshot.surface?.let { surface ->
            attempt("preview Surface.release") { surface.release() }
        }

        awaitCloseAcknowledgement(
            resourceName = "CameraDevice",
            resourceDelivered = snapshot.cameraDeviceDelivered,
            acknowledgementLatch = cameraDeviceClosedLatch,
            timeoutMs = DEVICE_CLOSE_ACK_TIMEOUT_MS,
            acknowledgementObserved = {
                synchronized(ownershipLock) { cameraDeviceCloseAcknowledged }
            },
            failures = failures
        )

        val callbackHandler = snapshot.handler
        if (callbackHandler == null) {
            if (snapshot.threadStarted) {
                failures.add(
                    IllegalStateException(
                        "Callback Handler was unavailable for close acknowledgement barrier ($runId)"
                    )
                )
            }
        } else {
            attempt("close acknowledgement callback barrier") {
                runCallbackBarrier(
                    callbackHandler,
                    "close acknowledgement callback barrier"
                ) {}
            }
        }

        val closeCallbackOutcome = synchronized(ownershipLock) {
            Triple(
                cameraDeviceCloseAcknowledged,
                captureSessionCloseAcknowledged,
                closeCallbackFailures.toList()
            )
        }
        failures.addAll(closeCallbackOutcome.third)

        val capturedThread = snapshot.thread
        if (capturedThread != null && snapshot.threadStarted && capturedThread.isAlive) {
            val quitAccepted = try {
                capturedThread.quitSafely()
            } catch (failure: Throwable) {
                failures.add(
                    IllegalStateException("HandlerThread.quitSafely failed for probe $runId", failure)
                )
                false
            }
            if (!quitAccepted && capturedThread.isAlive) {
                failures.add(
                    IllegalStateException("HandlerThread rejected quitSafely for probe $runId")
                )
            }

            try {
                capturedThread.join(THREAD_JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                failures.add(
                    IllegalStateException("Interrupted joining callback thread for probe $runId", e)
                )
            }
        }

        val callbackThreadTerminated = capturedThread?.isAlive != true
        if (!callbackThreadTerminated) {
            failures.add(
                IllegalStateException(
                    "Callback thread remained alive after ${THREAD_JOIN_TIMEOUT_MS}ms for probe $runId"
                )
            )
        }

        val evidence = Camera2ProbeTeardownEvidence(
            cameraDeviceDelivered = snapshot.cameraDeviceDelivered,
            cameraDeviceCloseAcknowledged = closeCallbackOutcome.first,
            captureSessionDelivered = snapshot.captureSessionDelivered,
            captureSessionCloseAcknowledged = closeCallbackOutcome.second,
            callbackThreadStarted = snapshot.threadStarted,
            callbackThreadTerminated = callbackThreadTerminated,
            cleanupFailureMessages = failures.map { failure ->
                failure.message ?: failure::class.java.simpleName
            }
        )
        val aggregateFailure = if (failures.isEmpty()) {
            null
        } else {
            IllegalStateException(
                "Probe cleanup failed ($runId): ${evidence.cleanupFailureMessages.joinToString()}"
            ).also { aggregate ->
                failures.forEach(aggregate::addSuppressed)
            }
        }

        synchronized(ownershipLock) {
            retainedTeardownEvidence = evidence
            cleanupFailure = aggregateFailure
            closeFinished = true
        }

        aggregateFailure?.let { throw it }
    }

    /**
     * Returns binding teardown evidence after [runProbe] or [close] completes.
     */
    fun teardownEvidence(): Camera2ProbeTeardownEvidence = synchronized(ownershipLock) {
        check(closeFinished) { "Teardown has not completed for probe $runId" }
        retainedTeardownEvidence
            ?: throw IllegalStateException("Teardown evidence is unavailable for probe $runId")
    }
}
