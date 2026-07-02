package com.proshot.app.camera

/**
 * Represents a normalized focus and metering target point in the [0, 1] coordinate space.
 * Coordinates are sensor-space and size is normalized relative to the active array or crop region.
 *
 * @property x The normalized X coordinate, where 0.0 is left and 1.0 is right. Must be in [0.0..1.0].
 * @property y The normalized Y coordinate, where 0.0 is top and 1.0 is bottom. Must be in [0.0..1.0].
 * @property afSize The normalized autofocus bounding box width and height relative to the sensor size. Must be > 0.0.
 * @property aeSize The normalized autoexposure bounding box width and height relative to the sensor size. Must be > 0.0.
 * @property afWeight The autofocus metering weight (typically 0 to 1000). Must be in 0..1000.
 * @property aeWeight The autoexposure metering weight (typically 0 to 1000). Must be in 0..1000.
 */
data class FocusMeteringTarget(
    val x: Float,
    val y: Float,
    val afSize: Float = 0.04f,
    val aeSize: Float = 0.10f,
    val afWeight: Int = 1000,
    val aeWeight: Int = 1000
) {
    init {
        require(x in 0f..1f) { "X coordinate must be in [0.0, 1.0]: $x" }
        require(y in 0f..1f) { "Y coordinate must be in [0.0, 1.0]: $y" }
        require(afSize > 0f) { "AF size must be positive: $afSize" }
        require(aeSize > 0f) { "AE size must be positive: $aeSize" }
        require(afWeight in 0..1000) { "AF weight must be in 0..1000: $afWeight" }
        require(aeWeight in 0..1000) { "AE weight must be in 0..1000: $aeWeight" }
    }

    companion object {
        /**
         * Returns a default centered focus/metering target.
         */
        fun center(): FocusMeteringTarget = FocusMeteringTarget(x = 0.5f, y = 0.5f)
    }
}

/**
 * A platform-agnostic integer rectangle representation.
 */
data class PureRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/**
 * Pure Kotlin utility for mapping normalized focus/metering points into pixel coordinates
 * relative to the sensor active array, supporting optional crop regions (digital zoom).
 */
object FocusMeteringCoordinateMapper {
    /**
     * Maps a normalized focus target into a pixel rectangle relative to the active array.
     *
     * @param target The normalized target coordinates.
     * @param size The normalized size (width/height) of the metering region.
     * @param activeArray The active pixel array size of the sensor.
     * @param cropRegion The optional crop region bounds if digital zoom is active.
     * @return A [PureRect] representing the mapped bounding box, clamped to the active array limits.
     */
    fun mapToActiveArray(
        target: FocusMeteringTarget,
        size: Float,
        activeArray: PureRect,
        cropRegion: PureRect? = null
    ): PureRect {
        require(size > 0f) { "Metering size must be positive: $size" }

        // Resolve reference bounds (use crop region if provided, else fall back to full active array)
        val sourceLeft = cropRegion?.left ?: activeArray.left
        val sourceTop = cropRegion?.top ?: activeArray.top
        val sourceRight = cropRegion?.right ?: activeArray.right
        val sourceBottom = cropRegion?.bottom ?: activeArray.bottom
        val refLeft = minOf(sourceLeft, sourceRight).coerceIn(activeArray.left, activeArray.right)
        val refTop = minOf(sourceTop, sourceBottom).coerceIn(activeArray.top, activeArray.bottom)
        val refRight = maxOf(sourceLeft, sourceRight).coerceIn(activeArray.left, activeArray.right)
        val refBottom = maxOf(sourceTop, sourceBottom).coerceIn(activeArray.top, activeArray.bottom)
        val refWidth = refRight - refLeft
        val refHeight = refBottom - refTop

        // Calculate center point within reference coordinates
        val centerX = refLeft + target.x * refWidth
        val centerY = refTop + target.y * refHeight

        // Determine size within reference coordinates
        val regionWidth = (size * refWidth).coerceAtLeast(1f)
        val regionHeight = (size * refHeight).coerceAtLeast(1f)
        val halfWidth = regionWidth / 2f
        val halfHeight = regionHeight / 2f

        // Compute bounds and clamp to active array limits
        val mappedLeft = kotlin.math.floor(centerX - halfWidth).toInt().coerceIn(activeArray.left, activeArray.right)
        val mappedTop = kotlin.math.floor(centerY - halfHeight).toInt().coerceIn(activeArray.top, activeArray.bottom)
        val mappedRight = kotlin.math.ceil(centerX + halfWidth).toInt().coerceIn(activeArray.left, activeArray.right)
        val mappedBottom = kotlin.math.ceil(centerY + halfHeight).toInt().coerceIn(activeArray.top, activeArray.bottom)

        // Ensure left <= right and top <= bottom
        val finalLeft = minOf(mappedLeft, mappedRight)
        val finalRight = maxOf(mappedLeft, mappedRight)
        val finalTop = minOf(mappedTop, mappedBottom)
        val finalBottom = maxOf(mappedTop, mappedBottom)

        return PureRect(
            left = finalLeft,
            top = finalTop,
            right = finalRight,
            bottom = finalBottom
        )
    }
}
