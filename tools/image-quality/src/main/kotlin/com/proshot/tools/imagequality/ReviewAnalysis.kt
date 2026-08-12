package com.proshot.tools.imagequality

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal sealed class ReportStatus {
    data object PASS : ReportStatus()
    data object FAIL : ReportStatus()
    data object INCONCLUSIVE : ReportStatus()
    data object INCONCLUSIVE_CALIBRATION : ReportStatus()
    data object INCONCLUSIVE_ZERO_DECISIVE : ReportStatus()

    fun code(): String = when (this) {
        PASS -> "PASS"
        FAIL -> "FAIL"
        INCONCLUSIVE -> "INCONCLUSIVE"
        INCONCLUSIVE_CALIBRATION -> "INCONCLUSIVE / CALIBRATION"
        INCONCLUSIVE_ZERO_DECISIVE -> "INCONCLUSIVE_ZERO_DECISIVE"
    }
}

/** Wilson score interval for a 95% two-sided confidence band on a binary rate. */
internal object Wilson {
    const val Z_95: Double = 1.959963984540054

    fun interval(k: Int, n: Int, z: Double = Z_95): Pair<Double, Double> {
        require(n > 0)
        val p = k.toDouble() / n
        val denom = 1.0 + z * z / n
        val center = (p + z * z / (2.0 * n)) / denom
        val margin = z * sqrt(p * (1.0 - p) / n + z * z / (4.0 * n * n)) / denom
        return max(0.0, center - margin) to min(1.0, center + margin)
    }
}

internal object Median {
    fun of(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}

/**
 * One production validator shared by `seal-review` and `analyze` for the
 * immutable review package, its manifest, the private key, and the seal.
 *
 * The immutable package consists only of `review.html`, `review.js`,
 * `review.css`, `manifest.properties`, and the manifest-declared files under
 * `assets/`. Reviewer responses and seal files must remain outside the
 * package; any file that is not exactly one of the declared immutable files
 * fails package verification.
 */
internal object PackageValidator {

    private val MANIFEST_FIXED: List<String> = listOf(
        "manifest_schema_version", "package_id", "response_schema_version", "response_schema_sha256",
        "plan.sha256", "key.sha256", "pair.count", "pair.order",
        "page.review.html.sha256", "script.review.js.sha256", "style.review.css.sha256",
    )
    private val SEAL_FIXED: List<String> = listOf(
        "seal_schema_version", "package_id", "package_manifest_hash_sha256", "response_file",
        "response_file_hash_sha256", "reviewer", "reviewer_category", "conflict_declaration",
        "seal_timestamp_utc",
    )
    private val KEY_FIXED: List<String> = listOf("key_schema_version", "package_id", "seed", "dataset_hash_sha256")
    private val PAIR_FIELDS: List<String> = listOf(
        "comparison_id", "purpose", "scene", "condition", "repetition",
        "left.trial_id", "left.arm", "right.trial_id", "right.arm",
    )
    private val ASSET_NAME: Regex = Regex("^assets/[A-Za-z0-9]+_[lr]_(whole|crop_[A-Za-z0-9]+)\\.png$")

    fun requireHex64(value: String, label: String, code: String = Codes.MANIFEST_INVALID): String {
        if (value.length != 64 || !value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            throw ToolError(code, "$label must be a 64-character hexadecimal hash")
        }
        return value
    }

    fun pairOrder(manifest: PropMap): List<String> =
        manifest.require("pair.order").split(',').map { it.trim() }

    /**
     * Reads the manifest, validates its schema, and verifies the complete
     * physical package (required files, hashes, no extra files, no links, no
     * unexpected directories, no real-path escape).
     */
    fun readAndVerifyManifest(packageDir: Path): PropMap {
        val manifestPath = packageDir.resolve("manifest.properties")
        if (!Files.isRegularFile(manifestPath)) {
            throw ToolError(Codes.MANIFEST_MISSING, "no manifest.properties in package: '$packageDir'")
        }
        val manifest = StrictProperties.read(manifestPath)
        validateManifestSchema(manifest)
        verifyPhysicalPackage(packageDir, manifest)
        return manifest
    }

    fun validateManifestSchema(manifest: PropMap) {
        if (manifest.require("manifest_schema_version") != TOOL_SCHEMA_VERSION) {
            throw ToolError(Codes.MANIFEST_INVALID, "unsupported manifest schema version")
        }
        val unknown = manifest.entries.keys.filter { it !in MANIFEST_FIXED && !it.startsWith("asset.") }
        if (unknown.isNotEmpty()) {
            throw ToolError(Codes.MANIFEST_INVALID, "unknown manifest keys: ${unknown.sorted().joinToString(",")}")
        }
        for (key in MANIFEST_FIXED) {
            if (manifest.get(key).isNullOrBlank()) {
                throw ToolError(Codes.MANIFEST_INVALID, "manifest is missing mandatory property '$key'")
            }
        }
        for (key in manifest.entries.keys.filter { it.startsWith("asset.") }) {
            if (!key.endsWith(".sha256")) {
                throw ToolError(Codes.MANIFEST_ASSET_KEY, "asset manifest key must end with '.sha256': '$key'")
            }
            val rel = key.removePrefix("asset.").removeSuffix(".sha256")
            if (!ASSET_NAME.matches(rel)) {
                throw ToolError(Codes.MANIFEST_ASSET_PATH, "malformed asset name '$rel'")
            }
            requireHex64(manifest.require(key), "asset $rel")
        }
        requireHex64(manifest.require("plan.sha256"), "plan.sha256")
        requireHex64(manifest.require("key.sha256"), "key.sha256")
        requireHex64(manifest.require("response_schema_sha256"), "response_schema_sha256")
        requireHex64(manifest.require("page.review.html.sha256"), "page.review.html.sha256")
        requireHex64(manifest.require("script.review.js.sha256"), "script.review.js.sha256")
        requireHex64(manifest.require("style.review.css.sha256"), "style.review.css.sha256")
        if (manifest.require("response_schema_version") != ResponseSchema.VERSION) {
            throw ToolError(Codes.SCHEMA_MISMATCH, "package response-schema version does not match the parser contract")
        }
        if (manifest.require("response_schema_sha256") != ResponseSchema.canonicalHash()) {
            throw ToolError(Codes.SCHEMA_MISMATCH, "package response-schema hash does not match the parser contract")
        }
        val pairCount = manifest.require("pair.count").toIntOrNull()
        if (pairCount == null || pairCount <= 0) {
            throw ToolError(Codes.MANIFEST_PAIR_COUNT, "manifest pair.count must be a positive integer")
        }
        val order = pairOrder(manifest)
        if (order.any { it.isBlank() }) {
            throw ToolError(Codes.MANIFEST_PAIR_ORDER, "manifest pair.order contains a blank pair id")
        }
        if (order.size != pairCount) {
            throw ToolError(
                Codes.MANIFEST_PAIR_COUNT,
                "manifest pair.count=$pairCount does not match pair.order entries ${order.size}",
            )
        }
        if (order.size != order.toSet().size) {
            throw ToolError(Codes.MANIFEST_PAIR_ORDER, "manifest pair.order contains duplicate pair ids")
        }
    }

    private fun verifyPhysicalPackage(packageDir: Path, manifest: PropMap) {
        val realPkg = try {
            packageDir.toRealPath()
        } catch (e: IOException) {
            throw ToolError(Codes.PATH_IO, "cannot resolve real package path '$packageDir': ${e.message}", ToolExitCode.IO)
        }
        val declaredFiles = mutableSetOf<String>()
        declaredFiles += listOf("review.html", "review.js", "review.css", "manifest.properties")
        for (key in manifest.entries.keys.filter { it.startsWith("asset.") }) {
            declaredFiles += key.removePrefix("asset.").removeSuffix(".sha256")
        }
        val seen = mutableSetOf<String>()
        try {
            Files.walk(packageDir).use { stream ->
                for (p in stream) {
                    val rel = packageDir.relativize(p).toString().replace('\\', '/')
                    if (Files.isSymbolicLink(p)) {
                        throw ToolError(Codes.MANIFEST_SYMLINK, "symbolic link inside package: '$rel'")
                    }
                    val real = try {
                        p.toRealPath()
                    } catch (e: IOException) {
                        throw ToolError(Codes.MANIFEST_SYMLINK, "cannot resolve real path inside package: '$rel'")
                    }
                    if (!real.startsWith(realPkg)) {
                        throw ToolError(Codes.MANIFEST_SYMLINK, "resolved path escapes the package: '$rel'")
                    }
                    if (Files.isDirectory(p)) {
                        if (rel.isNotEmpty() && rel != "assets") {
                            throw ToolError(Codes.MANIFEST_DIRECTORY, "unexpected directory inside package: '$rel'")
                        }
                        continue
                    }
                    if (!Files.isRegularFile(p)) {
                        throw ToolError(Codes.MANIFEST_EXTRA_FILE, "unexpected non-regular file inside package: '$rel'")
                    }
                    if (rel !in declaredFiles) {
                        throw ToolError(
                            Codes.MANIFEST_EXTRA_FILE,
                            "unmanifested file inside the immutable package: '$rel'. Reviewer responses and seal files must stay outside the package folder.",
                        )
                    }
                    seen += rel
                }
            }
        } catch (e: ToolError) {
            throw e
        } catch (e: IOException) {
            throw ToolError(Codes.FILE_READ, "cannot enumerate package '$packageDir': ${e.message}", ToolExitCode.IO)
        }
        val missing = declaredFiles - seen
        if (missing.isNotEmpty()) {
            throw ToolError(Codes.MANIFEST_MISSING_FILE, "package file(s) missing: ${missing.sorted().joinToString(",")}")
        }
        checkPackageHash(packageDir.resolve("review.html"), manifest.require("page.review.html.sha256"), "review.html")
        checkPackageHash(packageDir.resolve("review.js"), manifest.require("script.review.js.sha256"), "review.js")
        checkPackageHash(packageDir.resolve("review.css"), manifest.require("style.review.css.sha256"), "review.css")
        for (key in manifest.entries.keys.filter { it.startsWith("asset.") }) {
            val rel = key.removePrefix("asset.").removeSuffix(".sha256")
            checkPackageHash(packageDir.resolve(rel), manifest.require(key), "asset '$rel'")
        }
    }

