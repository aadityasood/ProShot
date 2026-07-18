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

/**
 * Replaceable boundary for submitting the current one-surface Camera2 session request.
 */
internal interface Camera2CaptureSessionCreator {
    /**
     * Submits a regular capture session on [device] for exactly one [surface].
     *
     * Callback delivery uses the per-capture [handler]. Framework submission exceptions
     * propagate to the caller without being remapped by this boundary.
     */
    fun createCaptureSession(
        device: CameraDevice,
        surface: Surface,
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

/**
 * Pure posting seam for executor tests that do not depend on Android framework stubs.
 */
internal fun interface HandlerPoster {
    fun post(command: Runnable): Boolean
}

/**
 * An [Executor] implementation that dispatches runnables through a [HandlerPoster],
 * throwing a [RejectedExecutionException] if the post is rejected.
 *
 * Camera2 may invoke this executor asynchronously, so framework code is not
 * guaranteed to propagate a later rejection back to the original session submission.
 * The capture timeout and resource owner's terminal policy remain the bounded fallback
 * when a framework callback cannot be delivered.
 */
internal class HandlerPosterExecutor(private val poster: HandlerPoster) : Executor {
    override fun execute(command: Runnable) {
        if (!poster.post(command)) {
            throw RejectedExecutionException("Execution rejected by the handler thread")
        }
    }
}

/**
 * Production implementation of [Camera2CaptureSessionCreator] that targets modern API 28+
 * session configuration or falls back to legacy deprecated APIs on API 26-27.
 */
@Singleton
internal class AndroidCamera2CaptureSessionCreator @Inject constructor() :
    Camera2CaptureSessionCreator {

    override fun createCaptureSession(
        device: CameraDevice,
        surface: Surface,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler
    ) {
        when (selectCamera2SessionApiPolicy(Build.VERSION.SDK_INT)) {
            Camera2SessionApiPolicy.MODERN_CONFIGURATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    createModernCaptureSession(device, surface, callback, handler)
                } else {
                    error("Modern Camera2 session configuration requires API 28")
                }
            }
            Camera2SessionApiPolicy.LEGACY_HANDLER -> {
                createLegacyCaptureSession(device, surface, callback, handler)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createModernCaptureSession(
        device: CameraDevice,
        surface: Surface,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler
    ) {
        val executor = HandlerPosterExecutor(
            HandlerPoster { command -> handler.post(command) }
        )
        val sessionConfiguration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface)),
            executor,
            callback
        )
        device.createCaptureSession(sessionConfiguration)
    }

    @Suppress("DEPRECATION")
    private fun createLegacyCaptureSession(
        device: CameraDevice,
        surface: Surface,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler
    ) {
        device.createCaptureSession(listOf(surface), callback, handler)
    }
}

/**
 * Hilt module binding [AndroidCamera2CaptureSessionCreator] to [Camera2CaptureSessionCreator].
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class Camera2CaptureSessionCreatorModule {
    @Binds
    abstract fun bindCamera2CaptureSessionCreator(
        impl: AndroidCamera2CaptureSessionCreator
    ): Camera2CaptureSessionCreator
}

private const val MODERN_SESSION_MIN_SDK = 28
