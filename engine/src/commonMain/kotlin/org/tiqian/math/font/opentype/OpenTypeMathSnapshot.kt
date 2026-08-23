package org.tiqian.math.font.opentype

import org.tiqian.math.core.DiagnosticCode

/** Build-time identity binding for the product's bundled Lete faces. */
object LeteSansMathPrebakedData {
    const val RegularFileStem: String = "LeteSansMath-Regular"
    const val BoldFileStem: String = "LeteSansMath-Bold"
    const val RegularSourceSha256: String = "ead643895be03f42f6fa201fb1176323f60dd330d4109387bac90bdf980fcf3e"
    const val BoldSourceSha256: String = "a521f128db0821a9943e4f703103204d8982aeb39c933e12344c43e9d3b0907a"
}

internal const val OpenTypeMathSnapshotFormatVersion: Int = 3

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
        val version = reader.readFixedInt()
        if (version !in 2..OpenTypeMathSnapshotFormatVersion) {
            malformed("Unsupported Tiqian MATH snapshot version $version")
        }
        reader.compact = version >= 3
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
            characterGlyphs = reader.readScalarGlyphMap(),
            scriptStyleAlternates = reader.readGlyphAlternatesMap(),
        )
        reader.requireFullyConsumed()
        return DecodedOpenTypeMathSnapshot(fontSha256, metadata)
    }

    private val Magic = byteArrayOf(0x54, 0x51, 0x4D, 0x41, 0x54, 0x48, 0x00, 0x01)
}

private fun malformed(message: String): Nothing = throw OpenTypeMathException(DiagnosticCode.MalformedFont, message)

private class SnapshotReader(private val bytes: ByteArray) {
    private var offset = 0
    var compact: Boolean = false

    fun readBytes(count: Int): ByteArray {
        requireAvailable(count)
        return bytes.copyOfRange(offset, offset + count).also { offset += count }
    }

    fun readFixedInt(): Int {
        requireAvailable(4)
        return (((bytes[offset++].toInt() and 0xff) shl 24) or
            ((bytes[offset++].toInt() and 0xff) shl 16) or
            ((bytes[offset++].toInt() and 0xff) shl 8) or
            (bytes[offset++].toInt() and 0xff))
    }

    fun readInt(): Int = if (compact) readVarInt() else readFixedInt()

    fun readU16(): UShort = if (compact) {
        val value = readVarUInt()
        if (value > 0xffff) malformed("Invalid glyph id $value in Tiqian MATH snapshot")
        value.toUShort()
    } else {
        requireAvailable(2)
        (((bytes[offset++].toInt() and 0xff) shl 8) or
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
        var glyph = 0
        repeat(readCount("glyph map")) {
            glyph = if (compact) glyph + readVarUInt() else readU16().toInt()
            put(glyph.toGlyphId(), readInt())
        }
    }

    fun readGlyphSet(): Set<UShort> = buildSet {
        var glyph = 0
        repeat(readCount("glyph set")) {
            glyph = if (compact) glyph + readVarUInt() else readU16().toInt()
            add(glyph.toGlyphId())
        }
    }

    fun readScalarGlyphMap(): Map<Int, UShort> = buildMap {
        var scalar = 0
        var glyph = 0
        repeat(readCount("scalar glyph map")) {
            if (compact) {
                scalar += readVarUInt()
                glyph += readVarInt()
            } else {
                scalar = readInt()
                glyph = readU16().toInt()
            }
            put(scalar, glyph.toGlyphId())
        }
    }

    fun readGlyphAlternatesMap(): Map<UShort, List<UShort>> = buildMap {
        var glyph = 0
        repeat(readCount("glyph alternates map")) {
            glyph = if (compact) glyph + readVarUInt() else readU16().toInt()
            var alternate = glyph
            put(glyph.toGlyphId(), List(readCount("glyph alternates")) {
                alternate = if (compact) alternate + readVarInt() else readU16().toInt()
                alternate.toGlyphId()
            })
        }
    }

    fun readDeviceAdjustmentMap(): Map<UShort, MathDeviceAdjustment> = buildMap {
        var glyph = 0
        repeat(readCount("device adjustment map")) {
            glyph = if (compact) glyph + readVarUInt() else readU16().toInt()
            val startPpem = readInt()
            val endPpem = readInt()
            val deltaFormat = readInt()
            val deltas = List(readCount("device adjustment values")) { readInt() }
            put(glyph.toGlyphId(), MathDeviceAdjustment(startPpem, endPpem, deltaFormat, deltas))
        }
    }

    fun readMathKernMap(): Map<UShort, MathGlyphKernInfo> = buildMap {
        var glyph = 0
        repeat(readCount("MathKern map")) {
            glyph = if (compact) glyph + readVarUInt() else readU16().toInt()
            put(glyph.toGlyphId(), MathGlyphKernInfo(readKernTable(), readKernTable(), readKernTable(), readKernTable()))
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
        var glyph = 0
        repeat(readCount("construction map")) {
            glyph = if (compact) glyph + readVarUInt() else readU16().toInt()
            var variantGlyph = glyph
            val variants = List(readCount("construction variants")) {
                variantGlyph = if (compact) variantGlyph + readVarInt() else readU16().toInt()
                MathGlyphVariant(variantGlyph.toGlyphId(), readInt())
            }
            val assembly = if (readBoolean()) {
                val minimumConnectorOverlap = readInt()
                val italicCorrection = readInt()
                var partGlyph = glyph
                val parts = List(readCount("assembly parts")) {
                    partGlyph = if (compact) partGlyph + readVarInt() else readU16().toInt()
                    MathGlyphAssemblyPart(partGlyph.toGlyphId(), readInt(), readInt(), readInt(), readBoolean())
                }
                MathGlyphAssembly(parts, minimumConnectorOverlap, italicCorrection)
            } else {
                null
            }
            put(glyph.toGlyphId(), MathGlyphConstruction(variants, assembly))
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
        val count = if (compact) readVarUInt() else readFixedInt()
        if (count < 0 || count > bytes.size) malformed("Invalid $context count $count in Tiqian MATH snapshot")
        return count
    }

    private fun readVarUInt(): Int {
        var value = 0
        var shift = 0
        while (shift < 35) {
            val byte = readByte()
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
        malformed("Invalid variable-length integer in Tiqian MATH snapshot")
    }

    private fun readVarInt(): Int {
        val value = readVarUInt()
        return (value ushr 1) xor -(value and 1)
    }

    private fun Int.toGlyphId(): UShort {
        if (this !in 0..0xffff) malformed("Invalid glyph id $this in Tiqian MATH snapshot")
        return toUShort()
    }

    private fun requireAvailable(count: Int) {
        if (count < 0 || offset > bytes.size - count) malformed("Truncated Tiqian MATH snapshot")
    }
}