    private fun checkPackageHash(path: Path, expectedHash: String, label: String) {
        if (!Files.isRegularFile(path)) {
            throw ToolError(Codes.MANIFEST_MISSING_FILE, "package $label missing")
        }
        if (Hashes.sha256File(path) != expectedHash) {
            throw ToolError(Codes.PACKAGE_TAMPERED, "package $label hash mismatch")
        }
    }

    /**
     * Strict private-key validation. The key must contain exactly the fixed
     * properties plus the properties derived from the manifest pair/asset
     * sets, its pair ids must equal the manifest pair ids exactly, every pair
     * mapping field must be present and safe to parse, left and right trial
     * ids must differ, and every asset-to-trial mapping must reference the
     * mapped pair trials. The key hash recorded in the manifest is verified
     * before any mapping is used.
     */
    fun validateKey(keyPath: Path, key: PropMap, manifest: PropMap): Map<String, ReviewAnalysis.PairInfo> {
        if (key.require("key_schema_version") != TOOL_SCHEMA_VERSION) {
            throw ToolError(Codes.KEY_INVALID, "unsupported key schema version")
        }
        if (key.require("package_id") != manifest.require("package_id")) {
            throw ToolError(Codes.KEY_MISMATCH, "key does not belong to this package")
        }
        if (manifest.require("key.sha256") != Hashes.sha256File(keyPath)) {
            throw ToolError(Codes.KEY_MISMATCH, "key file does not match the package manifest")
        }
        if (key.get("seed").isNullOrBlank()) {
            throw ToolError(Codes.KEY_INVALID, "key is missing the seed")
        }
        requireHex64(key.require("dataset_hash_sha256"), "key dataset_hash_sha256", Codes.KEY_INVALID)
        val manifestPairs = pairOrder(manifest).toSet()
        val keyPairIds = key.entries.keys
            .filter { it.startsWith("pair.") && it.endsWith(".comparison_id") }
            .map { it.removePrefix("pair.").removeSuffix(".comparison_id") }
        if (keyPairIds.size != keyPairIds.toSet().size) {
            throw ToolError(Codes.KEY_INVALID, "key contains duplicate pair ids")
        }
        if (keyPairIds.toSet() != manifestPairs) {
            throw ToolError(
                Codes.KEY_PAIR_SET_MISMATCH,
                "key pair set [${keyPairIds.sorted().joinToString(",")}] does not equal the manifest pair set [${manifestPairs.sorted().joinToString(",")}]",
            )
        }
        val out = LinkedHashMap<String, ReviewAnalysis.PairInfo>()
        for (pairId in keyPairIds.sorted()) {
            val prefix = "pair.$pairId."
            val got = key.entries.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
            if (got != PAIR_FIELDS.toSet()) {
                val missing = PAIR_FIELDS.filter { it !in got }
                val extra = got.filter { it !in PAIR_FIELDS }
                throw ToolError(
                    Codes.KEY_INVALID,
                    "key pair $pairId is missing fields [${missing.joinToString(",")}] or has unknown fields [${extra.joinToString(",")}]",
                )
            }
            val repetition = key.require("${prefix}repetition").toIntOrNull()
            if (repetition == null || repetition <= 0) {
                throw ToolError(Codes.KEY_INVALID, "key pair $pairId has a malformed repetition value")
            }
            val purposeValue = key.require("${prefix}purpose")
            val purpose = ComparisonPurpose.values().firstOrNull { it.value == purposeValue }
                ?: throw ToolError(Codes.KEY_INVALID, "unknown purpose '$purposeValue' in key for pair $pairId")
            val leftTrialId = key.require("${prefix}left.trial_id")
            val rightTrialId = key.require("${prefix}right.trial_id")
            if (leftTrialId.isBlank() || rightTrialId.isBlank()) {
                throw ToolError(Codes.KEY_INVALID, "key pair $pairId has blank trial ids")
            }
            if (leftTrialId == rightTrialId) {
                throw ToolError(Codes.KEY_TRIAL_SELF, "key pair $pairId maps the same trial to left and right")
            }
            if (key.require("${prefix}left.arm").isBlank() || key.require("${prefix}right.arm").isBlank()) {
                throw ToolError(Codes.KEY_INVALID, "key pair $pairId has blank arm names")
            }
            out[pairId] = ReviewAnalysis.PairInfo(
                comparisonId = key.require("${prefix}comparison_id"),
                purpose = purpose,
                scene = key.require("${prefix}scene"),
                condition = key.require("${prefix}condition"),
                repetition = repetition,
                leftTrialId = leftTrialId,
                leftArm = key.require("${prefix}left.arm"),
                rightTrialId = rightTrialId,
                rightArm = key.require("${prefix}right.arm"),
            )
        }
        val allowed = (KEY_FIXED + keyPairIds.flatMap { pid -> PAIR_FIELDS.map { "pair.$pid.$it" } }).toMutableSet()
        for (manifestKey in manifest.entries.keys.filter { it.startsWith("asset.") }) {
            val rel = manifestKey.removePrefix("asset.").removeSuffix(".sha256")
            val mappingKey = "asset.$rel.trial_id"
            allowed += mappingKey
            val mapped = key.get(mappingKey)
                ?: throw ToolError(Codes.KEY_ASSET_MAPPING, "key is missing the trial mapping for asset '$rel'")
            val expected = expectedTrialForAsset(rel, out)
                ?: throw ToolError(Codes.KEY_ASSET_MAPPING, "asset '$rel' cannot be mapped to a pair trial")
            if (mapped != expected) {
                throw ToolError(
                    Codes.KEY_ASSET_MAPPING,
                    "asset '$rel' maps to trial '$mapped' but must map to '$expected'",
                )
            }
        }
        val unknown = key.entries.keys.filter { it !in allowed }
        if (unknown.isNotEmpty()) {
            throw ToolError(Codes.KEY_INVALID, "key contains unknown fields: ${unknown.sorted().joinToString(",")}")
        }
        return out
    }

    private fun expectedTrialForAsset(rel: String, mapping: Map<String, ReviewAnalysis.PairInfo>): String? {
        val name = rel.removePrefix("assets/")
        val m = Regex("^(.+)_([lr])_(whole|crop_.+)\\.png$").matchEntire(name) ?: return null
        val info = mapping[m.groupValues[1]] ?: return null
        return if (m.groupValues[2] == "l") info.leftTrialId else info.rightTrialId
    }

    /** Strict seal validation: exactly the canonical seal properties with valid custody values and UTC timestamp. */
    fun validateSeal(seal: PropMap) {
        val unknown = seal.entries.keys.filter { it !in SEAL_FIXED }
        if (unknown.isNotEmpty()) {
            throw ToolError(Codes.SEAL_INVALID, "seal contains unknown fields: ${unknown.sorted().joinToString(",")}")
        }
        for (key in SEAL_FIXED) {
            if (seal.get(key).isNullOrBlank()) {
                throw ToolError(Codes.SEAL_INVALID, "seal is missing mandatory property '$key'")
            }
        }
        if (seal.require("seal_schema_version") != TOOL_SCHEMA_VERSION) {
            throw ToolError(Codes.SEAL_INVALID, "unsupported seal schema version")
        }
        if (seal.require("conflict_declaration") !in setOf("NONE", "DECLARED")) {
            throw ToolError(Codes.SEAL_BAD_CUSTODY, "conflict declaration must be NONE or DECLARED")
        }
        if (seal.require("reviewer").isBlank() || seal.require("reviewer_category").isBlank()) {
            throw ToolError(Codes.SEAL_BAD_CUSTODY, "reviewer identity and category must not be blank")
        }
        UtcClock.canonical(seal.require("seal_timestamp_utc"))
        requireHex64(seal.require("package_manifest_hash_sha256"), "seal package_manifest_hash_sha256", Codes.SEAL_INVALID)
        requireHex64(seal.require("response_file_hash_sha256"), "seal response_file_hash_sha256", Codes.SEAL_INVALID)
    }
}

/**
 * The `seal-review` and `analyze` commands.
 *
 * `seal-review` validates the response file against the canonical schema and
 * the fully verified immutable package, hashes manifest and response, and
 * records the reviewer custody declaration and UTC seal timestamp without
 * reading the key.
 *
 * `analyze` verifies every recorded hash (response, package, assets, plan,
 * manifest, key, dataset) before revealing arm mappings, then computes
 * pair-level statistics and reports a truthful status.
 */
internal object ReviewAnalysis {

    /** Only CANDIDATE_VS_BASELINE pairs bind candidate acceptance; stock and contextual pairs never enter binding cells. */
    private val BINDING_PURPOSES: Set<ComparisonPurpose> = setOf(ComparisonPurpose.CANDIDATE_VS_BASELINE)

    internal data class ResponseRow(
        val packageId: String,
        val pairId: String,
        val choice: String,
        val reasonTags: List<String>,
        val defect: String,
        val defectSide: String,
        val note: String,
    )

    internal data class SealRecord(
        val path: Path,
        val reviewer: String,
        val reviewerCategory: String,
        val conflictDeclaration: String,
        val sealTimestamp: String,
        val manifestHash: String,
        val responsePath: Path,
        val responseHash: String,
    )

    internal data class PairInfo(
        val comparisonId: String,
        val purpose: ComparisonPurpose,
        val scene: String,
        val condition: String,
        val repetition: Int,
        val leftTrialId: String,
        val leftArm: String,
        val rightTrialId: String,
        val rightArm: String,
    )

