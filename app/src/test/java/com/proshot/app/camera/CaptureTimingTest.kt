package com.proshot.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CaptureTiming] and [CaptureTimingTracker] formatting, snapshot mapping, and null-safety.
 */
class CaptureTimingTest {

    @Test
    fun formatDiagnostics_whenAllFieldsNull_onlyPrintsHeader() {
        val timing = CaptureTiming()
        val formatted = timing.formatDiagnostics()
        assertEquals("Capture Latency HUD:", formatted)
    }

    @Test
    fun formatDiagnostics_whenSomeFieldsSet_onlyPrintsSetFieldsWithAsciiLabels() {
        val timing = CaptureTiming(
            cameraOpenMs = 120L,
            lookProfileProcessMs = 45L,
            totalCapturePipelineMs = 850L
        )
        val formatted = timing.formatDiagnostics()
        
        val expected = """
            Capture Latency HUD:
              - Camera Open: 120ms
              - Look Profile Process: 45ms
              - Total Pipeline Latency: 850ms
        """.trimIndent()
        
        assertEquals(expected, formatted)
        // Make sure no bullet characters are in the output.
        assertFalse(formatted.any { it.code == 0x2022 })
    }

    @Test
    fun tracker_toCaptureTiming_mapsAllFieldsCorrectly() {
        val tracker = CaptureTimingTracker().apply {
            previewUnbindMs = 15L
            cameraOpenMs = 110L
            sessionConfigMs = 80L
            aeWarmupMs = 40L
            afWaitMs = 150L
            stillCaptureMs = 250L
            totalCamera2CaptureMs = 640L
            yuvToNv21AndRotateMs = 30L
            baselineSaveMs = 70L
            lookProfileProcessMs = 50L
            naturalSaveMs = 90L
            previewRebindMs = 200L
            totalCapturePipelineMs = 1045L
        }

        val timing = tracker.toCaptureTiming()

        assertEquals(15L, timing.previewUnbindMs)
        assertEquals(110L, timing.cameraOpenMs)
        assertEquals(80L, timing.sessionConfigMs)
        assertEquals(40L, timing.aeWarmupMs)
        assertEquals(150L, timing.afWaitMs)
        assertEquals(250L, timing.stillCaptureMs)
        assertEquals(640L, timing.totalCamera2CaptureMs)
        assertEquals(30L, timing.yuvToNv21AndRotateMs)
        assertEquals(70L, timing.baselineSaveMs)
        assertEquals(50L, timing.lookProfileProcessMs)
        assertEquals(90L, timing.naturalSaveMs)
        assertEquals(200L, timing.previewRebindMs)
        assertEquals(1045L, timing.totalCapturePipelineMs)
    }

    @Test
    fun tracker_defaultsToNull() {
        val tracker = CaptureTimingTracker()
        assertNull(tracker.cameraOpenMs)
        assertNull(tracker.totalCapturePipelineMs)
        
        val timing = tracker.toCaptureTiming()
        assertNull(timing.cameraOpenMs)
        assertNull(timing.totalCapturePipelineMs)
    }

    @Test
    fun formatDiagnostics_displaysCompressPlusSaveLabelsForSaveFields() {
        val timing = CaptureTiming(
            baselineSaveMs = 200L,
            naturalSaveMs = 180L
        )
        val formatted = timing.formatDiagnostics()

        assertTrue(formatted.contains("Baseline Compress+Save: 200ms"))
        assertTrue(formatted.contains("Natural Compress+Save: 180ms"))
        // Verify the old misleading labels are not present
        assertFalse(formatted.contains("Baseline Save:"))
        assertFalse(formatted.contains("Natural Save:"))
    }

    @Test
    fun tracker_toCaptureTiming_withNoFieldsSet_producesAllNullSnapshot() {
        // Simulates release build where tracker is created but no timing is recorded.
        // In practice, release builds don't create the tracker at all; this verifies
        // the snapshot is safely empty if they did.
        val tracker = CaptureTimingTracker()
        val timing = tracker.toCaptureTiming()
        val formatted = timing.formatDiagnostics()

        assertEquals("Capture Latency HUD:", formatted)
        assertNull(timing.previewUnbindMs)
        assertNull(timing.cameraOpenMs)
        assertNull(timing.sessionConfigMs)
        assertNull(timing.aeWarmupMs)
        assertNull(timing.afWaitMs)
        assertNull(timing.stillCaptureMs)
        assertNull(timing.totalCamera2CaptureMs)
        assertNull(timing.yuvToNv21AndRotateMs)
        assertNull(timing.baselineSaveMs)
        assertNull(timing.lookProfileProcessMs)
        assertNull(timing.naturalSaveMs)
        assertNull(timing.previewRebindMs)
        assertNull(timing.totalCapturePipelineMs)
    }
}
