package com.proshot.app.processing.colorscience

import com.proshot.app.output.CapturedImageEncoder
import com.proshot.app.processing.style.LookProfile
import com.proshot.app.processing.style.LookProfileCatalog
import com.proshot.app.processing.style.ToneCurvePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests verifying look profile post-processing correctness
 * inside [LookProfileNv21Processor].
 */
class LookProfileNv21ProcessorTest {

    // A minimal helper profile for deterministic tone and color testing
    private val testProfile = LookProfile(
        id = "test-profile",
        displayName = "Test Profile",
        toneCurve = listOf(
            ToneCurvePoint(0.0f, 10.0f / 255.0f),   // blacks lift
            ToneCurvePoint(0.5f, 130.0f / 255.0f),  // midtone boost
            ToneCurvePoint(1.0f, 250.0f / 255.0f)   // highlights rolloff
        ),
        globalWarmthShiftKelvin = 0,
        globalSaturationScale = 1.0f,
        skinHueLockEnabled = false,
        skinSaturationClamp = 0.0f,
        faceTargetLuminance = 0.5f..0.6f,
        regionalTunings = LookProfileCatalog.ProShotNatural.regionalTunings
    )

    @Test
    fun apply_doesNotMutateInputArray() {
        val width = 4
        val height = 4
        val size = width * height + (width * height) / 2
        val inputData = ByteArray(size) { (it * 3).toByte() }
        val originalCopy = inputData.clone()

        val image = CapturedImageEncoder.Nv21Image(inputData, width, height)
        val result = LookProfileNv21Processor.apply(image, testProfile)

        // Ensure the input array's data remains untouched
        assertArrayEquals("Input byte array must not be mutated by downstream processing", originalCopy, inputData)
        // Ensure that a new array is returned
        assertNotSame("Output image must wrap a newly allocated array", inputData, result.data)
    }

    @Test
    fun apply_preservesDimensions() {
        val width = 4
        val height = 4
        val size = width * height + (width * height) / 2
        val image = CapturedImageEncoder.Nv21Image(ByteArray(size), width, height)

        val result = LookProfileNv21Processor.apply(image, testProfile)

        assertEquals("Image width must be preserved", width, result.width)
        assertEquals("Image height must be preserved", height, result.height)
    }

    @Test
    fun apply_appliesToneCurveAndInterpolatesCorrectly() {
        val width = 4
        val height = 4
        val size = width * height + (width * height) / 2
        val inputData = ByteArray(size)

        // Set specific luma (Y) inputs
        inputData[0] = 0.toByte()     // normalized 0.0 -> expected output: 10
        inputData[1] = 128.toByte()   // normalized 0.50196 -> expected output: interpolated between 130 and 250
        inputData[2] = 255.toByte()   // normalized 1.0 -> expected output: 250

        val image = CapturedImageEncoder.Nv21Image(inputData, width, height)
        val result = LookProfileNv21Processor.apply(image, testProfile)

        // Y[0] -> input 0.0 should map exactly to 10
        assertEquals(10.toByte(), result.data[0])

        // Y[2] -> input 1.0 should map exactly to 250
        assertEquals(250.toByte(), result.data[2])

        // Y[1] -> input 128 (approx 0.50196) should be slightly above 130
        val midtoneVal = result.data[1].toInt() and 0xFF
        assertTrue("Midtone boost of 128 must be in shadow/highlight interpolation range", midtoneVal >= 130)
        assertTrue("Midtone boost of 128 must be in shadow/highlight interpolation range", midtoneVal < 140)
    }

    @Test
    fun apply_outputLumaIsStrictlyMonotonic() {
        val width = 4
        val height = 4
        val size = width * height + (width * height) / 2
        val inputData = ByteArray(size)

        // Fill luma with strictly increasing Y values
        for (i in 0 until (width * height)) {
            inputData[i] = (i * 10).toByte()
        }

        val image = CapturedImageEncoder.Nv21Image(inputData, width, height)
        val result = LookProfileNv21Processor.apply(image, testProfile)

        // Verify monotonic outputs
        for (i in 0 until (width * height - 1)) {
            val current = result.data[i].toInt() and 0xFF
            val next = result.data[i + 1].toInt() and 0xFF
            assertTrue(
                "Output luma must be monotonic (non-decreasing). Index $i: $current, Index ${i+1}: $next",
                current <= next
            )
        }
    }

