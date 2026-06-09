package com.proshot.app.camera

/**
 * Immutable diagnostic model containing capture pipeline stage latencies in milliseconds.
 */
data class CaptureTiming(
    val previewUnbindMs: Long? = null,
    val cameraOpenMs: Long? = null,
    val sessionConfigMs: Long? = null,
    val aeWarmupMs: Long? = null,
    val afWaitMs: Long? = null,
    val stillCaptureMs: Long? = null,
    val totalCamera2CaptureMs: Long? = null,
    val yuvToNv21AndRotateMs: Long? = null,
    val baselineSaveMs: Long? = null,
    val lookProfileProcessMs: Long? = null,
    val naturalSaveMs: Long? = null,
    val previewRebindMs: Long? = null,
    val totalCapturePipelineMs: Long? = null
) {
    /**
     * Formats the timing stages into a clean multiline diagnostic string.
     */
    fun formatDiagnostics(): String {
        val sb = StringBuilder()
        sb.append("Capture Latency HUD:\n")
        previewUnbindMs?.let { sb.append("  - Preview Unbind: ${it}ms\n") }
        cameraOpenMs?.let { sb.append("  - Camera Open: ${it}ms\n") }
        sessionConfigMs?.let { sb.append("  - Session Config: ${it}ms\n") }
        aeWarmupMs?.let { sb.append("  - AE Warm-up: ${it}ms\n") }
        afWaitMs?.let { sb.append("  - AF Wait/Lock: ${it}ms\n") }
        stillCaptureMs?.let { sb.append("  - Still Capture/Copy: ${it}ms\n") }
        totalCamera2CaptureMs?.let { sb.append("  - Total Camera2 Capture: ${it}ms\n") }
        yuvToNv21AndRotateMs?.let { sb.append("  - YUV-to-NV21 & Rotate: ${it}ms\n") }
        baselineSaveMs?.let { sb.append("  - Baseline Compress+Save: ${it}ms\n") }
        lookProfileProcessMs?.let { sb.append("  - Look Profile Process: ${it}ms\n") }
        naturalSaveMs?.let { sb.append("  - Natural Compress+Save: ${it}ms\n") }
        previewRebindMs?.let { sb.append("  - Preview Rebind: ${it}ms\n") }
        totalCapturePipelineMs?.let { sb.append("  - Total Pipeline Latency: ${it}ms") }
        return sb.toString().trimEnd()
    }
}

/**
 * A mutable tracker used by pipeline stages to collect execution durations.
 */
class CaptureTimingTracker {
    var previewUnbindMs: Long? = null
    var cameraOpenMs: Long? = null
    var sessionConfigMs: Long? = null
    var aeWarmupMs: Long? = null
    var afWaitMs: Long? = null
    var stillCaptureMs: Long? = null
    var totalCamera2CaptureMs: Long? = null
    var yuvToNv21AndRotateMs: Long? = null
    var baselineSaveMs: Long? = null
    var lookProfileProcessMs: Long? = null
    var naturalSaveMs: Long? = null
    var previewRebindMs: Long? = null
    var totalCapturePipelineMs: Long? = null

    fun toCaptureTiming(): CaptureTiming {
        return CaptureTiming(
            previewUnbindMs = previewUnbindMs,
            cameraOpenMs = cameraOpenMs,
            sessionConfigMs = sessionConfigMs,
            aeWarmupMs = aeWarmupMs,
            afWaitMs = afWaitMs,
            stillCaptureMs = stillCaptureMs,
            totalCamera2CaptureMs = totalCamera2CaptureMs,
            yuvToNv21AndRotateMs = yuvToNv21AndRotateMs,
            baselineSaveMs = baselineSaveMs,
            lookProfileProcessMs = lookProfileProcessMs,
            naturalSaveMs = naturalSaveMs,
            previewRebindMs = previewRebindMs,
            totalCapturePipelineMs = totalCapturePipelineMs
        )
    }
}
