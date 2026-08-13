package com.proshot.tools.imagequality

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Refuses a non-empty pre-existing output directory. */
internal fun guardOutDir(outDir: Path) {
    if (Files.exists(outDir)) {
        if (!Files.isDirectory(outDir)) {
            throw ToolError(Codes.OUT_DIR_NOT_EMPTY, "output path exists and is not a directory: '$outDir'")
        }
        try {
            Files.newDirectoryStream(outDir).use { stream ->
                if (stream.iterator().hasNext()) {
                    throw ToolError(Codes.OUT_DIR_NOT_EMPTY, "refusing to use non-empty output directory: '$outDir'")
                }
            }
        } catch (e: ToolError) {
            throw e
        } catch (e: IOException) {
            throw ToolError(Codes.FILE_READ, "cannot inspect '$outDir': ${e.message}", ToolExitCode.IO)
        }
    }
}

/** Refuses any pre-existing destination file that would otherwise be overwritten. */
internal fun guardNewFile(path: Path) {
    if (Files.exists(path)) {
        throw ToolError(Codes.OUT_PATH_EXISTS, "refusing to overwrite existing path: '$path'")
    }
}

/**
 * PNG utilities: metadata-free sRGB encoding through a fresh writer with no
 * inherited metadata, plus a strict chunk-stream validator that accepts only
 * `IHDR`, one or more `IDAT`, and `IEND` in valid order.
 */
internal object Png {

    private val SIGNATURE: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun toSrgb(image: BufferedImage): BufferedImage {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }

    fun renderScaled(image: BufferedImage, maxDim: Int): BufferedImage {
        val w = image.width
        val h = image.height
        val scale = minOf(1.0, maxDim.toDouble() / w, maxDim.toDouble() / h)
        if (scale >= 1.0) return toSrgb(image)
        val nw = max(1, (w * scale).toInt())
        val nh = max(1, (h * scale).toInt())
        val out = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, 0, 0, nw, nh, null)
        } finally {
            g.dispose()
        }
        return out
    }

    fun renderCrop(image: BufferedImage, x0: Double, y0: Double, x1: Double, y1: Double): BufferedImage {
        val px0 = floor(x0 * image.width).toInt().coerceIn(0, image.width - 1)
        val py0 = floor(y0 * image.height).toInt().coerceIn(0, image.height - 1)
        val px1 = ceil(x1 * image.width).toInt().coerceIn(0, image.width)
        val py1 = ceil(y1 * image.height).toInt().coerceIn(0, image.height)
        val w = px1 - px0
        val h = py1 - py0
        if (w <= 0 || h <= 0) {
            throw ToolError(Codes.CROP_EMPTY, "crop rectangle resolves to empty pixels")
        }
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.drawImage(image, 0, 0, w, h, px0, py0, px1, py1, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /**
     * Renders pixels into a fresh sRGB `BufferedImage` and writes with a fresh
     * PNG writer and null image metadata, then validates the chunk stream.
     */
    fun encodePng(image: BufferedImage): ByteArray {
        val srgb = toSrgb(image)
        val writers = ImageIO.getImageWritersByFormatName("png")
        if (!writers.hasNext()) {
            throw ToolError(Codes.IMG_WRITER, "no PNG writer available", ToolExitCode.IO)
        }
        val writer = writers.next()
        val baos = ByteArrayOutputStream()
        val ios = ImageIO.createImageOutputStream(baos)
        try {
            writer.output = ios
            writer.write(null, IIOImage(srgb, null, null), null)
        } catch (e: IOException) {
            throw ToolError(Codes.IMG_ENCODE, "PNG encoding failed: ${e.message}", ToolExitCode.IO)
        } finally {
            writer.dispose()
            ios.close()
        }
        val bytes = baos.toByteArray()
        validateChunks(bytes)
        return bytes
    }

    /**
     * Ensures that generated PNG asset SHA-256 does not match any private
     * original or review-source hash. If equal, deterministically rewrites IDAT
     * chunk boundaries without altering decoded pixels or image dimensions.
     */
    fun ensureHashSeparation(bytes: ByteArray, privateHashes: Set<String>): ByteArray {
        var currentBytes = bytes
        var currentHash = Hashes.sha256(currentBytes)
        if (currentHash !in privateHashes) {
            return currentBytes
        }
        currentBytes = rewriteIdatChunks(currentBytes)
        validateChunks(currentBytes)
        currentHash = Hashes.sha256(currentBytes)
        if (currentHash in privateHashes) {
            throw ToolError(Codes.PRIVACY_LEAK, "cannot establish hash separation from private source hash")
        }
        return currentBytes
    }

    private fun rewriteIdatChunks(bytes: ByteArray): ByteArray {
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) {
            throw ToolError(Codes.PNG_BAD_SIGNATURE, "PNG signature missing")
        }
        var pos = 8L
        var ihdrBytes: ByteArray? = null
        val idatBaos = ByteArrayOutputStream()
        var iendBytes: ByteArray? = null

        while (pos < bytes.size) {
            if (pos + 8 > bytes.size) {
                throw ToolError(Codes.PNG_TRUNCATED, "PNG truncated in chunk header")
            }
            val length = readUInt32BE(bytes, pos)
            val type = String(bytes, pos.toInt() + 4, 4, StandardCharsets.US_ASCII)
            val dataStart = pos + 8
            val dataEnd = dataStart + length
            if (dataEnd + 4 > bytes.size) {
                throw ToolError(Codes.PNG_TRUNCATED, "PNG truncated in chunk data ('$type')")
            }
            val chunkTotalLen = (12 + length).toInt()
            val chunkBytes = bytes.copyOfRange(pos.toInt(), pos.toInt() + chunkTotalLen)

            when (type) {
                "IHDR" -> ihdrBytes = chunkBytes
                "IDAT" -> idatBaos.write(bytes, dataStart.toInt(), length.toInt())
                "IEND" -> iendBytes = chunkBytes
                else -> throw ToolError(Codes.PNG_ANCILLARY, "ancillary chunk '$type' is not allowed in a reviewer asset")
            }
            pos = dataEnd + 4
        }

        if (ihdrBytes == null) throw ToolError(Codes.PNG_NO_IHDR, "PNG missing IHDR")
        if (iendBytes == null) throw ToolError(Codes.PNG_NO_IEND, "PNG missing IEND")

        val combinedIdat = idatBaos.toByteArray()
        if (combinedIdat.size < 2) {
            throw ToolError(Codes.PRIVACY_LEAK, "IDAT payload too small to split for hash separation")
        }

        val mid = combinedIdat.size / 2
        val part1 = combinedIdat.copyOfRange(0, mid)
        val part2 = combinedIdat.copyOfRange(mid, combinedIdat.size)

        val baos = ByteArrayOutputStream()
        baos.write(SIGNATURE)
        baos.write(ihdrBytes)

        writeIdatChunk(baos, part1)
        writeIdatChunk(baos, part2)

        baos.write(iendBytes)
        return baos.toByteArray()
    }

    private fun writeIdatChunk(out: ByteArrayOutputStream, data: ByteArray) {
        val length = data.size
        out.write((length ushr 24) and 0xFF)
        out.write((length ushr 16) and 0xFF)
        out.write((length ushr 8) and 0xFF)
        out.write(length and 0xFF)

        val typeBytes = "IDAT".toByteArray(StandardCharsets.US_ASCII)
        val typeAndData = ByteArray(4 + length)
        System.arraycopy(typeBytes, 0, typeAndData, 0, 4)
        System.arraycopy(data, 0, typeAndData, 4, length)

        out.write(typeAndData)

        val crcVal = crc32(typeAndData, 0, typeAndData.size)
        out.write(((crcVal ushr 24) and 0xFFL).toInt())
        out.write(((crcVal ushr 16) and 0xFFL).toInt())
        out.write(((crcVal ushr 8) and 0xFFL).toInt())
        out.write((crcVal and 0xFFL).toInt())
    }

    /** Accepts only IHDR, one or more IDAT, and IEND in valid order; no ancillary chunks. */
    fun validateChunks(bytes: ByteArray) {
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) {
            throw ToolError(Codes.PNG_BAD_SIGNATURE, "PNG signature missing")
        }
        // Positions are tracked as unsigned long values so a malformed or
        // oversized chunk length can never overflow int arithmetic or index
        // into the array out of bounds: any length that does not fit inside
        // the remaining bytes fails closed as PNG_TRUNCATED.
        var pos = 8L
        var sawIhdr = false
        var sawIdat = false
        var sawIend = false
        while (pos < bytes.size) {
            if (pos + 8 > bytes.size) {
                throw ToolError(Codes.PNG_TRUNCATED, "PNG truncated in chunk header")
            }
            val length = readUInt32BE(bytes, pos)
            val type = String(bytes, pos.toInt() + 4, 4, StandardCharsets.US_ASCII)
            val dataStart = pos + 8
            val dataEnd = dataStart + length
            if (dataEnd + 4 > bytes.size) {
                throw ToolError(Codes.PNG_TRUNCATED, "PNG truncated in chunk data ('$type')")
            }
            val crcStored = readUInt32BE(bytes, dataEnd)
            val crcComputed = crc32(bytes, pos.toInt() + 4, length.toInt() + 4)
            if (crcStored != crcComputed) {
                throw ToolError(Codes.PNG_CRC, "PNG CRC mismatch in '$type'")
            }
            when {
                !sawIhdr -> {
                    if (type != "IHDR") throw ToolError(Codes.PNG_ORDER, "first chunk must be IHDR, found '$type'")
                    sawIhdr = true
                }
                type == "IDAT" -> {
                    if (sawIend) throw ToolError(Codes.PNG_ORDER, "IDAT after IEND")
                    sawIdat = true
                }
                type == "IEND" -> {
                    if (!sawIdat) throw ToolError(Codes.PNG_NO_IDAT, "IEND without any IDAT")
                    if (sawIend) throw ToolError(Codes.PNG_ORDER, "duplicate IEND")
                    sawIend = true
                }
                else -> throw ToolError(Codes.PNG_ANCILLARY, "ancillary chunk '$type' is not allowed in a reviewer asset")
            }
            pos = dataEnd + 4
        }
        if (!sawIhdr) throw ToolError(Codes.PNG_NO_IHDR, "PNG missing IHDR")
        if (!sawIdat) throw ToolError(Codes.PNG_NO_IDAT, "PNG missing IDAT")
        if (!sawIend) throw ToolError(Codes.PNG_NO_IEND, "PNG missing IEND")
    }

    internal fun readIntBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /** Unsigned big-endian 32-bit read; returns a non-negative Long in [0, 0xFFFFFFFF]. */
    internal fun readUInt32BE(bytes: ByteArray, offset: Long): Long =
        (bytes[offset.toInt()].toLong() and 0xFFL) shl 24 or
            ((bytes[offset.toInt() + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset.toInt() + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset.toInt() + 3].toLong() and 0xFFL)

    private fun crc32(bytes: ByteArray, offset: Int, length: Int): Long {
        val crc = java.util.zip.CRC32()
        crc.update(bytes, offset, length)
        // CRC32.value is already an unsigned 32-bit value in [0, 0xFFFFFFFF].
        return crc.value
    }
}

