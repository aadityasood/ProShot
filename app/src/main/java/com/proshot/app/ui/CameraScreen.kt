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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.proshot.app.camera.CameraCapabilitiesMapper
import com.proshot.app.camera.SingleFrameCaptureController
import com.proshot.app.camera.compat.CompatibilityDecision
import com.proshot.app.camera.compat.CompatibilityPolicy
import com.proshot.app.camera.compat.DeviceCameraCapabilities
import com.proshot.app.output.CapturedImageEncoder
import com.proshot.app.output.GalleryImageSaver
import com.proshot.app.output.GallerySaveResult
import com.proshot.app.processing.colorscience.LookProfileNv21Processor
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    var captureStatusMessage by remember { mutableStateOf("Idle - Tap Shutter to capture YUV") }
    val isDebugBuild = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    val scope = rememberCoroutineScope()

    // Resolve ProcessCameraProvider off the main thread using cancellable coroutine.
    // This replaces the uncancellable addListener pattern.
    LaunchedEffect(lifecycleOwner, previewView, previewTrigger) {
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
        } catch (e: CancellationException) {
            Log.d(TAG, "CameraX preview binding cancelled during lifecycle change", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind CameraX preview", e)
            onCameraError(e.localizedMessage ?: "Unknown CameraX bind error")
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

    // Resolve capabilities off the main thread to avoid blocking on Camera2 HAL IPC.
    LaunchedEffect(context) {
        capabilityState = withContext(Dispatchers.IO) {
            val caps = CameraCapabilitiesMapper.map(context)
            val decision = CompatibilityPolicy.select(caps)
            caps to decision
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Render live CameraX preview
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // TODO(REMOVE BEFORE RELEASE): Debug-only status overlay.
        // This overlay is a temporary diagnostic aid for development and device testing.
        // It must be removed or gated behind a debug build flag before any external release.
        capabilityState?.let { (capabilities, decision) ->
            DebugStatusOverlay(capabilities, decision)
        }

        // Minimal capture state and shutter UI
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "State: ${if (isCapturing) "Capturing" else "Idle"}",
                color = if (isCapturing) Color.Yellow else Color.Green,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = captureStatusMessage,
                color = Color.White,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    if (isCapturing) return@Button
                    val activeDecision = capabilityState?.second ?: return@Button
                    val lookProfile = activeDecision.lookProfile
                    isCapturing = true
                    captureStatusMessage = "Initiating capture..."
                    scope.launch {
                        try {
                            // 1. Explicitly unbind all CameraX use cases from the provider on Main thread
                            withContext(Dispatchers.Main) {
                                cameraProvider?.unbindAll()
                            }

                            // 2. Call Camera2 raw capture on background dispatcher
                            val frame = withContext(Dispatchers.Default) {
                                SingleFrameCaptureController.captureSingleFrame(context)
                            }
                            val outputRotationDegrees = withContext(Dispatchers.IO) {
                                SingleFrameCaptureController.resolveOutputRotationDegrees(context)
                            }

                            val captureTimestampMs = System.currentTimeMillis()

                            // 3. Encode captured frame to NV21 & orient it on Dispatchers.Default
                            captureStatusMessage = "Encoding captured frame..."
                            val orientedNv21 = withContext(Dispatchers.Default) {
                                val nv21 = CapturedImageEncoder.yuv420ToNv21(frame)
                                CapturedImageEncoder.rotateNv21(
                                    nv21 = nv21,
                                    width = frame.width,
                                    height = frame.height,
                                    rotationDegrees = outputRotationDegrees
                                )
                            }

                            var baselineSaveResult: GallerySaveResult? = null
                            if (isDebugBuild) {
                                // Compress oriented baseline NV21 on Dispatchers.Default
                                val baselineJpegBytes = withContext(Dispatchers.Default) {
                                    CapturedImageEncoder.compressNv21ToJpeg(
                                        nv21 = orientedNv21.data,
                                        width = orientedNv21.width,
                                        height = orientedNv21.height
                                    )
                                }
                                // Save with suffix "baseline" on Dispatchers.IO
                                captureStatusMessage = "Saving baseline photo..."
                                baselineSaveResult = GalleryImageSaver.saveToGallery(
                                    context = context,
                                    jpegBytes = baselineJpegBytes,
                                    timestampMs = captureTimestampMs,
                                    filenameSuffix = "baseline"
                                )
                            }

                            // 4. Apply ProShot Natural v0 processing hook on Dispatchers.Default
                            captureStatusMessage = "Processing photo..."
                            val processedNv21 = withContext(Dispatchers.Default) {
                                LookProfileNv21Processor.apply(orientedNv21, lookProfile)
                            }

                            // 5. Compress processed NV21 to JPEG on Dispatchers.Default
                            val jpegBytes = withContext(Dispatchers.Default) {
                                CapturedImageEncoder.compressNv21ToJpeg(
                                    nv21 = processedNv21.data,
                                    width = processedNv21.width,
                                    height = processedNv21.height
                                )
                            }

                            // 6. Save JPEG to gallery on Dispatchers.IO
                            captureStatusMessage = "Saving to gallery..."
                            val saveResult = GalleryImageSaver.saveToGallery(
                                context = context,
                                jpegBytes = jpegBytes,
                                timestampMs = captureTimestampMs,
                                filenameSuffix = if (isDebugBuild) "natural" else null
                            )

                            // 7. Update user status message
                            captureStatusMessage = if (isDebugBuild) {
                                when (saveResult) {
                                    is GallerySaveResult.Success -> {
                                        if (baselineSaveResult is GallerySaveResult.Success) {
                                            "Saved diagnostic pair"
                                        } else {
                                            val reason = (baselineSaveResult as? GallerySaveResult.Failure)?.userReason ?: "unknown error"
                                            "Saved natural; baseline failed: $reason"
                                        }
                                    }
                                    is GallerySaveResult.Failure -> {
                                        if (baselineSaveResult is GallerySaveResult.Success) {
                                            "Saved baseline; natural failed: ${saveResult.userReason}"
                                        } else {
                                            "Save failed: ${saveResult.userReason}"
                                        }
                                    }
                                }
                            } else {
                                when (saveResult) {
                                    is GallerySaveResult.Success -> "Saved to gallery"
                                    is GallerySaveResult.Failure -> "Save failed: ${saveResult.userReason}"
                                }
                            }
                        } catch (e: CancellationException) {
                            captureStatusMessage = "Capture cancelled."
                            throw e
                        } catch (e: IllegalArgumentException) {
                            Log.e(TAG, "Frame verification failed", e)
                            captureStatusMessage = "Capture failed: invalid image data"
                        } catch (e: OutOfMemoryError) {
                            Log.e(TAG, "OOM during capture/encode; frame too large for available memory", e)
                            captureStatusMessage = "Not enough memory to save photo"
                        } catch (e: Exception) {
                            Log.e(TAG, "Single frame capture or encode failed", e)
                            captureStatusMessage = "Capture failed: system error"
                        } finally {
                            isCapturing = false
                            // 8. Guaranteed preview rebound by incrementing reactive key
                            previewTrigger++
                        }
                    }
                },
                enabled = !isCapturing && cameraProvider != null && capabilityState != null
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.height(18.dp)
                    )
                } else if (cameraProvider == null || capabilityState == null) {
                    Text("WAITING")
                } else {
                    Text("SHUTTER")
                }
            }
        }
    }
}

/**
 * Temporary debug overlay displaying pipeline tier and device capability diagnostics.
 */
@Composable
private fun DebugStatusOverlay(
    capabilities: DeviceCameraCapabilities,
    decision: CompatibilityDecision
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(8.dp)
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
