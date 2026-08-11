package com.proshot.tools.imagequality

import java.nio.file.Files
import java.nio.file.Path

internal enum class Severity { INFO, WARNING, CRITICAL }

internal class ValidationIssue(
    val severity: Severity,
    val code: String,
    val location: String,
    val evidence: String,
    val remediation: String,
) {
    override fun toString(): String = "$severity $code $location: $evidence"
}

internal class ValidationResult(
    val ok: Boolean,
    val issues: List<ValidationIssue>,
    val dataset: Dataset,
) {
    fun critical(): List<ValidationIssue> = issues.filter { it.severity == Severity.CRITICAL }
    fun warnings(): List<ValidationIssue> = issues.filter { it.severity == Severity.WARNING }
}

/**
 * The `validate` command and the pure evidence gate reused by `blind`.
 *
 * Any CRITICAL finding makes the dataset ineligible for blinding or analysis.
 * Warnings are reported explicitly and never silently pass a binding rule.
 */
internal object DatasetValidator {

    fun runValidate(root: Path, outDir: Path): Int {
        guardOutDir(outDir)
        val result = validateDataset(root)
        writeReports(outDir, result)
        if (!result.ok) {
            throw ToolError(
                Codes.VALIDATION_FAILED,
                "dataset ineligible: ${result.critical().size} critical finding(s); see validation-report.txt",
            )
        }
        return ToolExitCode.SUCCESS.value
    }

    fun validateDataset(root: Path): ValidationResult {
        val dataset = try {
            DatasetModel.load(root)
        } catch (e: ToolError) {
            return ValidationResult(
                ok = false,
                issues = listOf(
                    ValidationIssue(
                        Severity.CRITICAL, e.code, "dataset",
                        e.message ?: "load failed", "Fix the reported dataset structure and re-run validate.",
                    ),
                ),
                dataset = DatasetModel.emptyAt(root),
            )
        }
        val issues = mutableListOf<ValidationIssue>()
        checkTrials(dataset, issues)
        checkCoverage(dataset, issues)
        checkComparisons(dataset, issues)
        checkCrops(dataset, issues)
        checkFileEvidence(dataset, issues)
        val ok = issues.none { it.severity == Severity.CRITICAL }
        return ValidationResult(ok, issues, dataset)
    }

