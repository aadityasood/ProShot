package com.proshot.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.proshot.app.camera.CameraCapabilitiesMapper
import com.proshot.app.camera.CaptureCoordinator
import com.proshot.app.camera.CaptureResult
import com.proshot.app.camera.CaptureTiming
import com.proshot.app.camera.CaptureTimingTracker
import com.proshot.app.camera.FocusLensDiagnostics
import com.proshot.app.camera.FocusLensDiagnosticsTracker
import com.proshot.app.camera.SingleFrameCaptureController
import com.proshot.app.camera.FocusMeteringTarget
import com.proshot.app.camera.PreviewTapFocusMapper
import com.proshot.app.camera.compat.CompatibilityDecision
import com.proshot.app.camera.compat.CompatibilityPolicy
import com.proshot.app.camera.compat.DeviceCameraCapabilities
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraScreen"

/**
 * Maps coordinator progress strings to beginner-safe UI vocabulary.
 *
 * The [CaptureCoordinator] emits pipeline-specific status strings (e.g. "Encoding
 * captured frame...") that are useful for diagnostics but too technical for the main
 * status pill. This function translates them to simple camera-app language.
 */
private fun mapStatusForDisplay(coordinatorStatus: String): String {
    return when (coordinatorStatus) {
        "Initiating capture..." -> "Taking photo..."
        "Encoding captured frame..." -> "Taking photo..."
        "Saving baseline photo..." -> "Saving photo..."
        "Processing photo..." -> "Processing photo..."
        "Saving to gallery..." -> "Saving photo..."
        else -> "Working..."
    }
}

/**
 * States for the camera permissions and preview workflow state machine.
 */
private enum class CameraUIState {
    CHECKING_PERMISSION,
    PERMISSION_DENIED,
    PERMISSION_PERMANENTLY_DENIED,
    CAMERA_ERROR,
    ACTIVE_PREVIEW
}

/**
 * A functional, clean camera UI layer handling permissions, lifecycle-bound preview, and diagnostics.
 *
 * Capability mapping runs off the main thread on [Dispatchers.IO] to avoid blocking
 * the UI with Camera2 HAL IPC. CameraX preview binding is cancellable via
 * structured concurrency and cleaned up via [DisposableEffect].
 */
@Composable
fun CameraScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var uiState by remember { mutableStateOf(CameraUIState.CHECKING_PERMISSION) }
    var errorMessage by remember { mutableStateOf("") }
    var retryCount by remember { mutableIntStateOf(0) }

    // Launcher to request runtime camera permission
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                uiState = CameraUIState.ACTIVE_PREVIEW
            } else {
                // Detect permanent denial: shouldShowRequestPermissionRationale returns
                // false when the user has selected "Don't ask again" on Android 11+.
                val activity = context as? Activity
                val shouldShowRationale = activity?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        it, Manifest.permission.CAMERA
                    )
                } ?: true
                uiState = if (!shouldShowRationale) {
                    CameraUIState.PERMISSION_PERMANENTLY_DENIED
                } else {
                    CameraUIState.PERMISSION_DENIED
                }
            }
        }
    )

    // Check initial permission state and handle retries.
    // Keyed on retryCount so retry button triggers a fresh check.
    LaunchedEffect(retryCount) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            uiState = CameraUIState.ACTIVE_PREVIEW
        } else {
            uiState = CameraUIState.CHECKING_PERMISSION
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when (uiState) {
            CameraUIState.CHECKING_PERMISSION -> {
                LoadingStateView()
            }
            CameraUIState.PERMISSION_DENIED -> {
                PermissionDeniedView(
                    onGrantClick = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                )
            }
            CameraUIState.PERMISSION_PERMANENTLY_DENIED -> {
                PermissionPermanentlyDeniedView(
                    onOpenSettingsClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                        context.startActivity(intent)
                    }
                )
            }
            CameraUIState.CAMERA_ERROR -> {
                CameraErrorView(
                    message = errorMessage,
                    onRetryClick = {
                        retryCount++
                    }
                )
            }
            CameraUIState.ACTIVE_PREVIEW -> {
                ActivePreviewContent(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    onCameraError = { message ->
                        errorMessage = message
                        uiState = CameraUIState.CAMERA_ERROR
                    }
                )
            }
        }
    }
}

/**
 * Composable containing the live CameraX preview, binding logic, and debug overlay.
 * Separated to scope the [DisposableEffect] cleanup correctly.
 */
