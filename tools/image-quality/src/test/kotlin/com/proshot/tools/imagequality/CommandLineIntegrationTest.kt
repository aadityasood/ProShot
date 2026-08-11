package com.proshot.tools.imagequality

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier

/** Synthetic fixtures and end-to-end CLI tests. No real photograph is ever used. */
internal object TestData {

    private fun encode(image: BufferedImage, format: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, format, baos)
        return baos.toByteArray()
    }

    fun syntheticPng(width: Int = 160, height: Int = 120, base: Int = 40): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                img.setRGB(x, y, Color((base + x) % 256, (base + y) % 256, (base + x + y) % 256).rgb)
            }
        }
        return encode(img, "png")
    }

    fun syntheticJpeg(width: Int = 160, height: Int = 120, base: Int = 90): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                img.setRGB(x, y, Color((base + x * 2) % 256, (base + y * 2) % 256, (base + x + y) % 256).rgb)
            }
        }
        return encode(img, "jpg")
    }

    /** PNG with an injected tEXt chunk used to prove source metadata does not survive. */
    fun pngWithTextMetadata(text: String): ByteArray {
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 64) for (x in 0 until 64) img.setRGB(x, y, Color(x * 4, y * 4, (x + y) * 2).rgb)
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        val baos = ByteArrayOutputStream()
        val ios = ImageIO.createImageOutputStream(baos)
        writer.output = ios
        val param = writer.defaultWriteParam
        val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), param)
        val root = javax.imageio.metadata.IIOMetadataNode("javax_imageio_png_1.0")
        val textNode = javax.imageio.metadata.IIOMetadataNode("tEXt")
        val textEntryNode = javax.imageio.metadata.IIOMetadataNode("tEXtEntry")
        textEntryNode.setAttribute("keyword", "Comment")
        textEntryNode.setAttribute("value", text)
        textNode.appendChild(textEntryNode)
        root.appendChild(textNode)
        metadata.mergeTree("javax_imageio_png_1.0", root)
        writer.write(null, IIOImage(img, null, metadata), param)
        writer.dispose()
        ios.close()
        return baos.toByteArray()
    }

    /** Indexed-color PNG that must be converted to true-color sRGB so PLTE is not required. */
    fun indexedPng(offset: Int = 0): ByteArray {
        val r = ByteArray(256)
        val g = ByteArray(256)
        val b = ByteArray(256)
        for (i in 0 until 256) {
            r[i] = i.toByte(); g[i] = (255 - i).toByte(); b[i] = ((i * 3) % 256).toByte()
        }
        val cm = IndexColorModel(8, 256, r, g, b)
        val img = BufferedImage(64, 64, BufferedImage.TYPE_BYTE_INDEXED, cm)
        for (y in 0 until 64) for (x in 0 until 64) img.raster.setSample(x, y, 0, (x + y + offset) and 0xFF)
        return encode(img, "png")
    }

    fun heicBytes(): ByteArray {
        val bytes = ByteArray(64)
        bytes[4] = 'f'.code.toByte(); bytes[5] = 't'.code.toByte(); bytes[6] = 'y'.code.toByte(); bytes[7] = 'p'.code.toByte()
        bytes[8] = 'h'.code.toByte(); bytes[9] = 'e'.code.toByte(); bytes[10] = 'i'.code.toByte(); bytes[11] = 'c'.code.toByte()
        return bytes
    }

    /** JPEG with an EXIF APP1 orientation tag injected (still decodable by ImageIO). */
    fun jpegWithOrientation(orientation: Int): ByteArray {
        val jpeg = syntheticJpeg()
        val tiff = buildTiff(orientation)
        val payload = "Exif\u0000\u0000".toByteArray(StandardCharsets.ISO_8859_1) + tiff
        val len = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len ushr 8).toByte(), (len and 0xFF).toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun buildTiff(orientation: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0, 8, 0, 0, 0))
        out.write(byteArrayOf(1, 0))
        out.write(byteArrayOf(0x12, 0x01, 3, 0, 1, 0, 0, 0, orientation.toByte(), 0, 0, 0))
        out.write(byteArrayOf(0, 0, 0, 0))
        return out.toByteArray()
    }

    fun trial(
        id: String,
        scene: String,
        condition: String,
        arm: String,
        repetition: Int,
        captureOrder: Int,
        outcome: TrialOutcome,
        original: String?,
        review: String? = null,
        hash: String? = null,
        size: Long? = null,
        width: Int? = 160,
        height: Int? = 120,
        format: String? = "png",
        failureReason: String? = null,
        exclusionReason: String? = null,
        latencyMs: Double? = 120.0,
        memoryKb: Double? = null,
        outputBytes: Double? = null,
        route: String? = null,
        thermalState: String? = null,
        device: String = "TEST-PHONE-ABC123",
        appVersion: String = "test-app",
        cameraId: String = "camera-0",
        consent: String = "CONSENTED",
        provenance: String = "OWNER_CAPTURED",
        publication: String = "PERMITTED",
    ): Trial = Trial(
        trialId = id,
        scene = scene,
        condition = condition,
        arm = arm,
        repetition = repetition,
        captureOrder = captureOrder,
        outcome = outcome,
        exclusionReason = if (outcome == TrialOutcome.EXCLUDED) exclusionReason else null,
        failureReason = if (outcome == TrialOutcome.FAILED) failureReason else null,
        originalPath = original,
        originalHashSha256 = if (outcome == TrialOutcome.SUCCESS) hash else null,
        originalByteSize = if (outcome == TrialOutcome.SUCCESS) size else null,
        originalWidth = if (outcome == TrialOutcome.SUCCESS) width else null,
        originalHeight = if (outcome == TrialOutcome.SUCCESS) height else null,
        originalFormat = if (outcome == TrialOutcome.SUCCESS) format else null,
        reviewSourcePath = if (outcome == TrialOutcome.SUCCESS) (review ?: original) else null,
        reviewSourceHashSha256 = if (outcome == TrialOutcome.SUCCESS) hash else null,
        reviewSourceByteSize = if (outcome == TrialOutcome.SUCCESS) size else null,
        device = device,
        appVersion = appVersion,
        cameraIdentifier = cameraId,
        outputFormat = if (outcome == TrialOutcome.SUCCESS) "jpeg" else "",
        outputResolution = if (outcome == TrialOutcome.SUCCESS) "1200x900" else "",
        exifMake = null,
        exifModel = null,
        exifOrientation = null,
        latencyMs = latencyMs,
        memoryKb = memoryKb,
        outputBytes = outputBytes,
        thermalState = thermalState,
        route = route,
        fixture = "tripod",
        focusState = "locked",
        lightLevel = "daylight",
        motionState = "static",
        provenance = provenance,
        consent = consent,
        publicationPermission = publication,
    )

    fun writeStandardDataset(
        root: Path,
        kind: DatasetKind,
        comparisons: List<ComparisonPlanRow>,
        arms: List<String>? = null,
        repetitions: Int = 2,
        withCrops: Boolean = false,
        imageExt: String = "png",
        includeFailed: Boolean = false,
        includeExcluded: Boolean = false,
        latencyBase: Double? = 120.0,
        contractVersion: String = "T18.0-v1",
        allowShared: Boolean = false,
    ): List<Trial> {
        Files.createDirectories(root)
        val armsFinal = arms ?: if (kind == DatasetKind.CANDIDATE) listOf("baseline", "stock", "candidate") else listOf("baseline_pass_a", "baseline_pass_b")
        val scenes = listOf("city")
        val conditions = listOf("sun")
        val trials = mutableListOf<Trial>()
        val images = mutableMapOf<String, ByteArray>()
        var order = 1
        for (arm in armsFinal) {
            for (scene in scenes) {
                for (condition in conditions) {
                    for (rep in 1..repetitions) {
                        val id = "TRIAL_${arm}_${scene}_${condition}_$rep"
                        val rel = "originals/$id.$imageExt"
                        trials += trial(
                            id = id, scene = scene, condition = condition, arm = arm, repetition = rep,
                            captureOrder = order++,
                            outcome = TrialOutcome.SUCCESS,
                            original = rel,
                            latencyMs = latencyBase?.let { it + Math.floorMod(arm.hashCode(), 20) + rep * 5 },
                        )
                        images[rel] = imageBytesFor(imageExt, id)
                    }
                }
            }
        }
        if (includeFailed) {
            trials += trial(
                id = "TRIAL_FAILED_1", scene = "city", condition = "sun", arm = armsFinal.first(),
                repetition = repetitions + 1, captureOrder = order++,
                outcome = TrialOutcome.FAILED, failureReason = "camera session error", original = null,
            )
        }
        if (includeExcluded) {
            trials += trial(
                id = "TRIAL_EXCLUDED_1", scene = "city", condition = "sun", arm = armsFinal.last(),
                repetition = repetitions + 1, captureOrder = order++,
                outcome = TrialOutcome.EXCLUDED, exclusionReason = "motion blur", original = null,
            )
        }
        for ((rel, bytes) in images) {
            val path = root.resolve(rel)
            Files.createDirectories(path.parent)
            Files.write(path, bytes)
        }
        val finalized = trials.map { t ->
            if (t.originalPath != null) {
                val path = root.resolve(t.originalPath)
                val size = Files.size(path)
                val hash = Hashes.sha256File(path)
                t.copy(originalHashSha256 = hash, originalByteSize = size, reviewSourceHashSha256 = hash, reviewSourceByteSize = size)
            } else {
                t
            }
        }
        val props = LinkedHashMap<String, String>()
        props["schema_version"] = TOOL_SCHEMA_VERSION
        props["dataset_version"] = "test-v1"
        props["contract_version"] = contractVersion
        props["dataset_kind"] = kind.value
        props["capture_protocol"] = "handheld"
        props["declared_arms"] = armsFinal.joinToString(",")
        props["required_repetitions"] = repetitions.toString()
        props["app_identifier"] = "proshot-test"
        props["baseline_identifier"] = "baseline"
        props["privacy_classification"] = "PRIVATE"
        props["predeclared_hypothesis"] = "test hypothesis"
        props["critical_scenes"] = "city"
        props["guardrails"] = "latency_median_ms<=2000"
        if (kind == DatasetKind.CANDIDATE) props["candidate_identifier"] = "candidate"
        if (allowShared) props["allow_shared_originals"] = "true"
        writeProperties(root, props)
        writeTrialsCsv(root, finalized)
        writeComparisonCsv(root, comparisons)
        if (withCrops) {
            val successes = finalized.filter { it.outcome == TrialOutcome.SUCCESS }
            val rows = successes.mapIndexed { i, t ->
                listOf(t.trialId, "C${i + 1}", "GENERAL", "0.2", "0.2", "0.6", "0.6")
            }
            Files.write(root.resolve("crops.csv"), Csv.serialize(CropsSchema.COLUMNS, rows).toByteArray(StandardCharsets.UTF_8))
        }
        return finalized
    }

    /** A/A compares two independently ordered, source-blinded passes of the identical locked baseline. */
    fun calibrationComparisons(): List<ComparisonPlanRow> =
        listOf(ComparisonPlanRow("CMP_AA", "baseline_pass_a", "baseline_pass_b", ComparisonPurpose.BLINDED_AA))

    fun candidateComparisons(): List<ComparisonPlanRow> =
        listOf(ComparisonPlanRow("CMP_CB", "candidate", "baseline", ComparisonPurpose.CANDIDATE_VS_BASELINE))

    fun imageBytesFor(ext: String, id: String): ByteArray = when (ext) {
        "png" -> syntheticPng(base = Math.floorMod(id.hashCode(), 200))
        "jpeg" -> syntheticJpeg()
        "heic" -> heicBytes()
        else -> syntheticPng()
    }

    fun writeProperties(root: Path, entries: Map<String, String>) {
        StrictProperties.write(root.resolve("dataset.properties"), entries)
    }

    fun writeTrialsCsv(root: Path, trials: List<Trial>) {
        val rows = trials.map { trialToRow(it) }
        Csv.write(root.resolve("trials.csv"), TrialsSchema.COLUMNS, rows)
    }

    fun writeComparisonCsv(root: Path, comparisons: List<ComparisonPlanRow>) {
        val rows = comparisons.map { listOf(it.comparisonId, it.armA, it.armB, it.purpose.value) }
        Csv.write(root.resolve("comparison-plan.csv"), ComparisonPlanSchema.COLUMNS, rows)
    }

    fun writeCropsCsv(root: Path, crops: List<Crop>) {
        val rows = crops.map {
            listOf(it.trialId, it.cropId, it.cropPurpose, fmt(it.x0), fmt(it.y0), fmt(it.x1), fmt(it.y1))
        }
        Csv.write(root.resolve("crops.csv"), CropsSchema.COLUMNS, rows)
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.3f", v)

    fun trialToRow(t: Trial): List<String> = listOf(
        t.trialId, t.scene, t.condition, t.arm, t.repetition.toString(), t.captureOrder.toString(),
        t.outcome.value, t.exclusionReason ?: "", t.failureReason ?: "",
        t.originalPath ?: "", t.originalHashSha256 ?: "", t.originalByteSize?.toString() ?: "",
        t.originalWidth?.toString() ?: "", t.originalHeight?.toString() ?: "", t.originalFormat ?: "",
        t.reviewSourcePath ?: "", t.reviewSourceHashSha256 ?: "", t.reviewSourceByteSize?.toString() ?: "",
        t.device, t.appVersion, t.cameraIdentifier, t.outputFormat, t.outputResolution,
        t.exifMake ?: "", t.exifModel ?: "", t.exifOrientation ?: "",
        t.latencyMs?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "",
        t.memoryKb?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "",
        t.outputBytes?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "",
        t.thermalState ?: "", t.route ?: "",
        t.fixture, t.focusState, t.lightLevel, t.motionState,
        t.provenance, t.consent, t.publicationPermission,
    )

    fun writeResponses(packageDir: Path, choices: Map<String, String>, out: Path) {
        val manifest = StrictProperties.read(packageDir.resolve("manifest.properties"))
        val packageId = manifest.require("package_id")
        val pairs = manifest.require("pair.order").split(',')
        val rows = pairs.map { p ->
            listOf(packageId, p, choices[p] ?: "TIE", "", "", "", "")
        }
        Csv.write(out, ResponseSchema.COLUMNS, rows)
    }

    fun allTieResponses(packageDir: Path, out: Path): Path {
        writeResponses(packageDir, emptyMap(), out)
        return out
    }

    fun candidatePreferredResponses(packageDir: Path, keyPath: Path, out: Path): Path {
        val key = StrictProperties.read(keyPath)
        val pairIds = key.entries.keys
            .filter { it.startsWith("pair.") && it.endsWith(".comparison_id") }
            .map { it.removePrefix("pair.").removeSuffix(".comparison_id") }
        val choices = pairIds.associate { pid ->
            val leftArm = key.require("pair.$pid.left.arm")
            val rightArm = key.require("pair.$pid.right.arm")
            if (leftArm == "candidate") pid to "LEFT" else pid to "RIGHT"
        }
        writeResponses(packageDir, choices, out)
        return out
    }

    fun fillThresholdTemplate(
        template: Path,
        out: Path,
        adequacy: String = "ADEQUATE",
        minSample: Int = 2,
        failureMargin: String = "0.20",
        reliabilityMargin: String = "0.10",
        usefulness: String = "decisive_preference_lower_bound>=0.05",
        guardrails: String = "failure_rate<=0.20,latency_median_ms<=5000,privacy_no_leak=yes",
        scenes: String = "city",
        unavailablePolicy: String = "unavailable metrics do not block; missing critical scene evidence blocks",
        approvalId: String = "owner-1",
        approvalCategory: String = "project-owner",
        approvalTimestamp: String = "2026-08-01T00:00:00Z",
        additional: Map<String, String> = emptyMap(),
    ): Path {
        val draft = StrictProperties.read(template)
        val entries = LinkedHashMap<String, String>()
        for ((k, v) in draft.entries) entries[k] = v
        entries["adequacy_decision"] = adequacy
        entries["adequacy_justification"] = "reviewed A/A interval and scene variance"
        entries["min_sample_per_grain"] = minSample.toString()
        entries["critical_failure_margin"] = failureMargin
        entries["reliability_margin_non_inferiority"] = reliabilityMargin
        entries["usefulness_rule"] = usefulness
        entries["guardrails"] = guardrails
        entries["critical_scene_families"] = scenes
        entries["unavailable_metric_policy"] = unavailablePolicy
        entries["approval_identity"] = approvalId
        entries["approval_category"] = approvalCategory
        entries["approval_timestamp_utc"] = approvalTimestamp
        for ((k, v) in additional) entries[k] = v
        StrictProperties.write(out, entries)
        return out
    }

    fun listAllFiles(dir: Path): List<Path> =
        Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.sorted().toList() }

    fun filesEqual(a: Path, b: Path): Boolean = Files.readAllBytes(a).contentEquals(Files.readAllBytes(b))
}

