package com.proshot.app.camera.compat

import com.proshot.app.processing.style.LookProfile
import com.proshot.app.processing.style.LookProfileCatalog

/**
 * High-level camera capability tier exposed by a Camera2 device.
 */
enum class CameraHardwareLevel {
    LEGACY,
    EXTERNAL,
    LIMITED,
    FULL,
    LEVEL_3
}

/**
 * ProShot execution tier chosen for the current camera session.
 *
 * Lower tiers reduce capture or processing cost, but they still preserve the
 * selected look profile whenever a valid image buffer exists.
 */
enum class PipelineTier {
    FULL_COMPUTATIONAL,
    YUV_BURST,
    SINGLE_FRAME_ENHANCED,
    BASIC_CAPTURE
}

/**
 * Processing acceleration strategy for the selected pipeline tier.
 */
enum class AccelerationStrategy {
    GPU_PREFERRED,
    CPU_ONLY
}

/**
 * Normalized camera and runtime facts used to choose the safest pipeline tier.
 */
data class DeviceCameraCapabilities(
    val hardwareLevel: CameraHardwareLevel,
    val rawCaptureSupported: Boolean,
    val yuvCaptureSupported: Boolean,
    val manualExposureSupported: Boolean,
    val burstCaptureSupported: Boolean,
    val semanticMasksSupported: Boolean,
    val gpuDelegateSupported: Boolean,
    val cameraAvailable: Boolean,
    val memoryClassMb: Int,
    val lowRamDevice: Boolean
)

/**
 * Stable decision object describing how ProShot should execute on a device.
 *
 * When the diagnostics reporting layer is implemented (see
 * `COMPATIBILITY_AND_MAINTENANCE.md` §Diagnostics), this decision should be
 * included in the per-session report alongside environmental fields (device
 * model, ABI, app version) that are not carried here.
 */
data class CompatibilityDecision(
    val tier: PipelineTier,
    val fallbackReasons: List<String>,
    val expectedBurstCount: Int,
    val accelerationStrategy: AccelerationStrategy,
    val semanticMasksEnabled: Boolean,
    val lookProfile: LookProfile
)

/**
 * Selects an adaptive execution path while preserving ProShot's visual identity.
 */
object CompatibilityPolicy {
    private const val FULL_BURST_COUNT = 5
    private const val SINGLE_FRAME_COUNT = 1
    private const val NO_CAPTURE_FRAMES = 0
    // TODO: LOW_MEMORY_THRESHOLD_MB uses per-process heap class (ActivityManager
    // .memoryClass), not total device RAM. ARCHITECTURE.md line 195 specifies <3 GB total RAM
    // as the low-memory trigger. These are different APIs measuring different things. The
    // lowRamDevice flag captures OEM-flagged low-RAM correctly, but this numeric threshold
    // needs validation against the real device matrix before it can be treated as a stable
    // product decision.
    private const val LOW_MEMORY_THRESHOLD_MB = 256

