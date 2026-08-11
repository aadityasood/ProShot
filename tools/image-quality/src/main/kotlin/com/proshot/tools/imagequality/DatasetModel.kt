package com.proshot.tools.imagequality

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal enum class DatasetKind(val value: String) {
    CALIBRATION("CALIBRATION"),
    CANDIDATE("CANDIDATE"),
}

internal enum class TrialOutcome(val value: String) {
    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
    EXCLUDED("EXCLUDED"),
}

internal enum class ComparisonPurpose(val value: String) {
    BLINDED_AA("BLINDED_AA"),
    CANDIDATE_VS_BASELINE("CANDIDATE_VS_BASELINE"),
    CANDIDATE_VS_STOCK("CANDIDATE_VS_STOCK"),
    CONTEXTUAL_REFERENCE("CONTEXTUAL_REFERENCE"),
}

internal enum class CropPurpose(val value: String) {
    FOCUS("FOCUS"),
    TEXTURE("TEXTURE"),
    FACE("FACE"),
    HIGHLIGHT("HIGHLIGHT"),
    SHADOW("SHADOW"),
    ARTIFACT("ARTIFACT"),
    GENERAL("GENERAL"),
}

/**
 * Parsed `dataset.properties`. Unknown keys, missing keys, duplicate keys,
 * malformed lines, and unsupported schema versions fail closed.
 */
internal data class DatasetProperties(
    val schemaVersion: String,
    val datasetVersion: String,
    val contractVersion: String,
    val kind: DatasetKind,
    val captureProtocol: String,
    val declaredArms: List<String>,
    val requiredRepetitions: Int,
    val appIdentifier: String,
    val baselineIdentifier: String,
    val candidateIdentifier: String?,
    val privacyClassification: String,
    val predeclaredHypothesis: String,
    val criticalScenes: List<String>,
    val guardrails: List<String>,
    val allowSharedOriginals: Boolean,
)

internal data class Trial(
    val trialId: String,
    val scene: String,
    val condition: String,
    val arm: String,
    val repetition: Int,
    val captureOrder: Int,
    val outcome: TrialOutcome,
    val exclusionReason: String?,
    val failureReason: String?,
    val originalPath: String?,
    val originalHashSha256: String?,
    val originalByteSize: Long?,
    val originalWidth: Int?,
    val originalHeight: Int?,
    val originalFormat: String?,
    val reviewSourcePath: String?,
    val reviewSourceHashSha256: String?,
    val reviewSourceByteSize: Long?,
    val device: String,
    val appVersion: String,
    val cameraIdentifier: String,
    val outputFormat: String,
    val outputResolution: String,
    val exifMake: String?,
    val exifModel: String?,
    val exifOrientation: String?,
    val latencyMs: Double?,
    val memoryKb: Double?,
    val outputBytes: Double?,
    val thermalState: String?,
    val route: String?,
    val fixture: String,
    val focusState: String,
    val lightLevel: String,
    val motionState: String,
    val provenance: String,
    val consent: String,
    val publicationPermission: String,
) {
    fun grainKey(): String = "$scene/$condition/$arm/$repetition"
}

internal data class ComparisonPlanRow(
    val comparisonId: String,
    val armA: String,
    val armB: String,
    val purpose: ComparisonPurpose,
)

internal data class Crop(
    val trialId: String,
    val cropId: String,
    val cropPurpose: String,
    val x0: Double,
    val y0: Double,
    val x1: Double,
    val y1: Double,
)

internal data class Dataset(
    val root: Path,
    val props: DatasetProperties,
    val trials: List<Trial>,
    val comparisons: List<ComparisonPlanRow>,
    val crops: List<Crop>,
    val comparisonPlanHash: String,
) {
    fun successfulTrials(): List<Trial> = trials.filter { it.outcome == TrialOutcome.SUCCESS }

    fun trialById(id: String): Trial? = trials.firstOrNull { it.trialId == id }
}

/** Strict UTF-8 `key=value` properties reader/writer. */
internal class PropMap(val entries: Map<String, String>) {
    fun get(key: String): String? = entries[key]
    fun require(key: String): String =
        entries[key] ?: throw ToolError(Codes.PROP_MISSING_KEY, "missing property '$key'")

