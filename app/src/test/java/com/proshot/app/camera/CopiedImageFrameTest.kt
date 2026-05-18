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
}
