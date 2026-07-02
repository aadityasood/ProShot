package com.proshot.app.camera

import android.content.Context
import android.util.Log
import com.proshot.app.output.CapturedImageEncoder
import com.proshot.app.output.GalleryImageSaver
import com.proshot.app.output.GallerySaveResult
import com.proshot.app.processing.colorscience.LookProfileNv21Processor
import com.proshot.app.processing.style.LookProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of the capture orchestration flow.
 */
sealed class CaptureResult {
    /**
     * Capture completed successfully.
     *
     * @property message A friendly, localized success message suitable for UI presentation.
     */
    data class Success(val message: String) : CaptureResult()

    /**
     * Capture failed to complete.
     *
     * @property message A friendly, localized error description suitable for UI presentation.
     * @property cause The raw underlying exception, if any, for diagnostic logging.
     */
    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : CaptureResult()
}

/**
 * Functional interface to report intermediate pipeline progress back to the UI.
 */
fun interface StatusCallback {
    /**
     * Invoked when the pipeline transitions to a new state.
     */
    fun onStatusChanged(status: String)
}

/**
 * Core Kotlin coordinator orchestrating physical frame capture, orientation adjustments,
 * look profile application, compression, and media storage saving.
 *
 * Separates pipeline timing and file writing from Compose UI states, executing heavy stages
 * on appropriate background dispatchers ([Dispatchers.Default] for conversion/processing,
 * [Dispatchers.IO] for file I/O).
 */
object CaptureCoordinator {
    private const val TAG = "CaptureCoordinator"

