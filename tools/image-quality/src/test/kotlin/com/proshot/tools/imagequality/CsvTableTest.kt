package com.proshot.tools.imagequality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class CsvTableTest {

    private fun parseText(text: String): CsvTable = Csv.parse(text)

    @Test
    fun quotedCommasQuotesAndNewlines() {
        val table = parseText("a,b\r\n\"x,y\",\"say \"\"hi\"\"\"\r\n1,\"line1\nline2\"\r\n")
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("x,y", "say \"hi\""), table.rows[0])
        assertEquals(listOf("1", "line1\nline2"), table.rows[1])
    }

    @Test
    fun acceptsLfAndCrlf() {
        val lf = parseText("a,b\n1,2\n")
        val crlf = parseText("a,b\r\n1,2\r\n")
        assertEquals(lf.rows, crlf.rows)
    }

    @Test
    fun trailingEmptyFieldPreserved() {
        val table = parseText("a,b\n1,\n")
        assertEquals(listOf("1", ""), table.rows[0])
    }

    @Test
    fun trailingEmptyFieldsPreservedWithAndWithoutFinalNewline() {
        // `a,b,` at end-of-input (no newline) must still yield three fields.
        val noNewline = parseText("a,b,")
        assertEquals(listOf("a", "b", ""), noNewline.header)
        // `a,b,\r\n` yields three fields and no phantom fourth record.
        val crlf = parseText("a,b,\r\n1,2,\r\n")
        assertEquals(listOf("a", "b", ""), crlf.header)
        assertEquals(listOf("1", "2", ""), crlf.rows[0])
        assertEquals(1, crlf.rows.size)
        // `a,"",` yields three fields with a quoted empty middle field.
        val quoted = parseText("a,\"\",")
        assertEquals(listOf("a", "", ""), quoted.header)
    }

    @Test
    fun terminalRecordDelimiterCreatesNoPhantomRecord() {
        val table = parseText("a,b\r\n1,2\r\n")
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(1, table.rows.size)
        assertEquals(listOf("1", "2"), table.rows[0])
    }

    @Test
    fun finalRecordWithoutTerminalNewlineIsPreservedExactly() {
        val unquoted = parseText("a,b\n1,2")
        assertEquals(listOf(listOf("1", "2")), unquoted.rows)

        val quoted = parseText("a,b\r\n\"x,y\",\"say \"\"hi\"\"\"")
        assertEquals(listOf(listOf("x,y", "say \"hi\"")), quoted.rows)

        val trailingEmpty = parseText("a,b,c,d\n1,2,,")
        assertEquals(listOf(listOf("1", "2", "", "")), trailingEmpty.rows)
    }

    @Test
    fun nanAndInfinityRejectedAsNumeric() {
        val error = assertThrows(ToolError::class.java) { Values.parseOptionalDouble("NaN", "latency_ms") }
        assertEquals(Codes.INVALID_NUMERIC, error.code)
        assertThrows(ToolError::class.java) { Values.parseOptionalDouble("Infinity", "latency_ms") }
        assertThrows(ToolError::class.java) { Values.parseOptionalDouble("-Infinity", "latency_ms") }
        assertThrows(ToolError::class.java) { Values.parseOptionalDouble("1e999", "latency_ms") }
        assertThrows(ToolError::class.java) { Values.parseOptionalDouble("12abc", "latency_ms") }
        // Empty stays an explicit unavailable marker, never a number.
        assertEquals(null, Values.parseOptionalDouble("", "latency_ms"))
    }

    @Test
    fun malformedQuoteFails() {
        assertThrows(ToolError::class.java) { parseText("a,b\n\"unterminated,1\n") }
        assertThrows(ToolError::class.java) { parseText("a,b\nx\"y,1\n") }
    }

    @Test
    fun emptyInputFails() {
        assertThrows(ToolError::class.java) { parseText("") }
    }

    @Test
    fun invalidUtf8Fails() {
        val path = Files.createTempFile("csv", ".csv")
        try {
            Files.write(path, byteArrayOf(0x61, 0x2C, 0x62, 0x0A, 0xFF.toByte(), 0xFE.toByte(), 0x0A))
            assertThrows(ToolError::class.java) { Csv.read(path) }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun schemaRejectsUnknownMissingDuplicateReorderedAndArity() {
        val schema = CsvSchema("t", listOf("a", "b"))
        // unknown column
        assertThrows(ToolError::class.java) { schema.validate(parseText("a,c\n1,2\n")) }
        // missing column
        assertThrows(ToolError::class.java) { schema.validate(parseText("a\n1\n")) }
        // duplicate column
        assertThrows(ToolError::class.java) { schema.validate(parseText("a,a\n1,2\n")) }
        // reordered columns
        assertThrows(ToolError::class.java) { schema.validate(parseText("b,a\n1,2\n")) }
        // wrong arity
        assertThrows(ToolError::class.java) { schema.validate(parseText("a,b\n1\n")) }
        // valid
        schema.validate(parseText("a,b\n1,2\n"))
    }

    @Test
    fun roundTripSerializeParseIsStable() {
        val header = listOf("a", "b")
        val rows = listOf(
            listOf("plain", "x,y"),
            listOf("quoted \" value", "line1\nline2"),
            listOf("", "trailing"),
        )
        val bytes = Csv.serialize(header, rows).toByteArray(StandardCharsets.UTF_8)
        val reparsed = Csv.parse(String(bytes, StandardCharsets.UTF_8))
        assertEquals(header, reparsed.header)
        assertEquals(rows, reparsed.rows)
    }

    @Test
    fun directoryHashUsesNormalizedRelativePathOrdering() {
        val root = Files.createTempDirectory("dir-hash-order")
        try {
            val files = linkedMapOf(
                "a.z" to "dot",
                "a/z" to "slash",
                "a0" to "digit",
            )
            for ((relative, content) in files) {
                val path = root.resolve(relative)
                Files.createDirectories(path.parent)
                Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
            }

            val digest = MessageDigest.getInstance("SHA-256")
            for (relative in listOf("a.z", "a/z", "a0")) {
                digest.update(relative.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
                digest.update(files.getValue(relative).toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
            }
            assertEquals(Hex.encode(digest.digest()), Hashes.sha256Directory(root))

            val secondRoot = Files.createTempDirectory("different-absolute-root")
            try {
                for ((relative, content) in files) {
                    val path = secondRoot.resolve(relative)
                    Files.createDirectories(path.parent)
                    Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
                }
                assertEquals(Hashes.sha256Directory(root), Hashes.sha256Directory(secondRoot))
            } finally {
                secondRoot.toFile().deleteRecursively()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun schemaVersionRejection() {
        val dir = Files.createTempDirectory("schema-ver")
        try {
            TestData.writeStandardDataset(dir, DatasetKind.CALIBRATION, TestData.calibrationComparisons())
            val props = StrictProperties.read(dir.resolve("dataset.properties")).entries.toMutableMap()
            props["schema_version"] = "9.9"
            StrictProperties.write(dir.resolve("dataset.properties"), props)
            val error = assertThrows(ToolError::class.java) { DatasetModel.load(dir) }
            assertEquals(Codes.SCHEMA_UNSUPPORTED_VERSION, error.code)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun propertiesStrictRules() {
        val dir = Files.createTempDirectory("props")
        try {
            val p = dir.resolve("p.properties")
            Files.write(p, "k1=v1\nk2=a=b\n# comment\n\n".toByteArray(StandardCharsets.UTF_8))
            val map = StrictProperties.read(p)
            assertEquals("v1", map.require("k1"))
            assertEquals("a=b", map.require("k2"))

            Files.write(p, "k1=v1\nk1=v2\n".toByteArray(StandardCharsets.UTF_8))
            assertThrows(ToolError::class.java) { StrictProperties.read(p) }

            Files.write(p, "nokey\n".toByteArray(StandardCharsets.UTF_8))
            assertThrows(ToolError::class.java) { StrictProperties.read(p) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
