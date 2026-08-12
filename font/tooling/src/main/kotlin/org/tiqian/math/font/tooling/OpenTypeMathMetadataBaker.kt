package org.tiqian.math.font.tooling

import java.security.MessageDigest
import org.tiqian.math.font.opentype.MathDeviceAdjustment
import org.tiqian.math.font.opentype.MathGlyphAssembly
import org.tiqian.math.font.opentype.MathGlyphConstruction
import org.tiqian.math.font.opentype.MathGlyphKernInfo
import org.tiqian.math.font.opentype.MathKernTable
import org.tiqian.math.font.opentype.OpenTypeMathConstants
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.opentype.OpenTypeMathReader

data class BakedOpenTypeMathMetadata(
    val fontSha256: String,
    val snapshotBytes: ByteArray,
)

/** Build-time entry point. Runtime artifacts contain only the matching decoder. */
object OpenTypeMathMetadataBaker {
    fun bake(fontBytes: ByteArray): BakedOpenTypeMathMetadata {
        val digest = MessageDigest.getInstance("SHA-256").digest(fontBytes).toHex()
        return BakedOpenTypeMathMetadata(
            fontSha256 = digest,
            snapshotBytes = OpenTypeMathSnapshotEncoder.encode(
                digest,
                OpenTypeMathReader().read(fontBytes),
            ),
        )
    }
}
private object OpenTypeMathSnapshotEncoder {
    private const val FormatVersion = 1
    private val Magic = byteArrayOf(0x54, 0x51, 0x4D, 0x41, 0x54, 0x48, 0x00, 0x01)

    fun encode(fontSha256: String, font: OpenTypeMathFont): ByteArray {
        require(fontSha256.length == 64 && fontSha256.all { it in '0'..'9' || it in 'a'..'f' })
        val writer = SnapshotWriter()
        writer.writeBytes(Magic)
        writer.writeInt(FormatVersion)
        writer.writeString(fontSha256)
        writer.writeInt(font.unitsPerEm)
        writer.writeInt(font.lineMetrics.typoAscender)
        writer.writeInt(font.lineMetrics.typoDescender)
        writer.writeInt(font.lineMetrics.typoLineGap)
        writer.writeNullableInt(font.xHeight)
        writer.writeConstants(font.constants)
        writer.writeGlyphIntMap(font.italicCorrections)
        writer.writeDeviceAdjustmentMap(font.italicCorrectionDeviceAdjustments)
        writer.writeGlyphSet(font.unsupportedItalicCorrectionVariationAdjustments)
        writer.writeGlyphSet(font.extendedShapeGlyphs)
        writer.writeMathKernMap(font.mathKernInfo)
        writer.writeConstructionMap(font.verticalConstructions)
        writer.writeGlyphIntMap(font.topAccentAttachments)
        writer.writeDeviceAdjustmentMap(font.topAccentAttachmentDeviceAdjustments)
        writer.writeGlyphSet(font.unsupportedTopAccentAttachmentVariationAdjustments)
        writer.writeConstructionMap(font.horizontalConstructions)
        return writer.toByteArray()
    }
}

