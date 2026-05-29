package com.proshot.app.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun generateFilename_withNullSuffix_keepsDefaultFormat() {
        val timestamp = 1716035092123L
        val filenameWithNull = GalleryImageSaver.generateFilename(timestamp, null)
        val filenameDefault = GalleryImageSaver.generateFilename(timestamp)

        assertEquals(filenameDefault, filenameWithNull)
    }

    @Test
    fun generateFilename_withSafeSuffixes_appendsCorrectly() {
        val timestamp = 1716035092123L
        val safeLabels = listOf("baseline", "natural", "camera2-baseline", "test_123")

        for (suffix in safeLabels) {
            val filename = GalleryImageSaver.generateFilename(timestamp, suffix)
            val expectedSuffixPattern = Pattern.compile("^ProShot_\\d{8}_\\d{6}_\\d{3}_${Pattern.quote(suffix)}\\.jpg$")
            assertTrue(
                "Filename '$filename' with suffix '$suffix' did not match expected pattern",
                expectedSuffixPattern.matcher(filename).matches()
            )
        }
    }

    @Test
    fun generateFilename_withBlankSuffix_throwsIllegalArgumentException() {
        val timestamp = 1716035092123L
        val blankLabels = listOf("", "   ")

        for (suffix in blankLabels) {
            try {
                GalleryImageSaver.generateFilename(timestamp, suffix)
                fail("Expected IllegalArgumentException for blank suffix: '$suffix'")
            } catch (e: IllegalArgumentException) {
                // Expected
                assertEquals("Filename suffix must not be blank.", e.message)
            }
        }
    }

    @Test
    fun generateFilename_withUnsafeSuffixes_throwsIllegalArgumentException() {
        val timestamp = 1716035092123L
        val unsafeLabels = listOf("../raw", "bad/name", "bad name", "test.jpg", "@label", "natural!")

        for (suffix in unsafeLabels) {
            try {
                GalleryImageSaver.generateFilename(timestamp, suffix)
                fail("Expected IllegalArgumentException for unsafe suffix: '$suffix'")
            } catch (e: IllegalArgumentException) {
                // Expected
                assertEquals(
                    "Filename suffix contains unsafe characters. Only letters, numbers, underscores, and hyphens are allowed.",
                    e.message
                )
            }
        }
    }
}
