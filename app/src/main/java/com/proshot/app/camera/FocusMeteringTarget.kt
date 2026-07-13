package com.proshot.app.camera

/**
 * Represents the source of a focus target.
 */
enum class FocusTargetSource {
    /** Target is centered automatically (beginner default). */
    DEFAULT_CENTER,
    /** Target is placed via a user tap on the preview screen. */
    USER_TAP
}

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
 * @property source The source of this focus target (DEFAULT_CENTER or USER_TAP).
 */
data class FocusMeteringTarget(
    val x: Float,
    val y: Float,
    val afSize: Float = 0.04f,
    val aeSize: Float = 0.10f,
    val afWeight: Int = 1000,
    val aeWeight: Int = 1000,
    val source: FocusTargetSource = FocusTargetSource.DEFAULT_CENTER
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
        fun center(): FocusMeteringTarget = FocusMeteringTarget(
            x = 0.5f,
            y = 0.5f,
            source = FocusTargetSource.DEFAULT_CENTER
        )

        /**
         * Returns a user tap-based focus/metering target.
         */
        fun tap(x: Float, y: Float): FocusMeteringTarget = FocusMeteringTarget(
            x = x,
            y = y,
            source = FocusTargetSource.USER_TAP
        )
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

        // Resolve reference bounds (use crop region if provided, else fall back to full active array).
        // Normalize inverted crop regions to prevent negative-width arithmetic.
        val rawRefLeft = cropRegion?.left ?: activeArray.left
        val rawRefTop = cropRegion?.top ?: activeArray.top
        val rawRefRight = cropRegion?.right ?: activeArray.right
        val rawRefBottom = cropRegion?.bottom ?: activeArray.bottom
        val refLeft = minOf(rawRefLeft, rawRefRight).coerceIn(activeArray.left, activeArray.right)
        val refTop = minOf(rawRefTop, rawRefBottom).coerceIn(activeArray.top, activeArray.bottom)
        val refRight = maxOf(rawRefLeft, rawRefRight).coerceIn(activeArray.left, activeArray.right)
        val refBottom = maxOf(rawRefTop, rawRefBottom).coerceIn(activeArray.top, activeArray.bottom)
        val refWidth = refRight - refLeft
        val refHeight = refBottom - refTop

        // Calculate center point within reference coordinates
        val centerX = refLeft + target.x * refWidth
        val centerY = refTop + target.y * refHeight

        // Determine size within reference coordinates, ensuring at least 1 pixel
        // to prevent zero-width MeteringRectangle crashes on Camera2 HALs.
        val regionWidth = (size * refWidth).coerceAtLeast(1f)
        val regionHeight = (size * refHeight).coerceAtLeast(1f)
        val halfWidth = regionWidth / 2f
        val halfHeight = regionHeight / 2f

        // Clamp bounds to the crop region if provided, otherwise to the active array.
        // This prevents metering regions from spilling into active-array pixels
        // outside the visible capture crop (e.g., the top/bottom bars when a 16:9
        // stream is center-cropped from a 4:3 sensor).
        val clampLeft = refLeft
        val clampTop = refTop
        val clampRight = refRight
        val clampBottom = refBottom

        val mappedLeft = (centerX - halfWidth).toInt().coerceIn(clampLeft, clampRight)
        val mappedTop = (centerY - halfHeight).toInt().coerceIn(clampTop, clampBottom)
        val mappedRight = (centerX + halfWidth).toInt().coerceIn(clampLeft, clampRight)
        val mappedBottom = (centerY + halfHeight).toInt().coerceIn(clampTop, clampBottom)

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

/**
 * Pure Kotlin utility for mapping preview tap coordinates in view pixels into sensor-normalized coordinates.
 */
object PreviewTapFocusMapper {
    /**
     * Maps a preview tap location in view pixels to a sensor-normalized [FocusMeteringTarget]
     * using the sensor orientation degrees (constant for a given camera, independent of
     * display rotation).
     *
     * This mapper produces sensor-normalized [0, 1] coordinates which are then mapped into
     * the capture-stream crop region by [FocusMeteringCoordinateMapper.mapToActiveArray].
     * The downstream crop-aware mapper ensures metering rectangles land within the visible
     * capture content, not in the non-visible edges of the full active array.
     *
     * **Known limitation:** The CameraX preview may show a different field of view than the
     * Camera2 capture stream (e.g., 4:3 preview vs 16:9 capture). Taps in the preview area
     * that falls outside the capture crop will clamp to the crop boundary. Aligning the
     * CameraX preview aspect to the capture aspect is a future refinement.
     *
     * @param tapX The tapped X coordinate in view pixels.
     * @param tapY The tapped Y coordinate in view pixels.
     * @param viewWidth The width of the preview view.
     * @param viewHeight The height of the preview view.
     * @param rotationDegrees The sensor orientation angle (0, 90, 180, or 270).
     * @return A [FocusMeteringTarget] with source USER_TAP.
     */
    fun mapToSensorTarget(
        tapX: Float,
        tapY: Float,
        viewWidth: Int,
        viewHeight: Int,
        rotationDegrees: Int
    ): FocusMeteringTarget {
        require(viewWidth > 0) { "viewWidth must be positive: $viewWidth" }
        require(viewHeight > 0) { "viewHeight must be positive: $viewHeight" }
        require(rotationDegrees in listOf(0, 90, 180, 270)) { "Invalid rotation degrees: $rotationDegrees" }

        val clampedX = tapX.coerceIn(0f, viewWidth.toFloat())
        val clampedY = tapY.coerceIn(0f, viewHeight.toFloat())

        val normX = clampedX / viewWidth.toFloat()
        val normY = clampedY / viewHeight.toFloat()

        val (sensorX, sensorY) = when (rotationDegrees) {
            0 -> normX to normY
            90 -> normY to (1.0f - normX)
            180 -> (1.0f - normX) to (1.0f - normY)
            270 -> (1.0f - normY) to normX
            else -> throw IllegalArgumentException("Invalid rotation degrees: $rotationDegrees")
        }

        return FocusMeteringTarget.tap(sensorX, sensorY)
    }
}

/**
 * Pure Kotlin utility for calculating the center-cropped region of a sensor's active pixel array
 * that matches the aspect ratio of a given stream configuration.
 */
object ActiveArrayCropCalculator {
    /**
     * Calculates the center-cropped region of the active array matching the stream aspect ratio.
     *
     * The active array is expected in landscape orientation (width >= height), which is the
     * standard format for `CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE`. Stream
     * dimensions in either portrait or landscape orientation are normalized before comparison.
     *
     * The computed crop is a software-level center crop that identifies where the visible
     * content of the selected Camera2 YUV stream sits within the full active array. This is
     * the correct reference for metering rectangle placement: a sensor-normalized tap at
     * (0.5, 0.5) should meter the center of the visible capture content, not the center of
     * the full active array.
     *
     * **Known limitation:** The CameraX preview stream may use a different aspect ratio or
     * resolution than the selected Camera2 capture stream (CameraX defaults to 4:3 when
     * `setTargetAspectRatio` is not set). If the preview and capture aspects differ, the
     * user sees a 4:3 preview but captures a 16:9 frame. Tap coordinates are mapped relative
     * to the capture crop, not the preview crop. For taps near the top/bottom edges of a
     * 4:3 preview, the metering target will clamp to the 16:9 capture boundary. Aligning
     * the CameraX preview aspect to the capture aspect is a future refinement.
     *
     * @param activeArray The active pixel array size of the sensor (landscape: width >= height).
     * @param streamSize The selected stream size (capture or preview).
     * @return A [PureRect] representing the crop bounds relative to the active array coordinates.
     */
    fun calculateCenterCrop(activeArray: PureRect, streamSize: CaptureSize): PureRect {
        require(streamSize.width > 0) { "stream width must be positive: ${streamSize.width}" }
        require(streamSize.height > 0) { "stream height must be positive: ${streamSize.height}" }
        val arrayWidth = activeArray.right - activeArray.left
        val arrayHeight = activeArray.bottom - activeArray.top
        require(arrayWidth > 0) { "activeArray width must be positive: $arrayWidth" }
        require(arrayHeight > 0) { "activeArray height must be positive: $arrayHeight" }

        // Normalize orientations before computing aspect ratio
        val isArrayLandscape = arrayWidth >= arrayHeight
        val isStreamLandscape = streamSize.width >= streamSize.height

        val normWidth = if (isArrayLandscape == isStreamLandscape) streamSize.width else streamSize.height
        val normHeight = if (isArrayLandscape == isStreamLandscape) streamSize.height else streamSize.width

        val arrayAspect = arrayWidth.toDouble() / arrayHeight
        val streamAspect = normWidth.toDouble() / normHeight

        val cropLeft: Int
        val cropTop: Int
        val cropRight: Int
        val cropBottom: Int

        if (streamAspect > arrayAspect) {
            // Stream is wider than active array: height is cropped
            val croppedHeight = (arrayWidth / streamAspect).toInt()
            val verticalMargin = (arrayHeight - croppedHeight) / 2
            cropLeft = activeArray.left
            cropTop = activeArray.top + verticalMargin
            cropRight = activeArray.right
            cropBottom = cropTop + croppedHeight
        } else if (streamAspect < arrayAspect) {
            // Stream is narrower than active array: width is cropped
            val croppedWidth = (arrayHeight * streamAspect).toInt()
            val horizontalMargin = (arrayWidth - croppedWidth) / 2
            cropLeft = activeArray.left + horizontalMargin
            cropTop = activeArray.top
            cropRight = cropLeft + croppedWidth
            cropBottom = activeArray.bottom
        } else {
            // Aspects match exactly
            cropLeft = activeArray.left
            cropTop = activeArray.top
            cropRight = activeArray.right
            cropBottom = activeArray.bottom
        }

        return PureRect(
            left = cropLeft,
            top = cropTop,
            right = cropRight,
            bottom = cropBottom
        )
    }
}
