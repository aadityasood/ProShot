package com.proshot.tools.imagequality

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Accepted guardrail metric names and their syntax. Guardrails are written as
 * `name<op>value` entries separated by commas (for example
 * `failure_rate<=0.10,latency_median_ms<=1500`). `privacy_no_leak` is a
 * documented assertion that the package creation scan enforces by construction.
 *
 * Rate metrics must have finite targets inside [0,1]; latency, memory, and
 * output-byte limits must be finite and nonnegative. Unknown names and
 * non-finite bounds fail locking.
 */
internal object Guardrails {
    val KNOWN: Set<String> = setOf(
        "failure_rate", "completion_rate", "exclusion_rate", "critical_failure_rate",
        "latency_median_ms", "memory_median_kb", "output_bytes_median",
        "fallback_rate", "aa_arm_a_preference_rate", "privacy_no_leak",
    )
    private val RATE_NAMES: Set<String> = setOf(
        "failure_rate", "completion_rate", "exclusion_rate", "critical_failure_rate",
        "fallback_rate", "aa_arm_a_preference_rate",
    )
    private val NONNEGATIVE_NAMES: Set<String> = setOf("latency_median_ms", "memory_median_kb", "output_bytes_median")

    fun validateSyntax(text: String) {
        if (text.isBlank()) return
        for (entry in text.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = Regex("^([a-z_]+)(<=|>=|<|>|=)(.+)$").matchEntire(entry)
                ?: throw ToolError(Codes.LOCK_INVALID_RULE, "malformed guardrail '$entry'")
            val name = m.groupValues[1]
            if (name !in KNOWN) {
                throw ToolError(Codes.LOCK_INVALID_RULE, "unknown guardrail metric '$name'")
            }
            if (name == "privacy_no_leak") {
                if (m.groupValues[3] != "yes") {
                    throw ToolError(Codes.LOCK_INVALID_RULE, "privacy_no_leak requires value 'yes'")
                }
                continue
            }
            val target = m.groupValues[3].toDoubleOrNull()
                ?: throw ToolError(Codes.LOCK_INVALID_RULE, "guardrail '$name' requires a numeric bound")
            if (!target.isFinite()) {
                throw ToolError(Codes.LOCK_INVALID_RULE, "guardrail '$name' bound must be finite")
            }
            if (name in RATE_NAMES && (target < 0.0 || target > 1.0)) {
                throw ToolError(Codes.LOCK_INVALID_RULE, "guardrail '$name' is a rate and its bound must be inside [0,1]")
            }
            if (name in NONNEGATIVE_NAMES && target < 0.0) {
                throw ToolError(Codes.LOCK_INVALID_RULE, "guardrail '$name' bound must be nonnegative")
            }
        }
    }
}

/**
 * Threshold template emission (from calibration analysis) and the
 * `lock-thresholds` command.
 *
 * The locked artifact binds the contract version, baseline identifier,
 * calibration dataset/report/package/seal hashes, tie rule, adequacy decision,
 * margins, guardrails, owner approval, and a UTC timestamp, and is
 * immutable-by-hash and non-overwritable.
 */
internal object ThresholdLock {

    private val HUMAN_FIELDS: List<String> = listOf(
        "adequacy_decision", "adequacy_justification", "min_sample_per_grain",
        "critical_failure_margin", "reliability_margin_non_inferiority",
        "usefulness_rule", "guardrails", "critical_scene_families",
        "unavailable_metric_policy", "approval_identity", "approval_category",
        "approval_timestamp_utc",
    )

    fun emitTemplate(
        outDir: Path,
        dataset: Dataset,
        manifestPath: Path,
        reportPath: Path,
        seals: List<ReviewAnalysis.SealRecord>,
    ): Path {
        val entries = LinkedHashMap<String, String>()
        entries["threshold_schema_version"] = TOOL_SCHEMA_VERSION
        entries["contract_version"] = dataset.props.contractVersion
        entries["baseline_identifier"] = dataset.props.baselineIdentifier
        entries["tie_rule"] = "decisive-rate-exclusion-ties-reported"
        entries["calibration_dataset_path"] = dataset.root.toAbsolutePath().normalize().toString()
        entries["calibration_dataset_hash_sha256"] = Hashes.sha256Directory(dataset.root)
        entries["calibration_report_path"] = reportPath.toAbsolutePath().normalize().toString()
        entries["calibration_report_hash_sha256"] = Hashes.sha256File(reportPath)
        entries["calibration_package_path"] = manifestPath.toAbsolutePath().normalize().toString()
        entries["calibration_package_hash_sha256"] = Hashes.sha256File(manifestPath)
        for ((i, seal) in seals.withIndex()) {
            entries["calibration_seal_path.${i + 1}"] = seal.path.toAbsolutePath().normalize().toString()
            entries["calibration_seal_hash_sha256.${i + 1}"] = Hashes.sha256File(seal.path)
        }
        for (key in HUMAN_FIELDS) {
            entries[key] = ""
        }
        val templatePath = outDir.resolve("threshold-template.properties")
        StrictProperties.write(templatePath, entries)
        return templatePath
    }