class CommandLineIntegrationTest {

    private val temp = Files.createTempDirectory("proshot-tool-it")

    @After
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun cli(vararg args: String): Int = runCli(arrayOf(*args))

    private data class CalibrationFixture(
        val root: Path,
        val pkg: Path,
        val key: Path,
        val seal: Path,
        val reportDir: Path,
        val template: Path,
        val draft: Path,
        val lock: Path,
    )

    private val SEED = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"

    private fun buildCalibrationLock(label: String, lockTimestamp: String = "2026-08-01T00:00:00Z"): CalibrationFixture {
        val calRoot = temp.resolve("cal-$label")
        TestData.writeStandardDataset(calRoot, DatasetKind.CALIBRATION, TestData.calibrationComparisons(), withCrops = true)
        val calPackage = temp.resolve("cal-package-$label")
        val calKey = temp.resolve("cal-key-$label")
        assertEquals(0, cli("blind", "--root", "$calRoot", "--out-dir", "$calPackage", "--key", "$calKey", "--seed", "$SEED"))
        assertTrue(Files.isRegularFile(calPackage.resolve("manifest.properties")))
        assertTrue(Files.isRegularFile(calKey))

        val responses1 = temp.resolve("cal-responses-$label.csv")
        TestData.allTieResponses(calPackage, responses1)
        val seal1 = temp.resolve("cal-seal-$label.properties")
        assertEquals(0, cli(
            "seal-review", "--package", "$calPackage", "--responses", "$responses1", "--out", "$seal1",
            "--reviewer", "rev-cal-$label", "--category", "internal", "--conflict", "NONE", "--utc-timestamp", "2026-08-01T00:00:00Z",
        ))

        val calReportDir = temp.resolve("cal-report-$label")
        assertEquals(0, cli(
            "analyze", "--package", "$calPackage", "--key", "$calKey", "--root", "$calRoot",
            "--out-dir", "$calReportDir", "--seal", "$seal1", "--utc-timestamp", "2026-08-01T00:00:00Z",
        ))
        val template = calReportDir.resolve("threshold-template.properties")
        assertTrue(Files.isRegularFile(template))

        val draft = temp.resolve("cal-threshold-draft-$label.properties")
        TestData.fillThresholdTemplate(template, draft)
        val lock = temp.resolve("cal-threshold-$label.lock")
        assertEquals(0, cli("lock-thresholds", "--template", "$draft", "--out", "$lock", "--utc-timestamp", lockTimestamp))
        val lockProps = StrictProperties.read(lock)
        assertTrue(lockProps.get("lock_self_hash_sha256").isNullOrBlank().not())
        return CalibrationFixture(calRoot, calPackage, calKey, seal1, calReportDir, template, draft, lock)
    }