    internal data class PairVerdict(
        val pairId: String,
        val purpose: ComparisonPurpose,
        val scene: String,
        val referenceArm: String,
        val reviewerChoices: List<String>,
        val outcome: String,
        val preferredArm: String?,
        val win: Boolean?,
    )

    internal data class PreferenceCell(
        val wins: Int,
        val losses: Int,
        val ties: Int,
        val decisive: Int,
        val rate: Double?,
        val ciLow: Double?,
        val ciHigh: Double?,
        val splitScore: Double,
        val zeroDecisive: Boolean,
    )

    /** Per-reviewer, per-trial critical-defect evidence (one boolean per unique reviewer and trial). */
    internal data class TrialVote(
        val flagged: List<String>,
        val unflagged: List<String>,
    )

    internal enum class TrialCriticalStatus { CRITICAL, NONCRITICAL, DISPUTED }

    /** Per-arm capture statistics over non-excluded trials. */
    internal data class ArmMetrics(
        val arm: String,
        val eligibleTrials: Int,
        val successTrials: Int,
        val failedTrials: Int,
        val excludedTrials: Int,
        val reviewerCriticalTrials: Int,
        val disputedTrials: Int,
        val completionRate: Double?,
        val criticalFailureRate: Double?,
        val exclusionRate: Double?,
        val fallbackTrials: Int,
        val fallbackRate: Double?,
        val latencyMedianMs: Double?,
        val memoryMedianKb: Double?,
        val outputBytesMedian: Double?,
    )

    internal data class Metrics(
        val byArm: Map<String, ArmMetrics>,
        val byScene: Map<String, Map<String, ArmMetrics>>,
        val aaArmAPreferenceRate: Double?,
    )

    /**
     * Resolves the candidate and locked-baseline arms from the validated
     * CANDIDATE_VS_BASELINE plan.
     */
    internal object CandidateRoles {
        internal data class Roles(val candidateArm: String, val baselineArm: String)

        fun resolve(dataset: Dataset): Roles {
            val rows = dataset.comparisons.filter { it.purpose == ComparisonPurpose.CANDIDATE_VS_BASELINE }
            if (rows.isEmpty()) {
                throw ToolError(
                    Codes.INCONSISTENT_ROLE,
                    "candidate analysis requires at least one CANDIDATE_VS_BASELINE comparison",
                )
            }
            val candidateArms = rows.map { it.armA }.distinct()
            val baselineArms = rows.map { it.armB }.distinct()
            if (candidateArms.size != 1 || baselineArms.size != 1) {
                throw ToolError(Codes.INCONSISTENT_ROLE, "candidate and locked-baseline arms are not consistently declared")
            }
            return Roles(candidateArms.first(), baselineArms.first())
        }
    }

    /** Machine-decidable usefulness-rule grammar: `decisive_preference_rate>=X` or `decisive_preference_lower_bound>=X`, X finite in [0,1]. */
    internal object UsefulnessRule {
        enum class Kind { RATE, LOWER_BOUND }

        data class Rule(val kind: Kind, val bound: Double)

        private val PATTERN = Regex("^decisive_preference_(rate|lower_bound)>=([0-9]+(?:\\.[0-9]+)?)$")

        fun parse(text: String): Rule? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val m = PATTERN.matchEntire(trimmed) ?: return null
            val bound = m.groupValues[2].toDoubleOrNull() ?: return null
            if (!bound.isFinite() || bound < 0.0 || bound > 1.0) return null
            return Rule(if (m.groupValues[1] == "rate") Kind.RATE else Kind.LOWER_BOUND, bound)
        }
    }

    fun runSealReview(
        packageDir: Path,
        responsesPath: Path,
        outPath: Path,
        reviewer: String,
        category: String,
        conflict: String,
        timestamp: String?,
    ) {
        if (reviewer.isBlank()) throw ToolError(Codes.SEAL_BAD_CUSTODY, "reviewer identity must not be blank", ToolExitCode.USAGE)
        if (category.isBlank()) throw ToolError(Codes.SEAL_BAD_CUSTODY, "reviewer category must not be blank", ToolExitCode.USAGE)
        if (conflict !in setOf("NONE", "DECLARED")) {
            throw ToolError(Codes.SEAL_BAD_CUSTODY, "conflict declaration must be NONE or DECLARED", ToolExitCode.USAGE)
        }
        // The complete immutable package is verified before any seal is written.
        val manifest = PackageValidator.readAndVerifyManifest(packageDir)
        if (!Files.isRegularFile(responsesPath)) {
            throw ToolError(Codes.SEAL_RESPONSE_MISSING, "response file missing: '$responsesPath'")
        }
        val packageId = manifest.require("package_id")
        val pairSet = PackageValidator.pairOrder(manifest).toSet()
        parseResponses(responsesPath, packageId, pairSet)

        guardNewFile(outPath)
        val manifestHash = Hashes.sha256File(packageDir.resolve("manifest.properties"))
        val responseHash = Hashes.sha256File(responsesPath)
        val sealTime = UtcClock.canonical(timestamp ?: UtcClock.now())
        val entries = LinkedHashMap<String, String>()
        entries["seal_schema_version"] = TOOL_SCHEMA_VERSION
        entries["package_id"] = packageId
        entries["package_manifest_hash_sha256"] = manifestHash
        entries["response_file"] = responsesPath.toAbsolutePath().normalize().toString()
        entries["response_file_hash_sha256"] = responseHash
        entries["reviewer"] = reviewer
        entries["reviewer_category"] = category
        entries["conflict_declaration"] = conflict
        entries["seal_timestamp_utc"] = sealTime
        StrictProperties.write(outPath, entries)
    }

    fun runAnalyze(
        packageDir: Path,
        keyPath: Path,
        datasetRoot: Path,
        outDir: Path,
        sealPaths: List<Path>,
        thresholdPath: Path?,
        timestamp: String?,
    ) {
        guardOutDir(outDir)
        val dataset = DatasetModel.load(datasetRoot)
        val manifest = PackageValidator.readAndVerifyManifest(packageDir)
        val packageId = manifest.require("package_id")
        val pairSet = PackageValidator.pairOrder(manifest).toSet()

        val planPath = datasetRoot.resolve("comparison-plan.csv")
        if (!Files.isRegularFile(planPath)) {
            throw ToolError(Codes.COMPARISON_PLAN_MISSING, "missing comparison-plan.csv under dataset root")
        }
        if (Hashes.sha256File(planPath) != manifest.require("plan.sha256")) {
            throw ToolError(Codes.PLAN_TAMPERED, "comparison-plan.csv does not match the package manifest")
        }

        if (!Files.isRegularFile(keyPath)) {
            throw ToolError(Codes.KEY_MISSING, "missing key file: '$keyPath'")
        }
        val key = StrictProperties.read(keyPath)
        val pairMapping = PackageValidator.validateKey(keyPath, key, manifest)
        if (key.require("dataset_hash_sha256") != Hashes.sha256Directory(datasetRoot)) {
            throw ToolError(Codes.DATASET_MISMATCH, "key was created against a different dataset")
        }
        if (sealPaths.isEmpty()) {
            throw ToolError(Codes.SEAL_MISSING, "analyze requires at least one --seal")
        }
        val manifestPath = packageDir.resolve("manifest.properties")
        val seals = sealPaths.map { readSeal(it, packageId, manifestPath) }

        // Cross-seal integrity: one reviewer response may never be counted twice.
        val normalizedSealPaths = seals.map { it.path.toAbsolutePath().normalize() }
        if (normalizedSealPaths.size != normalizedSealPaths.toSet().size) {
            throw ToolError(Codes.SEAL_DUPLICATE_PATH, "the same seal file was passed more than once")
        }
        val responsePaths = seals.map { it.responsePath.toAbsolutePath().normalize() }
        if (responsePaths.size != responsePaths.toSet().size) {
            throw ToolError(Codes.SEAL_DUPLICATE_RESPONSE_HASH, "the same reviewer response file was sealed more than once")
        }
        val reviewers = seals.map { it.reviewer }
        if (reviewers.size != reviewers.toSet().size) {
            throw ToolError(Codes.SEAL_DUPLICATE_REVIEWER, "the same reviewer identity appears in more than one seal")
        }

        // All hashes verified. Unblinding begins here.
        val responsesByReviewer = seals.map { seal ->
            Pair(seal.reviewer, parseResponses(seal.responsePath, packageId, pairSet))
        }
        val verdicts = computeVerdicts(pairMapping, dataset, responsesByReviewer)
        val trialVotes = aggregateTrialDefects(pairMapping, responsesByReviewer)

        val report = Report()
        val generatedAt = UtcClock.canonical(timestamp ?: UtcClock.now())
        val metrics = computeMetrics(dataset, verdicts, trialVotes)
        val cells = computeCells(verdicts)
        val roles = if (dataset.props.kind == DatasetKind.CANDIDATE) CandidateRoles.resolve(dataset) else null

        writeAnalysisRows(report, dataset, packageId, manifestPath, keyPath, seals, verdicts, cells, metrics, roles, generatedAt, responsesByReviewer)

        val thresholdEval = when (dataset.props.kind) {
            DatasetKind.CALIBRATION -> evaluateCalibrationThreshold(dataset, seals, thresholdPath, cells)
            DatasetKind.CANDIDATE -> evaluateCandidateThreshold(dataset, seals, thresholdPath, cells, metrics, roles!!)
        }
        report.add("threshold", "bound_contract_version", thresholdEval.contractVersion)
        report.add("threshold", "bound_baseline_identifier", thresholdEval.baselineIdentifier)
        report.add("threshold", "lock_present", if (thresholdEval.lockPresent) "true" else "false")
        for ((i, reason) in thresholdEval.reasons.withIndex()) {
            report.add("threshold", "reason.${i + 1}", reason)
        }

        val status = when (dataset.props.kind) {
            DatasetKind.CALIBRATION -> ReportStatus.INCONCLUSIVE_CALIBRATION
            DatasetKind.CANDIDATE -> thresholdEval.status
        }
        report.add("status", "status", status.code())

        writeReports(outDir, report, status)
        if (dataset.props.kind == DatasetKind.CALIBRATION) {
            val templatePath = ThresholdLock.emitTemplate(
                outDir = outDir,
                dataset = dataset,
                manifestPath = packageDir.resolve("manifest.properties"),
                reportPath = outDir.resolve("analysis-report.csv"),
                seals = seals,
            )
            println("THRESHOLD_TEMPLATE=${templatePath.toAbsolutePath().normalize()}")
        }
        println("STATUS=${status.code()}")
        println("REPORT_DIR=${outDir.toAbsolutePath().normalize()}")
    }