@Composable
private fun ActivePreviewContent(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onCameraError: (String) -> Unit
) {
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    // Remembered reference to the camera provider for cleanup in DisposableEffect.
    var cameraProvider: ProcessCameraProvider? by remember { mutableStateOf(null) }

    var previewTrigger by remember { mutableIntStateOf(0) }
    var isCapturing by remember { mutableStateOf(false) }
    var captureStatusMessage by remember { mutableStateOf("") }
    var showDebugOverlay by remember { mutableStateOf(false) }
    val isDebugBuild = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    val scope = rememberCoroutineScope()

    var focusTarget by remember { mutableStateOf(FocusMeteringTarget.center()) }
    var focusRingVisible by remember { mutableStateOf(false) }
    var tapPosition by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var tapCounter by remember { mutableIntStateOf(0) }
    // Sensor orientation is constant for a given camera and is resolved once at
    // setup. Using this directly (instead of resolveOutputRotationDegrees) is
    // correct for the preview-to-sensor tap transform because sensor orientation
    // does not change with device rotation. Default 90 covers the Nothing Phone 2
    // back camera.
    var sensorOrientationDegrees by remember { mutableIntStateOf(90) }

    var lastCaptureTiming by remember { mutableStateOf<CaptureTiming?>(null) }
    var lastFocusLensDiagnostics by remember { mutableStateOf<FocusLensDiagnostics?>(null) }
    var activeTimingTracker by remember { mutableStateOf<CaptureTimingTracker?>(null) }
    var capturePipelineStart by remember { mutableStateOf<Long?>(null) }
    var captureRebindStart by remember { mutableStateOf<Long?>(null) }

    // Resolve ProcessCameraProvider off the main thread using cancellable coroutine.
    // This replaces the uncancellable addListener pattern.
    LaunchedEffect(lifecycleOwner, previewView, previewTrigger) {
        val startRebind = captureRebindStart
        val startPipeline = capturePipelineStart
        val activeTracker = activeTimingTracker
        try {
            val provider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
                val future = ProcessCameraProvider.getInstance(context)
                // Do not register invokeOnCancellation { future.cancel(...) } here.
                // CameraX exposes a shared provider future; cancelling it can break
                // later provider resolution. Instead, guard inactive continuations.
                future.addListener({
                    if (!cont.isActive) {
                        return@addListener
                    }
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        if (cont.isActive) {
                            cont.resumeWithException(e)
                        }
                    }
                }, ContextCompat.getMainExecutor(context))
            }

            cameraProvider = provider
            provider.unbindAll()

            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                previewUseCase
            )

            if (activeTracker != null && startRebind != null && startPipeline != null) {
                activeTracker.previewRebindMs = (System.nanoTime() - startRebind) / 1_000_000L
                activeTracker.totalCapturePipelineMs = (System.nanoTime() - startPipeline) / 1_000_000L
                lastCaptureTiming = activeTracker.toCaptureTiming()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (activeTracker != null && startRebind != null && startPipeline != null) {
                activeTracker.previewRebindMs = (System.nanoTime() - startRebind) / 1_000_000L
                activeTracker.totalCapturePipelineMs = (System.nanoTime() - startPipeline) / 1_000_000L
                lastCaptureTiming = activeTracker.toCaptureTiming()
            }
            Log.e(TAG, "Failed to bind CameraX preview", e)
            onCameraError("Camera could not start. Please try again.")
        } finally {
            if (activeTimingTracker != null) {
                activeTimingTracker = null
                capturePipelineStart = null
                captureRebindStart = null
            }
        }
    }

    // Keyed on lifecycleOwner only: LaunchedEffect unbinds before every rebind.
    // This effect is final cleanup when the preview leaves composition entirely.
    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                cameraProvider?.unbindAll()
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding CameraX on dispose", e)
            }
        }
    }

    var capabilityState by remember(context) {
        mutableStateOf<Pair<DeviceCameraCapabilities, CompatibilityDecision>?>(null)
    }

    // Resolve capabilities and sensor orientation off the main thread to avoid
    // blocking on Camera2 HAL IPC. Sensor orientation is constant for the back
    // camera and is needed synchronously by the tap handler.
    LaunchedEffect(context) {
        capabilityState = withContext(Dispatchers.IO) {
            val caps = CameraCapabilitiesMapper.map(context)
            val decision = CompatibilityPolicy.select(caps)
            caps to decision
        }
        sensorOrientationDegrees = withContext(Dispatchers.IO) {
            try {
                SingleFrameCaptureController.resolveSensorOrientation(context)
            } catch (e: Exception) {
                90 // Nothing Phone 2 back camera default
            }
        }
    }

    if (focusRingVisible && tapPosition != null) {
        LaunchedEffect(tapCounter) {
            delay(1500L)
            focusRingVisible = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Render live CameraX preview
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Transparent tap overlay for focus mapping.
        // Tap handling is fully synchronous: sensor orientation is resolved once
        // at setup and stored in sensorOrientationDegrees. This eliminates the
        // async race where rapid double-taps could desynchronize the visible
        // focus ring position from the actual capture focus target.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isCapturing, previewView.width, previewView.height) {
                    detectTapGestures { offset ->
                        if (!isCapturing && previewView.width > 0 && previewView.height > 0) {
                            val target = PreviewTapFocusMapper.mapToSensorTarget(
                                tapX = offset.x,
                                tapY = offset.y,
                                viewWidth = previewView.width,
                                viewHeight = previewView.height,
                                rotationDegrees = sensorOrientationDegrees
                            )
                            tapCounter++
                            tapPosition = offset
                            focusRingVisible = true
                            focusTarget = target
                            captureStatusMessage = "Focus set"
                        }
                    }
                }
        )

        // Focus ring overlay
        if (focusRingVisible && tapPosition != null) {
            val pos = tapPosition!!
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.Yellow,
                    center = pos,
                    radius = 30.dp.toPx(),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // Debug controls (only available in debug builds)
        if (isDebugBuild) {
            // Tiny toggle chip in TopEnd
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 16.dp)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .background(Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(16.dp))
                    .clickable { showDebugOverlay = !showDebugOverlay }
                    .semantics {
                        role = Role.Button
                        contentDescription = if (showDebugOverlay) {
                            "Hide debug diagnostics"
                        } else {
                            "Show debug diagnostics"
                        }
                        stateDescription = if (showDebugOverlay) "Expanded" else "Collapsed"
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (showDebugOverlay) "Hide" else "Debug",
                    color = Color.Green,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            // Debug overlay in TopStart (only if expanded)
            if (showDebugOverlay && capabilityState != null) {
                val (capabilities, decision) = capabilityState!!
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 48.dp, start = 16.dp)
                ) {
                    DebugStatusOverlay(capabilities, decision, lastCaptureTiming, lastFocusLensDiagnostics)
                }
            }
        }

        // Shutter and feedback container
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Auto-clear terminal status messages after 3 seconds to unclutter the viewfinder.
            if (captureStatusMessage.isNotEmpty() && !isCapturing) {
                LaunchedEffect(captureStatusMessage) {
                    delay(3000L)
                    captureStatusMessage = ""
                }
            }

            // Styled status message pill
            if (captureStatusMessage.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = captureStatusMessage,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            ShutterButton(
                enabled = !isCapturing && cameraProvider != null && capabilityState != null,
                isCapturing = isCapturing,
                isWaiting = cameraProvider == null || capabilityState == null,
                onClick = {
                    val activeDecision = capabilityState?.second ?: return@ShutterButton
                    val lookProfile = activeDecision.lookProfile
                    isCapturing = true
                    captureStatusMessage = "Taking photo..."
                    scope.launch {
                        val tracker = if (isDebugBuild) CaptureTimingTracker() else null
                        val diagnosticsTracker = if (isDebugBuild) FocusLensDiagnosticsTracker() else null
                        val pipelineStart = if (isDebugBuild) System.nanoTime() else null
                        activeTimingTracker = tracker
                        capturePipelineStart = pipelineStart

                        try {
                            val unbindStart = if (isDebugBuild) System.nanoTime() else null
                            // 1. Explicitly unbind all CameraX use cases from the provider on Main thread
                            withContext(Dispatchers.Main) {
                                cameraProvider?.unbindAll()
                            }
                            if (tracker != null && unbindStart != null) {
                                tracker.previewUnbindMs = (System.nanoTime() - unbindStart) / 1_000_000L
                            }

                            // 2. Delegate capture orchestration and background dispatch to CaptureCoordinator
                            val result = CaptureCoordinator.executeCapture(
                                context = context,
                                lookProfile = lookProfile,
                                isDebug = isDebugBuild,
                                tracker = tracker,
                                diagnosticsTracker = diagnosticsTracker,
                                focusTarget = focusTarget
                            ) { status ->
                                // Map coordinator progress to beginner-safe vocabulary.
                                captureStatusMessage = mapStatusForDisplay(status)
                            }

                            // Store timing snapshot intermediate result (without rebind yet)
                            if (tracker != null) {
                                lastCaptureTiming = tracker.toCaptureTiming()
                            }
                            if (diagnosticsTracker != null) {
                                lastFocusLensDiagnostics = diagnosticsTracker.snapshot()
                            }

                            // 3. Map high-level result to the UI status message
                            captureStatusMessage = when (result) {
                                is CaptureResult.Success -> result.message
                                is CaptureResult.Failure -> result.message
                            }
                        } catch (e: CancellationException) {
                            captureStatusMessage = "Capture cancelled."
                            throw e
                        } finally {
                            isCapturing = false
                            if (isDebugBuild) {
                                captureRebindStart = System.nanoTime()
                            }
                            // 4. Guaranteed preview rebound by incrementing reactive key
                            previewTrigger++
                        }
                    }
                }
            )
        }
    }
}

