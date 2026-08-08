package com.proshot.app.camera

internal enum class FocusTargetFallbackReason {
    NONE,
    AF_REGIONS_UNSUPPORTED,
    ACTIVE_ARRAY_UNAVAILABLE
}

internal data class EffectiveFocusTargetPolicy(
    val requestedSource: FocusTargetSource,
    val effectiveSource: FocusTargetSource,
    val fallbackReason: FocusTargetFallbackReason
)

internal fun resolveEffectiveFocusTargetPolicy(
    requestedSource: FocusTargetSource,
    maxAfRegions: Int,
    activeArrayAvailable: Boolean
): EffectiveFocusTargetPolicy {
    if (requestedSource != FocusTargetSource.USER_TAP) {
        return EffectiveFocusTargetPolicy(
            requestedSource = requestedSource,
            effectiveSource = requestedSource,
            fallbackReason = FocusTargetFallbackReason.NONE
        )
    }
    val fallbackReason = when {
        !activeArrayAvailable -> FocusTargetFallbackReason.ACTIVE_ARRAY_UNAVAILABLE
        maxAfRegions <= 0 -> FocusTargetFallbackReason.AF_REGIONS_UNSUPPORTED
        else -> FocusTargetFallbackReason.NONE
    }
    return EffectiveFocusTargetPolicy(
        requestedSource = requestedSource,
        effectiveSource = if (fallbackReason == FocusTargetFallbackReason.NONE) {
            requestedSource
        } else {
            FocusTargetSource.DEFAULT_CENTER
        },
        fallbackReason = fallbackReason
    )
}
