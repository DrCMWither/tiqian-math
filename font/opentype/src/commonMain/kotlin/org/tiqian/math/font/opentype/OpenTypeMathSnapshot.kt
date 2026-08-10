package org.tiqian.math.font.opentype

import org.tiqian.math.core.DiagnosticCode

/** Build-time identity binding for the product's bundled Lete faces. */
object LeteSansMathPrebakedData {
    const val RegularFileStem: String = "LeteSansMath-Regular"
    const val BoldFileStem: String = "LeteSansMath-Bold"
    const val RegularSha256: String = "ead643895be03f42f6fa201fb1176323f60dd330d4109387bac90bdf980fcf3e"
    const val BoldSha256: String = "a521f128db0821a9943e4f703103204d8982aeb39c933e12344c43e9d3b0907a"
}

internal const val OpenTypeMathSnapshotFormatVersion: Int = 1

internal data class DecodedOpenTypeMathSnapshot(
    val fontSha256: String,
    /** Detached immutable metadata. [OpenTypeMathFont.bytes] is empty until verified attachment. */
    val metadata: OpenTypeMathFont,
)

/** Runtime decoder only. Encoding lives in the build-time metadata-generator module. */
internal object OpenTypeMathSnapshotDecoder {
    fun decode(snapshotBytes: ByteArray): DecodedOpenTypeMathSnapshot {
        val reader = SnapshotReader(snapshotBytes)
        if (!reader.readBytes(Magic.size).contentEquals(Magic)) malformed("Invalid Tiqian MATH snapshot magic")
        val version = reader.readInt()
        if (version != OpenTypeMathSnapshotFormatVersion) {
            malformed("Unsupported Tiqian MATH snapshot version $version")
        }
        val fontSha256 = reader.readString()
        if (fontSha256.length != 64 || fontSha256.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            malformed("Invalid font SHA-256 in Tiqian MATH snapshot")
        }
        val metadata = OpenTypeMathFont(
            bytes = ByteArray(0),
            unitsPerEm = reader.readInt(),
            lineMetrics = OpenTypeLineMetrics(reader.readInt(), reader.readInt(), reader.readInt()),
            xHeight = reader.readNullableInt(),
            constants = reader.readConstants(),
            italicCorrections = reader.readGlyphIntMap(),
            italicCorrectionDeviceAdjustments = reader.readDeviceAdjustmentMap(),
            unsupportedItalicCorrectionVariationAdjustments = reader.readGlyphSet(),
            extendedShapeGlyphs = reader.readGlyphSet(),
            mathKernInfo = reader.readMathKernMap(),
            verticalConstructions = reader.readConstructionMap(),
            topAccentAttachments = reader.readGlyphIntMap(),
            topAccentAttachmentDeviceAdjustments = reader.readDeviceAdjustmentMap(),
            unsupportedTopAccentAttachmentVariationAdjustments = reader.readGlyphSet(),
            horizontalConstructions = reader.readConstructionMap(),
        )
        reader.requireFullyConsumed()
        return DecodedOpenTypeMathSnapshot(fontSha256, metadata)
    }

    private val Magic = byteArrayOf(0x54, 0x51, 0x4D, 0x41, 0x54, 0x48, 0x00, 0x01)
}

private fun malformed(message: String): Nothing = throw OpenTypeMathException(DiagnosticCode.MalformedFont, message)

private class SnapshotReader(private val bytes: ByteArray) {
    private var offset = 0

    fun readBytes(count: Int): ByteArray {
        requireAvailable(count)
        return bytes.copyOfRange(offset, offset + count).also { offset += count }
    }

    fun readInt(): Int {
        requireAvailable(4)
        return (((bytes[offset++].toInt() and 0xff) shl 24) or
            ((bytes[offset++].toInt() and 0xff) shl 16) or
            ((bytes[offset++].toInt() and 0xff) shl 8) or
            (bytes[offset++].toInt() and 0xff))
    }