private class SnapshotWriter {
    private var bytes = ByteArray(4096)
    private var size = 0

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    fun writeBytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(bytes, size)
        size += value.size
    }

    fun writeInt(value: Int) {
        ensure(4)
        bytes[size++] = (value ushr 24).toByte()
        bytes[size++] = (value ushr 16).toByte()
        bytes[size++] = (value ushr 8).toByte()
        bytes[size++] = value.toByte()
    }

    fun writeU16(value: UShort) {
        ensure(2)
        val intValue = value.toInt()
        bytes[size++] = (intValue ushr 8).toByte()
        bytes[size++] = intValue.toByte()
    }

    fun writeBoolean(value: Boolean) {
        ensure(1)
        bytes[size++] = if (value) 1 else 0
    }

    fun writeString(value: String) {
        val encoded = value.encodeToByteArray()
        writeInt(encoded.size)
        writeBytes(encoded)
    }

    fun writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    fun writeConstants(value: OpenTypeMathConstants) {
        with(value) {
            listOf(
                scriptPercentScaleDown, scriptScriptPercentScaleDown, delimitedSubFormulaMinHeight,
                displayOperatorMinHeight, mathLeading, axisHeight, accentBaseHeight,
                flattenedAccentBaseHeight, subscriptShiftDown, subscriptTopMax,
                subscriptBaselineDropMin, superscriptShiftUp, superscriptShiftUpCramped,
                superscriptBottomMin, superscriptBaselineDropMax, subSuperscriptGapMin,
                superscriptBottomMaxWithSubscript, spaceAfterScript, upperLimitGapMin,
                upperLimitBaselineRiseMin, lowerLimitGapMin, lowerLimitBaselineDropMin,
                stackTopShiftUp, stackTopDisplayStyleShiftUp, stackBottomShiftDown,
                stackBottomDisplayStyleShiftDown, stackGapMin, stackDisplayStyleGapMin,
                fractionNumeratorShiftUp, fractionNumeratorDisplayStyleShiftUp,
                fractionDenominatorShiftDown, fractionDenominatorDisplayStyleShiftDown,
                fractionNumeratorGapMin, fractionNumDisplayStyleGapMin, fractionRuleThickness,
                fractionDenominatorGapMin, fractionDenomDisplayStyleGapMin, overbarVerticalGap,
                overbarRuleThickness, overbarExtraAscender, underbarVerticalGap,
                underbarRuleThickness, underbarExtraDescender, radicalVerticalGap,
                radicalDisplayStyleVerticalGap, radicalRuleThickness, radicalExtraAscender,
                radicalKernBeforeDegree, radicalKernAfterDegree, radicalDegreeBottomRaisePercent,
            ).forEach(::writeInt)
        }
    }

    fun writeGlyphIntMap(value: Map<UShort, Int>) {
        writeInt(value.size)
        value.entries.sortedBy { it.key.toInt() }.forEach { (glyph, measurement) ->
            writeU16(glyph)
            writeInt(measurement)
        }
    }

    fun writeGlyphSet(value: Set<UShort>) {
        writeInt(value.size)
        value.sorted().forEach(::writeU16)
    }

    fun writeDeviceAdjustmentMap(value: Map<UShort, MathDeviceAdjustment>) {
        writeInt(value.size)
        value.entries.sortedBy { it.key.toInt() }.forEach { (glyph, adjustment) ->
            writeU16(glyph)
            writeInt(adjustment.startPpem)
            writeInt(adjustment.endPpem)
            writeInt(adjustment.deltaFormat)
            writeInt(adjustment.deltasPx.size)
            adjustment.deltasPx.forEach(::writeInt)
        }
    }

    fun writeMathKernMap(value: Map<UShort, MathGlyphKernInfo>) {
        writeInt(value.size)
        value.entries.sortedBy { it.key.toInt() }.forEach { (glyph, info) ->
            writeU16(glyph)
            writeKernTable(info.topRight)
            writeKernTable(info.topLeft)
            writeKernTable(info.bottomRight)
            writeKernTable(info.bottomLeft)
        }
    }

    private fun writeKernTable(value: MathKernTable?) {
        writeBoolean(value != null)
        if (value == null) return
        writeInt(value.correctionHeights.size)
        value.correctionHeights.forEach(::writeInt)
        writeInt(value.kernValues.size)
        value.kernValues.forEach(::writeInt)
    }

    fun writeConstructionMap(value: Map<UShort, MathGlyphConstruction>) {
        writeInt(value.size)
        value.entries.sortedBy { it.key.toInt() }.forEach { (glyph, construction) ->
            writeU16(glyph)
            writeInt(construction.variants.size)
            construction.variants.forEach { variant ->
                writeU16(variant.glyphId)
                writeInt(variant.advanceMeasurement)
            }
            writeBoolean(construction.assembly != null)
            construction.assembly?.let(::writeAssembly)
        }
    }

    private fun writeAssembly(assembly: MathGlyphAssembly) {
        writeInt(assembly.minimumConnectorOverlap)
        writeInt(assembly.italicCorrection)
        writeInt(assembly.parts.size)
        assembly.parts.forEach { part ->
            writeU16(part.glyphId)
            writeInt(part.startConnectorLength)
            writeInt(part.endConnectorLength)
            writeInt(part.fullAdvance)
            writeBoolean(part.extender)
        }
    }

    private fun ensure(additional: Int) {
        val required = size + additional
        if (required <= bytes.size) return
        var newSize = bytes.size
        while (newSize < required) newSize *= 2
        bytes = bytes.copyOf(newSize)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
