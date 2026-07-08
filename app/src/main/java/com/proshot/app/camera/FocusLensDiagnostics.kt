package com.proshot.app.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import java.util.Locale

/**
 * Result of comparing the still capture metadata timestamp and the copied image buffer timestamp.
 */
enum class TimestampMatchResult {
    /** Timestamps are present and match exactly. */
    MATCH,
    /** Timestamps are present but differ. */
    MISMATCH,
    /** One or both timestamps are unavailable. */
    UNAVAILABLE
}

/**
 * Immutable debug model representing camera characteristics and pre-capture diagnostics.
 */
data class FocusLensDiagnostics(
    val logicalCameraId: String? = null,
    val physicalCameraIds: List<String>? = null,
    val lensFacing: String? = null,
    val hardwareLevel: String? = null,
    val focalLengths: List<Float>? = null,
    val minFocusDistance: Float? = null,
    val hyperfocalDistance: Float? = null,
    val availableAfModes: List<String>? = null,
    val selectedAfMode: String? = null,
    val aeWarmupExitState: String? = null,
    val aeWarmupFrameCount: Int? = null,
    val afWaitExitState: String? = null,
    val afWaitFrameCount: Int? = null,
    val afWaitExitReason: String? = null,
    val stillCaptureResultTimestamp: Long? = null,
    val copiedImageTimestamp: Long? = null,
    val captureWidth: Int? = null,
    val captureHeight: Int? = null,
    val imageFormat: String? = null,
    val focusTargetSource: String? = null,
    val normalizedTargetX: Float? = null,
    val normalizedTargetY: Float? = null,
    val normalizedAfSize: Float? = null,
    val normalizedAeSize: Float? = null,
    val afMaxRegions: Int? = null,
    val aeMaxRegions: Int? = null,
    val afRegionApplied: String? = null,
    val aeRegionApplied: String? = null,
    val meteringCropRegion: String? = null
) {
    /**
     * Formats the diagnostics into a clean, monospace-friendly string for the debug HUD.
     * Any null or empty collections are omitted from display.
     */
    fun formatDiagnostics(): String {
        val sb = StringBuilder()
        sb.append("Focus & Lens HUD:\n")
        logicalCameraId?.let { sb.append("  - Camera ID: $it\n") }
        physicalCameraIds?.let { if (it.isNotEmpty()) sb.append("  - Physical IDs: ${it.joinToString(", ")}\n") }
        lensFacing?.let { sb.append("  - Lens Facing: $it\n") }
        hardwareLevel?.let { sb.append("  - HW Level: $it\n") }
        focalLengths?.let { if (it.isNotEmpty()) sb.append("  - Focal Lengths: ${it.joinToString(", ")} mm\n") }
        minFocusDistance?.let {
            val label = if (it == 0.0f) "fixed-focus (infinity)" else "%.2fm".format(1.0f / it)
            sb.append("  - Min Focus Dist: $label\n")
        }
        hyperfocalDistance?.let {
            val label = if (it == 0.0f) "infinity" else "%.2fm".format(1.0f / it)
            sb.append("  - Hyperfocal Dist: $label\n")
        }
        availableAfModes?.let { if (it.isNotEmpty()) sb.append("  - Available AF: ${it.joinToString(", ")}\n") }
        selectedAfMode?.let { sb.append("  - Selected AF: $it\n") }
        focusTargetSource?.let { sb.append("  - Focus Source: $it\n") }
        if (normalizedTargetX != null && normalizedTargetY != null) {
            sb.append("  - Normalized Target: (${normalizedTargetX}, ${normalizedTargetY})\n")
        }
        normalizedAfSize?.let { sb.append("  - Normalized AF Size: ${it.formatRegionSize()}\n") }
        normalizedAeSize?.let { sb.append("  - Normalized AE Size: ${it.formatRegionSize()}\n") }
        afMaxRegions?.let { sb.append("  - Max AF Regions: $it\n") }
        aeMaxRegions?.let { sb.append("  - Max AE Regions: $it\n") }
        afRegionApplied?.let { sb.append("  - AF Region: $it\n") }
        aeRegionApplied?.let { sb.append("  - AE Region: $it\n") }
        meteringCropRegion?.let { sb.append("  - Metering Crop: $it\n") }

        aeWarmupExitState?.let { state ->
            val fc = aeWarmupFrameCount?.let { " ($it frames)" } ?: ""
            sb.append("  - AE Warm-up: $state$fc\n")
        }

        afWaitExitReason?.let { reason ->
            val stateStr = afWaitExitState?.let { " State: $it" } ?: ""
            val fc = afWaitFrameCount?.let { " ($it frames)" } ?: ""
            sb.append("  - AF Lock: $reason$stateStr$fc\n")
        }

        if (stillCaptureResultTimestamp != null || copiedImageTimestamp != null) {
            val match = FocusLensDiagnosticsHelper.compareTimestamps(stillCaptureResultTimestamp, copiedImageTimestamp)
            sb.append("  - Timestamp Match: $match\n")
        }
        stillCaptureResultTimestamp?.let { sb.append("  - Still TS: ${it}ns\n") }
        copiedImageTimestamp?.let { sb.append("  - Copied TS: ${it}ns\n") }

        captureWidth?.let { w ->
            captureHeight?.let { h ->
                val fmt = imageFormat?.let { " ($it)" } ?: ""
                sb.append("  - Capture Size: ${w}x${h}$fmt\n")
            }
        }
        return sb.toString().trimEnd()
    }
}

