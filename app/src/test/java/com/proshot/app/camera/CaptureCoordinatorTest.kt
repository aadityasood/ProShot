package com.proshot.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests verifying result mapping and policies inside [CaptureCoordinator].
 */
class CaptureCoordinatorTest {

    @Test
    fun mapOutcome_inDebugBuild_whenBothSucceed_returnsSuccessDiagnosticPair() {
        val saveResult = SaveOutcome(isSuccess = true)
        val baselineResult = SaveOutcome(isSuccess = true)

        val result = CaptureCoordinator.mapOutcome(saveResult, baselineResult, isDebug = true)

        assertTrue(result is CaptureResult.Success)
        assertEquals("Saved diagnostic pair", (result as CaptureResult.Success).message)
    }

    @Test
    fun mapOutcome_inDebugBuild_whenBaselineFails_returnsSuccessWithBaselineFailureReason() {
        val saveResult = SaveOutcome(isSuccess = true)
        val baselineResult = SaveOutcome(isSuccess = false, userReason = "Disk full")

        val result = CaptureCoordinator.mapOutcome(saveResult, baselineResult, isDebug = true)

        assertTrue(result is CaptureResult.Success)
        assertEquals("Saved natural; baseline failed: Disk full", (result as CaptureResult.Success).message)
    }

    @Test
    fun mapOutcome_inDebugBuild_whenNaturalFailsButBaselineSucceeds_returnsFailureWithNaturalFailureReason() {
        val saveResult = SaveOutcome(isSuccess = false, userReason = "Permission denied")
        val baselineResult = SaveOutcome(isSuccess = true)

        val result = CaptureCoordinator.mapOutcome(saveResult, baselineResult, isDebug = true)

        assertTrue(result is CaptureResult.Failure)
        assertEquals("Saved baseline; natural failed: Permission denied", (result as CaptureResult.Failure).message)
    }

    @Test
    fun mapOutcome_inDebugBuild_whenBothFail_returnsFailureWithNaturalFailureReason() {
        val saveResult = SaveOutcome(isSuccess = false, userReason = "Write failed")
        val baselineResult = SaveOutcome(isSuccess = false, userReason = "Disk full")

        val result = CaptureCoordinator.mapOutcome(saveResult, baselineResult, isDebug = true)

        assertTrue(result is CaptureResult.Failure)
        assertEquals("Save failed: Write failed", (result as CaptureResult.Failure).message)
    }

    @Test
    fun mapOutcome_inReleaseBuild_whenSucceeds_returnsSavedToGallery() {
        val saveResult = SaveOutcome(isSuccess = true)
        // Baseline outcome is ignored in release builds
        val baselineResult = null

        val result = CaptureCoordinator.mapOutcome(saveResult, baselineResult, isDebug = false)

        assertTrue(result is CaptureResult.Success)
        assertEquals("Saved to gallery", (result as CaptureResult.Success).message)
    }

    @Test
    fun mapOutcome_inReleaseBuild_whenFails_returnsFailureWithReason() {
        val saveResult = SaveOutcome(isSuccess = false, userReason = "Write failed")
        val baselineResult = null

        val result = CaptureCoordinator.mapOutcome(saveResult, baselineResult, isDebug = false)

        assertTrue(result is CaptureResult.Failure)
        assertEquals("Save failed: Write failed", (result as CaptureResult.Failure).message)
    }

    @Test
    fun mapException_withIllegalArgumentException_returnsInvalidImageData() {
        val exception = IllegalArgumentException("Bad dimensions")
        val result = CaptureCoordinator.mapException(exception)

        assertTrue(result is CaptureResult.Failure)
        assertEquals("Capture failed: invalid image data", (result as CaptureResult.Failure).message)
        assertEquals(exception, (result as CaptureResult.Failure).cause)
    }

    @Test
    fun mapException_withOutOfMemoryError_returnsNotEnoughMemory() {
        val exception = OutOfMemoryError("Alloc failed")
        val result = CaptureCoordinator.mapException(exception)

        assertTrue(result is CaptureResult.Failure)
        assertEquals("Not enough memory to save photo", (result as CaptureResult.Failure).message)
        assertEquals(exception, (result as CaptureResult.Failure).cause)
    }

    @Test
    fun mapException_withGenericException_returnsSystemError() {
        val exception = RuntimeException("HAL crashed")
        val result = CaptureCoordinator.mapException(exception)

        assertTrue(result is CaptureResult.Failure)
        assertEquals("Capture failed: system error", (result as CaptureResult.Failure).message)
        assertEquals(exception, (result as CaptureResult.Failure).cause)
    }

    @Test
    fun mapOutcome_inDebugBuild_whenBaselineIsNull_returnsSuccessWithUnknownBaselineError() {
        val saveResult = SaveOutcome(isSuccess = true)
        // baselineSaveOutcome is null: baseline was never attempted or failed before save.
        val result = CaptureCoordinator.mapOutcome(saveResult, null, isDebug = true)

        assertTrue(result is CaptureResult.Success)
        assertEquals("Saved natural; baseline failed: unknown error", (result as CaptureResult.Success).message)
    }

    @Test
    fun mapOutcome_inDebugBuild_whenNaturalFailsAndBaselineIsNull_returnsGenericFailure() {
        val saveResult = SaveOutcome(isSuccess = false, userReason = "Write error")
        // No baseline was saved either
        val result = CaptureCoordinator.mapOutcome(saveResult, null, isDebug = true)

        assertTrue(result is CaptureResult.Failure)
        assertEquals("Save failed: Write error", (result as CaptureResult.Failure).message)
    }

    @Test
    fun mapOutcome_producesCorrectResultWithoutTimingCoupling() {
        // CaptureResult no longer carries CaptureTiming; the UI snapshots timing
        // directly from the tracker. Verify the mapping functions work cleanly
        // without any timing parameter.
        val saveResult = SaveOutcome(isSuccess = true)
        val result = CaptureCoordinator.mapOutcome(saveResult, null, isDebug = false)

        assertTrue(result is CaptureResult.Success)
        assertEquals("Saved to gallery", (result as CaptureResult.Success).message)
    }

    @Test
    fun mapException_producesCleanResultWithoutTimingCoupling() {
        val exception = RuntimeException("Some error")
        val result = CaptureCoordinator.mapException(exception)

        assertTrue(result is CaptureResult.Failure)
        assertEquals("Capture failed: system error", (result as CaptureResult.Failure).message)
        assertEquals(exception, (result as CaptureResult.Failure).cause)
    }
}