    /**
     * Orchestrates the complete capture-to-save sequence.
     *
     * @param context The application or activity context.
     * @param lookProfile The active color/tone [LookProfile] to apply.
     * @param isDebug Policy flag determining if paired baseline diagnostics are saved.
     * @param statusCallback Progress listener receiving beginner-friendly pipeline updates.
     * @return [CaptureResult] representing the high-level outcome of the capture transaction.
     */
    suspend fun executeCapture(
        context: Context,
        lookProfile: LookProfile,
        isDebug: Boolean,
        tracker: CaptureTimingTracker? = null,
        diagnosticsTracker: FocusLensDiagnosticsTracker? = null,
        focusTarget: FocusMeteringTarget = FocusMeteringTarget.center(),
        statusCallback: StatusCallback
    ): CaptureResult {
        return try {
            statusCallback.onStatusChanged("Initiating capture...")
            val frame = withContext(Dispatchers.Default) {
                SingleFrameCaptureController.captureSingleFrame(
                    context = context,
                    tracker = tracker,
                    diagnosticsTracker = diagnosticsTracker,
                    focusTarget = focusTarget
                )
            }
            val outputRotationDegrees = withContext(Dispatchers.IO) {
                SingleFrameCaptureController.resolveOutputRotationDegrees(context)
            }

            statusCallback.onStatusChanged("Encoding captured frame...")
            val conversionStart = tracker?.let { System.nanoTime() }
            val orientedNv21 = withContext(Dispatchers.Default) {
                val nv21 = CapturedImageEncoder.yuv420ToNv21(frame)
                CapturedImageEncoder.rotateNv21(
                    nv21 = nv21,
                    width = frame.width,
                    height = frame.height,
                    rotationDegrees = outputRotationDegrees
                )
            }
            if (conversionStart != null) {
                tracker?.yuvToNv21AndRotateMs = (System.nanoTime() - conversionStart) / 1_000_000L
            }

            val captureTimestampMs = System.currentTimeMillis()

            var baselineSaveResult: GallerySaveResult? = null
            if (isDebug) {
                val baselineSaveStart = tracker?.let { System.nanoTime() }
                val baselineJpegBytes = withContext(Dispatchers.Default) {
                    CapturedImageEncoder.compressNv21ToJpeg(
                        nv21 = orientedNv21.data,
                        width = orientedNv21.width,
                        height = orientedNv21.height
                    )
                }
                statusCallback.onStatusChanged("Saving baseline photo...")
                baselineSaveResult = GalleryImageSaver.saveToGallery(
                    context = context,
                    jpegBytes = baselineJpegBytes,
                    timestampMs = captureTimestampMs,
                    filenameSuffix = "baseline"
                )
                if (baselineSaveStart != null) {
                    tracker?.baselineSaveMs = (System.nanoTime() - baselineSaveStart) / 1_000_000L
                }
            }

            // Wrap post-baseline stages so that if processing, compression, or
            // natural save fails after a successful baseline save, the coordinator
            // returns a partial-save diagnostic message instead of a generic failure.
            try {
                statusCallback.onStatusChanged("Processing photo...")
                val processStart = tracker?.let { System.nanoTime() }
                val processedNv21 = withContext(Dispatchers.Default) {
                    LookProfileNv21Processor.apply(orientedNv21, lookProfile)
                }
                if (processStart != null) {
                    tracker?.lookProfileProcessMs = (System.nanoTime() - processStart) / 1_000_000L
                }

                val naturalSaveStart = tracker?.let { System.nanoTime() }
                val jpegBytes = withContext(Dispatchers.Default) {
                    CapturedImageEncoder.compressNv21ToJpeg(
                        nv21 = processedNv21.data,
                        width = processedNv21.width,
                        height = processedNv21.height
                    )
                }

                statusCallback.onStatusChanged("Saving to gallery...")
                val saveResult = GalleryImageSaver.saveToGallery(
                    context = context,
                    jpegBytes = jpegBytes,
                    timestampMs = captureTimestampMs,
                    filenameSuffix = if (isDebug) "natural" else null
                )
                if (naturalSaveStart != null) {
                    tracker?.naturalSaveMs = (System.nanoTime() - naturalSaveStart) / 1_000_000L
                }

                mapOutcome(saveResult, baselineSaveResult, isDebug)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                mapPostBaselineException(e, baselineSaveResult, isDebug)
            } catch (e: Exception) {
                mapPostBaselineException(e, baselineSaveResult, isDebug)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            mapLoggedException(e)
        } catch (e: Exception) {
            mapLoggedException(e)
        }
    }

    /**
     * Maps the underlying MediaStore save outcomes to simplified, beginner-friendly UI results.
     *
     * Public visibility allows pure JVM unit testing of diagnostic paired output policies.
     */
    fun mapOutcome(
        saveResult: GallerySaveResult,
        baselineSaveResult: GallerySaveResult?,
        isDebug: Boolean
    ): CaptureResult {
        return mapOutcome(
            saveOutcome = saveResult.toSaveOutcome(),
            baselineSaveOutcome = baselineSaveResult?.toSaveOutcome(),
            isDebug = isDebug
        )
    }

    internal fun mapOutcome(
        saveOutcome: SaveOutcome,
        baselineSaveOutcome: SaveOutcome?,
        isDebug: Boolean
    ): CaptureResult {
        return if (isDebug) {
            if (saveOutcome.isSuccess) {
                if (baselineSaveOutcome?.isSuccess == true) {
                    CaptureResult.Success("Saved diagnostic pair")
                } else {
                    val reason = baselineSaveOutcome?.userReason ?: "unknown error"
                    CaptureResult.Success("Saved natural; baseline failed: $reason")
                }
            } else {
                if (baselineSaveOutcome?.isSuccess == true) {
                    CaptureResult.Failure("Saved baseline; natural failed: ${saveOutcome.userReason}")
                } else {
                    CaptureResult.Failure("Save failed: ${saveOutcome.userReason}")
                }
            }
        } else {
            if (saveOutcome.isSuccess) {
                CaptureResult.Success("Saved to gallery")
            } else {
                CaptureResult.Failure("Save failed: ${saveOutcome.userReason}")
            }
        }
    }

    /**
     * Maps unexpected exceptions to simplified, beginner-friendly failure results.
     *
     * Public visibility allows pure JVM unit testing of error policy mapping.
     */
    fun mapException(e: Throwable): CaptureResult {
        return when (e) {
            is IllegalArgumentException -> CaptureResult.Failure("Capture failed: invalid image data", e)
            is OutOfMemoryError -> CaptureResult.Failure("Not enough memory to save photo", e)
            else -> CaptureResult.Failure("Capture failed: system error", e)
        }
    }

    private fun mapPostBaselineException(
        e: Throwable,
        baselineSaveResult: GallerySaveResult?,
        isDebug: Boolean
    ): CaptureResult {
        return if (isDebug && baselineSaveResult is GallerySaveResult.Success) {
            Log.e(TAG, "Post-baseline processing/save failed", e)
            val reason = mapException(e) as CaptureResult.Failure
            CaptureResult.Failure("Saved baseline; natural failed: ${reason.message}", e)
        } else {
            mapLoggedException(e)
        }
    }

    private fun mapLoggedException(e: Throwable): CaptureResult {
        Log.e(TAG, "Capture pipeline failed", e)
        return mapException(e)
    }
}

internal data class SaveOutcome(
    val isSuccess: Boolean,
    val userReason: String? = null
)

private fun GallerySaveResult.toSaveOutcome(): SaveOutcome {
    return when (this) {
        is GallerySaveResult.Success -> SaveOutcome(isSuccess = true)
        is GallerySaveResult.Failure -> SaveOutcome(isSuccess = false, userReason = userReason)
    }
}