    fun requireNonBlank(key: String): String {
        val value = require(key)
        if (value.isBlank()) {
            throw ToolError(Codes.PROP_MISSING_KEY, "property '$key' must not be empty")
        }
        return value
    }
}

internal object StrictProperties {
    fun read(path: Path): PropMap {
        val text = Csv.readUtf8(path)
        val entries = LinkedHashMap<String, String>()
        val lines = text.split("\n")
        for ((idx, rawLine) in lines.withIndex()) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) {
                throw ToolError(Codes.PROP_MALFORMED, "$path:${idx + 1}: malformed property line")
            }
            val key = line.substring(0, eq).trim()
            if (key.isEmpty()) {
                throw ToolError(Codes.PROP_MALFORMED, "$path:${idx + 1}: empty property key")
            }
            if (entries.containsKey(key)) {
                throw ToolError(Codes.PROP_DUPLICATE_KEY, "$path: duplicate property '$key'")
            }
            entries[key] = line.substring(eq + 1)
        }
        return PropMap(entries)
    }

    fun serialize(entries: Map<String, String>): String {
        val sb = StringBuilder()
        for ((k, v) in entries.toSortedMap()) {
            sb.append(k).append('=').append(v).append('\n')
        }
        return sb.toString()
    }

    fun write(path: Path, entries: Map<String, String>) {
        val bytes = serialize(entries).toByteArray(StandardCharsets.UTF_8)
        try {
            Files.write(path, bytes)
        } catch (e: Exception) {
            throw ToolError(Codes.FILE_WRITE, "cannot write '$path': ${e.message}", ToolExitCode.IO)
        }
    }
}

/** Accepted values and numeric parsing rules for the schema version 1.0. */
internal object Values {
    val UNAVAILABLE_MARKERS: Set<String> = setOf("", "UNAVAILABLE", "unknown", "n/a")
    val CONSENT_VALUES: Set<String> = setOf("CONSENTED", "NOT_APPLICABLE", "NOT_CONSENTED")
    val PROVENANCE_VALUES: Set<String> = setOf("OWNER_CAPTURED", "PROVIDED", "SYNTHETIC")
    val PUBLICATION_VALUES: Set<String> = setOf("PERMITTED", "NOT_PERMITTED", "PENDING")
    val PRIVACY_VALUES: Set<String> = setOf("PRIVATE", "CONTROLLED")

    fun isUnavailable(value: String): Boolean = value in UNAVAILABLE_MARKERS

    /** Numeric diagnostics: unavailable markers stay unavailable; `0` stays a real zero. NaN/Infinity are rejected. */
    fun parseOptionalDouble(value: String, fieldName: String): Double? {
        if (isUnavailable(value)) return null
        val v = value.toDoubleOrNull()
            ?: throw ToolError(Codes.INVALID_NUMERIC, "$fieldName: not a valid number: '$value'")
        if (!v.isFinite()) {
            throw ToolError(Codes.INVALID_NUMERIC, "$fieldName: value must be finite: '$value'")
        }
        if (v < 0.0) {
            throw ToolError(Codes.INVALID_NUMERIC, "$fieldName: negative value not allowed: '$value'")
        }
        return v
    }

    fun parseOptionalLong(value: String, fieldName: String): Long? {
        if (isUnavailable(value)) return null
        val v = value.toLongOrNull()
            ?: throw ToolError(Codes.INVALID_NUMERIC, "$fieldName: not a valid integer: '$value'")
        if (v < 0) {
            throw ToolError(Codes.INVALID_NUMERIC, "$fieldName: negative value not allowed: '$value'")
        }
        return v
    }

    fun parseOptionalInt(value: String, fieldName: String): Int? {
        if (isUnavailable(value)) return null
        val v = value.toIntOrNull()
            ?: throw ToolError(Codes.INVALID_NUMERIC, "$fieldName: not a valid integer: '$value'")
        if (v < 0) {
            throw ToolError(Codes.INVALID_NUMERIC, "$fieldName: negative value not allowed: '$value'")
        }
        return v
    }

    fun parsePositiveInt(value: String, fieldName: String): Int {
        val v = value.toIntOrNull() ?: throw ToolError(Codes.INVALID_NUMERIC, "$fieldName: not an integer: '$value'")
        if (v <= 0) {
            throw ToolError(Codes.INVALID_NUMERIC, "$fieldName: must be a positive integer: '$value'")
        }
        return v
    }

