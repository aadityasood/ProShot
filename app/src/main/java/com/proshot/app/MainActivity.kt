package com.proshot.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.proshot.app.ui.CameraScreen
import dagger.hilt.android.AndroidEntryPoint

private const val TAG = "MainActivity"

/**
 * Main activity for the ProShot camera application.
 * Boots the unified CameraScreen viewfinder to handle permission checks and live views.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Whether the native library loaded successfully. `false` when the NDK
         * pipeline has not been built yet (T01-D003).
         */
        @JvmStatic
        val nativeLibraryAvailable: Boolean

        init {
            nativeLibraryAvailable = try {
                System.loadLibrary("proshot")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native library 'proshot' not available; NDK features disabled.", e)
                false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // TODO(T22): Replace ProShotTheme with full custom theme from Theme.kt
            ProShotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CameraScreen()
                }
            }
        }
    }

    /**
     * A native method that is implemented by the 'proshot' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String
}

/**
 * Placeholder theme wrapper for ProShot.
 *
 * TODO(T22): Replace with a custom [Theme.kt] providing:
 *   - Custom dark-mode color scheme (camera apps are used in dark environments)
 *   - Material You / dynamic color support
 *   - Custom typography for viewfinder overlays (zoom, exposure values)
 *   - Edge-to-edge and WindowInsets configuration
 */
@Composable
fun ProShotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        content = content
    )
}
