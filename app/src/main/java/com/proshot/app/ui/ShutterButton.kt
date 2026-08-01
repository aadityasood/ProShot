package com.proshot.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * A camera-style circular shutter control.
 * Displays a capturing state with shrinking circle size and an active progress indicator,
 * and a disabled/waiting state.
 */
@Composable
internal fun ShutterButton(
    enabled: Boolean,
    isCapturing: Boolean,
    isWaiting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val innerSize by animateDpAsState(
        targetValue = when {
            isWaiting -> 0.dp
            isCapturing -> 44.dp
            else -> 60.dp
        },
        label = "shutterInnerSize"
    )

    Box(
        modifier = modifier
            .size(84.dp)
            .semantics {
                role = Role.Button
                contentDescription = when {
                    isWaiting -> "Camera loading"
                    isCapturing -> "Capturing photo"
                    else -> "Take photo"
                }
                stateDescription = when {
                    isWaiting -> "Waiting"
                    isCapturing -> "Capturing"
                    enabled -> "Ready"
                    else -> "Disabled"
                }
            }
            .clickable(
                enabled = enabled && !isWaiting && !isCapturing,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Outer Ring
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White.copy(alpha = if (isWaiting) 0.3f else 1.0f),
                radius = (size.minDimension / 2) - 4.dp.toPx(),
                style = Stroke(width = 4.dp.toPx())
            )
        }

        if (isWaiting || isCapturing) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(52.dp)
            )
        }

        if (innerSize > 0.dp) {
            Box(
                modifier = Modifier
                    .size(innerSize)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (enabled) 1.0f else 0.5f))
            )
        }
    }
}
