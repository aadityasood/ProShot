package com.proshot.app.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import java.util.Collections
import java.util.IdentityHashMap
import javax.inject.Inject

private const val TAG = "Camera2CaptureResourceOwner"

/**
 * Grace period for an already-submitted Camera2 open or session callback to
 * reach a terminal callback after owner cleanup begins.
 *
 * The capture coroutine has its own 8-second operation timeout; this additional
 * one-second window exists only to drain an already-enqueued terminal callback
 * without allowing the per-capture callback thread to become unbounded.
 *
 * When the grace expires, the callback looper is terminated so a missing vendor
 * callback cannot retain one per-capture thread indefinitely. A callback that
 * arrives before expiry is still delivered and its late resource is closed.
 */
internal const val CAMERA2_CALLBACK_TERMINAL_GRACE_MS = 1_000L

internal fun interface ScheduledTerminalAction {
    fun cancel()
}

internal fun interface TerminalActionScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): ScheduledTerminalAction
}

internal enum class Camera2CallbackPhase {
    IDLE,
    OPEN_PENDING,
    OPEN_TERMINAL,
    SESSION_PENDING,
    SESSION_TERMINAL,
    FORCED_TERMINAL
}

/**
 * Synchronized register-or-close gate shared by the Android owner and pure JVM
 * tests. Resource publication and closed-state evaluation occur under one lock.
 */
