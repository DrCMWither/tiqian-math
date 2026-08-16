package org.tiqian.math.font.opentype

import org.tiqian.math.core.DiagnosticCode

class OpenTypeMathReader {
    fun read(bytes: ByteArray): OpenTypeMathFont {
        val reader = BigEndianReader(bytes)
        if (bytes.size < 12) malformed("Font is shorter than an sfnt header")
        val signature = reader.tag(0)
        if (signature != "OTTO" && signature != "\u0000\u0001\u0000\u0000") {
            malformed("Unsupported sfnt signature ${signature.escapeForMessage()}")
        }
        val tableCount = reader.u16(4)
        val tables = buildMap {
            repeat(tableCount) { tableIndex ->
                val record = 12 + tableIndex * 16
                reader.requireRange(record, 16)
                val tag = reader.tag(record)
                val offset = reader.u32(record + 8)
                val length = reader.u32(record + 12)
                reader.requireRange(offset, length)
                put(tag, TableRecord(offset, length))
            }
        }

        val head = tables["head"] ?: malformed("Font has no head table")
        reader.requireRange(head.offset + 18, 2)
        val unitsPerEm = reader.u16(head.offset + 18)
        if (unitsPerEm <= 0) malformed("head.unitsPerEm is zero")
        val lineMetrics = readLineMetrics(reader, tables)

        val math = tables["MATH"] ?: throw OpenTypeMathException(
            DiagnosticCode.MissingMathTable,
            "Font has no OpenType MATH table",
        )
        reader.requireRange(math.offset, 10)
        val majorVersion = reader.u16(math.offset)
        val minorVersion = reader.u16(math.offset + 2)
        if (majorVersion != 1 || minorVersion != 0) {
            malformed("Unsupported MATH version $majorVersion.$minorVersion")
        }

        val constantsBase = math.offset + reader.u16(math.offset + 4)
        val glyphInfoBase = math.offset + reader.u16(math.offset + 6)
        val variantsBase = math.offset + reader.u16(math.offset + 8)
        val constants = readConstants(reader, constantsBase)
        val italicCorrections = readItalicCorrections(reader, glyphInfoBase)
        val topAccentAttachments = readTopAccentAttachments(reader, glyphInfoBase)
        val extendedShapeGlyphs = readExtendedShapeCoverage(reader, glyphInfoBase)
        val mathKernInfo = readMathKernInfo(reader, glyphInfoBase)
        val verticalConstructions = readConstructions(reader, variantsBase, vertical = true)
        val horizontalConstructions = readConstructions(reader, variantsBase, vertical = false)
        val characterGlyphs = readCharacterGlyphs(reader, tables)
        val scriptStyleAlternates = readScriptStyleAlternates(reader, tables)
        return OpenTypeMathFont(
            bytes = bytes.copyOf(),
            unitsPerEm = unitsPerEm,
            lineMetrics = lineMetrics,
            xHeight = readXHeight(reader, tables),
            constants = constants,
            italicCorrections = italicCorrections.values,
            italicCorrectionDeviceAdjustments = italicCorrections.deviceAdjustments,
            unsupportedItalicCorrectionVariationAdjustments = italicCorrections.unsupportedVariationAdjustments,
            extendedShapeGlyphs = extendedShapeGlyphs,
            mathKernInfo = mathKernInfo,
            verticalConstructions = verticalConstructions,
            topAccentAttachments = topAccentAttachments.values,
            topAccentAttachmentDeviceAdjustments = topAccentAttachments.deviceAdjustments,
            unsupportedTopAccentAttachmentVariationAdjustments =
                topAccentAttachments.unsupportedVariationAdjustments,
            horizontalConstructions = horizontalConstructions,
            characterGlyphs = characterGlyphs,
            scriptStyleAlternates = scriptStyleAlternates,
        )
    }

