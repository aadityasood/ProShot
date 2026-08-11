package com.proshot.tools.imagequality

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A parsed CSV table with an ordered header and ordered rows of string cells.
 *
 * The harness treats every CSV cell as raw text. Numeric and enumeration
 * interpretation happens in the model layer so that the strings `0`,
 * `unknown`, and `n/a` are never silently coerced into measurements.
 */
internal class CsvTable(val header: List<String>, val rows: List<List<String>>) {
    fun columnIndex(name: String): Int = header.indexOf(name)
}

/**
 * Declares the exact, ordered column list of a schema-backed CSV file. The
 * header must match this list exactly: unknown, missing, reordered, or
 * duplicate columns and wrong-arity rows all fail closed.
 */
internal class CsvSchema(val name: String, val columns: List<String>) {
    fun validate(table: CsvTable) {
        val header = table.header
        if (header.size != header.toSet().size) {
            val dupes = header.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            throw ToolError(
                Codes.CSV_DUPLICATE_COLUMN,
                "$name: duplicate header columns: ${dupes.joinToString(",")}",
            )
        }
        if (header.toSet() != columns.toSet()) {
            val unknown = header.filter { it !in columns }
            val missing = columns.filter { it !in header }
            throw ToolError(
                Codes.CSV_HEADER,
                "$name: unknown columns=[${unknown.joinToString(",")}] missing columns=[${missing.joinToString(",")}]",
            )
        }
        if (header != columns) {
            throw ToolError(Codes.CSV_HEADER_ORDER, "$name: header order does not match schema")
        }
        for ((idx, row) in table.rows.withIndex()) {
            if (row.size != columns.size) {
                throw ToolError(
                    Codes.CSV_ROW_ARITY,
                    "$name: row ${idx + 2} has ${row.size} fields, expected ${columns.size}",
                )
            }
        }
    }
}

/**
 * Dependency-free strict RFC-4180-style CSV reader/writer plus the shared
 * UTF-8 text loader used by the properties files.
 */
internal object Csv {

    fun readUtf8(path: Path): String {
        val bytes = try {
            Files.readAllBytes(path)
        } catch (e: IOException) {
            throw ToolError(Codes.FILE_READ, "cannot read '$path': ${e.message}", ToolExitCode.IO)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw ToolError(Codes.CSV_INVALID_UTF8, "'$path' is not valid UTF-8")
        }
    }

    fun read(path: Path): CsvTable = parse(readUtf8(path))

