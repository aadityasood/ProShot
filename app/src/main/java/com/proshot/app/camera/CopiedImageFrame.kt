package com.proshot.app.camera

import android.media.Image
import java.nio.ByteBuffer

/**
 * An abstraction over native image data sources to facilitate unit testing on the JVM
 * without depending on native platform stubs.
 */
interface ImageSource {
    /**
     * The image format (e.g., [android.graphics.ImageFormat.YUV_420_888] or RAW).
     */
    val format: Int

    /**
     * The width of the image in pixels.
     */
    val width: Int

    /**
     * The height of the image in pixels.
     */
    val height: Int

    /**
     * The hardware capture timestamp in nanoseconds.
     */
    val timestamp: Long

    /**
     * The list of image plane sources.
     */
    val planes: List<PlaneSource>

    /**
     * Represents a single plane of an image.
     */
    interface PlaneSource {
        /**
         * Row stride in bytes.
         */
        val rowStride: Int

        /**
         * Pixel stride in bytes.
         */
        val pixelStride: Int

        /**
         * Direct [ByteBuffer] containing the plane's pixel data.
         */
        val buffer: ByteBuffer
    }
}

/**
 * Platform implementation of [ImageSource] wrapping an [android.media.Image].
 */
class AndroidImageSource(private val image: Image) : ImageSource {
    override val format: Int get() = image.format
    override val width: Int get() = image.width
    override val height: Int get() = image.height
    override val timestamp: Long get() = image.timestamp
    override val planes: List<ImageSource.PlaneSource> = image.planes.map { AndroidPlaneSource(it) }

    private class AndroidPlaneSource(plane: Image.Plane) : ImageSource.PlaneSource {
        override val rowStride: Int = plane.rowStride
        override val pixelStride: Int = plane.pixelStride
        // Eagerly read the buffer while the Image is guaranteed open.
        // A lazy get() would risk accessing an invalidated native pointer if
        // the Image is closed between AndroidImageSource construction and
        // CopiedImageFrame.copyFrom reading the buffer.
        override val buffer: ByteBuffer = plane.buffer
    }
}

/**
 * Holds a copied image plane's pixel bytes on the JVM heap.
 * The byte array contains a direct copy of the original plane's [ByteBuffer] contents,
 * ensuring that data access is safe even after the original camera frame is closed.
 */
data class CopiedPlane(
    val rowStride: Int,
    val pixelStride: Int,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CopiedPlane

        if (rowStride != other.rowStride) return false
        if (pixelStride != other.pixelStride) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = rowStride
        result = 31 * result + pixelStride
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * A safe, heap-allocated representation of an [Image] containing copied frame planes.
 * All plane data is copied immediately upon instantiation, preventing native memory invalidation
 * after the original camera frame is closed or recycled.
 */
data class CopiedImageFrame(
    val format: Int,
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val planes: List<CopiedPlane>
) {
    companion object {
        /**
         * Copies an [Image] into a heap-allocated [CopiedImageFrame].
         * Must be invoked while the [Image] is still open and its native buffers are valid.
         */
        fun copyFrom(image: Image): CopiedImageFrame {
            return copyFrom(AndroidImageSource(image))
        }

        /**
         * Copies an [ImageSource] into a heap-allocated [CopiedImageFrame].
         */
        fun copyFrom(source: ImageSource): CopiedImageFrame {
            val copiedPlanes = source.planes.map { plane ->
                // Use duplicate() to create an independent buffer view with its own
                // position, limit, and mark. This avoids mutating the original
                // Camera2 buffer's position/mark state, eliminating both the
                // concurrent-access risk and the mark-invalidation issue.
                val view = plane.buffer.duplicate()
                val bytes = ByteArray(view.remaining())
                view.get(bytes)
                CopiedPlane(
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    data = bytes
                )
            }
            return CopiedImageFrame(
                format = source.format,
                width = source.width,
                height = source.height,
                timestamp = source.timestamp,
                planes = copiedPlanes
            )
        }
    }
}
