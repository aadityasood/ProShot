package com.proshot.app.ui

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal data class CurrentDisplayRotation(
    val displayId: Int,
    val rotationDegrees: Int
)

internal fun interface DisplayRotationSubscription {
    fun dispose()
}

internal interface DisplayRotationSource {
    fun currentDisplayRotation(): CurrentDisplayRotation?

    fun observeDisplayChanges(
        onDisplayChanged: (displayId: Int) -> Unit
    ): DisplayRotationSubscription
}

internal object UnavailableDisplayRotationSource : DisplayRotationSource {
    override fun currentDisplayRotation(): CurrentDisplayRotation? = null

    override fun observeDisplayChanges(
        onDisplayChanged: (displayId: Int) -> Unit
    ): DisplayRotationSubscription = DisplayRotationSubscription {}
}

internal class AndroidDisplayRotationSource(
    private val activity: Activity,
    private val displayManager: DisplayManager,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) : DisplayRotationSource {
    override fun currentDisplayRotation(): CurrentDisplayRotation? {
        val display = currentActivityDisplay() ?: return null
        return CurrentDisplayRotation(
            displayId = display.displayId,
            rotationDegrees = display.rotation.toNeutralRotationDegrees()
        )
    }

    override fun observeDisplayChanges(
        onDisplayChanged: (displayId: Int) -> Unit
    ): DisplayRotationSubscription {
        val notifyDisplayChanged = onDisplayChanged
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                notifyDisplayChanged(displayId)
            }

            override fun onDisplayRemoved(displayId: Int) {
                notifyDisplayChanged(displayId)
            }

            override fun onDisplayChanged(displayId: Int) {
                notifyDisplayChanged(displayId)
            }
        }
        displayManager.registerDisplayListener(listener, mainHandler)
        return DisplayRotationSubscription {
            displayManager.unregisterDisplayListener(listener)
        }
    }

    private fun currentActivityDisplay(): Display? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
    }

    private fun Int.toNeutralRotationDegrees(): Int {
        return when (this) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_180 -> 180
            else -> 0
        }
    }
}

@Composable
internal fun rememberCurrentDisplayRotationDegrees(
    source: DisplayRotationSource
): Int {
    var rotationDegrees by remember(source) {
        mutableIntStateOf(source.currentDisplayRotation()?.rotationDegrees ?: 0)
    }

    DisposableEffect(source) {
        var observedDisplayId = source.currentDisplayRotation()?.displayId
        val subscription = source.observeDisplayChanges { changedDisplayId ->
            val current = source.currentDisplayRotation()
            when {
                current != null && current.displayId == changedDisplayId -> {
                    observedDisplayId = current.displayId
                    rotationDegrees = current.rotationDegrees
                }
                current == null && observedDisplayId == changedDisplayId -> {
                    observedDisplayId = null
                    rotationDegrees = 0
                }
            }
        }

        val current = source.currentDisplayRotation()
        observedDisplayId = current?.displayId
        rotationDegrees = current?.rotationDegrees ?: 0

        onDispose {
            subscription.dispose()
        }
    }

    return rotationDegrees
}
