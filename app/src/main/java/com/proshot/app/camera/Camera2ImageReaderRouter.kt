package com.proshot.app.camera

import android.media.Image
import android.media.ImageReader
import android.util.Log

private const val TAG = "Camera2ImageReaderRouter"

/** Pure FIFO acquisition seam used by JVM tests. */
fun interface FrameCandidateProvider<T> {
    fun acquireNext(): T?
}

/** Identity token for one correlator arming. */
class RouterArmToken internal constructor(internal val id: Long)

/** Identity and activity epoch for one session-ready registration. */
class SessionGateToken internal constructor(val epoch: Long)

/**
 * Owner-lifetime FIFO ImageReader and session-activity router.
 *
 * Image acquisition, closure, and correlator delivery always occur outside the
 * state lock. The normal state drains every unexpected image.
 */
internal class Camera2ImageReaderRouter : ImageReader.OnImageAvailableListener, AutoCloseable {
    enum class Mode {
        DRAINING,
        ARMING,
        CORRELATING,
        CLOSED
    }

    private val lock = Any()
    private val acquisitionLock = Any()
    private var mode = Mode.DRAINING
    private var armCounter = 0L
    private var activeCorrelator: CaptureTimestampCorrelator<Image>? = null
    private var currentArmToken: RouterArmToken? = null

    private var gateEpochCounter = 0L
    private var activeReadyGate: CameraSessionReadyGate? = null
    private var currentGateToken: SessionGateToken? = null
    private var currentGateSawActive = false

    val currentMode: Mode
        get() = synchronized(lock) { mode }

    val hasActiveCorrelator: Boolean
        get() = synchronized(lock) { activeCorrelator != null }

    val hasActiveReadyGate: Boolean
        get() = synchronized(lock) { activeReadyGate != null }

    override fun onImageAvailable(reader: ImageReader) {
        while (true) {
            val (route, image) = synchronized(acquisitionLock) {
                val selectedRoute = synchronized(lock) {
                    when (mode) {
                        Mode.CORRELATING -> activeCorrelator
                        Mode.ARMING, Mode.DRAINING, Mode.CLOSED -> DRAIN_SENTINEL
                    }
                }
                val acquired = try {
                    reader.acquireNextImage()
                } catch (error: Exception) {
                    Log.w(TAG, "Error acquiring image from ImageReader", error)
                    if (selectedRoute !== DRAIN_SENTINEL) {
                        @Suppress("UNCHECKED_CAST")
                        (selectedRoute as CaptureTimestampCorrelator<Image>)
                            .onCandidateAcquisitionError(error)
                    }
                    null
                }
                selectedRoute to acquired
            }
            if (image == null) return

            if (route === DRAIN_SENTINEL) {
                closeImage(image)
            } else {
                @Suppress("UNCHECKED_CAST")
                (route as CaptureTimestampCorrelator<Image>).onCandidateAvailable(image)
            }
        }
    }

    /** Drains a pure provider for deterministic JVM ownership tests. */
    fun <T> drainAndCloseAll(provider: FrameCandidateProvider<T>) {
        while (true) {
            val candidate = try {
                provider.acquireNext()
            } catch (_: Exception) {
                null
            } ?: return
            if (candidate is AutoCloseable) {
                try {
                    candidate.close()
                } catch (_: Exception) {
                    // Best-effort cleanup of a test or platform candidate.
                }
            }
        }
    }

    /**
     * Prevents listener delivery, performs the final FIFO drain, then publishes
     * exactly one correlator identity.
     */
    fun armCorrelator(
        correlator: CaptureTimestampCorrelator<Image>,
        reader: ImageReader
    ): RouterArmToken = armCorrelator(correlator) { drainReader(reader) }

