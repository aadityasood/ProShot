package com.proshot.app.camera

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import com.proshot.app.camera.compat.CameraHardwareLevel
import com.proshot.app.camera.compat.DeviceCameraCapabilities

/**
 * Pure data model representing raw, unmapped platform camera metadata.
 * Facilitates direct unit testing on the JVM without mocking final Android SDK classes.
 */
data class RawCameraMetadata(
    val hardwareLevel: Int?,
    val availableCapabilities: IntArray?,
    val outputFormats: IntArray?,
    val glEsVersion: Int,
    val memoryClassMb: Int,
    val lowRamDevice: Boolean,
    val hasCameraFeature: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RawCameraMetadata

        if (hardwareLevel != other.hardwareLevel) return false
        if (availableCapabilities != null) {
            if (other.availableCapabilities == null) return false
            if (!availableCapabilities.contentEquals(other.availableCapabilities)) return false
        } else if (other.availableCapabilities != null) return false
        if (outputFormats != null) {
            if (other.outputFormats == null) return false
            if (!outputFormats.contentEquals(other.outputFormats)) return false
        } else if (other.outputFormats != null) return false
        if (glEsVersion != other.glEsVersion) return false
        if (memoryClassMb != other.memoryClassMb) return false
        if (lowRamDevice != other.lowRamDevice) return false
        if (hasCameraFeature != other.hasCameraFeature) return false

        return true
    }

    override fun hashCode(): Int {
        var result = hardwareLevel ?: 0
        result = 31 * result + (availableCapabilities?.contentHashCode() ?: 0)
        result = 31 * result + (outputFormats?.contentHashCode() ?: 0)
        result = 31 * result + glEsVersion
        result = 31 * result + memoryClassMb
        result = 31 * result + lowRamDevice.hashCode()
        result = 31 * result + hasCameraFeature.hashCode()
        return result
    }
}

/**
 * Maps system attributes and camera metadata to a unified [DeviceCameraCapabilities] model.
 * All feature checks are designed conservatively to ensure stable device execution fallbacks.
 */
object CameraCapabilitiesMapper {

    /**
     * Image format constants duplicated from [android.graphics.ImageFormat] to
     * keep [mapRaw] testable on the JVM without Robolectric or platform stubs.
     * If Android ever changes these values, update here and in all test fixtures.
     */
    internal const val FORMAT_RAW_SENSOR = 0x20   // android.graphics.ImageFormat.RAW_SENSOR
    internal const val FORMAT_YUV_420_888 = 0x23   // android.graphics.ImageFormat.YUV_420_888

