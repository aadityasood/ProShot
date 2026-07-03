package com.proshot.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests verifying focus/metering target mapping, coordinates, clamping, and digital zoom crop region logic.
 */
class FocusMeteringTargetTest {

    @Test
    fun center_returnsCenteredCoordinatesWithDistinctSizes() {
        val center = FocusMeteringTarget.center()
        assertEquals(0.5f, center.x, 1e-5f)
        assertEquals(0.5f, center.y, 1e-5f)
        assertEquals(0.04f, center.afSize, 1e-5f)
        assertEquals(0.10f, center.aeSize, 1e-5f)
        assertEquals(1000, center.afWeight)
        assertEquals(1000, center.aeWeight)
        assertEquals(FocusTargetSource.DEFAULT_CENTER, center.source)
    }

    @Test
    fun defaultCenterTarget_afRegionIsSmallerThanAeRegion() {
        val center = FocusMeteringTarget.center()
        assertTrue(center.afSize < center.aeSize)
    }

    @Test
    fun bothRegions_remainNonDegenerate() {
        val center = FocusMeteringTarget.center()
        assertTrue(center.afSize > 0.0f)
        assertTrue(center.aeSize > 0.0f)
    }

    @Test
    fun invalidCoordinatesAndSizesAndWeights_areRejected() {
        // test x out of range
        try {
            FocusMeteringTarget(x = -0.1f, y = 0.5f)
            fail("Should reject x < 0")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        try {
            FocusMeteringTarget(x = 1.1f, y = 0.5f)
            fail("Should reject x > 1")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        // test y out of range
        try {
            FocusMeteringTarget(x = 0.5f, y = -0.1f)
            fail("Should reject y < 0")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        // test negative/zero afSize
        try {
            FocusMeteringTarget(x = 0.5f, y = 0.5f, afSize = 0.0f)
            fail("Should reject zero AF size")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        // test negative/zero aeSize
        try {
            FocusMeteringTarget(x = 0.5f, y = 0.5f, aeSize = -0.05f)
            fail("Should reject negative AE size")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        // test weight out of range
        try {
            FocusMeteringTarget(x = 0.5f, y = 0.5f, afWeight = -1)
            fail("Should reject negative weight")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        try {
            FocusMeteringTarget(x = 0.5f, y = 0.5f, aeWeight = 1001)
            fail("Should reject weight > 1000")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun mapToActiveArray_centerTargetMapping_withoutCropRegion() {
        val target = FocusMeteringTarget(x = 0.5f, y = 0.5f)
        val activeArray = PureRect(left = 0, top = 0, right = 4000, bottom = 3000)

        // Using AE size (0.10)
        val mappedAe = FocusMeteringCoordinateMapper.mapToActiveArray(target, target.aeSize, activeArray)

        // Center is (2000, 1500)
        // Width is 10% of 4000 = 400 -> halfWidth is 200
        // Height is 10% of 3000 = 300 -> halfHeight is 150
        // Expected: left = 2000 - 200 = 1800, right = 2000 + 200 = 2200
        // Expected: top = 1500 - 150 = 1350, bottom = 1500 + 150 = 1650
        assertEquals(1800, mappedAe.left)
        assertEquals(1350, mappedAe.top)
        assertEquals(2200, mappedAe.right)
        assertEquals(1650, mappedAe.bottom)
    }

    @Test
    fun mapToActiveArray_defaultCenterAFvsAE_on4000x3000ActiveArray() {
        val target = FocusMeteringTarget.center()
        val activeArray = PureRect(left = 0, top = 0, right = 4000, bottom = 3000)

        // AF Region Mapping
        val mappedAf = FocusMeteringCoordinateMapper.mapToActiveArray(target, target.afSize, activeArray)
        val afWidth = mappedAf.right - mappedAf.left
        val afHeight = mappedAf.bottom - mappedAf.top
        assertEquals(160, afWidth)
        assertEquals(120, afHeight)
        assertEquals(1920, mappedAf.left)
        assertEquals(1440, mappedAf.top)
        assertEquals(2080, mappedAf.right)
        assertEquals(1560, mappedAf.bottom)

        // AE Region Mapping
        val mappedAe = FocusMeteringCoordinateMapper.mapToActiveArray(target, target.aeSize, activeArray)
        val aeWidth = mappedAe.right - mappedAe.left
        val aeHeight = mappedAe.bottom - mappedAe.top
        assertEquals(400, aeWidth)
        assertEquals(300, aeHeight)
        assertEquals(1800, mappedAe.left)
        assertEquals(1350, mappedAe.top)
        assertEquals(2200, mappedAe.right)
        assertEquals(1650, mappedAe.bottom)
    }

    @Test
    fun mapToActiveArray_tinySizeRemainsNonDegenerate() {
        val target = FocusMeteringTarget.center()
        val activeArray = PureRect(left = 0, top = 0, right = 4000, bottom = 3000)

        val mapped = FocusMeteringCoordinateMapper.mapToActiveArray(target, 0.00001f, activeArray)

        assertTrue((mapped.right - mapped.left) >= 1)
        assertTrue((mapped.bottom - mapped.top) >= 1)
    }

    @Test
    fun mapToActiveArray_nonZeroOriginActiveArrayMapsAroundSensorCenter() {
        val target = FocusMeteringTarget.center()
        val activeArray = PureRect(left = 100, top = 200, right = 4100, bottom = 3200)

        val mapped = FocusMeteringCoordinateMapper.mapToActiveArray(target, target.aeSize, activeArray)

        assertEquals(1900, mapped.left)
        assertEquals(1550, mapped.top)
        assertEquals(2300, mapped.right)
        assertEquals(1850, mapped.bottom)
    }

    @Test
    fun mapToActiveArray_malformedCropRegionStillClampsInsideActiveArray() {
        val target = FocusMeteringTarget.center()
        val activeArray = PureRect(left = 0, top = 0, right = 100, bottom = 100)
        val malformedCrop = PureRect(left = 80, top = 80, right = 20, bottom = 20)

        val mapped = FocusMeteringCoordinateMapper.mapToActiveArray(
            target = target,
            size = target.aeSize,
            activeArray = activeArray,
            cropRegion = malformedCrop
        )

        assertTrue(mapped.left in activeArray.left..activeArray.right)
        assertTrue(mapped.right in activeArray.left..activeArray.right)
        assertTrue(mapped.top in activeArray.top..activeArray.bottom)
        assertTrue(mapped.bottom in activeArray.top..activeArray.bottom)
        assertTrue((mapped.right - mapped.left) >= 1)
        assertTrue((mapped.bottom - mapped.top) >= 1)
    }

    @Test
    fun mapToActiveArray_invalidExplicitSizeIsRejected() {
        val target = FocusMeteringTarget.center()
        val activeArray = PureRect(left = 0, top = 0, right = 4000, bottom = 3000)

        try {
            FocusMeteringCoordinateMapper.mapToActiveArray(target, 0.0f, activeArray)
            fail("Should reject zero mapping size")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun mapToActiveArray_clampingNearEdges() {
        val activeArray = PureRect(left = 0, top = 0, right = 4000, bottom = 3000)

        // Target at top-left corner (0.0, 0.0) with custom size 0.20f
        val topLeftTarget = FocusMeteringTarget(x = 0.0f, y = 0.0f)
        val mappedTopLeft = FocusMeteringCoordinateMapper.mapToActiveArray(topLeftTarget, 0.20f, activeArray)

        // Center is (0, 0)
        // Half-width is 400, half-height is 300
        // Bounded by [0, 4000] and [0, 3000]
        assertEquals(0, mappedTopLeft.left)
        assertEquals(0, mappedTopLeft.top)
        assertEquals(400, mappedTopLeft.right)
        assertEquals(300, mappedTopLeft.bottom)

        // Target at bottom-right corner (1.0, 1.0) with custom size 0.20f
        val bottomRightTarget = FocusMeteringTarget(x = 1.0f, y = 1.0f)
        val mappedBottomRight = FocusMeteringCoordinateMapper.mapToActiveArray(bottomRightTarget, 0.20f, activeArray)

        // Center is (4000, 3000)
        // Half-width is 400, half-height is 300
        assertEquals(3600, mappedBottomRight.left)
        assertEquals(2700, mappedBottomRight.top)
        assertEquals(4000, mappedBottomRight.right)
        assertEquals(3000, mappedBottomRight.bottom)
    }

    @Test
    fun mapToActiveArray_cropRegionMapping_whenDigitalZoomIsActive() {
        val activeArray = PureRect(left = 0, top = 0, right = 4000, bottom = 3000)
        // Crop region is zoomed in center (2x zoom)
        val cropRegion = PureRect(left = 1000, top = 750, right = 3000, bottom = 2250)
        // Center of crop region is (2000, 1500)

        // Target at center (0.5, 0.5)
        val target = FocusMeteringTarget(x = 0.5f, y = 0.5f)
        val mapped = FocusMeteringCoordinateMapper.mapToActiveArray(target, target.aeSize, activeArray, cropRegion)

        // Crop region width = 2000, height = 1500
        // Center should be: 1000 + 0.5 * 2000 = 2000, 750 + 0.5 * 1500 = 1500
        // Half-width relative to crop is: (0.10 * 2000) / 2 = 100
        // Half-height relative to crop is: (0.10 * 1500) / 2 = 75
        // Expected: left = 2000 - 100 = 1900, right = 2000 + 100 = 2100
        // Expected: top = 1500 - 75 = 1425, bottom = 1500 + 75 = 1575
        assertEquals(1900, mapped.left)
        assertEquals(1425, mapped.top)
        assertEquals(2100, mapped.right)
        assertEquals(1575, mapped.bottom)
    }

    @Test
    fun afRegionIsContainedWithinAeRegionForCenterTarget() {
        val target = FocusMeteringTarget.center()
        val activeArray = PureRect(left = 0, top = 0, right = 4000, bottom = 3000)
        val af = FocusMeteringCoordinateMapper.mapToActiveArray(target, target.afSize, activeArray)
        val ae = FocusMeteringCoordinateMapper.mapToActiveArray(target, target.aeSize, activeArray)
        assertTrue(af.left >= ae.left)
        assertTrue(af.top >= ae.top)
        assertTrue(af.right <= ae.right)
        assertTrue(af.bottom <= ae.bottom)
    }

    @Test
    fun constructor_rejectsZeroAeSize() {
        try {
            FocusMeteringTarget(x = 0.5f, y = 0.5f, aeSize = 0.0f)
            fail("Should reject zero AE size")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun tap_preservesT11Defaults() {
        val target = FocusMeteringTarget.tap(0.3f, 0.7f)
        assertEquals(0.3f, target.x, 1e-5f)
        assertEquals(0.7f, target.y, 1e-5f)
        assertEquals(0.04f, target.afSize, 1e-5f)
        assertEquals(0.10f, target.aeSize, 1e-5f)
        assertEquals(1000, target.afWeight)
        assertEquals(1000, target.aeWeight)
        assertEquals(FocusTargetSource.USER_TAP, target.source)
    }

    @Test
    fun mapToActiveArray_offCenterTapTarget_onRealisticSensorDimensions() {
        val activeArray = PureRect(left = 0, top = 0, right = 4056, bottom = 3040)
        val target = FocusMeteringTarget.tap(0.25f, 0.75f)

        val mappedAf = FocusMeteringCoordinateMapper.mapToActiveArray(target, target.afSize, activeArray)
        // centerX = 0.25 * 4056 = 1014, centerY = 0.75 * 3040 = 2280
        // regionWidth = max(0.04 * 4056, 1) = 162.24, halfWidth = 81.12
        // regionHeight = max(0.04 * 3040, 1) = 121.6, halfHeight = 60.8
        assertTrue(mappedAf.left < mappedAf.right)
        assertTrue(mappedAf.top < mappedAf.bottom)
        assertTrue(mappedAf.left in activeArray.left..activeArray.right)
        assertTrue(mappedAf.right in activeArray.left..activeArray.right)
        assertTrue(mappedAf.top in activeArray.top..activeArray.bottom)
        assertTrue(mappedAf.bottom in activeArray.top..activeArray.bottom)

        val mappedAe = FocusMeteringCoordinateMapper.mapToActiveArray(target, target.aeSize, activeArray)
        assertTrue(mappedAe.left < mappedAe.right)
        assertTrue(mappedAe.top < mappedAe.bottom)
        // AF region should be contained within AE region for the same target
        assertTrue(mappedAf.left >= mappedAe.left)
        assertTrue(mappedAf.top >= mappedAe.top)
        assertTrue(mappedAf.right <= mappedAe.right)
        assertTrue(mappedAf.bottom <= mappedAe.bottom)
    }

    @Test
    fun mapToActiveArray_floatMinValueSizeRemainsNonDegenerate() {
        val target = FocusMeteringTarget.center()
        val activeArray = PureRect(left = 0, top = 0, right = 4000, bottom = 3000)

        val mapped = FocusMeteringCoordinateMapper.mapToActiveArray(target, Float.MIN_VALUE, activeArray)

        // coerceAtLeast(1f) ensures minimum 1px width and height
        assertTrue((mapped.right - mapped.left) >= 1)
        assertTrue((mapped.bottom - mapped.top) >= 1)
    }
}
