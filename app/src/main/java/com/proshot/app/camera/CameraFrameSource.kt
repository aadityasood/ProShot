package com.proshot.app.camera

import android.content.Context

/**
 * Abstraction for capturing still frames and resolving sensor/output orientation,
 * decoupling [CaptureCoordinator] from specific camera pipeline implementations.
 */
interface CameraFrameSource {
    /**
     * Captures a single frame and returns a heap-allocated [CopiedImageFrame].
     */
    suspend fun captureFrame(
        context: Context,
        tracker: CaptureTimingTracker? = null,
        diagnosticsTracker: FocusLensDiagnosticsTracker? = null,
        focusTarget: FocusMeteringTarget = FocusMeteringTarget.center()
    ): CopiedImageFrame

    /**
     * Resolves the clockwise output rotation in degrees needed for saved output.
     */
    fun resolveOutputRotationDegrees(context: Context): Int

    /**
     * Resolves the physical back-camera sensor orientation in degrees.
     */
    fun resolveSensorOrientation(context: Context): Int
}
