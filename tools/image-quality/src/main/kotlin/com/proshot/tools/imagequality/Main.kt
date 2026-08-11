package com.proshot.tools.imagequality

import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

const val TOOL_NAME = "proshot-image-quality"
const val TOOL_SCHEMA_VERSION = "1.0"
const val TOOL_MAIN_CLASS = "com.proshot.tools.imagequality.MainKt"

internal enum class ToolExitCode(val value: Int) {
    SUCCESS(0),
    USAGE(1),
    GATE(2),
    IO(3),
}

internal object Codes {
    const val USAGE_UNKNOWN_COMMAND = "USAGE_UNKNOWN_COMMAND"
    const val USAGE_BAD_ARGS = "USAGE_BAD_ARGS"
    const val USAGE_MISSING_OPTION = "USAGE_MISSING_OPTION"
    const val USAGE_UNKNOWN_OPTION = "USAGE_UNKNOWN_OPTION"
    const val USAGE_BAD_INTEGER = "USAGE_BAD_INTEGER"
    const val USAGE_BAD_PATH = "USAGE_BAD_PATH"
    const val BAD_TIMESTAMP = "BAD_TIMESTAMP"
    const val BAD_HEX = "BAD_HEX"
    const val BAD_SEED = "BAD_SEED"

    const val CSV_EMPTY = "CSV_EMPTY"
    const val CSV_INVALID_UTF8 = "CSV_INVALID_UTF8"
    const val CSV_MALFORMED = "CSV_MALFORMED"
    const val CSV_HEADER = "CSV_HEADER"
    const val CSV_DUPLICATE_COLUMN = "CSV_DUPLICATE_COLUMN"
    const val CSV_HEADER_ORDER = "CSV_HEADER_ORDER"
    const val CSV_ROW_ARITY = "CSV_ROW_ARITY"

    const val PROP_MISSING_KEY = "PROP_MISSING_KEY"
    const val PROP_UNKNOWN_KEY = "PROP_UNKNOWN_KEY"
    const val PROP_MALFORMED = "PROP_MALFORMED"
    const val PROP_DUPLICATE_KEY = "PROP_DUPLICATE_KEY"

    const val INVALID_ENUM = "INVALID_ENUM"
    const val INVALID_NUMERIC = "INVALID_NUMERIC"
    const val DUPLICATE_ARM = "DUPLICATE_ARM"
    const val MISSING_CANDIDATE_ID = "MISSING_CANDIDATE_ID"
    const val SCHEMA_UNSUPPORTED_VERSION = "SCHEMA_UNSUPPORTED_VERSION"

    const val AA_SAME_ARM = "AA_SAME_ARM"
    const val INCONSISTENT_ROLE = "INCONSISTENT_ROLE"

    const val DATASET_ROOT = "DATASET_ROOT"
    const val DATASET_PROPERTIES_MISSING = "DATASET_PROPERTIES_MISSING"
    const val TRIALS_MISSING = "TRIALS_MISSING"
    const val COMPARISON_PLAN_MISSING = "COMPARISON_PLAN_MISSING"
    const val VALIDATION_FAILED = "VALIDATION_FAILED"

    const val PATH_EMPTY = "PATH_EMPTY"
    const val PATH_ABSOLUTE = "PATH_ABSOLUTE"
    const val PATH_ESCAPE = "PATH_ESCAPE"
    const val PATH_ESCAPE_RESOLVED = "PATH_ESCAPE_RESOLVED"
    const val PATH_IO = "PATH_IO"
    const val PATH_MISSING = "PATH_MISSING"
    const val HASH_MISMATCH = "HASH_MISMATCH"
    const val BYTE_SIZE_MISMATCH = "BYTE_SIZE_MISMATCH"
    const val DUPLICATE_ORIGINAL = "DUPLICATE_ORIGINAL"
    const val DUPLICATE_TRIAL_ID = "DUPLICATE_TRIAL_ID"
    const val DUPLICATE_GRAIN = "DUPLICATE_GRAIN"
    const val DUPLICATE_COMPARISON_ID = "DUPLICATE_COMPARISON_ID"
    const val DUPLICATE_CROP_ID = "DUPLICATE_CROP_ID"
    const val UNKNOWN_ARM = "UNKNOWN_ARM"
    const val OUTCOME_CONTRADICTION = "OUTCOME_CONTRADICTION"
    const val CONSENT_CONTRADICTION = "CONSENT_CONTRADICTION"
    const val SCENE_COVERAGE = "SCENE_COVERAGE"
    const val ARM_COVERAGE = "ARM_COVERAGE"
    const val REPETITION_COVERAGE = "REPETITION_COVERAGE"
    const val COMPARISON_INCOMPLETE = "COMPARISON_INCOMPLETE"
    const val SELF_PAIR = "SELF_PAIR"
    const val CROP_ORPHAN = "CROP_ORPHAN"
    const val CROP_INVALID_RECT = "CROP_INVALID_RECT"