private fun Float.formatRegionSize(): String = String.format(Locale.US, "%.2f", this)

/**
 * Pure helper utility to compare timestamps and map Camera2 constants.
 */
object FocusLensDiagnosticsHelper {
    /**
     * Compares the still capture metadata timestamp and the copied image timestamp.
     */
    fun compareTimestamps(stillTs: Long?, imageTs: Long?): TimestampMatchResult {
        if (stillTs == null || imageTs == null) {
            return TimestampMatchResult.UNAVAILABLE
        }
        return if (stillTs == imageTs) {
            TimestampMatchResult.MATCH
        } else {
            TimestampMatchResult.MISMATCH
        }
    }

    /**
     * Maps Camera2 AF mode to a stable, readable label.
     */
    fun mapAfMode(mode: Int?): String? {
        if (mode == null) return null
        return when (mode) {
            CaptureRequest.CONTROL_AF_MODE_OFF -> "OFF"
            CaptureRequest.CONTROL_AF_MODE_AUTO -> "AUTO"
            CaptureRequest.CONTROL_AF_MODE_MACRO -> "MACRO"
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
            CaptureRequest.CONTROL_AF_MODE_EDOF -> "EDOF"
            else -> "UNKNOWN($mode)"
        }
    }

    /**
     * Maps Camera2 AF state to a stable, readable label.
     */
    fun mapAfState(state: Int?): String? {
        if (state == null) return null
        return when (state) {
            CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
            else -> "UNKNOWN($state)"
        }
    }

    /**
     * Maps Camera2 AE state to a stable, readable label.
     */
    fun mapAeState(state: Int?): String? {
        if (state == null) return null
        return when (state) {
            CaptureResult.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
            CaptureResult.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
            CaptureResult.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
            CaptureResult.CONTROL_AE_STATE_LOCKED -> "LOCKED"
            CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
            CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
            else -> "UNKNOWN($state)"
        }
    }

    /**
     * Maps Camera2 hardware level to a stable, readable label.
     */
    fun mapHardwareLevel(level: Int?): String? {
        if (level == null) return null
        return when (level) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "UNKNOWN($level)"
        }
    }

    /**
     * Maps Camera2 lens facing to a stable, readable label.
     */
    fun mapLensFacing(facing: Int?): String? {
        if (facing == null) return null
        return when (facing) {
            CameraMetadata.LENS_FACING_FRONT -> "FRONT"
            CameraMetadata.LENS_FACING_BACK -> "BACK"
            CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN($facing)"
        }
    }
}

/**
 * Mutable tracker used during capture to gather focus and lens diagnostics.
 *
 * Fields are marked `@Volatile` because the tracker is written on the camera
 * handler thread (inside Camera2 callbacks) and read on the main/UI thread
 * when `snapshot()` is called after the capture pipeline returns. While
 * coroutine resume boundaries provide happens-before guarantees for most
 * fields, `stillCaptureResultTimestamp` is written in a side-channel
 * `onCaptureCompleted` callback that may fire after the coroutine resumes.
 * Marking all fields volatile eliminates JMM ambiguity at negligible cost.
 */
