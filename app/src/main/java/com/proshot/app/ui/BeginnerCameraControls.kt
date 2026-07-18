package com.proshot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.proshot.app.R

/**
 * Placement configuration for the capture control strip.
 */
internal enum class CaptureControlsPlacement {
    PORTRAIT,
    LANDSCAPE
}

/**
 * Pure placement policy to determine controls alignment based on window dimensions.
 * Portrait/Square aligns to bottom center; Landscape aligns to center end.
 */
internal object CapturePlacementPolicy {
    /**
     * Resolves the placement configuration based on window width and height.
     */
    fun resolve(width: Int, height: Int): CaptureControlsPlacement {
        return if (width > height) {
            CaptureControlsPlacement.LANDSCAPE
        } else {
            CaptureControlsPlacement.PORTRAIT
        }
    }
}

/**
 * A lightweight router for handling hardware volume key events.
 * Keeps track of the currently pressed volume key to ignore repeats and matches up events.
 */
internal class VolumeKeyRouter(
    private val onShutterRequest: () -> Boolean
) {
    internal companion object {
        const val KEY_CODE_VOLUME_UP = 24
        const val KEY_CODE_VOLUME_DOWN = 25
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
    }

    private val consumedPressedKeys = mutableSetOf<Int>()

    /**
     * Processes a key event. Returns true if the event was consumed, false if it should fall through.
     */
    fun dispatchKeyEvent(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        if (keyCode != KEY_CODE_VOLUME_UP && keyCode != KEY_CODE_VOLUME_DOWN) {
            return false
        }

        return when (action) {
            ACTION_DOWN -> {
                if (repeatCount == 0) {
                    if (keyCode in consumedPressedKeys) {
                        true
                    } else if (consumedPressedKeys.isNotEmpty()) {
                        consumedPressedKeys.add(keyCode)
                        true
                    } else if (onShutterRequest()) {
                        consumedPressedKeys.add(keyCode)
                        true
                    } else {
                        false
                    }
                } else {
                    keyCode in consumedPressedKeys
                }
            }
            ACTION_UP -> consumedPressedKeys.remove(keyCode)
            else -> false
        }
    }
}

/**
 * A lightweight overlay grid that draws rule-of-thirds guide lines.
 * It is completely passive: does not consume pointer input or affect accessibility semantics.
 */
@Composable
internal fun RuleOfThirdsGrid(
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        val color = Color.White.copy(alpha = 0.4f)
        val strokeWidth = 1.dp.toPx()

        val stepX = size.width / 3f
        val stepY = size.height / 3f

        // Draw 2 vertical lines
        drawLine(color = color, start = Offset(stepX, 0f), end = Offset(stepX, size.height), strokeWidth = strokeWidth)
        drawLine(color = color, start = Offset(stepX * 2, 0f), end = Offset(stepX * 2, size.height), strokeWidth = strokeWidth)

        // Draw 2 horizontal lines
        drawLine(color = color, start = Offset(0f, stepY), end = Offset(size.width, stepY), strokeWidth = strokeWidth)
        drawLine(color = color, start = Offset(0f, stepY * 2), end = Offset(size.width, stepY * 2), strokeWidth = strokeWidth)
    }
}

/**
 * Accessible grid toggle button with custom canvas drawing.
 */
@Composable
internal fun GridToggleButton(
    gridVisible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentDescription = stringResource(R.string.grid_toggle_desc)
    val stateDescription = if (gridVisible) {
        stringResource(R.string.grid_state_on)
    } else {
        stringResource(R.string.grid_state_off)
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .toggleable(
                value = gridVisible,
                role = Role.Switch,
                onValueChange = { onToggle() }
            )
            .semantics {
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val color = if (gridVisible) Color.Yellow else Color.White
            val strokeWidth = 2.dp.toPx()

            // Draw outer border
            drawRect(
                color = color,
                style = Stroke(width = strokeWidth)
            )

            val stepX = size.width / 3f
            val stepY = size.height / 3f

            // Vertical lines
            drawLine(color = color, start = Offset(stepX, 0f), end = Offset(stepX, size.height), strokeWidth = strokeWidth)
            drawLine(color = color, start = Offset(stepX * 2, 0f), end = Offset(stepX * 2, size.height), strokeWidth = strokeWidth)

            // Horizontal lines
            drawLine(color = color, start = Offset(0f, stepY), end = Offset(size.width, stepY), strokeWidth = strokeWidth)
            drawLine(color = color, start = Offset(0f, stepY * 2), end = Offset(size.width, stepY * 2), strokeWidth = strokeWidth)
        }
    }
}

/**
 * Layout wrapper for beginner controls. Places shutter and grid toggle based on placement configuration.
 */
@Composable
internal fun BeginnerCameraControls(
    isCapturing: Boolean,
    isWaiting: Boolean,
    enabled: Boolean,
    gridVisible: Boolean,
    onGridToggle: () -> Unit,
    onShutterClick: () -> Unit,
    placement: CaptureControlsPlacement,
    modifier: Modifier = Modifier
) {
    if (placement == CaptureControlsPlacement.PORTRAIT) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 24.dp, end = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GridToggleButton(gridVisible = gridVisible, onToggle = onGridToggle)
            ShutterButton(
                enabled = enabled,
                isCapturing = isCapturing,
                isWaiting = isWaiting,
                onClick = onShutterClick
            )
            // Empty space to balance layout symmetrically
            Spacer(modifier = Modifier.size(48.dp))
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxHeight()
                .padding(end = 32.dp, top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GridToggleButton(gridVisible = gridVisible, onToggle = onGridToggle)
            ShutterButton(
                enabled = enabled,
                isCapturing = isCapturing,
                isWaiting = isWaiting,
                onClick = onShutterClick
            )
            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}