    fun requireIn(value: String, allowed: Set<String>, fieldName: String): String {
        if (value !in allowed) {
            throw ToolError(Codes.INVALID_ENUM, "$fieldName: value '$value' not in ${allowed.sorted().joinToString(",")}")
        }
        return value
    }

    fun splitList(value: String, fieldName: String): List<String> {
        val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            throw ToolError(Codes.INVALID_ENUM, "$fieldName: expected a comma-separated list, got '$value'")
        }
        return parts
    }
}

/** The exact ordered CSV column schemas for the T18.1 schema version 1.0. */
internal object TrialsSchema {
    val COLUMNS: List<String> = listOf(
        "trial_id", "scene", "condition", "arm", "repetition", "capture_order",
        "outcome", "exclusion_reason", "failure_reason",
        "original_path", "original_hash_sha256", "original_byte_size",
        "original_width", "original_height", "original_format",
        "review_source_path", "review_source_hash_sha256", "review_source_byte_size",
        "device", "app_version", "camera_identifier", "output_format", "output_resolution",
        "exif_make", "exif_model", "exif_orientation",
        "latency_ms", "memory_kb", "output_bytes", "thermal_state", "route",
        "fixture", "focus_state", "light_level", "motion_state",
        "provenance", "consent", "publication_permission",
    )
}

internal object ComparisonPlanSchema {
    val COLUMNS: List<String> = listOf("comparison_id", "arm_a", "arm_b", "purpose")
}

internal object CropsSchema {
    val COLUMNS: List<String> = listOf(
        "trial_id", "crop_id", "crop_purpose", "rect_x0", "rect_y0", "rect_x1", "rect_y1",
    )
}

/**
 * Loads a dataset directory. Structural failures (missing files, unknown
 * columns, unsupported schema version) throw a [ToolError] with a stable code.
 */
internal object DatasetModel {

    private const val PROP_SCHEMA_VERSION = "schema_version"
    private val PROP_REQUIRED: List<String> = listOf(
        "schema_version", "dataset_version", "contract_version", "dataset_kind",
        "capture_protocol", "declared_arms", "required_repetitions",
        "app_identifier", "baseline_identifier", "privacy_classification",
        "predeclared_hypothesis", "critical_scenes", "guardrails",
    )
    private val PROP_OPTIONAL: List<String> = listOf("candidate_identifier", "allow_shared_originals")
    private val PROP_KEYS: Set<String> = (PROP_REQUIRED + PROP_OPTIONAL).toSet()

    /** Placeholder used only when a structural load failure is reported as an issue. */
    fun emptyAt(root: Path): Dataset = Dataset(
        root = root,
        props = DatasetProperties(
            schemaVersion = TOOL_SCHEMA_VERSION,
            datasetVersion = "",
            contractVersion = "",
            kind = DatasetKind.CALIBRATION,
            captureProtocol = "",
            declaredArms = emptyList(),
            requiredRepetitions = 1,
            appIdentifier = "",
            baselineIdentifier = "",
            candidateIdentifier = null,
            privacyClassification = "",
            predeclaredHypothesis = "",
            criticalScenes = emptyList(),
            guardrails = emptyList(),
            allowSharedOriginals = false,
        ),
        trials = emptyList(),
        comparisons = emptyList(),
        crops = emptyList(),
        comparisonPlanHash = "",
    )

