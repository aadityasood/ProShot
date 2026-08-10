package com.proshot.app.camera

import kotlin.math.abs

/**
 * Pure 2D affine transform for 2D coordinate mapping without Android framework dependencies.
 * Represents matrix:
 * [ a  b  tx ]
 * [ c  d  ty ]
 */
data class PureAffineTransform(
    val a: Float = 1f, val b: Float = 0f, val tx: Float = 0f,
    val c: Float = 0f, val d: Float = 1f, val ty: Float = 0f
) {
    /** Maps one point through this affine transform. */
    fun mapPoint(x: Float, y: Float): FloatArray {
        val px = a * x + b * y + tx
        val py = c * x + d * y + ty
        return floatArrayOf(px, py)
    }

    /** Returns the inverse transform, or `null` when the matrix is singular. */
    fun invert(): PureAffineTransform? {
        val det = a * d - b * c
        if (abs(det) < 1e-6f) return null
        val invDet = 1f / det
        val ia = d * invDet
        val ib = -b * invDet
        val ic = -c * invDet
        val id = a * invDet
        val itx = (b * ty - d * tx) * invDet
        val ity = (c * tx - a * ty) * invDet
        return PureAffineTransform(ia, ib, itx, ic, id, ity)
    }

}

internal data class DirectPreviewTransform(
    /** Raw-buffer-to-view map used only for inverse tap metering; never pass it to TextureView. */
    val bufferToView: PureAffineTransform,
    val bufferWidth: Float,
    val bufferHeight: Float,
    /** View-local content correction that is the only transform valid for TextureView rendering. */
    val textureViewRenderTransform: TextureViewRenderTransform
) {
    fun mapTapToSensorTarget(tapX: Float, tapY: Float): FocusMeteringTarget {
        if (!DirectPreviewGeometry.areDimensionsValid(bufferWidth, bufferHeight)) {
            return FocusMeteringTarget.center()
        }
        val inverse = bufferToView.invert() ?: return FocusMeteringTarget.center()
        val bufferPoint = inverse.mapPoint(tapX, tapY)
        return FocusMeteringTarget(
            x = (bufferPoint[0] / bufferWidth).coerceIn(0f, 1f),
            y = (bufferPoint[1] / bufferHeight).coerceIn(0f, 1f),
            source = FocusTargetSource.USER_TAP
        )
    }
}

/**
 * Pure view-local correction for TextureView content.
 *
 * This value is valid for `TextureView.setTransform()`. It must not be used to
 * invert taps into raw camera-buffer coordinates.
 */
internal data class TextureViewRenderTransform(
    val correctionScaleX: Float = 1f,
    val correctionScaleY: Float = 1f,
    val negativeDisplayRotationDegrees: Float = 0f,
    val pivotX: Float = 0f,
    val pivotY: Float = 0f
)

/** Pure geometry that keeps TextureView rendering separate from raw-buffer tap mapping. */
object DirectPreviewGeometry {

    /** Computes clockwise sensor-to-display rotation for the back camera. */
    @JvmStatic
    fun computeRelativeRotation(sensorOrientation: Int, displayRotationDegrees: Int): Int {
        return normalizeDegrees(sensorOrientation - displayRotationDegrees)
    }

    /**
     * Calculates the pure 2D transform matrix mapping stream buffer coordinates [0, bufferWidth] x [0, bufferHeight]
     * to TextureView coordinates [0, viewWidth] x [0, viewHeight].
     */
    @JvmStatic
    fun calculateTransform(
        viewWidth: Float,
        viewHeight: Float,
        bufferWidth: Float,
        bufferHeight: Float,
        sensorOrientation: Int,
        displayRotationDegrees: Int
    ): PureAffineTransform {
        if (!areDimensionsValid(viewWidth, viewHeight, bufferWidth, bufferHeight)) {
            return PureAffineTransform()
        }

        val relativeRotation = computeRelativeRotation(sensorOrientation, displayRotationDegrees)
        val isRotated = relativeRotation == 90 || relativeRotation == 270
        val rotatedW = if (isRotated) bufferHeight else bufferWidth
        val rotatedH = if (isRotated) bufferWidth else bufferHeight

        val scale = maxOf(viewWidth / rotatedW, viewHeight / rotatedH)

        val bufCx = bufferWidth / 2f
        val bufCy = bufferHeight / 2f
        val viewCx = viewWidth / 2f
        val viewCy = viewHeight / 2f

        val rad = Math.toRadians(relativeRotation.toDouble())
        val cosR = Math.cos(rad).toFloat()
        val sinR = Math.sin(rad).toFloat()

        val a = cosR * scale
        val b = -sinR * scale
        val c = sinR * scale
        val d = cosR * scale

        val tx = viewCx - (a * bufCx + b * bufCy)
        val ty = viewCy - (c * bufCx + d * bufCy)

        return PureAffineTransform(a, b, tx, c, d, ty)
    }

