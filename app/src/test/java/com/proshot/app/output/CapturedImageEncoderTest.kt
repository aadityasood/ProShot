package com.proshot.app.output

import com.proshot.app.camera.CameraCapabilitiesMapper
import com.proshot.app.camera.CopiedImageFrame
import com.proshot.app.camera.CopiedPlane
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests verifying pure Kotlin conversion and stride-handling logic
 * inside [CapturedImageEncoder].
 */
class CapturedImageEncoderTest {

    @Test
    fun yuv420ToNv21_withValidYuvPixelStrideOne_returnsCorrectNv21Layout() {
        val width = 4
        val height = 4
        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)

        // Populate Y with consecutive numbers 1..16
        val yData = ByteArray(ySize) { (it + 1).toByte() }
        // Populate U with 100..103
        val uData = ByteArray(uvSize) { (100 + it).toByte() }
        // Populate V with 200..203
        val vData = ByteArray(uvSize) { (200 + it).toByte() }

        val frame = CopiedImageFrame(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = width,
            height = height,
            timestamp = 1000L,
            planes = listOf(
                CopiedPlane(rowStride = width, pixelStride = 1, data = yData),
                CopiedPlane(rowStride = width / 2, pixelStride = 1, data = uData),
                CopiedPlane(rowStride = width / 2, pixelStride = 1, data = vData)
            )
        )

        val result = CapturedImageEncoder.yuv420ToNv21(frame)

        // Expected NV21 size: width * height + width * height / 2 = 16 + 8 = 24 bytes
        assertEquals(24, result.size)

        // Y component at 0..15 should match yData exactly
        val expectedY = yData
        val actualY = result.copyOfRange(0, 16)
        assertArrayEquals(expectedY, actualY)