/**
 * Dependency-free review-source decoding. Detects the container format by
 * signature, rejects HEIC/HEIF as undecodable, reads EXIF orientation from
 * JPEG APP1, PNG eXIf, and raw TIFF, and refuses any source that is not
 * already display-oriented. The tool never rotates heuristically.
 */
internal object ReviewSource {

    private val PNG_SIG: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val EXIF_SIG: ByteArray = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

    fun detectFormat(bytes: ByteArray): String {
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIG)) return "png"
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return "jpeg"
        if (bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
        ) {
            val brand = String(bytes, 8, 4, StandardCharsets.US_ASCII)
            if (brand in setOf("heic", "heix", "heif", "heim", "heis", "mif1", "msf1")) return "heic"
        }
        if (bytes.size >= 6) {
            val sig = String(bytes, 0, 6, StandardCharsets.US_ASCII)
            if (sig == "GIF87a" || sig == "GIF89a") return "gif"
        }
        if (bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) return "bmp"
        if (bytes.size >= 4) {
            val little = bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 42.toByte() && bytes[3] == 0.toByte()
            val big = bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() && bytes[2] == 0.toByte() && bytes[3] == 42.toByte()
            if (little || big) return "tiff"
        }
        return "unknown"
    }

    /** Returns 1 when display-normal (or no orientation evidence), 0 when EXIF is unparseable, else the tag value. */
    fun exifOrientation(bytes: ByteArray, format: String): Int = when (format) {
        "jpeg" -> jpegExifOrientation(bytes)
        "png" -> pngExifOrientation(bytes)
        "tiff" -> parseTiffOrientation(bytes, 0)
        else -> 1
    }

    fun readReviewSource(path: Path): BufferedImage {
        val bytes = try {
            Files.readAllBytes(path)
        } catch (e: IOException) {
            throw ToolError(Codes.FILE_READ, "cannot read review source '$path': ${e.message}", ToolExitCode.IO)
        }
        val format = detectFormat(bytes)
        if (format == "heic") {
            throw ToolError(
                Codes.REVIEW_SOURCE_UNDECODABLE,
                "HEIC/HEIF review source is not decodable by this harness: '$path'. Prepare a separately transformed, display-oriented lossless PNG review source.",
            )
        }
        if (format == "unknown") {
            throw ToolError(Codes.REVIEW_SOURCE_UNDECODABLE, "unknown image format for review source: '$path'")
        }
        val orientation = exifOrientation(bytes, format)
        when (orientation) {
            0 -> throw ToolError(Codes.ORIENTATION_NOT_NORMALIZED, "EXIF orientation present but unparseable in review source: '$path'")
            1 -> {}
            else -> throw ToolError(
                Codes.ORIENTATION_NOT_NORMALIZED,
                "review source is not display-oriented (EXIF orientation $orientation): '$path'. Provide a display-oriented lossless PNG.",
            )
        }
        val image = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            null
        } ?: throw ToolError(Codes.REVIEW_SOURCE_UNDECODABLE, "ImageIO could not decode review source: '$path'")
        return image
    }

    private fun jpegExifOrientation(bytes: ByteArray): Int {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return 0
        var pos = 2
        while (pos < bytes.size) {
            if (pos + 1 >= bytes.size || bytes[pos] != 0xFF.toByte()) return 0
            var markerOffset = pos + 1
            while (markerOffset < bytes.size && bytes[markerOffset] == 0xFF.toByte()) markerOffset++
            if (markerOffset >= bytes.size) return 0
            val marker = bytes[markerOffset].toInt() and 0xFF
            if (marker == 0x00) return 0
            if (marker == 0xD9) return 1
            if (marker == 0xD8 || marker in 0xD0..0xD7) return 0
            if (marker == 0x01) {
                pos = markerOffset + 1
                continue
            }
            val lengthOffset = markerOffset + 1
            if (lengthOffset.toLong() + 2L > bytes.size) return 0
            val length = ((bytes[lengthOffset].toInt() and 0xFF) shl 8) or
                (bytes[lengthOffset + 1].toInt() and 0xFF)
            if (length < 2) return 0
            val segmentEnd = lengthOffset.toLong() + length.toLong()
            if (segmentEnd > bytes.size) return 0
            // The SOS header is the final metadata boundary. Validate its
            // component-dependent length before treating all remaining bytes
            // as entropy-coded scan data, where marker-like sequences are not metadata.
            if (marker == 0xDA) {
                if (length < 8) return 0
                val componentCount = bytes[lengthOffset + 2].toInt() and 0xFF
                if (componentCount == 0 || length != 6 + 2 * componentCount) return 0
                return 1
            }
            if (marker == 0xE1) {
                val payloadStart = lengthOffset + 2
                val payloadEnd = segmentEnd.toInt()
                val payloadLength = payloadEnd - payloadStart
                if (payloadLength >= EXIF_SIG.size &&
                    bytes.copyOfRange(payloadStart, payloadStart + EXIF_SIG.size).contentEquals(EXIF_SIG)
                ) {
                    return parseTiffOrientation(bytes, payloadStart + EXIF_SIG.size, payloadEnd)
                }
                val prefixLength = minOf(payloadLength, EXIF_SIG.size)
                if (prefixLength >= 4 && prefixLength < EXIF_SIG.size &&
                    bytes.copyOfRange(payloadStart, payloadStart + prefixLength)
                        .contentEquals(EXIF_SIG.copyOfRange(0, prefixLength))
                ) {
                    return 0
                }
            }
            pos = segmentEnd.toInt()
        }
        return 0
    }

    private fun pngExifOrientation(bytes: ByteArray): Int {
        if (bytes.size < PNG_SIG.size || !bytes.copyOfRange(0, PNG_SIG.size).contentEquals(PNG_SIG)) return 0
        // Unsigned long positions keep malformed lengths from overflowing.
        var pos = 8L
        var sawIhdr = false
        var sawIdat = false
        var closedIdat = false
        var sawIend = false
        var orientation = 1
        var sawExif = false
        while (pos < bytes.size) {
            if (sawIend || pos + 12L > bytes.size) return 0
            val length = Png.readUInt32BE(bytes, pos)
            val type = String(bytes, pos.toInt() + 4, 4, StandardCharsets.US_ASCII)
            val dataStart = pos + 8
            val dataEnd = dataStart + length
            if (dataEnd + 4L > bytes.size) return 0
            val storedCrc = Png.readUInt32BE(bytes, dataEnd)
            val crc = java.util.zip.CRC32().apply {
                update(bytes, pos.toInt() + 4, length.toInt() + 4)
            }.value
            if (storedCrc != crc) return 0
            when {
                !sawIhdr -> {
                    if (type != "IHDR" || length != 13L) return 0
                    sawIhdr = true
                }
                type == "IHDR" -> return 0
                type == "IDAT" -> {
                    if (closedIdat) return 0
                    sawIdat = true
                }
                type == "IEND" -> {
                    if (length != 0L || !sawIdat) return 0
                    sawIend = true
                }
                type == "eXIf" -> {
                    if (sawIdat || sawExif) return 0
                    sawExif = true
                    orientation = parseTiffOrientation(bytes, dataStart.toInt(), dataEnd.toInt())
                    if (orientation == 0) return 0
                }
                sawIdat -> closedIdat = true
            }
            pos = dataEnd + 4
        }
        return if (sawIend) orientation else 0
    }

    /** Returns 0 on structural failure (fail closed), 1 when no orientation tag is present, else the tag value. */
    private fun parseTiffOrientation(bytes: ByteArray, offset: Int, limitExclusive: Int = bytes.size): Int {
        if (offset < 0 || limitExclusive < offset || limitExclusive > bytes.size) return 0
        if (offset.toLong() + 8L > limitExclusive) return 0
        val first = bytes[offset].toInt() and 0xFF
        val second = bytes[offset + 1].toInt() and 0xFF
        val little = first == 'I'.code && second == 'I'.code
        val big = first == 'M'.code && second == 'M'.code
        if (!little && !big) return 0
        if (readU16(bytes, offset + 2, little) != 42) return 0
        val ifd0Offset = readU32(bytes, offset + 4, little)
        if (ifd0Offset < 8L) return 0
        // Overflow-safe IFD offset handling: compute the offset in Long, reject
        // values outside Int range or whose required bytes exceed the buffer,
        // and only then narrow to Int.
        val ifd0Long = offset.toLong() + ifd0Offset
        if (ifd0Long < 0L || ifd0Long > Int.MAX_VALUE.toLong()) return 0
        if (ifd0Long + 2L > limitExclusive) return 0
        val ifd0 = ifd0Long.toInt()
        val count = readU16(bytes, ifd0, little)
        // Entry-table size check in Long arithmetic so count * 12 cannot overflow.
        val tableEnd = ifd0.toLong() + 2L + count.toLong() * 12L
        if (tableEnd + 4L > limitExclusive) return 0
        var orientation: Int? = null
        for (i in 0 until count) {
            val entry = ifd0 + 2 + i * 12
            val tag = readU16(bytes, entry, little)
            if (tag == 0x0112) {
                if (orientation != null) return 0
                val type = readU16(bytes, entry + 2, little)
                val componentCount = readU32(bytes, entry + 4, little)
                if (type != 3 || componentCount != 1L) return 0
                val value = readU16(bytes, entry + 8, little)
                if (value !in 1..8) return 0
                orientation = value
            }
        }
        return orientation ?: 1
    }

    private fun readU16(bytes: ByteArray, offset: Int, little: Boolean): Int {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        return if (little) (b1 shl 8) or b0 else (b0 shl 8) or b1
    }

    private fun readU32(bytes: ByteArray, offset: Int, little: Boolean): Long {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        return if (little) {
            (b3.toLong() shl 24) or (b2.toLong() shl 16) or (b1.toLong() shl 8) or b0.toLong()
        } else {
            (b0.toLong() shl 24) or (b1.toLong() shl 16) or (b2.toLong() shl 8) or b3.toLong()
        }
    }
}