    private fun checkTrials(dataset: Dataset, issues: MutableList<ValidationIssue>) {
        val seenIds = mutableSetOf<String>()
        val seenGrains = mutableSetOf<String>()
        for (trial in dataset.trials) {
            val loc = "trial ${trial.trialId}"
            if (!seenIds.add(trial.trialId)) {
                issues += critical(Codes.DUPLICATE_TRIAL_ID, loc, "trial_id repeated", "make trial_id unique")
            }
            val grain = trial.grainKey()
            if (!seenGrains.add(grain)) {
                issues += critical(Codes.DUPLICATE_GRAIN, loc, "duplicate grain (scene, condition, arm, repetition)", "make the declared grain unique")
            }
            if (trial.scene.isBlank()) issues += critical(Codes.INVALID_ENUM, loc, "scene is blank", "provide a scene name")
            if (trial.condition.isBlank()) issues += critical(Codes.INVALID_ENUM, loc, "condition is blank", "provide a condition name")
            if (trial.arm !in dataset.props.declaredArms) {
                issues += critical(Codes.UNKNOWN_ARM, loc, "arm '${trial.arm}' not in declared_arms", "use an arm declared in dataset.properties")
            }
            when (trial.outcome) {
                TrialOutcome.SUCCESS -> {
                    if (trial.failureReason != null || trial.exclusionReason != null) {
                        issues += critical(Codes.OUTCOME_CONTRADICTION, loc, "SUCCESS must not carry failure/exclusion reasons", "clear those fields")
                    }
                    val missing = buildList {
                        if (trial.originalPath == null) add("original_path")
                        if (trial.originalHashSha256 == null) add("original_hash_sha256")
                        if (trial.originalByteSize == null) add("original_byte_size")
                        if (trial.originalWidth == null) add("original_width")
                        if (trial.originalHeight == null) add("original_height")
                        if (trial.originalFormat == null) add("original_format")
                        if (trial.reviewSourcePath == null) add("review_source_path")
                        if (trial.reviewSourceHashSha256 == null) add("review_source_hash_sha256")
                        if (trial.reviewSourceByteSize == null) add("review_source_byte_size")
                    }
                    if (missing.isNotEmpty()) {
                        issues += critical(Codes.OUTCOME_CONTRADICTION, loc, "SUCCESS missing required file facts: ${missing.joinToString(",")}", "record every required fact")
                    }
                    if ((trial.originalWidth ?: 0) <= 0 || (trial.originalHeight ?: 0) <= 0) {
                        issues += critical(Codes.OUTCOME_CONTRADICTION, loc, "SUCCESS requires positive dimensions", "record width and height")
                    }
                }
                TrialOutcome.FAILED -> {
                    if (trial.failureReason.isNullOrBlank()) {
                        issues += critical(Codes.OUTCOME_CONTRADICTION, loc, "FAILED requires a failure_reason", "record the failure reason")
                    }
                    val invented = buildList {
                        if (trial.originalPath != null) add("original_path")
                        if (trial.originalHashSha256 != null) add("original_hash_sha256")
                        if (trial.originalByteSize != null) add("original_byte_size")
                        if (trial.originalWidth != null) add("original_width")
                        if (trial.originalHeight != null) add("original_height")
                        if (trial.originalFormat != null) add("original_format")
                        if (trial.reviewSourcePath != null) add("review_source_path")
                        if (trial.reviewSourceHashSha256 != null) add("review_source_hash_sha256")
                        if (trial.reviewSourceByteSize != null) add("review_source_byte_size")
                    }
                    if (invented.isNotEmpty()) {
                        issues += critical(Codes.OUTCOME_CONTRADICTION, loc, "FAILED forbids invented file facts: ${invented.joinToString(",")}", "clear file facts for failed captures")
                    }
                }
                TrialOutcome.EXCLUDED -> {
                    if (trial.exclusionReason.isNullOrBlank()) {
                        issues += critical(Codes.OUTCOME_CONTRADICTION, loc, "EXCLUDED requires a pre-unblinding exclusion_reason", "record the exclusion reason")
                    }
                }
            }
            if (trial.consent !in Values.CONSENT_VALUES) {
                issues += critical(Codes.INVALID_ENUM, loc, "consent '${trial.consent}' not accepted", "use an accepted consent value")
            }
            if (trial.provenance !in Values.PROVENANCE_VALUES) {
                issues += critical(Codes.INVALID_ENUM, loc, "provenance '${trial.provenance}' not accepted", "use an accepted provenance value")
            }
            if (trial.publicationPermission !in Values.PUBLICATION_VALUES) {
                issues += critical(Codes.INVALID_ENUM, loc, "publication_permission '${trial.publicationPermission}' not accepted", "use an accepted publication value")
            }
            if (trial.publicationPermission == "PERMITTED" && trial.consent != "CONSENTED") {
                issues += critical(Codes.CONSENT_CONTRADICTION, loc, "publication_permission=PERMITTED contradicts consent=${trial.consent}", "align consent and publication permission")
            }
            if (trial.consent == "CONSENTED" && trial.provenance == "SYNTHETIC") {
                issues += warning(Codes.CONSENT_CONTRADICTION, loc, "SYNTHETIC provenance carries a consent declaration", "confirm the provenance/consent pair")
            }
        }
    }

    private fun checkCoverage(dataset: Dataset, issues: MutableList<ValidationIssue>) {
        val scenes = dataset.trials.map { it.scene }.toSet()
        for (criticalScene in dataset.props.criticalScenes) {
            if (criticalScene !in scenes) {
                issues += critical(Codes.SCENE_COVERAGE, "critical_scenes", "declared critical scene '$criticalScene' has no trials", "capture the declared critical scene or remove it")
            }
        }
        val declaredArms = dataset.props.declaredArms
        for (arm in declaredArms) {
            if (dataset.trials.none { it.arm == arm }) {
                issues += critical(Codes.ARM_COVERAGE, "declared_arms", "declared arm '$arm' has no trials", "capture trials for every declared arm")
            }
        }
        val grains = dataset.trials.map { Pair(it.scene, it.condition) }.distinct()
        for (arm in declaredArms) {
            for ((scene, condition) in grains) {
                val successful = dataset.successfulTrials()
                    .filter { it.arm == arm && it.scene == scene && it.condition == condition }
                if (successful.size < dataset.props.requiredRepetitions) {
                    issues += critical(
                        Codes.REPETITION_COVERAGE,
                        "coverage",
                        "arm '$arm' scene '$scene' condition '$condition' has ${successful.size} SUCCESS trial(s), required ${dataset.props.requiredRepetitions}",
                        "capture the predeclared repetitions",
                    )
                }
                val requiredReps = (1..dataset.props.requiredRepetitions).toSet()
                val actualReps = successful.map { it.repetition }.toSet()
                val missingReps = requiredReps - actualReps
                if (missingReps.isNotEmpty()) {
                    issues += critical(
                        Codes.REPETITION_COVERAGE,
                        "coverage",
                        "arm '$arm' scene '$scene' condition '$condition' missing repetition values ${missingReps.sorted().joinToString(",")}",
                        "record every predeclared repetition number",
                    )
                }
            }
        }
    }

