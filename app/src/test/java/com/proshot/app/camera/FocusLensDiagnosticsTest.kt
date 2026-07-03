package com.proshot.app.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests verifying focus/lens diagnostics helper mapping, comparison, and formatting.
 */
class FocusLensDiagnosticsTest {

    @Test
    fun mapAfMode_mapsCorrectly() {
        assertEquals("OFF", FocusLensDiagnosticsHelper.mapAfMode(CaptureRequest.CONTROL_AF_MODE_OFF))
        assertEquals("AUTO", FocusLensDiagnosticsHelper.mapAfMode(CaptureRequest.CONTROL_AF_MODE_AUTO))
        assertEquals("MACRO", FocusLensDiagnosticsHelper.mapAfMode(CaptureRequest.CONTROL_AF_MODE_MACRO))
        assertEquals("CONTINUOUS_VIDEO", FocusLensDiagnosticsHelper.mapAfMode(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO))
        assertEquals("CONTINUOUS_PICTURE", FocusLensDiagnosticsHelper.mapAfMode(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
        assertEquals("EDOF", FocusLensDiagnosticsHelper.mapAfMode(CaptureRequest.CONTROL_AF_MODE_EDOF))
        assertEquals("UNKNOWN(999)", FocusLensDiagnosticsHelper.mapAfMode(999))
        assertEquals(null, FocusLensDiagnosticsHelper.mapAfMode(null))
    }

    @Test
    fun mapAfState_mapsCorrectly() {
        assertEquals("INACTIVE", FocusLensDiagnosticsHelper.mapAfState(CaptureResult.CONTROL_AF_STATE_INACTIVE))
        assertEquals("PASSIVE_SCAN", FocusLensDiagnosticsHelper.mapAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN))
        assertEquals("PASSIVE_FOCUSED", FocusLensDiagnosticsHelper.mapAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED))
        assertEquals("ACTIVE_SCAN", FocusLensDiagnosticsHelper.mapAfState(CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN))
        assertEquals("FOCUSED_LOCKED", FocusLensDiagnosticsHelper.mapAfState(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED))
        assertEquals("NOT_FOCUSED_LOCKED", FocusLensDiagnosticsHelper.mapAfState(CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED))
        assertEquals("PASSIVE_UNFOCUSED", FocusLensDiagnosticsHelper.mapAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED))
        assertEquals("UNKNOWN(-1)", FocusLensDiagnosticsHelper.mapAfState(-1))
        assertEquals(null, FocusLensDiagnosticsHelper.mapAfState(null))
    }

    @Test
    fun mapAeState_mapsCorrectly() {
        assertEquals("INACTIVE", FocusLensDiagnosticsHelper.mapAeState(CaptureResult.CONTROL_AE_STATE_INACTIVE))
        assertEquals("SEARCHING", FocusLensDiagnosticsHelper.mapAeState(CaptureResult.CONTROL_AE_STATE_SEARCHING))
        assertEquals("CONVERGED", FocusLensDiagnosticsHelper.mapAeState(CaptureResult.CONTROL_AE_STATE_CONVERGED))
        assertEquals("LOCKED", FocusLensDiagnosticsHelper.mapAeState(CaptureResult.CONTROL_AE_STATE_LOCKED))
        assertEquals("FLASH_REQUIRED", FocusLensDiagnosticsHelper.mapAeState(CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED))
        assertEquals("PRECAPTURE", FocusLensDiagnosticsHelper.mapAeState(CaptureResult.CONTROL_AE_STATE_PRECAPTURE))
        assertEquals("UNKNOWN(42)", FocusLensDiagnosticsHelper.mapAeState(42))
        assertEquals(null, FocusLensDiagnosticsHelper.mapAeState(null))
    }

    @Test
    fun mapHardwareLevel_mapsCorrectly() {
        assertEquals("LEGACY", FocusLensDiagnosticsHelper.mapHardwareLevel(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY))
        assertEquals("EXTERNAL", FocusLensDiagnosticsHelper.mapHardwareLevel(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL))
        assertEquals("LIMITED", FocusLensDiagnosticsHelper.mapHardwareLevel(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED))
        assertEquals("FULL", FocusLensDiagnosticsHelper.mapHardwareLevel(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL))
        assertEquals("LEVEL_3", FocusLensDiagnosticsHelper.mapHardwareLevel(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3))
        assertEquals("UNKNOWN(-5)", FocusLensDiagnosticsHelper.mapHardwareLevel(-5))
        assertEquals(null, FocusLensDiagnosticsHelper.mapHardwareLevel(null))
    }

    @Test
    fun mapLensFacing_mapsCorrectly() {
        assertEquals("FRONT", FocusLensDiagnosticsHelper.mapLensFacing(CameraMetadata.LENS_FACING_FRONT))
        assertEquals("BACK", FocusLensDiagnosticsHelper.mapLensFacing(CameraMetadata.LENS_FACING_BACK))
        assertEquals("EXTERNAL", FocusLensDiagnosticsHelper.mapLensFacing(CameraMetadata.LENS_FACING_EXTERNAL))
        assertEquals("UNKNOWN(8)", FocusLensDiagnosticsHelper.mapLensFacing(8))
        assertEquals(null, FocusLensDiagnosticsHelper.mapLensFacing(null))
    }

    @Test
    fun compareTimestamps_returnsCorrectResults() {
        assertEquals(TimestampMatchResult.MATCH, FocusLensDiagnosticsHelper.compareTimestamps(12345L, 12345L))
        assertEquals(TimestampMatchResult.MISMATCH, FocusLensDiagnosticsHelper.compareTimestamps(12345L, 54321L))
        assertEquals(TimestampMatchResult.UNAVAILABLE, FocusLensDiagnosticsHelper.compareTimestamps(null, 12345L))
        assertEquals(TimestampMatchResult.UNAVAILABLE, FocusLensDiagnosticsHelper.compareTimestamps(12345L, null))
        assertEquals(TimestampMatchResult.UNAVAILABLE, FocusLensDiagnosticsHelper.compareTimestamps(null, null))
    }

    @Test
    fun formatDiagnostics_omitsNullValues() {
        val model = FocusLensDiagnostics(
            logicalCameraId = "0",
            lensFacing = "BACK"
        )
        val formatted = model.formatDiagnostics()

        assertTrue(formatted.contains("Camera ID: 0"))
        assertTrue(formatted.contains("Lens Facing: BACK"))
        // Omitted fields should not appear at all
        assertFalse(formatted.contains("Physical IDs"))
        assertFalse(formatted.contains("HW Level"))
        assertFalse(formatted.contains("Focal Lengths"))
        assertFalse(formatted.contains("Min Focus Dist"))
        assertFalse(formatted.contains("Hyperfocal Dist"))
        assertFalse(formatted.contains("Available AF"))
        assertFalse(formatted.contains("Selected AF"))
        assertFalse(formatted.contains("AE Warm-up"))
        assertFalse(formatted.contains("AF Lock"))
        assertFalse(formatted.contains("Still TS"))
        assertFalse(formatted.contains("Copied TS"))
        assertFalse(formatted.contains("Capture Size"))
        // Timestamp Match is suppressed when both timestamps are null
        assertFalse(formatted.contains("Timestamp Match"))
    }

    @Test
    fun formatDiagnostics_formatsPopulatedFieldsCorrectly() {
        val model = FocusLensDiagnostics(
            logicalCameraId = "0",
            physicalCameraIds = listOf("0", "2"),
            lensFacing = "BACK",
            hardwareLevel = "LEVEL_3",
            focalLengths = listOf(4.25f, 6.0f),
            minFocusDistance = 0.1f,
            hyperfocalDistance = 5.0f,
            availableAfModes = listOf("AUTO", "CONTINUOUS_PICTURE"),
            selectedAfMode = "AUTO",
            aeWarmupExitState = "CONVERGED",
            aeWarmupFrameCount = 4,
            afWaitExitState = "FOCUSED_LOCKED",
            afWaitFrameCount = 8,
            afWaitExitReason = "FOCUSED",
            stillCaptureResultTimestamp = 1000L,
            copiedImageTimestamp = 1000L,
            captureWidth = 1920,
            captureHeight = 1080,
            imageFormat = "YUV_420_888"
        )
        val formatted = model.formatDiagnostics()

        assertTrue(formatted.contains("Camera ID: 0"))
        assertTrue(formatted.contains("Physical IDs: 0, 2"))
        assertTrue(formatted.contains("Lens Facing: BACK"))
        assertTrue(formatted.contains("HW Level: LEVEL_3"))
        assertTrue(formatted.contains("Focal Lengths: 4.25, 6.0 mm"))
        // 0.1 diopters = 1/0.1 = 10.00 meters
        assertTrue(formatted.contains("Min Focus Dist: 10.00m"))
        // 5.0 diopters = 1/5.0 = 0.20 meters
        assertTrue(formatted.contains("Hyperfocal Dist: 0.20m"))
        assertTrue(formatted.contains("Available AF: AUTO, CONTINUOUS_PICTURE"))
        assertTrue(formatted.contains("Selected AF: AUTO"))
        assertTrue(formatted.contains("AE Warm-up: CONVERGED (4 frames)"))
        assertTrue(formatted.contains("AF Lock: FOCUSED State: FOCUSED_LOCKED (8 frames)"))
        assertTrue(formatted.contains("Timestamp Match: MATCH"))
        assertTrue(formatted.contains("Still TS: 1000ns"))
        assertTrue(formatted.contains("Copied TS: 1000ns"))
        assertTrue(formatted.contains("Capture Size: 1920x1080 (YUV_420_888)"))
    }

    @Test
    fun snapshot_preservesFixedFocusNoAFCase() {
        val tracker = FocusLensDiagnosticsTracker().apply {
            logicalCameraId = "1"
            lensFacing = "FRONT"
            selectedAfMode = "OFF"
            afWaitExitReason = "FIXED_FOCUS"
        }
        val snapshot = tracker.snapshot()
        val formatted = snapshot.formatDiagnostics()

        assertTrue(formatted.contains("Selected AF: OFF"))
        assertTrue(formatted.contains("AF Lock: FIXED_FOCUS"))
        assertFalse(formatted.contains("State:")) // No state should be printed if afWaitExitState is null
        assertFalse(formatted.contains("frames")) // No frame count printed if afWaitFrameCount is null
    }

    @Test
    fun formatDiagnostics_showsFixedFocusForZeroDiopters() {
        // 0.0 diopters means fixed-focus / infinity
        val model = FocusLensDiagnostics(minFocusDistance = 0.0f)
        val formatted = model.formatDiagnostics()
        assertTrue(formatted.contains("Min Focus Dist: fixed-focus"))
    }

    @Test
    fun formatDiagnostics_showsInfinityForZeroHyperfocalDiopters() {
        val model = FocusLensDiagnostics(hyperfocalDistance = 0.0f)
        val formatted = model.formatDiagnostics()
        assertTrue(formatted.contains("Hyperfocal Dist: infinity"))
    }

    @Test
    fun formatDiagnostics_convertsDioptersToMeters() {
        // 10 diopters = 1/10 = 0.10 meters = 10cm close focus
        val model = FocusLensDiagnostics(minFocusDistance = 10.0f)
        val formatted = model.formatDiagnostics()
        assertTrue(formatted.contains("Min Focus Dist: 0.10m"))
    }

    @Test
    fun formatDiagnostics_showsNullConvergedForNullAeState() {
        val model = FocusLensDiagnostics(
            aeWarmupExitState = "NULL_CONVERGED",
            aeWarmupFrameCount = 3
        )
        assertTrue(model.formatDiagnostics().contains("AE Warm-up: NULL_CONVERGED (3 frames)"))
    }

    @Test
    fun formatDiagnostics_showsNullTimeoutForNullAeStateAtCap() {
        val model = FocusLensDiagnostics(
            aeWarmupExitState = "NULL_TIMEOUT",
            aeWarmupFrameCount = 12
        )
        assertTrue(model.formatDiagnostics().contains("AE Warm-up: NULL_TIMEOUT (12 frames)"))
    }

    @Test
    fun formatDiagnostics_showsMismatchWhenTimestampsDiffer() {
        val model = FocusLensDiagnostics(
            stillCaptureResultTimestamp = 1000L,
            copiedImageTimestamp = 2000L
        )
        assertTrue(model.formatDiagnostics().contains("Timestamp Match: MISMATCH"))
    }

    @Test
    fun formatDiagnostics_showsFrameCapTimeoutWithState() {
        val model = FocusLensDiagnostics(
            afWaitExitReason = "FRAME_CAP_TIMEOUT",
            afWaitExitState = "NOT_FOCUSED_LOCKED",
            afWaitFrameCount = 30
        )
        val formatted = model.formatDiagnostics()
        assertTrue(formatted.contains("AF Lock: FRAME_CAP_TIMEOUT"))
        assertTrue(formatted.contains("State: NOT_FOCUSED_LOCKED"))
        assertTrue(formatted.contains("(30 frames)"))
    }

    @Test
    fun formatDiagnostics_omitsEmptyPhysicalCameraIds() {
        val model = FocusLensDiagnostics(physicalCameraIds = emptyList())
        assertFalse(model.formatDiagnostics().contains("Physical IDs"))
    }

    @Test
    fun formatDiagnostics_showsNotRunForUnreachedAfPhase() {
        val model = FocusLensDiagnostics(afWaitExitReason = "NOT_RUN")
        assertTrue(model.formatDiagnostics().contains("AF Lock: NOT_RUN"))
    }

    @Test
    fun formatDiagnostics_suppressesTimestampMatchWhenBothNull() {
        // When both timestamps are null, the Timestamp Match line should be omitted
        val model = FocusLensDiagnostics(
            logicalCameraId = "0"
        )
        assertFalse(model.formatDiagnostics().contains("Timestamp Match"))
    }

    @Test
    fun formatDiagnostics_showsTimestampUnavailableWhenOnlyOnePresent() {
        // When only one timestamp is present, show UNAVAILABLE
        val model = FocusLensDiagnostics(
            copiedImageTimestamp = 5000L
        )
        val formatted = model.formatDiagnostics()
        assertTrue(formatted.contains("Timestamp Match: UNAVAILABLE"))
        assertTrue(formatted.contains("Copied TS: 5000ns"))
        assertFalse(formatted.contains("Still TS"))
    }

    @Test
    fun formatDiagnostics_showsNewFocusTargetAndRegionFields() {
        val model = FocusLensDiagnostics(
            focusTargetSource = "DEFAULT_CENTER",
            normalizedTargetX = 0.5f,
            normalizedTargetY = 0.5f,
            normalizedAfSize = 0.04f,
            normalizedAeSize = 0.10f,
            afMaxRegions = 1,
            aeMaxRegions = 1,
            afRegionApplied = "Rect(1920, 1440, 160x120)",
            aeRegionApplied = "Rect(1800, 1350, 400x300)"
        )
        val formatted = model.formatDiagnostics()
        assertTrue(formatted.contains("Focus Source: DEFAULT_CENTER"))
        assertTrue(formatted.contains("Normalized Target: (0.5, 0.5)"))
        assertTrue(formatted.contains("Normalized AF Size: 0.04"))
        assertTrue(formatted.contains("Normalized AE Size: 0.10"))
        assertTrue(formatted.contains("Max AF Regions: 1"))
        assertTrue(formatted.contains("Max AE Regions: 1"))
        assertTrue(formatted.contains("AF Region: Rect(1920, 1440, 160x120)"))
        assertTrue(formatted.contains("AE Region: Rect(1800, 1350, 400x300)"))
    }

    @Test
    fun formatDiagnostics_showsZeroRegionFallbackPolicy() {
        val model = FocusLensDiagnostics(
            focusTargetSource = "DEFAULT_CENTER",
            normalizedTargetX = 0.5f,
            normalizedTargetY = 0.5f,
            afMaxRegions = 0,
            aeMaxRegions = 0,
            afRegionApplied = "NONE_UNSUPPORTED",
            aeRegionApplied = "NONE_UNSUPPORTED"
        )
        val formatted = model.formatDiagnostics()
        assertTrue(formatted.contains("Focus Source: DEFAULT_CENTER"))
        assertTrue(formatted.contains("Normalized Target: (0.5, 0.5)"))
        assertTrue(formatted.contains("Max AF Regions: 0"))
        assertTrue(formatted.contains("Max AE Regions: 0"))
        assertTrue(formatted.contains("AF Region: NONE_UNSUPPORTED"))
        assertTrue(formatted.contains("AE Region: NONE_UNSUPPORTED"))
    }

    @Test
    fun formatDiagnostics_showsActiveArrayNullFallbackEvenIfMaxRegionsAreZero() {
        val model = FocusLensDiagnostics(
            focusTargetSource = "DEFAULT_CENTER",
            afMaxRegions = 0,
            aeMaxRegions = 0,
            afRegionApplied = "NONE_ACTIVE_ARRAY_NULL",
            aeRegionApplied = "NONE_ACTIVE_ARRAY_NULL"
        )
        val formatted = model.formatDiagnostics()
        assertTrue(formatted.contains("AF Region: NONE_ACTIVE_ARRAY_NULL"))
        assertTrue(formatted.contains("AE Region: NONE_ACTIVE_ARRAY_NULL"))
    }

    @Test
    fun formatDiagnostics_showsUserTapFocusTargetSource() {
        val model = FocusLensDiagnostics(
            focusTargetSource = "USER_TAP"
        )
        val formatted = model.formatDiagnostics()
        assertTrue(formatted.contains("Focus Source: USER_TAP"))
    }
}
