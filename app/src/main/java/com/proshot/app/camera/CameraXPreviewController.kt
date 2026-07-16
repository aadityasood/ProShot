package com.proshot.app.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private class StalePreviewAttachmentException :
    IllegalStateException("Camera preview attachment was replaced")

/**
 * Configuration-retained, activity-owned CameraX preview adapter.
 *
 * All provider, bind, and unbind operations run on the main dispatcher. A
 * generation token shared across configuration recreation prevents completion
 * or detach from the old Activity instance from binding or clearing the
 * replacement preview. The shared CameraX provider future is intentionally not
 * cancelled when a waiting coroutine is cancelled; inactive continuations are
 * ignored instead.
 */
@ActivityRetainedScoped
internal class CameraXPreviewController @Inject constructor(
    @ApplicationContext private val context: Context
) : PreviewLifecyclePort {
    private val generationLock = Any()

    @Volatile
    private var validGeneration = NO_GENERATION
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeGeneration: Long? = null
    private var activeAttachment: AndroidPreviewAttachment? = null
    private var previewUseCase: Preview? = null

    override fun invalidate(generation: Long) {
        synchronized(generationLock) {
            if (validGeneration == generation) {
                validGeneration = NO_GENERATION
            }
        }
    }

    override suspend fun attach(generation: Long, attachment: PreviewAttachment) {
        val androidAttachment = attachment as? AndroidPreviewAttachment
            ?: throw IllegalArgumentException("Unsupported preview attachment")
        withContext(Dispatchers.Main) {
            synchronized(generationLock) {
                validGeneration = generation
            }
            activeGeneration = generation
            activeAttachment = androidAttachment
            bindCurrentGeneration(generation)
        }
    }

    override suspend fun unbind(generation: Long) {
        withContext(Dispatchers.Main) {
            requireCurrentGeneration(generation)
            val provider = cameraProvider
                ?: throw IllegalStateException("Camera preview provider is not ready")
            // The current application intentionally owns only the preview use case.
            // Tearing down all CameraX use cases preserves the separate Camera2
            // single-capture boundary until multi-use-case ownership is introduced.
            provider.unbindAll()
            previewUseCase = null
        }
    }

    override suspend fun rebind(generation: Long) {
        withContext(Dispatchers.Main) {
            bindCurrentGeneration(generation)
        }
    }

    override suspend fun detach(generation: Long) {
        withContext(Dispatchers.Main) {
            if (activeGeneration != generation) {
                return@withContext
            }
            invalidate(generation)
            activeGeneration = null
            try {
                cameraProvider?.unbindAll()
            } finally {
                activeAttachment = null
                previewUseCase = null
            }
        }
    }

    private suspend fun bindCurrentGeneration(generation: Long) {
        requireCurrentGeneration(generation)
        val provider = getCameraProvider(generation)
        requireCurrentGeneration(generation)
        val attachment = activeAttachment ?: throw StalePreviewAttachmentException()

        provider.unbindAll()
        val useCase = Preview.Builder().build().also {
            it.setSurfaceProvider(attachment.previewView.surfaceProvider)
        }
        requireCurrentGeneration(generation)
        provider.bindToLifecycle(
            attachment.lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            useCase
        )
        previewUseCase = useCase
    }

    private fun requireCurrentGeneration(generation: Long) {
        if (!isGenerationValid(generation) || activeGeneration != generation) {
            throw StalePreviewAttachmentException()
        }
    }

    private fun isGenerationValid(generation: Long): Boolean {
        return synchronized(generationLock) {
            validGeneration == generation
        }
    }

    private suspend fun getCameraProvider(generation: Long): ProcessCameraProvider {
        cameraProvider?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!continuation.isActive) {
                    return@addListener
                }
                if (!isGenerationValid(generation) || activeGeneration != generation) {
                    continuation.cancel(
                        CancellationException("Camera preview attachment was replaced")
                    )
                    return@addListener
                }
                try {
                    val provider = future.get()
                    val accepted = synchronized(generationLock) {
                        if (continuation.isActive &&
                            validGeneration == generation &&
                            activeGeneration == generation
                        ) {
                            cameraProvider = provider
                            true
                        } else {
                            false
                        }
                    }
                    if (accepted) {
                        continuation.resume(provider)
                    } else if (continuation.isActive) {
                        continuation.cancel(
                            CancellationException("Camera preview attachment was replaced")
                        )
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
            }, ContextCompat.getMainExecutor(context))
            continuation.invokeOnCancellation {
                // ProcessCameraProvider owns a shared future. Cancelling one
                // attachment must not cancel provider resolution for the process.
            }
        }
    }

    private companion object {
        const val NO_GENERATION = Long.MIN_VALUE
    }
}