    private fun checkComparisons(dataset: Dataset, issues: MutableList<ValidationIssue>) {
        if (dataset.comparisons.isEmpty()) {
            issues += critical(
                Codes.NO_COMPARISON_PAIRS,
                "comparison plan",
                "comparison-plan.csv contains no data rows",
                "declare at least one comparison",
            )
            return
        }
        val seenIds = mutableSetOf<String>()
        val successful = dataset.successfulTrials()
        val grains = successful.map { Triple(it.scene, it.condition, it.repetition) }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
        for (comparison in dataset.comparisons) {
            val loc = "comparison ${comparison.comparisonId}"
            if (!seenIds.add(comparison.comparisonId)) {
                issues += critical(Codes.DUPLICATE_COMPARISON_ID, loc, "comparison_id repeated", "make comparison_id unique")
            }
            if (comparison.armA !in dataset.props.declaredArms) {
                issues += critical(Codes.UNKNOWN_ARM, loc, "arm_a '${comparison.armA}' not declared", "use a declared arm")
            }
            if (comparison.armB !in dataset.props.declaredArms) {
                issues += critical(Codes.UNKNOWN_ARM, loc, "arm_b '${comparison.armB}' not declared", "use a declared arm")
            }
            if (comparison.armA == comparison.armB) {
                if (comparison.purpose == ComparisonPurpose.BLINDED_AA) {
                    issues += critical(
                        Codes.AA_SAME_ARM, loc,
                        "BLINDED_AA requires two distinct declared arm identifiers (arm_a == arm_b == '${comparison.armA}')",
                        "declare one distinct blinded pass arm per side, e.g. baseline_pass_a and baseline_pass_b",
                    )
                } else {
                    issues += critical(
                        Codes.INCONSISTENT_ROLE, loc,
                        "comparison arm_a must differ from arm_b",
                        "declare distinct arms for every comparison",
                    )
                }
            } else {
                // One unified grain resolver for every comparison purpose: exactly one
                // eligible trial per arm at identical (scene, condition, repetition),
                // never pairing a trial with itself and never crossing repetitions.
                for ((scene, condition, repetition) in grains) {
                    val aEligible = successful.filter {
                        it.scene == scene && it.condition == condition && it.repetition == repetition && it.arm == comparison.armA
                    }
                    val bEligible = successful.filter {
                        it.scene == scene && it.condition == condition && it.repetition == repetition && it.arm == comparison.armB
                    }
                    if (aEligible.size != 1 || bEligible.size != 1) {
                        issues += critical(
                            Codes.COMPARISON_INCOMPLETE, loc,
                            "comparison needs exactly one eligible trial per arm for scene='$scene' condition='$condition' repetition=$repetition but found a=${aEligible.size} b=${bEligible.size}",
                            "capture the missing repetitions or fix the plan",
                        )
                    }
                }
            }
        }
        checkCandidateRoles(dataset, issues)
    }