    @Test
    fun apply_appliesGlobalSaturationScale() {
        val width = 4
        val height = 4
        val frameSize = width * height
        val size = frameSize + (width * height) / 2

        val saturateProfile = testProfile.copy(globalSaturationScale = 1.2f)
        val desaturateProfile = testProfile.copy(globalSaturationScale = 0.8f)

        val inputData = ByteArray(size)
        // Chroma starts at index 16
        inputData[frameSize] = 150.toByte()     // delta = 22 above neutral 128
        inputData[frameSize + 1] = 100.toByte() // delta = -28 below neutral 128

        val image = CapturedImageEncoder.Nv21Image(inputData, width, height)

        // 1. Saturation scale > 1.0 (Color boost)
        val satResult = LookProfileNv21Processor.apply(image, saturateProfile)
        // expected chroma 1: 128 + (22 * 1.2) = 128 + 26.4 = 154
        assertEquals(154.toByte(), satResult.data[frameSize])
        // expected chroma 2: 128 - (28 * 1.2) = 128 - 33.6 = 94
        assertEquals(94.toByte(), satResult.data[frameSize + 1])

        // 2. Saturation scale < 1.0 (Color compression)
        val desatResult = LookProfileNv21Processor.apply(image, desaturateProfile)
        // expected chroma 1: 128 + (22 * 0.8) = 128 + 17.6 = 146
        assertEquals(146.toByte(), desatResult.data[frameSize])
        // expected chroma 2: 128 - (28 * 0.8) = 128 - 22.4 = 106
        assertEquals(106.toByte(), desatResult.data[frameSize + 1])
    }

    @Test
    fun apply_clampingSafelyCoercesOutOfRangeValues() {
        val width = 4
        val height = 4
        val frameSize = width * height
        val size = frameSize + (width * height) / 2

        val highSatProfile = testProfile.copy(globalSaturationScale = 2.0f)
        val inputData = ByteArray(size)

        // Setup boundary chroma conditions
        inputData[frameSize] = 200.toByte() // delta = 72. 72 * 2.0 = 144. 128 + 144 = 272 (>255)
        inputData[frameSize + 1] = 40.toByte() // delta = -88. -88 * 2.0 = -176. 128 - 176 = -48 (<0)

        val image = CapturedImageEncoder.Nv21Image(inputData, width, height)
        val result = LookProfileNv21Processor.apply(image, highSatProfile)

        // Ensure proper boundary clamps are enforced
        assertEquals(255.toByte(), result.data[frameSize])
        assertEquals(0.toByte(), result.data[frameSize + 1])
    }

    // ===== New tests added during T05 synthesis =====

    /**
     * Integration test: verifies the shipped [LookProfileCatalog.ProShotNatural]
     * profile processes a small NV21 frame without errors and produces the
     * expected black lift, highlight rolloff, and mild desaturation.
     */
    @Test
    fun apply_withProShotNaturalProfile_processesSuccessfullyAndMatchesExpectedBehavior() {
        val width = 4
        val height = 4
        val frameSize = width * height
        val size = frameSize + frameSize / 2
        val inputData = ByteArray(size)

        // Set luma test values: black, shadow, midtone, highlight, white
        inputData[0] = 0.toByte()       // pure black
        inputData[1] = 32.toByte()      // shadow
        inputData[2] = 128.toByte()     // midtone
        inputData[3] = 224.toByte()     // highlight
        inputData[4] = 255.toByte()     // pure white

        // Set a chroma test value with known delta from neutral
        inputData[frameSize] = 150.toByte()  // delta = +22 from 128

        val image = CapturedImageEncoder.Nv21Image(inputData, width, height)
        val profile = LookProfileCatalog.ProShotNatural
        val result = LookProfileNv21Processor.apply(image, profile)

        // ProShot Natural: black lift -> output(0) = 5/255, about 5
        val blackOut = result.data[0].toInt() and 0xFF
        assertEquals("Black should lift to 5 with ProShot Natural tone curve", 5, blackOut)

        // ProShot Natural: shadow(32) -> output = 38/255 scaled, about 38
        val shadowOut = result.data[1].toInt() and 0xFF
        assertEquals("Shadow 32 should lift to 38", 38, shadowOut)

        // ProShot Natural: highlight(224) -> output = 218/255 scaled, about 218
        val highlightOut = result.data[3].toInt() and 0xFF
        assertEquals("Highlight 224 should roll off to 218", 218, highlightOut)

        // ProShot Natural: white(255) -> output = 250/255 scaled, about 250
        val whiteOut = result.data[4].toInt() and 0xFF
        assertEquals("White 255 should soft clip to 250", 250, whiteOut)

        // ProShot Natural: globalSaturationScale = 0.95
        // chroma 150: (150-128)*0.95 + 128 = 22*0.95 + 128 = 20.9 + 128 = 148.9 -> 149
        val chromaOut = result.data[frameSize].toInt() and 0xFF
        assertEquals("Chroma 150 with 0.95 saturation should desaturate slightly", 149, chromaOut)
    }

