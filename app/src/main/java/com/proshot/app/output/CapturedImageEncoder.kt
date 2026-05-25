package com.proshot.app.output

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.proshot.app.camera.CameraCapabilitiesMapper
import com.proshot.app.camera.CopiedImageFrame
import com.proshot.app.camera.CopiedPlane
import java.io.ByteArrayOutputStream

/**
 * Utility to encode and compress YUV_420_888 camera frames to JPEGs.
 *
 * Designed to separate pure Kotlin conversion logic (highly testable on the JVM)
 * from Android-framework-dependent image compression algorithms.
 *
 * TODO: Convert to Hilt-injectable class before integration tests require mock encoder.
 */
object CapturedImageEncoder {
    /**
     * Contiguous NV21 image bytes plus the dimensions that belong to that byte layout.
     */
    data class Nv21Image(
        val data: ByteArray,
        val width: Int,
        val height: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Nv21Image
            return width == other.width && height == other.height && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            return result
        }
    }

    /**
     * Converts a [CopiedImageFrame] in YUV_420_888 format to NV21 ByteArray.
     * NV21 stores all Y bytes first, followed by interleaved V and U bytes (V, U, V, U...).
     *
     * This is a pure Kotlin function, entirely safe for JVM unit testing.
     *
     * @param frame The [CopiedImageFrame] to convert.
     * @return Contiguous NV21 byte array.
     * @throws IllegalArgumentException If dimensions, plane configurations, or format are invalid.
     */
    fun yuv420ToNv21(frame: CopiedImageFrame): ByteArray {
        require(frame.format == CameraCapabilitiesMapper.FORMAT_YUV_420_888) {
            "Unsupported format: ${frame.format}. Expected YUV_420_888 (${CameraCapabilitiesMapper.FORMAT_YUV_420_888})."
        }
        require(frame.planes.size >= 3) {
            "Invalid frame plane count: ${frame.planes.size}. Expected at least 3 planes."
        }
        require(frame.width > 0 && frame.height > 0) {
            "Invalid frame dimensions: ${frame.width}x${frame.height}. Both must be positive."
        }
        require(frame.width % 2 == 0 && frame.height % 2 == 0) {
            "Odd dimensions (${frame.width}x${frame.height}) are not supported by the NV21/JPEG encoding path."
        }

        val width = frame.width
        val height = frame.height
        val nv21 = ByteArray(width * height + (width * height) / 2)

        val yPlane = frame.planes[0]
        val uPlane = frame.planes[1]
        val vPlane = frame.planes[2]

        val yData = yPlane.data
        val uData = uPlane.data
        val vData = vPlane.data

        // 1. Copy Y Plane (Luma)
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        requirePlaneReadable(yPlane, "Y", width, height)

        for (row in 0 until height) {
            val srcRowOffset = row * yRowStride
            val destRowOffset = row * width
            if (yPixelStride == 1) {
                System.arraycopy(yData, srcRowOffset, nv21, destRowOffset, width)
            } else {
                for (col in 0 until width) {
                    val srcIdx = srcRowOffset + col * yPixelStride
                    nv21[destRowOffset + col] = yData[srcIdx]
                }
            }
        }

        // 2. Interleave V and U (Chroma)
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val chromaStart = width * height

        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        requirePlaneReadable(uPlane, "U", chromaWidth, chromaHeight)
        requirePlaneReadable(vPlane, "V", chromaWidth, chromaHeight)

        for (row in 0 until chromaHeight) {
            val uRowOffset = row * uRowStride
            val vRowOffset = row * vRowStride
            val destRowOffset = chromaStart + row * width

            for (col in 0 until chromaWidth) {
                val uSrcIdx = uRowOffset + col * uPixelStride
                val vSrcIdx = vRowOffset + col * vPixelStride

                val uVal = uData[uSrcIdx]
                val vVal = vData[vSrcIdx]

                // NV21 layout alternates V first, then U
                val vDestIdx = destRowOffset + col * 2
                val uDestIdx = destRowOffset + col * 2 + 1

                nv21[vDestIdx] = vVal
                nv21[uDestIdx] = uVal
            }
        }

        return nv21
    }

    /**
     * Rotates an NV21 buffer by [rotationDegrees] clockwise.
     *
     * Camera2 YUV buffers are delivered in sensor orientation. This helper makes
     * the JPEG pixels match the way the user held the device, instead of relying
     * on EXIF orientation metadata that may be ignored by gallery apps.
     */
    fun rotateNv21(
        nv21: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ): Nv21Image {
        require(width > 0 && height > 0) {
            "Invalid NV21 dimensions: ${width}x${height}. Both must be positive."
        }
        require(width % 2 == 0 && height % 2 == 0) {
            "Odd dimensions (${width}x${height}) are not supported by the NV21 rotation path."
        }
        require(rotationDegrees in setOf(0, 90, 180, 270)) {
            "Rotation must be one of 0, 90, 180, or 270 degrees."
        }
        val expectedNv21Size = width * height + (width * height) / 2
        require(nv21.size >= expectedNv21Size) {
            "NV21 buffer is too small: ${nv21.size} bytes. Expected at least $expectedNv21Size bytes."
        }

        if (rotationDegrees == 0) {
            return Nv21Image(nv21, width, height)
        }

        val outputWidth = if (rotationDegrees == 180) width else height
        val outputHeight = if (rotationDegrees == 180) height else width
        val rotated = ByteArray(expectedNv21Size)

        rotateLuma(nv21, rotated, width, height, outputWidth, rotationDegrees)
        rotateChromaNv21(nv21, rotated, width, height, outputWidth, rotationDegrees)

        return Nv21Image(rotated, outputWidth, outputHeight)
    }

    /**
     * Compresses NV21 bytes to JPEG bytes using Android framework's [YuvImage].
     * Runs on caller's dispatcher, should be offloaded to [kotlinx.coroutines.Dispatchers.Default].
     *
     * @param nv21 The source NV21 byte array.
     * @param width The image width.
     * @param height The image height.
     * @param quality The compression quality (1-100). Default is 95.
     * @return Compressed JPEG byte array.
     * @throws IllegalArgumentException If dimensions, quality, or buffer size are invalid.
     * @throws RuntimeException If framework compression fails or produces empty output.
     */
    fun compressNv21ToJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int = 95): ByteArray {
        require(width > 0 && height > 0) {
            "Invalid JPEG dimensions: ${width}x${height}. Both must be positive."
        }
        require(width % 2 == 0 && height % 2 == 0) {
            "Odd dimensions (${width}x${height}) are not supported by the NV21/JPEG encoding path."
        }
        require(quality in 1..100) {
            "JPEG quality must be in the range 1..100."
        }
        val expectedNv21Size = width * height + (width * height) / 2
        require(nv21.size >= expectedNv21Size) {
            "NV21 buffer is too small: ${nv21.size} bytes. Expected at least $expectedNv21Size bytes."
        }

        // Pre-size the output stream with a rough estimate to avoid repeated
        // internal array doubling. At quality 95, JPEG output is typically 10-50%
        // of the NV21 input size. Estimate = nv21.size * quality / 200.
        val estimatedJpegSize = (nv21.size.toLong() * quality / 200).coerceIn(1024, Int.MAX_VALUE.toLong()).toInt()
        val out = ByteArrayOutputStream(estimatedJpegSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val success = yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, out)
        if (!success || out.size() == 0) {
            throw RuntimeException("Failed to compress YUV frame to JPEG")
        }
        return out.toByteArray()
    }

    private fun rotateLuma(
        source: ByteArray,
        target: ByteArray,
        width: Int,
        height: Int,
        outputWidth: Int,
        rotationDegrees: Int
    ) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sourceIndex = y * width + x
                val targetIndex = when (rotationDegrees) {
                    90 -> x * outputWidth + (height - 1 - y)
                    180 -> (height - 1 - y) * outputWidth + (width - 1 - x)
                    270 -> (width - 1 - x) * outputWidth + y
                    else -> sourceIndex
                }
                target[targetIndex] = source[sourceIndex]
            }
        }
    }

    private fun rotateChromaNv21(
        source: ByteArray,
        target: ByteArray,
        width: Int,
        height: Int,
        outputWidth: Int,
        rotationDegrees: Int
    ) {
        val frameSize = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2

        for (y in 0 until chromaHeight) {
            for (x in 0 until chromaWidth) {
                val sourceIndex = frameSize + y * width + x * 2
                val targetPairIndex = when (rotationDegrees) {
                    90 -> x * outputWidth + (chromaHeight - 1 - y) * 2
                    180 -> (chromaHeight - 1 - y) * outputWidth + (chromaWidth - 1 - x) * 2
                    270 -> (chromaWidth - 1 - x) * outputWidth + y * 2
                    else -> y * outputWidth + x * 2
                }
                val targetIndex = frameSize + targetPairIndex
                target[targetIndex] = source[sourceIndex]
                target[targetIndex + 1] = source[sourceIndex + 1]
            }
        }
    }

    private fun requirePlaneReadable(
        plane: CopiedPlane,
        label: String,
        columns: Int,
        rows: Int
    ) {
        require(plane.rowStride > 0) {
            "$label plane rowStride must be positive."
        }
        require(plane.pixelStride > 0) {
            "$label plane pixelStride must be positive."
        }

        val lastRequiredIndex = ((rows - 1).toLong() * plane.rowStride) +
            ((columns - 1).toLong() * plane.pixelStride)
        require(lastRequiredIndex < plane.data.size) {
            "$label plane data is too small: ${plane.data.size} bytes. " +
                "Minimum required size: ${lastRequiredIndex + 1} bytes for ${columns}x$rows plane " +
                "(rowStride=${plane.rowStride}, pixelStride=${plane.pixelStride})."
        }
    }
}
