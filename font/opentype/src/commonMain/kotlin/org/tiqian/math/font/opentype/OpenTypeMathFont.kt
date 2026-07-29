package org.tiqian.math.font.opentype

import org.tiqian.math.core.DiagnosticCode
import kotlin.math.ceil

data class OpenTypeMathConstants(
    val scriptPercentScaleDown: Int,
    val scriptScriptPercentScaleDown: Int,
    val delimitedSubFormulaMinHeight: Int,
    val displayOperatorMinHeight: Int,
    val mathLeading: Int,
    val axisHeight: Int,
    val subscriptShiftDown: Int,
    val subscriptTopMax: Int,
    val subscriptBaselineDropMin: Int,
    val superscriptShiftUp: Int,
    val superscriptShiftUpCramped: Int,
    val superscriptBottomMin: Int,
    val superscriptBaselineDropMax: Int,
    val subSuperscriptGapMin: Int,
    val superscriptBottomMaxWithSubscript: Int,
    val spaceAfterScript: Int,
    val upperLimitGapMin: Int,
    val upperLimitBaselineRiseMin: Int,
    val lowerLimitGapMin: Int,
    val lowerLimitBaselineDropMin: Int,
    val stackTopShiftUp: Int,
    val stackTopDisplayStyleShiftUp: Int,
    val stackBottomShiftDown: Int,
    val stackBottomDisplayStyleShiftDown: Int,
    val stackGapMin: Int,
    val stackDisplayStyleGapMin: Int,
    val fractionNumeratorShiftUp: Int,
    val fractionNumeratorDisplayStyleShiftUp: Int,
    val fractionDenominatorShiftDown: Int,
    val fractionDenominatorDisplayStyleShiftDown: Int,
    val fractionNumeratorGapMin: Int,
    val fractionNumDisplayStyleGapMin: Int,
    val fractionRuleThickness: Int,
    val fractionDenominatorGapMin: Int,
    val fractionDenomDisplayStyleGapMin: Int,
    val radicalVerticalGap: Int,
    val radicalDisplayStyleVerticalGap: Int,
    val radicalRuleThickness: Int,
    val radicalExtraAscender: Int,
    val radicalKernBeforeDegree: Int,
    val radicalKernAfterDegree: Int,
    val radicalDegreeBottomRaisePercent: Int,
)

data class MathGlyphVariant(
    val glyphId: UShort,
    val advanceMeasurement: Int,
)

data class MathGlyphAssemblyPart(
    val glyphId: UShort,
    val startConnectorLength: Int,
    val endConnectorLength: Int,
    val fullAdvance: Int,
    val extender: Boolean,
)

data class MathGlyphAssembly(
    val parts: List<MathGlyphAssemblyPart>,
    val minimumConnectorOverlap: Int,
    /** MATH GlyphAssembly italics correction, in design units. */
    val italicCorrection: Int = 0,
)

data class MathGlyphConstruction(
    val variants: List<MathGlyphVariant>,
    val assembly: MathGlyphAssembly?,
)

data class OpenTypeLineMetrics(
    val typoAscender: Int,
    /** OpenType stores descenders as a negative distance below the baseline. */
    val typoDescender: Int,
    val typoLineGap: Int,
)

enum class MathKernCorner {
    TopRight,
    TopLeft,
    BottomRight,
    BottomLeft,
}

data class MathKernTable(
    val correctionHeights: List<Int>,
    val kernValues: List<Int>,
) {
    init {
        require(kernValues.size == correctionHeights.size + 1)
    }

    fun valueAt(correctionHeight: Float): Int {
        val nextHigher = correctionHeights.indexOfFirst { correctionHeight < it }
        return kernValues[if (nextHigher < 0) kernValues.lastIndex else nextHigher]
    }
}