    /**
     * Verifies neutral chroma value 128 remains exactly 128 across various
     * saturation scales. Neutral gray must not shift hue/saturation.
     */
    @Test
    fun apply_neutralChroma128_remainsUnchangedAcrossSaturationScales() {
        val width = 4
        val height = 4
        val frameSize = width * height
        val size = frameSize + frameSize / 2
        val inputData = ByteArray(size)

        // Fill all chroma with neutral 128
        for (i in frameSize until size) {
            inputData[i] = 128.toByte()
        }

        val image = CapturedImageEncoder.Nv21Image(inputData, width, height)

        // Test with several saturation scales
        val scales = listOf(0.0f, 0.5f, 0.95f, 1.0f, 1.5f, 2.0f)
        for (scale in scales) {
            val profile = testProfile.copy(globalSaturationScale = scale)
            val result = LookProfileNv21Processor.apply(image, profile)

            for (i in frameSize until size) {
                val outChroma = result.data[i].toInt() and 0xFF
                assertEquals(
                    "Neutral chroma 128 must remain 128 at saturation scale $scale (index $i)",
                    128,
                    outChroma
                )
            }
        }
    }

    /**
     * Full 0..255 luma domain monotonicity test with ProShotNatural.
     * Uses a 16 x 16 frame (256 pixels) to map every possible Y value.
     */
    @Test
    fun apply_fullLumaDomainMonotonicWithProShotNatural() {
        val width = 16
        val height = 16
        val frameSize = width * height  // 256
        val size = frameSize + frameSize / 2  // 384
        val inputData = ByteArray(size)

        // Fill luma 0..255
        for (i in 0 until frameSize) {
            inputData[i] = i.toByte()
        }

        val image = CapturedImageEncoder.Nv21Image(inputData, width, height)
        val result = LookProfileNv21Processor.apply(image, LookProfileCatalog.ProShotNatural)

        for (i in 0 until frameSize - 1) {
            val current = result.data[i].toInt() and 0xFF
            val next = result.data[i + 1].toInt() and 0xFF
            assertTrue(
                "Full-domain ProShotNatural luma must be monotonic. Y[$i]=$current > Y[${i + 1}]=$next",
                current <= next
            )
        }

        // Also verify black lift and white rolloff
        val blackOut = result.data[0].toInt() and 0xFF
        assertTrue("ProShotNatural must lift blacks above 0", blackOut > 0)
        val whiteOut = result.data[255].toInt() and 0xFF
        assertTrue("ProShotNatural must roll off whites below 255", whiteOut < 255)
    }

    /**
     * Exercises signed byte boundary values (0, 127, 128, 255) for chroma
     * to ensure unsigned byte handling is correct across the entire range.
     */
    @Test
    fun apply_chromaSignedByteBoundaryValues_handledCorrectly() {
        val width = 4
        val height = 4
        val frameSize = width * height
        val size = frameSize + frameSize / 2
        val inputData = ByteArray(size)

        // Set boundary chroma values at known positions
        inputData[frameSize + 0] = 0.toByte()     // min unsigned byte (most negative signed)
        inputData[frameSize + 1] = 127.toByte()   // max positive signed byte
        inputData[frameSize + 2] = 128.toByte()   // neutral (min negative signed)
        inputData[frameSize + 3] = 255.toByte()   // max unsigned byte (-1 as signed)

        val profile = testProfile.copy(globalSaturationScale = 1.0f)
        val image = CapturedImageEncoder.Nv21Image(inputData, width, height)
        val result = LookProfileNv21Processor.apply(image, profile)

        // At scale 1.0, output should equal input for chroma
        assertEquals("Chroma 0 at scale 1.0", 0.toByte(), result.data[frameSize + 0])
        assertEquals("Chroma 127 at scale 1.0", 127.toByte(), result.data[frameSize + 1])
        assertEquals("Chroma 128 at scale 1.0", 128.toByte(), result.data[frameSize + 2])
        assertEquals("Chroma 255 at scale 1.0", 255.toByte(), result.data[frameSize + 3])
    }

