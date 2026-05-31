package com.proshot.app.processing.colorscience

import com.proshot.app.output.CapturedImageEncoder
import com.proshot.app.processing.style.LookProfile
import kotlin.math.roundToInt

/**
 * Pure Kotlin processor that applies look profile enhancements directly to NV21 images.
 *
 * This implementation is deterministic and has no dependencies on the Android platform,
 * making it fully testable on the JVM. It is the first safe bridge to the computational
 * photography color science pipeline.
 */
object LookProfileNv21Processor {

    /**
     * Applies the specified [LookProfile] enhancements to an [CapturedImageEncoder.Nv21Image].
     *
     * This method does not mutate the input image data. It returns a new processed image
     * with the exact same dimensions.
     *
     * Processing is done via precomputed 256-element look-up tables for both luma (tone
     * curve) and chroma (saturation scale), eliminating per-pixel floating-point math.
     *
     * @param image The source NV21 image containing oriented capture bytes.
     * @param profile The active [LookProfile] containing the tone curve and color parameters.
     * @return A new processed [CapturedImageEncoder.Nv21Image] with enhancements applied.
     * @throws IllegalArgumentException If dimensions are invalid or the buffer size is incorrect.
     */
    fun apply(
        image: CapturedImageEncoder.Nv21Image,
        profile: LookProfile
    ): CapturedImageEncoder.Nv21Image {
        val width = image.width
        val height = image.height

        require(width > 0 && height > 0) {
            "Invalid image dimensions: ${width}x${height}. Both must be positive."
        }
        require(width % 2 == 0 && height % 2 == 0) {
            "Odd dimensions (${width}x${height}) are not supported by the NV21 processing path."
        }

        // Use Long arithmetic to prevent Int overflow on pathological dimensions
        // (e.g. 65536 x 65536 wraps to 0 in Int). Camera2 never produces frames this
        // large, but the require should catch them clearly.
        val pixelCount = width.toLong() * height.toLong()
        val expectedSize = pixelCount + pixelCount / 2
        require(expectedSize <= Int.MAX_VALUE) {
            "NV21 frame dimensions ${width}x${height} exceed maximum addressable buffer size."
        }

        val expectedSizeInt = expectedSize.toInt()
        require(image.data.size == expectedSizeInt) {
            "NV21 buffer size does not match dimensions: ${image.data.size} bytes. Expected $expectedSizeInt bytes."
        }

        val inputData = image.data
        val targetData = ByteArray(expectedSizeInt)

        // 1. Build luma LUT from the tone curve (256 entries, one per possible Y value)
        val lumaLut = buildLumaLut(profile.toneCurve)

        // 2. Build chroma LUT from the saturation scale (256 entries, one per possible UV value)
        val chromaLut = buildChromaLut(profile.globalSaturationScale)

        // 3. Apply luma LUT to Y plane
        val frameSize = pixelCount.toInt()
        for (i in 0 until frameSize) {
            val yVal = inputData[i].toInt() and 0xFF
            targetData[i] = lumaLut[yVal]
        }

        // 4. Apply chroma LUT to interleaved V/U plane
        for (i in frameSize until expectedSizeInt) {
            val cVal = inputData[i].toInt() and 0xFF
            targetData[i] = chromaLut[cVal]
        }

        return CapturedImageEncoder.Nv21Image(targetData, width, height)
    }

    /**
     * Precomputes a 256-element byte LUT mapping each possible luma value [0..255]
     * through the profile's tone curve via linear interpolation.
     */
    private fun buildLumaLut(toneCurve: List<com.proshot.app.processing.style.ToneCurvePoint>): ByteArray {
        val lut = ByteArray(256)
        for (yVal in 0..255) {
            val normalizedInput = yVal / 255.0f

            // Find the active segment in the tone curve
            var left = toneCurve.first()
            var right = toneCurve.last()
            for (k in 0 until toneCurve.size - 1) {
                val p1 = toneCurve[k]
                val p2 = toneCurve[k + 1]
                if (normalizedInput >= p1.input && normalizedInput <= p2.input) {
                    left = p1
                    right = p2
                    break
                }
            }

            val t = if (right.input > left.input) {
                (normalizedInput - left.input) / (right.input - left.input)
            } else {
                0.0f
            }

            val normalizedOutput = left.output + t * (right.output - left.output)
            val outLuma = (normalizedOutput * 255.0f).roundToInt().coerceIn(0, 255)
            lut[yVal] = outLuma.toByte()
        }
        return lut
    }

    /**
     * Precomputes a 256-element byte LUT mapping each possible chroma value [0..255]
     * through the saturation scale centered around neutral 128.
     */
    private fun buildChromaLut(saturationScale: Float): ByteArray {
        val lut = ByteArray(256)
        for (cVal in 0..255) {
            val shifted = cVal - 128
            val scaled = shifted * saturationScale
            val outChroma = (scaled + 128.0f).roundToInt().coerceIn(0, 255)
            lut[cVal] = outChroma.toByte()
        }
        return lut
    }
}