    private fun readSeal(sealPath: Path, packageId: String, manifestPath: Path): SealRecord {
        if (!Files.isRegularFile(sealPath)) {
            throw ToolError(Codes.SEAL_MISSING, "seal file missing: '$sealPath'")
        }
        val seal = StrictProperties.read(sealPath)
        PackageValidator.validateSeal(seal)
        if (seal.require("package_id") != packageId) {
            throw ToolError(Codes.SEAL_INVALID, "seal belongs to a different package")
        }
        val manifestHash = seal.require("package_manifest_hash_sha256")
        val actualManifestHash = Hashes.sha256File(manifestPath)
        if (manifestHash != actualManifestHash) {
            throw ToolError(Codes.PACKAGE_TAMPERED, "package manifest does not match the seal")
        }
        val responseFile = seal.require("response_file")
        val responsePath = try {
            Path.of(responseFile)
        } catch (e: InvalidPathException) {
            throw ToolError(Codes.SEAL_INVALID, "seal response_file is not a valid path: '$responseFile'")
        }
        if (!Files.isRegularFile(responsePath)) {
            throw ToolError(Codes.RESPONSE_TAMPERED, "response file missing: '$responseFile'")
        }
        val responseHash = seal.require("response_file_hash_sha256")
        if (Hashes.sha256File(responsePath) != responseHash) {
            throw ToolError(Codes.RESPONSE_TAMPERED, "response file does not match the seal")
        }
        return SealRecord(
            path = sealPath,
            reviewer = seal.require("reviewer"),
            reviewerCategory = seal.require("reviewer_category"),
            conflictDeclaration = seal.require("conflict_declaration"),
            sealTimestamp = seal.require("seal_timestamp_utc"),
            manifestHash = manifestHash,
            responsePath = responsePath,
            responseHash = responseHash,
        )
    }

    private fun validateResponseHeader(header: List<String>) {
        if (header.size != header.toSet().size) {
            throw ToolError(Codes.CSV_DUPLICATE_COLUMN, "responses.csv: duplicate header column")
        }
        if (header.toSet() != ResponseSchema.COLUMNS.toSet()) {
            val unknown = header.filter { it !in ResponseSchema.COLUMNS }
            val missing = ResponseSchema.COLUMNS.filter { it !in header }
            throw ToolError(
                Codes.CSV_HEADER,
                "responses.csv: unknown columns=[${unknown.joinToString(",")}] missing columns=[${missing.joinToString(",")}]",
            )
        }
        if (header != ResponseSchema.COLUMNS) {
            throw ToolError(Codes.CSV_HEADER_ORDER, "responses.csv: column order does not match the canonical response schema")
        }
    }

    private fun parseResponses(path: Path, packageId: String, pairSet: Set<String>): Map<String, ResponseRow> {
        val table = Csv.read(path)
        validateResponseHeader(table.header)
        val out = LinkedHashMap<String, ResponseRow>()
        for (row in table.rows) {
            val rowObj = ResponseRow(
                packageId = row[0],
                pairId = row[1],
                choice = row[2],
                reasonTags = if (row[3].isBlank()) emptyList() else row[3].split('|').map { it.trim() }.filter { it.isNotEmpty() },
                defect = row[4],
                defectSide = row[5],
                note = row[6],
            )
            if (rowObj.packageId != packageId) {
                throw ToolError(Codes.SEAL_RESPONSE_BAD_PACKAGE, "response row for pair ${rowObj.pairId} references a different package")
            }
            if (rowObj.pairId !in pairSet) {
                throw ToolError(Codes.SEAL_RESPONSE_UNKNOWN_PAIR, "unknown pair id '${rowObj.pairId}' in responses.csv")
            }
            if (out.containsKey(rowObj.pairId)) {
                throw ToolError(Codes.SEAL_RESPONSE_DUPLICATE_PAIR, "duplicate response row for pair '${rowObj.pairId}'")
            }
            if (rowObj.choice !in ResponseSchema.CHOICES) {
                throw ToolError(Codes.SEAL_RESPONSE_CHOICE, "invalid choice '${rowObj.choice}' for pair ${rowObj.pairId}")
            }
            for (tag in rowObj.reasonTags) {
                if (tag !in ResponseSchema.REASON_TAGS) {
                    throw ToolError(Codes.SEAL_RESPONSE_TAG, "unknown reason tag '$tag' for pair ${rowObj.pairId}")
                }
            }
            if (rowObj.defect.isNotEmpty() && rowObj.defect !in ResponseSchema.DEFECT_TAGS) {
                throw ToolError(Codes.SEAL_RESPONSE_DEFECT, "unknown critical-defect tag '${rowObj.defect}' for pair ${rowObj.pairId}")
            }
            if (rowObj.defectSide.isNotEmpty() && rowObj.defectSide !in ResponseSchema.DEFECT_SIDES) {
                throw ToolError(Codes.SEAL_RESPONSE_DEFECT, "critical-defect side must be LEFT, RIGHT, or BOTH for pair ${rowObj.pairId}")
            }
            if (rowObj.defect.isEmpty() != rowObj.defectSide.isEmpty()) {
                throw ToolError(Codes.SEAL_RESPONSE_DEFECT, "critical-defect tag and side must be set together for pair ${rowObj.pairId}")
            }
            if (rowObj.defect == "OTHER_PREDECLARED" && rowObj.note.isBlank()) {
                throw ToolError(Codes.SEAL_RESPONSE_DEFECT, "OTHER_PREDECLARED requires a nonblank reviewer note for pair ${rowObj.pairId}")
            }
            out[rowObj.pairId] = rowObj
        }
        val missing = pairSet - out.keys
        if (missing.isNotEmpty()) {
            throw ToolError(Codes.SEAL_RESPONSE_INCOMPLETE, "no response for pair(s): ${missing.sorted().joinToString(",")}")
        }
        return out
    }

    /** Returns TIE for empty input or no decisive votes; otherwise returns the strict decisive-vote majority. */
    internal fun pairOutcome(choices: List<String>): String {
        val decisive = choices.filter { it == "LEFT" || it == "RIGHT" }
        if (decisive.isEmpty()) return "TIE"
        val left = decisive.count { it == "LEFT" }
        val right = decisive.count { it == "RIGHT" }
        return when {
            left > right -> "LEFT"
            right > left -> "RIGHT"
            else -> "TIE"
        }
    }

    internal fun computeVerdicts(
        pairMapping: Map<String, PairInfo>,
        dataset: Dataset,
        responsesByReviewer: List<Pair<String, Map<String, ResponseRow>>>,
    ): List<PairVerdict> {
        val comparisons = dataset.comparisons.associateBy { it.comparisonId }
        return pairMapping.keys.sorted().mapNotNull { pairId ->
            val info = pairMapping.getValue(pairId)
            val comparison = comparisons[info.comparisonId] ?: return@mapNotNull null
            val choices = responsesByReviewer.mapNotNull { (_, responses) -> responses[pairId]?.choice }
            if (choices.isEmpty()) return@mapNotNull null
            val outcome = pairOutcome(choices)
            val referenceArm = comparison.armA
            val preferredArm = when (outcome) {
                "LEFT" -> info.leftArm
                "RIGHT" -> info.rightArm
                else -> null
            }
            val win = when (outcome) {
                "LEFT" -> info.leftArm == referenceArm
                "RIGHT" -> info.rightArm == referenceArm
                else -> null
            }
            PairVerdict(
                pairId = pairId,
                purpose = comparison.purpose,
                scene = info.scene,
                referenceArm = referenceArm,
                reviewerChoices = choices,
                outcome = outcome,
                preferredArm = preferredArm,
                win = win,
            )
        }
    }

    internal fun cellOf(verdicts: List<PairVerdict>): PreferenceCell {
        val wins = verdicts.count { it.win == true }
        val losses = verdicts.count { it.win == false }
        val ties = verdicts.count { it.win == null }
        val decisive = wins + losses
        // splitScore is descriptive over every pair, including half-weighted ties;
        // rate and its Wilson interval use decisive pair outcomes only.
        val splitScore = if (verdicts.isEmpty()) 0.0 else (wins + 0.5 * ties) / verdicts.size
        if (decisive == 0) {
            return PreferenceCell(wins, losses, ties, 0, null, null, null, splitScore, true)
        }
        val (lo, hi) = Wilson.interval(wins, decisive)
        return PreferenceCell(
            wins = wins,
            losses = losses,
            ties = ties,
            decisive = decisive,
            rate = wins.toDouble() / decisive,
            ciLow = lo,
            ciHigh = hi,
            splitScore = splitScore,
            zeroDecisive = false,
        )
    }

    private data class Cells(
        val overall: PreferenceCell,
        val byScene: Map<String, PreferenceCell>,
        val byPurpose: Map<ComparisonPurpose, PreferenceCell>,
        val agreement: AgreementStats,
    )

    private data class AgreementStats(
        val unanimous: Int,
        val majority: Int,
        val disagreement: Int,
    )

