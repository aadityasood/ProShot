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
import com.proshot.app.camera.CameraCaptureRuntime
import com.proshot.app.ui.CameraScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

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
         * pipeline has not been built yet.
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

    @Inject
    lateinit var cameraCaptureRuntime: CameraCaptureRuntime

    private var volumeKeyEventHandler: ((android.view.KeyEvent) -> Boolean)? = null

    internal fun registerVolumeKeyEventHandler(
        handler: (android.view.KeyEvent) -> Boolean
    ) {
        volumeKeyEventHandler = handler
    }

    internal fun unregisterVolumeKeyEventHandler(
        handler: (android.view.KeyEvent) -> Boolean
    ) {
        if (volumeKeyEventHandler === handler) {
            volumeKeyEventHandler = null
        }
    }

    private fun invokeEventHandler(event: android.view.KeyEvent): Boolean {
        return try {
            volumeKeyEventHandler?.invoke(event) == true
        } catch (e: Exception) {
            false
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        return if (invokeEventHandler(event)) {
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        return if (invokeEventHandler(event)) {
            true
        } else {
            super.onKeyUp(keyCode, event)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // TODO: Replace ProShotTheme with the full custom app theme.
            ProShotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CameraScreen(cameraCaptureRuntime = cameraCaptureRuntime)
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
 * A future custom theme should provide:
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