    const val REVIEW_SOURCE_UNDECODABLE = "REVIEW_SOURCE_UNDECODABLE"
    const val ORIENTATION_NOT_NORMALIZED = "ORIENTATION_NOT_NORMALIZED"
    const val CROP_EMPTY = "CROP_EMPTY"

    const val PNG_BAD_SIGNATURE = "PNG_BAD_SIGNATURE"
    const val PNG_TRUNCATED = "PNG_TRUNCATED"
    const val PNG_CRC = "PNG_CRC"
    const val PNG_ORDER = "PNG_ORDER"
    const val PNG_NO_IHDR = "PNG_NO_IHDR"
    const val PNG_NO_IDAT = "PNG_NO_IDAT"
    const val PNG_NO_IEND = "PNG_NO_IEND"
    const val PNG_ANCILLARY = "PNG_ANCILLARY"
    const val IMG_WRITER = "IMG_WRITER"
    const val IMG_ENCODE = "IMG_ENCODE"

    const val OUT_DIR_NOT_EMPTY = "OUT_DIR_NOT_EMPTY"
    const val OUT_PATH_EXISTS = "OUT_PATH_EXISTS"
    const val KEY_INSIDE_PACKAGE = "KEY_INSIDE_PACKAGE"
    const val NO_COMPARISON_PAIRS = "NO_COMPARISON_PAIRS"
    const val PRIVACY_LEAK = "PRIVACY_LEAK"

    const val MANIFEST_MISSING = "MANIFEST_MISSING"
    const val MANIFEST_INVALID = "MANIFEST_INVALID"
    const val MANIFEST_PAIR_COUNT = "MANIFEST_PAIR_COUNT"
    const val MANIFEST_PAIR_ORDER = "MANIFEST_PAIR_ORDER"
    const val MANIFEST_ASSET_KEY = "MANIFEST_ASSET_KEY"
    const val MANIFEST_ASSET_PATH = "MANIFEST_ASSET_PATH"
    const val MANIFEST_EXTRA_FILE = "MANIFEST_EXTRA_FILE"
    const val MANIFEST_MISSING_FILE = "MANIFEST_MISSING_FILE"
    const val MANIFEST_SYMLINK = "MANIFEST_SYMLINK"
    const val MANIFEST_DIRECTORY = "MANIFEST_DIRECTORY"
    const val SCHEMA_MISMATCH = "SCHEMA_MISMATCH"
    const val PLAN_TAMPERED = "PLAN_TAMPERED"
    const val PACKAGE_TAMPERED = "PACKAGE_TAMPERED"
    const val KEY_MISSING = "KEY_MISSING"
    const val KEY_INVALID = "KEY_INVALID"
    const val KEY_MISMATCH = "KEY_MISMATCH"
    const val KEY_PAIR_SET_MISMATCH = "KEY_PAIR_SET_MISMATCH"
    const val KEY_ASSET_MAPPING = "KEY_ASSET_MAPPING"
    const val KEY_TRIAL_SELF = "KEY_TRIAL_SELF"
    const val DATASET_MISMATCH = "DATASET_MISMATCH"

    const val SEAL_MISSING = "SEAL_MISSING"
    const val SEAL_INVALID = "SEAL_INVALID"
    const val SEAL_BAD_CUSTODY = "SEAL_BAD_CUSTODY"
    const val SEAL_DUPLICATE_PATH = "SEAL_DUPLICATE_PATH"
    const val SEAL_DUPLICATE_RESPONSE_HASH = "SEAL_DUPLICATE_RESPONSE_HASH"
    const val SEAL_DUPLICATE_REVIEWER = "SEAL_DUPLICATE_REVIEWER"
    const val SEAL_RESPONSE_MISSING = "SEAL_RESPONSE_MISSING"
    const val SEAL_RESPONSE_BAD_PACKAGE = "SEAL_RESPONSE_BAD_PACKAGE"
    const val SEAL_RESPONSE_UNKNOWN_PAIR = "SEAL_RESPONSE_UNKNOWN_PAIR"
    const val SEAL_RESPONSE_DUPLICATE_PAIR = "SEAL_RESPONSE_DUPLICATE_PAIR"
    const val SEAL_RESPONSE_INCOMPLETE = "SEAL_RESPONSE_INCOMPLETE"
    const val SEAL_RESPONSE_CHOICE = "SEAL_RESPONSE_CHOICE"
    const val SEAL_RESPONSE_TAG = "SEAL_RESPONSE_TAG"
    const val SEAL_RESPONSE_DEFECT = "SEAL_RESPONSE_DEFECT"
    const val RESPONSE_TAMPERED = "RESPONSE_TAMPERED"

