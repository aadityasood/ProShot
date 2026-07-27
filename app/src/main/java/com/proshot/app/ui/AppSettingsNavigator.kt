package com.proshot.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Kotlin-only destinations supported by the permission recovery policy.
 */
internal sealed class SettingsDestination {
    data class AppDetails(val packageName: String) : SettingsDestination()
    object Applications : SettingsDestination()
}

/**
 * Result of attempting one destination at the Android launch edge.
 */
internal enum class DestinationLaunchResult {
    LAUNCHED,
    UNAVAILABLE
}

/**
 * Outcome of a complete Settings navigation attempt.
 */
internal enum class NavigationOutcome {
    SUCCESS_PRIMARY,
    SUCCESS_FALLBACK,
    UNAVAILABLE
}

/**
 * Kotlin-only launch boundary used by the deterministic navigation policy.
 */
internal fun interface SettingsDestinationLauncher {
    fun launch(destination: SettingsDestination): DestinationLaunchResult
}

/**
 * Applies the package-details then Applications Settings fallback policy.
 */
internal class AppSettingsNavigator(
    private val launcher: SettingsDestinationLauncher
) {
    fun navigateToSettings(packageName: String): NavigationOutcome {
        val primaryResult = launcher.launch(SettingsDestination.AppDetails(packageName))
        if (primaryResult == DestinationLaunchResult.LAUNCHED) {
            return NavigationOutcome.SUCCESS_PRIMARY
        }

        return when (launcher.launch(SettingsDestination.Applications)) {
            DestinationLaunchResult.LAUNCHED -> NavigationOutcome.SUCCESS_FALLBACK
            DestinationLaunchResult.UNAVAILABLE -> NavigationOutcome.UNAVAILABLE
        }
    }
}

/**
 * Maps a navigation outcome to the transient manual-recovery guidance state.
 */
internal fun shouldShowManualRecoveryMessage(outcome: NavigationOutcome): Boolean {
    return outcome == NavigationOutcome.UNAVAILABLE
}

/**
 * Android production edge that constructs and launches Settings intents.
 */
internal class ContextIntentLauncher(
    private val startActivity: (Intent) -> Unit
) : SettingsDestinationLauncher {
    constructor(context: Context) : this(
        startActivity = { intent -> context.startActivity(intent) }
    )

    override fun launch(destination: SettingsDestination): DestinationLaunchResult {
        val intent = when (destination) {
            is SettingsDestination.AppDetails -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", destination.packageName, null)
            )
            SettingsDestination.Applications -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
        }

        return try {
            startActivity(intent)
            DestinationLaunchResult.LAUNCHED
        } catch (_: ActivityNotFoundException) {
            DestinationLaunchResult.UNAVAILABLE
        } catch (_: SecurityException) {
            DestinationLaunchResult.UNAVAILABLE
        }
    }
}