    private fun checkCandidateRoles(dataset: Dataset, issues: MutableList<ValidationIssue>) {
        val candidateRows = dataset.comparisons.filter {
            it.purpose == ComparisonPurpose.CANDIDATE_VS_BASELINE || it.purpose == ComparisonPurpose.CANDIDATE_VS_STOCK
        }
        if (candidateRows.isEmpty()) return
        val loc = "comparison roles"
        val candidateArms = candidateRows.map { it.armA }.distinct()
        if (candidateArms.size > 1) {
            issues += critical(
                Codes.INCONSISTENT_ROLE, loc,
                "candidate comparisons use inconsistent candidate arms: ${candidateArms.sorted().joinToString(",")}",
                "use one consistent candidate arm in arm_a of every candidate comparison",
            )
        }
        val baselineArms = candidateRows.filter { it.purpose == ComparisonPurpose.CANDIDATE_VS_BASELINE }.map { it.armB }.distinct()
        if (baselineArms.size > 1) {
            issues += critical(
                Codes.INCONSISTENT_ROLE, loc,
                "CANDIDATE_VS_BASELINE rows use inconsistent locked-baseline arms: ${baselineArms.sorted().joinToString(",")}",
                "use one consistent locked-baseline arm in arm_b of every CANDIDATE_VS_BASELINE row",
            )
        }
        val stockArms = candidateRows.filter { it.purpose == ComparisonPurpose.CANDIDATE_VS_STOCK }.map { it.armB }.distinct()
        if (stockArms.size > 1) {
            issues += critical(
                Codes.INCONSISTENT_ROLE, loc,
                "CANDIDATE_VS_STOCK rows use inconsistent stock arms: ${stockArms.sorted().joinToString(",")}",
                "use one consistent stock arm in arm_b of every CANDIDATE_VS_STOCK row",
            )
        }
        if (dataset.props.kind == DatasetKind.CANDIDATE) {
            val candidateId = dataset.props.candidateIdentifier
            val baselineId = dataset.props.baselineIdentifier
            for (candidate in candidateRows) {
                if (candidate.armA != candidateId) {
                    issues += critical(
                        Codes.INCONSISTENT_ROLE, "comparison ${candidate.comparisonId}",
                        "candidate comparison arm_a '${candidate.armA}' must equal the declared candidate_identifier '$candidateId'",
                        "align the candidate arm with candidate_identifier",
                    )
                }
            }
            for (row in candidateRows.filter { it.purpose == ComparisonPurpose.CANDIDATE_VS_BASELINE }) {
                if (row.armB != baselineId) {
                    issues += critical(
                        Codes.INCONSISTENT_ROLE, "comparison ${row.comparisonId}",
                        "CANDIDATE_VS_BASELINE arm_b '${row.armB}' must equal the declared baseline_identifier '$baselineId'",
                        "align the locked-baseline arm with baseline_identifier",
                    )
                }
            }
        }
    }

    private fun checkCrops(dataset: Dataset, issues: MutableList<ValidationIssue>) {
        val seenIds = mutableSetOf<String>()
        for (crop in dataset.crops) {
            val loc = "crop ${crop.cropId}"
            if (!seenIds.add(crop.cropId)) {
                issues += critical(Codes.DUPLICATE_CROP_ID, loc, "crop_id repeated", "make crop_id unique")
            }
            if (crop.cropPurpose !in CropPurpose.values().map { it.value }) {
                issues += critical(Codes.INVALID_ENUM, loc, "crop_purpose '${crop.cropPurpose}' not accepted", "use an accepted crop purpose")
            }
            val trial = dataset.trialById(crop.trialId)
            if (trial == null) {
                issues += critical(Codes.CROP_ORPHAN, loc, "trial '${crop.trialId}' does not exist", "link the crop to an existing trial")
            } else if (trial.outcome != TrialOutcome.SUCCESS) {
                issues += critical(Codes.CROP_ORPHAN, loc, "trial '${crop.trialId}' is not SUCCESS", "crops require a successful trial")
            }
            val xs = listOf(crop.x0, crop.x1)
            val ys = listOf(crop.y0, crop.y1)
            val notFinite = xs.any { !it.isFinite() } || ys.any { !it.isFinite() }
            if (notFinite || crop.x0 < 0.0 || crop.y0 < 0.0 || crop.x1 > 1.0 || crop.y1 > 1.0 || crop.x0 >= crop.x1 || crop.y0 >= crop.y1) {
                issues += critical(Codes.CROP_INVALID_RECT, loc, "normalized rectangle must be finite, within [0,1], and positive area", "fix the crop rectangle")
            }
        }
    }