    /** Pure final-drain seam used to verify the atomic arming boundary on the JVM. */
    internal fun armCorrelator(
        correlator: CaptureTimestampCorrelator<Image>,
        finalDrain: () -> Unit
    ): RouterArmToken {
        val (token, oldCorrelator) = synchronized(lock) {
            check(mode != Mode.CLOSED) { "Router is closed" }
            check(armCounter < Long.MAX_VALUE) { "Router arm identity exhausted" }
            armCounter += 1L
            val nextToken = RouterArmToken(armCounter)
            val previousCorrelator = activeCorrelator
            nextToken.also {
                activeCorrelator = null
                currentArmToken = it
                mode = Mode.ARMING
            }
            nextToken to previousCorrelator
        }

        oldCorrelator?.close()
        val published = try {
            // Lock order is acquisitionLock -> lock. Never enter acquisitionLock
            // while holding lock; listener delivery uses this same ordering.
            synchronized(acquisitionLock) {
                finalDrain()
                synchronized(lock) {
                    if (mode == Mode.ARMING && currentArmToken === token) {
                        activeCorrelator = correlator
                        mode = Mode.CORRELATING
                        true
                    } else {
                        false
                    }
                }
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (mode == Mode.ARMING && currentArmToken === token) {
                    currentArmToken = null
                    mode = Mode.DRAINING
                }
            }
            correlator.close()
            throw failure
        }
        if (!published) {
            correlator.close()
            throw IllegalStateException("Router closed or replaced during arming")
        }
        return token
    }

    fun disarmCorrelator(token: RouterArmToken, reader: ImageReader? = null) {
        val correlatorToClose = synchronized(lock) {
            if (currentArmToken !== token || mode == Mode.CLOSED) {
                null
            } else {
                currentArmToken = null
                mode = Mode.DRAINING
                activeCorrelator.also { activeCorrelator = null }
            }
        }
        correlatorToClose?.close()
        if (correlatorToClose != null && reader != null) {
            drainReader(reader)
        }
    }

    fun registerSessionReadyGate(gate: CameraSessionReadyGate): SessionGateToken {
        val (token, previousGate) = synchronized(lock) {
            check(mode != Mode.CLOSED) { "Router is closed" }
            check(gateEpochCounter < Long.MAX_VALUE) {
                "Session gate epoch exhausted"
            }
            gateEpochCounter += 1L
            val previous = activeReadyGate
            val nextToken = SessionGateToken(gateEpochCounter).also { token ->
                activeReadyGate = gate
                currentGateToken = token
                currentGateSawActive = false
            }
            nextToken to previous
        }
        previousGate?.disarm()
        return token
    }

    fun unregisterSessionReadyGate(token: SessionGateToken) {
        val gateToDisarm = synchronized(lock) {
            if (currentGateToken === token) {
                val current = activeReadyGate
                activeReadyGate = null
                currentGateToken = null
                currentGateSawActive = false
                current
            } else {
                null
            }
        }
        gateToDisarm?.disarm()
    }

    fun onSessionActive() {
        val gate = synchronized(lock) {
            val current = activeReadyGate
            if (current != null && currentGateToken != null) {
                currentGateSawActive = true
            }
            current
        }
        gate?.onActive()
    }

    fun onSessionReady() {
        val gate = synchronized(lock) {
            if (currentGateSawActive && currentGateToken != null) {
                currentGateSawActive = false
                activeReadyGate
            } else {
                null
            }
        }
        gate?.onReady()
    }

    override fun close() {
        val (correlatorToClose, gateToDisarm) = synchronized(lock) {
            if (mode == Mode.CLOSED) return
            mode = Mode.CLOSED
            currentArmToken = null
            val gate = activeReadyGate.also { activeReadyGate = null }
            currentGateToken = null
            currentGateSawActive = false
            val correlator = activeCorrelator.also { activeCorrelator = null }
            correlator to gate
        }
        correlatorToClose?.close()
        gateToDisarm?.disarm()
    }

    private fun drainReader(reader: ImageReader) {
        synchronized(acquisitionLock) {
            while (true) {
                val image = try {
                    reader.acquireNextImage()
                } catch (_: Exception) {
                    null
                } ?: return
                closeImage(image)
            }
        }
    }

    private fun closeImage(image: Image) {
        try {
            image.close()
        } catch (_: Exception) {
            // The reader/owner may already be closing.
        }
    }

    private companion object {
        private val DRAIN_SENTINEL = Any()
    }
}
