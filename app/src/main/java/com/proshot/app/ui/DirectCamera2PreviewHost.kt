package com.proshot.app.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.proshot.app.camera.CameraCaptureRuntime
import com.proshot.app.camera.DirectPreviewGeometry
import com.proshot.app.camera.DirectPreviewTransform
import com.proshot.app.camera.TextureViewRenderTransform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/** Compose-owned TextureView host for the direct Camera2 preview route. */
@Composable
internal fun DirectCamera2PreviewHost(
    cameraCaptureRuntime: CameraCaptureRuntime,
    displayRotationDegrees: Int,
    onTransformChanged: (DirectPreviewTransform?) -> Unit,
    onCameraError: (String) -> Unit,
    onDirectRouteFailure: (com.proshot.app.camera.DirectCamera2Failure) -> Unit = {},
    modifier: Modifier = Modifier,
    onPreviewFrame: () -> Unit = {},
    onRenderTransformApplied: (TextureViewRenderTransform) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val textureView = remember(context) { TextureView(context) }
    val configuration by cameraCaptureRuntime.directPreviewConfiguration.collectAsState()
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    val currentOnDirectRouteFailure by rememberUpdatedState(onDirectRouteFailure)
    val currentOnTransformChanged by rememberUpdatedState(onTransformChanged)
    val currentOnPreviewFrame by rememberUpdatedState(onPreviewFrame)
    val currentOnRenderTransformApplied by rememberUpdatedState(onRenderTransformApplied)

    var availableSurfaceTexture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var activeGeneration by remember { mutableStateOf<Long?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var lifecycleStarted by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        )
    }

    DisposableEffect(lifecycleOwner, textureView, cameraCaptureRuntime) {
        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                viewSize = IntSize(width, height)
                availableSurfaceTexture = surface
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                if (availableSurfaceTexture === surface) {
                    viewSize = IntSize(width, height)
                }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                cameraCaptureRuntime.detachDirectSurfaceSync(surface, activeGeneration)
                if (availableSurfaceTexture === surface) {
                    availableSurfaceTexture = null
                }
                activeGeneration = null
                currentOnTransformChanged(null)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (availableSurfaceTexture === surface) {
                    currentOnPreviewFrame()
                }
            }
        }
        textureView.surfaceTextureListener = listener
        if (textureView.isAvailable) {
            textureView.surfaceTexture?.let { surface ->
                viewSize = IntSize(textureView.width, textureView.height)
                availableSurfaceTexture = surface
            }
        }

        val lifecycleObserver = LifecycleEventObserver { _, _ ->
            val isStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(
                Lifecycle.State.STARTED
            )
            if (!isStarted && lifecycleStarted) {
                availableSurfaceTexture?.let { surface ->
                    cameraCaptureRuntime.detachDirectSurfaceSync(
                        surface,
                        activeGeneration
                    )
                }
                activeGeneration = null
                currentOnTransformChanged(null)
            }
            lifecycleStarted = isStarted
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            availableSurfaceTexture?.let { surface ->
                cameraCaptureRuntime.detachDirectSurfaceSync(surface, activeGeneration)
            }
            activeGeneration = null
            currentOnTransformChanged(null)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            textureView.surfaceTextureListener = null
        }
    }

    val currentSurface = availableSurfaceTexture
    LaunchedEffect(
        cameraCaptureRuntime,
        lifecycleOwner,
        currentSurface,
        lifecycleStarted
    ) {
        if (currentSurface == null || !lifecycleStarted) return@LaunchedEffect

        var generation: Long? = null
        try {
            val attachedGeneration = cameraCaptureRuntime.attachDirect(
                surfaceTexture = currentSurface,
                width = viewSize.width,
                height = viewSize.height,
                onGenerationReserved = { reservedGeneration ->
                    generation = reservedGeneration
                    activeGeneration = reservedGeneration
                },
                onTerminalError = { failure ->
                    textureView.post {
                        currentOnDirectRouteFailure(failure)
                    }
                }
            )
            if (attachedGeneration != null) {
                check(generation == attachedGeneration) {
                    "Direct attachment returned without publishing its reserved generation"
                }
                awaitCancellation()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: com.proshot.app.camera.DirectCamera2RouteException) {
            currentOnDirectRouteFailure(error.failure)
        } catch (error: Exception) {
            currentOnCameraError("Direct Camera2 preview failed. Please retry.")
        } finally {
            withContext(NonCancellable) {
                generation?.let { cameraCaptureRuntime.detach(it) }
            }
            if (activeGeneration == generation) {
                activeGeneration = null
            }
        }
    }

    LaunchedEffect(
        configuration,
        activeGeneration,
        viewSize,
        displayRotationDegrees,
        currentSurface
    ) {
        val activeConfiguration = configuration
        val generation = activeGeneration
        if (activeConfiguration == null ||
            generation == null ||
            activeConfiguration.generation != generation ||
            currentSurface == null ||
            viewSize.width <= 0 ||
            viewSize.height <= 0
        ) {
            textureView.setTransform(Matrix())
            currentOnTransformChanged(null)
            return@LaunchedEffect
        }

        val transform = DirectPreviewGeometry.createPreviewTransform(
            viewWidth = viewSize.width.toFloat(),
            viewHeight = viewSize.height.toFloat(),
            bufferWidth = activeConfiguration.streamSize.width.toFloat(),
            bufferHeight = activeConfiguration.streamSize.height.toFloat(),
            sensorOrientation = activeConfiguration.sensorOrientationDegrees,
            displayRotationDegrees = displayRotationDegrees
        )
        textureView.setTransform(transform.textureViewRenderTransform.toAndroidMatrix())
        currentOnRenderTransformApplied(transform.textureViewRenderTransform)
        currentOnTransformChanged(transform)
    }

    AndroidView(
        factory = { textureView },
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
    )
}

private fun TextureViewRenderTransform.toAndroidMatrix(): Matrix = Matrix().also { matrix ->
    matrix.setScale(
        correctionScaleX,
        correctionScaleY,
        pivotX,
        pivotY
    )
    matrix.postRotate(
        negativeDisplayRotationDegrees,
        pivotX,
        pivotY
    )
}