/**
 * The single canonical response schema declaration shared by the HTML export
 * code and the JVM response parser. Its canonical text is hashed into the
 * package manifest so `seal-review` can reject schema drift.
 *
 * The reason and critical-defect tags are exactly the accepted T18.0 review
 * categories. `FAILED_SAVE` is a capture outcome, not a reviewer-visible
 * defect tag. `OTHER_PREDECLARED` requires a nonblank reviewer note.
 */
internal object ResponseSchema {
    const val VERSION = "2"
    val COLUMNS: List<String> = listOf(
        "package_id", "pair_id", "choice", "reason_tags", "critical_defect", "critical_defect_side", "note",
    )
    val CHOICES: Set<String> = setOf("LEFT", "RIGHT", "TIE")
    val DEFECT_SIDES: List<String> = listOf("LEFT", "RIGHT", "BOTH")
    val REASON_TAGS: List<String> = listOf(
        "MOMENT_FOCUS", "EXPOSURE_HIGHLIGHTS", "COLOR_WB", "SKIN_RENDERING",
        "TEXTURE_NOISE", "NATURALNESS", "VISIBLE_ARTIFACT",
    )
    val DEFECT_TAGS: List<String> = listOf(
        "WRONG_ORIENTATION", "UNUSABLE_FOCUS_OR_MOMENT", "SEVERE_SUBJECT_CLIPPING",
        "SEVERE_GHOSTING_OR_MERGE_ARTIFACT", "OTHER_PREDECLARED",
    )