        // UV component at 16..23 should alternate V, U
        // V elements: 200, 201, 202, 203
        // U elements: 100, 101, 102, 103
        // NV21 order: V0, U0, V1, U1, V2, U2, V3, U3
        val expectedUV = byteArrayOf(
            200.toByte(), 100.toByte(),
            201.toByte(), 101.toByte(),
            202.toByte(), 102.toByte(),
            203.toByte(), 103.toByte()
        )
        val actualUV = result.copyOfRange(16, 24)
        assertArrayEquals(expectedUV, actualUV)
    }

    @Test
    fun yuv420ToNv21_withPixelStrideGreaterThanOne_handlesStridesAndInterleavesCorrectly() {
        val width = 4
        val height = 4
        val ySize = width * height

        // Y Plane: contiguous (pixelStride = 1)
        val yData = ByteArray(ySize) { (it + 1).toByte() }

        // U and V Planes: pixelStride = 2, rowStride = 4 (typical Android semi-planar arrangement)
        // Array sizes will have padding because of the stride.
        // For a 2x2 chroma, row 0 needs indices 0 and 2. row 1 needs indices 4 and 6.
        // So size needs to be at least 7 bytes. Let's make it 8.
        val uData = byteArrayOf(10, -1, 11, -1, 12, -1, 13, -1)
        val vData = byteArrayOf(20, -1, 21, -1, 22, -1, 23, -1)

        val frame = CopiedImageFrame(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = width,
            height = height,
            timestamp = 1000L,
            planes = listOf(
                CopiedPlane(rowStride = width, pixelStride = 1, data = yData),
                CopiedPlane(rowStride = 4, pixelStride = 2, data = uData),
                CopiedPlane(rowStride = 4, pixelStride = 2, data = vData)
            )
        )

        val result = CapturedImageEncoder.yuv420ToNv21(frame)

        // Verify Y copied correctly
        assertArrayEquals(yData, result.copyOfRange(0, 16))

        // Verify UV interleaving correctly ignores padding/stride bytes
        // Expected: V0, U0, V1, U1, V2, U2, V3, U3
        // V0 = vData[0] = 20, U0 = uData[0] = 10
        // V1 = vData[2] = 21, U1 = uData[2] = 11
        // V2 = vData[4] = 22, U2 = uData[4] = 12
        // V3 = vData[6] = 23, U3 = uData[6] = 13
        val expectedUV = byteArrayOf(
            20, 10,
            21, 11,
            22, 12,
            23, 13
        )
        assertArrayEquals(expectedUV, result.copyOfRange(16, 24))
    }

    @Test
    fun yuv420ToNv21_throwsOnUnsupportedFormat() {
        val frame = CopiedImageFrame(
            format = 999, // Unknown format
            width = 4,
            height = 4,
            timestamp = 0L,
            planes = listOf(
                CopiedPlane(4, 1, ByteArray(16)),
                CopiedPlane(2, 1, ByteArray(4)),
                CopiedPlane(2, 1, ByteArray(4))
            )
        )

        try {
            CapturedImageEncoder.yuv420ToNv21(frame)
            fail("Expected IllegalArgumentException for unsupported format")
        } catch (e: IllegalArgumentException) {
            assertEquals("Unsupported format: 999. Expected YUV_420_888 (${com.proshot.app.camera.CameraCapabilitiesMapper.FORMAT_YUV_420_888}).", e.message)
        }
    }

    @Test
    fun yuv420ToNv21_throwsOnInsufficientPlanes() {
        val frame = CopiedImageFrame(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = 4,
            height = 4,
            timestamp = 0L,
            planes = listOf(
                CopiedPlane(4, 1, ByteArray(16)),
                CopiedPlane(2, 1, ByteArray(4))
            )
        )

        try {
            CapturedImageEncoder.yuv420ToNv21(frame)
            fail("Expected IllegalArgumentException for insufficient planes")
        } catch (e: IllegalArgumentException) {
            assertEquals("Invalid frame plane count: 2. Expected at least 3 planes.", e.message)
        }
    }

    @Test
    fun yuv420ToNv21_throwsOnNonPositiveDimensions() {
        val basePlanes = listOf(
            CopiedPlane(4, 1, ByteArray(16)),
            CopiedPlane(2, 1, ByteArray(4)),
            CopiedPlane(2, 1, ByteArray(4))
        )

        try {
            val frame = CopiedImageFrame(CameraCapabilitiesMapper.FORMAT_YUV_420_888, 0, 4, 0L, basePlanes)
            CapturedImageEncoder.yuv420ToNv21(frame)
            fail("Expected IllegalArgumentException for zero width")
        } catch (e: IllegalArgumentException) {
            assertEquals("Invalid frame dimensions: 0x4. Both must be positive.", e.message)
        }

        try {
            val frame = CopiedImageFrame(CameraCapabilitiesMapper.FORMAT_YUV_420_888, 4, -2, 0L, basePlanes)
            CapturedImageEncoder.yuv420ToNv21(frame)
            fail("Expected IllegalArgumentException for negative height")
        } catch (e: IllegalArgumentException) {
            assertEquals("Invalid frame dimensions: 4x-2. Both must be positive.", e.message)
        }
    }

    @Test
    fun yuv420ToNv21_throwsOnOddDimensions() {
        val basePlanes = listOf(
            CopiedPlane(4, 1, ByteArray(16)),
            CopiedPlane(2, 1, ByteArray(4)),
            CopiedPlane(2, 1, ByteArray(4))
        )

        try {
            val frame = CopiedImageFrame(CameraCapabilitiesMapper.FORMAT_YUV_420_888, 3, 4, 0L, basePlanes)
            CapturedImageEncoder.yuv420ToNv21(frame)
            fail("Expected IllegalArgumentException for odd width")
        } catch (e: IllegalArgumentException) {
            assertEquals("Odd dimensions (3x4) are not supported by the NV21/JPEG encoding path.", e.message)
        }

        try {
            val frame = CopiedImageFrame(CameraCapabilitiesMapper.FORMAT_YUV_420_888, 4, 101, 0L, basePlanes)
            CapturedImageEncoder.yuv420ToNv21(frame)
            fail("Expected IllegalArgumentException for odd height")
        } catch (e: IllegalArgumentException) {
            assertEquals("Odd dimensions (4x101) are not supported by the NV21/JPEG encoding path.", e.message)
        }
    }

    @Test
    fun yuv420ToNv21_throwsWhenYPlaneIsTooSmall() {
        val frame = CopiedImageFrame(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = 4,
            height = 4,
            timestamp = 0L,
            planes = listOf(
                CopiedPlane(4, 1, ByteArray(15)),
                CopiedPlane(2, 1, ByteArray(4)),
                CopiedPlane(2, 1, ByteArray(4))
            )
        )

        try {
            CapturedImageEncoder.yuv420ToNv21(frame)
            fail("Expected IllegalArgumentException for truncated Y plane")
        } catch (e: IllegalArgumentException) {
            assertEquals(
                "Y plane data is too small: 15 bytes. " +
                    "Minimum required size: 16 bytes for 4x4 plane " +
                    "(rowStride=4, pixelStride=1).",
                e.message
            )
        }
    }

    @Test
    fun yuv420ToNv21_throwsWhenChromaPlaneIsTooSmall() {
        val frame = CopiedImageFrame(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = 4,
            height = 4,
            timestamp = 0L,
            planes = listOf(
                CopiedPlane(4, 1, ByteArray(16)),
                CopiedPlane(4, 2, ByteArray(6)),
                CopiedPlane(4, 2, ByteArray(8))
            )
        )

        try {
            CapturedImageEncoder.yuv420ToNv21(frame)
            fail("Expected IllegalArgumentException for truncated U plane")
        } catch (e: IllegalArgumentException) {
            assertEquals(
                "U plane data is too small: 6 bytes. " +
                    "Minimum required size: 7 bytes for 2x2 plane " +
                    "(rowStride=4, pixelStride=2).",
                e.message
            )
        }
    }

    @Test
    fun compressNv21ToJpeg_rejectsInvalidArgumentsBeforeFrameworkCompression() {
        try {
            CapturedImageEncoder.compressNv21ToJpeg(ByteArray(24), width = 4, height = 4, quality = 101)
            fail("Expected IllegalArgumentException for invalid JPEG quality")
        } catch (e: IllegalArgumentException) {
            assertEquals("JPEG quality must be in the range 1..100.", e.message)
        }

        try {
            CapturedImageEncoder.compressNv21ToJpeg(ByteArray(23), width = 4, height = 4)
            fail("Expected IllegalArgumentException for undersized NV21 buffer")
        } catch (e: IllegalArgumentException) {
            assertEquals("NV21 buffer is too small: 23 bytes. Expected at least 24 bytes.", e.message)
        }
    }

    @Test
    fun rotateNv21_withNinetyDegrees_rotatesPixelsClockwiseAndSwapsDimensions() {
        val source = byteArrayOf(
            0, 1, 2, 3,
            4, 5, 6, 7,
            8, 9, 10, 11
        )

        val result = CapturedImageEncoder.rotateNv21(source, width = 4, height = 2, rotationDegrees = 90)

        assertEquals(2, result.width)
        assertEquals(4, result.height)
        assertArrayEquals(
            byteArrayOf(
                4, 0,
                5, 1,
                6, 2,
                7, 3,
                8, 9,
                10, 11
            ),
            result.data
        )
    }

    @Test
    fun rotateNv21_withTwoHundredSeventyDegrees_rotatesPixelsCounterClockwiseAndSwapsDimensions() {
        val source = byteArrayOf(
            0, 1, 2, 3,
            4, 5, 6, 7,
            8, 9, 10, 11
        )

        val result = CapturedImageEncoder.rotateNv21(source, width = 4, height = 2, rotationDegrees = 270)

        assertEquals(2, result.width)
        assertEquals(4, result.height)
        assertArrayEquals(
            byteArrayOf(
                3, 7,
                2, 6,
                1, 5,
                0, 4,
                10, 11,
                8, 9
            ),
            result.data
        )
    }

    @Test
    fun rotateNv21_withOneHundredEightyDegrees_rotatesPixelsWithoutSwappingDimensions() {
        val source = byteArrayOf(
            0, 1, 2, 3,
            4, 5, 6, 7,
            8, 9, 10, 11
        )

        val result = CapturedImageEncoder.rotateNv21(source, width = 4, height = 2, rotationDegrees = 180)

        assertEquals(4, result.width)
        assertEquals(2, result.height)
        assertArrayEquals(
            byteArrayOf(
                7, 6, 5, 4,
                3, 2, 1, 0,
                10, 11, 8, 9
            ),
            result.data
        )
    }

    @Test
    fun rotateNv21_rejectsUnsupportedRotation() {
        try {
            CapturedImageEncoder.rotateNv21(ByteArray(12), width = 4, height = 2, rotationDegrees = 45)
            fail("Expected IllegalArgumentException for unsupported rotation")
        } catch (e: IllegalArgumentException) {
            assertEquals("Rotation must be one of 0, 90, 180, or 270 degrees.", e.message)
        }
    }

    /**
     * Non-degenerate 270 degree rotation test with a 4x4 frame.
     * This exercises the chroma row dimension that was degenerate in the 4x2 test.
     */
    @Test
    fun rotateNv21_with270Degrees_4x4_exercisesNonDegenerateChromaRows() {
        // 4x4 luma + 8 bytes chroma = 24 bytes total NV21.
        val source = byteArrayOf(
            0, 1, 2, 3,
            4, 5, 6, 7,
            8, 9, 10, 11,
            12, 13, 14, 15,
            // chroma (VU pairs)
            16, 17, 18, 19,
            20, 21, 22, 23
        )

        val result = CapturedImageEncoder.rotateNv21(source, width = 4, height = 4, rotationDegrees = 270)

        assertEquals(4, result.width)
        assertEquals(4, result.height)

        // Verify luma
        val expectedLuma = byteArrayOf(
            3, 7, 11, 15,
            2, 6, 10, 14,
            1, 5,  9, 13,
            0, 4,  8, 12
        )
        assertArrayEquals(expectedLuma, result.data.copyOfRange(0, 16))

        // Verify chroma
        val expectedChroma = byteArrayOf(
            18, 19, 22, 23,
            16, 17, 20, 21
        )
        assertArrayEquals(expectedChroma, result.data.copyOfRange(16, 24))
    }

    /**
     * Non-degenerate 90 degree rotation test with a 4x4 frame.
     * This verifies chroma rows as well as luma rows.
     */
    @Test
    fun rotateNv21_with90Degrees_4x4_exercisesNonDegenerateChromaRows() {
        val source = byteArrayOf(
            0, 1, 2, 3,
            4, 5, 6, 7,
            8, 9, 10, 11,
            12, 13, 14, 15,
            // chroma (VU pairs)
            16, 17, 18, 19,
            20, 21, 22, 23
        )

        val result = CapturedImageEncoder.rotateNv21(source, width = 4, height = 4, rotationDegrees = 90)

        assertEquals(4, result.width)
        assertEquals(4, result.height)

        // Verify luma
        val expectedLuma = byteArrayOf(
            12, 8, 4, 0,
            13, 9, 5, 1,
            14, 10, 6, 2,
            15, 11, 7, 3
        )
        assertArrayEquals(expectedLuma, result.data.copyOfRange(0, 16))

        // Verify chroma
        val expectedChroma = byteArrayOf(
            20, 21, 16, 17,
            22, 23, 18, 19
        )
        assertArrayEquals(expectedChroma, result.data.copyOfRange(16, 24))
    }
}
