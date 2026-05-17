package com.proshot.app.camera.compat

import com.proshot.app.processing.style.LookProfileCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityPolicyTest {
    @Test
    fun selectTier_fullCapabilitiesUsesFullComputationalPath() {
        val decision = CompatibilityPolicy.select(
            capabilities = fullCapabilities()
        )

        assertEquals(PipelineTier.FULL_COMPUTATIONAL, decision.tier)
        assertEquals(5, decision.expectedBurstCount)
        assertEquals(AccelerationStrategy.GPU_PREFERRED, decision.accelerationStrategy)
        assertSame(LookProfileCatalog.defaultProfile(), decision.lookProfile)
        assertTrue(decision.fallbackReasons.isEmpty())
    }

    @Test
    fun selectTier_noRawButYuvBurstKeepsProShotNaturalLook() {
        val decision = CompatibilityPolicy.select(
            capabilities = fullCapabilities(
                rawCaptureSupported = false
            )
        )

        assertEquals(PipelineTier.YUV_BURST, decision.tier)
        assertEquals(5, decision.expectedBurstCount)
        assertSame(LookProfileCatalog.defaultProfile(), decision.lookProfile)
        assertTrue(decision.fallbackReasons.any { it.contains("RAW", ignoreCase = true) })
    }

    @Test
    fun selectTier_legacyOrLowMemoryUsesSingleFrameEnhancedWithSameLook() {
        val decision = CompatibilityPolicy.select(
            capabilities = fullCapabilities(
                hardwareLevel = CameraHardwareLevel.LEGACY,
                memoryClassMb = 192,
                lowRamDevice = true
            )
        )

        assertEquals(PipelineTier.SINGLE_FRAME_ENHANCED, decision.tier)
        assertEquals(1, decision.expectedBurstCount)
        assertSame(LookProfileCatalog.defaultProfile(), decision.lookProfile)
        assertFalse(decision.fallbackReasons.isEmpty())
    }

    @Test
    fun selectTier_cameraUnavailableUsesBasicCapture() {
        val decision = CompatibilityPolicy.select(
            capabilities = fullCapabilities(
                cameraAvailable = false
            )
        )

        assertEquals(PipelineTier.BASIC_CAPTURE, decision.tier)
        assertEquals(0, decision.expectedBurstCount)
        assertEquals(AccelerationStrategy.CPU_ONLY, decision.accelerationStrategy)
        assertSame(LookProfileCatalog.defaultProfile(), decision.lookProfile)
        assertTrue(decision.fallbackReasons.any { it.contains("unavailable", ignoreCase = true) })
    }

    @Test
    fun selectTier_nonFatalFallbacksAlwaysExplainWhy() {
        val noRawDecision = CompatibilityPolicy.select(
            capabilities = fullCapabilities(rawCaptureSupported = false)
        )
        val noGpuDecision = CompatibilityPolicy.select(
            capabilities = fullCapabilities(gpuDelegateSupported = false)
        )

        assertTrue(noRawDecision.fallbackReasons.all { it.isNotBlank() })
        assertTrue(noGpuDecision.fallbackReasons.all { it.isNotBlank() })
        assertTrue(noGpuDecision.fallbackReasons.any { it.contains("GPU", ignoreCase = true) })
    }

    @Test
    fun selectTier_noRawNoYuvUsesBasicCaptureWithCpuOnly() {
        val decision = CompatibilityPolicy.select(
            capabilities = fullCapabilities(
                rawCaptureSupported = false,
                yuvCaptureSupported = false
            )
        )

        assertEquals(PipelineTier.BASIC_CAPTURE, decision.tier)
        assertEquals(AccelerationStrategy.CPU_ONLY, decision.accelerationStrategy)
        assertEquals(0, decision.expectedBurstCount)
        assertSame(LookProfileCatalog.defaultProfile(), decision.lookProfile)
    }

    @Test
    fun selectTier_noManualExposureWithYuvFallsBackToYuvBurst() {
        val decision = CompatibilityPolicy.select(
            capabilities = fullCapabilities(
                manualExposureSupported = false
            )
        )

        assertEquals(PipelineTier.YUV_BURST, decision.tier)
        assertEquals(5, decision.expectedBurstCount)
        assertSame(LookProfileCatalog.defaultProfile(), decision.lookProfile)
        assertTrue(decision.fallbackReasons.any { it.contains("exposure", ignoreCase = true) })
    }

    @Test
    fun selectTier_noManualExposureNoYuvUsesFullComputationalWithReason() {
        // RAW is supported but neither manual exposure nor YUV are available.
        // The policy should fall through to FULL_COMPUTATIONAL with auto-exposure
        // RAW frames rather than incorrectly selecting YUV_BURST.
        val decision = CompatibilityPolicy.select(
            capabilities = fullCapabilities(
                manualExposureSupported = false,
                yuvCaptureSupported = false
            )
        )

        assertEquals(PipelineTier.FULL_COMPUTATIONAL, decision.tier)
        assertEquals(5, decision.expectedBurstCount)
        assertSame(LookProfileCatalog.defaultProfile(), decision.lookProfile)
        assertTrue(decision.fallbackReasons.any { it.contains("auto-exposure", ignoreCase = true) })
    }

    @Test
    fun selectTier_lowRamDeviceAloneUsesSingleFrameEnhanced() {
        val decision = CompatibilityPolicy.select(
            capabilities = fullCapabilities(
                lowRamDevice = true
            )
        )

        assertEquals(PipelineTier.SINGLE_FRAME_ENHANCED, decision.tier)
        assertEquals(1, decision.expectedBurstCount)
        assertSame(LookProfileCatalog.defaultProfile(), decision.lookProfile)
        assertTrue(decision.fallbackReasons.any { it.contains("Low-RAM", ignoreCase = true) })
    }

    @Test
    fun selectTier_noBurstSupportUsesSingleFrameEnhanced() {
        val decision = CompatibilityPolicy.select(
            capabilities = fullCapabilities(
                burstCaptureSupported = false
            )
        )

        assertEquals(PipelineTier.SINGLE_FRAME_ENHANCED, decision.tier)
        assertEquals(1, decision.expectedBurstCount)
        assertSame(LookProfileCatalog.defaultProfile(), decision.lookProfile)
        assertTrue(decision.fallbackReasons.any { it.contains("Burst", ignoreCase = true) })
    }

    @Test
    fun selectTier_externalCameraUsesSingleFrameEnhanced() {
        val decision = CompatibilityPolicy.select(
            capabilities = fullCapabilities(
                hardwareLevel = CameraHardwareLevel.EXTERNAL
            )
        )

        assertEquals(PipelineTier.SINGLE_FRAME_ENHANCED, decision.tier)
        assertEquals(1, decision.expectedBurstCount)
        assertSame(LookProfileCatalog.defaultProfile(), decision.lookProfile)
    }

    private fun fullCapabilities(
        hardwareLevel: CameraHardwareLevel = CameraHardwareLevel.LEVEL_3,
        rawCaptureSupported: Boolean = true,
        yuvCaptureSupported: Boolean = true,
        manualExposureSupported: Boolean = true,
        burstCaptureSupported: Boolean = true,
        semanticMasksSupported: Boolean = true,
        gpuDelegateSupported: Boolean = true,
        cameraAvailable: Boolean = true,
        memoryClassMb: Int = 512,
        lowRamDevice: Boolean = false
    ) = DeviceCameraCapabilities(
        hardwareLevel = hardwareLevel,
        rawCaptureSupported = rawCaptureSupported,
        yuvCaptureSupported = yuvCaptureSupported,
        manualExposureSupported = manualExposureSupported,
        burstCaptureSupported = burstCaptureSupported,
        semanticMasksSupported = semanticMasksSupported,
        gpuDelegateSupported = gpuDelegateSupported,
        cameraAvailable = cameraAvailable,
        memoryClassMb = memoryClassMb,
        lowRamDevice = lowRamDevice
    )
}