    /**
     * Identity profile: flat tone curve + saturation 1.0 should produce
     * output identical to input.
     */
    @Test
    fun apply_withIdentityProfile_outputMatchesInput() {
        val identityProfile = LookProfile(
            id = "identity-test",
            displayName = "Identity Test",
            toneCurve = listOf(
                ToneCurvePoint(0.0f, 0.0f),
                ToneCurvePoint(1.0f, 1.0f)
            ),
            globalWarmthShiftKelvin = 0,
            globalSaturationScale = 1.0f,
            skinHueLockEnabled = false,
            skinSaturationClamp = 0.0f,
            faceTargetLuminance = 0.5f..0.6f,
            regionalTunings = LookProfileCatalog.ProShotNatural.regionalTunings
        )

        val width = 4
        val height = 4
        val size = width * height + (width * height) / 2
        val inputData = ByteArray(size) { it.toByte() }
        val image = CapturedImageEncoder.Nv21Image(inputData, width, height)

        val result = LookProfileNv21Processor.apply(image, identityProfile)

        assertArrayEquals(
            "Identity profile (flat curve, saturation 1.0) must produce identical output",
            inputData,
            result.data
        )
    }

    // ===== Validation edge case tests =====

    @Test
    fun apply_throwsOnZeroDimensions() {
        try {
            val image = CapturedImageEncoder.Nv21Image(ByteArray(0), 0, 4)
            LookProfileNv21Processor.apply(image, testProfile)
            fail("Expected IllegalArgumentException for zero width")
        } catch (e: IllegalArgumentException) {
            assertEquals("Invalid image dimensions: 0x4. Both must be positive.", e.message)
        }
    }

    @Test
    fun apply_throwsOnNegativeDimensions() {
        try {
            val image = CapturedImageEncoder.Nv21Image(ByteArray(0), 4, -2)
            LookProfileNv21Processor.apply(image, testProfile)
            fail("Expected IllegalArgumentException for negative height")
        } catch (e: IllegalArgumentException) {
            assertEquals("Invalid image dimensions: 4x-2. Both must be positive.", e.message)
        }
    }

    @Test
    fun apply_throwsOnOddDimensions() {
        try {
            val image = CapturedImageEncoder.Nv21Image(ByteArray(10), 3, 4)
            LookProfileNv21Processor.apply(image, testProfile)
            fail("Expected IllegalArgumentException for odd width")
        } catch (e: IllegalArgumentException) {
            assertEquals("Odd dimensions (3x4) are not supported by the NV21 processing path.", e.message)
        }
    }

    @Test
    fun apply_throwsOnUndersizedBuffer() {
        try {
            // 4 x 4 expects 24 bytes, provide 20
            val image = CapturedImageEncoder.Nv21Image(ByteArray(20), 4, 4)
            LookProfileNv21Processor.apply(image, testProfile)
            fail("Expected IllegalArgumentException for undersized buffer")
        } catch (e: IllegalArgumentException) {
            assertEquals("NV21 buffer size does not match dimensions: 20 bytes. Expected 24 bytes.", e.message)
        }
    }

    @Test
    fun apply_throwsOnOverflowDimensions() {
        try {
            // 65536 x 65536 overflows Int (4,294,967,296 > Int.MAX_VALUE)
            val image = CapturedImageEncoder.Nv21Image(ByteArray(0), 65536, 65536)
            LookProfileNv21Processor.apply(image, testProfile)
            fail("Expected IllegalArgumentException for overflow dimensions")
        } catch (e: IllegalArgumentException) {
            assertEquals(
                "NV21 frame dimensions 65536x65536 exceed maximum addressable buffer size.",
                e.message
            )
        }
    }
}
