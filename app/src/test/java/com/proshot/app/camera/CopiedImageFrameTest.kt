package com.proshot.app.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Unit tests verifying safe, immutable heap copies of image plane buffers.
 */
class CopiedImageFrameTest {

    private class FakePlaneSource(
        override val rowStride: Int,
        override val pixelStride: Int,
        override val buffer: ByteBuffer
    ) : ImageSource.PlaneSource

    private class FakeImageSource(
        override val format: Int,
        override val width: Int,
        override val height: Int,
        override val timestamp: Long,
        override val planes: List<ImageSource.PlaneSource>
    ) : ImageSource

    @Test
    fun copyFrom_copiesDataWithoutMutatingOriginalBuffer() {
        val originalBytes = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = ByteBuffer.allocateDirect(originalBytes.size).apply {
            put(originalBytes)
            flip()
            position(1) // Set a non-zero start position
        }

        val plane = FakePlaneSource(rowStride = 10, pixelStride = 1, buffer = buffer)
        val image = FakeImageSource(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = 100,
            height = 100,
            timestamp = 123456789L,
            planes = listOf(plane)
        )

        // Record original buffer state
        val originalPosition = buffer.position()
        val originalLimit = buffer.limit()

        val copiedFrame = CopiedImageFrame.copyFrom(image)

        // Verify the original buffer state is completely untouched (duplicate() guarantees this)
        assertEquals("Buffer position must not be modified", originalPosition, buffer.position())
        assertEquals("Buffer limit must not be modified", originalLimit, buffer.limit())

        // Verify the extracted bytes match the expected remaining slice [2, 3, 4, 5]
        val expectedCopiedBytes = byteArrayOf(2, 3, 4, 5)
        assertEquals(1, copiedFrame.planes.size)
        assertEquals(10, copiedFrame.planes[0].rowStride)
        assertEquals(1, copiedFrame.planes[0].pixelStride)
        assertArrayEquals(expectedCopiedBytes, copiedFrame.planes[0].data)
    }

    @Test
    fun copyFrom_ensuresImmutableHeapIsolation() {
        val originalBytes = byteArrayOf(10, 20, 30)
        val buffer = ByteBuffer.allocateDirect(originalBytes.size).apply {
            put(originalBytes)
            flip()
        }

        val plane = FakePlaneSource(rowStride = 5, pixelStride = 1, buffer = buffer)
        val image = FakeImageSource(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = 50,
            height = 50,
            timestamp = 999L,
            planes = listOf(plane)
        )

        val copiedFrame = CopiedImageFrame.copyFrom(image)

        // Mutate original backing direct buffer
        buffer.clear()
        buffer.put(byteArrayOf(99, 99, 99))

        // Copied heap frame must NOT change (it's isolated from direct/native memory pointer changes)
        assertArrayEquals(byteArrayOf(10, 20, 30), copiedFrame.planes[0].data)
    }

    @Test
    fun copyFrom_zeroPlanesProducesEmptyPlaneList() {
        val image = FakeImageSource(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = 100,
            height = 100,
            timestamp = 42L,
            planes = emptyList()
        )

        val copiedFrame = CopiedImageFrame.copyFrom(image)

        assertTrue("Zero-plane source should produce empty planes list", copiedFrame.planes.isEmpty())
        assertEquals(100, copiedFrame.width)
        assertEquals(100, copiedFrame.height)
    }

    @Test
    fun copyFrom_worksWithHeapAllocatedBuffer() {
        // Verify that non-direct (heap) ByteBuffers are handled correctly.
        // Camera2 uses direct buffers, but the ImageSource abstraction allows
        // any ByteBuffer; this tests the non-direct path.
        val originalBytes = byteArrayOf(7, 8, 9, 10)
        val heapBuffer = ByteBuffer.allocate(originalBytes.size).apply {
            put(originalBytes)
            flip()
        }

        val plane = FakePlaneSource(rowStride = 4, pixelStride = 1, buffer = heapBuffer)
        val image = FakeImageSource(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = 2,
            height = 2,
            timestamp = 555L,
            planes = listOf(plane)
        )

        val copiedFrame = CopiedImageFrame.copyFrom(image)

        assertArrayEquals(originalBytes, copiedFrame.planes[0].data)
        // Verify original heap buffer position is untouched
        assertEquals(0, heapBuffer.position())
    }

