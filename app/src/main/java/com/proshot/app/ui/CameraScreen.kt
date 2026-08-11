package com.proshot.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.proshot.app.MainActivity
import com.proshot.app.R
import com.proshot.app.camera.CameraCapabilitiesMapper
import com.proshot.app.camera.CameraCaptureRuntime
import com.proshot.app.camera.CaptureResult
import com.proshot.app.camera.CaptureTiming
import com.proshot.app.camera.CaptureTimingTracker
import com.proshot.app.camera.FocusLensDiagnostics
import com.proshot.app.camera.FocusLensDiagnosticsTracker
import com.proshot.app.camera.FocusMeteringTarget
import com.proshot.app.camera.DirectPreviewTransform
import com.proshot.app.camera.PreviewTapFocusMapper
import com.proshot.app.camera.compat.CompatibilityDecision
import com.proshot.app.camera.compat.CompatibilityPolicy
import com.proshot.app.camera.compat.DeviceCameraCapabilities
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CameraScreen"

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
 * the UI with Camera2 HAL IPC. Preview attachment is delegated to the activity-owned
 * camera runtime and cleaned up through structured effect cancellation.
 */
@Composable
fun CameraScreen(
    cameraCaptureRuntime: CameraCaptureRuntime,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var uiState by remember { mutableStateOf(CameraUIState.CHECKING_PERMISSION) }
    var errorMessage by remember { mutableStateOf("") }
    var showManualRecoveryMessage by remember { mutableStateOf(false) }
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
                        val navigator = AppSettingsNavigator(ContextIntentLauncher(context))
                        val result = navigator.navigateToSettings(context.packageName)
                        showManualRecoveryMessage = shouldShowManualRecoveryMessage(result)
                    },
                    showManualRecoveryMessage = showManualRecoveryMessage
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
                    cameraCaptureRuntime = cameraCaptureRuntime,
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
 * Composable containing the live CameraX preview surface and debug overlay.
 * Camera lifecycle mechanics remain delegated to [CameraCaptureRuntime].
 */