    private fun checkFileEvidence(dataset: Dataset, issues: MutableList<ValidationIssue>) {
        val originalHashes = mutableMapOf<String, String>()
        val reviewHashes = mutableMapOf<String, String>()
        for (trial in dataset.trials) {
            if (trial.outcome != TrialOutcome.SUCCESS) continue
            val loc = "trial ${trial.trialId}"
            if (trial.originalPath != null) {
                val path = try {
                    PathSecurity.resolve(dataset.root, trial.originalPath, "original of $loc")
                } catch (e: ToolError) {
                    issues += critical(e.code, loc, "original path: ${e.message}", "record a contained relative path")
                    null
                }
                if (path != null) {
                    if (!Files.isRegularFile(path)) {
                        issues += critical(Codes.PATH_MISSING, loc, "original file missing: '${trial.originalPath}'", "restore the original or fix the path")
                    } else {
                        val hash = Hashes.sha256File(path)
                        if (hash != trial.originalHashSha256) {
                            issues += critical(Codes.HASH_MISMATCH, loc, "original SHA-256 does not match recorded value", "recompute the recorded hash")
                        }
                        val size = Files.size(path)
                        if (size != trial.originalByteSize) {
                            issues += critical(Codes.BYTE_SIZE_MISMATCH, loc, "original byte size $size != recorded ${trial.originalByteSize}", "recompute the recorded byte size")
                        }
                        val prior = originalHashes.putIfAbsent(hash, trial.trialId)
                        if (prior != null && !dataset.props.allowSharedOriginals) {
                            issues += critical(
                                Codes.DUPLICATE_ORIGINAL, loc,
                                "original hash equals trial '$prior' (same captured file used for two trials)",
                                "declare allow_shared_originals=true or use distinct originals",
                            )
                        }
                    }
                }
            }
            if (trial.reviewSourcePath != null) {
                val path = try {
                    PathSecurity.resolve(dataset.root, trial.reviewSourcePath, "review source of $loc")
                } catch (e: ToolError) {
                    issues += critical(e.code, loc, "review-source path: ${e.message}", "record a contained relative path")
                    null
                }
                if (path != null) {
                    if (!Files.isRegularFile(path)) {
                        issues += critical(Codes.PATH_MISSING, loc, "review source missing: '${trial.reviewSourcePath}'", "restore the review source or fix the path")
                    } else {
                        val hash = Hashes.sha256File(path)
                        if (hash != trial.reviewSourceHashSha256) {
                            issues += critical(Codes.HASH_MISMATCH, loc, "review-source SHA-256 does not match recorded value", "recompute the recorded hash")
                        }
                        val size = Files.size(path)
                        if (size != trial.reviewSourceByteSize) {
                            issues += critical(Codes.BYTE_SIZE_MISMATCH, loc, "review-source byte size $size != recorded ${trial.reviewSourceByteSize}", "recompute the recorded byte size")
                        }
                        val prior = reviewHashes.putIfAbsent(hash, trial.trialId)
                        if (prior != null && !dataset.props.allowSharedOriginals) {
                            issues += warning(
                                Codes.DUPLICATE_ORIGINAL, loc,
                                "review-source hash equals trial '$prior'",
                                "confirm this is the declared same-source use case",
                            )
                        }
                    }
                    try {
                        ReviewSource.readReviewSource(path)
                    } catch (e: ToolError) {
                        issues += critical(e.code, loc, "review source: ${e.message}", "provide a display-oriented, decodable review source (lossless PNG recommended)")
                    }
                }
            }
        }
    }

    private fun writeReports(outDir: Path, result: ValidationResult) {
        try {
            Files.createDirectories(outDir)
        } catch (e: Exception) {
            throw ToolError(Codes.FILE_WRITE, "cannot create output directory '$outDir': ${e.message}", ToolExitCode.IO)
        }
        val sorted = result.issues.sortedWith(
            compareBy({ it.severity.ordinal }, { it.code }, { it.location }),
        )
        val summaryHeader = listOf("severity", "code", "location", "evidence", "remediation")
        val summaryRows = sorted.map {
            listOf(it.severity.name, it.code, it.location, it.evidence, it.remediation)
        }
        Csv.write(outDir.resolve("validation-summary.csv"), summaryHeader, summaryRows)

        val sb = StringBuilder()
        sb.append("PROSHOT IMAGE-QUALITY VALIDATION REPORT\n")
        sb.append("dataset_root=").append(result.dataset.root.toAbsolutePath().normalize()).append('\n')
        sb.append("dataset_kind=").append(result.dataset.props.kind.value).append('\n')
        sb.append("schema_version=").append(result.dataset.props.schemaVersion).append('\n')
        sb.append("contract_version=").append(result.dataset.props.contractVersion).append('\n')
        sb.append("result=").append(if (result.ok) "ELIGIBLE" else "NOT_ELIGIBLE").append('\n')
        sb.append("critical_findings=").append(result.critical().size).append('\n')
        sb.append("warnings=").append(result.warnings().size).append('\n')
        sb.append("findings:\n")
        for (issue in sorted) {
            sb.append("- ").append(issue.severity.name).append(' ')
                .append(issue.code).append(' ').append(issue.location).append(": ")
                .append(issue.evidence).append(" | remediation: ").append(issue.remediation).append('\n')
        }
        try {
            Files.write(outDir.resolve("validation-report.txt"), sb.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ToolError(Codes.FILE_WRITE, "cannot write validation report: ${e.message}", ToolExitCode.IO)
        }
    }

    private fun critical(code: String, location: String, evidence: String, remediation: String) =
        ValidationIssue(Severity.CRITICAL, code, location, evidence, remediation)

    private fun warning(code: String, location: String, evidence: String, remediation: String) =
        ValidationIssue(Severity.WARNING, code, location, evidence, remediation)
}
