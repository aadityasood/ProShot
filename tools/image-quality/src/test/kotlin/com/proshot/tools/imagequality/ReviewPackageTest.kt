package com.proshot.tools.imagequality

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class ReviewPackageTest {

    private val temp = Files.createTempDirectory("proshot-package")

    @After
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun blind(root: Path, pkg: Path, key: Path, seed: String): Int =
        runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "$seed"))

    private fun chunkTypes(bytes: ByteArray): List<String> {
        val types = mutableListOf<String>()
        var pos = 8
        while (pos + 8 <= bytes.size) {
            val len = Png.readIntBE(bytes, pos)
            types += String(bytes, pos + 4, 4, StandardCharsets.US_ASCII)
            pos += 8 + len + 4
        }
        return types
    }

    /** Two-arm candidate dataset used to exercise a single comparison without A/A constraints. */
    private fun writeManualPairDataset(root: Path, images: Map<String, ByteArray>): List<Trial> {
        Files.createDirectories(root)
        val trials = images.entries.sortedBy { it.key }.mapIndexed { i, (name, bytes) ->
            val rel = "originals/$name"
            val path = root.resolve(rel)
            Files.createDirectories(path.parent)
            Files.write(path, bytes)
            val size = Files.size(path)
            val hash = Hashes.sha256File(path)
            val arm = if (i == 0) "candidate" else "baseline"
            TestData.trial(
                id = "T${i + 1}", scene = "city", condition = "sun", arm = arm, repetition = 1,
                captureOrder = i + 1, outcome = TrialOutcome.SUCCESS, original = rel,
            ).copy(originalHashSha256 = hash, originalByteSize = size, reviewSourceHashSha256 = hash, reviewSourceByteSize = size)
        }
        TestData.writeProperties(
            root,
            mapOf(
                "schema_version" to TOOL_SCHEMA_VERSION,
                "dataset_version" to "test-v1",
                "contract_version" to "T18.0-v1",
                "dataset_kind" to "CANDIDATE",
                "capture_protocol" to "handheld",
                "declared_arms" to "candidate,baseline",
                "required_repetitions" to "1",
                "app_identifier" to "proshot-test",
                "baseline_identifier" to "baseline",
                "candidate_identifier" to "candidate",
                "privacy_classification" to "PRIVATE",
                "predeclared_hypothesis" to "test hypothesis",
                "critical_scenes" to "city",
                "guardrails" to "latency_median_ms<=2000",
            ),
        )
        TestData.writeTrialsCsv(root, trials)
        TestData.writeComparisonCsv(
            root,
            listOf(ComparisonPlanRow("CMP_CB", "candidate", "baseline", ComparisonPurpose.CANDIDATE_VS_BASELINE)),
        )
        return trials
    }

    @Test
    fun wholeAndCropPngsAreMetadataFreeSrgb() {
        val root = temp.resolve("meta-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons(), withCrops = true)
        val pkg = temp.resolve("meta-pkg")
        val key = temp.resolve("meta-key")
        val seed = "1111111111111111111111111111111111111111111111111111111111111111"
        assertEquals(0, blind(root, pkg, key, seed))

        val pngs = TestData.listAllFiles(pkg.resolve("assets")).filter { it.toString().endsWith(".png") }
        assertTrue(pngs.isNotEmpty())
        for (png in pngs) {
            val bytes = Files.readAllBytes(png)
            Png.validateChunks(bytes)
            val types = chunkTypes(bytes)
            assertTrue("first chunk must be IHDR", types.first() == "IHDR")
            assertTrue("last chunk must be IEND", types.last() == "IEND")
            assertTrue("every interior chunk must be IDAT", types.subList(1, types.size - 1).all { it == "IDAT" })
            assertTrue("at least one IDAT chunk must exist", types.contains("IDAT"))
            assertFalse("PLTE chunk must not exist", types.contains("PLTE"))
            assertFalse("tEXt chunk must not exist", types.contains("tEXt"))
        }
        // Both whole and crop assets exist.
        assertTrue(pngs.any { it.toString().endsWith("_l_whole.png") || it.toString().endsWith("_r_whole.png") })
        assertTrue(pngs.any { it.toString().contains("_crop_") })
    }

    @Test
    fun injectedSourceMetadataDoesNotSurvive() {
        val sourcePng = TestData.pngWithTextMetadata("SECRET-COMMENT-LABEL")
        assertTrue("injected source PNG must contain tEXt chunk", chunkTypes(sourcePng).contains("tEXt"))

        val root = temp.resolve("meta-inject-root")
        writeManualPairDataset(
            root,
            mapOf(
                "inject-a.png" to sourcePng,
                "inject-b.png" to TestData.pngWithTextMetadata("SECRET-COMMENT-LABEL-2"),
            ),
        )
        val pkg = temp.resolve("meta-inject-pkg")
        val key = temp.resolve("meta-inject-key")
        assertEquals(0, blind(root, pkg, key, "2222222222222222222222222222222222222222222222222222222222222222"))

        for (png in TestData.listAllFiles(pkg.resolve("assets")).filter { it.toString().endsWith(".png") }) {
            val bytes = Files.readAllBytes(png)
            val types = chunkTypes(bytes)
            assertFalse("source metadata survived into $png", types.contains("tEXt"))
            assertFalse(String(bytes, StandardCharsets.ISO_8859_1).contains("SECRET"))
        }
    }

    @Test
    fun indexedInputIsConvertedToTrueColor() {
        val root = temp.resolve("indexed-root")
        writeManualPairDataset(
            root,
            mapOf("indexed-a.png" to TestData.indexedPng(0), "indexed-b.png" to TestData.indexedPng(37)),
        )
        val pkg = temp.resolve("indexed-pkg")
        val key = temp.resolve("indexed-key")
        assertEquals(0, blind(root, pkg, key, "3333333333333333333333333333333333333333333333333333333333333333"))
        for (png in TestData.listAllFiles(pkg.resolve("assets")).filter { it.toString().endsWith(".png") }) {
            val types = chunkTypes(Files.readAllBytes(png))
            assertFalse("PLTE present in generated asset", types.contains("PLTE"))
        }
    }

    @Test
    fun undecodableReviewSourceFailsExplicitly() {
        val root = temp.resolve("heic-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons(), imageExt = "heic")
        val heicPath = root.resolve("originals/TRIAL_baseline_pass_a_city_sun_1.heic")
        val error = assertThrows(ToolError::class.java) {
            ReviewSource.readReviewSource(heicPath)
        }
        assertEquals(Codes.REVIEW_SOURCE_UNDECODABLE, error.code)

        val pkg = temp.resolve("heic-pkg")
        val key = temp.resolve("heic-key")
        val exitCode = runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "4444444444444444444444444444444444444444444444444444444444444444"))
        assertNotEquals(0, exitCode)
    }

    @Test
    fun fixedSeedGivesExactReproducibleMapping() {
        val root = temp.resolve("seed-root")
        TestData.writeStandardDataset(root, DatasetKind.CANDIDATE, TestData.candidateComparisons(), withCrops = true)
        val seedA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val seedB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        val pkg1 = temp.resolve("seed-pkg1")
        val key1 = temp.resolve("seed-key1")
        val pkg2 = temp.resolve("seed-pkg2")
        val key2 = temp.resolve("seed-key2")
        assertEquals(0, blind(root, pkg1, key1, seedA))
        assertEquals(0, blind(root, pkg2, key2, seedA))
        assertTrue(TestData.filesEqual(key1, key2))

        val pkg3 = temp.resolve("seed-pkg3")
        val key3 = temp.resolve("seed-key3")
        assertEquals(0, blind(root, pkg3, key3, seedB))
        assertFalse(TestData.filesEqual(key1, key3))

        // The key records the seed and the mapping, and lives outside the package.
        val keyProps = StrictProperties.read(key1)
        assertEquals(seedA, keyProps.require("seed"))
        assertTrue(keyProps.get("pair.p01.comparison_id").isNullOrBlank().not())
        assertFalse(PathSecurity.isStrictlyUnder(key1, pkg1))
    }

    @Test
    fun packageContainsNoPrivateLabels() {
        val root = temp.resolve("privacy-root")
        val trials = TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons(), withCrops = true)
        val pkg = temp.resolve("privacy-pkg")
        val key = temp.resolve("privacy-key")
        val seed = "5555555555555555555555555555555555555555555555555555555555555555"
        assertEquals(0, blind(root, pkg, key, seed))

        val forbidden = buildSet {
            add(root.toString())
            add(seed)
            addAll(trials.map { it.trialId })
            addAll(trials.mapNotNull { it.originalPath })
            addAll(trials.mapNotNull { it.originalHashSha256 })
            addAll(listOf("baseline_pass_a", "baseline_pass_b", "TEST-PHONE-ABC123", "test-app", "camera-0"))
        }
        val allText = TestData.listAllFiles(pkg)
            .joinToString("\n") { String(Files.readAllBytes(it), StandardCharsets.ISO_8859_1) }
        for (token in forbidden) {
            assertFalse("package leaks '$token'", allText.contains(token))
        }
        // The key must NOT be part of the package.
        assertFalse(PathSecurity.isStrictlyUnder(key, pkg))
    }

    @Test
    fun productionPrivacyScanCatchesLeaks() {
        val root = temp.resolve("leak-root")
        TestData.writeStandardDataset(
            root,
            DatasetKind.CALIBRATION,
            TestData.calibrationComparisons(),
            arms = listOf("baseline_pass_a", "baseline_pass_b"),
            repetitions = 2,
        )
        // Use a non-coverage field such as app_identifier="note" to introduce a private token
        // known to appear in generated reviewer content (e.g. "note" in review.js).
        TestData.writeProperties(
            root,
            mapOf(
                "schema_version" to TOOL_SCHEMA_VERSION,
                "dataset_version" to "test-v1",
                "contract_version" to "T18.0-v1",
                "dataset_kind" to "CALIBRATION",
                "capture_protocol" to "handheld",
                "declared_arms" to "baseline_pass_a,baseline_pass_b",
                "required_repetitions" to "2",
                "app_identifier" to "note",
                "baseline_identifier" to "baseline",
                "privacy_classification" to "PRIVATE",
                "predeclared_hypothesis" to "test hypothesis",
                "critical_scenes" to "city",
                "guardrails" to "latency_median_ms<=2000",
            ),
        )
        val valResult = DatasetValidator.validateDataset(root)
        assertTrue("dataset validation must pass before runBlind", valResult.ok)

        val pkg = temp.resolve("leak-pkg")
        val key = temp.resolve("leak-key")
        val error = assertThrows(ToolError::class.java) {
            ReviewPackage.runBlind(root, pkg, key, "6666666666666666666666666666666666666666666666666666666666666666", 1200)
        }
        assertEquals(Codes.PRIVACY_LEAK, error.code)
    }

    @Test
    fun wholeAssetDerivedFromMetadataFreePngDoesNotRetainPrivateSourceHash() {
        val root = temp.resolve("hash-sep-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val trials = DatasetModel.load(root).trials
        val privateHashes = (trials.mapNotNull { it.originalHashSha256 } + trials.mapNotNull { it.reviewSourceHashSha256 }).toSet()
        assertTrue(privateHashes.isNotEmpty())

        val pkg = temp.resolve("hash-sep-pkg")
        val key = temp.resolve("hash-sep-key")
        val seed = "7777777777777777777777777777777777777777777777777777777777777777"
        assertEquals(0, blind(root, pkg, key, seed))

        val manifestText = Files.readString(pkg.resolve("manifest.properties"))
        val assetFiles = TestData.listAllFiles(pkg.resolve("assets"))
        assertTrue(assetFiles.isNotEmpty())

        for (asset in assetFiles) {
            val bytes = Files.readAllBytes(asset)
            val assetHash = Hashes.sha256(bytes)
            assertFalse("asset hash must not equal any private source hash", privateHashes.contains(assetHash))
            val decoded = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
            org.junit.Assert.assertNotNull(decoded)
            assertEquals(160, decoded.width)
            assertEquals(120, decoded.height)
        }

        for (privateHash in privateHashes) {
            assertFalse("manifest.properties must not leak private source hash '$privateHash'", manifestText.contains(privateHash))
        }
    }

    @Test
    fun idatHashSeparationPreservesEveryDecodedPixel() {
        val image = BufferedImage(73, 41, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val red = ((x * 37) and 0xFF) shl 16
                val green = ((y * 53) and 0xFF) shl 8
                val blue = (x * 11 + y * 17) and 0xFF
                image.setRGB(x, y, red or green or blue)
            }
        }
        val input = Png.encodePng(image)
        val inputHash = Hashes.sha256(input)
        val output = Png.ensureHashSeparation(input, setOf(inputHash))

        Png.validateChunks(input)
        Png.validateChunks(output)
        assertFalse(input.contentEquals(output))
        assertNotEquals(inputHash, Hashes.sha256(output))

        val decodedInput = ImageIO.read(ByteArrayInputStream(input))
        val decodedOutput = ImageIO.read(ByteArrayInputStream(output))
        assertEquals(decodedInput.width, decodedOutput.width)
        assertEquals(decodedInput.height, decodedOutput.height)
        for (y in 0 until decodedInput.height) {
            for (x in 0 until decodedInput.width) {
                assertEquals("ARGB mismatch at ($x,$y)", decodedInput.getRGB(x, y), decodedOutput.getRGB(x, y))
            }
        }
    }

    @Test
    fun malformedJpegAndPngOrientationMetadataFailsClosed() {
        val truncatedExif = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x00, 0x0E,
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            'I'.code.toByte(), 'I'.code.toByte(), 42, 0, 8, 0,
        )
        assertEquals(0, ReviewSource.exifOrientation(truncatedExif, "jpeg"))

        val invalidSegmentLength = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x00, 0x01,
        )
        assertEquals(0, ReviewSource.exifOrientation(invalidSegmentLength, "jpeg"))

        val malformedJpeg = temp.resolve("malformed-exif.jpg")
        Files.write(malformedJpeg, truncatedExif)
        val jpegError = assertThrows(ToolError::class.java) { ReviewSource.readReviewSource(malformedJpeg) }
        assertEquals(Codes.ORIENTATION_NOT_NORMALIZED, jpegError.code)

        val validPng = Png.encodePng(BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB))
        val ihdrEnd = 8 + 12 + 13
        val malformedExifChunk = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
            "eXIf".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0)
        val truncatedPngExif = validPng.copyOfRange(0, ihdrEnd) + malformedExifChunk
        assertEquals(0, ReviewSource.exifOrientation(truncatedPngExif, "png"))

        val truncatedChunk = validPng.copyOf(validPng.size - 2)
        assertEquals(0, ReviewSource.exifOrientation(truncatedChunk, "png"))

        val malformedPng = temp.resolve("malformed-exif.png")
        Files.write(malformedPng, truncatedPngExif)
        val pngError = assertThrows(ToolError::class.java) { ReviewSource.readReviewSource(malformedPng) }
        assertEquals(Codes.ORIENTATION_NOT_NORMALIZED, pngError.code)
    }

    @Test
    fun validImagesWithoutExifStayDisplayNormalAndJpegScanDataIsNotParsed() {
        val image = BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB)
        image.setRGB(2, 3, 0x0066AA)
        val png = Png.encodePng(image)
        assertEquals(1, ReviewSource.exifOrientation(png, "png"))

        val jpegOut = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, "jpeg", jpegOut))
        val jpeg = jpegOut.toByteArray()
        assertEquals(1, ReviewSource.exifOrientation(jpeg, "jpeg"))
        val jpegPath = temp.resolve("valid-no-exif.jpg")
        Files.write(jpegPath, jpeg)
        val decoded = ReviewSource.readReviewSource(jpegPath)
        assertEquals(image.width, decoded.width)
        assertEquals(image.height, decoded.height)

        val markerLikeScanData = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08,
            0x01, 0x01, 0x00, 0x00, 0x3F, 0x00,
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x01,
        )
        assertEquals(1, ReviewSource.exifOrientation(markerLikeScanData, "jpeg"))
    }

    @Test
    fun responseSchemaV2AndDefectSidesAreCanonicalAndBound() {
        assertEquals("2", ResponseSchema.VERSION)
        assertEquals(listOf("LEFT", "RIGHT", "BOTH"), ResponseSchema.DEFECT_SIDES)
        assertEquals(3, ResponseSchema.DEFECT_SIDES.toSet().size)
        assertTrue(ResponseSchema.canonicalText().startsWith("RESPONSE_SCHEMA_V2\n"))
        assertTrue(ResponseSchema.canonicalText().contains("DEFECT_SIDES=BOTH,LEFT,RIGHT\n"))
    }

    @Test
    fun htmlIsSelfContainedCspAndIdentitySafe() {
        val root = temp.resolve("html-root")
        TestData.writeStandardDataset(root, DatasetKind.CANDIDATE, TestData.candidateComparisons(), withCrops = true)
        val pkg = temp.resolve("html-pkg")
        val key = temp.resolve("html-key")
        assertEquals(0, blind(root, pkg, key, "7777777777777777777777777777777777777777777777777777777777777777"))

        val html = Files.readString(pkg.resolve("review.html"))
        val js = Files.readString(pkg.resolve("review.js"))
        val css = Files.readString(pkg.resolve("review.css"))

        assertTrue(html.contains("Content-Security-Policy"))
        assertTrue(html.contains("script-src 'self'"))
        assertTrue(html.contains("img-src 'self'"))
        assertFalse(html.contains("http://"))
        assertFalse(html.contains("https://"))
        assertFalse(js.contains("http://"))
        assertFalse(js.contains("https://"))
        assertFalse(css.contains("http://"))
        assertFalse(css.contains("https://"))

        // The exact response schema columns appear in the export code in order.
        val schemaArray = ResponseSchema.COLUMNS.joinToString(",")
        assertTrue(js.contains("SCHEMA = [" + ResponseSchema.COLUMNS.joinToString(",") { "\"$it\"" } + "]"))

        // The defect sides array is serialized from ResponseSchema.DEFECT_SIDES and populates the selector.
        val defectSidesArray = ResponseSchema.DEFECT_SIDES.joinToString(",") { "\"$it\"" }
        assertTrue(js.contains("DEFECT_SIDES = [$defectSidesArray];"))
        assertTrue(js.contains("DEFECT_SIDES.forEach(function (side)"))
        assertFalse(js.contains("[\"LEFT\", \"RIGHT\"].forEach(function (side)"))

        // No arm names, trial ids, or source filenames in the page.
        val trials = DatasetModel.load(root).trials
        for (t in trials) {
            assertFalse(html.contains(t.trialId))
            assertFalse(js.contains(t.trialId))
        }
        assertFalse(html.contains("candidate"))
        assertFalse(html.contains("baseline"))
        assertFalse(html.contains("originals/"))
    }

    @Test
    fun malformedOrOversizedPngChunkLengthsFailStably() {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        // Length 0xFFFFFFFF: unsigned value far beyond the file size.
        val huge = signature + byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
            "IHDR".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0, 0, 0, 0, 0, 0)
        val e1 = assertThrows(ToolError::class.java) { Png.validateChunks(huge) }
        assertTrue(e1.code, e1.code == Codes.PNG_TRUNCATED || e1.code == Codes.PNG_BAD_SIGNATURE)

        // Length 0x80000000: negative when read as signed int; must not index out of bounds.
        val negative = signature + byteArrayOf(0x80.toByte(), 0, 0, 0) +
            "IHDR".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0, 0, 0, 0)
        val e2 = assertThrows(ToolError::class.java) { Png.validateChunks(negative) }
        assertTrue(e2.code, e2.code == Codes.PNG_TRUNCATED || e2.code == Codes.PNG_BAD_SIGNATURE)

        // Length that overflows an int add when computing the data end.
        val overflow = signature + byteArrayOf(0x7F.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
            "IHDR".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        val e3 = assertThrows(ToolError::class.java) { Png.validateChunks(overflow) }
        assertTrue(e3.code, e3.code == Codes.PNG_TRUNCATED || e3.code == Codes.PNG_BAD_SIGNATURE)
    }

    @Test
    fun keyInsidePackageRejectedLexicallyAndByMissingParents() {
        val root = temp.resolve("keyloc-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val pkg = temp.resolve("keyloc-pkg")
        val seed = "1010101010101010101010101010101010101010101010101010101010101010"

        // Lexically inside the package directory (package dir exists but is empty).
        Files.createDirectories(pkg)
        val inside = runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$pkg/inside.key", "--seed", "$seed"))
        assertNotEquals(0, inside)

        // Key parent directory does not exist.
        val missingParent = runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "${temp.resolve("no-dir/key")}", "--seed", "$seed"))
        assertNotEquals(0, missingParent)

        // Package output parent directory does not exist.
        val missingOutParent = runCli(arrayOf("blind", "--root", "$root", "--out-dir", "${temp.resolve("no-dir/pkg")}", "--key", "${temp.resolve("k2")}", "--seed", "$seed"))
        assertNotEquals(0, missingOutParent)

        // A key outside the package (lexically and physically) is accepted.
        assertEquals(0, runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "${temp.resolve("outside.key")}", "--seed", "$seed")))
    }

    @Test
    fun keyInsidePackageRejectedThroughResolvedParentLinksWhenHostPermits() {
        val root = temp.resolve("keyloc-link-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val pkg = temp.resolve("keyloc-link-pkg")
        Files.createDirectories(pkg)
        val alias = temp.resolve("keyloc-link-alias")
        try {
            Files.createSymbolicLink(alias, pkg)
        } catch (e: Exception) {
            System.err.println("SYMLINK_CREATION_UNAVAILABLE: host denied link creation; resolved-parent key containment not exercised")
            return
        }
        // The key is lexically OUTSIDE the package (under 'alias') but its real
        // parent resolves into the package, so it must be rejected.
        val code = runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "${alias.resolve("inside.key")}", "--seed", "1010101010101010101010101010101010101010101010101010101010101010"))
        assertNotEquals(0, code)
    }

    @Test
    fun productionLabelMapKeysEqualCanonicalResponseSchemaSets() {
        assertEquals(ResponseSchema.REASON_TAGS.toSet(), ReviewPageLabels.REASON_LABELS.keys)
        assertEquals(ResponseSchema.DEFECT_TAGS.toSet(), ReviewPageLabels.DEFECT_LABELS.keys)
        assertEquals(7, ReviewPageLabels.REASON_LABELS.size)
        assertEquals(5, ReviewPageLabels.DEFECT_LABELS.size)
    }

    @Test
    fun canonicalReasonAndDefectValuesArePairedWithExactVisibleLabels() {
        val root = temp.resolve("labels-root")
        TestData.writeStandardDataset(root, DatasetKind.CANDIDATE, TestData.candidateComparisons())
        val pkg = temp.resolve("labels-pkg")
        val key = temp.resolve("labels-key")
        assertEquals(0, blind(root, pkg, key, "1111111111111111111111111111111111111111111111111111111111111111"))

        val js = Files.readString(pkg.resolve("review.js"))
        for ((canonical, label) in ReviewPageLabels.REASON_LABELS) {
            assertTrue("JS must pair reason '$canonical' with label '$label'", js.contains("\"$canonical\":\"$label\""))
        }

        for ((canonical, label) in ReviewPageLabels.DEFECT_LABELS) {
            assertTrue("JS must pair defect '$canonical' with label '$label'", js.contains("\"$canonical\":\"$label\""))
        }
    }

    @Test
    fun sourceOrderingEnforcesInvalidReturnGuardBeforeBlobCreation() {
        val root = temp.resolve("order-root")
        TestData.writeStandardDataset(root, DatasetKind.CANDIDATE, TestData.candidateComparisons())
        val pkg = temp.resolve("order-pkg")
        val key = temp.resolve("order-key")
        assertEquals(0, blind(root, pkg, key, "1111111111111111111111111111111111111111111111111111111111111111"))

        val js = Files.readString(pkg.resolve("review.js"))
        val handlerStart = js.indexOf("document.getElementById(\"exportBtn\").addEventListener")
        assertTrue("exportBtn listener must exist in JS", handlerStart >= 0)
        val exportHandler = js.substring(handlerStart)

        val blobMatches = "new Blob".toRegex().findAll(exportHandler).toList()
        assertEquals("exportHandler must contain exactly one 'new Blob'", 1, blobMatches.size)

        val guardIndex = exportHandler.indexOf("if (!result.valid)")
        assertTrue("if (!result.valid) guard must exist in exportHandler", guardIndex >= 0)

        val returnIndex = exportHandler.indexOf("return;", guardIndex)
        assertTrue("return; must exist after if (!result.valid) guard", returnIndex > guardIndex)

        val blobIndex = blobMatches[0].range.first
        assertTrue("invalid branch return must occur before Blob creation", returnIndex < blobIndex)
    }

    @Test
    fun generatedPageKeepsCsvAreaEmptyAndReturnsBeforeBlobWhileInvalid() {
        val root = temp.resolve("invalid-csv-root")
        TestData.writeStandardDataset(root, DatasetKind.CANDIDATE, TestData.candidateComparisons())
        val pkg = temp.resolve("invalid-csv-pkg")
        val key = temp.resolve("invalid-csv-key")
        assertEquals(0, blind(root, pkg, key, "4444444444444444444444444444444444444444444444444444444444444444"))

        val js = Files.readString(pkg.resolve("review.js"))
        assertTrue("JS refresh must clear csvArea.value = \"\" when invalid", js.contains("csvArea.value = \"\";"))

        val handlerStart = js.indexOf("document.getElementById(\"exportBtn\").addEventListener")
        val exportHandler = js.substring(handlerStart)
        val guardIndex = exportHandler.indexOf("if (!result.valid)")
        val returnIndex = exportHandler.indexOf("return;", guardIndex)
        val blobIndex = exportHandler.indexOf("new Blob")

        assertTrue("Guard must exist", guardIndex >= 0)
        assertTrue("Return must follow guard", returnIndex > guardIndex)
        assertTrue("Return must precede Blob creation", returnIndex < blobIndex)
    }

    @Test
    fun initialErrorsSuppressedUntilExportAttempt() {
        val root = temp.resolve("suppress-root")
        TestData.writeStandardDataset(root, DatasetKind.CANDIDATE, TestData.candidateComparisons())
        val pkg = temp.resolve("suppress-pkg")
        val key = temp.resolve("suppress-key")
        assertEquals(0, blind(root, pkg, key, "2222222222222222222222222222222222222222222222222222222222222222"))

        val js = Files.readString(pkg.resolve("review.js"))
        assertTrue(js.contains("var hasAttemptedExport = false;"))
        assertTrue(js.contains("hasAttemptedExport = true;"))
        assertTrue(js.contains("if (hasAttemptedExport && result.errors.length > 0)"))
    }

    @Test
    fun HTMLAndCSSPreserveAccessibilityAndResponsiveConstraintsWithoutOverflowHidden() {
        val root = temp.resolve("a11y-root")
        TestData.writeStandardDataset(root, DatasetKind.CANDIDATE, TestData.candidateComparisons())
        val pkg = temp.resolve("a11y-pkg")
        val key = temp.resolve("a11y-key")
        assertEquals(0, blind(root, pkg, key, "3333333333333333333333333333333333333333333333333333333333333333"))

        val html = Files.readString(pkg.resolve("review.html"))
        val css = Files.readString(pkg.resolve("review.css"))

        // Accessibility attributes
        assertTrue(html.contains("aria-live=\"polite\""))
        assertTrue(html.contains("role=\"region\""))
        assertTrue(html.contains("tabindex=\"-1\""))

        // CSS responsive sizing and media query
        assertTrue(css.contains("display: inline-flex"))
        assertTrue(css.contains("white-space: nowrap"))
        assertTrue(css.contains("max-width: 100%"))
        assertTrue(css.contains("@media (max-width:"))

        // No overflow-x: hidden
        assertFalse("CSS must not use overflow-x: hidden", css.contains("overflow-x: hidden"))

        // Note: Real browser rendering remains manual smoke test evidence.
    }
}