class FocusLensDiagnosticsTracker {
    @Volatile var logicalCameraId: String? = null
    /** Physical sub-camera IDs for multi-camera logical cameras. Available on API 28+. Debug-only. */
    @Volatile var physicalCameraIds: List<String>? = null
    @Volatile var lensFacing: String? = null
    @Volatile var hardwareLevel: String? = null
    @Volatile var focalLengths: List<Float>? = null
    @Volatile var minFocusDistance: Float? = null
    @Volatile var hyperfocalDistance: Float? = null
    @Volatile var availableAfModes: List<String>? = null
    @Volatile var selectedAfMode: String? = null
    @Volatile var aeWarmupExitState: String? = null
    @Volatile var aeWarmupFrameCount: Int? = null
    @Volatile var afWaitExitState: String? = null
    @Volatile var afWaitFrameCount: Int? = null
    /**
     * AF exit reason. Values: `FOCUSED`, `FRAME_CAP_TIMEOUT`, `FIXED_FOCUS`.
     * Initialized to `NOT_RUN` at capture start, meaning the AF lock phase
     * was not reached (e.g. pipeline failed before AF). `FIXED_FOCUS` is a
     * separate label set when the camera reports no triggerable AF mode.
     */
    @Volatile var afWaitExitReason: String? = null
    /**
     * Sensor timestamp from the still capture's `onCaptureCompleted` callback.
     * This callback may fire after `onImageAvailable` on some HALs (common on
     * Qualcomm), so this field may still be null at `snapshot()` time. When
     * null, the timestamp match diagnostic shows `UNAVAILABLE`. This is a
     * known diagnostic limitation, not a capture correctness issue.
     */
    @Volatile var stillCaptureResultTimestamp: Long? = null
    @Volatile var copiedImageTimestamp: Long? = null
    @Volatile var captureWidth: Int? = null
    @Volatile var captureHeight: Int? = null
    @Volatile var imageFormat: String? = null
    @Volatile var focusTargetSource: String? = null
    @Volatile var normalizedTargetX: Float? = null
    @Volatile var normalizedTargetY: Float? = null
    @Volatile var normalizedAfSize: Float? = null
    @Volatile var normalizedAeSize: Float? = null
    @Volatile var afMaxRegions: Int? = null
    @Volatile var aeMaxRegions: Int? = null
    @Volatile var afRegionApplied: String? = null
    @Volatile var aeRegionApplied: String? = null
    @Volatile var meteringCropRegion: String? = null

    /**
     * Creates an immutable [FocusLensDiagnostics] snapshot of the current tracked values.
     */
    fun snapshot(): FocusLensDiagnostics {
        return FocusLensDiagnostics(
            logicalCameraId = logicalCameraId,
            physicalCameraIds = physicalCameraIds,
            lensFacing = lensFacing,
            hardwareLevel = hardwareLevel,
            focalLengths = focalLengths,
            minFocusDistance = minFocusDistance,
            hyperfocalDistance = hyperfocalDistance,
            availableAfModes = availableAfModes,
            selectedAfMode = selectedAfMode,
            aeWarmupExitState = aeWarmupExitState,
            aeWarmupFrameCount = aeWarmupFrameCount,
            afWaitExitState = afWaitExitState,
            afWaitFrameCount = afWaitFrameCount,
            afWaitExitReason = afWaitExitReason,
            stillCaptureResultTimestamp = stillCaptureResultTimestamp,
            copiedImageTimestamp = copiedImageTimestamp,
            captureWidth = captureWidth,
            captureHeight = captureHeight,
            imageFormat = imageFormat,
            focusTargetSource = focusTargetSource,
            normalizedTargetX = normalizedTargetX,
            normalizedTargetY = normalizedTargetY,
            normalizedAfSize = normalizedAfSize,
            normalizedAeSize = normalizedAeSize,
            afMaxRegions = afMaxRegions,
            aeMaxRegions = aeMaxRegions,
            afRegionApplied = afRegionApplied,
            aeRegionApplied = aeRegionApplied,
            meteringCropRegion = meteringCropRegion
        )
    }
}