    const val THRESHOLD_MISSING = "THRESHOLD_MISSING"
    const val THRESHOLD_INVALID = "THRESHOLD_INVALID"
    const val THRESHOLD_SELF_HASH = "THRESHOLD_SELF_HASH"
    const val THRESHOLD_CONTRACT_MISMATCH = "THRESHOLD_CONTRACT_MISMATCH"
    const val THRESHOLD_BASELINE_MISMATCH = "THRESHOLD_BASELINE_MISMATCH"
    const val THRESHOLD_CALIBRATION_EVIDENCE = "THRESHOLD_CALIBRATION_EVIDENCE"
    const val THRESHOLD_CRITICAL_SCENE_MISMATCH = "THRESHOLD_CRITICAL_SCENE_MISMATCH"
    const val THRESHOLD_POST_UNBLIND = "THRESHOLD_POST_UNBLIND"

    const val LOCK_EVIDENCE_MISMATCH = "LOCK_EVIDENCE_MISMATCH"
    const val LOCK_MISSING_FIELD = "LOCK_MISSING_FIELD"
    const val LOCK_INVALID_RULE = "LOCK_INVALID_RULE"

    const val FILE_READ = "FILE_READ"
    const val FILE_WRITE = "FILE_WRITE"
}

internal class ToolError(
    val code: String,
    message: String,
    val exit: ToolExitCode = ToolExitCode.GATE,
) : Exception(message)

fun main(args: Array<String>) {
    exitProcess(runCli(args))
}

internal fun runCli(args: Array<String>): Int {
    if (args.isEmpty()) {
        printUsage()
        return ToolExitCode.USAGE.value
    }
    val command = args[0]
    val tokens = args.drop(1)
    return try {
        when (command) {
            "validate" -> validateCommand(parseArgs(tokens, VALIDATE_FLAGS))
            "blind" -> blindCommand(parseArgs(tokens, BLIND_FLAGS))
            "seal-review" -> sealReviewCommand(parseArgs(tokens, SEAL_FLAGS))
            "analyze" -> analyzeCommand(parseArgs(tokens, ANALYZE_FLAGS, multi = setOf("seal")))
            "lock-thresholds" -> lockThresholdsCommand(parseArgs(tokens, LOCK_FLAGS))
            "help" -> {
                printUsage()
                ToolExitCode.SUCCESS.value
            }
            else -> {
                System.err.println("ERROR ${Codes.USAGE_UNKNOWN_COMMAND}: unknown command '$command'")
                ToolExitCode.USAGE.value
            }
        }
    } catch (e: ToolError) {
        System.err.println("ERROR ${e.code}: ${e.message}")
        e.exit.value
    } catch (e: IOException) {
        System.err.println("ERROR ${Codes.FILE_READ}: ${e.message}")
        ToolExitCode.IO.value
    }
}

private val VALIDATE_FLAGS = setOf("root", "out-dir")
private val BLIND_FLAGS = setOf("root", "out-dir", "key", "seed", "display-max-dimension")
private val SEAL_FLAGS = setOf("package", "responses", "out", "reviewer", "category", "conflict", "utc-timestamp")
private val ANALYZE_FLAGS = setOf("package", "key", "root", "out-dir", "seal", "threshold", "utc-timestamp")
private val LOCK_FLAGS = setOf("template", "out", "utc-timestamp")

private class CliArgs(private val map: Map<String, List<String>>) {
    fun get(name: String): String? = map[name]?.lastOrNull()

    fun all(name: String): List<String> = map[name] ?: emptyList()

    fun require(name: String): String = get(name)
        ?: throw ToolError(Codes.USAGE_MISSING_OPTION, "missing required option --$name", ToolExitCode.USAGE)

    fun getInt(name: String, default: Int): Int {
        val value = get(name) ?: return default
        return value.toIntOrNull()
            ?: throw ToolError(Codes.USAGE_BAD_INTEGER, "--$name expects an integer, got '$value'", ToolExitCode.USAGE)
    }

    fun getPath(name: String): Path = pathOf(require(name), name)

    fun allPaths(name: String): List<Path> = all(name).map { pathOf(it, name) }
}

/** Malformed command paths must fail with a stable error, never a raw JVM path exception. */
private fun pathOf(value: String, option: String): Path = try {
    Path.of(value)
} catch (e: InvalidPathException) {
    throw ToolError(
        Codes.USAGE_BAD_PATH,
        "--$option: malformed path '$value': ${e.message}",
        ToolExitCode.USAGE,
    )
}

