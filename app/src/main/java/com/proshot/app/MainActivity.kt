package com.proshot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main activity for the ProShot camera application.
 * Currently serves as an empty shell for the Compose-based UI.
 *
 * TODO: Remove stringFromJNI() and Greeting() after NDK smoke test is verified.
 *       These are temporary build-verification stubs (will be replaced in T02).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        init {
            System.loadLibrary("proshot")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // TODO(T22): Replace ProShotTheme with full custom theme from Theme.kt
            ProShotTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Greeting(stringFromJNI())
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

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Welcome to $name!",
        modifier = modifier
    )
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
