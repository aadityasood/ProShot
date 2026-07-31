package com.proshot.app.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private class ProbeTextureHostState(val generation: Int) {
    val frameTracker = ProbePreviewFrameTracker()
    val surfaceAvailable = CountDownLatch(1)
    val exactSurfaceDestroyed = CountDownLatch(1)
    val rejectedUpdateCount = AtomicInteger(0)

    @Volatile
    var surfaceTexture: SurfaceTexture? = null
        private set

    @Volatile
    var destroyedSurfaceTexture: SurfaceTexture? = null
        private set

    @Volatile
    var callbackFailure: Throwable? = null
        private set

    private val lock = Any()

    fun onAvailable(surface: SurfaceTexture, activeGeneration: Int) {
        synchronized(lock) {
            if (activeGeneration != generation) {
                recordFailureLocked(
                    IllegalStateException(
                        "Obsolete host generation $generation received surface availability while active generation was $activeGeneration"
                    )
                )
                surfaceAvailable.countDown()
                return
            }
            val existing = surfaceTexture
            if (existing != null && existing !== surface) {
                recordFailureLocked(
                    IllegalStateException("Host generation $generation received two SurfaceTexture identities")
                )
            } else {
                surfaceTexture = surface
            }
            surfaceAvailable.countDown()
        }
    }

    fun onUpdated(surface: SurfaceTexture, activeGeneration: Int) {
        synchronized(lock) {
            if (activeGeneration != generation) {
                rejectedUpdateCount.incrementAndGet()
                return
            }
            if (surfaceTexture !== surface) {
                recordFailureLocked(
                    IllegalStateException(
                        "Host generation $generation received an update from an obsolete SurfaceTexture"
                    )
                )
                return
            }
        }
        frameTracker.onFrameUpdated()
    }

    fun onDestroyed(surface: SurfaceTexture): Boolean {
        synchronized(lock) {
            if (surfaceTexture !== surface) {
                recordFailureLocked(
                    IllegalStateException(
                        "Host generation $generation destroyed an unexpected SurfaceTexture"
                    )
                )
            } else {
                destroyedSurfaceTexture = surface
                exactSurfaceDestroyed.countDown()
            }
        }
        return true
    }

    private fun recordFailureLocked(failure: Throwable) {
        if (callbackFailure == null) {
            callbackFailure = failure
        }
    }
}

@Composable
private fun ProbeTextureViewHost(
    hostState: ProbeTextureHostState,
    activeGeneration: AtomicInteger,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        hostState.onAvailable(surface, activeGeneration.get())
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {}

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        return hostState.onDestroyed(surface)
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        hostState.onUpdated(surface, activeGeneration.get())
                    }
                }
            }
        },
        modifier = modifier
    )
}

/**
 * Instrumented test suite proving Compose-hosted two-surface Camera2 session feasibility and replacement.
 */