    fun canonicalText(): String = buildString {
        append("RESPONSE_SCHEMA_V").append(VERSION).append('\n')
        append("COLUMNS=").append(COLUMNS.joinToString(",")).append('\n')
        append("CHOICES=").append(CHOICES.sorted().joinToString(",")).append('\n')
        append("DEFECT_SIDES=").append(DEFECT_SIDES.sorted().joinToString(",")).append('\n')
        append("TAGS=").append(REASON_TAGS.sorted().joinToString(",")).append('\n')
        append("DEFECT_TAGS=").append(DEFECT_TAGS.sorted().joinToString(",")).append('\n')
    }

    fun canonicalHash(): String = Hashes.sha256(canonicalText())
}

internal object ReviewPageLabels {
    val REASON_LABELS: Map<String, String> = mapOf(
        "MOMENT_FOCUS" to "Focus or captured moment",
        "EXPOSURE_HIGHLIGHTS" to "Exposure or highlight detail",
        "COLOR_WB" to "Color and white balance",
        "SKIN_RENDERING" to "Skin rendering",
        "TEXTURE_NOISE" to "Texture and noise",
        "NATURALNESS" to "Natural appearance",
        "VISIBLE_ARTIFACT" to "Visible artifact",
    )

    val DEFECT_LABELS: Map<String, String> = mapOf(
        "WRONG_ORIENTATION" to "Wrong orientation",
        "UNUSABLE_FOCUS_OR_MOMENT" to "Unusable focus or missed moment",
        "SEVERE_SUBJECT_CLIPPING" to "Severe subject clipping",
        "SEVERE_GHOSTING_OR_MERGE_ARTIFACT" to "Severe ghosting or merge artifact",
        "OTHER_PREDECLARED" to "Other critical defect (explain in note)",
    )

    init {
        require(REASON_LABELS.keys == ResponseSchema.REASON_TAGS.toSet()) {
            "REASON_LABELS keys must equal ResponseSchema.REASON_TAGS set exactly"
        }
        require(DEFECT_LABELS.keys == ResponseSchema.DEFECT_TAGS.toSet()) {
            "DEFECT_LABELS keys must equal ResponseSchema.DEFECT_TAGS set exactly"
        }
    }
}

/**
 * Deterministically resolves every comparison to exactly one pair per grain.
 *
 * One exact grain resolver is used for every purpose: a comparison matches
 * exactly one eligible trial from arm_a and exactly one from arm_b at
 * identical (scene, condition, repetition). A trial is never paired with
 * itself because arm_a != arm_b is enforced by validation; cross-repetition
 * pairing never occurs because the repetition is part of the grain. BLINDED_A
 * pairs therefore compare distinct baseline-pass arms (for example
 * `baseline_pass_a` and `baseline_pass_b`) at the same repetition.
 */
internal object PairResolver {

    internal data class ResolvedPair(
        val comparisonId: String,
        val purpose: ComparisonPurpose,
        val scene: String,
        val condition: String,
        val repetition: Int,
        val trialA: Trial,
        val trialB: Trial,
    )

    fun resolve(dataset: Dataset): List<ResolvedPair> {
        val successful = dataset.successfulTrials()
        val out = mutableListOf<ResolvedPair>()
        val grains = successful.map { Triple(it.scene, it.condition, it.repetition) }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
        for (comparison in dataset.comparisons) {
            if (comparison.armA == comparison.armB) continue
            for ((scene, condition, repetition) in grains) {
                val a = successful.filter {
                    it.scene == scene && it.condition == condition && it.repetition == repetition && it.arm == comparison.armA
                }
                val b = successful.filter {
                    it.scene == scene && it.condition == condition && it.repetition == repetition && it.arm == comparison.armB
                }
                if (a.isNotEmpty() && b.isNotEmpty()) {
                    val ta = a.sortedWith(compareBy({ it.captureOrder }, { it.trialId })).first()
                    val tb = b.sortedWith(compareBy({ it.captureOrder }, { it.trialId })).first()
                    out += ResolvedPair(comparison.comparisonId, comparison.purpose, scene, condition, repetition, ta, tb)
                }
            }
        }
        return out
    }
}

/**
 * The `blind` command. Runs the validation evidence gate, randomizes pair
 * order and left/right placement from a secure default seed (or an explicit
 * deterministic test seed), writes a source-neutral package and manifest, and
 * writes the private key to a separate path.
 */
internal object ReviewPackage {

    internal data class DisplayPair(
        val pairId: String,
        val comparisonId: String,
        val purpose: ComparisonPurpose,
        val scene: String,
        val condition: String,
        val repetition: Int,
        val leftTrial: Trial,
        val rightTrial: Trial,
    )

    private data class Page(val html: String, val js: String, val css: String)