internal class Camera2ResourceGate<Device : Any, Session : Any, Reader : Any>(
    reader: Reader,
    private val scheduler: TerminalActionScheduler,
    private val closeDevice: (Device) -> Unit,
    private val closeSession: (Session) -> Unit,
    private val closeReader: (Reader) -> Unit,
    private val onTerminal: () -> Unit
) {
    private val lock = Any()

    private var closed = false
    private var phase = Camera2CallbackPhase.IDLE
    private var device: Device? = null
    private var session: Session? = null
    private var reader: Reader? = reader
    private var scheduledTerminalAction: ScheduledTerminalAction? = null
    private var terminalSignalled = false
    private val closedDevices = Collections.newSetFromMap(
        IdentityHashMap<Device, Boolean>()
    )
    private val closedSessions = Collections.newSetFromMap(
        IdentityHashMap<Session, Boolean>()
    )
    private val closedReaders = Collections.newSetFromMap(
        IdentityHashMap<Reader, Boolean>()
    )

    fun getReader(): Reader? = synchronized(lock) { reader }

    fun getDevice(): Device? = synchronized(lock) { device }

    fun getSession(): Session? = synchronized(lock) { session }

    fun markOpenRequested() {
        synchronized(lock) {
            check(!closed) { "Camera2 owner is closed" }
            check(phase == Camera2CallbackPhase.IDLE) {
                "Camera open was already requested"
            }
            phase = Camera2CallbackPhase.OPEN_PENDING
        }
    }

    fun registerDevice(deliveredDevice: Device): Boolean {
        var closeDelivered = false
        var signalTerminal = false
        val accepted = synchronized(lock) {
            when {
                closed -> {
                    if (phase == Camera2CallbackPhase.OPEN_PENDING) {
                        phase = Camera2CallbackPhase.OPEN_TERMINAL
                    }
                    closeDelivered = closedDevices.add(deliveredDevice)
                    signalTerminal = finishTerminalIfPossibleLocked()
                    false
                }
                phase == Camera2CallbackPhase.OPEN_PENDING -> {
                    phase = Camera2CallbackPhase.OPEN_TERMINAL
                    device = deliveredDevice
                    true
                }
                device === deliveredDevice -> true
                else -> {
                    closeDelivered = closedDevices.add(deliveredDevice)
                    false
                }
            }
        }
        if (closeDelivered) {
            closeDevice(deliveredDevice)
        }
        if (signalTerminal) {
            onTerminal()
        }
        return accepted
    }

    fun registerOpenFailure(deliveredDevice: Device? = null) {
        var signalTerminal = false
        var closeDelivered = false
        synchronized(lock) {
            if (phase == Camera2CallbackPhase.OPEN_PENDING) {
                phase = Camera2CallbackPhase.OPEN_TERMINAL
            }
            if (deliveredDevice != null && device === deliveredDevice) {
                device = null
            }
            if (deliveredDevice != null) {
                closeDelivered = closedDevices.add(deliveredDevice)
            }
            signalTerminal = finishTerminalIfPossibleLocked()
        }
        if (closeDelivered && deliveredDevice != null) {
            closeDevice(deliveredDevice)
        }
        if (signalTerminal) {
            onTerminal()
        }
    }

    fun markOpenSubmissionFailed() {
        registerOpenFailure()
    }

    fun markSessionRequested() {
        synchronized(lock) {
            check(!closed) { "Camera2 owner is closed" }
            check(phase == Camera2CallbackPhase.OPEN_TERMINAL && device != null) {
                "Camera device is not ready for session configuration"
            }
            phase = Camera2CallbackPhase.SESSION_PENDING
        }
    }

    fun registerSession(deliveredSession: Session): Boolean {
        var closeDelivered = false
        var signalTerminal = false
        val accepted = synchronized(lock) {
            when {
                closed -> {
                    if (phase == Camera2CallbackPhase.SESSION_PENDING) {
                        phase = Camera2CallbackPhase.SESSION_TERMINAL
                    }
                    closeDelivered = closedSessions.add(deliveredSession)
                    signalTerminal = finishTerminalIfPossibleLocked()
                    false
                }
                phase == Camera2CallbackPhase.SESSION_PENDING -> {
                    phase = Camera2CallbackPhase.SESSION_TERMINAL
                    session = deliveredSession
                    true
                }
                session === deliveredSession -> true
                else -> {
                    closeDelivered = closedSessions.add(deliveredSession)
                    false
                }
            }
        }
        if (closeDelivered) {
            closeSession(deliveredSession)
        }
        if (signalTerminal) {
            onTerminal()
        }
        return accepted
    }

    fun registerSessionFailure(deliveredSession: Session? = null) {
        var signalTerminal = false
        var closeDelivered = false
        synchronized(lock) {
            if (phase == Camera2CallbackPhase.SESSION_PENDING) {
                phase = Camera2CallbackPhase.SESSION_TERMINAL
            }
            if (deliveredSession != null && session === deliveredSession) {
                session = null
            }
            if (deliveredSession != null) {
                closeDelivered = closedSessions.add(deliveredSession)
            }
            signalTerminal = finishTerminalIfPossibleLocked()
        }
        if (closeDelivered && deliveredSession != null) {
            closeSession(deliveredSession)
        }
        if (signalTerminal) {
            onTerminal()
        }
    }

    fun markSessionSubmissionFailed() {
        registerSessionFailure()
    }

    fun close() {
        var deviceToClose: Device? = null
        var sessionToClose: Session? = null
        var readerToClose: Reader? = null
        var signalTerminal = false

        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            deviceToClose = device
            sessionToClose = session
            readerToClose = reader
            if (deviceToClose != null && !closedDevices.add(deviceToClose!!)) {
                deviceToClose = null
            }
            if (sessionToClose != null && !closedSessions.add(sessionToClose!!)) {
                sessionToClose = null
            }
            if (readerToClose != null && !closedReaders.add(readerToClose!!)) {
                readerToClose = null
            }
            device = null
            session = null
            reader = null

            if (isCallbackPendingLocked()) {
                try {
                    scheduledTerminalAction = scheduler.schedule(
                        CAMERA2_CALLBACK_TERMINAL_GRACE_MS,
                        ::forceTerminal
                    )
                } catch (_: Throwable) {
                    phase = Camera2CallbackPhase.FORCED_TERMINAL
                    signalTerminal = signalTerminalLocked()
                }
            } else {
                signalTerminal = signalTerminalLocked()
            }
        }

        sessionToClose?.let(closeSession)
        deviceToClose?.let(closeDevice)
        readerToClose?.let(closeReader)
        if (signalTerminal) {
            onTerminal()
        }
    }

    private fun forceTerminal() {
        val signalTerminal = synchronized(lock) {
            if (!closed || !isCallbackPendingLocked()) {
                false
            } else {
                phase = Camera2CallbackPhase.FORCED_TERMINAL
                signalTerminalLocked()
            }
        }
        if (signalTerminal) {
            onTerminal()
        }
    }

    private fun finishTerminalIfPossibleLocked(): Boolean {
        return if (closed && !isCallbackPendingLocked()) {
            signalTerminalLocked()
        } else {
            false
        }
    }

    private fun isCallbackPendingLocked(): Boolean {
        return phase == Camera2CallbackPhase.OPEN_PENDING ||
            phase == Camera2CallbackPhase.SESSION_PENDING
    }

    private fun signalTerminalLocked(): Boolean {
        if (terminalSignalled) {
            return false
        }
        terminalSignalled = true
        scheduledTerminalAction?.cancel()
        scheduledTerminalAction = null
        return true
    }
}

