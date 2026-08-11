package com.proshot.tools.imagequality

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ThresholdLockTest {

    private val temp = Files.createTempDirectory("proshot-threshold")

    @After
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun cli(vararg args: String): Int = runCli(arrayOf(*args))

    private data class Bundle(val pkg: Path, val key: Path, val seal: Path)

    private fun sealedBundle(root: Path, label: String): Bundle {
        val pkg = temp.resolve("pkg-$label")
        val key = temp.resolve("key-$label")
        val seed = "aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666aaaa1111bbbb2222"
        assertEquals(0, cli("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "$seed"))
        val responses = temp.resolve("res-$label.csv")
        TestData.allTieResponses(pkg, responses)
        val seal = temp.resolve("seal-$label.properties")
        assertEquals(0, cli("seal-review", "--package", "$pkg", "--responses", "$responses", "--out", "$seal", "--reviewer", "reviewer-$label", "--category", "c", "--conflict", "NONE", "--utc-timestamp", "2026-08-01T00:00:00Z"))
        return Bundle(pkg, key, seal)
    }

    private data class CalibrationLock(
        val root: Path,
        val bundle: Bundle,
        val reportOut: Path,
        val template: Path,
        val draft: Path,
        val lock: Path,
    )

    private fun makeCalibrationLock(): CalibrationLock {
        val root = temp.resolve("cal-lock-root")
        TestData.writeStandardDataset(root, DatasetKind.CALIBRATION, TestData.calibrationComparisons(), withCrops = true)
        val bundle = sealedBundle(root, "cal")
        val reportOut = temp.resolve("cal-lock-report")
        assertEquals(0, cli("analyze", "--package", "${bundle.pkg}", "--key", "${bundle.key}", "--root", "$root", "--out-dir", "$reportOut", "--seal", "${bundle.seal}", "--utc-timestamp", "2026-08-01T00:00:00Z"))
        val template = reportOut.resolve("threshold-template.properties")
        assertTrue(Files.isRegularFile(template))
        val templateProps = StrictProperties.read(template)
        assertTrue(templateProps.get("calibration_dataset_hash_sha256").isNullOrBlank().not())
        assertTrue(templateProps.get("calibration_report_hash_sha256").isNullOrBlank().not())
        assertTrue(templateProps.get("calibration_package_hash_sha256").isNullOrBlank().not())
        assertTrue(templateProps.get("calibration_seal_hash_sha256.1").isNullOrBlank().not())
        assertEquals("decisive-rate-exclusion-ties-reported", templateProps.require("tie_rule"))
        assertEquals("T18.0-v1", templateProps.require("contract_version"))

        val draft = temp.resolve("cal-lock-draft.properties")
        TestData.fillThresholdTemplate(template, draft)
        val lock = temp.resolve("cal-lock.lock")
        assertEquals(0, cli("lock-thresholds", "--template", "$draft", "--out", "$lock", "--utc-timestamp", "2026-08-01T00:00:00Z"))
        val lockProps = StrictProperties.read(lock)
        assertTrue(lockProps.get("lock_self_hash_sha256").isNullOrBlank().not())
        assertTrue(lockProps.get("lock_generated_at_utc").isNullOrBlank().not())
        return CalibrationLock(root, bundle, reportOut, template, draft, lock)
    }

    private fun syntheticLock(entries: Map<String, String>, out: Path): Path {
        val selfHash = Hashes.sha256(StrictProperties.serialize(entries))
        StrictProperties.write(out, entries + mapOf("lock_self_hash_sha256" to selfHash))
        return out
    }

    private fun candidateWithSeal(contractVersion: String, label: String): Pair<Path, Bundle> {
        val root = temp.resolve("cand-$label")
        TestData.writeStandardDataset(root, DatasetKind.CANDIDATE, TestData.candidateComparisons(), contractVersion = contractVersion)
        return root to sealedBundle(root, label)
    }

    @Test
    fun lockBindsRequiredFieldsAndRefusesOverwrite() {
        val fixture = makeCalibrationLock()
        // Overwrite refusal.
        val second = temp.resolve("second.lock")
        assertNotEquals(0, cli("lock-thresholds", "--template", "${fixture.draft}", "--out", "${fixture.lock}", "--utc-timestamp", "2026-08-01T00:00:00Z"))
        assertEquals(0, cli("lock-thresholds", "--template", "${fixture.draft}", "--out", "$second", "--utc-timestamp", "2026-08-01T00:00:00Z"))
        assertTrue(Files.isRegularFile(second))
    }

    @Test
    fun missingHumanFieldAndInvalidRulesFail() {
        val fixture = makeCalibrationLock()
        val draftProps = StrictProperties.read(fixture.draft).entries.toMutableMap()

        val missingField = temp.resolve("missing-field.properties")
        draftProps["adequacy_justification"] = ""
        StrictProperties.write(missingField, draftProps)
        assertNotEquals(0, cli("lock-thresholds", "--template", "$missingField", "--out", "${temp.resolve("mf.lock")}"))

        val badMargin = temp.resolve("bad-margin.properties")
        draftProps["adequacy_justification"] = "ok"
        draftProps["critical_failure_margin"] = "1.5"
        StrictProperties.write(badMargin, draftProps)
        assertNotEquals(0, cli("lock-thresholds", "--template", "$badMargin", "--out", "${temp.resolve("bm.lock")}"))

        val badGuardrail = temp.resolve("bad-guardrail.properties")
        draftProps["critical_failure_margin"] = "0.2"
        draftProps["guardrails"] = "bogus_metric<=0.1"
        StrictProperties.write(badGuardrail, draftProps)
        assertNotEquals(0, cli("lock-thresholds", "--template", "$badGuardrail", "--out", "${temp.resolve("bg.lock")}"))
    }

    @Test
    fun changedBoundEvidenceFailsLocking() {
        val fixture = makeCalibrationLock()
        // Tamper the calibration report after the template was generated.
        val reportPath = fixture.reportOut.resolve("analysis-report.csv")
        Files.writeString(reportPath, Files.readString(reportPath) + "x,y,z\r\n")
        assertNotEquals(0, cli("lock-thresholds", "--template", "${fixture.draft}", "--out", "${temp.resolve("evidence.lock")}"))
    }

    @Test
    fun candidateAcceptsMatchingLockAndRejectsIdentityViolations() {
        val fixture = makeCalibrationLock()

        // Matching candidate (same contract and baseline) with a valid lock: analysis completes.
        val (candRoot, candBundle) = candidateWithSeal("T18.0-v1", "match")
        val passOut = temp.resolve("cand-pass")
        assertEquals(0, cli(
            "analyze", "--package", "${candBundle.pkg}", "--key", "${candBundle.key}", "--root", "$candRoot",
            "--out-dir", "$passOut", "--seal", "${candBundle.seal}", "--threshold", "${fixture.lock}",
            "--utc-timestamp", "2026-08-02T00:00:00Z",
        ))

        // Contract mismatch.
        val (otherRoot, otherBundle) = candidateWithSeal("OTHER-CONTRACT", "contract")
        val otherOut = temp.resolve("cand-contract")
        assertNotEquals(0, cli(
            "analyze", "--package", "${otherBundle.pkg}", "--key", "${otherBundle.key}", "--root", "$otherRoot",
            "--out-dir", "$otherOut", "--seal", "${otherBundle.seal}", "--threshold", "${fixture.lock}",
            "--utc-timestamp", "2026-08-02T00:00:00Z",
        ))

        // Self-hash tamper.
        val tamperedLock = temp.resolve("tampered.lock")
        Files.writeString(tamperedLock, Files.readString(fixture.lock) + "tampered=yes\n")
        val tamperOut = temp.resolve("cand-selfhash")
        assertNotEquals(0, cli(
            "analyze", "--package", "${candBundle.pkg}", "--key", "${candBundle.key}", "--root", "$candRoot",
            "--out-dir", "$tamperOut", "--seal", "${candBundle.seal}", "--threshold", "$tamperedLock",
            "--utc-timestamp", "2026-08-02T00:00:00Z",
        ))
    }

    @Test
    fun postUnblindingAndCalibrationEvidenceIdentityRejected() {
        val (candRoot, candBundle) = candidateWithSeal("T18.0-v1", "evidence")
        val candidateHash = Hashes.sha256Directory(candRoot)

        val base = linkedMapOf(
            "threshold_schema_version" to TOOL_SCHEMA_VERSION,
            "contract_version" to "T18.0-v1",
            "baseline_identifier" to "baseline",
            "calibration_dataset_hash_sha256" to "f".repeat(64),
            "calibration_report_hash_sha256" to "a".repeat(64),
            "calibration_package_hash_sha256" to "b".repeat(64),
            "calibration_seal_hash_sha256.1" to "c".repeat(64),
            "lock_generated_at_utc" to "2026-08-05T00:00:00Z",
        )

        // Post-unblinding: lock timestamp after the candidate seal timestamp (2026-08-01).
        val postLock = temp.resolve("post.lock")
        syntheticLock(base, postLock)
        val postOut = temp.resolve("post-out")
        assertNotEquals(0, cli(
            "analyze", "--package", "${candBundle.pkg}", "--key", "${candBundle.key}", "--root", "$candRoot",
            "--out-dir", "$postOut", "--seal", "${candBundle.seal}", "--threshold", "$postLock",
            "--utc-timestamp", "2026-08-02T00:00:00Z",
        ))

        // Calibration evidence identity: lock claims the candidate dataset as its calibration source.
        val calEvidenceLock = temp.resolve("calev.lock")
        syntheticLock(base + mapOf("calibration_dataset_hash_sha256" to candidateHash), calEvidenceLock)
        val calEvidenceOut = temp.resolve("calev-out")
        assertNotEquals(0, cli(
            "analyze", "--package", "${candBundle.pkg}", "--key", "${candBundle.key}", "--root", "$candRoot",
            "--out-dir", "$calEvidenceOut", "--seal", "${candBundle.seal}", "--threshold", "$calEvidenceLock",
            "--utc-timestamp", "2026-08-02T00:00:00Z",
        ))
    }

    @Test
    fun unsupportedAndMalformedUsefulnessRulesFailLocking() {
        val fixture = makeCalibrationLock()
        var i = 0
        for (rule in listOf(
            "preference_rate>=0.5",
            "decisive_preference_rate>=1.5",
            "decisive_preference_rate>=-0.1",
            "decisive_preference_lower_bound>=NaN",
            "decisive_preference_rate>=0.5,extra",
        )) {
            val draft = temp.resolve("bad-usefulness-$i.properties")
            TestData.fillThresholdTemplate(fixture.template, draft, usefulness = rule)
            assertNotEquals(0, cli("lock-thresholds", "--template", "$draft", "--out", "${temp.resolve("bu-$i.lock")}", "--utc-timestamp", "2026-08-01T00:00:00Z"))
            i++
        }
        // The exact accepted grammar still locks.
        val good = temp.resolve("good-usefulness.properties")
        TestData.fillThresholdTemplate(fixture.template, good, usefulness = "decisive_preference_rate>=0.5")
        assertEquals(0, cli("lock-thresholds", "--template", "$good", "--out", "${temp.resolve("good-u.lock")}", "--utc-timestamp", "2026-08-01T00:00:00Z"))
    }

    @Test
    fun malformedNumericAndGuardrailDomainsFailLocking() {
        val fixture = makeCalibrationLock()
        val nanMargin = temp.resolve("nan-margin.properties")
        TestData.fillThresholdTemplate(fixture.template, nanMargin, failureMargin = "NaN")
        assertNotEquals(0, cli("lock-thresholds", "--template", "$nanMargin", "--out", "${temp.resolve("nm.lock")}", "--utc-timestamp", "2026-08-01T00:00:00Z"))

        val infMargin = temp.resolve("inf-margin.properties")
        TestData.fillThresholdTemplate(fixture.template, infMargin, failureMargin = "Infinity")
        assertNotEquals(0, cli("lock-thresholds", "--template", "$infMargin", "--out", "${temp.resolve("im.lock")}", "--utc-timestamp", "2026-08-01T00:00:00Z"))

        // Rate guardrail target outside [0,1].
        val rateGuard = temp.resolve("rate-guard.properties")
        TestData.fillThresholdTemplate(fixture.template, rateGuard, guardrails = "failure_rate<=1.5,privacy_no_leak=yes")
        assertNotEquals(0, cli("lock-thresholds", "--template", "$rateGuard", "--out", "${temp.resolve("rg.lock")}", "--utc-timestamp", "2026-08-01T00:00:00Z"))

        // Latency bound must be nonnegative.
        val negLatency = temp.resolve("neg-latency.properties")
        TestData.fillThresholdTemplate(fixture.template, negLatency, guardrails = "latency_median_ms<=-1,privacy_no_leak=yes")
        assertNotEquals(0, cli("lock-thresholds", "--template", "$negLatency", "--out", "${temp.resolve("nl.lock")}", "--utc-timestamp", "2026-08-01T00:00:00Z"))

        // Unknown guardrail name fails locking.
        val unknownGuard = temp.resolve("unknown-guard.properties")
        TestData.fillThresholdTemplate(fixture.template, unknownGuard, guardrails = "bogus_metric<=0.1")
        assertNotEquals(0, cli("lock-thresholds", "--template", "$unknownGuard", "--out", "${temp.resolve("ug.lock")}", "--utc-timestamp", "2026-08-01T00:00:00Z"))

        // Malformed lock timestamp on the command line.
        assertNotEquals(0, cli("lock-thresholds", "--template", "${fixture.draft}", "--out", "${temp.resolve("bt.lock")}", "--utc-timestamp", "garbage-timestamp"))
    }

    @Test
    fun changedCalibrationArtifactRejectsCandidateAnalysis() {
        val fixture = makeCalibrationLock()
        val (candRoot, candBundle) = candidateWithSeal("T18.0-v1", "mutated")
        // Mutate the calibration report after locking; candidate analysis must fail closed.
        Files.writeString(fixture.reportOut.resolve("analysis-report.csv"), Files.readString(fixture.reportOut.resolve("analysis-report.csv")) + "x,y,z\r\n")
        val out = temp.resolve("mutated-out")
        assertNotEquals(0, cli(
            "analyze", "--package", "${candBundle.pkg}", "--key", "${candBundle.key}", "--root", "$candRoot",
            "--out-dir", "$out", "--seal", "${candBundle.seal}", "--threshold", "${fixture.lock}",
            "--utc-timestamp", "2026-08-02T00:00:00Z",
        ))
    }

    @Test
    fun lockCriticalSceneSetMustEqualCandidatePredeclaredScenes() {
        val fixture = makeCalibrationLock()
        val (candRoot, candBundle) = candidateWithSeal("T18.0-v1", "scenes")
        val draft = temp.resolve("scene-mismatch.properties")
        TestData.fillThresholdTemplate(fixture.template, draft, scenes = "city,portrait")
        val lock = temp.resolve("scene-mismatch.lock")
        assertEquals(0, cli("lock-thresholds", "--template", "$draft", "--out", "$lock", "--utc-timestamp", "2026-08-01T00:00:00Z"))
        val out = temp.resolve("scene-out")
        assertNotEquals(0, cli(
            "analyze", "--package", "${candBundle.pkg}", "--key", "${candBundle.key}", "--root", "$candRoot",
            "--out-dir", "$out", "--seal", "${candBundle.seal}", "--threshold", "$lock",
            "--utc-timestamp", "2026-08-02T00:00:00Z",
        ))
    }

    @Test
    fun unavailableBindingGuardrailIsInconclusiveNotFail() {
        val fixture = makeCalibrationLock()
        val root = temp.resolve("guard-unavail")
        TestData.writeStandardDataset(root, DatasetKind.CANDIDATE, TestData.candidateComparisons(), latencyBase = null)
        val pkg = temp.resolve("guard-unavail-pkg")
        val key = temp.resolve("guard-unavail-key")
        val seed = "aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666aaaa1111bbbb2222"
        assertEquals(0, cli("blind", "--root", "$root", "--out-dir", "$pkg", "--key", "$key", "--seed", "$seed"))
        val responses = temp.resolve("guard-unavail-res.csv")
        TestData.candidatePreferredResponses(pkg, key, responses)
        val seal = temp.resolve("guard-unavail-seal.properties")
        assertEquals(0, cli("seal-review", "--package", "$pkg", "--responses", "$responses", "--out", "$seal", "--reviewer", "guard-r", "--category", "c", "--conflict", "NONE", "--utc-timestamp", "2026-08-01T00:00:00Z"))
        val out = temp.resolve("guard-unavail-out")
        assertEquals(0, cli(
            "analyze", "--package", "$pkg", "--key", "$key", "--root", "$root",
            "--out-dir", "$out", "--seal", "$seal", "--threshold", "${fixture.lock}",
            "--utc-timestamp", "2026-08-02T00:00:00Z",
        ))
        val csv = Files.readString(out.resolve("analysis-report.csv"))
        assertTrue(csv, csv.contains("status,status,INCONCLUSIVE"))
        assertFalse(csv, csv.contains("status,status,PASS"))
    }
}