    private fun readCharacterGlyphs(
        reader: BigEndianReader,
        tables: Map<String, TableRecord>,
    ): Map<Int, UShort> {
        val cmap = tables["cmap"] ?: malformed("Font has no cmap table")
        reader.requireRange(cmap.offset, 4)
        val recordCount = reader.u16(cmap.offset + 2)
        val candidates = buildList {
            repeat(recordCount) { index ->
                val record = cmap.offset + 4 + index * 8
                reader.requireRange(record, 8)
                val platform = reader.u16(record)
                val encoding = reader.u16(record + 2)
                val subtable = cmap.offset + reader.u32(record + 4)
                val format = reader.u16(subtable)
                val priority = when {
                    format == 12 && platform == 3 && encoding == 10 -> 0
                    format == 12 && platform == 0 -> 1
                    format == 4 && platform == 3 && encoding == 1 -> 2
                    format == 4 && platform == 0 -> 3
                    else -> 4
                }
                if (priority < 4) add(Triple(priority, format, subtable))
            }
        }
        if (candidates.isEmpty()) malformed("Font has no Unicode cmap")
        return buildMap {
            candidates
                .sortedBy { it.first }
                .distinctBy { it.third }
                .forEach { (_, format, subtable) ->
                    val mappings = when (format) {
                        12 -> readFormat12Cmap(reader, subtable)
                        4 -> readFormat4Cmap(reader, subtable)
                        else -> malformed("Unsupported selected cmap format $format")
                    }
                    mappings.forEach { (scalar, glyph) ->
                        if (scalar !in this) put(scalar, glyph)
                    }
                }
        }
    }

    private fun readFormat12Cmap(reader: BigEndianReader, base: Int): Map<Int, UShort> = buildMap {
        reader.requireRange(base, 16)
        val groupCount = reader.u32(base + 12)
        repeat(groupCount) { index ->
            val group = base + 16 + index * 12
            val start = reader.u32(group)
            val end = reader.u32(group + 4)
            val startGlyph = reader.u32(group + 8)
            if (end < start) malformed("cmap format 12 group is reversed")
            for (scalar in start..end) {
                if (scalar in MathGlyphReplayScalarBase..0xFFFFD) continue
                val glyph = startGlyph + scalar - start
                if (glyph in 1..0xFFFF) put(scalar, glyph.toUShort())
            }
        }
    }

    private fun readFormat4Cmap(reader: BigEndianReader, base: Int): Map<Int, UShort> = buildMap {
        reader.requireRange(base, 14)
        val length = reader.u16(base + 2)
        reader.requireRange(base, length)
        val segmentCount = reader.u16(base + 6) / 2
        val endCodes = base + 14
        val startCodes = endCodes + segmentCount * 2 + 2
        val deltas = startCodes + segmentCount * 2
        val rangeOffsets = deltas + segmentCount * 2
        repeat(segmentCount) { index ->
            val start = reader.u16(startCodes + index * 2)
            val end = reader.u16(endCodes + index * 2)
            val delta = reader.s16(deltas + index * 2)
            val rangeOffsetAddress = rangeOffsets + index * 2
            val rangeOffset = reader.u16(rangeOffsetAddress)
            if (end < start) malformed("cmap format 4 segment is reversed")
            for (scalar in start..end) {
                if (scalar == 0xFFFF) continue
                val glyph = if (rangeOffset == 0) {
                    (scalar + delta) and 0xFFFF
                } else {
                    val address = rangeOffsetAddress + rangeOffset + (scalar - start) * 2
                    val raw = reader.u16(address)
                    if (raw == 0) 0 else (raw + delta) and 0xFFFF
                }
                if (glyph != 0) put(scalar, glyph.toUShort())
            }
        }
    }

