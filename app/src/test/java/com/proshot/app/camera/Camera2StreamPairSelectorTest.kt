package com.proshot.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2StreamPairSelectorTest {

    @Test
    fun selectCommonStreamSize_selectsExact1080pWhenCommon() {
        val preview = listOf(CaptureSize(3840, 2160), CaptureSize(1920, 1080), CaptureSize(1280, 720))
        val yuv = listOf(CaptureSize(1920, 1080), CaptureSize(1280, 720), CaptureSize(640, 480))

        val selected = Camera2StreamPairSelector.selectCommonStreamSize(preview, yuv)

        assertEquals(CaptureSize(1920, 1080), selected)
    }

    @Test
    fun selectCommonStreamSize_handlesLargeAreaWithoutIntOverflow() {
        // High resolution sensor sizes (e.g. 50MP: 8192 x 6144) where Int area calculation would overflow
        val preview = listOf(CaptureSize(8192, 6144), CaptureSize(1920, 1080))
        val yuv = listOf(CaptureSize(8192, 6144), CaptureSize(1920, 1080))

        val selected = Camera2StreamPairSelector.selectCommonStreamSize(preview, yuv)

        assertEquals(CaptureSize(1920, 1080), selected)
    }

    @Test
    fun selectCommonStreamSize_appliesWidthAndHeightTieBreaker() {
        val preview = listOf(CaptureSize(1080, 1920), CaptureSize(1920, 1080))
        val yuv = listOf(CaptureSize(1080, 1920), CaptureSize(1920, 1080))

        val selected = Camera2StreamPairSelector.selectCommonStreamSize(preview, yuv)

        assertEquals(CaptureSize(1920, 1080), selected)
    }

    @Test
    fun selectCommonStreamSize_throwsWhenPreviewSizesEmpty() {
        var thrown = false
        try {
            Camera2StreamPairSelector.selectCommonStreamSize(emptyList(), listOf(CaptureSize(1920, 1080)))
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun selectCommonStreamSize_throwsWhenYuvSizesEmpty() {
        var thrown = false
        try {
            Camera2StreamPairSelector.selectCommonStreamSize(listOf(CaptureSize(1920, 1080)), emptyList())
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun selectCommonStreamSize_throwsWhenNoCommonSizes() {
        val preview = listOf(CaptureSize(3840, 2160))
        val yuv = listOf(CaptureSize(1920, 1080))

        var thrown = false
        try {
            Camera2StreamPairSelector.selectCommonStreamSize(preview, yuv)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