private object MainLooperTerminalActionScheduler : TerminalActionScheduler {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun schedule(
        delayMs: Long,
        action: () -> Unit
    ): ScheduledTerminalAction {
        val runnable = Runnable(action)
        check(handler.postDelayed(runnable, delayMs)) {
            "Unable to schedule Camera2 callback cleanup deadline"
        }
        return ScheduledTerminalAction {
            handler.removeCallbacks(runnable)
        }
    }
}

internal class Camera2CaptureResourceOwner(
    imageReader: ImageReader,
    callbackThread: HandlerThread,
    val handler: Handler,
    scheduler: TerminalActionScheduler = MainLooperTerminalActionScheduler
) {
    private val gate = Camera2ResourceGate<CameraDevice, CameraCaptureSession, ImageReader>(
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
            Log.d(TAG, "Camera2 callback ownership reached a terminal state")
            callbackThread.quitSafely()
        }
    )

    fun getReader(): ImageReader? = gate.getReader()

    fun getDevice(): CameraDevice? = gate.getDevice()

    fun getSession(): CameraCaptureSession? = gate.getSession()

    fun markOpenRequested() = gate.markOpenRequested()

    fun registerDevice(device: CameraDevice): Boolean = gate.registerDevice(device)

    fun registerOpenFailure(device: CameraDevice? = null) = gate.registerOpenFailure(device)

    fun markOpenSubmissionFailed() = gate.markOpenSubmissionFailed()

    fun markSessionRequested() = gate.markSessionRequested()

    fun registerSession(session: CameraCaptureSession): Boolean = gate.registerSession(session)

    fun registerSessionFailure(session: CameraCaptureSession? = null) =
        gate.registerSessionFailure(session)

    fun markSessionSubmissionFailed() = gate.markSessionSubmissionFailed()

    fun close() = gate.close()
}

/** Creates one exception-safe Camera2 resource owner per still capture. */
class Camera2CaptureResourceOwnerFactory @Inject constructor() {
    /**
     * Creates the YUV reader before starting the callback thread so reader
     * construction failure cannot leak a thread. The queue capacity remains 4.
     */
    internal fun create(targetSize: Size): Camera2CaptureResourceOwner {
        val imageReader = ImageReader.newInstance(
            targetSize.width,
            targetSize.height,
            ImageFormat.YUV_420_888,
            4
        )
        var callbackThread: HandlerThread? = null
        try {
            callbackThread = HandlerThread("Camera2CaptureResourceOwnerThread").apply {
                start()
            }
            return Camera2CaptureResourceOwner(
                imageReader = imageReader,
                callbackThread = callbackThread,
                handler = Handler(callbackThread.looper)
            )
        } catch (error: Throwable) {
            try {
                imageReader.close()
            } catch (_: Exception) {
                // Preserve the construction failure as the primary error.
            }
            callbackThread?.takeIf { it.isAlive }?.quitSafely()
            throw error
        }
    }
}
