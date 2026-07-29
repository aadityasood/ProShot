package com.proshot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFeedbackTest {

    @Test
    fun acceptedCapture_createsCapturingEvent_andNewerCaptureSupersedesOlderTerminalEvent() {
        val reducer = CaptureFeedbackReducer()
        val initial = reducer.state
        assertTrue(initial is CaptureFeedbackState.Hidden)

        val capturing1 = reducer.startCapture("Taking photo...")
        assertEquals(1L, capturing1.eventToken)
        assertEquals("Taking photo...", capturing1.text)
        assertEquals(capturing1, reducer.state)

        val terminal1 = reducer.completeCapture("Photo saved.")
        assertEquals(2L, terminal1.eventToken)
        assertEquals("Photo saved.", terminal1.text)

        val capturing2 = reducer.startCapture("Taking photo...")
        assertEquals(3L, capturing2.eventToken)
        assertEquals(capturing2, reducer.state)

        // Attempting to dismiss with terminal1 token (2L) while state is capturing2 (3L)
        val afterStaleDismiss = reducer.dismiss(terminal1.eventToken)
        assertEquals(capturing2, afterStaleDismiss)
        assertEquals(capturing2, reducer.state)
    }

    @Test
    fun terminalSuccessAndFailure_preserveExactSuppliedResultMessage() {
        val reducer = CaptureFeedbackReducer()
        reducer.startCapture("Taking photo...")

        val successTerminal = reducer.completeCapture("Photo saved to Pictures/ProShot")
        assertEquals("Photo saved to Pictures/ProShot", successTerminal.text)

        reducer.startCapture("Taking photo...")
        val failureTerminal = reducer.completeCapture("Capture failed: Device error")
        assertEquals("Capture failed: Device error", failureTerminal.text)
    }

    @Test
    fun matchingTerminalToken_dismissesToHidden() {
        val reducer = CaptureFeedbackReducer()
        reducer.startCapture("Taking photo...")
        val terminal = reducer.completeCapture("Photo saved.")

        val dismissed = reducer.dismiss(terminal.eventToken)
        assertTrue(dismissed is CaptureFeedbackState.Hidden)
        assertEquals(terminal.eventToken, dismissed.eventToken)
        assertEquals(dismissed, reducer.state)
    }

    @Test
    fun staleToken_cannotClearNewerState() {
        val reducer = CaptureFeedbackReducer()
        reducer.startCapture("Taking photo...")
        val terminal1 = reducer.completeCapture("First photo saved.")

        val capturing2 = reducer.startCapture("Taking photo...")
        val terminal2 = reducer.completeCapture("Second photo saved.")

        // Stale dismiss attempt for terminal1
        val result = reducer.dismiss(terminal1.eventToken)
        assertEquals(terminal2, result)
        assertEquals(terminal2, reducer.state)
    }

    @Test
    fun capturingState_cannotBeClearedByTerminalAutoDismissPolicy() {
        val reducer = CaptureFeedbackReducer()
        val capturing = reducer.startCapture("Taking photo...")

        // Attempting to dismiss with the capturing token or any previous token
        val resultSameToken = reducer.dismiss(capturing.eventToken)
        assertEquals(capturing, resultSameToken)
        assertEquals(capturing, reducer.state)

        val resultArbitraryToken = reducer.dismiss(999L)
        assertEquals(capturing, resultArbitraryToken)
        assertEquals(capturing, reducer.state)
    }
}
