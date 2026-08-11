package com.proshot.app.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.concurrent.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "PersistentCamera2ResourceOwner"
private const val DEVICE_CLOSE_ACK_TIMEOUT_MS = 1_000L
private const val OWNER_CLOSE_WAIT_TIMEOUT_MS = 2_500L
private const val CONSTRUCTION_THREAD_JOIN_TIMEOUT_MS = 1_500L

private object PersistentMainLooperTerminalActionScheduler : TerminalActionScheduler {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun schedule(delayMs: Long, action: () -> Unit): ScheduledTerminalAction {
        val runnable = Runnable(action)
        check(handler.postDelayed(runnable, delayMs)) {
            "Main looper rejected persistent Camera2 terminal action"
        }
        return ScheduledTerminalAction { handler.removeCallbacks(runnable) }
    }
}

/**
 * Pure idempotent close-order seam used by the persistent Android owner.
 */
internal class PersistentOwnerCloseCoordinator(
    private val clearImageListener: () -> Unit,
    private val closeRouter: () -> Unit,
    private val closeGate: () -> Unit,
    private val releasePreviewSurface: () -> Unit
) {
    private val closed = AtomicBoolean(false)

    fun close(): List<Throwable> {
        if (!closed.compareAndSet(false, true)) return emptyList()
        val failures = mutableListOf<Throwable>()
        listOf(
            clearImageListener,
            closeRouter,
            closeGate,
            releasePreviewSurface
        ).forEach { closeStep ->
            try {
                closeStep()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        return failures
    }
}

/** Exact terminal failure raised when persistent-owner close wins submission. */
internal class PersistentCamera2OwnerClosedException :
    IllegalStateException("Persistent Camera2 owner is closing")

/** Construction failed and bounded callback-thread death could not be proved. */
internal class PersistentCamera2ConstructionBarrierException(
    constructionCause: Exception
) : IllegalStateException(
    "Persistent Camera2 callback thread survived construction cleanup",
    constructionCause
)

/**
 * Generic construction cleanup seam. Resource cleanup never replaces the original
 * failure, and an ordinary [Exception] cannot escape while a started thread remains
 * unproven. The production join is invoked from the controller's Default dispatcher.
 */
internal fun <Reader, PreviewSurface, CallbackThread> throwAfterPersistentConstructionFailure(
    constructionFailure: Throwable,
    reader: Reader,
    previewSurface: PreviewSurface,
    callbackThread: CallbackThread?,
    closeReader: (Reader) -> Unit,
    releasePreviewSurface: (PreviewSurface) -> Unit,
    quitSafely: (CallbackThread) -> Unit,
    joinBounded: (CallbackThread, Long) -> Unit,
    isThreadAlive: (CallbackThread) -> Boolean,
    joinTimeoutMs: Long = CONSTRUCTION_THREAD_JOIN_TIMEOUT_MS
): Nothing {
    val cleanupFailures = mutableListOf<Throwable>()

    fun attempt(step: () -> Unit) {
        try {
            step()
        } catch (failure: Throwable) {
            cleanupFailures += failure
        }
    }

    attempt { closeReader(reader) }
    attempt { releasePreviewSurface(previewSurface) }

    val threadDeathProved = if (callbackThread == null) {
        true
    } else {
        attempt { quitSafely(callbackThread) }
        attempt { joinBounded(callbackThread, joinTimeoutMs) }
        try {
            !isThreadAlive(callbackThread)
        } catch (failure: Throwable) {
            cleanupFailures += failure
            false
        }
    }

    cleanupFailures.forEach { cleanupFailure ->
        if (cleanupFailure !== constructionFailure) {
            constructionFailure.addSuppressed(cleanupFailure)
        }
    }

    if (!threadDeathProved) {
        if (constructionFailure is Exception) {
            throw PersistentCamera2ConstructionBarrierException(constructionFailure)
        }
        constructionFailure.addSuppressed(
            IllegalStateException(
                "Persistent Camera2 callback thread death was not proved after construction failure"
            )
        )
    }
    throw constructionFailure
}

/** Synchronous boundary around one Camera2 request construction and submission. */
internal interface Camera2RequestSubmissionGate {
    fun <T> withOpenSubmission(block: () -> T): T
}

/** Rollback-route seam whose resource owner outlives the still engine call. */
internal object AlwaysOpenCamera2RequestSubmissionGate : Camera2RequestSubmissionGate {
    override fun <T> withOpenSubmission(block: () -> T): T = block()
}

/**
 * Prevents a persistent owner from closing Camera2 resources while a synchronous
 * request construction/submission call is using them.
 */
internal class Camera2SubmissionCloseGate : Camera2RequestSubmissionGate {
    private val lock = ReentrantLock()
    private val noActiveSubmissions = lock.newCondition()
    private var closed = false
    private var activeSubmissions = 0

    override fun <T> withOpenSubmission(block: () -> T): T {
        lock.withLock {
            if (closed) throw PersistentCamera2OwnerClosedException()
            activeSubmissions += 1
        }
        return try {
            block()
        } finally {
            lock.withLock {
                activeSubmissions -= 1
                check(activeSubmissions >= 0) { "Camera2 submission count underflow" }
                if (activeSubmissions == 0) noActiveSubmissions.signalAll()
            }
        }
    }

    /**
     * Invalidates future submissions and waits for calls that already entered.
     * Callback parameters are non-blocking pure-test signals, never physical close.
     */
    fun closeAndAwaitSubmissions(
        onCloseStarted: () -> Unit = {},
        onSubmissionsDrained: () -> Unit = {}
    ): Boolean {
        lock.withLock {
            if (closed) return false
            closed = true
            onCloseStarted()
            while (activeSubmissions > 0) {
                noActiveSubmissions.awaitUninterruptibly()
            }
            onSubmissionsDrained()
            return true
        }
    }
}

/**
 * Pure identity-safe device-close acknowledgement and timeout coordinator.
 */
internal class PersistentDeviceCloseBarrier<T>(
    private val scheduler: TerminalActionScheduler,
    private val timeoutMs: Long,
    private val terminate: () -> Unit
) {
    private val lock = Any()
    private var acceptedDevice: T? = null
    private var closeAcknowledged = false
    private var gateTerminal = false
    private var terminationStarted = false
    private var timeout: ScheduledTerminalAction? = null

    fun recordDeliveredDevice(device: T) {
        synchronized(lock) {
            if (acceptedDevice == null) acceptedDevice = device
        }
    }

    fun registerDeviceClosed(device: T) {
        val shouldTerminate = synchronized(lock) {
            if (acceptedDevice !== device || closeAcknowledged) {
                false
            } else {
                closeAcknowledged = true
                gateTerminal
            }
        }
        if (shouldTerminate) terminateOnce()
    }

    fun onGateTerminal() {
        val shouldTerminate = synchronized(lock) {
            gateTerminal = true
            acceptedDevice == null || closeAcknowledged
        }
        if (shouldTerminate) {
            terminateOnce()
            return
        }

        val scheduled = try {
            scheduler.schedule(timeoutMs, ::terminateOnce)
        } catch (failure: Throwable) {
            terminateOnce()
            throw failure
        }
        synchronized(lock) {
            if (terminationStarted) {
                scheduled.cancel()
            } else {
                timeout = scheduled
            }
        }
    }

    fun forceTerminate() = terminateOnce()

    private fun terminateOnce() {
        val scheduled = synchronized(lock) {
            if (terminationStarted) return
            terminationStarted = true
            timeout.also { timeout = null }
        }
        scheduled?.cancel()
        terminate()
    }
}

/** Owns every physical resource for one direct Camera2 attachment generation. */
internal class PersistentCamera2ResourceOwner(
    val surfaceTexture: SurfaceTexture,
    val previewSurface: Surface,
    private val imageReader: ImageReader,
    val imageRouter: Camera2ImageReaderRouter,
    private val callbackThread: HandlerThread,
    val handler: Handler,
    private val scheduler: TerminalActionScheduler =
        PersistentMainLooperTerminalActionScheduler
) : AutoCloseable {
    private val terminalSignal = CompletableDeferred<Unit>()
    val submissionCloseGate = Camera2SubmissionCloseGate()
    private val deviceCloseBarrier = PersistentDeviceCloseBarrier<CameraDevice>(
        scheduler = scheduler,
        timeoutMs = DEVICE_CLOSE_ACK_TIMEOUT_MS,
        terminate = {
            callbackThread.quitSafely()
            terminalSignal.complete(Unit)
        }
    )

    val gate = Camera2ResourceGate<CameraDevice, CameraCaptureSession, ImageReader>(
        reader = imageReader,
        scheduler = scheduler,
        closeDevice = { device ->
            try {
                device.close()
            } catch (error: Exception) {
                Log.w(TAG, "Error closing camera device", error)
            }
        },
        closeSession = { session ->
            try {
                session.stopRepeating()
            } catch (_: Exception) {
                // A session may already be stopped or closed.
            }
            try {
                session.abortCaptures()
            } catch (_: Exception) {
                // A session may already be stopped or closed.
            }
            try {
                session.close()
            } catch (error: Exception) {
                Log.w(TAG, "Error closing capture session", error)
            }
        },
        closeReader = { reader ->
            try {
                reader.close()
            } catch (error: Exception) {
                Log.w(TAG, "Error closing ImageReader", error)
            }
        },
        onTerminal = {
            Log.d(TAG, "Persistent Camera2 owner reached terminal state")
            onGateTerminal()
        }
    )

    private val closeCoordinator = PersistentOwnerCloseCoordinator(
        clearImageListener = {
            try {
                imageReader.setOnImageAvailableListener(null, null)
            } catch (error: Exception) {
                Log.w(TAG, "Error clearing ImageReader listener", error)
            }
        },
        closeRouter = imageRouter::close,
        closeGate = gate::close,
        releasePreviewSurface = {
            try {
                previewSurface.release()
            } catch (error: Exception) {
                Log.w(TAG, "Error releasing preview Surface wrapper", error)
            }
        }
    )

    init {
        imageReader.setOnImageAvailableListener(imageRouter, handler)
    }

    fun getReader(): ImageReader? = gate.getReader()

    fun getDevice(): CameraDevice? = gate.getDevice()

    fun getSession(): CameraCaptureSession? = gate.getSession()

    fun markOpenRequested() = gate.markOpenRequested()

    fun registerDevice(device: CameraDevice): Boolean {
        deviceCloseBarrier.recordDeliveredDevice(device)
        return gate.registerDevice(device)
    }

    fun registerOpenFailure(device: CameraDevice? = null) {
        device?.let(deviceCloseBarrier::recordDeliveredDevice)
        gate.registerOpenFailure(device)
    }

    fun registerDeviceClosed(device: CameraDevice) {
        deviceCloseBarrier.registerDeviceClosed(device)
    }

    fun markOpenSubmissionFailed() = gate.markOpenSubmissionFailed()

    fun markSessionRequested() = gate.markSessionRequested()

    fun registerSession(session: CameraCaptureSession): Boolean = gate.registerSession(session)

    fun registerSessionFailure(session: CameraCaptureSession? = null) =
        gate.registerSessionFailure(session)

    fun markSessionSubmissionFailed() = gate.markSessionSubmissionFailed()

    override fun close() {
        if (submissionCloseGate.closeAndAwaitSubmissions()) {
            closeCoordinator.close().forEach { failure ->
                Log.w(TAG, "Persistent Camera2 close step failed", failure)
            }
        }
        // SurfaceTexture is owned by TextureView and is never released here.
    }

    suspend fun awaitClosed() {
        val completed = withTimeoutOrNull(OWNER_CLOSE_WAIT_TIMEOUT_MS) {
            terminalSignal.await()
            true
        } ?: false
        if (!completed) {
            deviceCloseBarrier.forceTerminate()
        }
        withContext(Dispatchers.IO) {
            callbackThread.join(DEVICE_CLOSE_ACK_TIMEOUT_MS + 500L)
            check(!callbackThread.isAlive) {
                "Persistent Camera2 callback thread did not terminate"
            }
        }
    }

    private fun onGateTerminal() {
        try {
            deviceCloseBarrier.onGateTerminal()
        } catch (failure: Throwable) {
            Log.w(TAG, "Unable to schedule device-close acknowledgement timeout", failure)
            deviceCloseBarrier.forceTerminate()
        }
    }
}

/** Creates one exception-safe persistent owner per direct preview attachment. */
class PersistentCamera2ResourceOwnerFactory @Inject constructor() {
    internal fun create(
        surfaceTexture: SurfaceTexture,
        targetSize: Size
    ): PersistentCamera2ResourceOwner {
        val previewSurface = Surface(surfaceTexture)
        val imageReader = try {
            ImageReader.newInstance(
                targetSize.width,
                targetSize.height,
                ImageFormat.YUV_420_888,
                4
            )
        } catch (error: Throwable) {
            try {
                previewSurface.release()
            } catch (_: Exception) {
                // Preserve the construction failure.
            }
            throw error
        }

        var callbackThread: HandlerThread? = null
        try {
            callbackThread = HandlerThread("PersistentCamera2ResourceOwnerThread")
            callbackThread.start()
            val handler = Handler(callbackThread.looper)
            return PersistentCamera2ResourceOwner(
                surfaceTexture = surfaceTexture,
                previewSurface = previewSurface,
                imageReader = imageReader,
                imageRouter = Camera2ImageReaderRouter(),
                callbackThread = callbackThread,
                handler = handler
            )
        } catch (error: Throwable) {
            throwAfterPersistentConstructionFailure(
                constructionFailure = error,
                reader = imageReader,
                previewSurface = previewSurface,
                callbackThread = callbackThread,
                closeReader = ImageReader::close,
                releasePreviewSurface = Surface::release,
                quitSafely = { thread ->
                    thread.quitSafely()
                    Unit
                },
                joinBounded = { thread, timeoutMs -> thread.join(timeoutMs) },
                isThreadAlive = { thread -> thread.isAlive }
            )
        }
    }
}