@Composable
private fun ActivePreviewContent(
    cameraCaptureRuntime: CameraCaptureRuntime,
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onCameraError: (String) -> Unit
) {
    val isDebugBuild = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    var routeStateName by rememberSaveable {
        mutableStateOf(com.proshot.app.camera.CameraOwnershipRouteState.PENDING.name)
    }
    val routeState = remember(routeStateName) {
        com.proshot.app.camera.CameraOwnershipRouteState.parse(routeStateName)
    }
    val retainedFallbackMandatory by
        cameraCaptureRuntime.isCameraXFallbackMandatory.collectAsState()
    val effectiveRouteState = remember(routeState, retainedFallbackMandatory) {
        com.proshot.app.camera.CameraOwnershipRoutePolicy.applyRetainedFallback(
            currentState = routeState,
            isFallbackMandatory = retainedFallbackMandatory
        )
    }
    val activeRoute = effectiveRouteState.route

    LaunchedEffect(effectiveRouteState.name) {
        if (effectiveRouteState ==
            com.proshot.app.camera.CameraOwnershipRouteState.FALLBACK_CAMERAX &&
            routeStateName != effectiveRouteState.name
        ) {
            routeStateName = effectiveRouteState.name
        }
    }

    var isCapturing by remember { mutableStateOf(false) }
    val feedbackReducer = remember { CaptureFeedbackReducer() }
    var captureFeedbackState by remember { mutableStateOf<CaptureFeedbackState>(CaptureFeedbackState.Hidden()) }
    var focusStatusMessage by remember { mutableStateOf("") }
    var showDebugOverlay by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isPreviewReady by cameraCaptureRuntime.isPreviewReady.collectAsState()

    var focusTarget by remember { mutableStateOf(FocusMeteringTarget.center()) }
    var focusRingVisible by remember { mutableStateOf(false) }
    var tapPosition by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var tapCounter by remember { mutableIntStateOf(0) }
    var sensorOrientationDegrees by remember { mutableIntStateOf(90) }
    var directPreviewTransform by remember {
        mutableStateOf<DirectPreviewTransform?>(null)
    }

    var lastCaptureTiming by remember { mutableStateOf<CaptureTiming?>(null) }
    var lastFocusLensDiagnostics by remember { mutableStateOf<FocusLensDiagnostics?>(null) }

    val hapticFeedback = LocalHapticFeedback.current
    var gridVisible by rememberSaveable { mutableStateOf(false) }
    var capabilityState by remember(context) {
        mutableStateOf<Pair<DeviceCameraCapabilities, CompatibilityDecision>?>(null)
    }
    val displayRotationSource = remember(context) {
        val activity = context as? Activity
        val displayManager = activity?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (activity != null && displayManager != null) {
            AndroidDisplayRotationSource(activity, displayManager)
        } else {
            UnavailableDisplayRotationSource
        }
    }
    val displayRotation = rememberCurrentDisplayRotationDegrees(displayRotationSource)

    val onShutterPressed = {
        val activeDecision = capabilityState?.second
        val isReady = !isCapturing && isPreviewReady && activeDecision != null && activeRoute != null
        if (isReady) {
            isCapturing = true
            focusStatusMessage = ""
            val takingPhotoText = context.getString(R.string.taking_photo)
            captureFeedbackState = feedbackReducer.startCapture(takingPhotoText)
            try {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            } catch (_: Exception) {
                // Haptic availability must not prevent an accepted capture.
            }
            val lookProfile = activeDecision!!.lookProfile
            scope.launch {
                val tracker = if (isDebugBuild) CaptureTimingTracker() else null
                val diagnosticsTracker = if (isDebugBuild) FocusLensDiagnosticsTracker() else null

                try {
                    val result = cameraCaptureRuntime.capture(
                        context = context,
                        lookProfile = lookProfile,
                        isDebug = isDebugBuild,
                        tracker = tracker,
                        diagnosticsTracker = diagnosticsTracker,
                        focusTarget = focusTarget
                    ) { _ ->
                        // Continue supplying coordinator status callback without updating beginner status pill
                    }

                    if (tracker != null) {
                        lastCaptureTiming = tracker.toCaptureTiming()
                    }
                    if (diagnosticsTracker != null) {
                        lastFocusLensDiagnostics = diagnosticsTracker.snapshot()
                    }

                    val message = when (result) {
                        is CaptureResult.Success -> result.message
                        is CaptureResult.Failure -> result.message
                    }
                    captureFeedbackState = feedbackReducer.completeCapture(message)
                } catch (e: CancellationException) {
                    if (isActive) {
                        captureFeedbackState = feedbackReducer.completeCapture("Capture cancelled.")
                    }
                    throw e
                } finally {
                    isCapturing = false
                }
            }
            true
        } else {
            false
        }
    }

    val currentOnShutterPressed by rememberUpdatedState(onShutterPressed)
    val router = remember {
        VolumeKeyRouter { currentOnShutterPressed() }
    }

    val activity = LocalContext.current as? MainActivity
    DisposableEffect(activity, router) {
        val installedHandler = activity?.let {
            val handler: (android.view.KeyEvent) -> Boolean = { event ->
                router.dispatchKeyEvent(
                    keyCode = event.keyCode,
                    action = event.action,
                    repeatCount = event.repeatCount
                )
            }
            it.registerVolumeKeyEventHandler(handler)
            handler
        }
        onDispose {
            if (activity != null && installedHandler != null) {
                activity.unregisterVolumeKeyEventHandler(installedHandler)
            }
        }
    }

    LaunchedEffect(context) {
        val (capabilities, decision) = withContext(Dispatchers.IO) {
            val caps = CameraCapabilitiesMapper.map(context)
            val dec = CompatibilityPolicy.select(caps)
            caps to dec
        }.also { capabilityState = it }

        val currentSavedState = com.proshot.app.camera.CameraOwnershipRouteState.parse(
            routeStateName
        )
        val currentEffectiveState =
            com.proshot.app.camera.CameraOwnershipRoutePolicy.applyRetainedFallback(
                currentState = currentSavedState,
                isFallbackMandatory = cameraCaptureRuntime
                    .isCameraXFallbackMandatory.value
            )
        if (currentEffectiveState ==
            com.proshot.app.camera.CameraOwnershipRouteState.PENDING
        ) {
            val initialState = com.proshot.app.camera.CameraOwnershipRoutePolicy.selectInitialState(
                isDebuggable = isDebugBuild,
                cameraAvailable = capabilities.cameraAvailable,
                yuvCaptureSupported = capabilities.yuvCaptureSupported
            )
            routeStateName = com.proshot.app.camera.CameraOwnershipRoutePolicy
                .applyRetainedFallback(
                    currentState = initialState,
                    isFallbackMandatory = cameraCaptureRuntime
                        .isCameraXFallbackMandatory.value
                ).name
        } else if (currentEffectiveState != currentSavedState) {
            routeStateName = currentEffectiveState.name
        }

        sensorOrientationDegrees = withContext(Dispatchers.IO) {
            try {
                cameraCaptureRuntime.resolveSensorOrientation(context)
            } catch (_: Exception) {
                90
            }
        }
    }

    val onDirectRouteFailure: (com.proshot.app.camera.DirectCamera2Failure) -> Unit = { failure ->
        val nextState = com.proshot.app.camera.CameraOwnershipRoutePolicy.reduceDirectFailure(
            currentState = effectiveRouteState,
            failure = failure
        )
        if (nextState ==
            com.proshot.app.camera.CameraOwnershipRouteState.FALLBACK_CAMERAX
        ) {
            routeStateName = nextState.name
        } else if (failure.kind != com.proshot.app.camera.DirectCamera2FailureKind.LIFECYCLE_OR_SUPERSESSION) {
            onCameraError("Direct Camera2 preview failed. Please retry.")
        }
    }

    if (focusRingVisible && tapPosition != null) {
        LaunchedEffect(tapCounter) {
            delay(1500L)
            focusRingVisible = false
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val placement = CapturePlacementPolicy.resolve(
            width = constraints.maxWidth,
            height = constraints.maxHeight,
            displayRotationDegrees = displayRotation
        )

        if (activeRoute == com.proshot.app.camera.CameraOwnershipRoute.PERSISTENT_CAMERA2) {
            DirectCamera2PreviewHost(
                cameraCaptureRuntime = cameraCaptureRuntime,
                displayRotationDegrees = displayRotation,
                onTransformChanged = { directPreviewTransform = it },
                onCameraError = onCameraError,
                onDirectRouteFailure = onDirectRouteFailure
            )
        } else if (activeRoute == com.proshot.app.camera.CameraOwnershipRoute.CAMERA_X_HANDOFF) {
            val previewView = remember(context) {
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            }
            LaunchedEffect(cameraCaptureRuntime, lifecycleOwner, previewView) {
                var attachmentGeneration: Long? = null
                try {
                    attachmentGeneration = cameraCaptureRuntime.attach(
                        lifecycleOwner,
                        previewView
                    )
                    if (attachmentGeneration == null) {
                        return@LaunchedEffect
                    }
                    awaitCancellation()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to attach camera preview", error)
                    onCameraError("Camera could not start. Please try again.")
                } finally {
                    withContext(NonCancellable) {
                        attachmentGeneration?.let { generation ->
                            try {
                                cameraCaptureRuntime.detach(generation)
                            } catch (error: Exception) {
                                Log.e(TAG, "Failed to detach camera preview", error)
                            }
                        }
                    }
                }
            }
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (gridVisible) {
            RuleOfThirdsGrid()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(
                    isCapturing,
                    isPreviewReady,
                    activeRoute,
                    directPreviewTransform
                ) {
                    detectTapGestures { offset ->
                        if (!isCapturing &&
                            isPreviewReady &&
                            size.width > 0 &&
                            size.height > 0
                        ) {
                            val target = if (activeRoute ==
                                com.proshot.app.camera.CameraOwnershipRoute.PERSISTENT_CAMERA2
                            ) {
                                directPreviewTransform?.mapTapToSensorTarget(
                                    offset.x,
                                    offset.y
                                ) ?: return@detectTapGestures
                            } else {
                                PreviewTapFocusMapper.mapToSensorTarget(
                                    tapX = offset.x,
                                    tapY = offset.y,
                                    viewWidth = size.width,
                                    viewHeight = size.height,
                                    rotationDegrees = sensorOrientationDegrees
                                )
                            }
                            tapCounter++
                            tapPosition = offset
                            focusRingVisible = true
                            focusTarget = target
                            focusStatusMessage = "Focus set"
                        }
                    }
                }
        )

        if (focusRingVisible && tapPosition != null) {
            val position = tapPosition!!
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.Yellow,
                    center = position,
                    radius = 30.dp.toPx(),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        if (isDebugBuild) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 16.dp)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .background(
                        Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { showDebugOverlay = !showDebugOverlay }
                    .semantics {
                        role = Role.Button
                        contentDescription = if (showDebugOverlay) {
                            "Hide debug diagnostics"
                        } else {
                            "Show debug diagnostics"
                        }
                        stateDescription = if (showDebugOverlay) {
                            "Expanded"
                        } else {
                            "Collapsed"
                        }
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

            if (showDebugOverlay && capabilityState != null) {
                val (capabilities, decision) = capabilityState!!
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 48.dp, start = 16.dp)
                ) {
                    DebugStatusOverlay(
                        capabilities,
                        decision,
                        lastCaptureTiming,
                        lastFocusLensDiagnostics
                    )
                }
            }
        }

        if (focusStatusMessage.isNotEmpty()) {
            LaunchedEffect(tapCounter) {
                delay(3000L)
                focusStatusMessage = ""
            }
        }

        CaptureFeedbackPlacement(
            state = captureFeedbackState,
            focusMessage = focusStatusMessage,
            onDismiss = { eventToken ->
                captureFeedbackState = feedbackReducer.dismiss(eventToken)
            }
        )

        val alignment = when (placement) {
            CaptureControlsPlacement.PORTRAIT_BOTTOM -> Alignment.BottomCenter
            CaptureControlsPlacement.LANDSCAPE_LEFT -> AbsoluteAlignment.CenterLeft
            CaptureControlsPlacement.LANDSCAPE_RIGHT -> AbsoluteAlignment.CenterRight
        }

        BeginnerCameraControls(
            isCapturing = isCapturing,
            isWaiting = capabilityState == null || !isPreviewReady,
            enabled = !isCapturing && isPreviewReady && capabilityState != null,
            gridVisible = gridVisible,
            onGridToggle = { gridVisible = !gridVisible },
            onShutterClick = { onShutterPressed() },
            placement = placement,
            modifier = Modifier
                .align(alignment)
                .safeDrawingPadding()
        )
    }
}
