package com.proshot.app.processing.style

/**
 * Catalog of processing looks available to the app.
 *
 * This is a Kotlin `object` singleton appropriate for v1 (one bundled profile,
 * no user selection). When user-selected profiles are introduced, callers should
 * resolve profiles via [findById] using a preference-persisted ID rather than
 * calling [defaultProfile] directly. At that point, consider wrapping this
 * singleton behind an injectable provider so per-session overrides are possible.
 */
object LookProfileCatalog {
    val ProShotNatural = LookProfile(
        id = "proshot-natural",
        displayName = "ProShot Natural",
        toneCurve = listOf(
            ToneCurvePoint(0.0f, 5.0f / BYTE_MAX),
            ToneCurvePoint(32.0f / BYTE_MAX, 38.0f / BYTE_MAX),
            ToneCurvePoint(64.0f / BYTE_MAX, 72.0f / BYTE_MAX),
            ToneCurvePoint(128.0f / BYTE_MAX, 132.0f / BYTE_MAX),
            ToneCurvePoint(192.0f / BYTE_MAX, 188.0f / BYTE_MAX),
            ToneCurvePoint(224.0f / BYTE_MAX, 218.0f / BYTE_MAX),
            ToneCurvePoint(1.0f, 250.0f / BYTE_MAX)
        ),
        globalWarmthShiftKelvin = 4,
        globalSaturationScale = 0.95f,
        skinHueLockEnabled = true,
        skinSaturationClamp = 0.10f,
        faceTargetLuminance = 0.55f..0.65f,
        regionalTunings = mapOf(
            SemanticRegion.FACE to RegionTuning(
                exposureLift = 0.25f,
                warmthShiftKelvin = 8,
                saturationScale = 0.90f,
                sharpeningAmount = 0.0f,
                noiseReductionStrength = 0.70f
            ),
            SemanticRegion.SKIN to RegionTuning(
                exposureLift = 0.20f,
                warmthShiftKelvin = 8,
                saturationScale = 0.90f,
                sharpeningAmount = 0.0f,
                noiseReductionStrength = 0.75f
            ),
            SemanticRegion.SKY to RegionTuning(
                exposureLift = -0.05f,
                warmthShiftKelvin = 0,
                saturationScale = 1.15f,
                sharpeningAmount = 0.35f,
                noiseReductionStrength = 0.35f
            ),
            SemanticRegion.PERSON to RegionTuning(
                exposureLift = 0.10f,
                warmthShiftKelvin = 3,
                saturationScale = 0.95f,
                sharpeningAmount = 0.10f,
                noiseReductionStrength = 0.55f
            ),
            SemanticRegion.BACKGROUND to RegionTuning(
                exposureLift = 0.0f,
                warmthShiftKelvin = 3,
                saturationScale = 0.95f,
                sharpeningAmount = 0.30f,
                noiseReductionStrength = 0.45f
            )
        )
    )

    /**
     * Returns the v1 default profile (ProShot Natural).
     *
     * **Do not call from UI or session code.** Callers that need the
     * user-selected profile should read the profile ID from preferences and
     * resolve it via [findById]. This function exists for
     * [CompatibilityPolicy][com.proshot.app.camera.compat.CompatibilityPolicy]
     * default parameter initialization only.
     */
    fun defaultProfile(): LookProfile = ProShotNatural

    /**
     * Returns every profile bundled with this app version.
     */
    fun bundledProfiles(): List<LookProfile> = listOf(ProShotNatural)

    /**
     * Finds a bundled profile by its stable identifier.
     */
    fun findById(id: String): LookProfile? = bundledProfiles().firstOrNull { it.id == id }

    private const val BYTE_MAX = 255.0f
}
