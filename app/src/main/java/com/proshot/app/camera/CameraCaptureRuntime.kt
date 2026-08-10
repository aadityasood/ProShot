package com.proshot.app.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.proshot.app.processing.style.LookProfile
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private const val TAG = "CameraCaptureRuntime"

internal data class AndroidPreviewAttachment(
    val lifecycleOwner: LifecycleOwner,
    val previewView: PreviewView
) : PreviewAttachment

/**
 * Configuration-retained owner of the selected preview route and complete
 * capture-to-save transaction.
 */
@ActivityRetainedScoped
class CameraCaptureRuntime @Inject internal constructor(
    private val cameraXPreviewController: CameraXPreviewController,
    private val directCamera2PreviewController: DirectCamera2PreviewController,
    private val singleFrameCaptureController: SingleFrameCaptureController,
    private val captureCoordinator: CaptureCoordinator
) {
    private val core = CameraCaptureRuntimeCore(
        errorReporter = CameraRuntimeErrorReporter { message, error ->
            Log.e(TAG, message, error)
        }
    )

    /** Whether the current attachment has a successfully bound preview. */
    val isPreviewReady: StateFlow<Boolean> = core.previewReady

    internal val directPreviewConfiguration: StateFlow<DirectPreviewConfiguration?> =
        directCamera2PreviewController.previewConfiguration

    /** Attaches the CameraX rollback preview route. */
    suspend fun attach(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ): Long? {
        return when (val outcome = core.attach(
            route = CameraOwnershipRoute.CAMERA_X_HANDOFF,
            previewPort = cameraXPreviewController,
            frameSource = singleFrameCaptureController,
            attachment = AndroidPreviewAttachment(lifecycleOwner, previewView)
        )) {
            is PreviewAttachOutcome.Ready -> outcome.generation
            PreviewAttachOutcome.Superseded -> null
        }
    }

    /** Attaches the direct Camera2 route to the supplied TextureView surface. */
    suspend fun attachDirect(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
        onTerminalError: (Throwable) -> Unit = {}
    ): Long? {
        return when (val outcome = core.attach(
            route = CameraOwnershipRoute.PERSISTENT_CAMERA2,
            previewPort = directCamera2PreviewController,
            frameSource = directCamera2PreviewController,
            attachmentFactory = { generation ->
                DirectPreviewAttachment(
                    surfaceTexture = surfaceTexture,
                    width = width,
                    height = height,
                    onTerminal = { error ->
                        core.onPortTerminated(generation, error)
                        onTerminalError(error)
                    }
                )
            }
        )) {
            is PreviewAttachOutcome.Ready -> outcome.generation
            PreviewAttachOutcome.Superseded -> null
        }
    }

    /** Detaches only the matching attachment generation. */
    suspend fun detach(attachmentGeneration: Long) {
        core.detach(attachmentGeneration)
    }

    /** Invalidates/cancels the generation before matching physical surface close. */
    internal fun detachDirectSurfaceSync(
        surfaceTexture: SurfaceTexture,
        attachmentGeneration: Long?
    ) {
        attachmentGeneration?.let(core::detachSync)
        directCamera2PreviewController.invalidateSurface(surfaceTexture)
    }

    /** Executes one fail-fast capture transaction for the ready generation. */
    suspend fun capture(
        context: Context,
        lookProfile: LookProfile,
        isDebug: Boolean,
        tracker: CaptureTimingTracker? = null,
        diagnosticsTracker: FocusLensDiagnosticsTracker? = null,
        focusTarget: FocusMeteringTarget = FocusMeteringTarget.center(),
        statusCallback: StatusCallback
    ): CaptureResult {
        return core.capture(isDebug = isDebug, tracker = tracker) { frameSource ->
            captureCoordinator.executeCapture(
                context = context,
                frameSource = frameSource,
                lookProfile = lookProfile,
                isDebug = isDebug,
                tracker = tracker,
                diagnosticsTracker = diagnosticsTracker,
                focusTarget = focusTarget,
                statusCallback = statusCallback
            )
        }
    }

    /** Resolves the physical back-camera sensor orientation synchronously. */
    fun resolveSensorOrientationSync(context: Context): Int {
        return directCamera2PreviewController.resolveSensorOrientation(context)
    }

    /** Resolves the physical back-camera sensor orientation off the main thread. */
    suspend fun resolveSensorOrientation(context: Context): Int {
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            captureCoordinator.resolveSensorOrientation(context)
        }
    }
}