    private fun customLock(template: Path, label: String, vararg overrides: Pair<String, String>): Path {
        val draft = temp.resolve("draft-$label.properties")
        TestData.fillThresholdTemplate(template, draft, additional = mapOf(*overrides))
        val lock = temp.resolve("lock-$label.lock")
        assertEquals(0, cli("lock-thresholds", "--template", "$draft", "--out", "$lock", "--utc-timestamp", "2026-08-01T00:00:00Z"))
        return lock
    }

    private fun candidateDataset(label: String): Path {
        val root = temp.resolve("cand-$label")
        TestData.writeStandardDataset(root, DatasetKind.CANDIDATE, TestData.candidateComparisons())
        return root
    }

    private fun addFailedCandidateTrials(root: Path, count: Int) {
        val trials = DatasetModel.load(root).trials.toMutableList()
        var order = trials.maxOf { it.captureOrder } + 1
        for (i in 1..count) {
            trials += TestData.trial(
                id = "TFAIL_CAND_$i", scene = "city", condition = "sun", arm = "candidate",
                repetition = 2 + i, captureOrder = order++,
                outcome = TrialOutcome.FAILED, failureReason = "camera session error", original = null,
            )
        }
        TestData.writeTrialsCsv(root, trials)
    }

    private fun blindAndSeal(
        root: Path,
        label: String,
        timestamp: String,
        responsesWriter: (Path, Path, Path) -> Unit,
    ): Triple<Path, Path, Path> {
        val pkg = temp.resolve("cpkg-$label")
        val key = temp.resolve("ckey-$label")
        assertEquals(0, cli("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "$SEED"))
        val responses = temp.resolve("cres-$label.csv")
        responsesWriter(pkg, key, responses)
        val seal = temp.resolve("cseal-$label.properties")
        assertEquals(0, cli(
            "seal-review", "--package", "$pkg", "--responses", "$responses", "--out", "$seal",
            "--reviewer", "rev-$label", "--category", "internal", "--conflict", "NONE", "--utc-timestamp", timestamp,
        ))
        return Triple(pkg, key, seal)
    }

    private fun analyzeWithThreshold(pkg: Path, key: Path, root: Path, out: Path, seal: Path, lock: Path): Int = cli(
        "analyze", "--package", "$pkg", "--key", "$key", "--root", "$root",
        "--out-dir", "$out", "--seal", "$seal", "--threshold", "$lock",
        "--utc-timestamp", "2026-08-02T00:00:00Z",
    )

    private fun stockContextResponses(pkg: Path, key: Path, out: Path) {
        val keyProps = StrictProperties.read(key)
        val manifest = StrictProperties.read(pkg.resolve("manifest.properties"))
        val packageId = manifest.require("package_id")
        val pairs = manifest.require("pair.order").split(',')
        val rows = pairs.map { p ->
            val purpose = keyProps.require("pair.$p.purpose")
            val leftArm = keyProps.require("pair.$p.left.arm")
            val rightArm = keyProps.require("pair.$p.right.arm")
            val choice = if (purpose == "CANDIDATE_VS_BASELINE") {
                if (rightArm == "baseline") "RIGHT" else "LEFT"
            } else {
                if (leftArm == "candidate") "LEFT" else "RIGHT"
            }
            listOf(packageId, p, choice, "", "", "", "")
        }
        Csv.write(out, ResponseSchema.COLUMNS, rows)
    }

    private fun candidateWithDefectResponses(pkg: Path, key: Path, out: Path, markCritical: Boolean) {
        val keyProps = StrictProperties.read(key)
        val manifest = StrictProperties.read(pkg.resolve("manifest.properties"))
        val packageId = manifest.require("package_id")
        val pairs = manifest.require("pair.order").split(',')
        val rows = pairs.map { p ->
            val leftArm = keyProps.require("pair.$p.left.arm")
            val candidateSide = if (leftArm == "candidate") "LEFT" else "RIGHT"
            val defect = if (markCritical) "SEVERE_SUBJECT_CLIPPING" else ""
            val side = if (markCritical) candidateSide else ""
            listOf(packageId, p, candidateSide, "", defect, side, "")
        }
        Csv.write(out, ResponseSchema.COLUMNS, rows)
    }

    @Test
    fun calibrationPlanWithoutTerminalNewlineLoadsResolvesAndBlinds() {
        val root = temp.resolve("no-terminal-newline")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val planPath = root.resolve("comparison-plan.csv")
        val withoutTerminalNewline = Files.readString(planPath).trimEnd('\r', '\n')
        Files.writeString(planPath, withoutTerminalNewline, StandardCharsets.UTF_8)

        val dataset = DatasetModel.load(root)
        assertEquals(1, dataset.comparisons.size)
        assertEquals("CMP_AA", dataset.comparisons.single().comparisonId)
        val pairs = PairResolver.resolve(dataset)
        assertEquals(2, pairs.size)
        assertTrue(pairs.all { it.comparisonId == "CMP_AA" })

        val validation = DatasetValidator.validateDataset(root)
        assertTrue(validation.issues.toString(), validation.ok)
        val pkg = temp.resolve("no-terminal-newline-pkg")
        val key = temp.resolve("no-terminal-newline-key")
        assertEquals(0, cli("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "$SEED"))
        assertTrue(Files.isRegularFile(pkg.resolve("manifest.properties")))
        assertTrue(Files.isRegularFile(key))
    }

    @Test
    fun candidateGenuinelyPassesAllLockedGates() {
        val fixture = buildCalibrationLock("pass")
        val root = candidateDataset("pass")
        val (pkg, key, seal) = blindAndSeal(root, "pass", "2026-08-02T00:00:00Z") { p, k, out ->
            TestData.candidatePreferredResponses(p, k, out)
        }
        val reportDir = temp.resolve("cand-pass-report")
        assertEquals(0, analyzeWithThreshold(pkg, key, root, reportDir, seal, fixture.lock))
        val csv = Files.readString(reportDir.resolve("analysis-report.csv"))
        assertTrue(csv, csv.contains("status,status,PASS"))
        assertTrue(csv, csv.contains("roles,candidate_arm,candidate"))
        assertTrue(csv, csv.contains("roles,locked_baseline_arm,baseline"))
    }

    @Test
    fun zeroDecisiveBindingEvidenceIsInconclusive() {
        val fixture = buildCalibrationLock("zero")
        val root = candidateDataset("zero")
        val (pkg, key, seal) = blindAndSeal(root, "zero", "2026-08-02T00:00:00Z") { p, _, out ->
            TestData.allTieResponses(p, out)
        }
        val reportDir = temp.resolve("cand-zero-report")
        assertEquals(0, analyzeWithThreshold(pkg, key, root, reportDir, seal, fixture.lock))
        val csv = Files.readString(reportDir.resolve("analysis-report.csv"))
        assertTrue(csv, csv.contains("status,status,INCONCLUSIVE_ZERO_DECISIVE"))
    }

    @Test
    fun stockContextWinsCannotRescueCandidateBaselineLoss() {
        val fixture = buildCalibrationLock("stock")
        val root = temp.resolve("cand-stock")
        TestData.writeStandardDataset(
            root,
            DatasetKind.CANDIDATE,
            TestData.candidateComparisons() +
                ComparisonPlanRow("CMP_CS", "candidate", "stock", ComparisonPurpose.CANDIDATE_VS_STOCK),
        )
        val (pkg, key, seal) = blindAndSeal(root, "stock", "2026-08-02T00:00:00Z") { p, k, out ->
            stockContextResponses(p, k, out)
        }
        val reportDir = temp.resolve("cand-stock-report")
        assertEquals(0, analyzeWithThreshold(pkg, key, root, reportDir, seal, fixture.lock))
        val csv = Files.readString(reportDir.resolve("analysis-report.csv"))
        // The candidate loses every candidate-vs-baseline pair and wins every stock pair;
        // the stock wins must not rescue the binding gate.
        assertTrue(csv, csv.contains("status,status,FAIL"))
        assertTrue(csv, csv.contains("usefulness rule violated"))
        assertTrue(csv, csv.contains("contextual_candidate_vs_stock,cell.wins,2"))
        assertTrue(csv, csv.contains("preference_overall,decisive_pairs,2"))
        assertTrue(csv, csv.contains("preference_overall,decisive_preference_rate,0.0000"))
    }

    @Test
    fun candidateCriticalFailureRegressionFails() {
        val fixture = buildCalibrationLock("cf")
        // Isolate the critical-failure gate: completion margin kept wide.
        val lock = customLock(fixture.template, "cf", "reliability_margin_non_inferiority" to "0.90")
        val root = candidateDataset("cf")
        addFailedCandidateTrials(root, 1)
        val (pkg, key, seal) = blindAndSeal(root, "cf", "2026-08-02T00:00:00Z") { p, k, out ->
            TestData.candidatePreferredResponses(p, k, out)
        }
        val reportDir = temp.resolve("cand-cf-report")
        assertEquals(0, analyzeWithThreshold(pkg, key, root, reportDir, seal, lock))
        val csv = Files.readString(reportDir.resolve("analysis-report.csv"))
        assertTrue(csv, csv.contains("status,status,FAIL"))
        assertTrue(csv, csv.contains("critical-failure regression"))
    }

    @Test
    fun candidateCompletionRegressionFails() {
        val fixture = buildCalibrationLock("comp")
        // Isolate the completion gate: failure margin kept wide.
        val lock = customLock(fixture.template, "comp", "critical_failure_margin" to "0.90")
        val root = candidateDataset("comp")
        addFailedCandidateTrials(root, 1)
        val (pkg, key, seal) = blindAndSeal(root, "comp", "2026-08-02T00:00:00Z") { p, k, out ->
            TestData.candidatePreferredResponses(p, k, out)
        }
        val reportDir = temp.resolve("cand-comp-report")
        assertEquals(0, analyzeWithThreshold(pkg, key, root, reportDir, seal, lock))
        val csv = Files.readString(reportDir.resolve("analysis-report.csv"))
        assertTrue(csv, csv.contains("status,status,FAIL"))
        assertTrue(csv, csv.contains("completion regression"))
    }

    @Test
    fun disputedCriticalDefectEvidenceIsInconclusive() {
        val fixture = buildCalibrationLock("disputed")
        val root = candidateDataset("disputed")
        val (pkg, key, sealA) = blindAndSeal(root, "da", "2026-08-02T00:00:00Z") { p, k, out ->
            candidateWithDefectResponses(p, k, out, markCritical = true)
        }
        val (_, _, sealB) = blindAndSeal(root, "db", "2026-08-02T00:00:00Z") { p, k, out ->
            candidateWithDefectResponses(p, k, out, markCritical = false)
        }
        val reportDir = temp.resolve("cand-disputed-report")
        // Both reviewers prefer the candidate (decisive) but disagree on the critical defect.
        assertEquals(0, cli(
            "analyze", "--package", "$pkg", "--key", "$key", "--root", "$root",
            "--out-dir", "$reportDir", "--seal", "$sealA", "--seal", "$sealB", "--threshold", "${fixture.lock}",
            "--utc-timestamp", "2026-08-02T00:00:00Z",
        ))
        val csv = Files.readString(reportDir.resolve("analysis-report.csv"))
        assertTrue(csv, csv.contains("status,status,INCONCLUSIVE"))
        assertTrue(csv, csv.contains("disputed critical-defect evidence"))
        assertTrue(csv, csv.contains("capture_by_arm,arm_candidate.disputed,2"))
    }

    @Test
    fun unsupportedUsefulnessRulesFailThresholdLocking() {
        val fixture = buildCalibrationLock("rules")
        var i = 0
        for (rule in listOf("decisive_preference_rate>=1.5", "preference_rate>=0.5", "decisive_preference_lower_bound>=NaN")) {
            val draft = temp.resolve("rules-draft-$i.properties")
            TestData.fillThresholdTemplate(fixture.template, draft, usefulness = rule)
            assertNotEquals(0, cli("lock-thresholds", "--template", "$draft", "--out", "${temp.resolve("rules-$i.lock")}", "--utc-timestamp", "2026-08-01T00:00:00Z"))
            i++
        }
    }

    @Test
    fun calibrationStaysInconclusiveWithValidLockAndLockTimingDiffers() {
        // A calibration lock created well after the calibration seal is accepted;
        // the same lock is rejected for a candidate sealed before it.
        val fixture = buildCalibrationLock("timing", lockTimestamp = "2026-08-05T00:00:00Z")
        val calReport2 = temp.resolve("cal-timing-report2")
        assertEquals(0, cli(
            "analyze", "--package", "${fixture.pkg}", "--key", "${fixture.key}", "--root", "${fixture.root}",
            "--out-dir", "$calReport2", "--seal", "${fixture.seal}", "--threshold", "${fixture.lock}",
            "--utc-timestamp", "2026-08-06T00:00:00Z",
        ))
        val calCsv = Files.readString(calReport2.resolve("analysis-report.csv"))
        assertTrue(calCsv, calCsv.contains("status,status,INCONCLUSIVE / CALIBRATION"))

        val root = candidateDataset("timing")
        val (pkg, key, seal) = blindAndSeal(root, "timing", "2026-08-02T00:00:00Z") { p, k, out ->
            TestData.candidatePreferredResponses(p, k, out)
        }
        assertNotEquals(0, analyzeWithThreshold(pkg, key, root, temp.resolve("cand-timing-out"), seal, fixture.lock))
    }

    @Test
    fun malformedPathsTimestampsAndIntegersFailStably() {
        val errBuf = ByteArrayOutputStream()
        val oldErr = System.err
        try {
            System.setErr(PrintStream(errBuf))
            // Malformed command path (embedded NUL is not a legal path character).
            assertNotEquals(0, cli("validate", "--root", "bad\u0000root", "--out-dir", "${temp.resolve("x")}"))
            // display-max-dimension must be a positive integer.
            assertNotEquals(0, cli("blind", "--root", "r", "--out-dir", "o", "--key", "k", "--display-max-dimension", "0"))
            assertNotEquals(0, cli("blind", "--root", "r", "--out-dir", "o", "--key", "k", "--display-max-dimension", "-5"))
        } finally {
            System.setErr(oldErr)
        }
        val err = errBuf.toString(StandardCharsets.UTF_8)
        assertFalse(err, err.contains("Exception"))
        assertFalse(err, err.contains(" at com.proshot"))
    }

    @Test
    fun malformedSealTimestampFailsStably() {
        val root = temp.resolve("ts-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val pkg = temp.resolve("ts-pkg")
        val key = temp.resolve("ts-key")
        assertEquals(0, cli("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "$SEED"))
        val res = temp.resolve("ts-res.csv")
        TestData.allTieResponses(pkg, res)
        val errBuf = ByteArrayOutputStream()
        val oldErr = System.err
        try {
            System.setErr(PrintStream(errBuf))
            assertNotEquals(0, cli(
                "seal-review", "--package", "$pkg", "--responses", "$res", "--out", "${temp.resolve("ts-seal")}",
                "--reviewer", "r1", "--category", "c", "--conflict", "NONE", "--utc-timestamp", "garbage",
            ))
        } finally {
            System.setErr(oldErr)
        }
        assertFalse(errBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8).contains("Exception"))
    }

    @Test
    fun deterministicOutputsForFixedSeed() {
        val root = temp.resolve("det-root")
        TestData.writeStandardDataset(root, DatasetKind.CANDIDATE, TestData.candidateComparisons(), withCrops = true)
        val seed = "0000111122223333444455556666777788889999aaaabbbbccccddddeeeeffff"

        val pkgA = temp.resolve("det-pkg-a")
        val keyA = temp.resolve("det-key-a")
        val pkgB = temp.resolve("det-pkg-b")
        val keyB = temp.resolve("det-key-b")
        assertEquals(0, cli("blind", "--root", "$root", "--out-dir", "$pkgA", "--key", "$keyA", "--seed", "$seed"))
        assertEquals(0, cli("blind", "--root", "$root", "--out-dir", "$pkgB", "--key", "$keyB", "--seed", "$seed"))

        val filesA = TestData.listAllFiles(pkgA)
        val filesB = TestData.listAllFiles(pkgB)
        assertEquals(filesA.map { pkgA.relativize(it).toString() }, filesB.map { pkgB.relativize(it).toString() })
        for (f in filesA) {
            val rel = pkgA.relativize(f)
            assertTrue("byte mismatch for $rel", TestData.filesEqual(f, pkgB.resolve(rel)))
        }
        assertTrue(TestData.filesEqual(keyA, keyB))
        val manifestA = StrictProperties.read(pkgA.resolve("manifest.properties"))
        val manifestB = StrictProperties.read(pkgB.resolve("manifest.properties"))
        assertEquals(manifestA.require("package_id"), manifestB.require("package_id"))

        // validate determinism
        val valA = temp.resolve("det-val-a")
        val valB = temp.resolve("det-val-b")
        assertEquals(0, cli("validate", "--root", "$root", "--out-dir", "$valA"))
        assertEquals(0, cli("validate", "--root", "$root", "--out-dir", "$valB"))
        assertTrue(TestData.filesEqual(valA.resolve("validation-summary.csv"), valB.resolve("validation-summary.csv")))
        assertTrue(TestData.filesEqual(valA.resolve("validation-report.txt"), valB.resolve("validation-report.txt")))

        // analyze determinism with fixed timestamp (calibration dataset)
        val calRoot = temp.resolve("det-cal")
        TestData.writeStandardDataset(calRoot, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val calPkg = temp.resolve("det-cal-pkg")
        val calKey = temp.resolve("det-cal-key")
        assertEquals(0, cli("blind", "--root", "$calRoot", "--out-dir", "$calPkg", "--key", "$calKey", "--seed", "$seed"))
        val res = temp.resolve("det-cal-res.csv")
        TestData.allTieResponses(calPkg, res)
        val seal = temp.resolve("det-cal-seal.properties")
        assertEquals(0, cli("seal-review", "--package", "$calPkg", "--responses", "$res", "--out", "$seal", "--reviewer", "r1", "--category", "x", "--conflict", "NONE", "--utc-timestamp", "2026-08-01T00:00:00Z"))
        val repA = temp.resolve("det-rep-a")
        val repB = temp.resolve("det-rep-b")
        assertEquals(0, cli("analyze", "--package", "$calPkg", "--key", "$calKey", "--root", "$calRoot", "--out-dir", "$repA", "--seal", "$seal", "--utc-timestamp", "2026-08-01T00:00:00Z"))
        assertEquals(0, cli("analyze", "--package", "$calPkg", "--key", "$calKey", "--root", "$calRoot", "--out-dir", "$repB", "--seal", "$seal", "--utc-timestamp", "2026-08-01T00:00:00Z"))
        assertTrue(TestData.filesEqual(repA.resolve("analysis-report.csv"), repB.resolve("analysis-report.csv")))
        assertTrue(TestData.filesEqual(repA.resolve("analysis-report.html"), repB.resolve("analysis-report.html")))
    }

    @Test
    fun noOverwriteAndNoOriginalMutation() {
        val root = temp.resolve("no-mut")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val before = TestData.listAllFiles(root).map { it to Hashes.sha256File(it) }.toMap()

        val pkg = temp.resolve("no-mut-pkg")
        val key = temp.resolve("no-mut-key")
        val seed = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        assertEquals(0, cli("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "$seed"))

        // Re-running into the same package dir must fail closed.
        assertNotEquals(0, cli("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "$seed"))
        // Reusing an existing key path must fail closed.
        val pkg2 = temp.resolve("no-mut-pkg2")
        assertNotEquals(0, cli("blind", "--root", "$root", "--out-dir", "$pkg2", "--key", "$key", "--seed", "$seed"))
        // Key inside package must fail closed.
        val pkg3 = temp.resolve("no-mut-pkg3")
        assertNotEquals(0, cli("blind", "--root", "$root", "--out-dir", "$pkg3", "--key", "$pkg3/inside.key", "--seed", "$seed"))

        val after = TestData.listAllFiles(root).map { it to Hashes.sha256File(it) }.toMap()
        assertEquals(before, after)
    }

    @Test
    fun errorsAreConciseWithoutStackTrace() {
        val errBuf = ByteArrayOutputStream()
        val oldErr = System.err
        try {
            System.setErr(PrintStream(errBuf))
            val code = cli("validate", "--root", "D:/does/not/exist/xyz", "--out-dir", temp.resolve("err-out").toString())
            assertNotEquals(0, code)
        } finally {
            System.setErr(oldErr)
        }
        val err = errBuf.toString(StandardCharsets.UTF_8)
        assertTrue(err, err.startsWith("ERROR "))
        assertTrue(err, !err.contains("Exception"))
        assertTrue(!err.contains("at com.proshot"))
    }

    @Test
    fun unknownCommandAndUsage() {
        assertEquals(1, cli("nonsense"))
        assertEquals(1, cli("validate"))
        assertEquals(1, cli("validate", "--bogus", "x", "--root", "r", "--out-dir", "o"))
    }
}
