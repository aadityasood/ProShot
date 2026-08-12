package com.proshot.tools.imagequality

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ReviewAnalysisTest {

    private val temp = Files.createTempDirectory("proshot-analysis")

    @After
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private data class SealedBundle(val pkg: Path, val key: Path, val seal: Path)

    private fun blindAndSeal(root: Path, reviewer: String, timestamp: String, responsesWriter: (Path, Path, Path) -> Path): SealedBundle {
        val pkg = temp.resolve("pkg-$reviewer")
        val key = temp.resolve("key-$reviewer")
        val seed = "8888888888888888888888888888888888888888888888888888888888888888"
        assertEquals(0, runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "$seed")))
        val responses = temp.resolve("res-$reviewer.csv")
        responsesWriter(pkg, key, responses)
        val seal = temp.resolve("seal-$reviewer.properties")
        assertEquals(0, runCli(
            arrayOf(
                "seal-review", "--package", "$pkg", "--responses", "$responses", "--out", "$seal",
                "--reviewer", reviewer, "--category", "internal", "--conflict", "NONE", "--utc-timestamp", timestamp,
            ),
        ))
        return SealedBundle(pkg, key, seal)
    }

    private fun sealedCalibration(): SealedBundle {
        val root = temp.resolve("cal")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons(), withCrops = true)
        return blindAndSeal(root, "r1", "2026-08-01T00:00:00Z") { p, _, out ->
            TestData.allTieResponses(p, out)
        }
    }

    private fun analyze(pkg: Path, key: Path, root: Path, outDir: Path, seals: List<Path>, threshold: Path? = null): Int {
        val args = mutableListOf(
            "analyze", "--package", "$pkg", "--key", "$key", "--root", "$root", "--out-dir", "$outDir",
        )
        for (s in seals) args += listOf("--seal", "$s")
        if (threshold != null) args += listOf("--threshold", "$threshold")
        args += listOf("--utc-timestamp", "2026-08-02T00:00:00Z")
        return runCli(arrayOf(*args.toTypedArray()))
    }

    private fun assertSealFails(pkg: Path, responses: Path, out: Path) {
        val code = runCli(
            arrayOf(
                "seal-review", "--package", "$pkg", "--responses", "$responses", "--out", "$out",
                "--reviewer", "r9", "--category", "internal", "--conflict", "NONE",
            ),
        )
        assertNotEquals(0, code)
    }

    private fun rawResponses(pkg: Path, header: List<String>, rows: List<List<String>>, out: Path): Path {
        Csv.write(out, header, rows)
        return out
    }

    @Test
    fun sealRejectsBadResponses() {
        val fix = sealedCalibration()
        val manifest = StrictProperties.read(fix.pkg.resolve("manifest.properties"))
        val packageId = manifest.require("package_id")
        val pairs = manifest.require("pair.order").split(',')

        // Missing pair row.
        val missingPair = pairs.dropLast(1)
        val r1 = temp.resolve("r1.csv")
        rawResponses(fix.pkg, ResponseSchema.COLUMNS, missingPair.map { listOf(packageId, it, "TIE", "", "", "", "") }, r1)
        assertSealFails(fix.pkg, r1, temp.resolve("s1"))

        // Unknown pair id.
        val r2 = temp.resolve("r2.csv")
        rawResponses(fix.pkg, ResponseSchema.COLUMNS, pairs.mapIndexed { i, p -> listOf(packageId, if (i == 0) "p99" else p, "TIE", "", "", "", "") }, r2)
        assertSealFails(fix.pkg, r2, temp.resolve("s2"))

        // Duplicate pair row.
        val r3 = temp.resolve("r3.csv")
        val dup = pairs.flatMapIndexed { i, p -> if (i == 0) listOf(p, p) else listOf(p) }
        rawResponses(fix.pkg, ResponseSchema.COLUMNS, dup.map { listOf(packageId, it, "TIE", "", "", "", "") }, r3)
        assertSealFails(fix.pkg, r3, temp.resolve("s3"))

        // Invalid choice.
        val r4 = temp.resolve("r4.csv")
        rawResponses(fix.pkg, ResponseSchema.COLUMNS, pairs.map { listOf(packageId, it, "MAYBE", "", "", "", "") }, r4)
        assertSealFails(fix.pkg, r4, temp.resolve("s4"))

        // Missing column.
        val r5 = temp.resolve("r5.csv")
        rawResponses(fix.pkg, ResponseSchema.COLUMNS.dropLast(1), pairs.map { listOf(packageId, it, "TIE", "", "", "") }, r5)
        assertSealFails(fix.pkg, r5, temp.resolve("s5"))

        // Reordered columns.
        val r6 = temp.resolve("r6.csv")
        val reordered = listOf("pair_id") + ResponseSchema.COLUMNS.filter { it != "pair_id" }
        rawResponses(fix.pkg, reordered, pairs.map { listOf(it, packageId, "TIE", "", "", "", "") }, r6)
        assertSealFails(fix.pkg, r6, temp.resolve("s6"))

        // Schema drift in the manifest must be rejected.
        val manifestPath = fix.pkg.resolve("manifest.properties")
        val tampered = StrictProperties.serialize(StrictProperties.read(manifestPath).entries + mapOf("response_schema_sha256" to "0".repeat(64)))
        Files.write(manifestPath, tampered.toByteArray(StandardCharsets.UTF_8))
        val r7 = temp.resolve("r7.csv")
        TestData.allTieResponses(fix.pkg, r7)
        val code = runCli(
            arrayOf(
                "seal-review", "--package", "${fix.pkg}", "--responses", "$r7", "--out", "${temp.resolve("s7")}",
                "--reviewer", "r9", "--category", "internal", "--conflict", "NONE",
            ),
        )
        assertNotEquals(0, code)
    }

    @Test
    fun postSealTamperingPreventsAnalysis() {
        val root = temp.resolve("tamper-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val pkg = temp.resolve("tamper-pkg")
        val keyPath = temp.resolve("tamper-key")
        val seed = "9999999999999999999999999999999999999999999999999999999999999999"
        assertEquals(0, runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$keyPath", "--seed", "$seed")))
        val responses = temp.resolve("tamper-res.csv")
        TestData.allTieResponses(pkg, responses)
        val seal = temp.resolve("tamper-seal.properties")
        assertEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "$responses", "--out", "$seal", "--reviewer", "r1", "--category", "c", "--conflict", "NONE", "--utc-timestamp", "2026-08-01T00:00:00Z")))

        fun baselineOutDir() = temp.resolve("analyze-${System.nanoTime()}")
        assertEquals(0, analyze(pkg, keyPath, root, baselineOutDir(), listOf(seal)))

        // Response tamper: rewrite the response file after sealing.
        val resPath = temp.resolve("tamper-res2.csv")
        TestData.writeResponses(pkg, emptyMap(), resPath)
        val seal2 = temp.resolve("tamper-seal2.properties")
        assertEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "$resPath", "--out", "$seal2", "--reviewer", "r1", "--category", "c", "--conflict", "NONE", "--utc-timestamp", "2026-08-01T00:00:00Z")))
        Files.writeString(resPath, Files.readString(resPath).replace("TIE", "LEFT"))
        assertNotEquals(0, analyze(pkg, keyPath, root, baselineOutDir(), listOf(seal2)))

        // Manifest tamper.
        val manifestPath = pkg.resolve("manifest.properties")
        val originalManifest = StrictProperties.read(manifestPath)
        Files.write(manifestPath, (StrictProperties.serialize(originalManifest.entries) + "tampered=yes\n").toByteArray(StandardCharsets.UTF_8))
        assertNotEquals(0, analyze(pkg, keyPath, root, baselineOutDir(), listOf(seal)))

        // Asset tamper.
        Files.write(manifestPath, StrictProperties.serialize(originalManifest.entries).toByteArray(StandardCharsets.UTF_8))
        val asset = TestData.listAllFiles(pkg.resolve("assets")).first()
        Files.write(asset, Files.readAllBytes(asset) + byteArrayOf(1))
        assertNotEquals(0, analyze(pkg, keyPath, root, baselineOutDir(), listOf(seal)))

        // Key tamper (restore the asset first).
        Files.write(asset, Files.readAllBytes(asset).copyOfRange(0, Files.readAllBytes(asset).size - 1))
        val keyEntries = StrictProperties.read(keyPath).entries.toMutableMap()
        val pairKey = keyEntries.keys.first { it.startsWith("pair.") && it.endsWith(".left.arm") }
        keyEntries[pairKey] = "tampered-arm"
        Files.write(keyPath, StrictProperties.serialize(keyEntries).toByteArray(StandardCharsets.UTF_8))
        assertNotEquals(0, analyze(pkg, keyPath, root, baselineOutDir(), listOf(seal)))
    }

    @Test
    fun wilsonIntervalAndPairOutcomeFixtures() {
        val (lo, hi) = Wilson.interval(5, 10)
        assertEquals(0.2366, lo, 0.001)
        assertEquals(0.7634, hi, 0.001)

        assertEquals("LEFT", ReviewAnalysis.pairOutcome(listOf("LEFT", "LEFT", "RIGHT")))
        assertEquals("TIE", ReviewAnalysis.pairOutcome(listOf("LEFT", "RIGHT")))
        assertEquals("TIE", ReviewAnalysis.pairOutcome(listOf("TIE")))
        assertEquals("TIE", ReviewAnalysis.pairOutcome(emptyList()))
        assertEquals("LEFT", ReviewAnalysis.pairOutcome(listOf("LEFT")))
    }

    @Test
    fun pairLevelAggregationAvoidsPseudoreplication() {
        val v1 = ReviewAnalysis.PairVerdict(
            pairId = "p01",
            purpose = ComparisonPurpose.CANDIDATE_VS_BASELINE,
            scene = "city",
            referenceArm = "candidate",
            reviewerChoices = listOf("LEFT", "LEFT"),
            outcome = "LEFT",
            preferredArm = "candidate",
            win = true,
        )
        val v2 = ReviewAnalysis.PairVerdict(
            pairId = "p02",
            purpose = ComparisonPurpose.CANDIDATE_VS_BASELINE,
            scene = "city",
            referenceArm = "candidate",
            reviewerChoices = listOf("RIGHT", "RIGHT"),
            outcome = "RIGHT",
            preferredArm = "candidate",
            win = true,
        )
        val cell = ReviewAnalysis.cellOf(listOf(v1, v2))
        assertEquals(2, cell.wins)
        assertEquals(0, cell.losses)
        assertEquals(0, cell.ties)
        assertEquals(2, cell.decisive)
        assertEquals(1.0, cell.rate!!, 0.0)
        assertFalse(cell.zeroDecisive)
    }

    @Test
    fun missingnessFailureAndExclusionCountsReported() {
        val root = temp.resolve("counts-root")
        TestData.writeStandardDataset(
            root, DatasetKind.CALIBRATION, TestData.calibrationComparisons(),
            includeFailed = true, includeExcluded = true,
        )
        val bundle = blindAndSeal(root, "r1", "2026-08-01T00:00:00Z") { p, _, out ->
            TestData.allTieResponses(p, out)
        }
        val outDir = temp.resolve("counts-report")
        assertEquals(0, analyze(bundle.pkg, bundle.key, root, outDir, listOf(bundle.seal)))
        val csv = Files.readString(outDir.resolve("analysis-report.csv"))
        assertTrue(csv, csv.contains("capture,failed,1"))
        assertTrue(csv, csv.contains("capture,excluded,1"))
        assertTrue(csv, csv.contains("repeated_numeric,latency_ms.city.baseline_pass_a,"))
    }

    @Test
    fun unavailableDiagnosticsAndMetricsStayUnavailable() {
        val root = temp.resolve("unavail-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons(), latencyBase = null)
        val bundle = blindAndSeal(root, "r1", "2026-08-01T00:00:00Z") { p, k, out ->
            TestData.allTieResponses(p, out)
        }
        val outDir = temp.resolve("unavail-report")
        assertEquals(0, analyze(bundle.pkg, bundle.key, root, outDir, listOf(bundle.seal)))
        val csv = Files.readString(outDir.resolve("analysis-report.csv"))
        assertTrue(csv, csv.contains("repeated_numeric,latency_ms.city.baseline_pass_a,UNAVAILABLE"))
        assertTrue(csv, csv.contains("image_metrics,sharpness,UNAVAILABLE_NOT_IMPLEMENTED"))
        assertTrue(csv, csv.contains("image_metrics,ssim,UNAVAILABLE_NOT_IMPLEMENTED"))
        assertFalse(csv.contains("sharpness,0"))
    }

    @Test
    fun calibrationNeverReportsQualityPass() {
        val root = temp.resolve("cal-pass-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val bundle = blindAndSeal(root, "r1", "2026-08-01T00:00:00Z") { p, k, out ->
            TestData.allTieResponses(p, out)
        }
        val outDir = temp.resolve("cal-pass-report")
        assertEquals(0, analyze(bundle.pkg, bundle.key, root, outDir, listOf(bundle.seal)))
        val csv = Files.readString(outDir.resolve("analysis-report.csv"))
        assertTrue(csv, csv.contains("status,status,INCONCLUSIVE / CALIBRATION"))
        assertFalse(csv.contains("status,status,PASS"))

        // A candidate analysis without a valid lock must fail closed.
        val candRoot = temp.resolve("cand-no-lock")
        TestData.writeStandardDataset(candRoot, DatasetKind.CANDIDATE, TestData.candidateComparisons())
        val candBundle = blindAndSeal(candRoot, "r2", "2026-08-03T00:00:00Z") { p, k, out ->
            TestData.candidatePreferredResponses(p, k, out)
        }
        val candOut = temp.resolve("cand-no-lock-report")
        assertNotEquals(0, analyze(candBundle.pkg, candBundle.key, candRoot, candOut, listOf(candBundle.seal)))
    }

    @Test
    fun trialCriticalDefectAggregationMapsExactTrialsWithoutPseudoreplication() {
        val mapping = mapOf(
            "p01" to ReviewAnalysis.PairInfo("CMP_CB", ComparisonPurpose.CANDIDATE_VS_BASELINE, "city", "sun", 1, "T_CAND", "candidate", "T_BASE", "baseline"),
            "p02" to ReviewAnalysis.PairInfo("CMP_CS", ComparisonPurpose.CANDIDATE_VS_STOCK, "city", "sun", 1, "T_CAND", "candidate", "T_STOCK", "stock"),
        )
        val r1Flag = mapOf(
            "p01" to ReviewAnalysis.ResponseRow("pkg", "p01", "LEFT", emptyList(), "SEVERE_SUBJECT_CLIPPING", "LEFT", ""),
            "p02" to ReviewAnalysis.ResponseRow("pkg", "p02", "LEFT", emptyList(), "", "", ""),
        )
        val votes = ReviewAnalysis.aggregateTrialDefects(mapping, listOf("r1" to r1Flag))
        // The candidate trial was seen in two comparisons but contributes one reviewer boolean.
        val candidateVote = votes.getValue("T_CAND")
        assertEquals(listOf("r1"), candidateVote.flagged)
        assertEquals(emptyList<String>(), candidateVote.unflagged)
        assertEquals(ReviewAnalysis.TrialCriticalStatus.CRITICAL, ReviewAnalysis.trialCriticalStatus(candidateVote))
        assertEquals(listOf("r1"), votes.getValue("T_BASE").unflagged)
        assertEquals(listOf("r1"), votes.getValue("T_STOCK").unflagged)

        // A second reviewer who saw the trial without flagging it makes the vote disputed.
        val r2Clean = mapOf(
            "p01" to ReviewAnalysis.ResponseRow("pkg", "p01", "LEFT", emptyList(), "", "", ""),
            "p02" to ReviewAnalysis.ResponseRow("pkg", "p02", "LEFT", emptyList(), "", "", ""),
        )
        val votes2 = ReviewAnalysis.aggregateTrialDefects(mapping, listOf("r1" to r1Flag, "r2" to r2Clean))
        assertEquals(
            ReviewAnalysis.TrialCriticalStatus.DISPUTED,
            ReviewAnalysis.trialCriticalStatus(votes2.getValue("T_CAND")),
        )

        // A duplicate reviewer identity or multiple response entries under the same identity
        // collapse to a single vote per reviewer: r1 flagged in p01 is preserved and not cancelled by r1 clean in p02.
        val votes3 = ReviewAnalysis.aggregateTrialDefects(mapping, listOf("r1" to r1Flag, "r1" to r2Clean))
        assertEquals(listOf("r1"), votes3.getValue("T_CAND").flagged)
        assertEquals(emptyList<String>(), votes3.getValue("T_CAND").unflagged)
        assertEquals(ReviewAnalysis.TrialCriticalStatus.CRITICAL, ReviewAnalysis.trialCriticalStatus(votes3.getValue("T_CAND")))
    }

    @Test
    fun armSpecificCriticalAndCompletionRatesReported() {
        val root = temp.resolve("arm-rates-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        // One extra FAILED capture on baseline_pass_a (outside the comparison grains).
        val trials = DatasetModel.load(root).trials.toMutableList()
        trials += TestData.trial(
            id = "TFAIL_BASE_A", scene = "city", condition = "sun", arm = "baseline_pass_a",
            repetition = 3, captureOrder = 999, outcome = TrialOutcome.FAILED,
            failureReason = "camera session error", original = null,
        )
        TestData.writeTrialsCsv(root, trials)

        val pkg = temp.resolve("rates-pkg")
        val key = temp.resolve("rates-key")
        assertEquals(0, runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "8888888888888888888888888888888888888888888888888888888888888888")))
        // A single reviewer marks a critical defect on baseline_pass_a for exactly one pair.
        val keyProps = StrictProperties.read(key)
        val manifest = StrictProperties.read(pkg.resolve("manifest.properties"))
        val packageId = manifest.require("package_id")
        val pairs = manifest.require("pair.order").split(',')
        val rows = pairs.mapIndexed { index, p ->
            val leftArm = keyProps.require("pair.$p.left.arm")
            val side = if (leftArm == "baseline_pass_a") "LEFT" else "RIGHT"
            if (index == 0) {
                listOf(packageId, p, side, "", "SEVERE_SUBJECT_CLIPPING", side, "")
            } else {
                listOf(packageId, p, side, "", "", "", "")
            }
        }
        val responses = temp.resolve("rates-res.csv")
        Csv.write(responses, ResponseSchema.COLUMNS, rows)
        val seal = temp.resolve("rates-seal.properties")
        assertEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "$responses", "--out", "$seal", "--reviewer", "rates-r", "--category", "c", "--conflict", "NONE", "--utc-timestamp", "2026-08-02T00:00:00Z")))

        val outDir = temp.resolve("rates-report")
        assertEquals(0, analyze(pkg, key, root, outDir, listOf(seal)))
        val csv = Files.readString(outDir.resolve("analysis-report.csv"))
        // baseline_pass_a: 3 eligible trials (2 success + 1 failed), 1 reviewer-critical success.
        assertTrue(csv, csv.contains("capture_by_arm,arm_baseline_pass_a.eligible_trials,3"))
        assertTrue(csv, csv.contains("capture_by_arm,arm_baseline_pass_a.failed,1"))
        assertTrue(csv, csv.contains("capture_by_arm,arm_baseline_pass_a.reviewer_critical,1"))
        assertTrue(csv, csv.contains("capture_by_arm,arm_baseline_pass_a.disputed,0"))
        assertTrue(csv, csv.contains("capture_by_arm,arm_baseline_pass_a.critical_failure_rate,0.6667"))
        assertTrue(csv, csv.contains("capture_by_arm,arm_baseline_pass_a.completion_rate,0.6667"))
        assertTrue(csv, csv.contains("capture_by_arm,arm_baseline_pass_b.critical_failure_rate,0.0000"))
        assertTrue(csv, csv.contains("capture_by_arm,arm_baseline_pass_b.completion_rate,1.0000"))
    }

    @Test
    fun packageLinkEscapeRejectedWhenHostPermits() {
        val root = temp.resolve("pkg-link-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val pkg = temp.resolve("pkg-link-pkg")
        val key = temp.resolve("pkg-link-key")
        assertEquals(0, runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "8888888888888888888888888888888888888888888888888888888888888888")))
        val outside = temp.resolve("pkg-link-outside")
        Files.createDirectories(outside)
        val secret = outside.resolve("secret.png")
        Files.write(secret, TestData.syntheticPng(base = 9))
        val link = pkg.resolve("assets/escape-link.png")
        try {
            Files.createSymbolicLink(link, secret)
        } catch (e: Exception) {
            System.err.println("SYMLINK_CREATION_UNAVAILABLE: host denied link creation; package link escape not exercised")
            return
        }
        val responses = temp.resolve("pkg-link-res.csv")
        TestData.allTieResponses(pkg, responses)
        val code = runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "$responses", "--out", "${temp.resolve("pkg-link-seal")}", "--reviewer", "r1", "--category", "c", "--conflict", "NONE", "--utc-timestamp", "2026-08-01T00:00:00Z"))
        assertNotEquals(0, code)
    }

    @Test
    fun responsesInsideImmutablePackageRejectedAtSealAndAnalyze() {
        val root = temp.resolve("pkg-file-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val pkg = temp.resolve("pkg-files")
        val key = temp.resolve("pkg-files-key")
        assertEquals(0, runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "8888888888888888888888888888888888888888888888888888888888888888")))

        // A response file placed inside the package folder is an unmanifested file.
        val inside = pkg.resolve("responses.csv")
        TestData.allTieResponses(pkg, inside)
        val sealOut = temp.resolve("pkg-files-seal")
        val code = runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "$inside", "--out", "$sealOut", "--reviewer", "r1", "--category", "c", "--conflict", "NONE", "--utc-timestamp", "2026-08-01T00:00:00Z"))
        assertNotEquals(0, code)

        // A seal file placed inside the package folder also fails analysis.
        Files.delete(inside)
        val outside = temp.resolve("pkg-files-res.csv")
        TestData.allTieResponses(pkg, outside)
        val seal = temp.resolve("pkg-files-seal.properties")
        assertEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "$outside", "--out", "$seal", "--reviewer", "r1", "--category", "c", "--conflict", "NONE", "--utc-timestamp", "2026-08-01T00:00:00Z")))
        val sealInsidePkg = pkg.resolve("seal.properties")
        Files.copy(seal, sealInsidePkg)
        assertNotEquals(0, analyze(pkg, key, root, temp.resolve("pkg-files-out"), listOf(sealInsidePkg)))
    }

    @Test
    fun manifestSchemaAndPackageIntegrityRejected() {
        val root = temp.resolve("manifest-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val pkg = temp.resolve("manifest-pkg")
        val key = temp.resolve("manifest-key")
        assertEquals(0, runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "8888888888888888888888888888888888888888888888888888888888888888")))
        val manifestPath = pkg.resolve("manifest.properties")
        val original = StrictProperties.read(manifestPath)

        fun rewrite(entries: Map<String, String>) {
            Files.write(manifestPath, StrictProperties.serialize(entries).toByteArray(StandardCharsets.UTF_8))
        }

        // Missing mandatory manifest key.
        rewrite(original.entries - "pair.order")
        assertNotEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "${temp.resolve("m1.csv")}", "--out", "${temp.resolve("m1.seal")}", "--reviewer", "r1", "--category", "c", "--conflict", "NONE")))
        // Unknown manifest key.
        rewrite(original.entries + mapOf("bogus_key" to "1"))
        assertNotEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "${temp.resolve("m2.csv")}", "--out", "${temp.resolve("m2.seal")}", "--reviewer", "r1", "--category", "c", "--conflict", "NONE")))
        // Duplicate pair.order entry.
        rewrite(original.entries + mapOf("pair.order" to "p01,p01"))
        assertNotEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "${temp.resolve("m3.csv")}", "--out", "${temp.resolve("m3.seal")}", "--reviewer", "r1", "--category", "c", "--conflict", "NONE")))
        // pair.count mismatch.
        rewrite(original.entries + mapOf("pair.count" to "9"))
        assertNotEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "${temp.resolve("m4.csv")}", "--out", "${temp.resolve("m4.seal")}", "--reviewer", "r1", "--category", "c", "--conflict", "NONE")))

        // Missing package file.
        rewrite(original.entries)
        val asset = TestData.listAllFiles(pkg.resolve("assets")).first()
        val assetBytes = Files.readAllBytes(asset)
        Files.delete(asset)
        assertNotEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "${temp.resolve("m5.csv")}", "--out", "${temp.resolve("m5.seal")}", "--reviewer", "r1", "--category", "c", "--conflict", "NONE")))
        // Extra package file (a stray note).
        Files.write(asset, assetBytes)
        Files.writeString(pkg.resolve("stray.txt"), "x")
        assertNotEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "${temp.resolve("m6.csv")}", "--out", "${temp.resolve("m6.seal")}", "--reviewer", "r1", "--category", "c", "--conflict", "NONE")))
        // Changed asset.
        Files.delete(pkg.resolve("stray.txt"))
        Files.write(asset, assetBytes + byteArrayOf(1))
        assertNotEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "${temp.resolve("m7.csv")}", "--out", "${temp.resolve("m7.seal")}", "--reviewer", "r1", "--category", "c", "--conflict", "NONE")))
    }

    @Test
    fun keyPairSetMismatchAndUnknownFieldsRejected() {
        val root = temp.resolve("key-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val pkg = temp.resolve("key-pkg")
        val keyPath = temp.resolve("key-file")
        assertEquals(0, runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$keyPath", "--seed", "8888888888888888888888888888888888888888888888888888888888888888")))
        val responses = temp.resolve("key-res.csv")
        TestData.allTieResponses(pkg, responses)
        val seal = temp.resolve("key-seal.properties")
        assertEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "$responses", "--out", "$seal", "--reviewer", "r1", "--category", "c", "--conflict", "NONE", "--utc-timestamp", "2026-08-01T00:00:00Z")))
        val keyEntries = StrictProperties.read(keyPath).entries.toMutableMap()

        // Missing pair mapping field.
        val missingField = keyEntries.toMutableMap()
        val pairPrefix = keyEntries.keys.first { it.startsWith("pair.") && it.endsWith(".left.arm") }.substringBefore(".left.arm")
        missingField.remove("$pairPrefix.left.trial_id")
        val key1 = temp.resolve("key-1")
        Files.write(key1, StrictProperties.serialize(missingField).toByteArray(StandardCharsets.UTF_8))
        assertNotEquals(0, analyze(pkg, key1, root, temp.resolve("key-out1"), listOf(seal)))

        // Unknown key field.
        val unknownField = keyEntries.toMutableMap()
        unknownField["bogus_key"] = "1"
        val key2 = temp.resolve("key-2")
        Files.write(key2, StrictProperties.serialize(unknownField).toByteArray(StandardCharsets.UTF_8))
        assertNotEquals(0, analyze(pkg, key2, root, temp.resolve("key-out2"), listOf(seal)))

        // Malformed repetition must not leak NumberFormatException.
        val badRep = keyEntries.toMutableMap()
        badRep["$pairPrefix.repetition"] = "abc"
        val key3 = temp.resolve("key-3")
        Files.write(key3, StrictProperties.serialize(badRep).toByteArray(StandardCharsets.UTF_8))
        assertNotEquals(0, analyze(pkg, key3, root, temp.resolve("key-out3"), listOf(seal)))
    }

    @Test
    fun duplicateReviewerSealPathAndResponseFileRejected() {
        val root = temp.resolve("dupes-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val pkg = temp.resolve("dupes-pkg")
        val keyPath = temp.resolve("dupes-key")
        assertEquals(0, runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$keyPath", "--seed", "8888888888888888888888888888888888888888888888888888888888888888")))
        val responses = temp.resolve("dupes-res.csv")
        TestData.allTieResponses(pkg, responses)
        // A second response file with a different note so the hashes differ.
        val responses2 = temp.resolve("dupes-res2.csv")
        val manifest = StrictProperties.read(pkg.resolve("manifest.properties"))
        val packageId = manifest.require("package_id")
        val pairs = manifest.require("pair.order").split(',')
        Csv.write(responses2, ResponseSchema.COLUMNS, pairs.map { listOf(packageId, it, "TIE", "", "", "", "note-b") })
        val sealA = temp.resolve("dupes-a.properties")
        val sealB = temp.resolve("dupes-b.properties")
        assertEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "$responses", "--out", "$sealA", "--reviewer", "dup-r", "--category", "c", "--conflict", "NONE", "--utc-timestamp", "2026-08-01T00:00:00Z")))
        assertEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "$responses2", "--out", "$sealB", "--reviewer", "dup-r", "--category", "c", "--conflict", "NONE", "--utc-timestamp", "2026-08-01T00:00:00Z")))

        // Duplicate seal path (same file twice).
        assertNotEquals(0, analyze(pkg, keyPath, root, temp.resolve("dupes-out1"), listOf(sealA, sealA)))
        // Duplicate reviewer identity (distinct response files, same reviewer).
        assertNotEquals(0, analyze(pkg, keyPath, root, temp.resolve("dupes-out2"), listOf(sealA, sealB)))
        // Duplicate response hash (two seals over the same response file).
        val sealC = temp.resolve("dupes-c.properties")
        assertEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "$responses", "--out", "$sealC", "--reviewer", "other-r", "--category", "c", "--conflict", "NONE", "--utc-timestamp", "2026-08-01T00:00:00Z")))
        assertNotEquals(0, analyze(pkg, keyPath, root, temp.resolve("dupes-out3"), listOf(sealA, sealC)))
    }

    @Test
    fun sealReviewAcceptsBothDefectSideWithTiePreference() {
        val fix = sealedCalibration()
        val manifest = StrictProperties.read(fix.pkg.resolve("manifest.properties"))
        val packageId = manifest.require("package_id")
        val pairs = manifest.require("pair.order").split(',')

        val validBothRows = pairs.mapIndexed { index, p ->
            if (index == 0) {
                listOf(packageId, p, "TIE", "", "SEVERE_SUBJECT_CLIPPING", "BOTH", "")
            } else {
                listOf(packageId, p, "TIE", "", "", "", "")
            }
        }
        val res = temp.resolve("both-valid-res.csv")
        rawResponses(fix.pkg, ResponseSchema.COLUMNS, validBothRows, res)
        val seal = temp.resolve("both-valid-seal.properties")
        assertEquals(
            0,
            runCli(
                arrayOf(
                    "seal-review", "--package", "${fix.pkg}", "--responses", "$res", "--out", "$seal",
                    "--reviewer", "r_both", "--category", "internal", "--conflict", "NONE",
                ),
            ),
        )
        assertTrue(Files.isRegularFile(seal))
    }

    @Test
    fun sealReviewRejectsUndeclaredDefectSide() {
        val fix = sealedCalibration()
        val manifest = StrictProperties.read(fix.pkg.resolve("manifest.properties"))
        val packageId = manifest.require("package_id")
        val pairs = manifest.require("pair.order").split(',')

        val undeclaredSides = listOf("MID", "BOTH_SIDES", "left", "LEFT_RIGHT", "BOTH ")
        for ((idx, badSide) in undeclaredSides.withIndex()) {
            val rows = pairs.mapIndexed { i, p ->
                if (i == 0) {
                    listOf(packageId, p, "LEFT", "", "SEVERE_SUBJECT_CLIPPING", badSide, "")
                } else {
                    listOf(packageId, p, "LEFT", "", "", "", "")
                }
            }
            val res = temp.resolve("bad-side-$idx.csv")
            rawResponses(fix.pkg, ResponseSchema.COLUMNS, rows, res)
            val seal = temp.resolve("bad-side-$idx.seal")
            val error = assertThrows(ToolError::class.java) {
                ReviewAnalysis.runSealReview(
                    packageDir = fix.pkg,
                    responsesPath = res,
                    outPath = seal,
                    reviewer = "r9",
                    category = "internal",
                    conflict = "NONE",
                    timestamp = null,
                )
            }
            assertEquals(Codes.SEAL_RESPONSE_DEFECT, error.code)
        }
    }

    @Test
    fun simulatedV1ManifestFailsClosedUnderV2Validator() {
        val root = temp.resolve("v1-sim-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val pkg = temp.resolve("v1-sim-pkg")
        val key = temp.resolve("v1-sim-key")
        assertEquals(0, runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "8888888888888888888888888888888888888888888888888888888888888888")))

        val manifestPath = pkg.resolve("manifest.properties")
        val originalManifest = StrictProperties.read(manifestPath)
        val tamperedEntries = originalManifest.entries.toMutableMap()
        tamperedEntries["response_schema_version"] = "1"
        Files.write(manifestPath, StrictProperties.serialize(tamperedEntries).toByteArray(StandardCharsets.UTF_8))

        val error = assertThrows(ToolError::class.java) {
            PackageValidator.readAndVerifyManifest(pkg)
        }
        assertEquals(Codes.SCHEMA_MISMATCH, error.code)
    }

    @Test
    fun trialCriticalDefectAggregationBothSideMapsBothTrialsOnce() {
        val mapping = mapOf(
            "p01" to ReviewAnalysis.PairInfo("CMP_CB", ComparisonPurpose.CANDIDATE_VS_BASELINE, "city", "sun", 1, "T_LEFT", "candidate", "T_RIGHT", "baseline"),
            "p02" to ReviewAnalysis.PairInfo("CMP_CS", ComparisonPurpose.CANDIDATE_VS_STOCK, "city", "sun", 1, "T_LEFT", "candidate", "T_STOCK", "stock"),
        )
        val r1Both = mapOf(
            "p01" to ReviewAnalysis.ResponseRow("pkg", "p01", "TIE", emptyList(), "SEVERE_SUBJECT_CLIPPING", "BOTH", ""),
            "p02" to ReviewAnalysis.ResponseRow("pkg", "p02", "TIE", emptyList(), "", "", ""),
        )
        val votes = ReviewAnalysis.aggregateTrialDefects(mapping, listOf("r1" to r1Both))

        val leftVote = votes.getValue("T_LEFT")
        val rightVote = votes.getValue("T_RIGHT")
        val stockVote = votes.getValue("T_STOCK")

        // BOTH flags both T_LEFT and T_RIGHT for r1 exactly once.
        assertEquals(listOf("r1"), leftVote.flagged)
        assertEquals(emptyList<String>(), leftVote.unflagged)
        assertEquals(ReviewAnalysis.TrialCriticalStatus.CRITICAL, ReviewAnalysis.trialCriticalStatus(leftVote))

        assertEquals(listOf("r1"), rightVote.flagged)
        assertEquals(emptyList<String>(), rightVote.unflagged)
        assertEquals(ReviewAnalysis.TrialCriticalStatus.CRITICAL, ReviewAnalysis.trialCriticalStatus(rightVote))

        // T_STOCK was seen in p02 without a defect tag, so r1 is unflagged.
        assertEquals(emptyList<String>(), stockVote.flagged)
        assertEquals(listOf("r1"), stockVote.unflagged)
        assertEquals(ReviewAnalysis.TrialCriticalStatus.NONCRITICAL, ReviewAnalysis.trialCriticalStatus(stockVote))
    }

    @Test
    fun calibrationAnalyzeWithBothDefectSideReportsCriticalInBothArms() {
        val root = temp.resolve("cal-both-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val pkg = temp.resolve("cal-both-pkg")
        val key = temp.resolve("cal-both-key")
        assertEquals(0, runCli(arrayOf("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "8888888888888888888888888888888888888888888888888888888888888888")))

        val manifest = StrictProperties.read(pkg.resolve("manifest.properties"))
        val packageId = manifest.require("package_id")
        val pairs = manifest.require("pair.order").split(',')

        // First pair specifies BOTH defect side.
        val rows = pairs.mapIndexed { index, p ->
            if (index == 0) {
                listOf(packageId, p, "TIE", "", "SEVERE_SUBJECT_CLIPPING", "BOTH", "")
            } else {
                listOf(packageId, p, "TIE", "", "", "", "")
            }
        }
        val res = temp.resolve("cal-both-res.csv")
        Csv.write(res, ResponseSchema.COLUMNS, rows)
        val seal = temp.resolve("cal-both-seal.properties")
        assertEquals(0, runCli(arrayOf("seal-review", "--package", "$pkg", "--responses", "$res", "--out", "$seal", "--reviewer", "both-r", "--category", "c", "--conflict", "NONE", "--utc-timestamp", "2026-08-02T00:00:00Z")))

        val outDir = temp.resolve("cal-both-report")
        assertEquals(0, analyze(pkg, key, root, outDir, listOf(seal)))
        val csv = Files.readString(outDir.resolve("analysis-report.csv"))

        assertTrue(csv, csv.contains("status,status,INCONCLUSIVE / CALIBRATION"))
        assertTrue(csv, csv.contains("capture_by_arm,arm_baseline_pass_a.reviewer_critical,1"))
        assertTrue(csv, csv.contains("capture_by_arm,arm_baseline_pass_b.reviewer_critical,1"))
    }
}