    private fun readScriptStyleAlternates(
        reader: BigEndianReader,
        tables: Map<String, TableRecord>,
    ): Map<UShort, List<UShort>> {
        val gsub = tables["GSUB"] ?: return emptyMap()
        reader.requireRange(gsub.offset, 10)
        val featureList = gsub.offset + reader.u16(gsub.offset + 6)
        val lookupList = gsub.offset + reader.u16(gsub.offset + 8)
        val featureCount = reader.u16(featureList)
        val lookupIndices = buildList {
            repeat(featureCount) { index ->
                val record = featureList + 2 + index * 6
                if (reader.tag(record) != "ssty") return@repeat
                val feature = featureList + reader.u16(record + 4)
                val count = reader.u16(feature + 2)
                repeat(count) { add(reader.u16(feature + 4 + it * 2)) }
            }
        }.distinct()
        if (lookupIndices.isEmpty()) return emptyMap()
        val lookupCount = reader.u16(lookupList)
        return buildMap {
            lookupIndices.forEach { lookupIndex ->
                if (lookupIndex >= lookupCount) malformed("GSUB ssty lookup index is out of range")
                val lookup = lookupList + reader.u16(lookupList + 2 + lookupIndex * 2)
                val type = reader.u16(lookup)
                val subtableCount = reader.u16(lookup + 4)
                repeat(subtableCount) { subtableIndex ->
                    val subtable = lookup + reader.u16(lookup + 6 + subtableIndex * 2)
                    when (type) {
                        3 -> putAll(readAlternateSubstitution(reader, subtable))
                        7 -> {
                            if (reader.u16(subtable) != 1) malformed("Unsupported GSUB extension format")
                            if (reader.u16(subtable + 2) == 3) {
                                putAll(readAlternateSubstitution(reader, subtable + reader.u32(subtable + 4)))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun readAlternateSubstitution(
        reader: BigEndianReader,
        base: Int,
    ): Map<UShort, List<UShort>> {
        if (reader.u16(base) != 1) malformed("Unsupported GSUB AlternateSubst format")
        val coverage = readCoverage(reader, base + reader.u16(base + 2))
        val setCount = reader.u16(base + 4)
        if (setCount != coverage.size) malformed("GSUB AlternateSubst coverage size mismatch")
        return coverage.indices.associate { index ->
            val set = base + reader.u16(base + 6 + index * 2)
            val count = reader.u16(set)
            coverage[index] to List(count) { reader.u16(set + 2 + it * 2).toUShort() }
        }
    }

    private fun readLineMetrics(
        reader: BigEndianReader,
        tables: Map<String, TableRecord>,
    ): OpenTypeLineMetrics {
        val os2 = tables["OS/2"]
        if (os2 != null && os2.length >= 74) {
            return OpenTypeLineMetrics(
                typoAscender = reader.s16(os2.offset + 68),
                typoDescender = reader.s16(os2.offset + 70),
                typoLineGap = reader.s16(os2.offset + 72),
            )
        }
        val hhea = tables["hhea"] ?: malformed("Font has neither usable OS/2 typographic metrics nor hhea")
        reader.requireRange(hhea.offset + 4, 6)
        return OpenTypeLineMetrics(
            typoAscender = reader.s16(hhea.offset + 4),
            typoDescender = reader.s16(hhea.offset + 6),
            typoLineGap = reader.s16(hhea.offset + 8),
        )
    }

    private fun readXHeight(
        reader: BigEndianReader,
        tables: Map<String, TableRecord>,
    ): Int? {
        val os2 = tables["OS/2"] ?: return null
        if (os2.length < 88 || reader.u16(os2.offset) < 2) return null
        val value = reader.s16(os2.offset + 86)
        return value.takeIf { it > 0 }
    }

    private fun readConstants(reader: BigEndianReader, base: Int): OpenTypeMathConstants {
        reader.requireRange(base, MATH_CONSTANTS_BYTES)
        fun value(index: Int): Int = readStaticMathValue(reader, base + 8 + index * 4, "MathConstants[$index]")
        return OpenTypeMathConstants(
            scriptPercentScaleDown = reader.s16(base),
            scriptScriptPercentScaleDown = reader.s16(base + 2),
            delimitedSubFormulaMinHeight = reader.u16(base + 4),
            displayOperatorMinHeight = reader.u16(base + 6),
            mathLeading = value(0),
            axisHeight = value(1),
            accentBaseHeight = value(2),
            flattenedAccentBaseHeight = value(3),
            subscriptShiftDown = value(4),
            subscriptTopMax = value(5),
            subscriptBaselineDropMin = value(6),
            superscriptShiftUp = value(7),
            superscriptShiftUpCramped = value(8),
            superscriptBottomMin = value(9),
            superscriptBaselineDropMax = value(10),
            subSuperscriptGapMin = value(11),
            superscriptBottomMaxWithSubscript = value(12),
            spaceAfterScript = value(13),
            upperLimitGapMin = value(14),
            upperLimitBaselineRiseMin = value(15),
            lowerLimitGapMin = value(16),
            lowerLimitBaselineDropMin = value(17),
            stackTopShiftUp = value(18),
            stackTopDisplayStyleShiftUp = value(19),
            stackBottomShiftDown = value(20),
            stackBottomDisplayStyleShiftDown = value(21),
            stackGapMin = value(22),
            stackDisplayStyleGapMin = value(23),
            fractionNumeratorShiftUp = value(28),
            fractionNumeratorDisplayStyleShiftUp = value(29),
            fractionDenominatorShiftDown = value(30),
            fractionDenominatorDisplayStyleShiftDown = value(31),
            fractionNumeratorGapMin = value(32),
            fractionNumDisplayStyleGapMin = value(33),
            fractionRuleThickness = value(34),
            fractionDenominatorGapMin = value(35),
            fractionDenomDisplayStyleGapMin = value(36),
            overbarVerticalGap = value(39),
            overbarRuleThickness = value(40),
            overbarExtraAscender = value(41),
            underbarVerticalGap = value(42),
            underbarRuleThickness = value(43),
            underbarExtraDescender = value(44),
            radicalVerticalGap = value(45),
            radicalDisplayStyleVerticalGap = value(46),
            radicalRuleThickness = value(47),
            radicalExtraAscender = value(48),
            radicalKernBeforeDegree = value(49),
            radicalKernAfterDegree = value(50),
            radicalDegreeBottomRaisePercent = reader.s16(base + 8 + MATH_VALUE_COUNT * 4),
        )
    }

    private fun readItalicCorrections(reader: BigEndianReader, glyphInfoBase: Int): ItalicCorrectionData {
        reader.requireRange(glyphInfoBase, 8)
        val italicOffset = reader.u16(glyphInfoBase)
        if (italicOffset == 0) return ItalicCorrectionData(emptyMap(), emptyMap(), emptySet())
        val italicBase = glyphInfoBase + italicOffset
        reader.requireRange(italicBase, 4)
        val coverageOffset = reader.u16(italicBase)
        val count = reader.u16(italicBase + 2)
        reader.requireRange(italicBase + 4, count * 4)
        val coverage = readCoverage(reader, italicBase + coverageOffset)
        if (coverage.size != count) malformed("MATH italic coverage count does not match value count")
        val devices = mutableMapOf<UShort, MathDeviceAdjustment>()
        val unsupportedVariations = mutableSetOf<UShort>()
        val values = coverage.mapIndexed { index, glyphId ->
            val record = italicBase + 4 + index * 4
            reader.requireRange(record, 4)
            when (val adjustment = readMathValueAdjustment(reader, italicBase, record, "MathItalicsCorrectionInfo[$glyphId]")) {
                is ParsedMathValueAdjustment.Device -> devices[glyphId] = adjustment.value
                ParsedMathValueAdjustment.VariationIndex -> unsupportedVariations += glyphId
                null -> Unit
            }
            glyphId to reader.s16(record)
        }.toMap()
        return ItalicCorrectionData(values, devices, unsupportedVariations)
    }

    private fun readTopAccentAttachments(
        reader: BigEndianReader,
        glyphInfoBase: Int,
    ): TopAccentAttachmentData {
        reader.requireRange(glyphInfoBase, 8)
        val offset = reader.u16(glyphInfoBase + 2)
        if (offset == 0) return TopAccentAttachmentData(emptyMap(), emptyMap(), emptySet())
        val base = glyphInfoBase + offset
        reader.requireRange(base, 4)
        val coverageOffset = reader.u16(base)
        val count = reader.u16(base + 2)
        reader.requireRange(base + 4, count * 4)
        val coverage = readCoverage(reader, base + coverageOffset)
        if (coverage.size != count) malformed("MATH top accent coverage count does not match value count")
        val devices = mutableMapOf<UShort, MathDeviceAdjustment>()
        val unsupportedVariations = mutableSetOf<UShort>()
        val values = coverage.mapIndexed { index, glyphId ->
            val record = base + 4 + index * 4
            when (val adjustment = readMathValueAdjustment(reader, base, record, "MathTopAccentAttachment[$glyphId]")) {
                is ParsedMathValueAdjustment.Device -> devices[glyphId] = adjustment.value
                ParsedMathValueAdjustment.VariationIndex -> unsupportedVariations += glyphId
                null -> Unit
            }
            glyphId to reader.s16(record)
        }.toMap()
        return TopAccentAttachmentData(values, devices, unsupportedVariations)
    }

    private fun readMathKernInfo(reader: BigEndianReader, glyphInfoBase: Int): Map<UShort, MathGlyphKernInfo> {
        reader.requireRange(glyphInfoBase, 8)
        val kernInfoOffset = reader.u16(glyphInfoBase + 6)
        if (kernInfoOffset == 0) return emptyMap()
        val kernInfoBase = glyphInfoBase + kernInfoOffset
        reader.requireRange(kernInfoBase, 4)
        val coverageOffset = reader.u16(kernInfoBase)
        val count = reader.u16(kernInfoBase + 2)
        reader.requireRange(kernInfoBase + 4, count * 8)
        val coverage = readCoverage(reader, kernInfoBase + coverageOffset)
        if (coverage.size != count) malformed("MATH kern coverage count does not match record count")
        return coverage.mapIndexed { index, glyphId ->
            val record = kernInfoBase + 4 + index * 8
            fun tableAt(fieldOffset: Int): MathKernTable? {
                val offset = reader.u16(record + fieldOffset)
                return if (offset == 0) null else readMathKernTable(reader, kernInfoBase + offset)
            }
            glyphId to MathGlyphKernInfo(
                topRight = tableAt(0),
                topLeft = tableAt(2),
                bottomRight = tableAt(4),
                bottomLeft = tableAt(6),
            )
        }.toMap()
    }

    private fun readExtendedShapeCoverage(reader: BigEndianReader, glyphInfoBase: Int): Set<UShort> {
        reader.requireRange(glyphInfoBase, 8)
        val coverageOffset = reader.u16(glyphInfoBase + 4)
        return if (coverageOffset == 0) emptySet() else readCoverage(reader, glyphInfoBase + coverageOffset).toSet()
    }

    private fun readMathKernTable(reader: BigEndianReader, base: Int): MathKernTable {
        reader.requireRange(base, 2)
        val heightCount = reader.u16(base)
        reader.requireRange(base + 2, heightCount * 4 + (heightCount + 1) * 4)
        val heights = List(heightCount) { index ->
            readStaticMathValue(reader, base + 2 + index * 4, "MathKern.correctionHeight[$index]")
        }
        if (heights.zipWithNext().any { (left, right) -> left > right }) {
            malformed("MATH kern correction heights are not sorted")
        }
        val valuesBase = base + 2 + heightCount * 4
        val values = List(heightCount + 1) { index ->
            readStaticMathValue(reader, valuesBase + index * 4, "MathKern.kernValue[$index]")
        }
        return MathKernTable(heights, values)
    }

    private fun readConstructions(
        reader: BigEndianReader,
        variantsBase: Int,
        vertical: Boolean,
    ): Map<UShort, MathGlyphConstruction> {
        reader.requireRange(variantsBase, 10)
        val minimumConnectorOverlap = reader.u16(variantsBase)
        val verticalCount = reader.u16(variantsBase + 6)
        val count = reader.u16(variantsBase + if (vertical) 6 else 8)
        val coverageOffset = reader.u16(variantsBase + if (vertical) 2 else 4)
        val offsetsBase = variantsBase + 10 + if (vertical) 0 else verticalCount * 2
        reader.requireRange(offsetsBase, count * 2)
        if (count == 0 || coverageOffset == 0) return emptyMap()
        val coverage = readCoverage(reader, variantsBase + coverageOffset)
        if (coverage.size != count) {
            malformed("MATH ${if (vertical) "vertical" else "horizontal"} coverage count does not match construction count")
        }
        return coverage.mapIndexed { index, glyphId ->
            val constructionOffset = reader.u16(offsetsBase + index * 2)
            val construction = variantsBase + constructionOffset
            reader.requireRange(construction, 4)
            val assemblyOffset = reader.u16(construction)
            val variantCount = reader.u16(construction + 2)
            reader.requireRange(construction + 4, variantCount * 4)
            val variants = List(variantCount) { variantIndex ->
                val record = construction + 4 + variantIndex * 4
                MathGlyphVariant(
                    glyphId = reader.u16(record).toUShort(),
                    advanceMeasurement = reader.u16(record + 2),
                )
            }
            val assembly = if (assemblyOffset == 0) {
                null
            } else {
                val assemblyBase = construction + assemblyOffset
                reader.requireRange(assemblyBase, 6)
                val italicCorrection = readStaticMathValue(
                    reader,
                    assemblyBase,
                    "GlyphAssembly.italicsCorrection",
                )
                val partCount = reader.u16(assemblyBase + 4)
                reader.requireRange(assemblyBase + 6, partCount * 10)
                MathGlyphAssembly(
                    parts = List(partCount) { partIndex ->
                        val part = assemblyBase + 6 + partIndex * 10
                        MathGlyphAssemblyPart(
                            glyphId = reader.u16(part).toUShort(),
                            startConnectorLength = reader.u16(part + 2),
                            endConnectorLength = reader.u16(part + 4),
                            fullAdvance = reader.u16(part + 6),
                            extender = reader.u16(part + 8) and 0x0001 != 0,
                        )
                    },
                    minimumConnectorOverlap = minimumConnectorOverlap,
                    italicCorrection = italicCorrection,
                )
            }
            glyphId to MathGlyphConstruction(variants, assembly)
        }.toMap()
    }

    private fun readCoverage(reader: BigEndianReader, base: Int): List<UShort> {
        reader.requireRange(base, 4)
        return when (val format = reader.u16(base)) {
            1 -> {
                val count = reader.u16(base + 2)
                reader.requireRange(base + 4, count * 2)
                List(count) { reader.u16(base + 4 + it * 2).toUShort() }
            }
            2 -> {
                val rangeCount = reader.u16(base + 2)
                reader.requireRange(base + 4, rangeCount * 6)
                val indexed = mutableMapOf<Int, UShort>()
                repeat(rangeCount) { rangeIndex ->
                    val record = base + 4 + rangeIndex * 6
                    val startGlyph = reader.u16(record)
                    val endGlyph = reader.u16(record + 2)
                    val startCoverageIndex = reader.u16(record + 4)
                    if (endGlyph < startGlyph) malformed("MATH coverage range is reversed")
                    for (glyph in startGlyph..endGlyph) {
                        indexed[startCoverageIndex + glyph - startGlyph] = glyph.toUShort()
                    }
                }
                if (indexed.isEmpty()) emptyList() else List(indexed.keys.max() + 1) { coverageIndex ->
                    indexed[coverageIndex] ?: malformed("MATH coverage indices are not contiguous")
                }
            }
            else -> malformed("Unsupported MATH coverage format $format")
        }
    }

    private fun malformed(message: String): Nothing = throw OpenTypeMathException(
        DiagnosticCode.MalformedFont,
        message,
    )

    private fun readStaticMathValue(reader: BigEndianReader, record: Int, context: String): Int {
        reader.requireRange(record, 4)
        val deviceOrVariationOffset = reader.u16(record + 2)
        if (deviceOrVariationOffset != 0) {
            throw OpenTypeMathException(
                DiagnosticCode.UnsupportedMathDeviceAdjustment,
                "$context uses a device or variation adjustment that this static-font slice cannot apply",
            )
        }
        return reader.s16(record)
    }

    private fun readMathValueAdjustment(
        reader: BigEndianReader,
        parentTableBase: Int,
        record: Int,
        context: String,
    ): ParsedMathValueAdjustment? {
        reader.requireRange(record, 4)
        val offset = reader.u16(record + 2)
        if (offset == 0) return null
        val adjustmentBase = parentTableBase + offset
        reader.requireRange(adjustmentBase, 6)
        val startOrOuter = reader.u16(adjustmentBase)
        val endOrInner = reader.u16(adjustmentBase + 2)
        return when (val format = reader.u16(adjustmentBase + 4)) {
            1, 2, 3 -> {
                if (endOrInner < startOrOuter) malformed("$context Device table has a reversed ppem range")
                val bits = when (format) {
                    1 -> 2
                    2 -> 4
                    else -> 8
                }
                val count = endOrInner - startOrOuter + 1
                val wordCount = (count * bits + 15) / 16
                reader.requireRange(adjustmentBase + 6, wordCount * 2)
                val mask = (1 shl bits) - 1
                val signBit = 1 shl (bits - 1)
                val deltas = List(count) { index ->
                    val bitOffset = index * bits
                    val word = reader.u16(adjustmentBase + 6 + (bitOffset / 16) * 2)
                    val shift = 16 - bits - bitOffset % 16
                    val raw = (word ushr shift) and mask
                    if (raw and signBit != 0) raw - (1 shl bits) else raw
                }
                ParsedMathValueAdjustment.Device(
                    MathDeviceAdjustment(startOrOuter, endOrInner, format, deltas),
                )
            }
            0x8000 -> ParsedMathValueAdjustment.VariationIndex
            else -> malformed("$context uses reserved Device/VariationIndex format $format")
        }
    }

    private data class TableRecord(val offset: Int, val length: Int)

    private data class TopAccentAttachmentData(
        val values: Map<UShort, Int>,
        val deviceAdjustments: Map<UShort, MathDeviceAdjustment>,
        val unsupportedVariationAdjustments: Set<UShort>,
    )

    private data class ItalicCorrectionData(
        val values: Map<UShort, Int>,
        val deviceAdjustments: Map<UShort, MathDeviceAdjustment>,
        val unsupportedVariationAdjustments: Set<UShort>,
    )

    private sealed interface ParsedMathValueAdjustment {
        data class Device(val value: MathDeviceAdjustment) : ParsedMathValueAdjustment
        data object VariationIndex : ParsedMathValueAdjustment
    }

    private companion object {
        const val MATH_VALUE_COUNT = 51
        const val MATH_CONSTANTS_BYTES = 8 + MATH_VALUE_COUNT * 4 + 2
    }
}

private class BigEndianReader(private val bytes: ByteArray) {
    fun u16(offset: Int): Int {
        requireRange(offset, 2)
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    fun s16(offset: Int): Int = u16(offset).toShort().toInt()

    fun u32(offset: Int): Int {
        requireRange(offset, 4)
        val value = ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
        if (value > Int.MAX_VALUE) throw OpenTypeMathException(
            DiagnosticCode.MalformedFont,
            "sfnt offset exceeds supported byte array range",
        )
        return value.toInt()
    }

    fun tag(offset: Int): String {
        requireRange(offset, 4)
        return buildString(4) {
            repeat(4) { append(bytes[offset + it].toInt().and(0xFF).toChar()) }
        }
    }

    fun requireRange(offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw OpenTypeMathException(
                DiagnosticCode.MalformedFont,
                "sfnt read [$offset, ${offset + length}) exceeds ${bytes.size} bytes",
            )
        }
    }
}

private fun String.escapeForMessage(): String = buildString {
    this@escapeForMessage.forEach { character ->
        if (character.code in 0x20..0x7E) append(character) else append("\\u${character.code.toString(16).padStart(4, '0')}")
    }
}
