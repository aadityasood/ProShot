package com.proshot.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests verifying preview tap coordinate mapping and rotation transformations.
 */
class PreviewTapFocusMapperTest {

    @Test
    fun mapToSensorTarget_returnsUserTapSource() {
        val target = PreviewTapFocusMapper.mapToSensorTarget(
            tapX = 100f,
            tapY = 150f,
            viewWidth = 400,
            viewHeight = 300,
            rotationDegrees = 0
        )
        assertEquals(FocusTargetSource.USER_TAP, target.source)
    }

    @Test
    fun mapToSensorTarget_centerTapMapsToHalf_forAllRotations() {
        val rotations = listOf(0, 90, 180, 270)
        for (rotation in rotations) {
            val target = PreviewTapFocusMapper.mapToSensorTarget(
                tapX = 200f,
                tapY = 150f,
                viewWidth = 400,
                viewHeight = 300,
                rotationDegrees = rotation
            )
            assertEquals("Rotation $rotation X should be 0.5", 0.5f, target.x, 1e-5f)
            assertEquals("Rotation $rotation Y should be 0.5", 0.5f, target.y, 1e-5f)
        }
    }

    @Test
    fun mapToSensorTarget_rotation0_mapsCorrectly() {
        // top-left (0, 0)
        val topLeft = PreviewTapFocusMapper.mapToSensorTarget(0f, 0f, 400, 300, 0)
        assertEquals(0.0f, topLeft.x, 1e-5f)
        assertEquals(0.0f, topLeft.y, 1e-5f)

        // bottom-right (400, 300)
        val bottomRight = PreviewTapFocusMapper.mapToSensorTarget(400f, 300f, 400, 300, 0)
        assertEquals(1.0f, bottomRight.x, 1e-5f)
        assertEquals(1.0f, bottomRight.y, 1e-5f)

        // off-center (100, 225) -> normX = 0.25, normY = 0.75
        val offCenter = PreviewTapFocusMapper.mapToSensorTarget(100f, 225f, 400, 300, 0)
        assertEquals(0.25f, offCenter.x, 1e-5f)
        assertEquals(0.75f, offCenter.y, 1e-5f)
    }

    @Test
    fun mapToSensorTarget_rotation90_mapsCorrectly() {
        // rotation 90: sensorX = normY, sensorY = 1 - normX
        // top-left (0, 0) -> normX = 0, normY = 0 -> sensorX = 0, sensorY = 1
        val topLeft = PreviewTapFocusMapper.mapToSensorTarget(0f, 0f, 400, 300, 90)
        assertEquals(0.0f, topLeft.x, 1e-5f)
        assertEquals(1.0f, topLeft.y, 1e-5f)

        // bottom-right (400, 300) -> normX = 1, normY = 1 -> sensorX = 1, sensorY = 0
        val bottomRight = PreviewTapFocusMapper.mapToSensorTarget(400f, 300f, 400, 300, 90)
        assertEquals(1.0f, bottomRight.x, 1e-5f)
        assertEquals(0.0f, bottomRight.y, 1e-5f)

        // off-center (100, 225) -> normX = 0.25, normY = 0.75 -> sensorX = 0.75, sensorY = 0.75
        val offCenter = PreviewTapFocusMapper.mapToSensorTarget(100f, 225f, 400, 300, 90)
        assertEquals(0.75f, offCenter.x, 1e-5f)
        assertEquals(0.75f, offCenter.y, 1e-5f)
    }

    @Test
    fun mapToSensorTarget_rotation180_mapsCorrectly() {
        // rotation 180: sensorX = 1 - normX, sensorY = 1 - normY
        // top-left (0, 0) -> normX = 0, normY = 0 -> sensorX = 1, sensorY = 1
        val topLeft = PreviewTapFocusMapper.mapToSensorTarget(0f, 0f, 400, 300, 180)
        assertEquals(1.0f, topLeft.x, 1e-5f)
        assertEquals(1.0f, topLeft.y, 1e-5f)

        // bottom-right (400, 300) -> normX = 1, normY = 1 -> sensorX = 0, sensorY = 0
        val bottomRight = PreviewTapFocusMapper.mapToSensorTarget(400f, 300f, 400, 300, 180)
        assertEquals(0.0f, bottomRight.x, 1e-5f)
        assertEquals(0.0f, bottomRight.y, 1e-5f)

        // off-center (100, 225) -> normX = 0.25, normY = 0.75 -> sensorX = 0.75, sensorY = 0.25
        val offCenter = PreviewTapFocusMapper.mapToSensorTarget(100f, 225f, 400, 300, 180)
        assertEquals(0.75f, offCenter.x, 1e-5f)
        assertEquals(0.25f, offCenter.y, 1e-5f)
    }

    @Test
    fun mapToSensorTarget_rotation270_mapsCorrectly() {
        // rotation 270: sensorX = 1 - normY, sensorY = normX
        // top-left (0, 0) -> normX = 0, normY = 0 -> sensorX = 1, sensorY = 0
        val topLeft = PreviewTapFocusMapper.mapToSensorTarget(0f, 0f, 400, 300, 270)
        assertEquals(1.0f, topLeft.x, 1e-5f)
        assertEquals(0.0f, topLeft.y, 1e-5f)

        // bottom-right (400, 300) -> normX = 1, normY = 1 -> sensorX = 0, sensorY = 1
        val bottomRight = PreviewTapFocusMapper.mapToSensorTarget(400f, 300f, 400, 300, 270)
        assertEquals(0.0f, bottomRight.x, 1e-5f)
        assertEquals(1.0f, bottomRight.y, 1e-5f)

        // off-center (100, 225) -> normX = 0.25, normY = 0.75 -> sensorX = 0.25, sensorY = 0.25
        val offCenter = PreviewTapFocusMapper.mapToSensorTarget(100f, 225f, 400, 300, 270)
        assertEquals(0.25f, offCenter.x, 1e-5f)
        assertEquals(0.25f, offCenter.y, 1e-5f)
    }

    @Test
    fun mapToSensorTarget_clampsOutOfBoundsTapCoordinates() {
        // tapX < 0 and tapY > viewHeight
        val target = PreviewTapFocusMapper.mapToSensorTarget(-50f, 450f, 400, 300, 0)
        assertEquals(0.0f, target.x, 1e-5f)
        assertEquals(1.0f, target.y, 1e-5f)
    }

    @Test
    fun mapToSensorTarget_throwsOnInvalidViewDimensions() {
        try {
            PreviewTapFocusMapper.mapToSensorTarget(10f, 10f, 0, 300, 0)
            fail("Should reject zero viewWidth")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        try {
            PreviewTapFocusMapper.mapToSensorTarget(10f, 10f, 400, -10, 0)
            fail("Should reject negative viewHeight")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun mapToSensorTarget_throwsOnInvalidRotationDegrees() {
        try {
            PreviewTapFocusMapper.mapToSensorTarget(10f, 10f, 400, 300, 45)
            fail("Should reject invalid rotationDegrees")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
