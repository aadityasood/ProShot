package com.proshot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val TERMINAL_FEEDBACK_DURATION_MILLIS = 5_000L
private val feedbackBottomClearance = 84.dp + 32.dp + 16.dp

/**
 * State model for capture outcome feedback.
 */
internal sealed interface CaptureFeedbackState {
    val eventToken: Long

    data class Hidden(override val eventToken: Long = 0L) : CaptureFeedbackState
    data class Capturing(override val eventToken: Long, val text: String) : CaptureFeedbackState
    data class Terminal(override val eventToken: Long, val text: String) : CaptureFeedbackState
}

/**
 * Reducer for managing capture outcome feedback states and event token transitions.
 */
internal class CaptureFeedbackReducer(
    initialState: CaptureFeedbackState = CaptureFeedbackState.Hidden()
) {
    var state: CaptureFeedbackState = initialState
        private set

    private var nextToken: Long = initialState.eventToken

    fun startCapture(text: String): CaptureFeedbackState.Capturing {
        nextToken++
        val newState = CaptureFeedbackState.Capturing(eventToken = nextToken, text = text)
        state = newState
        return newState
    }

    fun completeCapture(text: String): CaptureFeedbackState.Terminal {
        nextToken++
        val newState = CaptureFeedbackState.Terminal(eventToken = nextToken, text = text)
        state = newState
        return newState
    }

    fun dismiss(token: Long): CaptureFeedbackState {
        val current = state
        if (current is CaptureFeedbackState.Terminal && current.eventToken == token) {
            // Retain the dismissed token so hidden state records the latest handled event.
            val newState = CaptureFeedbackState.Hidden(eventToken = token)
            state = newState
            return newState
        }
        return current
    }
}

/**
 * Hosts capture feedback timing, focus-message priority, and pill rendering.
 */
@Composable
internal fun CaptureFeedbackHost(
    state: CaptureFeedbackState,
    focusMessage: String,
    onDismiss: (eventToken: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state is CaptureFeedbackState.Terminal) {
        LaunchedEffect(state.eventToken) {
            delay(TERMINAL_FEEDBACK_DURATION_MILLIS)
            onDismiss(state.eventToken)
        }
    }

    val message = when (state) {
        is CaptureFeedbackState.Capturing -> state.text
        is CaptureFeedbackState.Terminal -> state.text
        is CaptureFeedbackState.Hidden -> focusMessage
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CaptureFeedbackPill(message = message)
    }
}

/**
 * Places feedback above the portrait shutter envelope and the safe drawing bottom inset.
 */
@Composable
internal fun BoxScope.CaptureFeedbackPlacement(
    state: CaptureFeedbackState,
    focusMessage: String,
    onDismiss: (eventToken: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    CaptureFeedbackHost(
        state = state,
        focusMessage = focusMessage,
        onDismiss = onDismiss,
        modifier = modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(bottom = feedbackBottomClearance)
    )
}

/**
 * Displays beginner capture outcome feedback as one polite accessibility message.
 */
@Composable
internal fun CaptureFeedbackPill(
    message: String,
    modifier: Modifier = Modifier
) {
    if (message.isNotEmpty()) {
        Box(
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(16.dp))
                .clearAndSetSemantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = message
                }
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(
                text = message,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}