    /**
     * Parses strict RFC-4180 text. Quoted fields may contain commas, CR/LF,
     * and doubled quotes. A quote outside a quoted field, an unterminated
     * quoted field, or a non-delimiter character immediately after a closing
     * quote is malformed and fails closed.
     *
     * Optional trailing empty fields are preserved exactly:
     * - `a,b,` yields three fields (including at end-of-input without a newline);
     * - `a,b,\r\n` yields three fields;
     * - `a,"",` yields three fields;
     * - a terminal record delimiter never creates an additional phantom record.
     */
    fun parse(text: String): CsvTable {
        if (text.isEmpty()) {
            throw ToolError(Codes.CSV_EMPTY, "empty CSV input")
        }
        val records = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldOpen = false
        var afterClosingQuote = false
        var lastWasDelimiter = false
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    }
                    inQuotes = false
                    afterClosingQuote = true
                    i += 1
                    continue
                }
                field.append(c)
                i += 1
                continue
            }
            if (afterClosingQuote) {
                when (c) {
                    ',' -> {
                        fields.add(field.toString()); field.setLength(0)
                        fieldOpen = false; lastWasDelimiter = true; afterClosingQuote = false; i += 1
                    }
                    '\r', '\n' -> {
                        fields.add(field.toString()); field.setLength(0)
                        fieldOpen = false; lastWasDelimiter = false; afterClosingQuote = false
                        records.add(fields); fields = mutableListOf()
                        i += if (c == '\r' && i + 1 < n && text[i + 1] == '\n') 2 else 1
                    }
                    else -> throw ToolError(Codes.CSV_MALFORMED, "unexpected character after closing quote at offset $i")
                }
                continue
            }
            when (c) {
                '"' -> {
                    if (fieldOpen) {
                        throw ToolError(Codes.CSV_MALFORMED, "quote inside unquoted field at offset $i")
                    }
                    inQuotes = true
                    fieldOpen = true
                    lastWasDelimiter = false
                    i += 1
                }
                ',' -> {
                    fields.add(field.toString()); field.setLength(0)
                    fieldOpen = false; lastWasDelimiter = true; i += 1
                }
                '\r', '\n' -> {
                    fields.add(field.toString()); field.setLength(0)
                    fieldOpen = false; lastWasDelimiter = false
                    records.add(fields); fields = mutableListOf()
                    i += if (c == '\r' && i + 1 < n && text[i + 1] == '\n') 2 else 1
                }
                else -> {
                    field.append(c); fieldOpen = true; lastWasDelimiter = false; i += 1
                }
            }
        }
        if (inQuotes) {
            throw ToolError(Codes.CSV_MALFORMED, "unterminated quoted field")
        }
        when {
            lastWasDelimiter -> {
                fields.add("")
                records.add(fields)
            }
            fieldOpen || fields.isNotEmpty() -> {
                fields.add(field.toString())
                records.add(fields)
            }
            records.isEmpty() -> throw ToolError(Codes.CSV_EMPTY, "empty CSV input")
        }
        if (records.isEmpty()) {
            throw ToolError(Codes.CSV_EMPTY, "empty CSV input")
        }
        return CsvTable(records.first(), records.drop(1))
    }

    fun serialize(header: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        appendRecord(sb, header)
        for (row in rows) {
            appendRecord(sb, row)
        }
        return sb.toString()
    }

    fun write(path: Path, header: List<String>, rows: List<List<String>>) {
        val bytes = serialize(header, rows).toByteArray(StandardCharsets.UTF_8)
        try {
            Files.write(path, bytes)
        } catch (e: IOException) {
            throw ToolError(Codes.FILE_WRITE, "cannot write '$path': ${e.message}", ToolExitCode.IO)
        }
    }

    private fun appendRecord(sb: StringBuilder, fields: List<String>) {
        for ((i, f) in fields.withIndex()) {
            if (i > 0) sb.append(',')
            if (f.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                sb.append('"').append(f.replace("\"", "\"\"")).append('"')
            } else {
                sb.append(f)
            }
        }
        sb.append("\r\n")
    }
}

/**
 * Deterministic lowercase hex encoding/decoding.
 */
internal object Hex {
    private val CHARS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(CHARS[v ushr 4]).append(CHARS[v and 0x0F])
        }
        return out.toString()
    }

    fun decode(hex: String): ByteArray {
        if (hex.length % 2 != 0) {
            throw ToolError(Codes.BAD_HEX, "invalid hex string", ToolExitCode.USAGE)
        }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) {
                throw ToolError(Codes.BAD_HEX, "invalid hex string", ToolExitCode.USAGE)
            }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

/**
 * SHA-256 helpers for bytes, strings, files, and deterministic whole-directory
 * hashing (sorted relative paths, byte-delimited).
 */
internal object Hashes {
    fun sha256(data: ByteArray): String = Hex.encode(digestOf(data))

    fun sha256(text: String): String = sha256(text.toByteArray(StandardCharsets.UTF_8))

    fun sha256Bytes(text: String): ByteArray = digestOf(text.toByteArray(StandardCharsets.UTF_8))

    fun sha256File(path: Path): String {
        val digest = MessageDigestNew.sha256()
        try {
            Files.newInputStream(path).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
        } catch (e: IOException) {
            throw ToolError(Codes.FILE_READ, "cannot hash '$path': ${e.message}", ToolExitCode.IO)
        }
        return Hex.encode(digest.digest())
    }

    /**
     * Deterministic whole-directory hashing: sorted relative paths with
     * backslashes normalized to `/`, each path NUL-delimited, then each file
     * streamed through the digest in bounded chunks (never loaded whole into
     * memory) with a NUL terminator between file contents.
     */
    fun sha256Directory(root: Path): String {
        val files = try {
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .map { path ->
                        root.relativize(path).toString().replace('\\', '/') to path
                    }
                    .sorted { a, b -> a.first.compareTo(b.first) }
                    .toList()
            }
        } catch (e: IOException) {
            throw ToolError(Codes.FILE_READ, "cannot walk '$root': ${e.message}", ToolExitCode.IO)
        }
        val digest = MessageDigestNew.sha256()
        val buf = ByteArray(64 * 1024)
        for ((rel, f) in files) {
            digest.update(rel.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            try {
                Files.newInputStream(f).use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
            } catch (e: IOException) {
                throw ToolError(Codes.FILE_READ, "cannot hash '$f': ${e.message}", ToolExitCode.IO)
            }
            digest.update(0)
        }
        return Hex.encode(digest.digest())
    }

