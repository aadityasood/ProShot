package com.proshot.app.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SingleFrameCaptureControllerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var controller: SingleFrameCaptureController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val permissionState = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        check(permissionState == PackageManager.PERMISSION_GRANTED) {
            "CAMERA permission must be granted before running SingleFrameCaptureControllerInstrumentedTest"
        }
        val resourceOwnerFactory = Camera2CaptureResourceOwnerFactory()
        val sessionCreator = AndroidCamera2CaptureSessionCreator()
        controller = SingleFrameCaptureController(resourceOwnerFactory, sessionCreator)
    }

    @Test
    fun captureSingleFrame_returnsOnlyExactlyTimestampCorrelatedFramesAcrossSequentialCaptures() = runBlocking {
        val timestamps = mutableListOf<Long>()

        for (i in 1..3) {
            val diagnosticsTracker = FocusLensDiagnosticsTracker()
            val frame = controller.captureSingleFrame(
                context = context,
                diagnosticsTracker = diagnosticsTracker
            )

            val snapshot = diagnosticsTracker.snapshot()
            val resultTs = snapshot.stillCaptureResultTimestamp
            val copiedTs = snapshot.copiedImageTimestamp

            assertNotNull("Iteration $i: stillCaptureResultTimestamp must not be null", resultTs)
            assertNotNull("Iteration $i: copiedImageTimestamp must not be null", copiedTs)

            assertEquals("Iteration $i: result timestamp must equal copied timestamp", resultTs, copiedTs)
            assertEquals("Iteration $i: frame timestamp must equal result timestamp", resultTs, frame.timestamp)

            assertEquals("Iteration $i: frame format must be YUV_420_888", ImageFormat.YUV_420_888, frame.format)
            assertTrue("Iteration $i: frame width must be positive", frame.width > 0)
            assertTrue("Iteration $i: frame height must be positive", frame.height > 0)
            assertTrue("Iteration $i: frame planes must not be empty", frame.planes.isNotEmpty())

            for (pIndex in frame.planes.indices) {
                assertTrue(
                    "Iteration $i plane $pIndex data must not be empty",
                    frame.planes[pIndex].data.isNotEmpty()
                )
            }

            timestamps.add(frame.timestamp)
        }

        assertTrue("Timestamps must be distinct", timestamps.toSet().size == 3)
        assertTrue("Timestamp 2 must be greater than timestamp 1", timestamps[1] > timestamps[0])
        assertTrue("Timestamp 3 must be greater than timestamp 2", timestamps[2] > timestamps[1])
    }
}
