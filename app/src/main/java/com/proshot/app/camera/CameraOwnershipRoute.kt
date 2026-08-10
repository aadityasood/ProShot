package com.proshot.app.camera

/**
 * Route selection for camera ownership architecture.
 */
enum class CameraOwnershipRoute {
    CAMERA_X_HANDOFF,
    PERSISTENT_CAMERA2
}

/**
 * Pure policy determining camera route for an attachment generation.
 *
 * Debuggable builds select [CameraOwnershipRoute.PERSISTENT_CAMERA2]; release builds select
 * [CameraOwnershipRoute.CAMERA_X_HANDOFF].
 */
object CameraOwnershipRoutePolicy {
    /**
     * Debuggable builds select [CameraOwnershipRoute.PERSISTENT_CAMERA2]; release builds select
     * [CameraOwnershipRoute.CAMERA_X_HANDOFF].
     */
    @JvmStatic
    fun select(isDebuggable: Boolean): CameraOwnershipRoute {
        return if (isDebuggable) {
            CameraOwnershipRoute.PERSISTENT_CAMERA2
        } else {
            CameraOwnershipRoute.CAMERA_X_HANDOFF
        }
    }
}
