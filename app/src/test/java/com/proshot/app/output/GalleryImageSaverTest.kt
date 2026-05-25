package com.proshot.app.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Unit tests verifying helper functions in [GalleryImageSaver].
 */
class GalleryImageSaverTest {

    @Test
    fun generateFilename_returnsCorrectRegexPattern() {
        val timestamp = 1716035092123L // Sometime in May 2024
        val filename = GalleryImageSaver.generateFilename(timestamp)

        // Verify the filename strictly follows the pattern ProShot_YYYYMMDD_HHMMSS_SSS.jpg
        val pattern = Pattern.compile("^ProShot_\\d{8}_\\d{6}_\\d{3}\\.jpg$")
        val matcher = pattern.matcher(filename)

        assertTrue("Filename '$filename' did not match expected pattern", matcher.matches())
    }

    @Test
    fun generateFilename_differsForDifferentTimestamps() {
        val time1 = 1716035092123L
        val time2 = 1716035092124L // 1 millisecond later

        val filename1 = GalleryImageSaver.generateFilename(time1)
        val filename2 = GalleryImageSaver.generateFilename(time2)

        assertTrue("Filenames should be unique for distinct timestamps", filename1 != filename2)
    }
}
