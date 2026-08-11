package com.proshot.tools.imagequality

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class DatasetValidatorTest {

    private val temp = Files.createTempDirectory("proshot-validator")

    @After
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun validCalibrationRoot(): Path {
        val root = temp.resolve("cal")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons(), withCrops = true)
        return root
    }

    private fun assertCritical(result: ValidationResult, code: String) {
        assertTrue("expected critical $code, got ${result.critical().map { it.code }}", result.critical().any { it.code == code })
    }

    @Test
    fun validDatasetPasses() {
        val result = DatasetValidator.validateDataset(validCalibrationRoot())
        assertTrue(result.ok)
        assertEquals(0, result.critical().size)
    }

    @Test
    fun duplicateTrialIdAndGrainFail() {
        val root = validCalibrationRoot()
        val trials = DatasetModel.load(root).trials
        val dupId = listOf(trials[0], trials[0].copy(captureOrder = 999)) + trials.drop(1)
        TestData.writeTrialsCsv(root, dupId)
        val result = DatasetValidator.validateDataset(root)
        assertFalse(result.ok)
        assertCritical(result, Codes.DUPLICATE_TRIAL_ID)

        val dupGrain = listOf(trials[0], trials[0].copy(trialId = "OTHER", captureOrder = 999)) + trials.drop(1)
        TestData.writeTrialsCsv(root, dupGrain)
        val result2 = DatasetValidator.validateDataset(root)
        assertFalse(result2.ok)
        assertCritical(result2, Codes.DUPLICATE_GRAIN)
    }

    @Test
    fun missingRepetitionCoverageFails() {
        val root = validCalibrationRoot()
        val trials = DatasetModel.load(root).trials
        // Drop every repetition-2 baseline-pass-a success, keeping the grain shape otherwise.
        val kept = trials.filter { !(it.arm == "baseline_pass_a" && it.repetition == 2) }
        TestData.writeTrialsCsv(root, kept)
        val result = DatasetValidator.validateDataset(root)
        assertFalse(result.ok)
        assertCritical(result, Codes.REPETITION_COVERAGE)
    }

    @Test
    fun outcomeContradictionsFail() {
        val root = temp.resolve("contradictions-cal")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons(), includeFailed = true, withCrops = true)
        val trials = DatasetModel.load(root).trials
        // SUCCESS missing review source facts
        val missingReview = trials.map { t ->
            if (t.outcome == TrialOutcome.SUCCESS && t.trialId == trials.first { it.outcome == TrialOutcome.SUCCESS }.trialId) {
                t.copy(reviewSourcePath = null, reviewSourceHashSha256 = null, reviewSourceByteSize = null)
            } else t
        }
        TestData.writeTrialsCsv(root, missingReview)
        val r1 = DatasetValidator.validateDataset(root)
        assertFalse(r1.ok)
        assertCritical(r1, Codes.OUTCOME_CONTRADICTION)

        // FAILED with invented file facts
        val badFailed = trials.map { t ->
            if (t.outcome == TrialOutcome.FAILED) t.copy(originalPath = "originals/fake.png") else t
        }
        TestData.writeTrialsCsv(root, badFailed)
        val r2 = DatasetValidator.validateDataset(root)
        assertFalse(r2.ok)
        assertCritical(r2, Codes.OUTCOME_CONTRADICTION)

        // FAILED without a reason
        val noReason = trials.map { t ->
            if (t.outcome == TrialOutcome.FAILED) t.copy(failureReason = null) else t
        }
        TestData.writeTrialsCsv(root, noReason)
        val r3 = DatasetValidator.validateDataset(root)
        assertFalse(r3.ok)
        assertCritical(r3, Codes.OUTCOME_CONTRADICTION)
    }

    @Test
    fun unavailableValuesStayUnavailableAndZeroStaysZero() {
        val root = temp.resolve("unavail")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons(), latencyBase = null)
        val trials = DatasetModel.load(root).trials
        // All latency_ms cells were left empty by the builder.
        assertTrue(trials.all { it.latencyMs == null })
        // A literal "0" must be a real measurement, not unavailable.
        assertEquals(0.0, Values.parseOptionalDouble("0", "latency_ms")!!, 0.0)
        assertEquals(null, Values.parseOptionalDouble("unknown", "latency_ms"))
        assertEquals(null, Values.parseOptionalDouble("n/a", "latency_ms"))
        assertEquals(null, Values.parseOptionalDouble("", "latency_ms"))
        assertEquals(5.0, Values.parseOptionalDouble("5", "latency_ms")!!, 0.0)
    }

    @Test
    fun cropAndConsentRulesFail() {
        val root = validCalibrationRoot()
        val success = DatasetModel.load(root).successfulTrials().first().trialId
        TestData.writeCropsCsv(
            root,
            listOf(Crop(success, "C1", "GENERAL", 0.2, 0.2, 0.6, 0.6), Crop("NO_SUCH_TRIAL", "C2", "GENERAL", 0.1, 0.1, 0.3, 0.3)),
        )
        val r1 = DatasetValidator.validateDataset(root)
        assertFalse(r1.ok)
        assertCritical(r1, Codes.CROP_ORPHAN)

        TestData.writeCropsCsv(root, listOf(Crop(success, "C1", "GENERAL", -0.1, 0.0, 0.5, 0.5)))
        val r2 = DatasetValidator.validateDataset(root)
        assertFalse(r2.ok)
        assertCritical(r2, Codes.CROP_INVALID_RECT)

        val trials = DatasetModel.load(root).trials
        val badConsent = trials.map { t ->
            if (t.consent == "CONSENTED" && t.publicationPermission == "PERMITTED") {
                t.copy(consent = "NOT_APPLICABLE")
            } else t
        }
        TestData.writeTrialsCsv(root, badConsent)
        val r3 = DatasetValidator.validateDataset(root)
        assertFalse(r3.ok)
        assertCritical(r3, Codes.CONSENT_CONTRADICTION)
    }

    @Test
    fun comparisonCompletenessFailsWhenArmMissing() {
        val root = validCalibrationRoot()
        val trials = DatasetModel.load(root).trials
        // Remove one baseline-pass-a eligible trial so the A/A comparison loses a pair.
        val missing = trials.filter { !(it.arm == "baseline_pass_a" && it.repetition == 2) }
        TestData.writeTrialsCsv(root, missing)
        val result = DatasetValidator.validateDataset(root)
        assertFalse(result.ok)
        assertCritical(result, Codes.COMPARISON_INCOMPLETE)
    }

    @Test
    fun emptyComparisonPlanFailsWithNoComparisonPairs() {
        val root = validCalibrationRoot()
        TestData.writeComparisonCsv(root, emptyList())

        val result = DatasetValidator.validateDataset(root)

        assertFalse(result.ok)
        assertCritical(result, Codes.NO_COMPARISON_PAIRS)
    }

    @Test
    fun hashAndByteSizeMismatchFail() {
        val root = validCalibrationRoot()
        val trials = DatasetModel.load(root).trials
        val t0 = trials.first { it.outcome == TrialOutcome.SUCCESS }
        val wrongHash = trials.map { if (it.trialId == t0.trialId) it.copy(originalHashSha256 = "0".repeat(64)) else it }
        TestData.writeTrialsCsv(root, wrongHash)
        assertCritical(DatasetValidator.validateDataset(root), Codes.HASH_MISMATCH)

        val wrongSize = trials.map { if (it.trialId == t0.trialId) it.copy(originalByteSize = (it.originalByteSize ?: 0) + 1) else it }
        TestData.writeTrialsCsv(root, wrongSize)
        assertCritical(DatasetValidator.validateDataset(root), Codes.BYTE_SIZE_MISMATCH)
    }

    @Test
    fun duplicateOriginalEvidenceFailsUnlessDeclared() {
        val root = validCalibrationRoot()
        val trials = DatasetModel.load(root).trials
        val success = trials.filter { it.outcome == TrialOutcome.SUCCESS }
        val t0 = success.first()
        val t1 = success[1]
        // Make t1 point at t0's original file (same path/hash).
        val swapped = trials.map {
            if (it.trialId == t1.trialId) it.copy(originalPath = t0.originalPath, originalHashSha256 = t0.originalHashSha256, originalByteSize = t0.originalByteSize) else it
        }
        TestData.writeTrialsCsv(root, swapped)
        assertCritical(DatasetValidator.validateDataset(root), Codes.DUPLICATE_ORIGINAL)

        // Declared same-source use case passes.
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
                "app_identifier" to "proshot-test",
                "baseline_identifier" to "baseline",
                "privacy_classification" to "PRIVATE",
                "predeclared_hypothesis" to "test hypothesis",
                "critical_scenes" to "city",
                "guardrails" to "latency_median_ms<=2000",
                "allow_shared_originals" to "true",
            ),
        )
        val result = DatasetValidator.validateDataset(root)
        assertFalse(result.critical().any { it.code == Codes.DUPLICATE_ORIGINAL })
    }

    @Test
    fun traversalAbsoluteAndResolvedEscapeRejected() {
        val root = temp.resolve("paths")
        Files.createDirectories(root.resolve("originals"))
        Files.write(root.resolve("originals/ok.png"), TestData.syntheticPng())

        val good = PathSecurity.resolve(root, "originals/ok.png", "test")
        assertTrue(Files.isRegularFile(good))

        // traversal
        val traversal = assertThrows(ToolError::class.java) { PathSecurity.resolve(root, "originals/../../escape.png", "test") }
        assertEquals(Codes.PATH_ESCAPE, traversal.code)

        // absolute
        val absolute = assertThrows(ToolError::class.java) { PathSecurity.resolve(root, root.resolve("originals/ok.png").toString(), "test") }
        assertEquals(Codes.PATH_ABSOLUTE, absolute.code)

        // pure containment is accepted (normalized traversal back into the root)
        val contained = PathSecurity.resolve(root, "originals/../originals/ok.png", "test")
        assertTrue(Files.isRegularFile(contained))
    }

    @Test
    fun resolvedRootEscapeViaSymlinkWhenHostPermits() {
        val outside = temp.resolve("outside")
        Files.createDirectories(outside)
        val secret = outside.resolve("secret.png")
        Files.write(secret, TestData.syntheticPng(base = 7))
        val root = temp.resolve("linkroot")
        Files.createDirectories(root)
        val link = root.resolve("link.png")
        try {
            Files.createSymbolicLink(link, outside.resolve("secret.png"))
        } catch (e: Exception) {
            // Host denies link creation (Windows without privilege). Record the limitation instead of claiming junction proof.
            System.err.println("SYMLINK_CREATION_UNAVAILABLE: host denied link creation; resolved-link escape not exercised")
            return
        }
        val error = assertThrows(ToolError::class.java) { PathSecurity.resolve(root, "link.png", "test") }
        assertEquals(Codes.PATH_ESCAPE_RESOLVED, error.code)
    }

    @Test
    fun reviewSourceUndecodableAndOrientationFail() {
        val root = temp.resolve("undecodable")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons(), imageExt = "heic")
        val result = DatasetValidator.validateDataset(root)
        assertFalse(result.ok)
        assertCritical(result, Codes.REVIEW_SOURCE_UNDECODABLE)

        val oriented = temp.resolve("oriented")
        Files.createDirectories(oriented)
        val jpeg = oriented.resolve("oriented.jpg")
        Files.write(jpeg, TestData.jpegWithOrientation(6))
        val error = assertThrows(ToolError::class.java) { ReviewSource.readReviewSource(jpeg) }
        assertEquals(Codes.ORIENTATION_NOT_NORMALIZED, error.code)

        val normal = oriented.resolve("normal.jpg")
        Files.write(normal, TestData.jpegWithOrientation(1))
        assertNotNull(ReviewSource.readReviewSource(normal))
    }

    @Test
    fun evidenceGrainPreservedAcrossOutcomes() {
        val root = temp.resolve("grains")
        val trials = TestData.writeStandardDataset(
            root,
            DatasetKind.CALIBRATION,
            TestData.calibrationComparisons(),
            includeFailed = true,
            includeExcluded = true,
        )
        val result = DatasetValidator.validateDataset(root)
        assertTrue(result.ok)
        assertEquals(1, trials.count { it.outcome == TrialOutcome.FAILED })
        assertEquals(1, trials.count { it.outcome == TrialOutcome.EXCLUDED })
        val pairs = PairResolver.resolve(result.dataset)
        assertEquals(2, pairs.size)
        assertTrue(pairs.all { it.trialA.outcome == TrialOutcome.SUCCESS && it.trialB.outcome == TrialOutcome.SUCCESS })
    }

    @Test
    fun blindedAaUsesDistinctArmsSameGrainAndNeverCrossesRepetitions() {
        val root = temp.resolve("aa-grain")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
        val dataset = DatasetModel.load(root)
        val pairs = PairResolver.resolve(dataset)
        // Two grains -> exactly two A/A pairs, each matching one eligible trial per arm
        // at the identical (scene, condition, repetition).
        assertEquals(2, pairs.size)
        assertEquals(setOf(1, 2), pairs.map { it.repetition }.toSet())
        for (pair in pairs) {
            assertNotEquals(pair.trialA.arm, pair.trialB.arm)
            assertEquals(pair.trialA.repetition, pair.trialB.repetition)
            assertEquals(pair.trialA.scene, pair.trialB.scene)
            assertEquals(pair.trialA.condition, pair.trialB.condition)
        }
    }

    @Test
    fun sameArmBlindedAaRejectedFailClosed() {
        val root = temp.resolve("aa-same-arm")
        TestData.writeStandardDataset(
            root,
            DatasetKind.CALIBRATION,
            listOf(ComparisonPlanRow("CMP_AA", "baseline_pass_a", "baseline_pass_a", ComparisonPurpose.BLINDED_AA)),
        )
        val result = DatasetValidator.validateDataset(root)
        assertFalse(result.ok)
        assertCritical(result, Codes.AA_SAME_ARM)
    }

    @Test
    fun inconsistentCandidateRolesRejected() {
        // Two CANDIDATE_VS_BASELINE rows using different candidate arms.
        val root = temp.resolve("roles-a")
        TestData.writeStandardDataset(
            root,
            DatasetKind.CANDIDATE,
            listOf(
                ComparisonPlanRow("CMP_1", "candidate", "baseline", ComparisonPurpose.CANDIDATE_VS_BASELINE),
                ComparisonPlanRow("CMP_2", "stock", "baseline", ComparisonPurpose.CANDIDATE_VS_BASELINE),
            ),
        )
        val result = DatasetValidator.validateDataset(root)
        assertFalse(result.ok)
        assertCritical(result, Codes.INCONSISTENT_ROLE)

        // A single swapped row: arm_a must be the candidate arm and arm_b the locked baseline.
        val swapped = temp.resolve("roles-b")
        TestData.writeStandardDataset(
            swapped,
            DatasetKind.CANDIDATE,
            listOf(ComparisonPlanRow("CMP_1", "baseline", "candidate", ComparisonPurpose.CANDIDATE_VS_BASELINE)),
        )
        val result2 = DatasetValidator.validateDataset(swapped)
        assertFalse(result2.ok)
        assertCritical(result2, Codes.INCONSISTENT_ROLE)
    }
}