    fun runBlind(root: Path, outDir: Path, keyPath: Path, seedHex: String?, displayMaxDimension: Int) {
        val validation = DatasetValidator.validateDataset(root)
        if (!validation.ok) {
            throw ToolError(
                Codes.VALIDATION_FAILED,
                "dataset is not eligible for blinding (${validation.critical().size} critical finding(s)); run validate first",
            )
        }
        val dataset = validation.dataset

        guardOutDir(outDir)
        guardNewFile(keyPath)
        rejectKeyInsidePackageResolved(outDir, keyPath)

        val seed = seedHex ?: Hex.encode(ByteArray(16).also { SecureRandom().nextBytes(it) })
        validateSeed(seed)
        val seedLong = seedToLong(seed)
        val pairRng = Random(seedLong xor 0x9E3779B97F4A7C15UL.toLong())
        val idRng = Random(seedLong xor 0xBF58476D1CE4E5B9UL.toLong())
        val packageId = Hex.encode(ByteArray(8).also { idRng.nextBytes(it) })

        val resolved = PairResolver.resolve(dataset)
        if (resolved.isEmpty()) {
            throw ToolError(Codes.NO_COMPARISON_PAIRS, "no comparison pairs resolved from the dataset")
        }

        val displayPairs = blindPairs(resolved, pairRng)

        val privateHashes = (dataset.trials.mapNotNull { it.originalHashSha256 } +
            dataset.trials.mapNotNull { it.reviewSourceHashSha256 }).toSet()

        val assets = LinkedHashMap<String, ByteArray>()
        for (dp in displayPairs) {
            val leftImage = ReviewSource.readReviewSource(reviewSourcePath(dataset.root, dp.leftTrial))
            val rightImage = ReviewSource.readReviewSource(reviewSourcePath(dataset.root, dp.rightTrial))
            assets["assets/${dp.pairId}_l_whole.png"] = Png.ensureHashSeparation(
                Png.encodePng(Png.renderScaled(leftImage, displayMaxDimension)),
                privateHashes,
            )
            assets["assets/${dp.pairId}_r_whole.png"] = Png.ensureHashSeparation(
                Png.encodePng(Png.renderScaled(rightImage, displayMaxDimension)),
                privateHashes,
            )
            for (crop in cropsOf(dataset, dp.leftTrial.trialId)) {
                assets["assets/${dp.pairId}_l_crop_${crop.cropId}.png"] = Png.ensureHashSeparation(
                    Png.encodePng(Png.renderCrop(leftImage, crop.x0, crop.y0, crop.x1, crop.y1)),
                    privateHashes,
                )
            }
            for (crop in cropsOf(dataset, dp.rightTrial.trialId)) {
                assets["assets/${dp.pairId}_r_crop_${crop.cropId}.png"] = Png.ensureHashSeparation(
                    Png.encodePng(Png.renderCrop(rightImage, crop.x0, crop.y0, crop.x1, crop.y1)),
                    privateHashes,
                )
            }
        }

        val page = buildPage(packageId, displayPairs, assets)
        val htmlBytes = page.html.toByteArray(StandardCharsets.UTF_8)
        val jsBytes = page.js.toByteArray(StandardCharsets.UTF_8)
        val cssBytes = page.css.toByteArray(StandardCharsets.UTF_8)

        val keyBytes = buildKey(packageId, seed, dataset, displayPairs, assets).toByteArray(StandardCharsets.UTF_8)

        val manifest = buildManifest(packageId, dataset, displayPairs, assets, htmlBytes, jsBytes, cssBytes, keyBytes)
        val manifestBytes = manifest.toByteArray(StandardCharsets.UTF_8)

        val allFiles = LinkedHashMap<String, ByteArray>()
        for ((name, bytes) in assets) allFiles[name] = bytes
        allFiles["review.html"] = htmlBytes
        allFiles["review.js"] = jsBytes
        allFiles["review.css"] = cssBytes
        allFiles["manifest.properties"] = manifestBytes

        val leaks = scanForPrivateContent(allFiles, forbiddenTokens(dataset, seed, keyPath))
        if (leaks.isNotEmpty()) {
            throw ToolError(Codes.PRIVACY_LEAK, "reviewer package would contain private labels: ${leaks.take(5).joinToString("; ")}")
        }

        try {
            Files.createDirectories(outDir)
            Files.createDirectories(outDir.resolve("assets"))
            for ((name, bytes) in assets) {
                Files.write(outDir.resolve(name), bytes)
            }
            Files.write(outDir.resolve("review.html"), htmlBytes)
            Files.write(outDir.resolve("review.js"), jsBytes)
            Files.write(outDir.resolve("review.css"), cssBytes)
            Files.write(outDir.resolve("manifest.properties"), manifestBytes)
            Files.write(keyPath, keyBytes)
        } catch (e: IOException) {
            throw ToolError(Codes.FILE_WRITE, "failed to write reviewer package: ${e.message}", ToolExitCode.IO)
        }

        println("PACKAGE_ID=$packageId")
        println("PAIR_COUNT=${displayPairs.size}")
        println("PACKAGE_DIR=${outDir.toAbsolutePath().normalize()}")
        println("KEY_FILE=${keyPath.toAbsolutePath().normalize()}")
    }

    private fun validateSeed(seed: String) {
        if (seed.length < 16 || seed.length > 128) {
            throw ToolError(Codes.BAD_SEED, "seed must contain 16..128 hex characters", ToolExitCode.USAGE)
        }
        if (!seed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            throw ToolError(Codes.BAD_SEED, "seed must be hexadecimal", ToolExitCode.USAGE)
        }
    }

    /**
     * Rejects a private key whose physically resolved projected location equals
     * or lies under the physically resolved projected package location.
     *
     * Lexical comparison alone is insufficient when an existing parent is a
     * symlink or junction, so both not-yet-created paths are projected from the
     * real path of their existing parent directory. Missing or invalid parents
     * fail closed with a stable error. No ACL is created, modified, or tested.
     */
    private fun rejectKeyInsidePackageResolved(outDir: Path, keyPath: Path) {
        val outAbs = outDir.toAbsolutePath().normalize()
        val keyAbs = keyPath.toAbsolutePath().normalize()
        val outParent = outAbs.parent
            ?: throw ToolError(Codes.PATH_IO, "package output path has no parent directory: '$outDir'", ToolExitCode.IO)
        val keyParent = keyAbs.parent
            ?: throw ToolError(Codes.PATH_IO, "key path has no parent directory: '$keyPath'", ToolExitCode.IO)
        if (!Files.isDirectory(outParent)) {
            throw ToolError(
                Codes.PATH_IO,
                "package output parent directory does not exist: '$outParent'",
                ToolExitCode.IO,
            )
        }
        if (!Files.isDirectory(keyParent)) {
            throw ToolError(
                Codes.PATH_IO,
                "key parent directory does not exist: '$keyParent'",
                ToolExitCode.IO,
            )
        }
        val realOutParent = try {
            outParent.toRealPath()
        } catch (e: IOException) {
            throw ToolError(Codes.PATH_IO, "cannot resolve real package parent '$outParent': ${e.message}", ToolExitCode.IO)
        }
        val realKeyParent = try {
            keyParent.toRealPath()
        } catch (e: IOException) {
            throw ToolError(Codes.PATH_IO, "cannot resolve real key parent '$keyParent': ${e.message}", ToolExitCode.IO)
        }
        val projectedOut = realOutParent.resolve(outAbs.fileName)
        val projectedKey = realKeyParent.resolve(keyAbs.fileName)
        if (projectedKey == projectedOut || projectedKey.startsWith(projectedOut)) {
            throw ToolError(
                Codes.KEY_INSIDE_PACKAGE,
                "key path must not be inside the reviewer package (resolved: '$projectedKey' under '$projectedOut')",
            )
        }
    }

    private fun seedToLong(seed: String): Long {
        val digest = Hashes.sha256Bytes(seed)
        return ByteBufferWrap.longAt(digest, 0)
    }