    /**
     * Maps normalized camera capabilities to a pipeline decision.
     */
    fun select(
        capabilities: DeviceCameraCapabilities,
        lookProfile: LookProfile = LookProfileCatalog.defaultProfile()
    ): CompatibilityDecision {
        val reasons = mutableListOf<String>()
        val accelerationStrategy = if (capabilities.gpuDelegateSupported) {
            AccelerationStrategy.GPU_PREFERRED
        } else {
            reasons += "GPU delegate unavailable; using CPU processing fallback."
            AccelerationStrategy.CPU_ONLY
        }

        if (!capabilities.cameraAvailable) {
            reasons += "Camera is unavailable; using emergency capture path."
            return CompatibilityDecision(
                tier = PipelineTier.BASIC_CAPTURE,
                fallbackReasons = reasons.toList(),
                expectedBurstCount = NO_CAPTURE_FRAMES,
                accelerationStrategy = AccelerationStrategy.CPU_ONLY,
                semanticMasksEnabled = false,
                lookProfile = lookProfile
            )
        }

        if (!capabilities.rawCaptureSupported && !capabilities.yuvCaptureSupported) {
            reasons += "No RAW or YUV image buffer is available; using emergency capture path."
            return CompatibilityDecision(
                tier = PipelineTier.BASIC_CAPTURE,
                fallbackReasons = reasons.toList(),
                expectedBurstCount = NO_CAPTURE_FRAMES,
                accelerationStrategy = AccelerationStrategy.CPU_ONLY,
                semanticMasksEnabled = false,
                lookProfile = lookProfile
            )
        }

        if (requiresSingleFrame(capabilities)) {
            addSingleFrameReasons(capabilities, reasons)
            return CompatibilityDecision(
                tier = PipelineTier.SINGLE_FRAME_ENHANCED,
                fallbackReasons = reasons.toList(),
                expectedBurstCount = SINGLE_FRAME_COUNT,
                accelerationStrategy = accelerationStrategy,
                semanticMasksEnabled = capabilities.semanticMasksSupported,
                lookProfile = lookProfile
            )
        }

        if (!capabilities.rawCaptureSupported) {
            reasons += "RAW capture unavailable; using YUV burst with the same look profile."
            return CompatibilityDecision(
                tier = PipelineTier.YUV_BURST,
                fallbackReasons = reasons.toList(),
                expectedBurstCount = FULL_BURST_COUNT,
                accelerationStrategy = accelerationStrategy,
                semanticMasksEnabled = capabilities.semanticMasksSupported,
                lookProfile = lookProfile
            )
        }

        if (!capabilities.manualExposureSupported && capabilities.yuvCaptureSupported) {
            reasons += "Manual exposure unavailable; using YUV burst for safer exposure behavior."
            return CompatibilityDecision(
                tier = PipelineTier.YUV_BURST,
                fallbackReasons = reasons.toList(),
                expectedBurstCount = FULL_BURST_COUNT,
                accelerationStrategy = accelerationStrategy,
                semanticMasksEnabled = capabilities.semanticMasksSupported,
                lookProfile = lookProfile
            )
        }

        if (!capabilities.manualExposureSupported) {
            // RAW is supported but manual exposure and YUV are not.
            // Proceed with full computational path using auto-exposure RAW frames.
            reasons += "Manual exposure unavailable and no YUV fallback; using RAW burst with auto-exposure."
        }

        return CompatibilityDecision(
            tier = PipelineTier.FULL_COMPUTATIONAL,
            fallbackReasons = reasons.toList(),
            expectedBurstCount = FULL_BURST_COUNT,
            accelerationStrategy = accelerationStrategy,
            semanticMasksEnabled = capabilities.semanticMasksSupported,
            lookProfile = lookProfile
        )
    }

    private fun requiresSingleFrame(capabilities: DeviceCameraCapabilities): Boolean =
        capabilities.hardwareLevel == CameraHardwareLevel.LEGACY ||
            capabilities.hardwareLevel == CameraHardwareLevel.EXTERNAL ||
            !capabilities.burstCaptureSupported ||
            capabilities.lowRamDevice ||
            capabilities.memoryClassMb < LOW_MEMORY_THRESHOLD_MB

    private fun addSingleFrameReasons(
        capabilities: DeviceCameraCapabilities,
        reasons: MutableList<String>
    ) {
        if (capabilities.hardwareLevel == CameraHardwareLevel.LEGACY) {
            reasons += "Camera2 LEGACY device; using single-frame enhanced path."
        }
        if (capabilities.hardwareLevel == CameraHardwareLevel.EXTERNAL) {
            reasons += "External camera device; using single-frame enhanced path."
        }
        if (!capabilities.burstCaptureSupported) {
            reasons += "Burst capture unavailable; using single-frame enhanced path."
        }
        if (capabilities.lowRamDevice) {
            reasons += "Low-RAM device; using single-frame enhanced path."
        }
        if (capabilities.memoryClassMb < LOW_MEMORY_THRESHOLD_MB) {
            reasons += "Memory class below ${LOW_MEMORY_THRESHOLD_MB}MB; using single-frame enhanced path."
        }
    }
}