    private fun computeCells(verdicts: List<PairVerdict>): Cells {
        val binding = verdicts.filter { it.purpose in BINDING_PURPOSES }
        val overall = cellOf(binding)
        val byScene = binding.groupBy { it.scene }.mapValues { cellOf(it.value) }
        val byPurpose = verdicts.groupBy { it.purpose }.mapValues { cellOf(it.value) }
        var unanimous = 0
        var majority = 0
        var disagreement = 0
        for (verdict in verdicts.filter { it.reviewerChoices.size >= 2 }) {
            val allEqual = verdict.reviewerChoices.distinct().size == 1
            if (allEqual) {
                unanimous++
            } else {
                val outcomeCount = verdict.reviewerChoices.count { it == verdict.outcome }
                val others = verdict.reviewerChoices.size - outcomeCount
                if (outcomeCount > others) majority++ else disagreement++
            }
        }
        return Cells(overall, byScene, byPurpose, AgreementStats(unanimous, majority, disagreement))
    }

    /**
     * Maps each reviewer's critical-defect declarations through the private
     * key to affected trials. Each unique reviewer contributes exactly one
     * trial-level boolean per trial they saw (flagged when any response marks
     * that trial with a critical defect, unflagged otherwise), regardless of
     * how many comparisons or crops reused the trial.
     */
    internal fun aggregateTrialDefects(
        pairMapping: Map<String, PairInfo>,
        responsesByReviewer: List<Pair<String, Map<String, ResponseRow>>>,
    ): Map<String, TrialVote> {
        val reviewerSeenTrials = mutableMapOf<String, MutableSet<String>>()
        val reviewerFlaggedTrials = mutableMapOf<String, MutableSet<String>>()

        for ((reviewer, responses) in responsesByReviewer) {
            val seen = reviewerSeenTrials.getOrPut(reviewer) { mutableSetOf() }
            val flagged = reviewerFlaggedTrials.getOrPut(reviewer) { mutableSetOf() }
            for ((pairId, row) in responses) {
                val info = pairMapping[pairId] ?: continue
                seen += info.leftTrialId
                seen += info.rightTrialId
                if (row.defect.isNotEmpty() && row.defectSide in ResponseSchema.DEFECT_SIDES) {
                    when (row.defectSide) {
                        "LEFT" -> flagged += info.leftTrialId
                        "RIGHT" -> flagged += info.rightTrialId
                        "BOTH" -> {
                            flagged += info.leftTrialId
                            flagged += info.rightTrialId
                        }
                    }
                }
            }
        }

        val allTrialIds = reviewerSeenTrials.values.flatten().toSet()
        val result = mutableMapOf<String, TrialVote>()

        for (trialId in allTrialIds) {
            val flaggedReviewers = mutableListOf<String>()
            val unflaggedReviewers = mutableListOf<String>()

            for ((reviewer, seenSet) in reviewerSeenTrials) {
                if (trialId in seenSet) {
                    val flaggedSet = reviewerFlaggedTrials[reviewer] ?: emptySet()
                    if (trialId in flaggedSet) {
                        flaggedReviewers += reviewer
                    } else {
                        unflaggedReviewers += reviewer
                    }
                }
            }

            result[trialId] = TrialVote(
                flagged = flaggedReviewers.distinct().sorted(),
                unflagged = unflaggedReviewers.distinct().sorted(),
            )
        }

        return result
    }

    /** One reviewer: that vote decides; otherwise strict majority; equal votes are DISPUTED. */
    internal fun trialCriticalStatus(vote: TrialVote): TrialCriticalStatus? = when {
        vote.flagged.isEmpty() && vote.unflagged.isEmpty() -> null
        vote.flagged.size > vote.unflagged.size -> TrialCriticalStatus.CRITICAL
        vote.unflagged.size > vote.flagged.size -> TrialCriticalStatus.NONCRITICAL
        else -> TrialCriticalStatus.DISPUTED
    }

    internal fun computeArmMetrics(trials: List<Trial>, votes: Map<String, TrialVote>): Map<String, ArmMetrics> {
        return trials.groupBy { it.arm }.mapValues { (arm, armTrials) ->
            val excluded = armTrials.count { it.outcome == TrialOutcome.EXCLUDED }
            val eligible = armTrials.filter { it.outcome != TrialOutcome.EXCLUDED }
            val denom = eligible.size
            val failed = eligible.count { it.outcome == TrialOutcome.FAILED }
            val success = eligible.count { it.outcome == TrialOutcome.SUCCESS }
            val successTrials = eligible.filter { it.outcome == TrialOutcome.SUCCESS }
            val criticalSuccess = successTrials.count {
                val vote = votes[it.trialId] ?: return@count false
                trialCriticalStatus(vote) == TrialCriticalStatus.CRITICAL
            }
            val disputed = successTrials.count {
                val vote = votes[it.trialId] ?: return@count false
                trialCriticalStatus(vote) == TrialCriticalStatus.DISPUTED
            }
            val fallback = eligible.count { it.route?.lowercase(Locale.ROOT)?.contains("fallback") == true }
            ArmMetrics(
                arm = arm,
                eligibleTrials = denom,
                successTrials = success,
                failedTrials = failed,
                excludedTrials = excluded,
                reviewerCriticalTrials = criticalSuccess,
                disputedTrials = disputed,
                completionRate = if (denom > 0) success.toDouble() / denom else null,
                criticalFailureRate = if (denom > 0) (failed + criticalSuccess).toDouble() / denom else null,
                exclusionRate = if (armTrials.isNotEmpty()) excluded.toDouble() / armTrials.size else null,
                fallbackTrials = fallback,
                fallbackRate = if (denom > 0) fallback.toDouble() / denom else null,
                latencyMedianMs = medianOver(successTrials) { it.latencyMs },
                memoryMedianKb = medianOver(successTrials) { it.memoryKb },
                outputBytesMedian = medianOver(successTrials) { it.outputBytes },
            )
        }
    }

    private fun computeMetrics(dataset: Dataset, verdicts: List<PairVerdict>, votes: Map<String, TrialVote>): Metrics {
        val byArm = computeArmMetrics(dataset.trials, votes)
        val byScene = dataset.trials.groupBy { it.scene }
            .mapValues { (_, trials) -> computeArmMetrics(trials, votes) }
        val aaDecisive = verdicts.filter { it.purpose == ComparisonPurpose.BLINDED_AA && it.win != null }
        val aaRate = if (aaDecisive.isEmpty()) {
            null
        } else {
            aaDecisive.count { it.win == true }.toDouble() / aaDecisive.size
        }
        return Metrics(byArm, byScene, aaRate)
    }

    private fun medianOver(trials: List<Trial>, selector: (Trial) -> Double?): Double? {
        val values = trials.mapNotNull(selector)
        return if (values.isEmpty()) null else Median.of(values)
    }

    private data class ThresholdEval(
        val status: ReportStatus,
        val reasons: List<String>,
        val contractVersion: String,
        val baselineIdentifier: String,
        val lockPresent: Boolean,
    )

    private fun evaluateCalibrationThreshold(
        dataset: Dataset,
        seals: List<SealRecord>,
        thresholdPath: Path?,
        cells: Cells,
    ): ThresholdEval {
        val reasons = mutableListOf<String>()
        if (thresholdPath != null && Files.isRegularFile(thresholdPath)) {
            // A calibration threshold is naturally created after calibration
            // review, so its creation time is not compared against the
            // calibration seal boundary.
            val lock = readAndVerifyLock(
                thresholdPath,
                dataset,
                seals,
                rejectCalibrationEqualsCandidate = false,
                checkLockTiming = false,
            )
            reasons += "valid locked threshold present: contract=${lock.require("contract_version")} baseline=${lock.require("baseline_identifier")}"
        } else {
            reasons += "no valid locked threshold; calibration remains descriptive"
        }
        reasons += "calibration analysis is descriptive; a quality PASS requires a validly locked threshold from lock-thresholds"
        return ThresholdEval(
            status = ReportStatus.INCONCLUSIVE_CALIBRATION,
            reasons = reasons,
            contractVersion = dataset.props.contractVersion,
            baselineIdentifier = dataset.props.baselineIdentifier,
            lockPresent = thresholdPath != null && Files.isRegularFile(thresholdPath),
        )
    }