    private object ByteBufferWrap {
        fun longAt(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) {
                value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
            }
            return value
        }
    }

    private fun blindPairs(resolved: List<PairResolver.ResolvedPair>, rng: Random): List<DisplayPair> {
        val shuffled = resolved.toMutableList()
        for (i in shuffled.size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = shuffled[i]
            shuffled[i] = shuffled[j]
            shuffled[j] = tmp
        }
        return shuffled.mapIndexed { index, rp ->
            val swap = rng.nextBoolean()
            val left = if (swap) rp.trialB else rp.trialA
            val right = if (swap) rp.trialA else rp.trialB
            DisplayPair(
                pairId = "p" + (index + 1).toString().padStart(2, '0'),
                comparisonId = rp.comparisonId,
                purpose = rp.purpose,
                scene = rp.scene,
                condition = rp.condition,
                repetition = rp.repetition,
                leftTrial = left,
                rightTrial = right,
            )
        }
    }

    private fun reviewSourcePath(root: Path, trial: Trial): Path {
        val rel = trial.reviewSourcePath
            ?: throw ToolError(Codes.OUTCOME_CONTRADICTION, "trial ${trial.trialId} has no review source")
        return PathSecurity.resolve(root, rel, "review source of trial ${trial.trialId}")
    }

    private fun cropsOf(dataset: Dataset, trialId: String): List<Crop> =
        dataset.crops.filter { it.trialId == trialId }.sortedWith(compareBy({ it.cropId }))

    private fun buildPage(packageId: String, displayPairs: List<DisplayPair>, assets: Map<String, ByteArray>): Page {
        val pairsJs = displayPairs.joinToString(",\n") { dp ->
            val leftCrops = mutableListOf<String>()
            val rightCrops = mutableListOf<String>()
            for ((name, _) in assets) {
                if (name.startsWith("assets/${dp.pairId}_l_crop_")) leftCrops += "\"$name\""
                if (name.startsWith("assets/${dp.pairId}_r_crop_")) rightCrops += "\"$name\""
            }
            "{\"id\":\"${dp.pairId}\",\"wholeLeft\":\"assets/${dp.pairId}_l_whole.png\",\"wholeRight\":\"assets/${dp.pairId}_r_whole.png\"," +
                "\"leftCrops\":[${leftCrops.joinToString(",")}],\"rightCrops\":[${rightCrops.joinToString(",")}]}"
        }
        val schemaJs = ResponseSchema.COLUMNS.joinToString(",") { "\"$it\"" }
        val tagsJs = ResponseSchema.REASON_TAGS.joinToString(",") { "\"$it\"" }
        val defectsJs = ResponseSchema.DEFECT_TAGS.joinToString(",") { "\"$it\"" }
        val defectSidesJs = ResponseSchema.DEFECT_SIDES.joinToString(",") { "\"$it\"" }
        val reasonLabelsJs = ReviewPageLabels.REASON_LABELS.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        val defectLabelsJs = ReviewPageLabels.DEFECT_LABELS.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }

        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src 'self'; style-src 'self'; script-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'">
<title>Offline review</title>
<link rel="stylesheet" href="review.css">
</head>
<body>
<h1>Offline review</h1>
<p class="notice">Compare the two photographs. First choose the photograph you would keep or use (Left, Right, or Tie). Reason tags are optional broad analysis categories, and technical detail belongs in the note. A critical defect is rare and means unusable. Choose Defect Side BOTH only when the same critical defect makes both images unusable.</p>
<div id="progress" class="progress"></div>
<div id="errorSummary" class="error-summary" tabindex="-1" role="region" aria-live="polite" aria-atomic="true"></div>
<div id="pairs"></div>
<div class="toolbar">
<button type="button" id="exportBtn">Generate responses.csv</button>
<p class="hint">Copy the text below into a file named <code>responses.csv</code> saved OUTSIDE this package folder (for example next to it). The package itself is immutable: do not add, remove, rename, or edit any file inside this folder, and do not save the response file inside it. Then seal the response file.</p>
<textarea id="csvArea" readonly spellcheck="false"></textarea>
</div>
<script src="review.js"></script>
</body>
</html>
""".trimIndent()

        val js = """
"use strict";
var PACKAGE_ID = "@@PACKAGE_ID@@";
var SCHEMA = [@@SCHEMA@@];
var REASON_TAGS = [@@REASON_TAGS@@];
var DEFECT_TAGS = [@@DEFECT_TAGS@@];
var DEFECT_SIDES = [@@DEFECT_SIDES@@];
var PAIRS = [@@PAIRS@@];

var REASON_LABELS = {@@REASON_LABELS@@};
var DEFECT_LABELS = {@@DEFECT_LABELS@@};

var hasAttemptedExport = false;

var state = {};
PAIRS.forEach(function (pair) {
  state[pair.id] = { choice: "", tags: [], defect: "", side: "", note: "" };
});

function el(tag, attrs, text) {
  var node = document.createElement(tag);
  for (var k in attrs) { node.setAttribute(k, attrs[k]); }
  if (text !== undefined && text !== null) { node.appendChild(document.createTextNode(text)); }
  return node;
}

function rebuild() {
  var container = document.getElementById("pairs");
  container.textContent = "";
  PAIRS.forEach(function (pair) {
    var s = state[pair.id];
    var block = el("section", { "class": "pair", "id": "pair_" + pair.id });
    block.appendChild(el("h2", {}, "Pair " + pair.id));
    var images = el("div", { "class": "images" });
    var left = el("figure", { "class": "image" });
    left.appendChild(el("img", { "src": pair.wholeLeft, "alt": "Left image" }));
    left.appendChild(el("figcaption", {}, "Left"));
    pair.leftCrops.forEach(function (src) {
      left.appendChild(el("img", { "src": src, "alt": "Left crop" }));
    });
    var right = el("figure", { "class": "image" });
    right.appendChild(el("img", { "src": pair.wholeRight, "alt": "Right image" }));
    right.appendChild(el("figcaption", {}, "Right"));
    pair.rightCrops.forEach(function (src) {
      right.appendChild(el("img", { "src": src, "alt": "Right crop" }));
    });
    images.appendChild(left);
    images.appendChild(right);
    block.appendChild(images);
    var controls = el("div", { "class": "controls" });

    var choiceSec = el("div", { "class": "section-block" });
    choiceSec.appendChild(el("span", { "class": "lbl" }, "Preference (required): "));
    var choiceFlex = el("div", { "class": "flex-group choice-group" });
    ["LEFT", "RIGHT", "TIE"].forEach(function (choice) {
      var label = el("label", { "class": "radio-unit" });
      var radio = el("input", { "type": "radio", "name": "choice_" + pair.id, "value": choice });
      if (s.choice === choice) { radio.setAttribute("checked", "checked"); }
      radio.addEventListener("change", function () { s.choice = choice; refresh(); });
      label.appendChild(radio);
      label.appendChild(document.createTextNode(choice === "LEFT" ? " Left is better" : choice === "RIGHT" ? " Right is better" : " Tie"));
      choiceFlex.appendChild(label);
    });
    choiceSec.appendChild(choiceFlex);
    controls.appendChild(choiceSec);

    var tagSec = el("div", { "class": "section-block" });
    tagSec.appendChild(el("span", { "class": "lbl" }, "Reason tags (optional analysis categories): "));
    var tagFlex = el("div", { "class": "flex-group tags-group" });
    REASON_TAGS.forEach(function (tag) {
      var label = el("label", { "class": "tag-unit" });
      var box = el("input", { "type": "checkbox", "value": tag });
      if (s.tags.indexOf(tag) >= 0) { box.setAttribute("checked", "checked"); }
      box.addEventListener("change", function () {
        if (box.checked) { if (s.tags.indexOf(tag) < 0) { s.tags.push(tag); } }
        else { s.tags = s.tags.filter(function (t) { return t !== tag; }); }
        refresh();
      });
      label.appendChild(box);
      label.appendChild(document.createTextNode(REASON_LABELS[tag] || tag));
      tagFlex.appendChild(label);
    });
    tagSec.appendChild(tagFlex);
    controls.appendChild(tagSec);

    var fieldset = el("fieldset", { "class": "critical-panel" });
    fieldset.appendChild(el("legend", {}, "Critical defect: use only when unusable"));
    var critFlex = el("div", { "class": "flex-group" });
    var defectLabel = el("label", { "for": "defect_" + pair.id, "class": "lbl" }, "Tag: ");
    critFlex.appendChild(defectLabel);
    var defectSelect = el("select", { "id": "defect_" + pair.id });
    defectSelect.appendChild(el("option", { "value": "" }, "(none)"));
    DEFECT_TAGS.forEach(function (tag) {
      var opt = el("option", { "value": tag }, DEFECT_LABELS[tag] || tag);
      if (s.defect === tag) { opt.setAttribute("selected", "selected"); }
      defectSelect.appendChild(opt);
    });
    defectSelect.addEventListener("change", function () { s.defect = defectSelect.value; refresh(); });
    critFlex.appendChild(defectSelect);

    var sideLabel = el("label", { "for": "side_" + pair.id, "class": "lbl" }, " Defect side: ");
    critFlex.appendChild(sideLabel);
    var sideSelect = el("select", { "id": "side_" + pair.id });
    sideSelect.appendChild(el("option", { "value": "" }, "(none)"));
    DEFECT_SIDES.forEach(function (side) {
      var opt = el("option", { "value": side }, side);
      if (s.side === side) { opt.setAttribute("selected", "selected"); }
      sideSelect.appendChild(opt);
    });
    sideSelect.addEventListener("change", function () { s.side = sideSelect.value; refresh(); });
    critFlex.appendChild(sideSelect);
    fieldset.appendChild(critFlex);
    controls.appendChild(fieldset);

    var noteSec = el("div", { "class": "section-block" });
    var noteLabel = el("label", { "for": "note_" + pair.id, "class": "lbl" }, "Note: ");
    noteSec.appendChild(noteLabel);
    var note = el("textarea", {
      "id": "note_" + pair.id,
      "rows": "2",
      "cols": "60",
      "placeholder": "Optional detail: contrast, shadows, sharpening, tone, or anything else you noticed."
    });
    note.value = s.note;
    note.addEventListener("input", function () { s.note = note.value; refresh(); });
    noteSec.appendChild(note);
    controls.appendChild(noteSec);

    block.appendChild(controls);
    container.appendChild(block);
  });
}

