package com.proshot.app.output

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Result of saving an image to the device's system gallery.
 */
sealed class GallerySaveResult {
    /**
     * Successfully saved photo.
     *
     * @property uri Scoped MediaStore [Uri], the single source of truth for the file.
     * @property displayName The formatted public display name.
     * @property relativePath The subdirectory where the image was stored, if supported (API 29+).
     */
    data class Success(
        val uri: Uri,
        val displayName: String,
        val relativePath: String?
    ) : GallerySaveResult()

    /**
     * Failed to save photo.
     *
     * @property userReason A friendly, localized error description suitable for UI presentation.
     * @property cause The raw underlying exception, if any, for diagnostic logging.
     */
    data class Failure(
        val userReason: String,
        val cause: Throwable? = null
    ) : GallerySaveResult()
}

/**
 * Orchestrates saving captured image files into the system gallery utilizing Scoped Storage
 * on Android 10+ (API 29+) and checked legacy shared storage on older OS versions.
 *
 * Runs exclusively in the background on [Dispatchers.IO].
 *
 * TODO: Convert to Hilt-injectable class before integration tests require mock saver.
 */
object GalleryImageSaver {
    private const val TAG = "GalleryImageSaver"
    private const val ALBUM_PATH = "Pictures/ProShot"

    /**
     * Generates a stable, standard filename based on current timestamp.
     */
    fun generateFilename(timestampMs: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "ProShot_${sdf.format(Date(timestampMs))}.jpg"
    }

    /**
     * Writes JPEG byte arrays directly into the system's [MediaStore] library.
     *
     * @param context The application or activity context.
     * @param jpegBytes The raw encoded JPEG image data.
     * @param timestampMs The epoch timestamp in milliseconds when the image was captured.
     * @return [GallerySaveResult] indicating the success details or friendly failure reason.
     */
    suspend fun saveToGallery(
        context: Context,
        jpegBytes: ByteArray,
        timestampMs: Long = System.currentTimeMillis()
    ): GallerySaveResult = withContext(Dispatchers.IO) {
        if (jpegBytes.isEmpty()) {
            Log.w(TAG, "Refusing to save empty JPEG data")
            return@withContext GallerySaveResult.Failure(
                userReason = "Image data was empty"
            )
        }

        // 1. Android 9 and lower: Verify legacy storage write permissions.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val hasWritePermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasWritePermission) {
                Log.w(TAG, "Legacy write permission is missing at runtime on API ${Build.VERSION.SDK_INT}")
                return@withContext GallerySaveResult.Failure(
                    userReason = "Storage permission missing. Please grant storage access in settings to save photos on this Android version."
                )
            }
        }

        val resolver = context.contentResolver
        val filename = generateFilename(timestampMs)

        // 2. Prepare explicit MediaStore metadata
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, timestampMs)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        var itemUri: Uri? = null
        try {
            // 3. Insert metadata record to reserve the Uri
            itemUri = resolver.insert(collectionUri, contentValues)
                ?: throw IOException("Failed to create MediaStore entry for $filename")

            // 4. Stream raw JPEG bytes to the reserved location
            val outputStream: OutputStream = resolver.openOutputStream(itemUri)
                ?: throw IOException("Failed to open output stream for Uri: $itemUri")

            outputStream.use { stream ->
                stream.write(jpegBytes)
                stream.flush()
            }

            // 5. Complete write transaction (removes pending flag) on Android 10+
            val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                val rowsUpdated = resolver.update(itemUri, contentValues, null, null)
                if (rowsUpdated == 0) {
                    Log.e(TAG, "IS_PENDING clear failed (update returned 0) for $itemUri")
                    cleanupOrphanRow(context, itemUri)
                    return@withContext GallerySaveResult.Failure(
                        userReason = "Photo was saved but could not be published to the gallery"
                    )
                }
                ALBUM_PATH
            } else {
                null
            }

            Log.d(TAG, "Successfully saved image to system gallery: $itemUri")
            GallerySaveResult.Success(
                uri = itemUri,
                displayName = filename,
                relativePath = relativePath
            )
        } catch (e: CancellationException) {
            Log.d(TAG, "Gallery save cancelled, cleaning up orphan row", e)
            cleanupOrphanRow(context, itemUri)
            throw e
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException saving image to MediaStore", e)
            cleanupOrphanRow(context, itemUri)
            GallerySaveResult.Failure(
                userReason = "Storage permission denied",
                cause = e
            )
        } catch (e: IOException) {
            Log.e(TAG, "IOException saving image to MediaStore", e)
            cleanupOrphanRow(context, itemUri)
            GallerySaveResult.Failure(
                userReason = "Disk full or storage unavailable",
                cause = e
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception saving image to MediaStore", e)
            cleanupOrphanRow(context, itemUri)
            GallerySaveResult.Failure(
                userReason = "Failed to save photo",
                cause = e
            )
        }
    }

    private fun cleanupOrphanRow(context: Context, uri: Uri?) {
        if (uri == null) return
        try {
            context.contentResolver.delete(uri, null, null)
            Log.d(TAG, "Successfully cleaned up partial/orphan MediaStore row: $uri")
        } catch (delEx: Exception) {
            Log.w(TAG, "Failed to clean up orphan MediaStore row: $uri", delEx)
        }
    }
}
