package com.proshot.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented compatibility checks that do not launch the external Settings application.
 */
@RunWith(AndroidJUnit4::class)
class AppSettingsCompatibilityInstrumentedTest {

    @Test
    fun appIcon_hasPositiveIntrinsicDimensions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val appInfo = packageManager.getApplicationInfo(context.packageName, 0)
        val iconDrawable = packageManager.getApplicationIcon(appInfo)

        assertNotNull("Application icon must not be null", iconDrawable)
        assertTrue(
            "Icon intrinsic width must be positive, was: ${iconDrawable.intrinsicWidth}",
            iconDrawable.intrinsicWidth > 0
        )
        assertTrue(
            "Icon intrinsic height must be positive, was: ${iconDrawable.intrinsicHeight}",
            iconDrawable.intrinsicHeight > 0
        )

        if (Build.VERSION.SDK_INT >= 26) {
            assertTrue("Icon must be an AdaptiveIconDrawable", iconDrawable is AdaptiveIconDrawable)
            val adaptiveIcon = iconDrawable as AdaptiveIconDrawable
            val foreground = adaptiveIcon.foreground
            assertNotNull("Adaptive icon foreground must not be null", foreground)
            assertTrue(
                "Foreground intrinsic width must be positive, was: ${foreground.intrinsicWidth}",
                foreground.intrinsicWidth > 0
            )
            assertTrue(
                "Foreground intrinsic height must be positive, was: ${foreground.intrinsicHeight}",
                foreground.intrinsicHeight > 0
            )
        }
    }

    @Test
    fun productionLauncher_buildsExactPrimaryAndFallbackIntentsWithoutNewTaskFlag() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val capturedIntents = mutableListOf<Intent>()
        val launcher = ContextIntentLauncher { intent ->
            capturedIntents.add(intent)
            if (intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                throw ActivityNotFoundException("Force fallback without launching Settings")
            }
        }

        val outcome = AppSettingsNavigator(launcher).navigateToSettings(context.packageName)

        assertEquals(NavigationOutcome.SUCCESS_FALLBACK, outcome)
        assertEquals(2, capturedIntents.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, capturedIntents[0].action)
        assertEquals("package:${context.packageName}", capturedIntents[0].dataString)
        assertEquals(0, capturedIntents[0].flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(Settings.ACTION_APPLICATION_SETTINGS, capturedIntents[1].action)
        assertNull(capturedIntents[1].data)
        assertEquals(0, capturedIntents[1].flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun navigateToSettings_detailsNotFoundThenApplicationsSecurityBlocked_returnsUnavailable() {
        val attempts = mutableListOf<String?>()
        val launcher = ContextIntentLauncher { intent ->
            attempts.add(intent.action)
            if (intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                throw ActivityNotFoundException("Details unavailable")
            }
            throw SecurityException("Applications Settings blocked")
        }

        val outcome = AppSettingsNavigator(launcher).navigateToSettings(PACKAGE_NAME)

        assertEquals(NavigationOutcome.UNAVAILABLE, outcome)
        assertEquals(expectedActionOrder(), attempts)
    }

    @Test
    fun navigateToSettings_detailsSecurityBlockedThenApplicationsNotFound_returnsUnavailable() {
        val attempts = mutableListOf<String?>()
        val launcher = ContextIntentLauncher { intent ->
            attempts.add(intent.action)
            if (intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                throw SecurityException("Details Settings blocked")
            }
            throw ActivityNotFoundException("Applications unavailable")
        }

        val outcome = AppSettingsNavigator(launcher).navigateToSettings(PACKAGE_NAME)

        assertEquals(NavigationOutcome.UNAVAILABLE, outcome)
        assertEquals(expectedActionOrder(), attempts)
    }

    @Test
    fun productionLauncher_unexpectedExceptionPropagates() {
        val launcher = ContextIntentLauncher {
            throw IllegalArgumentException("Unexpected launch failure")
        }

        try {
            launcher.launch(SettingsDestination.AppDetails(PACKAGE_NAME))
            fail("Unexpected exception must propagate")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test(expected = AssertionError::class)
    fun productionLauncher_errorPropagates() {
        val launcher = ContextIntentLauncher {
            throw AssertionError("Fatal launch failure")
        }

        launcher.launch(SettingsDestination.AppDetails(PACKAGE_NAME))
    }

    private fun expectedActionOrder(): List<String> {
        return listOf(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Settings.ACTION_APPLICATION_SETTINGS
        )
    }

    private companion object {
        const val PACKAGE_NAME = "com.proshot.app"
    }
}