/**
 * A camera-style circular shutter control.
 * Displays a capturing state with shrinking circle size and an active progress indicator,
 * and a disabled/waiting state.
 */
@Composable
private fun ShutterButton(
    enabled: Boolean,
    isCapturing: Boolean,
    isWaiting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val innerSize by animateDpAsState(
        targetValue = when {
            isWaiting -> 0.dp
            isCapturing -> 44.dp
            else -> 60.dp
        },
        label = "shutterInnerSize"
    )

    Box(
        modifier = modifier
            .size(84.dp)
            .semantics {
                role = Role.Button
                contentDescription = when {
                    isWaiting -> "Camera loading"
                    isCapturing -> "Capturing photo"
                    else -> "Take photo"
                }
                stateDescription = when {
                    isWaiting -> "Waiting"
                    isCapturing -> "Capturing"
                    enabled -> "Ready"
                    else -> "Disabled"
                }
            }
            .clickable(
                enabled = enabled && !isWaiting && !isCapturing,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Outer Ring
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White.copy(alpha = if (isWaiting) 0.3f else 1.0f),
                radius = (size.minDimension / 2) - 4.dp.toPx(),
                style = Stroke(width = 4.dp.toPx())
            )
        }

        if (isWaiting || isCapturing) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(52.dp)
            )
        }

        if (innerSize > 0.dp) {
            Box(
                modifier = Modifier
                    .size(innerSize)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (enabled) 1.0f else 0.5f))
            )
        }
    }
}

