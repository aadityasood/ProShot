package com.proshot.app.processing.style

/**
 * Semantic image regions that can receive independent processing decisions.
 */
enum class SemanticRegion {
    FACE,
    SKIN,
    SKY,
    PERSON,
    BACKGROUND
}

/**
 * A normalized tone-curve point in linear luminance space.
 */
data class ToneCurvePoint(
    val input: Float,
    val output: Float
) {
    init {
        require(input in NORMALIZED_RANGE) { "Tone curve input must be normalized." }
        require(output in NORMALIZED_RANGE) { "Tone curve output must be normalized." }
    }

    private companion object {
        val NORMALIZED_RANGE = 0.0f..1.0f
    }
}

/**
 * Per-region tuning data that core processing stages can consume.
 *
 * **Warmth unit convention:** [warmthShiftKelvin] stores the value used
 * directly by the color-science shader. The unit interpretation (true Kelvin
 * delta vs. product-relative slider unit) must be agreed upon before the
 * first shader implementation consumes this contract. See the warmth-unit
 * TODO in `ARCHITECTURE.md` and ledger entry BL-D005.
 */
data class RegionTuning(
    val exposureLift: Float,
    val warmthShiftKelvin: Int,
    val saturationScale: Float,
    val sharpeningAmount: Float,
    val noiseReductionStrength: Float
) {
    init {
        require(exposureLift in -1.0f..1.0f) { "Exposure lift must be in [-1.0, 1.0]." }
        require(warmthShiftKelvin in -10_000..10_000) { "Warmth shift must be in [-10000, 10000] K." }
        require(saturationScale in 0.0f..2.0f) { "Saturation scale must be between 0.0 and 2.0." }
        require(sharpeningAmount in 0.0f..1.0f) { "Sharpening amount must be normalized." }
        require(noiseReductionStrength in 0.0f..1.0f) { "Noise reduction strength must be normalized." }
    }
}

/**
 * Immutable description of a device-inspired post-processing look.
 *
 * Core algorithms should depend on this neutral contract instead of depending
 * directly on one device family. Version 1 ships the ProShot Natural profile,
 * and future profiles can add their own tuning data without changing the
 * pipeline stage interfaces.
 */
data class LookProfile(
    val id: String,
    val displayName: String,
    val toneCurve: List<ToneCurvePoint>,
    val globalWarmthShiftKelvin: Int,
    val globalSaturationScale: Float,
    val skinHueLockEnabled: Boolean,
    val skinSaturationClamp: Float,
    val faceTargetLuminance: ClosedFloatingPointRange<Float>,
    val regionalTunings: Map<SemanticRegion, RegionTuning>
) {
    init {
        require(ID_PATTERN.matches(id)) { "Profile id must be lowercase and stable." }
        require(displayName.isNotBlank()) { "Display name must not be blank." }
        require(toneCurve.size >= MIN_TONE_CURVE_POINTS) {
            "Tone curve requires at least black and white control points."
        }
        require(toneCurve.first().input == 0.0f) { "Tone curve must start at 0.0 input." }
        require(toneCurve.last().input == 1.0f) { "Tone curve must end at 1.0 input." }
        require(toneCurve.zipWithNext().all { (left, right) -> left.input < right.input }) {
            "Tone curve inputs must be strictly increasing."
        }
        require(toneCurve.zipWithNext().all { (left, right) -> left.output <= right.output }) {
            "Tone curve outputs must be monotonically non-decreasing."
        }
        require(globalWarmthShiftKelvin in -10_000..10_000) {
            "Global warmth shift must be in [-10000, 10000] K."
        }
        require(globalSaturationScale in 0.0f..2.0f) {
            "Global saturation scale must be between 0.0 and 2.0."
        }
        require(skinSaturationClamp in 0.0f..1.0f) { "Skin saturation clamp must be normalized." }
        require(faceTargetLuminance.start in 0.0f..1.0f) {
            "Face target luminance lower bound must be normalized."
        }
        require(faceTargetLuminance.endInclusive in 0.0f..1.0f) {
            "Face target luminance upper bound must be normalized."
        }
        require(faceTargetLuminance.start <= faceTargetLuminance.endInclusive) {
            "Face target luminance range is inverted."
        }
        require(regionalTunings.keys.containsAll(SemanticRegion.entries)) {
            "Every semantic region must have explicit tuning."
        }
    }

    private companion object {
        const val MIN_TONE_CURVE_POINTS = 2
        val ID_PATTERN = Regex("[a-z][a-z0-9]+([._-][a-z0-9]+)*")
    }
}
