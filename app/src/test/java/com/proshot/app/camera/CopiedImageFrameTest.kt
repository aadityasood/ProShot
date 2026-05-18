package com.proshot.app.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Unit tests verifying safe, immutable heap copies of image plane buffers.
 */
class CopiedImageFrameTest {

    private class FakePlaneSource(
        override val rowStride: Int,
        override val pixelStride: Int,
        override val buffer: ByteBuffer
    ) : ImageSource.PlaneSource

    private class FakeImageSource(
        override val format: Int,
        override val width: Int,
        override val height: Int,
        override val timestamp: Long,
        override val planes: List<ImageSource.PlaneSource>
    ) : ImageSource

    @Test
    fun copyFrom_copiesDataWithoutMutatingOriginalBuffer() {
        val originalBytes = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = ByteBuffer.allocateDirect(originalBytes.size).apply {
            put(originalBytes)
            flip()
            position(1) // Set a non-zero start position
        }

        val plane = FakePlaneSource(rowStride = 10, pixelStride = 1, buffer = buffer)
        val image = FakeImageSource(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = 100,
            height = 100,
            timestamp = 123456789L,
            planes = listOf(plane)
        )

        // Record original buffer state
        val originalPosition = buffer.position()
        val originalLimit = buffer.limit()

        val copiedFrame = CopiedImageFrame.copyFrom(image)

        // Verify the original buffer state is completely untouched (duplicate() guarantees this)
        assertEquals("Buffer position must not be modified", originalPosition, buffer.position())
        assertEquals("Buffer limit must not be modified", originalLimit, buffer.limit())

        // Verify the extracted bytes match the expected remaining slice [2, 3, 4, 5]
        val expectedCopiedBytes = byteArrayOf(2, 3, 4, 5)
        assertEquals(1, copiedFrame.planes.size)
        assertEquals(10, copiedFrame.planes[0].rowStride)
        assertEquals(1, copiedFrame.planes[0].pixelStride)
        assertArrayEquals(expectedCopiedBytes, copiedFrame.planes[0].data)
    }

    @Test
    fun copyFrom_ensuresImmutableHeapIsolation() {
        val originalBytes = byteArrayOf(10, 20, 30)
        val buffer = ByteBuffer.allocateDirect(originalBytes.size).apply {
            put(originalBytes)
            flip()
        }

        val plane = FakePlaneSource(rowStride = 5, pixelStride = 1, buffer = buffer)
        val image = FakeImageSource(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = 50,
            height = 50,
            timestamp = 999L,
            planes = listOf(plane)
        )

        val copiedFrame = CopiedImageFrame.copyFrom(image)

        // Mutate original backing direct buffer
        buffer.clear()
        buffer.put(byteArrayOf(99, 99, 99))

        // Copied heap frame must NOT change (it's isolated from direct/native memory pointer changes)
        assertArrayEquals(byteArrayOf(10, 20, 30), copiedFrame.planes[0].data)
    }

    @Test
    fun copyFrom_zeroPlanesProducesEmptyPlaneList() {
        val image = FakeImageSource(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = 100,
            height = 100,
            timestamp = 42L,
            planes = emptyList()
        )

        val copiedFrame = CopiedImageFrame.copyFrom(image)

        assertTrue("Zero-plane source should produce empty planes list", copiedFrame.planes.isEmpty())
        assertEquals(100, copiedFrame.width)
        assertEquals(100, copiedFrame.height)
    }

    @Test
    fun copyFrom_worksWithHeapAllocatedBuffer() {
        // Verify that non-direct (heap) ByteBuffers are handled correctly.
        // Camera2 uses direct buffers, but the ImageSource abstraction allows
        // any ByteBuffer; this tests the non-direct path.
        val originalBytes = byteArrayOf(7, 8, 9, 10)
        val heapBuffer = ByteBuffer.allocate(originalBytes.size).apply {
            put(originalBytes)
            flip()
        }

        val plane = FakePlaneSource(rowStride = 4, pixelStride = 1, buffer = heapBuffer)
        val image = FakeImageSource(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = 2,
            height = 2,
            timestamp = 555L,
            planes = listOf(plane)
        )

        val copiedFrame = CopiedImageFrame.copyFrom(image)

        assertArrayEquals(originalBytes, copiedFrame.planes[0].data)
        // Verify original heap buffer position is untouched
        assertEquals(0, heapBuffer.position())
    }

    @Test
    fun findClosestStableSize_selectsClosestTo1080p() {
        val sizes = listOf(
            CaptureSize(1280, 720),    // area = 921,600 (diff = 1,152,000)
            CaptureSize(1920, 1080),   // area = 2,073,600 (diff = 0)
            CaptureSize(3840, 2160),   // area = 8,294,400 (diff = 6,220,800)
            CaptureSize(640, 480)      // area = 307,200 (diff = 1,766,400)
        )

        val selected = SingleFrameCaptureController.findClosestStableSize(sizes)
        assertEquals(1920, selected.width)
        assertEquals(1080, selected.height)
    }

    @Test
    fun findClosestStableSize_emptyListReturnsFallback() {
        val selected = SingleFrameCaptureController.findClosestStableSize(emptyList())
        assertEquals(1920, selected.width)
        assertEquals(1080, selected.height)
    }

    @Test
    fun findClosestStableSize_selectsClosestAreaWhenNoExact1080p() {
        val sizes = listOf(
            CaptureSize(1600, 1200),   // area = 1,920,000 (diff = 153,600) -> closest!
            CaptureSize(2560, 1440),   // area = 3,686,400 (diff = 1,612,800)
            CaptureSize(800, 600)      // area = 480,000
        )

        val selected = SingleFrameCaptureController.findClosestStableSize(sizes)
        assertEquals(1600, selected.width)
        assertEquals(1200, selected.height)
    }

    @Test
    fun summarizeFrame_producesValidSummary() {
        val planeY = CopiedPlane(rowStride = 100, pixelStride = 1, data = ByteArray(100))
        val planeU = CopiedPlane(rowStride = 50, pixelStride = 2, data = ByteArray(50))
        val planeV = CopiedPlane(rowStride = 50, pixelStride = 2, data = ByteArray(50))

        val frame = CopiedImageFrame(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = 100,
            height = 100,
            timestamp = 987654321L,
            planes = listOf(planeY, planeU, planeV)
        )

        val summary = SingleFrameCaptureController.summarizeFrame(frame)
        assertEquals(100, summary.width)
        assertEquals(100, summary.height)
        assertEquals(987654321L, summary.timestampNs)
        assertEquals("YUV_420_888", summary.formatName)
        assertEquals(100, summary.yPlaneSize)
        assertEquals(50, summary.uPlaneSize)
        assertEquals(50, summary.vPlaneSize)

        val formatted = summary.getFormattedSummary()
        assertTrue(formatted.contains("Res: 100x100"))
        assertTrue(formatted.contains("Time: 987654321ns"))
        assertTrue(formatted.contains("Y: 100"))
        assertTrue(formatted.contains("U: 50"))
        assertTrue(formatted.contains("V: 50"))
    }
}