data class MathGlyphKernInfo(
    val topRight: MathKernTable?,
    val topLeft: MathKernTable?,
    val bottomRight: MathKernTable?,
    val bottomLeft: MathKernTable?,
) {
    fun table(corner: MathKernCorner): MathKernTable? = when (corner) {
        MathKernCorner.TopRight -> topRight
        MathKernCorner.TopLeft -> topLeft
        MathKernCorner.BottomRight -> bottomRight
        MathKernCorner.BottomLeft -> bottomLeft
    }
}

enum class MathConstructionKind {
    BaseGlyph,
    Variant,
    Assembly,
}

data class MathVerticalConstructionRequest(
    val baseGlyphId: UShort,
    val targetSizePx: Float,
    val fontSizePx: Float,
    val normalGlyphHeightPx: Float,
    val normalGlyphAdvanceWidthPx: Float,
)

data class MathGlyphComponent(
    val glyphId: UShort,
    /** Design-unit growth-axis offset; for a vertical assembly this locates the glyph bottom. */
    val offset: Float,
)

data class MathVerticalConstruction(
    val kind: MathConstructionKind,
    val components: List<MathGlyphComponent>,
    val advanceMeasurement: Float,
    val reachesTarget: Boolean,
    val connectorOverlaps: List<Float> = emptyList(),
    /** Number of instances inserted for each extender record. */
    val extenderRepetitions: Int = 0,
    /** Present only for a GlyphAssembly; variants use their glyph correction record. */
    val assemblyItalicCorrection: Int? = null,
    /** Validation evidence is retained when an assembly is selected or rejected. */
    val assemblyValidation: MathGlyphAssemblyValidation? = null,
    /** Named selection/construction algorithm for structured layout decisions. */
    val constructionPolicy: String = "OpenTypeMathVariants",
    /** MathML Core 5.3.1 uses one overlap value for every assembly connection. */
    val uniformConnectorOverlap: Float? = null,
    /** Advance in the direction orthogonal to stretching; pixels at the requested size. */
    val orthogonalAdvancePx: Float,
)

enum class MathGlyphAssemblyInvalidReason {
    NoExtender,
    NonPositiveExtenderGrowth,
    ConnectorShorterThanMinimumOverlap,
}

data class MathGlyphAssemblyValidation(
    val valid: Boolean,
    val invalidReasons: Set<MathGlyphAssemblyInvalidReason>,
    val extenderCount: Int,
    val nonExtenderCount: Int,
    val extenderNonOverlappingAdvance: Long,
    val checkedConnectionCount: Int,
    val validationPolicy: String,
    val specificationDivergence: String?,
)

