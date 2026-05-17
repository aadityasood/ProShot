package com.proshot.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Main application class for ProShot.
 * Initialized with Hilt for dependency injection.
 */
@HiltAndroidApp
class ProShotApplication : Application()
