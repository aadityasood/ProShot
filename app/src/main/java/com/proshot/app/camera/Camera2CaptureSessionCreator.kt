package com.proshot.app.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.view.Surface
import androidx.annotation.RequiresApi
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.inject.Inject
import javax.inject.Singleton

internal const val MODERN_SESSION_MIN_SDK = 28

/**
 * Pure generic validator for output configuration lists.
 */
object SessionOutputValidator {
    /** Returns the original ordered list after rejecting an empty output set. */
    @JvmStatic
    fun <T> validateOutputs(outputs: List<T>): List<T> {
        require(outputs.isNotEmpty()) { "Surfaces list must not be empty" }
        return outputs
    }
}

/**
 * Replaceable boundary for submitting Camera2 session requests.
 */
internal interface Camera2CaptureSessionCreator {
    fun createCaptureSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler
    )
}

internal enum class Camera2SessionApiPolicy {
    LEGACY_HANDLER,
    MODERN_CONFIGURATION
}

internal fun selectCamera2SessionApiPolicy(sdkInt: Int): Camera2SessionApiPolicy {
    return if (sdkInt >= MODERN_SESSION_MIN_SDK) {
        Camera2SessionApiPolicy.MODERN_CONFIGURATION
    } else {
        Camera2SessionApiPolicy.LEGACY_HANDLER
    }
}

internal fun interface HandlerPoster {
    fun post(command: Runnable): Boolean
}

internal class HandlerPosterExecutor(private val poster: HandlerPoster) : Executor {
    override fun execute(command: Runnable) {
        if (!poster.post(command)) {
            throw RejectedExecutionException("Execution rejected by the handler thread")
        }
    }
}

@Singleton
internal class AndroidCamera2CaptureSessionCreator @Inject constructor() :
    Camera2CaptureSessionCreator {

    override fun createCaptureSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler
    ) {
        val validatedSurfaces = SessionOutputValidator.validateOutputs(surfaces)
        when (selectCamera2SessionApiPolicy(Build.VERSION.SDK_INT)) {
            Camera2SessionApiPolicy.MODERN_CONFIGURATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    createModernCaptureSession(device, validatedSurfaces, callback, handler)
                } else {
                    error("Modern Camera2 session configuration requires API 28")
                }
            }
            Camera2SessionApiPolicy.LEGACY_HANDLER -> {
                createLegacyCaptureSession(device, validatedSurfaces, callback, handler)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createModernCaptureSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler
    ) {
        val executor = HandlerPosterExecutor(
            HandlerPoster { command -> handler.post(command) }
        )
        val sessionConfiguration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            surfaces.map { OutputConfiguration(it) },
            executor,
            callback
        )
        device.createCaptureSession(sessionConfiguration)
    }

    @Suppress("DEPRECATION")
    private fun createLegacyCaptureSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler
    ) {
        device.createCaptureSession(surfaces, callback, handler)
    }
}

/** Binds the production multi-surface creator behind its replaceable boundary. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class Camera2CaptureSessionCreatorModule {
    @Binds
    abstract fun bindCamera2CaptureSessionCreator(
        impl: AndroidCamera2CaptureSessionCreator
    ): Camera2CaptureSessionCreator
}