    /**
     * Resolves the device capabilities using a physical [CameraManager] and the active device context.
     * Maps the primary back camera if available.
     *
     * @param context the Android system context.
     * @return a mapped [DeviceCameraCapabilities] representing the device limits.
     */
    fun map(context: Context): DeviceCameraCapabilities {
        val packageManager = context.packageManager
        val hasCameraFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            activityManager?.isLowRamDevice == true
        } else {
            false
        }
        val memoryClass = activityManager?.memoryClass ?: 128
        val glEsVersion = activityManager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null || !hasCameraFeature) {
            return fallbackCapabilities(memoryClass, lowRam)
        }

        return try {
            val cameraIds = cameraManager.cameraIdList
            val backCameraId = cameraIds.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                facing == CameraMetadata.LENS_FACING_BACK
            } ?: cameraIds.firstOrNull()

            if (backCameraId == null) {
                return fallbackCapabilities(memoryClass, lowRam)
            }

            val characteristics = cameraManager.getCameraCharacteristics(backCameraId)
            val level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

            // Extract supported output formats from target config map
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputFormats = map?.outputFormats

            val rawMetadata = RawCameraMetadata(
                hardwareLevel = level,
                availableCapabilities = capabilities,
                outputFormats = outputFormats,
                glEsVersion = glEsVersion,
                memoryClassMb = memoryClass,
                lowRamDevice = lowRam,
                hasCameraFeature = true
            )

            mapRaw(rawMetadata)
        } catch (e: Exception) {
            fallbackCapabilities(memoryClass, lowRam)
        }
    }

    /**
     * Pure, unit-testable function mapping a [RawCameraMetadata] block to a [DeviceCameraCapabilities].
     */
    fun mapRaw(raw: RawCameraMetadata): DeviceCameraCapabilities {
        if (!raw.hasCameraFeature) {
            return fallbackCapabilities(raw.memoryClassMb, raw.lowRamDevice)
        }

        val hwLevel = when (raw.hardwareLevel) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> CameraHardwareLevel.LEGACY
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> CameraHardwareLevel.LIMITED
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> CameraHardwareLevel.FULL
            3 -> CameraHardwareLevel.LEVEL_3 // INFO_SUPPORTED_HARDWARE_LEVEL_3
            4 -> CameraHardwareLevel.EXTERNAL // INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL
            else -> CameraHardwareLevel.LEGACY // Conservative fallback
        }

        val capabilitiesArray = raw.availableCapabilities ?: intArrayOf()
        val formatsArray = raw.outputFormats ?: intArrayOf()

        val rawSupported = capabilitiesArray.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
        ) || formatsArray.contains(FORMAT_RAW_SENSOR)

        val yuvSupported = formatsArray.contains(FORMAT_YUV_420_888)

        val manualExposureSupported = capabilitiesArray.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        )

        val burstCaptureSupported = capabilitiesArray.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE
        )

        // GPU delegate support is evaluated conservatively:
        // Do not treat OpenGL ES 3.1 version alone as proof that TFLite GPU delegate or semantic masks will work.
        // Block GPU acceleration entirely on low-RAM devices, low heap limits (< 512MB memory class),
        // or hardware levels below FULL/LEVEL_3, as legacy GPU drivers often crash.
        val gpuDelegateSupported = !raw.lowRamDevice &&
                raw.memoryClassMb >= 512 &&
                raw.glEsVersion >= 0x30001 &&
                (hwLevel == CameraHardwareLevel.FULL || hwLevel == CameraHardwareLevel.LEVEL_3)

        // Semantic masks require a strong combination of heap memory and GPU acceleration
        // to run the segmentation model without severe capture frame rate lagging.
        val semanticMasksSupported = gpuDelegateSupported &&
                !raw.lowRamDevice &&
                raw.memoryClassMb >= 512

        return DeviceCameraCapabilities(
            hardwareLevel = hwLevel,
            rawCaptureSupported = rawSupported,
            yuvCaptureSupported = yuvSupported,
            manualExposureSupported = manualExposureSupported,
            burstCaptureSupported = burstCaptureSupported,
            semanticMasksSupported = semanticMasksSupported,
            gpuDelegateSupported = gpuDelegateSupported,
            cameraAvailable = true,
            memoryClassMb = raw.memoryClassMb,
            lowRamDevice = raw.lowRamDevice
        )
    }

    /**
     * Conservative fallback when no camera is detected or a platform exception
     * prevents capability probing. Sets `cameraAvailable = false` and all
     * capture flags to `false`, routing through `BASIC_CAPTURE`.
     *
     * Note: This fallback is used both for genuine "no camera" devices and for
     * transient HAL exceptions. A future refinement may
     * differentiate these cases to avoid over-degrading devices that experience
     * a one-time HAL crash at cold boot.
     */
    private fun fallbackCapabilities(memoryClassMb: Int, lowRam: Boolean): DeviceCameraCapabilities {
        return DeviceCameraCapabilities(
            hardwareLevel = CameraHardwareLevel.LEGACY,
            rawCaptureSupported = false,
            yuvCaptureSupported = false,
            manualExposureSupported = false,
            burstCaptureSupported = false,
            semanticMasksSupported = false,
            gpuDelegateSupported = false,
            cameraAvailable = false,
            memoryClassMb = memoryClassMb,
            lowRamDevice = lowRam
        )
    }
}