@RunWith(AndroidJUnit4::class)
class Camera2PersistentSessionProbeInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permissionCheck = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        )
        assertTrue(
            "Target app must already hold CAMERA permission; this probe does not adopt shell identity, grant, or skip",
            permissionCheck == PackageManager.PERMISSION_GRANTED
        )
    }

    @Test
    fun composeTextureView_twoSurfaceSession_keepsPreviewUpdatingAndCorrelatesThreeYuvStills() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activeGeneration = AtomicInteger(1)
        val hostState = ProbeTextureHostState(generation = 1)

        composeTestRule.setContent {
            ProbeTextureViewHost(
                hostState = hostState,
                activeGeneration = activeGeneration,
                modifier = Modifier.fillMaxSize()
            )
        }

        assertTrue(
            "Timed out waiting for TextureView surface creation",
            hostState.surfaceAvailable.await(5, TimeUnit.SECONDS)
        )
        assertNull("TextureView host callback failed", hostState.callbackFailure)
        val surfaceTexture = hostState.surfaceTexture
        assertNotNull("SurfaceTexture should not be null", surfaceTexture)

        val probe = Camera2PersistentSessionProbe(runId = "SingleRun")
        val summary = probe.runProbe(
            context = context,
            surfaceTexture = surfaceTexture!!,
            frameTracker = hostState.frameTracker,
            stillCount = 3
        )

        assertCleanTeardown(probe, summary)
        assertDeviceLocalSummary(context, summary, expectedStillCount = 3)
    }

    @Test
    fun composeTextureView_replacementAfterFullClose_reopensWithoutStaleCompletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activeGeneration = AtomicInteger(1)
        val firstHost = ProbeTextureHostState(generation = 1)
        var currentHost by mutableStateOf(firstHost)

        composeTestRule.setContent {
            key(currentHost.generation) {
                ProbeTextureViewHost(
                    hostState = currentHost,
                    activeGeneration = activeGeneration,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        assertTrue(
            "Timed out waiting for Surface 1 creation",
            firstHost.surfaceAvailable.await(5, TimeUnit.SECONDS)
        )
        assertNull("Surface 1 host callback failed", firstHost.callbackFailure)
        val surface1 = firstHost.surfaceTexture
        assertNotNull("Surface 1 must not be null", surface1)

        val probe1 = Camera2PersistentSessionProbe(runId = "Run1")
        val summary1 = probe1.runProbe(
            context = context,
            surfaceTexture = surface1!!,
            frameTracker = firstHost.frameTracker,
            stillCount = 1
        )
        assertCleanTeardown(probe1, summary1)
        assertDeviceLocalSummary(context, summary1, expectedStillCount = 1)

        val firstCompact = summary1.toCompactString()
        val secondHost = ProbeTextureHostState(generation = 2)
        activeGeneration.set(2)

        composeTestRule.runOnIdle {
            currentHost = secondHost
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "Timed out waiting for exact Surface 1 destruction | $firstCompact",
            firstHost.exactSurfaceDestroyed.await(5, TimeUnit.SECONDS)
        )
        assertSame(
            "Destroyed SurfaceTexture must be the exact Surface 1 identity | $firstCompact",
            surface1,
            firstHost.destroyedSurfaceTexture
        )
        assertNull(
            "Surface 1 host callback failed during replacement | $firstCompact",
            firstHost.callbackFailure
        )

        assertTrue(
            "Timed out waiting for Surface 2 creation after replacement | $firstCompact",
            secondHost.surfaceAvailable.await(5, TimeUnit.SECONDS)
        )
        assertNull(
            "Surface 2 host callback failed | $firstCompact",
            secondHost.callbackFailure
        )
        val surface2 = secondHost.surfaceTexture
        assertNotNull("Surface 2 must not be null | $firstCompact", surface2)
        assertNotSame(
            "Surface 2 must use a distinct SurfaceTexture identity | $firstCompact",
            surface1,
            surface2
        )
        assertEquals(
            "Run-1 callbacks must not increment the run-2 tracker before run 2 opens | $firstCompact",
            0,
            secondHost.frameTracker.currentFrameCount()
        )
        val firstFramesAtDestruction = firstHost.frameTracker.currentFrameCount()

        val probe2 = Camera2PersistentSessionProbe(runId = "Run2")
        val summary2 = probe2.runProbe(
            context = context,
            surfaceTexture = surface2!!,
            frameTracker = secondHost.frameTracker,
            stillCount = 1
        )
        assertCleanTeardown(probe2, summary2)
        assertDeviceLocalSummary(context, summary2, expectedStillCount = 1)

        val combinedEvidence =
            "$firstCompact | ${summary2.toCompactString()} | RejectedRun1Updates: ${firstHost.rejectedUpdateCount.get()}"
        assertEquals(
            "Obsolete run-1 callbacks must not update the run-1 tracker after exact destruction | $combinedEvidence",
            firstFramesAtDestruction,
            firstHost.frameTracker.currentFrameCount()
        )
        assertNull(
            "Surface 2 host callback failed during run 2 | $combinedEvidence",
            secondHost.callbackFailure
        )
    }

    private fun assertCleanTeardown(
        probe: Camera2PersistentSessionProbe,
        summary: Camera2ProbeRunSummary
    ) {
        val compact = summary.toCompactString()
        val teardown = probe.teardownEvidence()
        val evidence = "$compact | ${teardown.toCompactString()}"
        assertTrue(
            "Successful run must have delivered a CameraDevice | $evidence",
            teardown.cameraDeviceDelivered
        )
        assertTrue(
            "Exact delivered CameraDevice must acknowledge onClosed before thread termination | $evidence",
            teardown.cameraDeviceCloseAcknowledged
        )
        assertTrue(
            "Successful run must have delivered a configured CameraCaptureSession | $evidence",
            teardown.captureSessionDelivered
        )
        assertTrue("Callback thread must have started | $evidence", teardown.callbackThreadStarted)
        assertTrue(
            "Callback thread must be terminated after bounded join | $evidence",
            teardown.callbackThreadTerminated
        )
        assertTrue("Probe teardown must be clean | $evidence", teardown.isClean)
    }

    private fun assertDeviceLocalSummary(
        context: Context,
        summary: Camera2ProbeRunSummary,
        expectedStillCount: Int
    ) {
        val compact = summary.toCompactString()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        assertNotNull("CameraManager must be available | $compact", manager)
        val cameraManager = manager!!

        assertTrue(
            "Selected camera ID must be a current framework camera | $compact",
            summary.cameraId in cameraManager.cameraIdList
        )
        val characteristics = cameraManager.getCameraCharacteristics(summary.cameraId)
        assertEquals(
            "Selected camera must be LENS_FACING_BACK | $compact",
            CameraCharacteristics.LENS_FACING_BACK,
            characteristics.get(CameraCharacteristics.LENS_FACING)
        )
        assertEquals(
            "SDK evidence must match the attached device | $compact",
            Build.VERSION.SDK_INT,
            summary.sdkInt
        )
        assertEquals(
            "Hardware-level evidence must match selected camera characteristics | $compact",
            Camera2PersistentSessionProbe.mapHardwareLevel(
                characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ),
            summary.hardwareLevel
        )

        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        assertNotNull("Selected camera must expose a stream configuration map | $compact", streamMap)
        val previewSizes = streamMap!!.getOutputSizes(SurfaceTexture::class.java)
            ?: emptyArray<Size>()
        val yuvSizes = streamMap.getOutputSizes(ImageFormat.YUV_420_888)
            ?: emptyArray<Size>()
        assertTrue("Supported preview output list must be non-empty | $compact", previewSizes.isNotEmpty())
        assertTrue("Supported YUV output list must be non-empty | $compact", yuvSizes.isNotEmpty())
        assertTrue(
            "Selected preview size must be framework-supported | $compact",
            summary.previewSize in previewSizes
        )
        assertTrue(
            "Selected YUV size must be framework-supported | $compact",
            summary.yuvSize in yuvSizes
        )
        val expectedPair = Camera2PersistentSessionProbe.selectSupportedOutputPair(
            previewSizes,
            yuvSizes
        )
        assertEquals(
            "Preview selection must use the deterministic nearest supported size | $compact",
            expectedPair.first,
            summary.previewSize
        )
        assertEquals(
            "YUV selection must use the deterministic nearest supported size | $compact",
            expectedPair.second,
            summary.yuvSize
        )

        assertTrue(
            "Initial preview evidence must contain at least three updates | $compact",
            summary.previewFramesBeforeFirstStill >= 3
        )
        assertEquals(
            "Post-correlation preview evidence count must match still count | $compact",
            expectedStillCount,
            summary.previewFramesAfterStills.size
        )
        summary.previewFramesAfterStills.forEachIndexed { index, count ->
            assertTrue(
                "Still ${index + 1} must have a strictly later preview update | $compact",
                count > 0
            )
        }
        assertEquals(
            "Exact correlation count must match submitted still count | $compact",
            expectedStillCount,
            summary.correlatedStillTimestampsNs.size
        )
        summary.correlatedStillTimestampsNs.forEachIndexed { index, timestamp ->
            assertTrue(
                "Still ${index + 1} correlated timestamp must be positive | $compact",
                timestamp > 0L
            )
        }
        assertEquals(
            "Every sequential still must have a distinct correlated timestamp | $compact",
            expectedStillCount,
            summary.correlatedStillTimestampsNs.toSet().size
        )
    }
}
