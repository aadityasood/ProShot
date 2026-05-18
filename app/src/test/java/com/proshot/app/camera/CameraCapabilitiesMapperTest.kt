package com.proshot.app.camera

import com.proshot.app.camera.compat.CameraHardwareLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying conservative mapping from Camera2 descriptors to [DeviceCameraCapabilities].
 */
class CameraCapabilitiesMapperTest {

    @Test
    fun mapRaw_mapsHardwareLevelsCorrectly() {
        val levels = mapOf(
            2 to CameraHardwareLevel.LEGACY,    // INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
            0 to CameraHardwareLevel.LIMITED,   // INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
            1 to CameraHardwareLevel.FULL,      // INFO_SUPPORTED_HARDWARE_LEVEL_FULL
            3 to CameraHardwareLevel.LEVEL_3,   // INFO_SUPPORTED_HARDWARE_LEVEL_3
            4 to CameraHardwareLevel.EXTERNAL    // INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL
        )

        levels.forEach { (rawLevel, expectedLevel) ->
            val metadata = makeRawMetadata(hardwareLevel = rawLevel)
            val mapped = CameraCapabilitiesMapper.mapRaw(metadata)
            assertEquals(expectedLevel, mapped.hardwareLevel)
        }
    }

    @Test
    fun mapRaw_nullHardwareLevelFallsToLegacy() {
        val metadata = makeRawMetadata(hardwareLevel = null)
        val mapped = CameraCapabilitiesMapper.mapRaw(metadata)
        assertEquals(CameraHardwareLevel.LEGACY, mapped.hardwareLevel)
    }

    @Test
    fun mapRaw_resolvesFormatsAndCapabilities() {
        val metadata = makeRawMetadata(
            availableCapabilities = intArrayOf(
                3, // REQUEST_AVAILABLE_CAPABILITIES_RAW
                1, // REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
                6  // REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE
            ),
            outputFormats = intArrayOf(CameraCapabilitiesMapper.FORMAT_YUV_420_888)
        )

        val mapped = CameraCapabilitiesMapper.mapRaw(metadata)

        assertTrue(mapped.rawCaptureSupported)
        assertTrue(mapped.yuvCaptureSupported)
        assertTrue(mapped.manualExposureSupported)
        assertTrue(mapped.burstCaptureSupported)
    }

    @Test
    fun mapRaw_rawDetectedByCapabilityFlagAloneWithoutOutputFormat() {
        // Device reports RAW capability but RAW_SENSOR is not in the output formats.
        // The OR logic in mapRaw should still mark rawCaptureSupported = true.
        val metadata = makeRawMetadata(
            availableCapabilities = intArrayOf(3), // RAW capability only
            outputFormats = intArrayOf(CameraCapabilitiesMapper.FORMAT_YUV_420_888)
        )
        val mapped = CameraCapabilitiesMapper.mapRaw(metadata)
        assertTrue("RAW should be detected via capability flag alone", mapped.rawCaptureSupported)
    }

    @Test
    fun mapRaw_rawDetectedByOutputFormatAloneWithoutCapabilityFlag() {
        // Device reports RAW_SENSOR in output formats but not in capabilities array.
        // The OR logic should still mark rawCaptureSupported = true.
        val metadata = makeRawMetadata(
            availableCapabilities = intArrayOf(),
            outputFormats = intArrayOf(CameraCapabilitiesMapper.FORMAT_RAW_SENSOR)
        )
        val mapped = CameraCapabilitiesMapper.mapRaw(metadata)
        assertTrue("RAW should be detected via output format alone", mapped.rawCaptureSupported)
    }

    @Test
    fun mapRaw_gpuAndMasksDisabledOnLowMemoryDevice() {
        // High capabilities but process heap memory class is extremely low (< 512MB threshold)
        val metadata = makeRawMetadata(
            hardwareLevel = 1, // FULL
            glEsVersion = 0x30001, // OpenGL ES 3.1
            memoryClassMb = 256, // Under 512MB
            lowRamDevice = false
        )

        val mapped = CameraCapabilitiesMapper.mapRaw(metadata)

        assertFalse(mapped.gpuDelegateSupported)
        assertFalse(mapped.semanticMasksSupported)
    }

    @Test
    fun mapRaw_gpuAndMasksDisabledOnLowRamFlaggedDevice() {
        val metadata = makeRawMetadata(
            hardwareLevel = 3, // LEVEL_3
            glEsVersion = 0x30001,
            memoryClassMb = 512,
            lowRamDevice = true // Explicit low-RAM flag
        )

        val mapped = CameraCapabilitiesMapper.mapRaw(metadata)

        assertFalse(mapped.gpuDelegateSupported)
        assertFalse(mapped.semanticMasksSupported)
    }

    @Test
    fun mapRaw_gpuAndMasksDisabledOnInsufficientGlVersion() {
        val metadata = makeRawMetadata(
            hardwareLevel = 3,
            glEsVersion = 0x30000, // OpenGL ES 3.0 (Requires 3.1+)
            memoryClassMb = 512,
            lowRamDevice = false
        )

        val mapped = CameraCapabilitiesMapper.mapRaw(metadata)

        assertFalse(mapped.gpuDelegateSupported)
        assertFalse(mapped.semanticMasksSupported)
    }

    @Test
    fun mapRaw_gpuAndMasksDisabledOnLegacyOrLimitedHardware() {
        val metadata = makeRawMetadata(
            hardwareLevel = 0, // LIMITED hardware level
            glEsVersion = 0x30001,
            memoryClassMb = 512,
            lowRamDevice = false
        )

        val mapped = CameraCapabilitiesMapper.mapRaw(metadata)

        assertFalse(mapped.gpuDelegateSupported)
        assertFalse(mapped.semanticMasksSupported)
    }

    @Test
    fun mapRaw_enablesGpuAndMasksOnEligiblePremiumConfigurations() {
        val metadata = makeRawMetadata(
            hardwareLevel = 3, // LEVEL_3
            glEsVersion = 0x30001, // OpenGL ES 3.1
            memoryClassMb = 512, // Meets memory requirement
            lowRamDevice = false
        )

        val mapped = CameraCapabilitiesMapper.mapRaw(metadata)

        assertTrue(mapped.gpuDelegateSupported)
        assertTrue(mapped.semanticMasksSupported)
    }

    @Test
    fun mapRaw_noCameraFeatureReturnsFallback() {
        val metadata = makeRawMetadata(hasCameraFeature = false)
        val mapped = CameraCapabilitiesMapper.mapRaw(metadata)

        assertFalse(mapped.cameraAvailable)
        assertFalse(mapped.rawCaptureSupported)
        assertFalse(mapped.yuvCaptureSupported)
        assertFalse(mapped.gpuDelegateSupported)
        assertFalse(mapped.semanticMasksSupported)
    }

    private fun makeRawMetadata(
        hardwareLevel: Int? = 2,
        availableCapabilities: IntArray = intArrayOf(),
        outputFormats: IntArray = intArrayOf(),
        glEsVersion: Int = 0,
        memoryClassMb: Int = 128,
        lowRamDevice: Boolean = false,
        hasCameraFeature: Boolean = true
    ) = RawCameraMetadata(
        hardwareLevel = hardwareLevel,
        availableCapabilities = availableCapabilities,
        outputFormats = outputFormats,
        glEsVersion = glEsVersion,
        memoryClassMb = memoryClassMb,
        lowRamDevice = lowRamDevice,
        hasCameraFeature = hasCameraFeature
    )
}