function validateDraft() {
  var errors = [];
  var answeredCount = 0;
  PAIRS.forEach(function (pair) {
    var s = state[pair.id];
    var pairErrors = [];
    if (s.choice === "LEFT" || s.choice === "RIGHT" || s.choice === "TIE") {
      answeredCount++;
    } else {
      pairErrors.push("Preference choice (Left, Right, or Tie) is required");
    }

    if ((s.defect !== "" && s.side === "") || (s.defect === "" && s.side !== "")) {
      pairErrors.push("Critical defect tag and side must be set together or both empty");
    }

    if (s.defect === "OTHER_PREDECLARED" && s.note.trim() === "") {
      pairErrors.push("Other critical defect requires an explanatory note");
    }

    var block = document.getElementById("pair_" + pair.id);
    if (block) {
      if (hasAttemptedExport && pairErrors.length > 0) {
        block.classList.add("invalid");
      } else {
        block.classList.remove("invalid");
      }
    }

    if (pairErrors.length > 0) {
      errors.push({ id: pair.id, messages: pairErrors });
    }
  });

  return {
    valid: errors.length === 0,
    answered: answeredCount,
    total: PAIRS.length,
    errors: errors
  };
}

function csvField(value) {
  var v = value == null ? "" : String(value);
  if (v.indexOf(",") >= 0 || v.indexOf("\"") >= 0 || v.indexOf("\n") >= 0 || v.indexOf("\r") >= 0) {
    return "\"" + v.replace(/"/g, "\"\"") + "\"";
  }
  return v;
}

function buildCsv() {
  var lines = [SCHEMA.join(",")];
  PAIRS.forEach(function (pair) {
    var s = state[pair.id];
    var row = [PACKAGE_ID, pair.id, s.choice, s.tags.join("|"), s.defect, s.side, s.note];
    lines.push(row.map(csvField).join(","));
  });
  return lines.join("\r\n");
}

function refresh() {
  var result = validateDraft();

  var progressEl = document.getElementById("progress");
  if (progressEl) {
    progressEl.textContent = "Answered " + result.answered + " of " + result.total + " required pairs";
  }

  var summaryEl = document.getElementById("errorSummary");
  if (summaryEl) {
    summaryEl.textContent = "";
    if (hasAttemptedExport && result.errors.length > 0) {
      var h3 = el("h3", {}, "Please correct the following issues before exporting:");
      summaryEl.appendChild(h3);
      var ul = el("ul", {});
      result.errors.forEach(function (err) {
        var li = el("li", {});
        var a = el("a", { "href": "#pair_" + err.id }, "Pair " + err.id + ": ");
        li.appendChild(a);
        li.appendChild(document.createTextNode(err.messages.join("; ")));
        ul.appendChild(li);
      });
      summaryEl.appendChild(ul);
      summaryEl.style.display = "block";
    } else {
      summaryEl.style.display = "none";
    }
  }

  var csvArea = document.getElementById("csvArea");
  if (csvArea) {
    if (result.valid) {
      csvArea.value = buildCsv();
    } else {
      csvArea.value = "";
    }
  }
}

document.getElementById("exportBtn").addEventListener("click", function () {
  hasAttemptedExport = true;
  var result = validateDraft();
  refresh();
  if (!result.valid) {
    var firstInvalidId = result.errors[0].id;
    var firstEl = document.getElementById("pair_" + firstInvalidId);
    var summaryEl = document.getElementById("errorSummary");
    if (summaryEl && summaryEl.style.display !== "none") { summaryEl.focus(); }
    if (firstEl) {
      var firstControl = firstEl.querySelector("input[type=radio]:not(:checked), select, textarea");
      if (firstControl) { firstControl.focus(); }
      firstEl.scrollIntoView({ behavior: "smooth" });
    }
    return;
  }

  var csv = buildCsv();
  var blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
  var a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = "responses.csv";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
});

rebuild();
refresh();
""".trimIndent()

        val css = """