/**
 * Temporary debug overlay displaying pipeline tier and device capability diagnostics.
 */
@Composable
private fun DebugStatusOverlay(
    capabilities: DeviceCameraCapabilities,
    decision: CompatibilityDecision,
    lastCaptureTiming: CaptureTiming?,
    lastFocusLensDiagnostics: FocusLensDiagnostics?
) {
    Column(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = "ProShot Debug Status",
            color = Color.Green,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Pipeline Tier: ${decision.tier}",
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "Look Profile: ${decision.lookProfile.displayName}",
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "HW Level: ${capabilities.hardwareLevel}",
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "GPU: ${if (capabilities.gpuDelegateSupported) "OK" else "CPU fallback"}",
            color = if (capabilities.gpuDelegateSupported) Color.Cyan else Color.Yellow,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "Masks: ${if (capabilities.semanticMasksSupported) "OK" else "Disabled"}",
            color = if (capabilities.semanticMasksSupported) Color.Cyan else Color.Yellow,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        if (lastCaptureTiming != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = lastCaptureTiming.formatDiagnostics(),
                color = Color.Green,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        if (lastFocusLensDiagnostics != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = lastFocusLensDiagnostics.formatDiagnostics(),
                color = Color.Green,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun LoadingStateView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Initializing camera...",
                color = Color.LightGray,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun PermissionDeniedView(
    onGrantClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Camera Permission Required",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "ProShot needs camera permission to capture photos.",
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onGrantClick) {
                Text(text = "Grant Permission")
            }
        }
    }
}

/**
 * Shown when the user has permanently denied the camera permission (e.g. "Don't ask again"
 * on Android 11+). Offers a deep-link to the app's system Settings page so the user can
 * manually re-enable the permission.
 */
@Composable
private fun PermissionPermanentlyDeniedView(
    onOpenSettingsClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Camera Permission Required",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Camera permission has been permanently denied. " +
                    "Please enable it in your device settings to use ProShot.",
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onOpenSettingsClick) {
                Text(text = "Open Settings")
            }
        }
    }
}

@Composable
private fun CameraErrorView(
    message: String,
    onRetryClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Camera Initialization Error",
                color = Color.Red,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetryClick) {
                Text(text = "Retry")
            }
        }
    }
}
