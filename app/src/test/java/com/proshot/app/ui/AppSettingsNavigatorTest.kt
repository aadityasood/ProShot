package com.proshot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsNavigatorTest {

    @Test
    fun navigateToSettings_whenPrimarySucceeds_attemptsOnlyAppDetails() {
        val launcher = RecordingLauncher { _, _ -> DestinationLaunchResult.LAUNCHED }

        val outcome = AppSettingsNavigator(launcher).navigateToSettings(PACKAGE_NAME)

        assertEquals(NavigationOutcome.SUCCESS_PRIMARY, outcome)
        assertEquals(
            listOf(SettingsDestination.AppDetails(PACKAGE_NAME)),
            launcher.attempts
        )
    }

    @Test
    fun navigateToSettings_whenPrimaryUnavailable_attemptsApplicationsSecond() {
        val launcher = RecordingLauncher { destination, _ ->
            when (destination) {
                is SettingsDestination.AppDetails -> DestinationLaunchResult.UNAVAILABLE
                SettingsDestination.Applications -> DestinationLaunchResult.LAUNCHED
            }
        }

        val outcome = AppSettingsNavigator(launcher).navigateToSettings(PACKAGE_NAME)

        assertEquals(NavigationOutcome.SUCCESS_FALLBACK, outcome)
        assertEquals(expectedDestinationOrder(), launcher.attempts)
    }

    @Test
    fun navigateToSettings_whenBothUnavailable_attemptsEachDestinationOnceInOrder() {
        val launcher = RecordingLauncher { _, _ -> DestinationLaunchResult.UNAVAILABLE }

        val outcome = AppSettingsNavigator(launcher).navigateToSettings(PACKAGE_NAME)

        assertEquals(NavigationOutcome.UNAVAILABLE, outcome)
        assertEquals(expectedDestinationOrder(), launcher.attempts)
        assertEquals(1, launcher.attempts.count { it is SettingsDestination.AppDetails })
        assertEquals(1, launcher.attempts.count { it == SettingsDestination.Applications })
    }

    @Test
    fun navigateToSettings_afterUnavailable_canRetryAndClearManualGuidanceOnSuccess() {
        val launcher = RecordingLauncher { _, attemptNumber ->
            if (attemptNumber <= 2) {
                DestinationLaunchResult.UNAVAILABLE
            } else {
                DestinationLaunchResult.LAUNCHED
            }
        }
        val navigator = AppSettingsNavigator(launcher)

        val firstOutcome = navigator.navigateToSettings(PACKAGE_NAME)
        val showAfterFailure = shouldShowManualRecoveryMessage(firstOutcome)
        val secondOutcome = navigator.navigateToSettings(PACKAGE_NAME)
        val showAfterSuccess = shouldShowManualRecoveryMessage(secondOutcome)

        assertEquals(NavigationOutcome.UNAVAILABLE, firstOutcome)
        assertTrue(showAfterFailure)
        assertEquals(NavigationOutcome.SUCCESS_PRIMARY, secondOutcome)
        assertFalse(showAfterSuccess)
        assertEquals(
            listOf(
                SettingsDestination.AppDetails(PACKAGE_NAME),
                SettingsDestination.Applications,
                SettingsDestination.AppDetails(PACKAGE_NAME)
            ),
            launcher.attempts
        )
    }

    @Test(expected = IllegalStateException::class)
    fun navigateToSettings_whenPrimaryThrowsUnexpectedException_propagatesFailure() {
        val launcher = RecordingLauncher { _, _ ->
            throw IllegalStateException("Unexpected primary failure")
        }

        AppSettingsNavigator(launcher).navigateToSettings(PACKAGE_NAME)
    }

    @Test(expected = IllegalStateException::class)
    fun navigateToSettings_whenFallbackThrowsUnexpectedException_propagatesFailure() {
        val launcher = RecordingLauncher { destination, _ ->
            if (destination is SettingsDestination.AppDetails) {
                DestinationLaunchResult.UNAVAILABLE
            } else {
                throw IllegalStateException("Unexpected fallback failure")
            }
        }

        AppSettingsNavigator(launcher).navigateToSettings(PACKAGE_NAME)
    }

    @Test(expected = AssertionError::class)
    fun navigateToSettings_whenPrimaryThrowsError_propagatesFailure() {
        val launcher = RecordingLauncher { _, _ ->
            throw AssertionError("Fatal primary failure")
        }

        AppSettingsNavigator(launcher).navigateToSettings(PACKAGE_NAME)
    }

    @Test(expected = AssertionError::class)
    fun navigateToSettings_whenFallbackThrowsError_propagatesFailure() {
        val launcher = RecordingLauncher { destination, _ ->
            if (destination is SettingsDestination.AppDetails) {
                DestinationLaunchResult.UNAVAILABLE
            } else {
                throw AssertionError("Fatal fallback failure")
            }
        }

        AppSettingsNavigator(launcher).navigateToSettings(PACKAGE_NAME)
    }

    private fun expectedDestinationOrder(): List<SettingsDestination> {
        return listOf(
            SettingsDestination.AppDetails(PACKAGE_NAME),
            SettingsDestination.Applications
        )
    }

    private class RecordingLauncher(
        private val resultForAttempt: (
            destination: SettingsDestination,
            attemptNumber: Int
        ) -> DestinationLaunchResult
    ) : SettingsDestinationLauncher {
        val attempts = mutableListOf<SettingsDestination>()

        override fun launch(destination: SettingsDestination): DestinationLaunchResult {
            attempts.add(destination)
            return resultForAttempt(destination, attempts.size)
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.proshot.app"
    }
}
