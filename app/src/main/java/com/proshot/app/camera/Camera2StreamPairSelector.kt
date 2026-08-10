package com.proshot.app.camera

import kotlin.math.abs

/**
 * Pure selector that resolves a common dimension between preview (SurfaceTexture)
 * and YUV_420_888 configuration maps.
 */
object Camera2StreamPairSelector {
    /**
     * Finds the dimension common to [previewSizes] and [yuvSizes] closest in area to 1920x1080.
     * Tie-breaking prefers larger width, then larger height.
     * Uses Long arithmetic to prevent Int area overflow for large sensor resolutions.
     *
     * @throws IllegalArgumentException if either list is empty or no common dimension exists.
     */
    @JvmStatic
    fun selectCommonStreamSize(
        previewSizes: List<CaptureSize>,
        yuvSizes: List<CaptureSize>
    ): CaptureSize {
        require(previewSizes.isNotEmpty()) { "Preview sizes list must not be empty" }
        require(yuvSizes.isNotEmpty()) { "YUV sizes list must not be empty" }

        val previewSet = previewSizes.toSet()
        val common = yuvSizes.filter { it in previewSet }
        require(common.isNotEmpty()) {
            "No common dimensions found between SurfaceTexture and YUV_420_888 configuration maps"
        }

        val targetArea: Long = 1920L * 1080L
        return common.minWithOrNull(
            Comparator<CaptureSize> { s1, s2 ->
                val area1: Long = s1.width.toLong() * s1.height.toLong()
                val area2: Long = s2.width.toLong() * s2.height.toLong()
                val areaDiff1 = abs(area1 - targetArea)
                val areaDiff2 = abs(area2 - targetArea)
                if (areaDiff1 != areaDiff2) {
                    areaDiff1.compareTo(areaDiff2)
                } else if (s1.width != s2.width) {
                    s2.width.compareTo(s1.width)
                } else {
                    s2.height.compareTo(s1.height)
                }
            }
        ) ?: throw IllegalStateException("Stream size selection failed")
    }
}
