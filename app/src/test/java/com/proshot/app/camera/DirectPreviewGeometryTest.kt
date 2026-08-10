package com.proshot.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPreviewGeometryTest {

    @Test
    fun rawBufferTransformAndInverse_preserveNonCenterPointAcrossOrientations() {
        val cases = listOf(
            GeometryCase(1080f, 1920f, sensorOrientation = 90, displayRotation = 0),
            GeometryCase(1920f, 1080f, sensorOrientation = 90, displayRotation = 90),
            GeometryCase(1920f, 1080f, sensorOrientation = 90, displayRotation = 270)
        )

        cases.forEach { case ->
            val previewTransform = DirectPreviewGeometry.createPreviewTransform(
                viewWidth = case.viewWidth,
                viewHeight = case.viewHeight,
                bufferWidth = BUFFER_WIDTH,
                bufferHeight = BUFFER_HEIGHT,
                sensorOrientation = case.sensorOrientation,
                displayRotationDegrees = case.displayRotation
            )
            val viewPoint = previewTransform.bufferToView.mapPoint(
                BUFFER_WIDTH * 0.27f,
                BUFFER_HEIGHT * 0.63f
            )
            val target = previewTransform.mapTapToSensorTarget(viewPoint[0], viewPoint[1])

            assertEquals(0.27f, target.x, TOLERANCE)
            assertEquals(0.63f, target.y, TOLERANCE)
        }
    }

    @Test
    fun computeRelativeRotation_calculatesCorrectDegrees() {
        assertEquals(90, DirectPreviewGeometry.computeRelativeRotation(90, 0))
        assertEquals(0, DirectPreviewGeometry.computeRelativeRotation(90, 90))
        assertEquals(180, DirectPreviewGeometry.computeRelativeRotation(90, 270))
        assertEquals(270, DirectPreviewGeometry.computeRelativeRotation(270, 0))
    }

    @Test
    fun textureViewRender_portraitDoesNotReapplySensorRotation() {
        val render = renderTransform(
            viewWidth = 1080f,
            viewHeight = 1920f,
            displayRotation = 0
        )

        assertEquals(1f, render.correctionScaleX, TOLERANCE)
        assertEquals(1f, render.correctionScaleY, TOLERANCE)
        assertEquals(0f, render.negativeDisplayRotationDegrees, TOLERANCE)
        assertEquals(540f, render.pivotX, TOLERANCE)
        assertEquals(960f, render.pivotY, TOLERANCE)
    }

    @Test
    fun textureViewRender_landscape90ReversesDefaultStretchAndRotatesDisplayOnly() {
        val render = renderTransform(
            viewWidth = 1920f,
            viewHeight = 1080f,
            displayRotation = 90
        )

        assertEquals(0.5625f, render.correctionScaleX, TOLERANCE)
        assertEquals(1.7777778f, render.correctionScaleY, TOLERANCE)
        assertEquals(-90f, render.negativeDisplayRotationDegrees, TOLERANCE)
        assertEquals(960f, render.pivotX, TOLERANCE)
        assertEquals(540f, render.pivotY, TOLERANCE)
    }

    @Test
    fun textureViewRender_landscape270ReversesDefaultStretchAndRotatesDisplayOnly() {
        val render = renderTransform(
            viewWidth = 1920f,
            viewHeight = 1080f,
            displayRotation = 270
        )

        assertEquals(0.5625f, render.correctionScaleX, TOLERANCE)
        assertEquals(1.7777778f, render.correctionScaleY, TOLERANCE)
        assertEquals(-270f, render.negativeDisplayRotationDegrees, TOLERANCE)
    }

    @Test
    fun textureViewRender_reversePortraitSupportsDisplay180() {
        val render = renderTransform(
            viewWidth = 1080f,
            viewHeight = 1920f,
            displayRotation = 180
        )

        assertEquals(1f, render.correctionScaleX, TOLERANCE)
        assertEquals(1f, render.correctionScaleY, TOLERANCE)
        assertEquals(-180f, render.negativeDisplayRotationDegrees, TOLERANCE)
    }

    @Test
    fun textureViewRender_sensor0NaturalAxesUseReferencePortraitScale() {
        val render = renderTransform(
            viewWidth = 1080f,
            viewHeight = 1920f,
            displayRotation = 0,
            sensorOrientation = 0
        )

        assertEquals(1920f / 1080f, render.correctionScaleX, TOLERANCE)
        assertEquals(1080f / 1920f, render.correctionScaleY, TOLERANCE)
        assertEquals(0f, render.negativeDisplayRotationDegrees, TOLERANCE)
    }

    @Test
    fun textureViewRender_sensor180NaturalAxesUseReferencePortraitScale() {
        val render = renderTransform(
            viewWidth = 1080f,
            viewHeight = 1920f,
            displayRotation = 0,
            sensorOrientation = 180
        )

        assertEquals(1920f / 1080f, render.correctionScaleX, TOLERANCE)
        assertEquals(1080f / 1920f, render.correctionScaleY, TOLERANCE)
        assertEquals(0f, render.negativeDisplayRotationDegrees, TOLERANCE)
    }

    @Test
    fun textureViewRender_sensor0Display90UsesReferenceLandscapeScale() {
        val render = renderTransform(
            viewWidth = 1920f,
            viewHeight = 1080f,
            displayRotation = 90,
            sensorOrientation = 0
        )

        assertEquals(1f, render.correctionScaleX, TOLERANCE)
        assertEquals(1f, render.correctionScaleY, TOLERANCE)
        assertEquals(-90f, render.negativeDisplayRotationDegrees, TOLERANCE)
    }

    @Test
    fun textureViewRender_sensor180Display270UsesReferenceLandscapeScale() {
        val render = renderTransform(
            viewWidth = 1920f,
            viewHeight = 1080f,
            displayRotation = 270,
            sensorOrientation = 180
        )

        assertEquals(1f, render.correctionScaleX, TOLERANCE)
        assertEquals(1f, render.correctionScaleY, TOLERANCE)
        assertEquals(-270f, render.negativeDisplayRotationDegrees, TOLERANCE)
    }

    @Test
    fun textureViewRender_allQuarterTurnSensorAndDisplayOrientationsStayFiniteAndPositive() {
        QUARTER_TURNS.forEach { sensorOrientation ->
            QUARTER_TURNS.forEach { displayRotation ->
                val viewWidth = if (displayRotation == 90 || displayRotation == 270) {
                    1920f
                } else {
                    1080f
                }
                val viewHeight = if (displayRotation == 90 || displayRotation == 270) {
                    1080f
                } else {
                    1920f
                }
                val render = DirectPreviewGeometry.calculateTextureViewRenderTransform(
                    viewWidth = viewWidth,
                    viewHeight = viewHeight,
                    bufferWidth = BUFFER_WIDTH,
                    bufferHeight = BUFFER_HEIGHT,
                    sensorOrientation = sensorOrientation,
                    displayRotationDegrees = displayRotation
                )

                assertTrue(render.correctionScaleX.isFinite())
                assertTrue(render.correctionScaleY.isFinite())
                assertTrue(render.correctionScaleX > 0f)
                assertTrue(render.correctionScaleY > 0f)
                assertEquals(-displayRotation.toFloat(), render.negativeDisplayRotationDegrees, 0f)
            }
        }
    }

    @Test
    fun textureViewRender_invalidDimensionsReturnNeutralTransform() {
        val render = DirectPreviewGeometry.calculateTextureViewRenderTransform(
            viewWidth = 0f,
            viewHeight = 1920f,
            bufferWidth = BUFFER_WIDTH,
            bufferHeight = BUFFER_HEIGHT,
            sensorOrientation = 90,
            displayRotationDegrees = 90
        )

        assertEquals(1f, render.correctionScaleX, 0f)
        assertEquals(1f, render.correctionScaleY, 0f)
        assertEquals(0f, render.negativeDisplayRotationDegrees, 0f)
        assertEquals(0f, render.pivotX, 0f)
        assertEquals(960f, render.pivotY, 0f)
    }

    @Test
    fun tapMapping_portraitNonCenterTapHasIndependentExpectedRawCoordinates() {
        val target = mapTap(
            tapX = 270f,
            tapY = 480f,
            viewWidth = 1080f,
            viewHeight = 1920f,
            displayRotation = 0
        )

        // Portrait contract: rawX = viewY; rawY = bufferHeight - viewX.
        assertEquals(0.25f, target.x, TOLERANCE)
        assertEquals(0.75f, target.y, TOLERANCE)
    }

    @Test
    fun tapMapping_landscape90NonCenterTapHasIndependentExpectedRawCoordinates() {
        val target = mapTap(
            tapX = 480f,
            tapY = 810f,
            viewWidth = 1920f,
            viewHeight = 1080f,
            displayRotation = 90
        )

        // Landscape 90 contract is identity for sensor orientation 90.
        assertEquals(0.25f, target.x, TOLERANCE)
        assertEquals(0.75f, target.y, TOLERANCE)
    }

    @Test
    fun tapMapping_landscape270NonCenterTapHasIndependentExpectedRawCoordinates() {
        val target = mapTap(
            tapX = 480f,
            tapY = 270f,
            viewWidth = 1920f,
            viewHeight = 1080f,
            displayRotation = 270
        )

        // Landscape 270 contract mirrors both raw axes around the visible center.
        assertEquals(0.75f, target.x, TOLERANCE)
        assertEquals(0.75f, target.y, TOLERANCE)
    }

    @Test
    fun tapMapping_centerCropOutsideCoordinatesClampOnceAndVisibleCenterStaysCentered() {
        val leftCroppedBand = mapTap(
            tapX = -500f,
            tapY = 500f,
            viewWidth = 1000f,
            viewHeight = 1000f,
            displayRotation = 90
        )
        val rightCroppedBand = mapTap(
            tapX = 1500f,
            tapY = 500f,
            viewWidth = 1000f,
            viewHeight = 1000f,
            displayRotation = 90
        )
        val visibleCenter = mapTap(
            tapX = 500f,
            tapY = 500f,
            viewWidth = 1000f,
            viewHeight = 1000f,
            displayRotation = 90
        )

        assertEquals(0f, leftCroppedBand.x, TOLERANCE)
        assertEquals(0.5f, leftCroppedBand.y, TOLERANCE)
        assertEquals(1f, rightCroppedBand.x, TOLERANCE)
        assertEquals(0.5f, rightCroppedBand.y, TOLERANCE)
        assertEquals(0.5f, visibleCenter.x, TOLERANCE)
        assertEquals(0.5f, visibleCenter.y, TOLERANCE)
    }

    @Test
    fun tapMapping_invalidDimensionsUseSafeCenterFallback() {
        val target = DirectPreviewGeometry.mapTapToNormalizedSensorTarget(
            tapX = 100f,
            tapY = 200f,
            viewWidth = 0f,
            viewHeight = 1920f,
            bufferWidth = BUFFER_WIDTH,
            bufferHeight = BUFFER_HEIGHT,
            sensorOrientation = 90,
            displayRotationDegrees = 0
        )

        assertEquals(0.5f, target.x, 0f)
        assertEquals(0.5f, target.y, 0f)
    }

    private fun renderTransform(
        viewWidth: Float,
        viewHeight: Float,
        displayRotation: Int,
        sensorOrientation: Int = 90
    ): TextureViewRenderTransform {
        return DirectPreviewGeometry.calculateTextureViewRenderTransform(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            bufferWidth = BUFFER_WIDTH,
            bufferHeight = BUFFER_HEIGHT,
            sensorOrientation = sensorOrientation,
            displayRotationDegrees = displayRotation
        )
    }

    private fun mapTap(
        tapX: Float,
        tapY: Float,
        viewWidth: Float,
        viewHeight: Float,
        displayRotation: Int
    ): FocusMeteringTarget {
        val previewTransform = DirectPreviewGeometry.createPreviewTransform(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            bufferWidth = BUFFER_WIDTH,
            bufferHeight = BUFFER_HEIGHT,
            sensorOrientation = 90,
            displayRotationDegrees = displayRotation
        )
        return previewTransform.mapTapToSensorTarget(tapX, tapY)
    }

    private data class GeometryCase(
        val viewWidth: Float,
        val viewHeight: Float,
        val sensorOrientation: Int,
        val displayRotation: Int
    )

    private companion object {
        const val BUFFER_WIDTH = 1920f
        const val BUFFER_HEIGHT = 1080f
        const val TOLERANCE = 0.001f
        val QUARTER_TURNS = listOf(0, 90, 180, 270)
    }
}