    fun runLock(templatePath: Path, outPath: Path, timestamp: String?) {
        if (!Files.isRegularFile(templatePath)) {
            throw ToolError(Codes.THRESHOLD_MISSING, "threshold template missing: '$templatePath'")
        }
        val draft = StrictProperties.read(templatePath)
        if (draft.require("threshold_schema_version") != TOOL_SCHEMA_VERSION) {
            throw ToolError(Codes.THRESHOLD_INVALID, "unsupported threshold schema version")
        }
        verifyBoundHashes(draft)
        validateHumanFields(draft)
        guardNewFile(outPath)
        val lockTime = UtcClock.canonical(timestamp ?: UtcClock.now())
        val entries = LinkedHashMap<String, String>()
        for ((k, v) in draft.entries.toSortedMap()) entries[k] = v
        entries["lock_generated_at_utc"] = lockTime
        val selfHash = Hashes.sha256(StrictProperties.serialize(entries))
        entries["lock_self_hash_sha256"] = selfHash
        StrictProperties.write(outPath, entries)
    }

    private fun boundPath(raw: String, key: String): Path = try {
        Path.of(raw)
    } catch (e: InvalidPathException) {
        throw ToolError(Codes.LOCK_EVIDENCE_MISMATCH, "template '$key' is not a valid path: '$raw'")
    }

    private fun verifyBoundHashes(draft: PropMap) {
        val datasetPath = boundPath(draft.require("calibration_dataset_path"), "calibration_dataset_path")
        if (Hashes.sha256Directory(datasetPath) != draft.require("calibration_dataset_hash_sha256")) {
            throw ToolError(Codes.LOCK_EVIDENCE_MISMATCH, "calibration dataset hash does not match the bound value")
        }
        val reportPath = boundPath(draft.require("calibration_report_path"), "calibration_report_path")
        if (Hashes.sha256File(reportPath) != draft.require("calibration_report_hash_sha256")) {
            throw ToolError(Codes.LOCK_EVIDENCE_MISMATCH, "calibration report hash does not match the bound value")
        }
        val packagePath = boundPath(draft.require("calibration_package_path"), "calibration_package_path")
        if (Hashes.sha256File(packagePath) != draft.require("calibration_package_hash_sha256")) {
            throw ToolError(Codes.LOCK_EVIDENCE_MISMATCH, "calibration package hash does not match the bound value")
        }
        val sealPathKeys = draft.entries.keys.filter { it.startsWith("calibration_seal_path.") }
        if (sealPathKeys.isEmpty()) {
            throw ToolError(Codes.LOCK_EVIDENCE_MISMATCH, "template binds no calibration seal")
        }
        for (key in sealPathKeys.sorted()) {
            val index = key.removePrefix("calibration_seal_path.")
            val sealPath = boundPath(draft.require(key), key)
            val sealHash = draft.require("calibration_seal_hash_sha256.$index")
            if (Hashes.sha256File(sealPath) != sealHash) {
                throw ToolError(Codes.LOCK_EVIDENCE_MISMATCH, "calibration seal $index hash does not match the bound value")
            }
        }
    }

    private fun validateHumanFields(draft: PropMap) {
        for (key in HUMAN_FIELDS) {
            if (draft.get(key).isNullOrBlank()) {
                throw ToolError(Codes.LOCK_MISSING_FIELD, "threshold draft is missing human field '$key'")
            }
        }
        val adequacy = draft.require("adequacy_decision")
        if (adequacy !in setOf("ADEQUATE", "INADEQUATE", "CONDITIONAL")) {
            throw ToolError(Codes.LOCK_INVALID_RULE, "adequacy_decision must be ADEQUATE, INADEQUATE, or CONDITIONAL")
        }
        val minSample = draft.require("min_sample_per_grain").toIntOrNull()
        if (minSample == null || minSample <= 0) {
            throw ToolError(Codes.LOCK_INVALID_RULE, "min_sample_per_grain must be a positive integer")
        }
        finiteUnitMargin(draft.require("critical_failure_margin"), "critical_failure_margin")
        finiteUnitMargin(draft.require("reliability_margin_non_inferiority"), "reliability_margin_non_inferiority")
        val usefulness = draft.require("usefulness_rule")
        if (ReviewAnalysis.UsefulnessRule.parse(usefulness) == null) {
            throw ToolError(
                Codes.LOCK_INVALID_RULE,
                "usefulness_rule must be 'decisive_preference_rate>=X' or 'decisive_preference_lower_bound>=X' with X finite and inside [0,1]",
            )
        }
        Guardrails.validateSyntax(draft.require("guardrails"))
        val scenes = draft.require("critical_scene_families").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (scenes.isEmpty()) {
            throw ToolError(Codes.LOCK_INVALID_RULE, "critical_scene_families must list at least one scene family")
        }
        UtcClock.canonical(draft.require("approval_timestamp_utc"))
        if (draft.require("tie_rule") != "decisive-rate-exclusion-ties-reported") {
            throw ToolError(Codes.LOCK_INVALID_RULE, "tie_rule must remain decisive-rate-exclusion-ties-reported")
        }
    }

    private fun finiteUnitMargin(value: String, key: String): Double {
        val parsed = value.toDoubleOrNull()
            ?: throw ToolError(Codes.LOCK_INVALID_RULE, "$key must be a decimal in [0,1]")
        if (!parsed.isFinite() || parsed < 0.0 || parsed > 1.0) {
            throw ToolError(Codes.LOCK_INVALID_RULE, "$key must be finite and inside [0,1]")
        }
        return parsed
    }
}