*, *:before, *:after { box-sizing: border-box; }
body { font-family: system-ui, sans-serif; max-width: 1000px; margin: 2em auto; padding: 0 1em; color: #222; }
img { max-width: 100%; height: auto; border: 1px solid #ddd; display: block; }
.pair { border: 1px solid #ccc; margin-bottom: 2em; padding: 1em; border-radius: 4px; max-width: 100%; }
.pair.invalid { border: 2px solid #d9534f; background-color: #fff9f9; }
.images { display: flex; flex-wrap: wrap; gap: 1em; margin-bottom: 1em; max-width: 100%; }
.image { flex: 1 1 300px; min-width: 0; max-width: 100%; margin: 0; }
.controls { margin-top: 0.5em; max-width: 100%; }
.section-block { margin-bottom: 0.8em; max-width: 100%; }
.flex-group { display: flex; flex-wrap: wrap; gap: 0.5em 1em; align-items: center; max-width: 100%; }
.radio-unit, .tag-unit { display: inline-flex; align-items: center; white-space: nowrap; font-size: 0.9em; cursor: pointer; }
.radio-unit input, .tag-unit input { margin-right: 0.4em; }
.controls .lbl { font-weight: bold; margin-right: 0.4em; display: inline-block; }
.critical-panel { border: 1px solid #e0e0e0; background: #fafafa; padding: 0.8em; margin: 0.8em 0; border-radius: 4px; max-width: 100%; }
.critical-panel legend { font-weight: bold; padding: 0 0.4em; color: #c9302c; }
select, textarea, input { max-width: 100%; }
.controls textarea, #csvArea { font-family: monospace; width: 100%; box-sizing: border-box; max-width: 100%; }
.toolbar { margin: 1.5em 0; padding: 1em; background: #f8f9fa; border: 1px solid #e9ecef; border-radius: 4px; max-width: 100%; }
.progress { font-weight: bold; font-size: 1.1em; margin: 1em 0; color: #2e6da4; }
.error-summary { background: #f2dede; border: 1px solid #ebccd1; color: #a94442; padding: 1em; margin: 1em 0; border-radius: 4px; display: none; max-width: 100%; }
.error-summary h3 { margin-top: 0; margin-bottom: 0.5em; font-size: 1em; }
.error-summary ul { margin: 0; padding-left: 1.5em; }
.error-summary a { color: #843534; font-weight: bold; text-decoration: underline; }
.notice, .hint { font-size: 0.9em; color: #555; line-height: 1.4; max-width: 100%; }

@media (max-width: 600px) {
  body { margin: 1em auto; padding: 0 0.5em; }
  .pair { padding: 0.5em; }
  .images { gap: 0.5em; }
  .image { flex: 1 1 100%; }
}
""".trimIndent()

        return Page(
            html = html,
            js = js.replace("@@PACKAGE_ID@@", packageId)
                .replace("@@SCHEMA@@", schemaJs)
                .replace("@@REASON_TAGS@@", tagsJs)
                .replace("@@DEFECT_TAGS@@", defectsJs)
                .replace("@@DEFECT_SIDES@@", defectSidesJs)
                .replace("@@REASON_LABELS@@", reasonLabelsJs)
                .replace("@@DEFECT_LABELS@@", defectLabelsJs)
                .replace("@@PAIRS@@", pairsJs),
            css = css,
        )
    }

    private fun buildManifest(
        packageId: String,
        dataset: Dataset,
        displayPairs: List<DisplayPair>,
        assets: Map<String, ByteArray>,
        htmlBytes: ByteArray,
        jsBytes: ByteArray,
        cssBytes: ByteArray,
        keyBytes: ByteArray,
    ): String {
        val entries = LinkedHashMap<String, String>()
        entries["manifest_schema_version"] = TOOL_SCHEMA_VERSION
        entries["package_id"] = packageId
        entries["response_schema_version"] = ResponseSchema.VERSION
        entries["response_schema_sha256"] = ResponseSchema.canonicalHash()
        entries["plan.sha256"] = dataset.comparisonPlanHash
        entries["key.sha256"] = Hashes.sha256(keyBytes)
        entries["pair.count"] = displayPairs.size.toString()
        entries["pair.order"] = displayPairs.joinToString(",") { it.pairId }
        entries["page.review.html.sha256"] = Hashes.sha256(htmlBytes)
        entries["script.review.js.sha256"] = Hashes.sha256(jsBytes)
        entries["style.review.css.sha256"] = Hashes.sha256(cssBytes)
        for ((name, bytes) in assets.toSortedMap()) {
            entries["asset.$name.sha256"] = Hashes.sha256(bytes)
        }
        return StrictProperties.serialize(entries)
    }

    private fun buildKey(
        packageId: String,
        seed: String,
        dataset: Dataset,
        displayPairs: List<DisplayPair>,
        assets: Map<String, ByteArray>,
    ): String {
        val entries = LinkedHashMap<String, String>()
        entries["key_schema_version"] = TOOL_SCHEMA_VERSION
        entries["package_id"] = packageId
        entries["seed"] = seed
        entries["dataset_hash_sha256"] = Hashes.sha256Directory(dataset.root)
        for (dp in displayPairs) {
            entries["pair.${dp.pairId}.comparison_id"] = dp.comparisonId
            entries["pair.${dp.pairId}.purpose"] = dp.purpose.value
            entries["pair.${dp.pairId}.scene"] = dp.scene
            entries["pair.${dp.pairId}.condition"] = dp.condition
            entries["pair.${dp.pairId}.repetition"] = dp.repetition.toString()
            entries["pair.${dp.pairId}.left.trial_id"] = dp.leftTrial.trialId
            entries["pair.${dp.pairId}.left.arm"] = dp.leftTrial.arm
            entries["pair.${dp.pairId}.right.trial_id"] = dp.rightTrial.trialId
            entries["pair.${dp.pairId}.right.arm"] = dp.rightTrial.arm
        }
        for ((name, _) in assets.toSortedMap()) {
            for (dp in displayPairs) {
                when {
                    name == "assets/${dp.pairId}_l_whole.png" || name.startsWith("assets/${dp.pairId}_l_crop_") ->
                        entries["asset.$name.trial_id"] = dp.leftTrial.trialId
                    name == "assets/${dp.pairId}_r_whole.png" || name.startsWith("assets/${dp.pairId}_r_crop_") ->
                        entries["asset.$name.trial_id"] = dp.rightTrial.trialId
                }
            }
        }
        return StrictProperties.serialize(entries)
    }

    private fun forbiddenTokens(dataset: Dataset, seed: String, keyPath: Path): List<String> {
        val tokens = mutableListOf<String>()
        tokens += dataset.props.declaredArms
        tokens += dataset.props.criticalScenes
        tokens += listOf(dataset.props.appIdentifier, dataset.props.baselineIdentifier)
        dataset.props.candidateIdentifier?.let { tokens += it }
        for (trial in dataset.trials) {
            tokens += trial.trialId
            tokens += trial.scene
            tokens += trial.condition
            trial.originalPath?.let {
                tokens += it
                tokens += try { Path.of(it).fileName.toString() } catch (e: Exception) { it }
            }
            trial.reviewSourcePath?.let {
                tokens += it
                tokens += try { Path.of(it).fileName.toString() } catch (e: Exception) { it }
            }
            trial.device.takeIf { it.isNotBlank() }?.let { tokens += it }
            trial.appVersion.takeIf { it.isNotBlank() }?.let { tokens += it }
            trial.cameraIdentifier.takeIf { it.isNotBlank() }?.let { tokens += it }
            trial.originalHashSha256?.let { tokens += it }
            trial.reviewSourceHashSha256?.let { tokens += it }
        }
        tokens += seed
        tokens += keyPath.toString()
        tokens += dataset.root.toAbsolutePath().normalize().toString()
        return tokens.filter { it.isNotBlank() }.distinct().sorted()
    }

    private fun scanForPrivateContent(files: Map<String, ByteArray>, forbidden: List<String>): List<String> {
        val found = mutableListOf<String>()
        for ((name, bytes) in files) {
            val text = String(bytes, StandardCharsets.ISO_8859_1)
            for (token in forbidden) {
                if (token.isNotEmpty() && text.contains(token)) {
                    found += "'$token' inside $name"
                }
            }
        }
        return found
    }
}
