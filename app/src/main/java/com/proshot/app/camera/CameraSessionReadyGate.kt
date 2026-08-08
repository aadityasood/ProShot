package com.proshot.app.camera

/** Result of attempting to arm a [CameraSessionReadyGate]. */
internal enum class CameraSessionReadyArmResult {
    ARMED,
    NO_UNMATCHED_ACTIVE_GENERATION,
    ALREADY_READY_AFTER_ACTIVITY,
    ALREADY_ARMED,
    DISARMED
}

/**
 * One-capture gate that pairs a Camera2 session activity generation with the
 * later readiness generation that ends that active work.
 *
 * The gate owns at most one callback and invokes it once, outside its lock.
 * It is deliberately platform-neutral and does not schedule or time out work.
 */
internal class CameraSessionReadyGate {
    private val lock = Any()
    private var activeGeneration = 0L
    private var readyGeneration = 0L
    private var acceptedArm = false
    private var disarmed = false
    private var targetGeneration: Long? = null
    private var readyCallback: (() -> Unit)? = null

    /** Records that the session has transitioned into a new active generation. */
    fun onActive() {
        synchronized(lock) {
            check(activeGeneration < Long.MAX_VALUE) {
                "Camera session activity generation exhausted"
            }
            activeGeneration++
        }
    }

    /**
     * Records readiness for all activity observed so far and completes the
     * accepted arm when that readiness reaches its target generation.
     */
    fun onReady() {
        val callbackToInvoke = synchronized(lock) {
            if (readyGeneration < activeGeneration) {
                readyGeneration = activeGeneration
            }

            val target = targetGeneration
            if (!disarmed && target != null && readyGeneration >= target) {
                targetGeneration = null
                readyCallback.also { readyCallback = null }
            } else {
                null
            }
        }

        callbackToInvoke?.invoke()
    }

    /**
     * Arms the single callback for the current unmatched active generation.
     * Completed real activity is reported without storing the callback or
     * consuming the gate's one accepted asynchronous arm; no-work readiness is
     * rejected explicitly.
     */
    fun arm(callback: () -> Unit): CameraSessionReadyArmResult {
        return synchronized(lock) {
            when {
                disarmed -> CameraSessionReadyArmResult.DISARMED
                acceptedArm -> CameraSessionReadyArmResult.ALREADY_ARMED
                activeGeneration == 0L ->
                    CameraSessionReadyArmResult.NO_UNMATCHED_ACTIVE_GENERATION
                activeGeneration <= readyGeneration ->
                    CameraSessionReadyArmResult.ALREADY_READY_AFTER_ACTIVITY
                else -> {
                    acceptedArm = true
                    targetGeneration = activeGeneration
                    readyCallback = callback
                    CameraSessionReadyArmResult.ARMED
                }
            }
        }
    }

    /** Cancels the pending callback, if any, and permanently disarms this gate. */
    fun disarm() {
        synchronized(lock) {
            disarmed = true
            targetGeneration = null
            readyCallback = null
        }
    }
}