    private fun evaluateCandidateThreshold(
        dataset: Dataset,
        seals: List<SealRecord>,
        thresholdPath: Path?,
        cells: Cells,
        metrics: Metrics,
        roles: CandidateRoles.Roles,
    ): ThresholdEval {
        if (thresholdPath == null || !Files.isRegularFile(thresholdPath)) {
            throw ToolError(Codes.THRESHOLD_MISSING, "candidate analysis requires a valid locked threshold")
        }
        val lock = readAndVerifyLock(
            thresholdPath,
            dataset,
            seals,
            rejectCalibrationEqualsCandidate = true,
            checkLockTiming = true,
        )
        val reasons = mutableListOf<String>()
        var status: ReportStatus = ReportStatus.PASS

        fun fail(reason: String) {
            reasons += reason
            status = ReportStatus.FAIL
        }

        fun inconclusive(reason: String, newStatus: ReportStatus = ReportStatus.INCONCLUSIVE) {
            reasons += reason
            if (status == ReportStatus.PASS) status = newStatus
        }

        val adequacy = lock.entries["adequacy_decision"]
        if (adequacy != "ADEQUATE") {
            inconclusive("calibration adequacy decision is '$adequacy', not ADEQUATE")
        } else {
            reasons += "calibration adequacy decision: ADEQUATE"
        }

        val minSample = lock.require("min_sample_per_grain").toIntOrNull()
        if (minSample == null || minSample <= 0) {
            throw ToolError(Codes.THRESHOLD_INVALID, "locked threshold min_sample_per_grain must be a positive integer")
        }
        val grains = dataset.successfulTrials()
            .groupBy { Triple(it.scene, it.condition, it.arm) }
        val insufficient = grains.filter { it.value.size < minSample }.keys
        if (insufficient.isNotEmpty()) {
            inconclusive("minimum sample rule violated for grain(s): ${insufficient.take(3).joinToString(", ")}")
        } else {
            reasons += "minimum sample rule satisfied (>= $minSample per grain)"
        }

        val failureMargin = lockMargin(lock, "critical_failure_margin")
        val reliabilityMargin = lockMargin(lock, "reliability_margin_non_inferiority")

        val candidate = metrics.byArm[roles.candidateArm]
        val baseline = metrics.byArm[roles.baselineArm]
        if (candidate == null || baseline == null) {
            inconclusive("missing arm-level capture evidence for candidate '${roles.candidateArm}' or baseline '${roles.baselineArm}'")
        } else {
            // Critical-failure non-inferiority: candidateRate - baselineRate <= margin.
            val candRate = candidate.criticalFailureRate
            val baseRate = baseline.criticalFailureRate
            when {
                candRate == null || baseRate == null ->
                    inconclusive("critical-failure evidence unavailable (zero eligible trials)")
                candRate - baseRate > failureMargin ->
                    fail("critical-failure regression: candidate ${fmt(candRate, 4)} - baseline ${fmt(baseRate, 4)} exceeds critical_failure_margin $failureMargin")
                else ->
                    reasons += "critical-failure non-inferiority satisfied (candidate ${fmt(candRate, 4)} - baseline ${fmt(baseRate, 4)} <= $failureMargin)"
            }
            // Disputed trial-level defect evidence makes the gate inconclusive
            // unless another fail-closed condition already caused FAIL.
            if (candidate.disputedTrials > 0 || baseline.disputedTrials > 0) {
                inconclusive("disputed critical-defect evidence (candidate disputed=${candidate.disputedTrials}, baseline disputed=${baseline.disputedTrials})")
            }
            // Completion reliability: baselineCompletion - candidateCompletion <= margin.
            val candCompletion = candidate.completionRate
            val baseCompletion = baseline.completionRate
            when {
                candCompletion == null || baseCompletion == null ->
                    inconclusive("completion evidence unavailable (zero eligible trials)")
                baseCompletion - candCompletion > reliabilityMargin ->
                    fail("completion regression: baseline ${fmt(baseCompletion, 4)} - candidate ${fmt(candCompletion, 4)} exceeds reliability_margin_non_inferiority $reliabilityMargin")
                else ->
                    reasons += "completion reliability satisfied (baseline ${fmt(baseCompletion, 4)} - candidate ${fmt(candCompletion, 4)} <= $reliabilityMargin)"
            }
        }

        // The lock's critical-scene set must equal the candidate dataset's
        // predeclared critical-scene set.
        val lockScenes = lock.require("critical_scene_families").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (lockScenes.toSet() != dataset.props.criticalScenes.toSet()) {
            throw ToolError(
                Codes.THRESHOLD_CRITICAL_SCENE_MISMATCH,
                "locked critical scene families [${lockScenes.sorted().joinToString(",")}] do not equal the candidate dataset's predeclared critical scenes [${dataset.props.criticalScenes.sorted().joinToString(",")}]",
            )
        }
        for (scene in lockScenes) {
            val sceneStats = metrics.byScene[scene]
            if (sceneStats == null) {
                inconclusive("no capture evidence for critical scene '$scene'")
                continue
            }
            val c = sceneStats[roles.candidateArm]
            val b = sceneStats[roles.baselineArm]
            if (c == null || b == null) {
                inconclusive("missing candidate/baseline capture evidence in critical scene '$scene'")
                continue
            }
            val cc = c.completionRate
            val bc = b.completionRate
            if (cc == null || bc == null) {
                inconclusive("zero-denominator completion evidence in critical scene '$scene'")
                continue
            }
            if (bc - cc > reliabilityMargin) {
                fail("completion regression in critical scene '$scene': baseline ${fmt(bc, 4)} - candidate ${fmt(cc, 4)} exceeds reliability_margin_non_inferiority $reliabilityMargin")
            } else {
                reasons += "critical scene '$scene' completion reliability satisfied (${fmt(bc, 4)} - ${fmt(cc, 4)} <= $reliabilityMargin)"
            }
        }

        // Usefulness rule evaluated only against binding CANDIDATE_VS_BASELINE evidence.
        val usefulness = lock.require("usefulness_rule")
        when (val eval = evalUsefulness(usefulness, cells.overall)) {
            is UsefulnessEval.Satisfied -> reasons += "usefulness rule satisfied"
            is UsefulnessEval.Violated -> fail("usefulness rule violated: ${eval.reason}")
            UsefulnessEval.ZeroDecisive ->
                inconclusive("no decisive binding pair-level evidence for the usefulness rule", ReportStatus.INCONCLUSIVE_ZERO_DECISIVE)
            UsefulnessEval.Unevaluated ->
                inconclusive("usefulness rule is not machine-decidable: '$usefulness'")
        }

        // Guardrails evaluated against candidate-arm values only; baseline and
        // stock values remain separately visible in the report.
        val (guardrailReasons, unverified) = evalGuardrails(lock.require("guardrails"), metrics, roles)
        reasons += guardrailReasons
        if (guardrailReasons.any { it.startsWith("VIOLATION") }) {
            status = ReportStatus.FAIL
        }
        if (unverified.isNotEmpty() && status != ReportStatus.FAIL) {
            inconclusive("required binding guardrail metric(s) unavailable: ${unverified.joinToString(", ")}")
        }

        return ThresholdEval(
            status = status,
            reasons = reasons,
            contractVersion = lock.require("contract_version"),
            baselineIdentifier = lock.require("baseline_identifier"),
            lockPresent = true,
        )
    }

    private fun lockMargin(lock: PropMap, key: String): Double {
        val raw = lock.require(key)
        val value = raw.toDoubleOrNull()
            ?: throw ToolError(Codes.THRESHOLD_INVALID, "locked threshold '$key' is not a number: '$raw'")
        if (!value.isFinite() || value < 0.0 || value > 1.0) {
            throw ToolError(Codes.THRESHOLD_INVALID, "locked threshold '$key' must be finite and within [0,1]: '$raw'")
        }
        return value
    }

    /**
     * Verifies a locked threshold: schema version, self hash, contract and
     * baseline identity, and every referenced calibration artifact rehashed
     * from its recorded path. For candidate analysis the calibration evidence
     * must not be this dataset, and the lock time must not be after any
     * candidate response seal.
     */
    private fun readAndVerifyLock(
        path: Path,
        dataset: Dataset,
        seals: List<SealRecord>,
        rejectCalibrationEqualsCandidate: Boolean,
        checkLockTiming: Boolean,
    ): PropMap {
        val lock = StrictProperties.read(path)
        if (lock.require("threshold_schema_version") != TOOL_SCHEMA_VERSION) {
            throw ToolError(Codes.THRESHOLD_INVALID, "unsupported threshold schema version")
        }
        val selfHash = lock.entries["lock_self_hash_sha256"]
            ?: throw ToolError(Codes.THRESHOLD_INVALID, "locked threshold missing its self hash")
        val recomputed = Hashes.sha256(StrictProperties.serialize(lock.entries - "lock_self_hash_sha256"))
        if (selfHash != recomputed) {
            throw ToolError(Codes.THRESHOLD_SELF_HASH, "locked threshold is not immutable-by-hash")
        }
        val contractVersion = lock.require("contract_version")
        val baselineIdentifier = lock.require("baseline_identifier")
        if (contractVersion != dataset.props.contractVersion) {
            throw ToolError(Codes.THRESHOLD_CONTRACT_MISMATCH, "threshold contract '$contractVersion' does not match dataset '${dataset.props.contractVersion}'")
        }
        if (baselineIdentifier != dataset.props.baselineIdentifier) {
            throw ToolError(Codes.THRESHOLD_BASELINE_MISMATCH, "threshold baseline '$baselineIdentifier' does not match dataset '${dataset.props.baselineIdentifier}'")
        }
        verifyCalibrationEvidence(lock, dataset, rejectCalibrationEqualsCandidate)
        val lockTime = UtcClock.parseInstant(lock.require("lock_generated_at_utc"))
        if (checkLockTiming) {
            val sealBoundary = seals.minOf { UtcClock.parseInstant(it.sealTimestamp) }
            if (lockTime.isAfter(sealBoundary)) {
                throw ToolError(Codes.THRESHOLD_POST_UNBLIND, "locked threshold was created after the candidate response seal/unblinding boundary")
            }
        }
        return lock
    }