    @Test
    fun findClosestStableSize_selectsClosestTo1080p() {
        val sizes = listOf(
            CaptureSize(1280, 720),    // area = 921,600 (diff = 1,152,000)
            CaptureSize(1920, 1080),   // area = 2,073,600 (diff = 0)
            CaptureSize(3840, 2160),   // area = 8,294,400 (diff = 6,220,800)
            CaptureSize(640, 480)      // area = 307,200 (diff = 1,766,400)
        )

        val selected = SingleFrameCaptureController.findClosestStableSize(sizes)
        assertEquals(1920, selected.width)
        assertEquals(1080, selected.height)
    }

    @Test
    fun findClosestStableSize_emptyListReturnsFallback() {
        val selected = SingleFrameCaptureController.findClosestStableSize(emptyList())
        assertEquals(1920, selected.width)
        assertEquals(1080, selected.height)
    }

    @Test
    fun findClosestStableSize_selectsClosestAreaWhenNoExact1080p() {
        val sizes = listOf(
            CaptureSize(1600, 1200),   // area = 1,920,000 (diff = 153,600) -> closest!
            CaptureSize(2560, 1440),   // area = 3,686,400 (diff = 1,612,800)
            CaptureSize(800, 600)      // area = 480,000
        )

        val selected = SingleFrameCaptureController.findClosestStableSize(sizes)
        assertEquals(1600, selected.width)
        assertEquals(1200, selected.height)
    }

    @Test
    fun summarizeFrame_producesValidSummary() {
        val planeY = CopiedPlane(rowStride = 100, pixelStride = 1, data = ByteArray(100))
        val planeU = CopiedPlane(rowStride = 50, pixelStride = 2, data = ByteArray(50))
        val planeV = CopiedPlane(rowStride = 50, pixelStride = 2, data = ByteArray(50))

        val frame = CopiedImageFrame(
            format = CameraCapabilitiesMapper.FORMAT_YUV_420_888,
            width = 100,
            height = 100,
            timestamp = 987654321L,
            planes = listOf(planeY, planeU, planeV)
        )

        val summary = SingleFrameCaptureController.summarizeFrame(frame)
        assertEquals(100, summary.width)
        assertEquals(100, summary.height)
        assertEquals(987654321L, summary.timestampNs)
        assertEquals("YUV_420_888", summary.formatName)
        assertEquals(100, summary.yPlaneSize)
        assertEquals(50, summary.uPlaneSize)
        assertEquals(50, summary.vPlaneSize)

        val formatted = summary.getFormattedSummary()
        assertTrue(formatted.contains("Res: 100x100"))
        assertTrue(formatted.contains("Time: 987654321ns"))
        assertTrue(formatted.contains("Y: 100"))
        assertTrue(formatted.contains("U: 50"))
        assertTrue(formatted.contains("V: 50"))
    }

