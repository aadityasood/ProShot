package com.proshot.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.RejectedExecutionException

class Camera2CaptureSessionCreatorTest {

    @Test
    fun selectCamera2SessionApiPolicy_onSdk26_selectsLegacyPolicy() {
        assertEquals(
            Camera2SessionApiPolicy.LEGACY_HANDLER,
            selectCamera2SessionApiPolicy(26)
        )
    }

    @Test
    fun selectCamera2SessionApiPolicy_onSdk27_selectsLegacyPolicy() {
        assertEquals(
            Camera2SessionApiPolicy.LEGACY_HANDLER,
            selectCamera2SessionApiPolicy(27)
        )
    }

    @Test
    fun selectCamera2SessionApiPolicy_onSdk28_selectsModernPolicy() {
        assertEquals(
            Camera2SessionApiPolicy.MODERN_CONFIGURATION,
            selectCamera2SessionApiPolicy(28)
        )
    }

    @Test
    fun selectCamera2SessionApiPolicy_onHigherSdk_selectsModernPolicy() {
        assertEquals(
            Camera2SessionApiPolicy.MODERN_CONFIGURATION,
            selectCamera2SessionApiPolicy(35)
        )
    }

    @Test
    fun handlerPosterExecutor_submitsCommandExactlyOnce() {
        var postCount = 0
        var executionCount = 0
        val poster = HandlerPoster { command ->
            postCount++
            command.run()
            true
        }
        val executor = HandlerPosterExecutor(poster)
        executor.execute { executionCount++ }

        assertEquals(1, postCount)
        assertEquals(1, executionCount)
    }

    @Test
    fun handlerPosterExecutor_onRejectedPost_throwsRejectedExecutionException() {
        var postCount = 0
        var executionCount = 0
        val poster = HandlerPoster {
            postCount++
            false
        }
        val executor = HandlerPosterExecutor(poster)
        var exceptionThrown = false

        try {
            executor.execute { executionCount++ }
        } catch (e: RejectedExecutionException) {
            exceptionThrown = true
        }

        assertTrue(exceptionThrown)
        assertEquals(1, postCount)
        assertEquals(0, executionCount)
    }
}
