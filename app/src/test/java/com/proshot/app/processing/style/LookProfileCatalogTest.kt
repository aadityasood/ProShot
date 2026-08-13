package com.proshot.app.processing.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LookProfileCatalogTest {
    @Test
    fun bundledProfiles_shipOnlyProShotNaturalForFirstIteration() {
        val profiles = LookProfileCatalog.bundledProfiles()

        assertEquals(1, profiles.size)
        assertSame(LookProfileCatalog.ProShotNatural, profiles.single())
        assertSame(LookProfileCatalog.ProShotNatural, LookProfileCatalog.defaultProfile())
    }

    @Test
    fun proShotNaturalProfile_hasStableIdAndFindsById() {
        val profile = LookProfileCatalog.ProShotNatural

        assertEquals("proshot-natural", profile.id)
        assertEquals("ProShot Natural", profile.displayName)
        assertSame(profile, LookProfileCatalog.findById("proshot-natural"))
        assertNull(LookProfileCatalog.findById("clear-hdr"))
    }

    @Test
    fun proShotNaturalProfile_usesNormalizedMonotonicToneCurve() {
        val curve = LookProfileCatalog.ProShotNatural.toneCurve

        assertEquals(0.0f, curve.first().input, FLOAT_TOLERANCE)
        assertEquals(1.0f, curve.last().input, FLOAT_TOLERANCE)
        curve.zipWithNext().forEach { (left, right) ->
            assertTrue(left.input < right.input)
            // Non-strict (<=): flat clip segments are permitted for outputs,
            // but all inputs must be strictly ordered. The LookProfile init
            // block enforces the same contract.
            assertTrue(left.output <= right.output)
        }
    }

    @Test
    fun proShotNaturalProfile_hasExplicitTuningForEverySemanticRegion() {
        val profile = LookProfileCatalog.ProShotNatural

        SemanticRegion.entries.forEach { region ->
            assertNotNull("Missing tuning for $region", profile.regionalTunings[region])
        }
        assertEquals(
            0.0f,
            profile.regionalTunings.getValue(SemanticRegion.SKIN).sharpeningAmount,
            FLOAT_TOLERANCE
        )
        assertTrue(
            profile.regionalTunings.getValue(SemanticRegion.SKY).saturationScale >
                profile.regionalTunings.getValue(SemanticRegion.SKIN).saturationScale
        )
    }

    @Test
    fun bundledProfiles_allHaveDistinctIds() {
        val profiles = LookProfileCatalog.bundledProfiles()
        val ids = profiles.map { it.id }

        assertEquals(
            "Duplicate profile IDs detected",
            ids.size,
            ids.toSet().size
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun lookProfile_rejectsNonMonotoneToneCurveOutputs() {
        LookProfile(
            id = "test-non-monotone",
            displayName = "Non-Monotone Test",
            toneCurve = listOf(
                ToneCurvePoint(0.0f, 0.02f),
                ToneCurvePoint(0.5f, 0.8f),
                ToneCurvePoint(0.75f, 0.6f),  // output decreases: invalid
                ToneCurvePoint(1.0f, 0.98f)
            ),
            globalWarmthShiftKelvin = 0,
            globalSaturationScale = 1.0f,
            skinHueLockEnabled = false,
            skinSaturationClamp = 0.10f,
            faceTargetLuminance = 0.55f..0.65f,
            regionalTunings = defaultRegionalTunings()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun regionTuning_rejectsOutOfRangeExposureLift() {
        RegionTuning(
            exposureLift = 5.0f,  // way out of [-1, 1] range
            warmthShiftKelvin = 0,
            saturationScale = 1.0f,
            sharpeningAmount = 0.0f,
            noiseReductionStrength = 0.0f
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun regionTuning_rejectsOutOfRangeWarmthShift() {
        RegionTuning(
            exposureLift = 0.0f,
            warmthShiftKelvin = 50_000,  // way out of [-10000, 10000] range
            saturationScale = 1.0f,
            sharpeningAmount = 0.0f,
            noiseReductionStrength = 0.0f
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun lookProfile_rejectsOutOfRangeGlobalWarmthShift() {
        LookProfile(
            id = "test-warmth",
            displayName = "Warmth Test",
            toneCurve = listOf(
                ToneCurvePoint(0.0f, 0.0f),
                ToneCurvePoint(1.0f, 1.0f)
            ),
            globalWarmthShiftKelvin = 50_000,  // way out of [-10000, 10000] range
            globalSaturationScale = 1.0f,
            skinHueLockEnabled = false,
            skinSaturationClamp = 0.10f,
            faceTargetLuminance = 0.55f..0.65f,
            regionalTunings = defaultRegionalTunings()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun lookProfile_rejectsBlankDisplayName() {
        LookProfile(
            id = "test-blank",
            displayName = "   ",
            toneCurve = listOf(
                ToneCurvePoint(0.0f, 0.0f),
                ToneCurvePoint(1.0f, 1.0f)
            ),
            globalWarmthShiftKelvin = 0,
            globalSaturationScale = 1.0f,
            skinHueLockEnabled = false,
            skinSaturationClamp = 0.10f,
            faceTargetLuminance = 0.55f..0.65f,
            regionalTunings = defaultRegionalTunings()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun lookProfile_rejectsInvertedFaceLuminanceRange() {
        LookProfile(
            id = "test-inverted",
            displayName = "Inverted Test",
            toneCurve = listOf(
                ToneCurvePoint(0.0f, 0.0f),
                ToneCurvePoint(1.0f, 1.0f)
            ),
            globalWarmthShiftKelvin = 0,
            globalSaturationScale = 1.0f,
            skinHueLockEnabled = false,
            skinSaturationClamp = 0.10f,
            faceTargetLuminance = 0.65f..0.55f,  // inverted
            regionalTunings = defaultRegionalTunings()
        )
    }

    /**
     * Helper providing neutral tunings for all semantic regions so profile
     * constructor validation tests can focus on the property under test.
     */
    private fun defaultRegionalTunings(): Map<SemanticRegion, RegionTuning> =
        SemanticRegion.entries.associateWith {
            RegionTuning(
                exposureLift = 0.0f,
                warmthShiftKelvin = 0,
                saturationScale = 1.0f,
                sharpeningAmount = 0.0f,
                noiseReductionStrength = 0.0f
            )
        }

    private companion object {
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
