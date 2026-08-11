package com.proshot.app.camera

import android.os.Handler
import android.os.HandlerThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented test verifying executor behavior on an active Android HandlerThread.
 */
class Camera2CaptureSessionCreatorInstrumentedTest {

    @Test
    fun testHandlerPosterExecutor_dispatchesOnDesignatedHandlerThread() {
        val handlerThread = HandlerThread("TestHandlerThread")
        handlerThread.start()

        try {
            val handler = Handler(handlerThread.looper)
            val latch = CountDownLatch(1)
            var executedThread: Thread? = null
            var executedThreadName: String? = null
            val executor = HandlerPosterExecutor(
                HandlerPoster { command -> handler.post(command) }
            )

            executor.execute {
                executedThread = Thread.currentThread()
                executedThreadName = Thread.currentThread().name
                latch.countDown()
            }

            val completed = latch.await(2, TimeUnit.SECONDS)
            assertTrue("Executor command timed out", completed)
            assertSame(handlerThread, executedThread)
            assertEquals("TestHandlerThread", executedThreadName)
            assertNotSame(Thread.currentThread(), executedThread)
        } finally {
            handlerThread.quitSafely()
            handlerThread.join(2_000L)
            assertFalse("HandlerThread did not terminate", handlerThread.isAlive)
        }
    }
}