    fun load(root: Path): Dataset {
        if (!Files.isDirectory(root)) {
            throw ToolError(Codes.DATASET_ROOT, "dataset root is not a directory: '$root'")
        }
        val propsPath = root.resolve("dataset.properties")
        if (!Files.isRegularFile(propsPath)) {
            throw ToolError(Codes.DATASET_PROPERTIES_MISSING, "missing dataset.properties under '$root'")
        }
        val map = StrictProperties.read(propsPath)
        val unknownKeys = map.entries.keys.filter { it !in PROP_KEYS }
        if (unknownKeys.isNotEmpty()) {
            throw ToolError(Codes.PROP_UNKNOWN_KEY, "dataset.properties: unknown keys=[${unknownKeys.sorted().joinToString(",")}]")
        }
        for (key in PROP_REQUIRED) {
            map.require(key)
        }
        val schemaVersion = map.require(PROP_SCHEMA_VERSION)
        if (schemaVersion != TOOL_SCHEMA_VERSION) {
            throw ToolError(Codes.SCHEMA_UNSUPPORTED_VERSION, "unsupported schema_version '$schemaVersion' (supported: $TOOL_SCHEMA_VERSION)")
        }
        val kind = enumValue(map.require("dataset_kind"), DatasetKind.values(), "dataset_kind")
        val arms = Values.splitList(map.require("declared_arms"), "declared_arms")
        if (arms.size != arms.toSet().size) {
            throw ToolError(Codes.DUPLICATE_ARM, "declared_arms contains duplicate arm names")
        }
        val requiredRepetitions = Values.parsePositiveInt(map.require("required_repetitions"), "required_repetitions")
        val privacy = Values.requireIn(map.require("privacy_classification"), Values.PRIVACY_VALUES, "privacy_classification")
        val candidateId = map.get("candidate_identifier")?.takeIf { it.isNotBlank() }
        if (kind == DatasetKind.CANDIDATE && candidateId == null) {
            throw ToolError(Codes.MISSING_CANDIDATE_ID, "dataset_kind=CANDIDATE requires candidate_identifier")
        }
        val allowShared = map.get("allow_shared_originals")?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> throw ToolError(Codes.INVALID_ENUM, "allow_shared_originals must be true or false, got '$it'")
            }
        } ?: false

        val props = DatasetProperties(
            schemaVersion = schemaVersion,
            datasetVersion = map.requireNonBlank("dataset_version"),
            contractVersion = map.requireNonBlank("contract_version"),
            kind = kind,
            captureProtocol = map.requireNonBlank("capture_protocol"),
            declaredArms = arms,
            requiredRepetitions = requiredRepetitions,
            appIdentifier = map.requireNonBlank("app_identifier"),
            baselineIdentifier = map.requireNonBlank("baseline_identifier"),
            candidateIdentifier = candidateId,
            privacyClassification = privacy,
            predeclaredHypothesis = map.requireNonBlank("predeclared_hypothesis"),
            criticalScenes = Values.splitList(map.require("critical_scenes"), "critical_scenes"),
            guardrails = Values.splitList(map.require("guardrails"), "guardrails"),
            allowSharedOriginals = allowShared,
        )

        val trialsPath = root.resolve("trials.csv")
        if (!Files.isRegularFile(trialsPath)) {
            throw ToolError(Codes.TRIALS_MISSING, "missing trials.csv under '$root'")
        }
        val trialsTable = Csv.read(trialsPath)
        CsvSchema("trials.csv", TrialsSchema.COLUMNS).validate(trialsTable)
        val trials = trialsTable.rows.map { rowToTrial(it, trialsTable.header) }

        val comparisonsPath = root.resolve("comparison-plan.csv")
        if (!Files.isRegularFile(comparisonsPath)) {
            throw ToolError(Codes.COMPARISON_PLAN_MISSING, "missing comparison-plan.csv under '$root'")
        }
        val comparisonsTable = Csv.read(comparisonsPath)
        CsvSchema("comparison-plan.csv", ComparisonPlanSchema.COLUMNS).validate(comparisonsTable)
        val comparisons = comparisonsTable.rows.map { row ->
            ComparisonPlanRow(
                comparisonId = cell(row, "comparison_id", comparisonsTable.header),
                armA = cell(row, "arm_a", comparisonsTable.header),
                armB = cell(row, "arm_b", comparisonsTable.header),
                purpose = enumValue(cell(row, "purpose", comparisonsTable.header), ComparisonPurpose.values(), "purpose"),
            )
        }

        val cropsPath = root.resolve("crops.csv")
        val crops = if (Files.isRegularFile(cropsPath)) {
            val cropsTable = Csv.read(cropsPath)
            CsvSchema("crops.csv", CropsSchema.COLUMNS).validate(cropsTable)
            cropsTable.rows.map { row ->
                Crop(
                    trialId = cell(row, "trial_id", cropsTable.header),
                    cropId = cell(row, "crop_id", cropsTable.header),
                    cropPurpose = cell(row, "crop_purpose", cropsTable.header),
                    x0 = doubleCell(row, "rect_x0", cropsTable.header),
                    y0 = doubleCell(row, "rect_y0", cropsTable.header),
                    x1 = doubleCell(row, "rect_x1", cropsTable.header),
                    y1 = doubleCell(row, "rect_y1", cropsTable.header),
                )
            }
        } else {
            emptyList()
        }

        val planHash = Hashes.sha256File(comparisonsPath)
        return Dataset(root, props, trials, comparisons, crops, planHash)
    }

    private fun rowToTrial(row: List<String>, header: List<String>): Trial {
        val outcome = enumValue(cell(row, "outcome", header), TrialOutcome.values(), "outcome")
        val originalPath = optionalCell(row, "original_path", header)
        val reviewSourcePath = optionalCell(row, "review_source_path", header)
        return Trial(
            trialId = cell(row, "trial_id", header),
            scene = cell(row, "scene", header),
            condition = cell(row, "condition", header),
            arm = cell(row, "arm", header),
            repetition = Values.parsePositiveInt(cell(row, "repetition", header), "repetition"),
            captureOrder = Values.parsePositiveInt(cell(row, "capture_order", header), "capture_order"),
            outcome = outcome,
            exclusionReason = optionalCell(row, "exclusion_reason", header),
            failureReason = optionalCell(row, "failure_reason", header),
            originalPath = originalPath,
            originalHashSha256 = optionalCell(row, "original_hash_sha256", header),
            originalByteSize = Values.parseOptionalLong(cell(row, "original_byte_size", header), "original_byte_size"),
            originalWidth = Values.parseOptionalInt(cell(row, "original_width", header), "original_width"),
            originalHeight = Values.parseOptionalInt(cell(row, "original_height", header), "original_height"),
            originalFormat = optionalCell(row, "original_format", header),
            reviewSourcePath = reviewSourcePath,
            reviewSourceHashSha256 = optionalCell(row, "review_source_hash_sha256", header),
            reviewSourceByteSize = Values.parseOptionalLong(cell(row, "review_source_byte_size", header), "review_source_byte_size"),
            device = optionalCell(row, "device", header) ?: "",
            appVersion = optionalCell(row, "app_version", header) ?: "",
            cameraIdentifier = optionalCell(row, "camera_identifier", header) ?: "",
            outputFormat = optionalCell(row, "output_format", header) ?: "",
            outputResolution = optionalCell(row, "output_resolution", header) ?: "",
            exifMake = optionalCell(row, "exif_make", header),
            exifModel = optionalCell(row, "exif_model", header),
            exifOrientation = optionalCell(row, "exif_orientation", header),
            latencyMs = Values.parseOptionalDouble(cell(row, "latency_ms", header), "latency_ms"),
            memoryKb = Values.parseOptionalDouble(cell(row, "memory_kb", header), "memory_kb"),
            outputBytes = Values.parseOptionalDouble(cell(row, "output_bytes", header), "output_bytes"),
            thermalState = optionalCell(row, "thermal_state", header),
            route = optionalCell(row, "route", header),
            fixture = optionalCell(row, "fixture", header) ?: "",
            focusState = optionalCell(row, "focus_state", header) ?: "",
            lightLevel = optionalCell(row, "light_level", header) ?: "",
            motionState = optionalCell(row, "motion_state", header) ?: "",
            provenance = cell(row, "provenance", header),
            consent = cell(row, "consent", header),
            publicationPermission = cell(row, "publication_permission", header),
        )
    }

    private fun cell(row: List<String>, name: String, header: List<String>): String {
        val idx = header.indexOf(name)
        return row[idx]
    }

    private fun optionalCell(row: List<String>, name: String, header: List<String>): String? {
        val idx = header.indexOf(name)
        val value = row[idx].trim()
        return value.ifEmpty { null }
    }

    private fun doubleCell(row: List<String>, name: String, header: List<String>): Double {
        val idx = header.indexOf(name)
        val value = row[idx].trim()
        val parsed = value.toDoubleOrNull()
            ?: throw ToolError(Codes.INVALID_NUMERIC, "$name: not a number: '$value'")
        if (!parsed.isFinite()) {
            throw ToolError(Codes.INVALID_NUMERIC, "$name: value must be finite: '$value'")
        }
        return parsed
    }

    private fun <T : Enum<T>> enumValue(value: String, values: Array<T>, fieldName: String): T {
        for (v in values) {
            if (v.name == value) return v
        }
        throw ToolError(Codes.INVALID_ENUM, "$fieldName: value '$value' not in ${values.joinToString(",") { it.name }}")
    }
}