data class OpenTypeMathFont(
    val bytes: ByteArray,
    val unitsPerEm: Int,
    val lineMetrics: OpenTypeLineMetrics,
    val constants: OpenTypeMathConstants,
    val italicCorrections: Map<UShort, Int>,
    val unsupportedItalicCorrectionAdjustments: Set<UShort>,
    val extendedShapeGlyphs: Set<UShort>,
    val mathKernInfo: Map<UShort, MathGlyphKernInfo>,
    val verticalConstructions: Map<UShort, MathGlyphConstruction>,
) {
    val verticalVariants: Map<UShort, List<MathGlyphVariant>>
        get() = verticalConstructions.mapValues { it.value.variants }

    fun scaleDesignUnits(value: Int, fontSizePx: Float): Float = value * fontSizePx / unitsPerEm

    fun scaleDesignUnits(value: Float, fontSizePx: Float): Float = value * fontSizePx / unitsPerEm

    fun italicCorrection(glyphId: UShort, fontSizePx: Float): Float {
        if (glyphId in unsupportedItalicCorrectionAdjustments) {
            throw OpenTypeMathException(
                DiagnosticCode.UnsupportedMathDeviceAdjustment,
                "MathItalicsCorrectionInfo[$glyphId] requires a device or variation adjustment",
            )
        }
        return scaleDesignUnits(italicCorrections[glyphId] ?: 0, fontSizePx)
    }

    fun mathKern(
        glyphId: UShort,
        corner: MathKernCorner,
        correctionHeightPx: Float,
        fontSizePx: Float,
    ): Float {
        val table = mathKernInfo[glyphId]?.table(corner) ?: return 0f
        val heightDesignUnits = correctionHeightPx * unitsPerEm / fontSizePx
        return scaleDesignUnits(table.valueAt(heightDesignUnits), fontSizePx)
    }

    fun verticalVariant(baseGlyphId: UShort, minimumAdvancePx: Float, fontSizePx: Float): MathGlyphVariant? {
        val variants = verticalVariants[baseGlyphId].orEmpty()
        if (variants.isEmpty()) return null
        val minimumDesignUnits = minimumAdvancePx * unitsPerEm / fontSizePx
        return variants.firstOrNull { it.advanceMeasurement >= minimumDesignUnits } ?: variants.last()
    }

    fun verticalConstruction(
        request: MathVerticalConstructionRequest,
        glyphAdvanceWidthPx: (UShort) -> Float,
    ): MathVerticalConstruction? {
        if (request.normalGlyphHeightPx >= request.targetSizePx) {
            return MathVerticalConstruction(
                kind = MathConstructionKind.BaseGlyph,
                components = listOf(MathGlyphComponent(request.baseGlyphId, 0f)),
                advanceMeasurement = request.normalGlyphHeightPx * unitsPerEm / request.fontSizePx,
                reachesTarget = true,
                constructionPolicy = "MathMLCore5.3.2NormalGlyph",
                orthogonalAdvancePx = request.normalGlyphAdvanceWidthPx,
            )
        }
        val construction = verticalConstructions[request.baseGlyphId] ?: return null
        val target = request.targetSizePx * unitsPerEm / request.fontSizePx
        construction.variants.firstOrNull { it.advanceMeasurement >= target }?.let { variant ->
            return MathVerticalConstruction(
                MathConstructionKind.Variant,
                listOf(MathGlyphComponent(variant.glyphId, 0f)),
                variant.advanceMeasurement.toFloat(),
                reachesTarget = true,
                constructionPolicy = "MathMLCore5.3.2Variant",
                orthogonalAdvancePx = glyphAdvanceWidthPx(variant.glyphId),
            )
        }
        val assembly = construction.assembly
        val assemblyValidation = assembly?.let(::validateAssembly)
        if (assembly != null && assemblyValidation?.valid == true) {
            return assemble(assembly, assemblyValidation, target, glyphAdvanceWidthPx)
        }
        val last = construction.variants.lastOrNull() ?: return null
        return MathVerticalConstruction(
            MathConstructionKind.Variant,
            listOf(MathGlyphComponent(last.glyphId, 0f)),
            last.advanceMeasurement.toFloat(),
            reachesTarget = last.advanceMeasurement >= target,
            assemblyValidation = assemblyValidation,
            constructionPolicy = if (assemblyValidation?.valid == false) {
                "MathMLCore5.3.2LastVariantAfterInvalidAssembly"
            } else {
                "MathMLCore5.3.2LastVariant"
            },
            orthogonalAdvancePx = glyphAdvanceWidthPx(last.glyphId),
        )
    }

    fun verticalAssemblyValidation(baseGlyphId: UShort): MathGlyphAssemblyValidation? =
        verticalConstructions[baseGlyphId]?.assembly?.let(::validateAssembly)

    private fun validateAssembly(assembly: MathGlyphAssembly): MathGlyphAssemblyValidation {
        val extenders = assembly.parts.filter { it.extender }
        val extenderAdvance = extenders.sumOf { it.fullAdvance.toLong() }
        val nonOverlappingAdvance = extenderAdvance -
            assembly.minimumConnectorOverlap.toLong() * extenders.size
        // Only connector ends that can meet another part are constrained. The bottom
        // non-extender's outer start and top non-extender's outer end are not connections;
        // real Lete/STIX constructions intentionally store zero there.
        val adjacentConnections = assembly.parts.zipWithNext()
        val shortAdjacentConnector = adjacentConnections.any { (lower, upper) ->
            lower.endConnectorLength < assembly.minimumConnectorOverlap ||
                upper.startConnectorLength < assembly.minimumConnectorOverlap
        }
        // An extender can be repeated next to itself even when it appears only once in the
        // source part list, so both of its connector ends participate in a possible join.
        val shortExtenderSelfConnector = extenders.any {
            it.startConnectorLength < assembly.minimumConnectorOverlap ||
                it.endConnectorLength < assembly.minimumConnectorOverlap
        }
        val reasons = buildSet {
            if (extenders.isEmpty()) add(MathGlyphAssemblyInvalidReason.NoExtender)
            if (nonOverlappingAdvance <= 0L) {
                add(MathGlyphAssemblyInvalidReason.NonPositiveExtenderGrowth)
            }
            if (shortAdjacentConnector || shortExtenderSelfConnector) {
                add(MathGlyphAssemblyInvalidReason.ConnectorShorterThanMinimumOverlap)
            }
        }
        return MathGlyphAssemblyValidation(
            valid = reasons.isEmpty(),
            invalidReasons = reasons,
            extenderCount = extenders.size,
            nonExtenderCount = assembly.parts.size - extenders.size,
            extenderNonOverlappingAdvance = nonOverlappingAdvance,
            checkedConnectionCount = adjacentConnections.size + extenders.size,
            validationPolicy = "TiqianOpenTypeTerminalConnectorCompatibility",
            specificationDivergence =
                "MathMLCore5.3.1RequiresEveryTerminalConnectorAtLeastMinimum",
        )
    }

    private fun assemble(
        assembly: MathGlyphAssembly,
        validation: MathGlyphAssemblyValidation,
        target: Float,
        glyphAdvanceWidthPx: (UShort) -> Float,
    ): MathVerticalConstruction {
        check(validation.valid)
        val extenders = assembly.parts.filter { it.extender }
        val nonExtenders = assembly.parts.filterNot { it.extender }
        val extenderAdvance = extenders.sumOf { it.fullAdvance }.toFloat()
        val nonExtenderAdvance = nonExtenders.sumOf { it.fullAdvance }.toFloat()
        val minimumOverlap = assembly.minimumConnectorOverlap.toFloat()
        val extenderGrowth = validation.extenderNonOverlappingAdvance.toFloat()
        // MathML Core 5.3.1 r_min: the smallest whole extender repetition count whose
        // assembly reaches T when every connection uses the minimum overlap.
        val repetitionsNumerator = target - nonExtenderAdvance +
            minimumOverlap * (validation.nonExtenderCount - 1)
        val rMin = ceil(repetitionsNumerator / extenderGrowth)
            .toInt()
            .coerceAtLeast(if (validation.nonExtenderCount == 0) 1 else 0)
        val sequence = buildList {
            assembly.parts.forEach { part ->
                if (!part.extender) add(part) else repeat(rMin) { add(part) }
            }
        }
        check(sequence.isNotEmpty())

        val connectionMaxima = sequence.zipWithNext { lower, upper ->
            minOf(lower.endConnectorLength, upper.startConnectorLength).toFloat()
        }
        val zeroOverlapSize = sequence.sumOf { it.fullAdvance }.toFloat()
        // MathML Core 5.3.1 o_max: one overlap for all connections, bounded by both
        // the target-derived theoretical value and every participating connector pair.
        val oMax = if (sequence.size <= 1) {
            0f
        } else {
            val oMaxTheoretical = (zeroOverlapSize - target) / (sequence.size - 1)
            minOf(oMaxTheoretical, connectionMaxima.min()).coerceAtLeast(minimumOverlap)
        }
        val overlaps = List(connectionMaxima.size) { oMax }

        var offset = 0f
        val components = sequence.mapIndexed { index, part ->
            MathGlyphComponent(part.glyphId, offset).also {
                if (index < sequence.lastIndex) {
                    offset += part.fullAdvance - overlaps[index]
                }
            }
        }
        val actualAdvance = offset + sequence.last().fullAdvance
        return MathVerticalConstruction(
            MathConstructionKind.Assembly,
            components,
            actualAdvance,
            reachesTarget = actualAdvance + ASSEMBLY_REACH_EPSILON_DESIGN_UNITS >= target,
            connectorOverlaps = overlaps,
            extenderRepetitions = rMin,
            assemblyItalicCorrection = assembly.italicCorrection,
            assemblyValidation = validation,
            constructionPolicy = "MathMLCore5.3.1UniformOverlap",
            uniformConnectorOverlap = oMax,
            // MathML Core 5.3.1 defines the vertical assembly's orthogonal width from
            // every part record, including an extender skipped when rMin is zero.
            orthogonalAdvancePx = assembly.parts.maxOf { glyphAdvanceWidthPx(it.glyphId) },
        )
    }

    private companion object {
        const val ASSEMBLY_REACH_EPSILON_DESIGN_UNITS = 0.01f
    }
}