    @Test
    fun selectAutoFocusModeForStillCapture_defaultCenter_prefersContinuousPicture() {
        val selected = SingleFrameCaptureController.selectAutoFocusModeForStillCapture(
            intArrayOf(
                CaptureRequest.CONTROL_AF_MODE_OFF,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CaptureRequest.CONTROL_AF_MODE_AUTO
            ),
            FocusTargetSource.DEFAULT_CENTER
        )
        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, selected)
    }

    @Test
    fun selectAutoFocusModeForStillCapture_defaultCenter_fallsBackToAuto() {
        val selected = SingleFrameCaptureController.selectAutoFocusModeForStillCapture(
            intArrayOf(
                CaptureRequest.CONTROL_AF_MODE_OFF,
                CaptureRequest.CONTROL_AF_MODE_AUTO
            ),
            FocusTargetSource.DEFAULT_CENTER
        )
        assertEquals(CaptureRequest.CONTROL_AF_MODE_AUTO, selected)
    }

    @Test
    fun selectAutoFocusModeForStillCapture_userTap_prefersAuto() {
        val selected = SingleFrameCaptureController.selectAutoFocusModeForStillCapture(
            intArrayOf(
                CaptureRequest.CONTROL_AF_MODE_OFF,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CaptureRequest.CONTROL_AF_MODE_AUTO
            ),
            FocusTargetSource.USER_TAP
        )
        assertEquals(CaptureRequest.CONTROL_AF_MODE_AUTO, selected)
    }

    @Test
    fun selectAutoFocusModeForStillCapture_userTap_fallsBackToContinuousPicture() {
        val selected = SingleFrameCaptureController.selectAutoFocusModeForStillCapture(
            intArrayOf(
                CaptureRequest.CONTROL_AF_MODE_OFF,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            ),
            FocusTargetSource.USER_TAP
        )
        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, selected)
    }

    @Test
    fun selectAutoFocusModeForStillCapture_returnsNullForFixedFocusOnlyCamera() {
        val selectedDefault = SingleFrameCaptureController.selectAutoFocusModeForStillCapture(
            intArrayOf(CaptureRequest.CONTROL_AF_MODE_OFF),
            FocusTargetSource.DEFAULT_CENTER
        )
        val selectedTap = SingleFrameCaptureController.selectAutoFocusModeForStillCapture(
            intArrayOf(CaptureRequest.CONTROL_AF_MODE_OFF),
            FocusTargetSource.USER_TAP
        )
        assertEquals(null, selectedDefault)
        assertEquals(null, selectedTap)
    }

    @Test
    fun selectAutoFocusModeForStillCapture_returnsNullForNullCharacteristic() {
        val selectedDefault = SingleFrameCaptureController.selectAutoFocusModeForStillCapture(null, FocusTargetSource.DEFAULT_CENTER)
        val selectedTap = SingleFrameCaptureController.selectAutoFocusModeForStillCapture(null, FocusTargetSource.USER_TAP)
        assertEquals(null, selectedDefault)
        assertEquals(null, selectedTap)
    }

    @Test
    fun selectAutoFocusModeForStillCapture_returnsNullForEmptyModes() {
        assertEquals(
            null,
            SingleFrameCaptureController.selectAutoFocusModeForStillCapture(
                intArrayOf(),
                FocusTargetSource.DEFAULT_CENTER
            )
        )
        assertEquals(
            null,
            SingleFrameCaptureController.selectAutoFocusModeForStillCapture(
                intArrayOf(),
                FocusTargetSource.USER_TAP
            )
        )
    }

    @Test
    fun selectAutoFocusModeForStillCapture_macroOnlyReturnsFallbackNull() {
        val selectedDefault = SingleFrameCaptureController.selectAutoFocusModeForStillCapture(
            intArrayOf(CaptureRequest.CONTROL_AF_MODE_MACRO),
            FocusTargetSource.DEFAULT_CENTER
        )
        val selectedTap = SingleFrameCaptureController.selectAutoFocusModeForStillCapture(
            intArrayOf(CaptureRequest.CONTROL_AF_MODE_MACRO),
            FocusTargetSource.USER_TAP
        )
        assertEquals(null, selectedDefault)
        assertEquals(null, selectedTap)
    }

    @Test
    fun shouldTriggerAutoFocus_isTrueOnlyForAuto() {
        assertTrue(SingleFrameCaptureController.shouldTriggerAutoFocus(CaptureRequest.CONTROL_AF_MODE_AUTO))
        assertFalse(SingleFrameCaptureController.shouldTriggerAutoFocus(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
        assertFalse(SingleFrameCaptureController.shouldTriggerAutoFocus(CaptureRequest.CONTROL_AF_MODE_OFF))
        assertFalse(SingleFrameCaptureController.shouldTriggerAutoFocus(null))
    }

    @Test
    fun focusMeteringTarget_userTapDefaults() {
        val tapTarget = FocusMeteringTarget.tap(0.3f, 0.7f)
        assertEquals(0.3f, tapTarget.x, 1e-5f)
        assertEquals(0.7f, tapTarget.y, 1e-5f)
        assertEquals(0.04f, tapTarget.afSize, 1e-5f)
        assertEquals(0.10f, tapTarget.aeSize, 1e-5f)
        assertEquals(1000, tapTarget.afWeight)
        assertEquals(1000, tapTarget.aeWeight)
        assertEquals(FocusTargetSource.USER_TAP, tapTarget.source)
    }

    @Test
    fun isAutoFocusReadyForStillCapture_autoGate_focusedLockedReadyAtFrame2() {
        val autoMode = CaptureRequest.CONTROL_AF_MODE_AUTO
        val locked = CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED

        // Under the AUTO gate of 2 frames, FOCUSED_LOCKED is not ready
        assertFalse(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(0, locked, autoMode))
        assertFalse(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(1, locked, autoMode))

        // At exactly 2 frames (gate boundary), AUTO FOCUSED_LOCKED is ready
        assertTrue(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(2, locked, autoMode))
        assertTrue(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(3, locked, autoMode))
    }

    @Test
    fun isAutoFocusReadyForStillCapture_continuousGate_passiveFocusedFalseBeforeFrame8() {
        val continuousMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        val passiveFocused = CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED

        // PASSIVE_FOCUSED must be rejected at frames 0-7 in CONTINUOUS_PICTURE mode.
        // In a fresh Camera2 session, PASSIVE_FOCUSED in the first ~267 ms may be
        // stale carry-over from the prior CameraX session's lens position.
        assertFalse(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(0, passiveFocused, continuousMode))
        assertFalse(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(1, passiveFocused, continuousMode))
        assertFalse(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(2, passiveFocused, continuousMode))
        assertFalse(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(4, passiveFocused, continuousMode))
        assertFalse(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(7, passiveFocused, continuousMode))

        // At exactly 8 frames (passive gate), PASSIVE_FOCUSED is ready
        assertTrue(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(8, passiveFocused, continuousMode))
        assertTrue(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(9, passiveFocused, continuousMode))
    }

    @Test
    fun isAutoFocusReadyForStillCapture_continuousGate_focusedLockedFalseBeforeFrame8() {
        val continuousMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        val locked = CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED

        // FOCUSED_LOCKED must also be gated by AF_PASSIVE_MIN_FRAMES in
        // CONTINUOUS_PICTURE mode. If a prior session's lock state leaked, it
        // could appear before the HAL ran a real scan cycle.
        assertFalse(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(0, locked, continuousMode))
        assertFalse(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(2, locked, continuousMode))
        assertFalse(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(7, locked, continuousMode))

        // At exactly 8 frames, FOCUSED_LOCKED is ready
        assertTrue(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(8, locked, continuousMode))
    }

    @Test
    fun isAutoFocusReadyForStillCapture_continuousGate_rejectsNonFocusedStatesEvenAfterGate() {
        val continuousMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        val pastGate = 10

        // PASSIVE_UNFOCUSED, ACTIVE_SCAN, and null must never be accepted,
        // even after the passive gate has been met
        assertFalse(
            "PASSIVE_UNFOCUSED must not be ready even after gate",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                pastGate, CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, continuousMode
            )
        )
        assertFalse(
            "ACTIVE_SCAN must not be ready even after gate",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                pastGate, CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN, continuousMode
            )
        )
        assertFalse(
            "Null must not be ready even after gate",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                pastGate, null, continuousMode
            )
        )
    }

    @Test
    fun isAutoFocusReadyForStillCapture_inAutoMode_acceptsOnlyFocusedState() {
        val autoMode = CaptureRequest.CONTROL_AF_MODE_AUTO
        val gateMet = 3

        // Null AF state is not ready in active AF modes
        assertFalse(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(gateMet, null, autoMode))

        // Focused locked is ready (post-trigger focused terminal state)
        assertTrue(
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, autoMode
            )
        )

        // Not-focused locked means the device finished scanning but did not focus;
        // keep waiting until the frame cap instead of capturing a soft close subject.
        assertFalse(
            "NOT_FOCUSED_LOCKED must not be accepted in AUTO mode",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, autoMode
            )
        )

        // Passive states must NOT be accepted in AUTO mode; they are pre-trigger
        // residual states from the warm-up phase
        assertFalse(
            "PASSIVE_FOCUSED must not be accepted in AUTO mode",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED, autoMode
            )
        )
        assertFalse(
            "PASSIVE_UNFOCUSED must not be accepted in AUTO mode",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, autoMode
            )
        )

        // Scanning states must not be accepted
        assertFalse(
            "Inactive AF must not be treated as ready",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_INACTIVE, autoMode
            )
        )
        assertFalse(
            "Active AF scan must not be treated as ready",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN, autoMode
            )
        )
        assertFalse(
            "Passive AF scan must not be treated as ready",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, autoMode
            )
        )
    }

    @Test
    fun isAutoFocusReadyForStillCapture_inContinuousPictureMode_acceptsFocusedStatesOnly() {
        val continuousMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        // Use a frame count past the passive gate (AF_PASSIVE_MIN_FRAMES = 8)
        // to test state-acceptance logic independently of the gate
        val gateMet = 10

        // Null AF state is not ready in active AF modes
        assertFalse(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(gateMet, null, continuousMode))

        // Focused locked is ready
        assertTrue(
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, continuousMode
            )
        )

        // Not-focused locked is a terminal focus failure, not a good capture state.
        // No AF_TRIGGER_START is sent in CONTINUOUS_PICTURE, so this state should
        // not arise. If it does (prior session leak or OEM HAL quirk), waiting
        // for the frame cap is safer than capturing known-failed focus.
        assertFalse(
            "NOT_FOCUSED_LOCKED must not be accepted in CONTINUOUS_PICTURE mode",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, continuousMode
            )
        )

        // Passive focused is accepted in CONTINUOUS_PICTURE mode
        assertTrue(
            "PASSIVE_FOCUSED must be accepted in CONTINUOUS_PICTURE mode",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED, continuousMode
            )
        )

        // Passive unfocused means continuous AF is settled but not sharp yet.
        assertFalse(
            "PASSIVE_UNFOCUSED must not be accepted in CONTINUOUS_PICTURE mode",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, continuousMode
            )
        )

        // Scanning states must not be accepted even in CONTINUOUS_PICTURE mode
        assertFalse(
            "Inactive AF must not be treated as ready",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_INACTIVE, continuousMode
            )
        )
        assertFalse(
            "Active AF scan must not be treated as ready",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN, continuousMode
            )
        )
        assertFalse(
            "Passive AF scan must not be treated as ready",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, continuousMode
            )
        )
    }

    @Test
    fun isAutoFocusReadyForStillCapture_withFixedFocus_returnsTrueImmediately() {
        // Gated count and AF state do not apply to fixed-focus (null afMode) because no AF check is done
        assertTrue(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(0, null, null))
        assertTrue(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(1, null, null))
        assertTrue(SingleFrameCaptureController.isAutoFocusReadyForStillCapture(2, null, null))
        assertTrue(
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                0, CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN, null
            )
        )
    }

    @Test
    fun isAutoFocusReadyForStillCapture_autoRejectsPassiveFocusedAtExactGateBoundary() {
        // PASSIVE_FOCUSED at exactly AF_TRIGGER_MIN_FRAMES (2) must not exit in
        // AUTO mode. This is the close-focus regression vector: pre-trigger warm-up
        // callbacks can carry PASSIVE_FOCUSED from CONTINUOUS_PICTURE residual state.
        assertFalse(
            "PASSIVE_FOCUSED at exactly AF_TRIGGER_MIN_FRAMES must not exit in AUTO mode",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                2, CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                CaptureRequest.CONTROL_AF_MODE_AUTO
            )
        )
    }

    @Test
    fun isAutoFocusReadyForStillCapture_unknownActiveModeFailsClosed() {
        // Unknown active AF modes must return false so capture waits for the
        // bounded frame cap rather than silently accepting unfocused output.
        val macroMode = CaptureRequest.CONTROL_AF_MODE_MACRO
        val edofMode = CaptureRequest.CONTROL_AF_MODE_EDOF
        val gateMet = 3

        assertFalse(
            "MACRO mode must not be treated as immediately ready",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, macroMode
            )
        )
        assertFalse(
            "EDOF mode must not be treated as immediately ready",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                gateMet, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, edofMode
            )
        )
        // Unknown mode at high frame count still fails closed
        assertFalse(
            "Unknown mode at frame 29 must still fail closed",
            SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                29, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, macroMode
            )
        )
    }



    @Test
    fun isAutoFocusReadyForStillCapture_nullAfStateNeverReadyInActiveModes() {
        // Regression guard: null AF state must never be accepted as ready when
        // an active AF mode is set. This prevents premature exit on HALs where
        // null appears during trigger processing.
        val autoMode = CaptureRequest.CONTROL_AF_MODE_AUTO
        val continuousMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE

        for (frame in listOf(0, 1, 2, 3, 5, 10, 20, 29)) {
            assertFalse(
                "Null AF state must not be ready in AUTO mode at frame $frame",
                SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                    frame, null, autoMode
                )
            )
            assertFalse(
                "Null AF state must not be ready in CONTINUOUS_PICTURE mode at frame $frame",
                SingleFrameCaptureController.isAutoFocusReadyForStillCapture(
                    frame, null, continuousMode
                )
            )
        }
    }
}
