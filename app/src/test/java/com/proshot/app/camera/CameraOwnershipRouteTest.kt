package com.proshot.app.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOwnershipRouteTest {

    @Test
    fun select_inDebuggableBuild_selectsPersistentCamera2Route() {
        val route = CameraOwnershipRoutePolicy.select(isDebuggable = true)
        assertEquals(CameraOwnershipRoute.PERSISTENT_CAMERA2, route)
    }

    @Test
    fun select_inReleaseBuild_selectsCameraXHandoffRoute() {
        val route = CameraOwnershipRoutePolicy.select(isDebuggable = false)
        assertEquals(CameraOwnershipRoute.CAMERA_X_HANDOFF, route)
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