    private fun digestOf(data: ByteArray): ByteArray = MessageDigestNew.sha256().apply { update(data) }.digest()

    private object MessageDigestNew {
        fun sha256(): java.security.MessageDigest = java.security.MessageDigest.getInstance("SHA-256")
    }
}

/**
 * UTC ISO-8601 timestamps with an optional explicit override for deterministic
 * tests. All artifacts use the canonical `yyyy-MM-dd'T'HH:mm:ss'Z'` form.
 */
internal object UtcClock {
    private val FMT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun now(): String = FMT.format(java.time.Instant.now())

    fun canonical(value: String): String {
        val parsed = try {
            java.time.Instant.parse(value)
        } catch (e: Exception) {
            throw ToolError(Codes.BAD_TIMESTAMP, "invalid UTC timestamp '$value'", ToolExitCode.USAGE)
        }
        return FMT.format(parsed)
    }

    /** Bounds-checked UTC instant parse; malformed values fail with a stable [Codes.BAD_TIMESTAMP]. */
    fun parseInstant(value: String): java.time.Instant = try {
        java.time.Instant.parse(value)
    } catch (e: Exception) {
        throw ToolError(Codes.BAD_TIMESTAMP, "invalid UTC timestamp '$value'", ToolExitCode.USAGE)
    }
}

/**
 * Fail-closed path containment for every dataset input file.
 *
 * Sequence required by the brief:
 * 1. reject absolute paths;
 * 2. resolve and normalize against the declared dataset root;
 * 3. require lexical containment;
 * 4. obtain the real root with `root.toRealPath()`;
 * 5. obtain the candidate with `candidate.toRealPath()` using default
 *    link-following behavior;
 * 6. require the real candidate to remain under the real root.
 *
 * `NOFOLLOW_LINKS` is deliberately never used as a substitute for escape
 * detection.
 */
internal object PathSecurity {
    fun resolve(root: Path, rawRelative: String, purpose: String): Path {
        if (rawRelative.isBlank()) {
            throw ToolError(Codes.PATH_EMPTY, "$purpose: empty path")
        }
        val raw = try {
            Path.of(rawRelative)
        } catch (e: Exception) {
            throw ToolError(Codes.PATH_ABSOLUTE, "$purpose: invalid path '$rawRelative': ${e.message}")
        }
        if (raw.isAbsolute) {
            throw ToolError(Codes.PATH_ABSOLUTE, "$purpose: absolute path not allowed: '$rawRelative'")
        }
        val normalizedRoot = root.toAbsolutePath().normalize()
        val candidate = normalizedRoot.resolve(raw).normalize()
        if (!candidate.startsWith(normalizedRoot)) {
            throw ToolError(Codes.PATH_ESCAPE, "$purpose: path escapes dataset root: '$rawRelative'")
        }
        val realRoot = try {
            root.toRealPath()
        } catch (e: IOException) {
            throw ToolError(Codes.PATH_IO, "$purpose: cannot resolve real root '$root': ${e.message}", ToolExitCode.IO)
        }
        val realCandidate = try {
            candidate.toRealPath()
        } catch (e: IOException) {
            throw ToolError(Codes.PATH_IO, "$purpose: cannot resolve real path '$rawRelative': ${e.message}", ToolExitCode.IO)
        }
        if (!realCandidate.startsWith(realRoot)) {
            throw ToolError(
                Codes.PATH_ESCAPE_RESOLVED,
                "$purpose: resolved path escapes dataset root through links: '$rawRelative'",
            )
        }
        return realCandidate
    }

    fun isStrictlyUnder(child: Path, parent: Path): Boolean {
        val c = child.toAbsolutePath().normalize()
        val p = parent.toAbsolutePath().normalize()
        return c.startsWith(p) && c != p
    }
}
