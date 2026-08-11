package com.proshot.app.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.proshot.app.ui.DirectCamera2PreviewHost
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistentCamera2ProductionInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val permissionState = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        check(permissionState == PackageManager.PERMISSION_GRANTED) {
            "CAMERA permission must be granted before running PersistentCamera2ProductionInstrumentedTest"
        }
    }

    @Test
    fun productionHost_keepsPreviewUpdatingAcrossThreeCorrelatedStillsAndReplacement() =
        runBlocking {
            val sessionCreator = AndroidCamera2CaptureSessionCreator()
            val rollbackController = SingleFrameCaptureController(
                Camera2CaptureResourceOwnerFactory(),
                sessionCreator
            )
            val directController = DirectCamera2PreviewController(
                context,
                PersistentCamera2ResourceOwnerFactory(),
                sessionCreator
            )
            val runtime = CameraCaptureRuntime(
                CameraXPreviewController(context),
                directController,
                rollbackController,
                CaptureCoordinator(rollbackController)
            )
            val hostKey = mutableIntStateOf(0)
            val showHost = mutableStateOf(true)
            val previewUpdates = AtomicInteger(0)
            val hostErrors = ConcurrentLinkedQueue<String>()
            val appliedRenderTransforms = ConcurrentLinkedQueue<TextureViewRenderTransform>()

            composeRule.setContent {
                if (showHost.value) {
                    key(hostKey.intValue) {
                        DirectCamera2PreviewHost(
                            cameraCaptureRuntime = runtime,
                            displayRotationDegrees = 0,
                            onTransformChanged = {},
                            onCameraError = { message -> hostErrors += message },
                            modifier = Modifier.fillMaxSize(),
                            onPreviewFrame = { previewUpdates.incrementAndGet() },
                            onRenderTransformApplied = { transform ->
                                appliedRenderTransforms += transform
                            }
                        )
                    }
                }
            }

            try {
                composeRule.waitUntil(timeoutMillis = 15_000L) {
                    runtime.isPreviewReady.value &&
                        directController.isAttached &&
                        previewUpdates.get() > 0 &&
                        appliedRenderTransforms.isNotEmpty()
                }
                assertTrue(hostErrors.joinToString(), hostErrors.isEmpty())
                val initialConfiguration = checkNotNull(
                    directController.previewConfiguration.value
                )
                val initialRenderTransform = checkNotNull(appliedRenderTransforms.lastOrNull())

                // Matrix/value assertions validate geometry, not rendered pixels. Upright pixels
                // still require the binding owner-run physical portrait/landscape smoke.
                assertEquals(
                    "Portrait display rotation must not reapply sensor orientation",
                    0f,
                    initialRenderTransform.negativeDisplayRotationDegrees,
                    0f
                )
                assertValidCenterCropRender(
                    renderTransform = initialRenderTransform,
                    bufferWidth = initialConfiguration.streamSize.width.toFloat(),
                    bufferHeight = initialConfiguration.streamSize.height.toFloat(),
                    sensorOrientationDegrees = initialConfiguration.sensorOrientationDegrees,
                    displayRotationDegrees = 0
                )

                val timestamps = mutableListOf<Long>()
                repeat(3) { index ->
                    val diagnosticsTracker = FocusLensDiagnosticsTracker()
                    val frame = directController.captureFrame(
                        context = context,
                        diagnosticsTracker = diagnosticsTracker
                    )
                    val snapshot = diagnosticsTracker.snapshot()
                    val resultTimestamp = snapshot.stillCaptureResultTimestamp
                    val copiedTimestamp = snapshot.copiedImageTimestamp

                    assertNotNull("Capture ${index + 1}: result timestamp", resultTimestamp)
                    assertNotNull("Capture ${index + 1}: copied timestamp", copiedTimestamp)
                    assertEquals(resultTimestamp, copiedTimestamp)
                    assertEquals(resultTimestamp, frame.timestamp)
                    assertEquals(ImageFormat.YUV_420_888, frame.format)
                    assertTrue(frame.width > 0)
                    assertTrue(frame.height > 0)
                    assertTrue(frame.planes.all { plane -> plane.data.isNotEmpty() })
                    timestamps += frame.timestamp

                    val previewCountAtCompletion = previewUpdates.get()
                    composeRule.waitUntil(timeoutMillis = 5_000L) {
                        previewUpdates.get() > previewCountAtCompletion
                    }
                }

                assertEquals(3, timestamps.toSet().size)
                assertTrue(timestamps.zipWithNext().all { (earlier, later) -> later > earlier })

                val firstGeneration = checkNotNull(
                    directController.previewConfiguration.value
                ).generation
                val countBeforeReplacement = previewUpdates.get()
                val renderCountBeforeReplacement = appliedRenderTransforms.size
                composeRule.runOnUiThread { hostKey.intValue += 1 }
                composeRule.waitUntil(timeoutMillis = 15_000L) {
                    val replacement = directController.previewConfiguration.value
                    replacement != null &&
                        replacement.generation > firstGeneration &&
                        runtime.isPreviewReady.value &&
                        previewUpdates.get() > countBeforeReplacement &&
                        appliedRenderTransforms.size > renderCountBeforeReplacement
                }
                assertTrue(hostErrors.joinToString(), hostErrors.isEmpty())
                val replacementConfiguration = checkNotNull(
                    directController.previewConfiguration.value
                )
                val replacementRenderTransform = checkNotNull(
                    appliedRenderTransforms.drop(renderCountBeforeReplacement).lastOrNull()
                )
                assertTrue(
                    "Replacement host must receive a fresh render value",
                    replacementRenderTransform !== initialRenderTransform
                )
                assertValidCenterCropRender(
                    renderTransform = replacementRenderTransform,
                    bufferWidth = replacementConfiguration.streamSize.width.toFloat(),
                    bufferHeight = replacementConfiguration.streamSize.height.toFloat(),
                    sensorOrientationDegrees = replacementConfiguration.sensorOrientationDegrees,
                    displayRotationDegrees = 0
                )
            } finally {
                composeRule.runOnUiThread { showHost.value = false }
                composeRule.waitUntil(timeoutMillis = 10_000L) {
                    !directController.isAttached && !runtime.isPreviewReady.value
                }
                assertFalse(directController.isAttached)
            }
        }

    @Test
    fun productionRouteReplacement_transitionsDirectToCameraX_andCompletesRollbackCapture() =
        runBlocking {
            // This proves production replacement ordering and CameraX rollback capture, not a real vendor HAL disconnect.
            val sessionCreator = AndroidCamera2CaptureSessionCreator()
            val rollbackController = SingleFrameCaptureController(
                Camera2CaptureResourceOwnerFactory(),
                sessionCreator
            )
            val directController = DirectCamera2PreviewController(
                context,
                PersistentCamera2ResourceOwnerFactory(),
                sessionCreator
            )
            val cameraXController = CameraXPreviewController(context)
            val runtime = CameraCaptureRuntime(
                cameraXController,
                directController,
                rollbackController,
                CaptureCoordinator(rollbackController)
            )

            val activeRoute = mutableStateOf(CameraOwnershipRoute.PERSISTENT_CAMERA2)
            val showHost = mutableStateOf(true)
            val directPreviewUpdates = AtomicInteger(0)
            val hostErrors = ConcurrentLinkedQueue<String>()
            val cameraXPreviewView = AtomicReference<PreviewView?>()
            val visualContext = AtomicReference<Context?>()

            composeRule.setContent {
                visualContext.set(androidx.compose.ui.platform.LocalContext.current)
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                if (showHost.value) {
                    when (activeRoute.value) {
                        CameraOwnershipRoute.PERSISTENT_CAMERA2 -> {
                            DirectCamera2PreviewHost(
                                cameraCaptureRuntime = runtime,
                                displayRotationDegrees = 0,
                                onTransformChanged = {},
                                onCameraError = { message -> hostErrors += message },
                                modifier = Modifier.fillMaxSize(),
                                onPreviewFrame = { directPreviewUpdates.incrementAndGet() }
                            )
                        }
                        CameraOwnershipRoute.CAMERA_X_HANDOFF -> {
                            val previewView = androidx.compose.runtime.remember {
                                PreviewView(context).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    cameraXPreviewView.set(this)
                                }
                            }
                            androidx.compose.runtime.LaunchedEffect(runtime, lifecycleOwner, previewView) {
                                var gen: Long? = null
                                try {
                                    gen = runtime.attach(
                                        lifecycleOwner,
                                        previewView
                                    )
                                    if (gen != null) {
                                        kotlinx.coroutines.awaitCancellation()
                                    }
                                } finally {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                        gen?.let { runtime.detach(it) }
                                    }
                                }
                            }
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { previewView },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            try {
                composeRule.waitUntil(timeoutMillis = 15_000L) {
                    runtime.isPreviewReady.value &&
                        directController.isAttached &&
                        directPreviewUpdates.get() > 0
                }
                assertTrue(hostErrors.joinToString(), hostErrors.isEmpty())

                composeRule.runOnUiThread {
                    activeRoute.value = CameraOwnershipRoute.CAMERA_X_HANDOFF
                }

                composeRule.waitUntil(timeoutMillis = 15_000L) {
                    val previewView = cameraXPreviewView.get()
                    !directController.isAttached &&
                        !directController.hasClosingOwners &&
                        runtime.isPreviewReady.value &&
                        previewView?.previewStreamState?.value ==
                        PreviewView.StreamState.STREAMING
                }
                assertFalse("Direct controller must be unattached before CameraX is ready", directController.isAttached)
                assertFalse(
                    "Direct controller closing-owner barrier must be settled before CameraX is ready",
                    directController.hasClosingOwners
                )
                assertTrue("CameraX preview must be ready", runtime.isPreviewReady.value)
                assertEquals(
                    "CameraX PreviewView must be streaming before rollback capture",
                    PreviewView.StreamState.STREAMING,
                    checkNotNull(cameraXPreviewView.get()).previewStreamState.value
                )

                val diagnosticsTracker = FocusLensDiagnosticsTracker()
                val captureContext = requireNotNull(visualContext.get()) {
                    "Compose host visual context must be available before rollback capture"
                }
                val captureResult = runtime.capture(
                    context = captureContext,
                    lookProfile = com.proshot.app.processing.style.LookProfileCatalog.ProShotNatural,
                    isDebug = true,
                    tracker = null,
                    diagnosticsTracker = diagnosticsTracker,
                    statusCallback = {}
                )

                val captureFailureDetails = (captureResult as? CaptureResult.Failure)?.let { failure ->
                    "; message=${failure.message}; cause=${failure.cause}"
                }.orEmpty()
                assertTrue(
                    "Rollback capture must succeed$captureFailureDetails",
                    captureResult is CaptureResult.Success
                )
                val snapshot = diagnosticsTracker.snapshot()
                val resultTimestamp = snapshot.stillCaptureResultTimestamp
                val copiedTimestamp = snapshot.copiedImageTimestamp
                assertNotNull("Result timestamp must be present", resultTimestamp)
                assertNotNull("Copied timestamp must be present", copiedTimestamp)
                assertEquals("Timestamps must match exactly", resultTimestamp, copiedTimestamp)

                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    runtime.isPreviewReady.value &&
                        cameraXPreviewView.get()?.previewStreamState?.value ==
                        PreviewView.StreamState.STREAMING
                }
                assertTrue(runtime.isPreviewReady.value)
                assertEquals(
                    "CameraX PreviewView must stream again after capture/rebind",
                    PreviewView.StreamState.STREAMING,
                    checkNotNull(cameraXPreviewView.get()).previewStreamState.value
                )
            } finally {
                composeRule.runOnUiThread { showHost.value = false }
                composeRule.waitUntil(timeoutMillis = 10_000L) {
                    !directController.isAttached &&
                        !directController.hasClosingOwners &&
                        !runtime.isPreviewReady.value
                }
                assertFalse(directController.isAttached)
                assertFalse(directController.hasClosingOwners)
                assertFalse(runtime.isPreviewReady.value)
            }
        }

    private fun assertValidCenterCropRender(
        renderTransform: TextureViewRenderTransform,
        bufferWidth: Float,
        bufferHeight: Float,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int
    ) {
        assertTrue(renderTransform.correctionScaleX.isFinite())
        assertTrue(renderTransform.correctionScaleY.isFinite())
        assertTrue(renderTransform.correctionScaleX > 0f)
        assertTrue(renderTransform.correctionScaleY > 0f)

        val viewWidth = renderTransform.pivotX * 2f
        val viewHeight = renderTransform.pivotY * 2f
        assertTrue(viewWidth > 0f && viewWidth.isFinite())
        assertTrue(viewHeight > 0f && viewHeight.isFinite())

        val sensorAxesAreSwapped = normalizedDegrees(sensorOrientationDegrees) in setOf(90, 270)
        val textureOrientedWidth = if (sensorAxesAreSwapped) bufferHeight else bufferWidth
        val textureOrientedHeight = if (sensorAxesAreSwapped) bufferWidth else bufferHeight
        val appliedUniformScaleX =
            (viewWidth / textureOrientedWidth) * renderTransform.correctionScaleX
        val appliedUniformScaleY =
            (viewHeight / textureOrientedHeight) * renderTransform.correctionScaleY
        assertEquals(appliedUniformScaleX, appliedUniformScaleY, GEOMETRY_TOLERANCE)

        val relativeRotation = DirectPreviewGeometry.computeRelativeRotation(
            sensorOrientationDegrees,
            displayRotationDegrees
        )
        val relativeAxesAreSwapped = relativeRotation == 90 || relativeRotation == 270
        val displayedBufferWidth = if (relativeAxesAreSwapped) bufferHeight else bufferWidth
        val displayedBufferHeight = if (relativeAxesAreSwapped) bufferWidth else bufferHeight
        assertTrue(
            "Center-cropped content must cover the TextureView width",
            displayedBufferWidth * appliedUniformScaleX + GEOMETRY_TOLERANCE >= viewWidth
        )
        assertTrue(
            "Center-cropped content must cover the TextureView height",
            displayedBufferHeight * appliedUniformScaleY + GEOMETRY_TOLERANCE >= viewHeight
        )
    }

    private fun normalizedDegrees(rotationDegrees: Int): Int {
        return ((rotationDegrees % 360) + 360) % 360
    }

    private companion object {
        const val GEOMETRY_TOLERANCE = 0.01f
    }
}