class OpenTypeMathException(
    val diagnosticCode: DiagnosticCode,
    message: String,
) : IllegalArgumentException(message)

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
        val extendedShapeGlyphs = readExtendedShapeCoverage(reader, glyphInfoBase)
        val mathKernInfo = readMathKernInfo(reader, glyphInfoBase)
        val verticalConstructions = readVerticalConstructions(reader, variantsBase)
        return OpenTypeMathFont(
            bytes = bytes.copyOf(),
            unitsPerEm = unitsPerEm,
            lineMetrics = lineMetrics,
            constants = constants,
            italicCorrections = italicCorrections.values,
            unsupportedItalicCorrectionAdjustments = italicCorrections.unsupportedAdjustments,
            extendedShapeGlyphs = extendedShapeGlyphs,
            mathKernInfo = mathKernInfo,
            verticalConstructions = verticalConstructions,
        )
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
        if (italicOffset == 0) return ItalicCorrectionData(emptyMap(), emptySet())
        val italicBase = glyphInfoBase + italicOffset
        reader.requireRange(italicBase, 4)
        val coverageOffset = reader.u16(italicBase)
        val count = reader.u16(italicBase + 2)
        reader.requireRange(italicBase + 4, count * 4)
        val coverage = readCoverage(reader, italicBase + coverageOffset)
        if (coverage.size != count) malformed("MATH italic coverage count does not match value count")
        val unsupported = mutableSetOf<UShort>()
        val values = coverage.mapIndexed { index, glyphId ->
            val record = italicBase + 4 + index * 4
            reader.requireRange(record, 4)
            if (reader.u16(record + 2) != 0) unsupported += glyphId
            glyphId to reader.s16(record)
        }.toMap()
        return ItalicCorrectionData(values, unsupported)
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

    private fun readVerticalConstructions(reader: BigEndianReader, variantsBase: Int): Map<UShort, MathGlyphConstruction> {
        reader.requireRange(variantsBase, 10)
        val minimumConnectorOverlap = reader.u16(variantsBase)
        val verticalCoverageOffset = reader.u16(variantsBase + 2)
        val verticalCount = reader.u16(variantsBase + 6)
        reader.requireRange(variantsBase + 10, verticalCount * 2)
        if (verticalCount == 0 || verticalCoverageOffset == 0) return emptyMap()
        val coverage = readCoverage(reader, variantsBase + verticalCoverageOffset)
        if (coverage.size != verticalCount) malformed("MATH vertical coverage count does not match construction count")
        return coverage.mapIndexed { index, glyphId ->
            val constructionOffset = reader.u16(variantsBase + 10 + index * 2)
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

    private data class TableRecord(val offset: Int, val length: Int)
    private data class ItalicCorrectionData(
        val values: Map<UShort, Int>,
        val unsupportedAdjustments: Set<UShort>,
    )

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