    /**
     * Calculates the view-local correction applied to TextureView content.
     *
     * TextureView has already compensated for sensor orientation. The sensor
     * axis family and relative sensor/display rotation select Android's reference
     * scale branches, while this value applies only negative display rotation
     * around the view center.
     */
    internal fun calculateTextureViewRenderTransform(
        viewWidth: Float,
        viewHeight: Float,
        bufferWidth: Float,
        bufferHeight: Float,
        sensorOrientation: Int,
        displayRotationDegrees: Int
    ): TextureViewRenderTransform {
        val neutral = TextureViewRenderTransform(
            pivotX = validCenter(viewWidth),
            pivotY = validCenter(viewHeight)
        )
        if (!areDimensionsValid(viewWidth, viewHeight, bufferWidth, bufferHeight)) {
            return neutral
        }

        val normalizedSensorOrientation = normalizeDegrees(sensorOrientation)
        val normalizedDisplayRotation = normalizeDegrees(displayRotationDegrees)
        val sensorAxesAreNatural =
            normalizedSensorOrientation == 0 || normalizedSensorOrientation == 180
        val relativeRotation = computeRelativeRotation(
            normalizedSensorOrientation,
            normalizedDisplayRotation
        )
        val isRotationRequired = relativeRotation % 180 != 0

        // This is the Android resizable Camera2 preview scale calculation,
        // generalized so sensor 180 follows sensor 0 and sensor 270 follows 90.
        val scaleX = if (sensorAxesAreNatural) {
            if (isRotationRequired) viewWidth / bufferWidth else viewWidth / bufferHeight
        } else {
            if (isRotationRequired) viewWidth / bufferHeight else viewWidth / bufferWidth
        }
        val scaleY = if (sensorAxesAreNatural) {
            if (isRotationRequired) viewHeight / bufferHeight else viewHeight / bufferWidth
        } else {
            if (isRotationRequired) viewHeight / bufferWidth else viewHeight / bufferHeight
        }
        val finalScale = maxOf(scaleX, scaleY)
        val correctionScaleX: Float
        val correctionScaleY: Float
        if (isRotationRequired) {
            correctionScaleX = finalScale / scaleX
            correctionScaleY = finalScale / scaleY
        } else {
            correctionScaleX = viewHeight / viewWidth / scaleY * finalScale
            correctionScaleY = viewWidth / viewHeight / scaleX * finalScale
        }

        if (!areDimensionsValid(correctionScaleX, correctionScaleY)) return neutral
        return TextureViewRenderTransform(
            correctionScaleX = correctionScaleX,
            correctionScaleY = correctionScaleY,
            negativeDisplayRotationDegrees = -normalizedDisplayRotation.toFloat(),
            pivotX = viewWidth / 2f,
            pivotY = viewHeight / 2f
        )
    }

    internal fun createPreviewTransform(
        viewWidth: Float,
        viewHeight: Float,
        bufferWidth: Float,
        bufferHeight: Float,
        sensorOrientation: Int,
        displayRotationDegrees: Int
    ): DirectPreviewTransform {
        return DirectPreviewTransform(
            bufferToView = calculateTransform(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                sensorOrientation = sensorOrientation,
                displayRotationDegrees = displayRotationDegrees
            ),
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            textureViewRenderTransform = calculateTextureViewRenderTransform(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                sensorOrientation = sensorOrientation,
                displayRotationDegrees = displayRotationDegrees
            )
        )
    }

    /**
     * Maps a tap point in view coordinates [0, viewWidth] x [0, viewHeight] to normalized [0.0, 1.0] sensor coordinates.
     */
    @JvmStatic
    fun mapTapToNormalizedSensorTarget(
        tapX: Float,
        tapY: Float,
        viewWidth: Float,
        viewHeight: Float,
        bufferWidth: Float,
        bufferHeight: Float,
        sensorOrientation: Int,
        displayRotationDegrees: Int
    ): FocusMeteringTarget {
        if (!areDimensionsValid(viewWidth, viewHeight, bufferWidth, bufferHeight)) {
            return FocusMeteringTarget.center()
        }
        val transform = calculateTransform(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            sensorOrientation = sensorOrientation,
            displayRotationDegrees = displayRotationDegrees
        )
        val inverse = transform.invert() ?: return FocusMeteringTarget.center()

        val bufPts = inverse.mapPoint(tapX, tapY)

        val normX = (bufPts[0] / bufferWidth).coerceIn(0f, 1f)
        val normY = (bufPts[1] / bufferHeight).coerceIn(0f, 1f)

        return FocusMeteringTarget(
            x = normX,
            y = normY,
            source = FocusTargetSource.USER_TAP
        )
    }

    internal fun areDimensionsValid(vararg dimensions: Float): Boolean {
        return dimensions.all { dimension -> dimension > 0f && dimension.isFinite() }
    }

    private fun normalizeDegrees(rotationDegrees: Int): Int {
        return ((rotationDegrees % 360) + 360) % 360
    }

    private fun validCenter(dimension: Float): Float {
        return if (dimension > 0f && dimension.isFinite()) dimension / 2f else 0f
    }
}
