package com.proshot.app.camera

/**
 * Route selection for camera ownership architecture.
 */
enum class CameraOwnershipRoute {
    CAMERA_X_HANDOFF,
    PERSISTENT_CAMERA2
}

/**
 * Classification of direct Camera2 route failures.
 */
enum class DirectCamera2FailureKind(val isEligibleForFallback: Boolean) {
    UNSUPPORTED_CONFIGURATION(true),
    CAMERA_DEVICE_OR_OPEN(true),
    CAMERA_SESSION(true),
    TERMINAL_CAPTURE_OR_REPEATING(true),
    PERMISSION_OR_SECURITY(false),
    OWNER_TERMINAL_BARRIER(false),
    LIFECYCLE_OR_SUPERSESSION(false)
}

/**
 * Encapsulates a classified direct Camera2 failure.
 */
data class DirectCamera2Failure(
    val kind: DirectCamera2FailureKind,
    val cause: Exception
)

/**
 * Typed exception thrown during direct Camera2 route attachment.
 */
class DirectCamera2RouteException(
    val failure: DirectCamera2Failure
) : Exception(failure.cause.message, failure.cause)

/**
 * State machine for camera ownership route selection.
 */
enum class CameraOwnershipRouteState(val route: CameraOwnershipRoute?) {
    PENDING(null),
    DIRECT(CameraOwnershipRoute.PERSISTENT_CAMERA2),
    INITIAL_CAMERAX(CameraOwnershipRoute.CAMERA_X_HANDOFF),
    FALLBACK_CAMERAX(CameraOwnershipRoute.CAMERA_X_HANDOFF);

    companion object {
        /**
         * Safely parses a route state by enum name, defaulting to [PENDING] for null or invalid inputs.
         */
        @JvmStatic
        fun parse(name: String?): CameraOwnershipRouteState {
            if (name.isNullOrEmpty()) return PENDING
            return try {
                valueOf(name)
            } catch (_: IllegalArgumentException) {
                PENDING
            }
        }
    }
}

/**
 * Pure policy determining camera route for an attachment generation.
 */
object CameraOwnershipRoutePolicy {
    /**
     * Resolves initial candidate route state from capability facts.
     */
    @JvmStatic
    fun selectInitialState(
        isDebuggable: Boolean,
        cameraAvailable: Boolean,
        yuvCaptureSupported: Boolean
    ): CameraOwnershipRouteState {
        if (!isDebuggable) return CameraOwnershipRouteState.INITIAL_CAMERAX
        return if (cameraAvailable && yuvCaptureSupported) {
            CameraOwnershipRouteState.DIRECT
        } else {
            CameraOwnershipRouteState.INITIAL_CAMERAX
        }
    }

    /**
     * Pure reducer transitioning direct route state to fallback CameraX upon eligible failure.
     */
    @JvmStatic
    fun reduceDirectFailure(
        currentState: CameraOwnershipRouteState,
        failure: DirectCamera2Failure
    ): CameraOwnershipRouteState {
        if (currentState == CameraOwnershipRouteState.DIRECT && failure.kind.isEligibleForFallback) {
            return CameraOwnershipRouteState.FALLBACK_CAMERAX
        }
        return currentState
    }

    /** Applies the retained one-way fallback authority without changing CameraX states. */
    @JvmStatic
    fun applyRetainedFallback(
        currentState: CameraOwnershipRouteState,
        isFallbackMandatory: Boolean
    ): CameraOwnershipRouteState {
        if (!isFallbackMandatory) return currentState
        return when (currentState) {
            CameraOwnershipRouteState.PENDING,
            CameraOwnershipRouteState.DIRECT -> CameraOwnershipRouteState.FALLBACK_CAMERAX
            CameraOwnershipRouteState.INITIAL_CAMERAX,
            CameraOwnershipRouteState.FALLBACK_CAMERAX -> currentState
        }
    }
}
