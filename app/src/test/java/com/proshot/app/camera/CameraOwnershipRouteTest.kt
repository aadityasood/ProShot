package com.proshot.app.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOwnershipRouteTest {

    @Test
    fun parse_nullOrEmpty_returnsPending() {
        assertEquals(CameraOwnershipRouteState.PENDING, CameraOwnershipRouteState.parse(null))
        assertEquals(CameraOwnershipRouteState.PENDING, CameraOwnershipRouteState.parse(""))
    }

    @Test
    fun parse_validName_returnsMatchingState() {
        assertEquals(CameraOwnershipRouteState.DIRECT, CameraOwnershipRouteState.parse("DIRECT"))
        assertEquals(CameraOwnershipRouteState.INITIAL_CAMERAX, CameraOwnershipRouteState.parse("INITIAL_CAMERAX"))
        assertEquals(CameraOwnershipRouteState.FALLBACK_CAMERAX, CameraOwnershipRouteState.parse("FALLBACK_CAMERAX"))
    }

    @Test
    fun parse_unknownOrCorruptName_returnsPending() {
        assertEquals(CameraOwnershipRouteState.PENDING, CameraOwnershipRouteState.parse("INVALID_STATE"))
        assertEquals(CameraOwnershipRouteState.PENDING, CameraOwnershipRouteState.parse("12345"))
    }

    @Test
    fun selectInitialState_releaseAlwaysSelectsInitialCameraX() {
        val state = CameraOwnershipRoutePolicy.selectInitialState(
            isDebuggable = false,
            cameraAvailable = true,
            yuvCaptureSupported = true
        )
        assertEquals(CameraOwnershipRouteState.INITIAL_CAMERAX, state)
        assertEquals(CameraOwnershipRoute.CAMERA_X_HANDOFF, state.route)
    }

    @Test
    fun selectInitialState_debugWithCameraAvailableAndYuv_selectsDirect() {
        val state = CameraOwnershipRoutePolicy.selectInitialState(
            isDebuggable = true,
            cameraAvailable = true,
            yuvCaptureSupported = true
        )
        assertEquals(CameraOwnershipRouteState.DIRECT, state)
        assertEquals(CameraOwnershipRoute.PERSISTENT_CAMERA2, state.route)
    }

    @Test
    fun selectInitialState_debugWithoutCameraAvailable_selectsInitialCameraX() {
        val state = CameraOwnershipRoutePolicy.selectInitialState(
            isDebuggable = true,
            cameraAvailable = false,
            yuvCaptureSupported = true
        )
        assertEquals(CameraOwnershipRouteState.INITIAL_CAMERAX, state)
    }

    @Test
    fun selectInitialState_debugWithoutYuvSupport_selectsInitialCameraX() {
        val state = CameraOwnershipRoutePolicy.selectInitialState(
            isDebuggable = true,
            cameraAvailable = true,
            yuvCaptureSupported = false
        )
        assertEquals(CameraOwnershipRouteState.INITIAL_CAMERAX, state)
    }

    @Test
    fun failureKinds_verifyExactFallbackEligibility() {
        assertTrue(DirectCamera2FailureKind.UNSUPPORTED_CONFIGURATION.isEligibleForFallback)
        assertTrue(DirectCamera2FailureKind.CAMERA_DEVICE_OR_OPEN.isEligibleForFallback)
        assertTrue(DirectCamera2FailureKind.CAMERA_SESSION.isEligibleForFallback)
        assertTrue(DirectCamera2FailureKind.TERMINAL_CAPTURE_OR_REPEATING.isEligibleForFallback)

        assertFalse(DirectCamera2FailureKind.PERMISSION_OR_SECURITY.isEligibleForFallback)
        assertFalse(DirectCamera2FailureKind.OWNER_TERMINAL_BARRIER.isEligibleForFallback)
        assertFalse(DirectCamera2FailureKind.LIFECYCLE_OR_SUPERSESSION.isEligibleForFallback)
    }

    @Test
    fun reduceDirectFailure_eligibleDirectFailure_transitionsToFallbackCameraX() {
        val failure = DirectCamera2Failure(
            kind = DirectCamera2FailureKind.CAMERA_SESSION,
            cause = IllegalStateException("Session failure")
        )

        val nextState = CameraOwnershipRoutePolicy.reduceDirectFailure(
            currentState = CameraOwnershipRouteState.DIRECT,
            failure = failure
        )

        assertEquals(CameraOwnershipRouteState.FALLBACK_CAMERAX, nextState)
        assertEquals(CameraOwnershipRoute.CAMERA_X_HANDOFF, nextState.route)
    }

    @Test
    fun reduceDirectFailure_duplicateOrStaleFailureInCameraXState_isNoOp() {
        val failure = DirectCamera2Failure(
            kind = DirectCamera2FailureKind.CAMERA_DEVICE_OR_OPEN,
            cause = IllegalStateException("Open failure")
        )

        val afterFallback = CameraOwnershipRoutePolicy.reduceDirectFailure(
            currentState = CameraOwnershipRouteState.FALLBACK_CAMERAX,
            failure = failure
        )
        val afterInitial = CameraOwnershipRoutePolicy.reduceDirectFailure(
            currentState = CameraOwnershipRouteState.INITIAL_CAMERAX,
            failure = failure
        )

        assertEquals(CameraOwnershipRouteState.FALLBACK_CAMERAX, afterFallback)
        assertEquals(CameraOwnershipRouteState.INITIAL_CAMERAX, afterInitial)
    }

    @Test
    fun reduceDirectFailure_ineligibleFailureKinds_doNotFallback() {
        val ineligibleKinds = listOf(
            DirectCamera2FailureKind.PERMISSION_OR_SECURITY,
            DirectCamera2FailureKind.OWNER_TERMINAL_BARRIER,
            DirectCamera2FailureKind.LIFECYCLE_OR_SUPERSESSION
        )

        ineligibleKinds.forEach { kind ->
            val failure = DirectCamera2Failure(kind = kind, cause = IllegalStateException("Test"))
            val nextState = CameraOwnershipRoutePolicy.reduceDirectFailure(
                currentState = CameraOwnershipRouteState.DIRECT,
                failure = failure
            )
            assertEquals("Kind $kind must not fallback", CameraOwnershipRouteState.DIRECT, nextState)
        }
    }

    @Test
    fun applyRetainedFallback_coversEveryLatchAndRouteStateCombination() {
        val expectedWhenMandatory = mapOf(
            CameraOwnershipRouteState.PENDING to
                CameraOwnershipRouteState.FALLBACK_CAMERAX,
            CameraOwnershipRouteState.DIRECT to
                CameraOwnershipRouteState.FALLBACK_CAMERAX,
            CameraOwnershipRouteState.INITIAL_CAMERAX to
                CameraOwnershipRouteState.INITIAL_CAMERAX,
            CameraOwnershipRouteState.FALLBACK_CAMERAX to
                CameraOwnershipRouteState.FALLBACK_CAMERAX
        )

        CameraOwnershipRouteState.entries.forEach { state ->
            assertEquals(
                state,
                CameraOwnershipRoutePolicy.applyRetainedFallback(
                    currentState = state,
                    isFallbackMandatory = false
                )
            )
            assertEquals(
                expectedWhenMandatory.getValue(state),
                CameraOwnershipRoutePolicy.applyRetainedFallback(
                    currentState = state,
                    isFallbackMandatory = true
                )
            )
        }
    }

    @Test
    fun requiredBackCamera_selectsBackCamera() {
        val facing = mapOf(
            "front" to CameraMetadata.LENS_FACING_FRONT,
            "back" to CameraMetadata.LENS_FACING_BACK
        )

        val selected = selectRequiredBackCameraId(listOf("front", "back"), facing::get)

        assertEquals("back", selected)
    }

    @Test
    fun requiredBackCamera_preservesStableFirstBackOrdering() {
        val facing = mapOf(
            "back-wide" to CameraMetadata.LENS_FACING_BACK,
            "back-tele" to CameraMetadata.LENS_FACING_BACK
        )

        val selected = selectRequiredBackCameraId(
            listOf("back-wide", "back-tele"),
            facing::get
        )

        assertEquals("back-wide", selected)
    }

    @Test
    fun requiredBackCamera_emptyListFailsAsUnsupportedHardware() {
        val failure = runCatching {
            selectRequiredBackCameraId(emptyList()) { null }
        }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
        assertEquals(
            "Persistent Camera2 preview requires a physical back camera",
            failure?.message
        )
    }

    @Test
    fun requiredBackCamera_nonEmptyListWithoutBackFailsInsteadOfFallingBack() {
        val failure = runCatching {
            selectRequiredBackCameraId(listOf("front", "external")) { cameraId ->
                when (cameraId) {
                    "front" -> CameraMetadata.LENS_FACING_FRONT
                    else -> CameraMetadata.LENS_FACING_EXTERNAL
                }
            }
        }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
        assertEquals(
            "Persistent Camera2 preview requires a physical back camera",
            failure?.message
        )
    }
}