    fun readU16(): UShort {
        requireAvailable(2)
        return (((bytes[offset++].toInt() and 0xff) shl 8) or
            (bytes[offset++].toInt() and 0xff)).toUShort()
    }

    fun readBoolean(): Boolean = when (val value = readByte()) {
        0 -> false
        1 -> true
        else -> malformed("Invalid boolean $value in Tiqian MATH snapshot")
    }

    fun readString(): String {
        val size = readCount("string")
        return readBytes(size).decodeToString()
    }

    fun readNullableInt(): Int? = if (readBoolean()) readInt() else null

    fun readConstants(): OpenTypeMathConstants {
        val values = IntArray(50) { readInt() }
        var index = 0
        fun next() = values[index++]
        return OpenTypeMathConstants(
            next(), next(), next(), next(), next(), next(), next(), next(), next(), next(),
            next(), next(), next(), next(), next(), next(), next(), next(), next(), next(),
            next(), next(), next(), next(), next(), next(), next(), next(), next(), next(),
            next(), next(), next(), next(), next(), next(), next(), next(), next(), next(),
            next(), next(), next(), next(), next(), next(), next(), next(), next(), next(),
        )
    }

    fun readGlyphIntMap(): Map<UShort, Int> = buildMap {
        repeat(readCount("glyph map")) { put(readU16(), readInt()) }
    }

    fun readGlyphSet(): Set<UShort> = buildSet {
        repeat(readCount("glyph set")) { add(readU16()) }
    }

    fun readDeviceAdjustmentMap(): Map<UShort, MathDeviceAdjustment> = buildMap {
        repeat(readCount("device adjustment map")) {
            val glyph = readU16()
            val startPpem = readInt()
            val endPpem = readInt()
            val deltaFormat = readInt()
            val deltas = List(readCount("device adjustment values")) { readInt() }
            put(glyph, MathDeviceAdjustment(startPpem, endPpem, deltaFormat, deltas))
        }
    }

    fun readMathKernMap(): Map<UShort, MathGlyphKernInfo> = buildMap {
        repeat(readCount("MathKern map")) {
            put(readU16(), MathGlyphKernInfo(readKernTable(), readKernTable(), readKernTable(), readKernTable()))
        }
    }

    private fun readKernTable(): MathKernTable? {
        if (!readBoolean()) return null
        val heights = List(readCount("MathKern heights")) { readInt() }
        val values = List(readCount("MathKern values")) { readInt() }
        if (values.size != heights.size + 1) malformed("Invalid MathKern table in Tiqian MATH snapshot")
        return MathKernTable(heights, values)
    }

    fun readConstructionMap(): Map<UShort, MathGlyphConstruction> = buildMap {
        repeat(readCount("construction map")) {
            val glyph = readU16()
            val variants = List(readCount("construction variants")) {
                MathGlyphVariant(readU16(), readInt())
            }
            val assembly = if (readBoolean()) {
                val minimumConnectorOverlap = readInt()
                val italicCorrection = readInt()
                val parts = List(readCount("assembly parts")) {
                    MathGlyphAssemblyPart(readU16(), readInt(), readInt(), readInt(), readBoolean())
                }
                MathGlyphAssembly(parts, minimumConnectorOverlap, italicCorrection)
            } else {
                null
            }
            put(glyph, MathGlyphConstruction(variants, assembly))
        }
    }

    fun requireFullyConsumed() {
        if (offset != bytes.size) malformed("Trailing bytes in Tiqian MATH snapshot")
    }

    private fun readByte(): Int {
        requireAvailable(1)
        return bytes[offset++].toInt() and 0xff
    }

    private fun readCount(context: String): Int {
        val count = readInt()
        if (count < 0 || count > bytes.size) malformed("Invalid $context count $count in Tiqian MATH snapshot")
        return count
    }

    private fun requireAvailable(count: Int) {
        if (count < 0 || offset > bytes.size - count) malformed("Truncated Tiqian MATH snapshot")
    }
}