private fun parseArgs(tokens: List<String>, allowed: Set<String>, multi: Set<String> = emptySet()): CliArgs {
    val map = LinkedHashMap<String, MutableList<String>>()
    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        if (!token.startsWith("--")) {
            throw ToolError(Codes.USAGE_BAD_ARGS, "unexpected argument '$token'", ToolExitCode.USAGE)
        }
        val name = token.substring(2)
        if (name !in allowed) {
            throw ToolError(Codes.USAGE_UNKNOWN_OPTION, "unknown option '--$name'", ToolExitCode.USAGE)
        }
        val value = tokens.getOrNull(i + 1)
            ?: throw ToolError(Codes.USAGE_MISSING_OPTION, "missing value for '--$name'", ToolExitCode.USAGE)
        if (value.startsWith("--")) {
            throw ToolError(Codes.USAGE_MISSING_OPTION, "missing value for '--$name'", ToolExitCode.USAGE)
        }
        if (map.containsKey(name) && name !in multi) {
            throw ToolError(Codes.USAGE_BAD_ARGS, "duplicate option '--$name'", ToolExitCode.USAGE)
        }
        map.getOrPut(name) { mutableListOf() }.add(value)
        i += 2
    }
    return CliArgs(map)
}

private fun validateCommand(a: CliArgs): Int {
    val root = a.getPath("root")
    val outDir = a.getPath("out-dir")
    return DatasetValidator.runValidate(root, outDir)
}

private fun blindCommand(a: CliArgs): Int {
    val root = a.getPath("root")
    val outDir = a.getPath("out-dir")
    val keyPath = a.getPath("key")
    val seed = a.get("seed")
    val maxDim = a.getInt("display-max-dimension", 1200)
    if (maxDim <= 0) {
        throw ToolError(
            Codes.USAGE_BAD_INTEGER,
            "--display-max-dimension must be a positive integer, got '$maxDim'",
            ToolExitCode.USAGE,
        )
    }
    ReviewPackage.runBlind(root, outDir, keyPath, seed, maxDim)
    return ToolExitCode.SUCCESS.value
}

private fun sealReviewCommand(a: CliArgs): Int {
    ReviewAnalysis.runSealReview(
        packageDir = a.getPath("package"),
        responsesPath = a.getPath("responses"),
        outPath = a.getPath("out"),
        reviewer = a.require("reviewer"),
        category = a.require("category"),
        conflict = a.require("conflict"),
        timestamp = a.get("utc-timestamp"),
    )
    return ToolExitCode.SUCCESS.value
}

private fun analyzeCommand(a: CliArgs): Int {
    val seals = a.allPaths("seal")
    if (seals.isEmpty()) {
        throw ToolError(Codes.USAGE_MISSING_OPTION, "analyze requires at least one --seal", ToolExitCode.USAGE)
    }
    ReviewAnalysis.runAnalyze(
        packageDir = a.getPath("package"),
        keyPath = a.getPath("key"),
        datasetRoot = a.getPath("root"),
        outDir = a.getPath("out-dir"),
        sealPaths = seals,
        thresholdPath = a.get("threshold")?.let { pathOf(it, "threshold") },
        timestamp = a.get("utc-timestamp"),
    )
    return ToolExitCode.SUCCESS.value
}

private fun lockThresholdsCommand(a: CliArgs): Int {
    ThresholdLock.runLock(
        templatePath = a.getPath("template"),
        outPath = a.getPath("out"),
        timestamp = a.get("utc-timestamp"),
    )
    return ToolExitCode.SUCCESS.value
}

private fun printUsage() {
    println("ProShot offline image-quality harness (dependency-light Kotlin/JVM).")
    println()
    println("Usage: $TOOL_NAME <command> [options]")
    println()
    println("Commands:")
    println("  validate        Validate a dataset against the strict schemas and evidence gate.")
    println("                  --root <dataset-dir> --out-dir <dir>")
    println("  blind           Create a source-neutral reviewer package and private key.")
    println("                  --root <dataset-dir> --out-dir <package-dir> --key <key-file>")
    println("                  [--seed <hex>] [--display-max-dimension <px>]")
    println("  seal-review     Seal one reviewer response file before unblinding.")
    println("                  --package <dir> --responses <csv> --out <seal-file>")
    println("                  --reviewer <id> --category <text> --conflict NONE|DECLARED")
    println("                  [--utc-timestamp <iso-utc>]")
    println("  analyze         Verify every hash, unblind, and produce the report.")
    println("                  --package <dir> --key <key-file> --root <dataset-dir> --out-dir <dir>")
    println("                  --seal <seal-file> [--seal <seal-file> ...] [--threshold <lock-file>]")
    println("                  [--utc-timestamp <iso-utc>]")
    println("  lock-thresholds Validate a human-reviewed threshold draft and write the lock.")
    println("                  --template <draft-file> --out <lock-file> [--utc-timestamp <iso-utc>]")
    println()
    println("Exit codes: 0 success, 1 usage, 2 evidence/schema/integrity failure, 3 I/O error.")
}