    private fun verifyCalibrationEvidence(lock: PropMap, dataset: Dataset, rejectCalibrationEqualsCandidate: Boolean) {
        fun evidencePath(key: String): Path {
            val raw = lock.require(key)
            return try {
                Path.of(raw)
            } catch (e: InvalidPathException) {
                throw ToolError(Codes.THRESHOLD_CALIBRATION_EVIDENCE, "locked threshold '$key' is not a valid path: '$raw'")
            }
        }
        val calDatasetPath = evidencePath("calibration_dataset_path")
        if (!Files.isDirectory(calDatasetPath)) {
            throw ToolError(Codes.THRESHOLD_CALIBRATION_EVIDENCE, "calibration dataset is unavailable: '$calDatasetPath'")
        }
        val calDatasetHash = lock.require("calibration_dataset_hash_sha256")
        if (Hashes.sha256Directory(calDatasetPath) != calDatasetHash) {
            throw ToolError(Codes.THRESHOLD_CALIBRATION_EVIDENCE, "calibration dataset has changed since locking")
        }
        val currentDatasetHash = Hashes.sha256Directory(dataset.root)
        if (rejectCalibrationEqualsCandidate && calDatasetHash == currentDatasetHash) {
            throw ToolError(Codes.THRESHOLD_CALIBRATION_EVIDENCE, "calibration evidence must come from the calibration dataset, not this dataset")
        }
        val calReportPath = evidencePath("calibration_report_path")
        if (!Files.isRegularFile(calReportPath)) {
            throw ToolError(Codes.THRESHOLD_CALIBRATION_EVIDENCE, "calibration report is unavailable: '$calReportPath'")
        }
        if (Hashes.sha256File(calReportPath) != lock.require("calibration_report_hash_sha256")) {
            throw ToolError(Codes.THRESHOLD_CALIBRATION_EVIDENCE, "calibration report has changed since locking")
        }
        val calPackagePath = evidencePath("calibration_package_path")
        if (!Files.isRegularFile(calPackagePath)) {
            throw ToolError(Codes.THRESHOLD_CALIBRATION_EVIDENCE, "calibration package manifest is unavailable: '$calPackagePath'")
        }
        if (Hashes.sha256File(calPackagePath) != lock.require("calibration_package_hash_sha256")) {
            throw ToolError(Codes.THRESHOLD_CALIBRATION_EVIDENCE, "calibration package manifest has changed since locking")
        }
        val sealKeys = lock.entries.keys.filter { it.startsWith("calibration_seal_hash_sha256.") }
        if (sealKeys.isEmpty()) {
            throw ToolError(Codes.THRESHOLD_CALIBRATION_EVIDENCE, "locked threshold binds no calibration seal hash")
        }
        for (key in sealKeys) {
            val hash = lock.entries[key]
            if (hash.isNullOrBlank()) {
                throw ToolError(Codes.THRESHOLD_CALIBRATION_EVIDENCE, "locked threshold has an empty calibration seal hash")
            }
            val index = key.removePrefix("calibration_seal_hash_sha256.")
            val sealPath = evidencePath("calibration_seal_path.$index")
            if (!Files.isRegularFile(sealPath)) {
                throw ToolError(Codes.THRESHOLD_CALIBRATION_EVIDENCE, "calibration seal $index is unavailable: '$sealPath'")
            }
            if (Hashes.sha256File(sealPath) != hash) {
                throw ToolError(Codes.THRESHOLD_CALIBRATION_EVIDENCE, "calibration seal $index has changed since locking")
            }
        }
    }

    internal sealed class UsefulnessEval {
        data object Satisfied : UsefulnessEval()
        data class Violated(val reason: String) : UsefulnessEval()
        data object ZeroDecisive : UsefulnessEval()
        data object Unevaluated : UsefulnessEval()
    }

    internal fun evalUsefulness(rule: String, cell: PreferenceCell): UsefulnessEval {
        val parsed = UsefulnessRule.parse(rule) ?: return UsefulnessEval.Unevaluated
        if (cell.zeroDecisive) return UsefulnessEval.ZeroDecisive
        val value = when (parsed.kind) {
            UsefulnessRule.Kind.RATE -> cell.rate
            UsefulnessRule.Kind.LOWER_BOUND -> cell.ciLow
        }
        if (value == null) return UsefulnessEval.ZeroDecisive
        return if (value < parsed.bound) {
            UsefulnessEval.Violated("decisive preference value ${fmt(value, 4)} below target ${parsed.bound}")
        } else {
            UsefulnessEval.Satisfied
        }
    }

    /** Returns (reasons, unverifiedMetricNames) with values taken from the candidate arm only. */
    internal fun evalGuardrails(text: String, metrics: Metrics, roles: CandidateRoles.Roles): Pair<List<String>, List<String>> {
        val reasons = mutableListOf<String>()
        val unverified = mutableListOf<String>()
        val candidate = metrics.byArm[roles.candidateArm]
        for (entry in text.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val opMatch = Regex("^([a-z_]+)(<=|>=|<|>|=)(.+)$").matchEntire(entry)
                ?: throw ToolError(Codes.LOCK_INVALID_RULE, "malformed guardrail '$entry'")
            val name = opMatch.groupValues[1]
            val op = opMatch.groupValues[2]
            val rawValue = opMatch.groupValues[3]
            if (name !in Guardrails.KNOWN) {
                throw ToolError(Codes.LOCK_INVALID_RULE, "unknown guardrail metric '$name'")
            }
            if (name == "privacy_no_leak") {
                if (rawValue != "yes") throw ToolError(Codes.LOCK_INVALID_RULE, "privacy_no_leak requires value 'yes'")
                reasons += "GUARDRAIL privacy_no_leak satisfied"
                continue
            }
            val target = rawValue.toDoubleOrNull()
                ?: throw ToolError(Codes.LOCK_INVALID_RULE, "guardrail '$name' requires a numeric bound")
            if (!target.isFinite()) {
                throw ToolError(Codes.LOCK_INVALID_RULE, "guardrail '$name' bound must be finite")
            }
            val actual = when (name) {
                "failure_rate", "critical_failure_rate" -> candidate?.criticalFailureRate
                "completion_rate" -> candidate?.completionRate
                "exclusion_rate" -> candidate?.exclusionRate
                "latency_median_ms" -> candidate?.latencyMedianMs
                "memory_median_kb" -> candidate?.memoryMedianKb
                "output_bytes_median" -> candidate?.outputBytesMedian
                "fallback_rate" -> candidate?.fallbackRate
                "aa_arm_a_preference_rate" -> metrics.aaArmAPreferenceRate
                else -> null
            }
            if (actual == null) {
                unverified += name
                continue
            }
            val satisfied = when (op) {
                "<=" -> actual <= target
                ">=" -> actual >= target
                "<" -> actual < target
                ">" -> actual > target
                else -> actual == target
            }
            if (satisfied) {
                reasons += "GUARDRAIL $name satisfied (${fmt(actual, 4)} $op $target)"
            } else {
                reasons += "VIOLATION guardrail $name violated (${fmt(actual, 4)} $op $target)"
            }
        }
        return reasons to unverified
    }

    private fun writeAnalysisRows(
        report: Report,
        dataset: Dataset,
        packageId: String,
        manifestPath: Path,
        keyPath: Path,
        seals: List<SealRecord>,
        verdicts: List<PairVerdict>,
        cells: Cells,
        metrics: Metrics,
        roles: CandidateRoles.Roles?,
        generatedAt: String,
        reviewerResponses: List<Pair<String, Map<String, ResponseRow>>>,
    ) {
        report.add("identity", "generated_at_utc", generatedAt)
        report.add("identity", "dataset_kind", dataset.props.kind.value)
        report.add("identity", "contract_version", dataset.props.contractVersion)
        report.add("identity", "baseline_identifier", dataset.props.baselineIdentifier)
        report.add("identity", "dataset_hash_sha256", Hashes.sha256Directory(dataset.root))
        report.add("identity", "package_id", packageId)
        report.add("identity", "package_manifest_hash_sha256", Hashes.sha256File(manifestPath))
        report.add("identity", "key_hash_sha256", Hashes.sha256File(keyPath))
        if (roles != null) {
            report.add("roles", "candidate_arm", roles.candidateArm)
            report.add("roles", "locked_baseline_arm", roles.baselineArm)
        }

        report.add("seal", "seal_count", seals.size.toString())
        for ((i, seal) in seals.withIndex()) {
            report.add("seal", "seal.${i + 1}.reviewer", seal.reviewer)
            report.add("seal", "seal.${i + 1}.category", seal.reviewerCategory)
            report.add("seal", "seal.${i + 1}.conflict_declaration", seal.conflictDeclaration)
            report.add("seal", "seal.${i + 1}.timestamp_utc", seal.sealTimestamp)
            report.add("seal", "seal.${i + 1}.hash_sha256", Hashes.sha256File(seal.path))
            report.add("seal", "seal.${i + 1}.response_hash_sha256", seal.responseHash)
        }

        report.add("preference_overall", "binding_purpose", ComparisonPurpose.CANDIDATE_VS_BASELINE.value)
        report.add("preference_overall", "wins", cells.overall.wins.toString())
        report.add("preference_overall", "losses", cells.overall.losses.toString())
        report.add("preference_overall", "ties", cells.overall.ties.toString())
        report.add("preference_overall", "decisive_pairs", cells.overall.decisive.toString())
        appendCell(report, "preference_overall", cells.overall)

        for ((purpose, cell) in cells.byPurpose.toSortedMap(compareBy { it.value })) {
            val section = "preference_by_purpose"
            report.add(section, "purpose_${purpose.value}.wins", cell.wins.toString())
            report.add(section, "purpose_${purpose.value}.losses", cell.losses.toString())
            report.add(section, "purpose_${purpose.value}.ties", cell.ties.toString())
            appendCellPrefixed(report, section, "purpose_${purpose.value}", cell)
        }

        // Contextual purposes are reported separately and never affect acceptance.
        for (purpose in listOf(ComparisonPurpose.CANDIDATE_VS_STOCK, ComparisonPurpose.CONTEXTUAL_REFERENCE)) {
            val cell = cells.byPurpose[purpose]
            if (cell != null) {
                val section = "contextual_${purpose.value.lowercase(Locale.ROOT)}"
                report.add(section, "wins", cell.wins.toString())
                report.add(section, "losses", cell.losses.toString())
                report.add(section, "ties", cell.ties.toString())
                report.add(section, "decisive_pairs", cell.decisive.toString())
                appendCellPrefixed(report, section, "cell", cell)
                report.add(section, "note", "contextual report only; never improves or reduces candidate acceptance")
            }
        }

        for ((scene, cell) in cells.byScene.toSortedMap()) {
            appendCellPrefixed(report, "preference_by_scene", "scene_$scene", cell)
        }

        val aa = verdicts.filter { it.purpose == ComparisonPurpose.BLINDED_AA }
        val aaLeft = aa.count { it.outcome == "LEFT" }
        val aaRight = aa.count { it.outcome == "RIGHT" }
        val aaArmAPref = aa.count { it.win == true }
        val aaArmB = aa.count { it.win == false }
        report.add("aa_diagnostics", "pairs", aa.size.toString())
        report.add("aa_diagnostics", "left_preferred", aaLeft.toString())
        report.add("aa_diagnostics", "right_preferred", aaRight.toString())
        report.add("aa_diagnostics", "arm_a_preferred", aaArmAPref.toString())
        report.add("aa_diagnostics", "arm_b_preferred", aaArmB.toString())
        report.add("aa_diagnostics", "arm_a_preference_rate", if (aaArmAPref + aaArmB == 0) "UNAVAILABLE" else fmt(aaArmAPref.toDouble() / (aaArmAPref + aaArmB), 4))

        report.add("agreement", "reviewers", seals.size.toString())
        report.add("agreement", "unanimous_pairs", cells.agreement.unanimous.toString())
        report.add("agreement", "majority_pairs", cells.agreement.majority.toString())
        report.add("agreement", "disagreement_pairs", cells.agreement.disagreement.toString())

        for ((reviewer, responses) in reviewerResponses) {
            val left = responses.values.count { it.choice == "LEFT" }
            val right = responses.values.count { it.choice == "RIGHT" }
            val tie = responses.values.count { it.choice == "TIE" }
            report.add("reviewer_raw", "reviewer_$reviewer.left", left.toString())
            report.add("reviewer_raw", "reviewer_$reviewer.right", right.toString())
            report.add("reviewer_raw", "reviewer_$reviewer.tie", tie.toString())
        }

        val trials = dataset.trials
        val total = trials.size
        val success = trials.count { it.outcome == TrialOutcome.SUCCESS }
        val failed = trials.count { it.outcome == TrialOutcome.FAILED }
        val excluded = trials.count { it.outcome == TrialOutcome.EXCLUDED }
        report.add("capture", "total_trials", total.toString())
        report.add("capture", "success", success.toString())
        report.add("capture", "failed", failed.toString())
        report.add("capture", "excluded", excluded.toString())
        report.add("capture", "duplicate_evidence_groups", duplicateOriginalGroups(dataset).toString())

        for ((arm, s) in metrics.byArm.toSortedMap()) {
            val section = "capture_by_arm"
            report.add(section, "arm_$arm.eligible_trials", s.eligibleTrials.toString())
            report.add(section, "arm_$arm.success", s.successTrials.toString())
            report.add(section, "arm_$arm.failed", s.failedTrials.toString())
            report.add(section, "arm_$arm.excluded", s.excludedTrials.toString())
            report.add(section, "arm_$arm.reviewer_critical", s.reviewerCriticalTrials.toString())
            report.add(section, "arm_$arm.disputed", s.disputedTrials.toString())
            report.add(section, "arm_$arm.completion_rate", s.completionRate?.let { fmt(it, 4) } ?: "UNAVAILABLE")
            report.add(section, "arm_$arm.critical_failure_rate", s.criticalFailureRate?.let { fmt(it, 4) } ?: "UNAVAILABLE")
            report.add(section, "arm_$arm.fallback_rate", s.fallbackRate?.let { fmt(it, 4) } ?: "UNAVAILABLE")
            report.add(section, "arm_$arm.latency_median_ms", s.latencyMedianMs?.let { fmt(it, 3) } ?: "UNAVAILABLE")
            report.add(section, "arm_$arm.memory_median_kb", s.memoryMedianKb?.let { fmt(it, 3) } ?: "UNAVAILABLE")
            report.add(section, "arm_$arm.output_bytes_median", s.outputBytesMedian?.let { fmt(it, 3) } ?: "UNAVAILABLE")
        }
        for ((scene, arms) in metrics.byScene.toSortedMap()) {
            for ((arm, s) in arms.toSortedMap()) {
                val section = "capture_by_scene"
                report.add(section, "scene_$scene.arm_$arm.eligible_trials", s.eligibleTrials.toString())
                report.add(section, "scene_$scene.arm_$arm.success", s.successTrials.toString())
                report.add(section, "scene_$scene.arm_$arm.failed", s.failedTrials.toString())
                report.add(section, "scene_$scene.arm_$arm.excluded", s.excludedTrials.toString())
                report.add(section, "scene_$scene.arm_$arm.reviewer_critical", s.reviewerCriticalTrials.toString())
                report.add(section, "scene_$scene.arm_$arm.disputed", s.disputedTrials.toString())
                report.add(section, "scene_$scene.arm_$arm.completion_rate", s.completionRate?.let { fmt(it, 4) } ?: "UNAVAILABLE")
                report.add(section, "scene_$scene.arm_$arm.critical_failure_rate", s.criticalFailureRate?.let { fmt(it, 4) } ?: "UNAVAILABLE")
            }
        }

        report.add("repeated_numeric", "metrics_available", "latency_ms memory_kb output_bytes")
        for ((key, selector) in listOf(
            "latency_ms" to { t: Trial -> t.latencyMs },
            "memory_kb" to { t: Trial -> t.memoryKb },
            "output_bytes" to { t: Trial -> t.outputBytes },
        )) {
            val grains = dataset.successfulTrials().groupBy { Pair(it.scene, it.arm) }.toSortedMap(compareBy({ it.first }, { it.second }))
            for ((grain, groupTrials) in grains) {
                val values = groupTrials.mapNotNull(selector)
                if (values.isEmpty()) {
                    report.add("repeated_numeric", "$key.${grain.first}.${grain.second}", "UNAVAILABLE")
                } else {
                    val sorted = values.sorted()
                    report.add("repeated_numeric", "$key.${grain.first}.${grain.second}", "n=${values.size} min=${fmt(sorted.first(), 3)} max=${fmt(sorted.last(), 3)} median=${fmt(Median.of(values), 3)}")
                }
            }
        }

        report.add("missingness", "pairs", verdicts.size.toString())
        report.add("missingness", "unsealed_pairs", "0")
        report.add("missingness", "sealed_reviewers", seals.size.toString())

        for (metric in listOf("sharpness", "noise", "motion", "clipping", "delta_e", "psnr", "ssim", "lpips", "mtf")) {
            report.add("image_metrics", metric, "UNAVAILABLE_NOT_IMPLEMENTED")
        }
        report.add("image_metrics", "output_byte_size_is", "diagnostic guardrail, not an image-quality score")
    }

    private fun appendCell(report: Report, section: String, cell: PreferenceCell) {
        if (cell.zeroDecisive) {
            report.add(section, "status", "INCONCLUSIVE_ZERO_DECISIVE")
        } else {
            report.add(section, "decisive_preference_rate", fmt(cell.rate!!, 4))
            report.add(section, "wilson_95_lower", fmt(cell.ciLow!!, 4))
            report.add(section, "wilson_95_upper", fmt(cell.ciHigh!!, 4))
            report.add(section, "status", "computed")
        }
        report.add(section, "split_score_descriptive", fmt(cell.splitScore, 4))
    }

    private fun appendCellPrefixed(report: Report, section: String, prefix: String, cell: PreferenceCell) {
        report.add(section, "$prefix.wins", cell.wins.toString())
        report.add(section, "$prefix.losses", cell.losses.toString())
        report.add(section, "$prefix.ties", cell.ties.toString())
        report.add(section, "$prefix.decisive_pairs", cell.decisive.toString())
        if (cell.zeroDecisive) {
            report.add(section, "$prefix.status", "INCONCLUSIVE_ZERO_DECISIVE")
        } else {
            report.add(section, "$prefix.decisive_preference_rate", fmt(cell.rate!!, 4))
            report.add(section, "$prefix.wilson_95_lower", fmt(cell.ciLow!!, 4))
            report.add(section, "$prefix.wilson_95_upper", fmt(cell.ciHigh!!, 4))
        }
        report.add(section, "$prefix.split_score_descriptive", fmt(cell.splitScore, 4))
    }

    private fun duplicateOriginalGroups(dataset: Dataset): Int {
        val hashes = dataset.successfulTrials().mapNotNull { it.originalHashSha256 }
        return hashes.groupingBy { it }.eachCount().count { it.value > 1 }
    }

    private fun fmt(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

    private fun writeReports(outDir: Path, report: Report, status: ReportStatus) {
        try {
            Files.createDirectories(outDir)
        } catch (e: Exception) {
            throw ToolError(Codes.FILE_WRITE, "cannot create output directory '$outDir': ${e.message}", ToolExitCode.IO)
        }
        val header = listOf("section", "key", "value")
        val rows = report.rows.map { listOf(it.first, it.second, it.third) }
        Csv.write(outDir.resolve("analysis-report.csv"), header, rows)
        val html = renderHtml(report.rows, status.code())
        try {
            Files.write(outDir.resolve("analysis-report.html"), html.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ToolError(Codes.FILE_WRITE, "cannot write analysis report: ${e.message}", ToolExitCode.IO)
        }
    }

    private fun renderHtml(rows: List<Triple<String, String, String>>, status: String): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
        sb.append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'\">\n")
        sb.append("<title>Analysis report</title>\n<style>")
        sb.append("body{font-family:system-ui,sans-serif;max-width:900px;margin:2em auto;padding:0 1em;}")
        sb.append("table{border-collapse:collapse;width:100%;margin-bottom:2em;}")
        sb.append("th,td{border:1px solid #ccc;padding:4px 8px;text-align:left;font-size:0.85em;}")
        sb.append("th{background:#f2f2f2;width:40%;}")
        sb.append("h1{font-size:1.3em;} h2{font-size:1.05em;margin-top:1.5em;}")
        sb.append(".status{font-weight:bold;}")
        sb.append("</style>\n</head>\n<body>\n")
        sb.append("<h1>Analysis report</h1>\n<p class=\"status\">Status: ").append(escapeHtml(status)).append("</p>\n")
        var currentSection = ""
        for ((section, key, value) in rows) {
            if (section != currentSection) {
                if (currentSection.isNotEmpty()) sb.append("</table>\n")
                currentSection = section
                sb.append("<h2>").append(escapeHtml(section)).append("</h2>\n<table>\n")
            }
            sb.append("<tr><th>").append(escapeHtml(key)).append("</th><td>").append(escapeHtml(value)).append("</td></tr>\n")
        }
        if (currentSection.isNotEmpty()) sb.append("</table>\n")
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private class Report {
        val rows = mutableListOf<Triple<String, String, String>>()

        fun add(section: String, key: String, value: String) {
            rows += Triple(section, key, value)
        }
    }
}
