package org.tiqian.math.layout

import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.MathConstructionKind
import org.tiqian.math.font.opentype.MathDeviceAdjustment
import org.tiqian.math.font.opentype.MathGlyphComponent
import org.tiqian.math.font.opentype.MathHorizontalConstructionRequest
import org.tiqian.math.font.opentype.MathKernCorner
import org.tiqian.math.font.opentype.MathVerticalConstruction
import org.tiqian.math.font.opentype.MathVerticalConstructionRequest
import org.tiqian.math.font.opentype.MathVerticalAssemblyPolicy
import org.tiqian.math.font.opentype.OpenTypeMathConstants
import org.tiqian.math.font.opentype.OpenTypeMathException
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.parser.MacroExpansionLimits
import org.tiqian.math.parser.MathFormulaParser
import org.tiqian.math.parser.MathMacroDefinition
import org.tiqian.math.parser.MathParser
import kotlin.math.floor
import kotlin.math.max

data class MathLayoutOptions(
    val mode: MathMode = MathMode.Inline,
    val fontSizePx: Float = 24f,
    /** Primarily for embedding in an existing TeX-style context; null derives from [mode]. */
    val initialStyle: MathStyle? = null,
    /**
     * Formula-scoped equivalent of TeX's `\nulldelimiterspace`; fixed across math styles.
     * Null keeps the plain-TeX 1.2pt-to-10pt proportion at the formula's text size.
     */
    val nullDelimiterSpacePx: Float? = null,
    /**
     * Formula-scoped equivalent of TeX's `\scriptspace`; fixed across math styles.
     * Null keeps plain TeX/XeTeX's 0.5pt value. The OpenType MATH `SpaceAfterScript`
     * constant remains available to non-TeX dialects, but is not XeTeX's default.
     */
    val scriptSpacePx: Float? = null,
    /** Plain-TeX `\delimiterfactor`; 901 means roughly 90.1% of the axis-symmetric span. */
    val delimiterFactor: Int = 901,
    /** Formula-scoped `\delimitershortfall` in pixels; null keeps the plain-TeX 5pt/10pt ratio. */
    val delimiterShortfallPx: Float? = null,
    /** BCP-47 locale forwarded only to host-owned upright text atoms. */
    val textLocale: String? = null,
    /** TeX `\arraycolsep` for each side of a matrix/array cell; null is 0.5em. */
    val arrayColumnSeparationPx: Float? = null,
    /** LaTeX `\fboxsep`; null is the standard 3pt converted to CSS pixels. */
    val fboxSeparationPx: Float? = null,
    /** LaTeX `\fboxrule`; null is the standard 0.4pt converted to CSS pixels. */
    val fboxRuleThicknessPx: Float? = null,
    /** Completed display-row width used to right-align explicit amsmath equation tags. */
    val displayWidthPx: Float? = null,
) {
    init {
        require(fontSizePx > 0f) { "math font size must be positive" }
        require(nullDelimiterSpacePx == null || nullDelimiterSpacePx >= 0f) {
            "null delimiter space must not be negative"
        }
        require(scriptSpacePx == null || scriptSpacePx >= 0f) {
            "script space must not be negative"
        }
        require(delimiterFactor > 0) { "delimiter factor must be positive" }
        require(delimiterShortfallPx == null || delimiterShortfallPx >= 0f) {
            "delimiter shortfall must not be negative"
        }
        require(arrayColumnSeparationPx == null || arrayColumnSeparationPx >= 0f) {
            "array column separation must not be negative"
        }
        require(fboxSeparationPx == null || fboxSeparationPx >= 0f) {
            "fbox separation must not be negative"
        }
        require(fboxRuleThicknessPx == null || fboxRuleThicknessPx >= 0f) {
            "fbox rule thickness must not be negative"
        }
        require(displayWidthPx == null || displayWidthPx > 0f) {
            "display width must be positive"
        }
    }
}

data class MathPreparedFormula(
    val parseResult: MathParseResult,
) {
    val source: String get() = parseResult.source
    val diagnostics: List<MathDiagnostic> get() = parseResult.diagnostics
}

/** Parse-once production pipeline; analysis callers may still use [MathLayoutEngine.layout]. */
interface MathFormulaProductionPipeline {
    fun prepare(source: String): MathPreparedFormula

    fun layout(
        prepared: MathPreparedFormula,
        options: MathLayoutOptions = MathLayoutOptions(),
    ): MathLayoutResult
}

class MathLayoutEngine(
    private val glyphSource: MathFontFace,
    private val parser: MathFormulaParser,
    private val textRunProvider: MathTextRunProvider? = null,
) : MathFormulaProductionPipeline {
    constructor(
        glyphSource: MathFontFace,
        macros: List<MathMacroDefinition> = emptyList(),
        expansionLimits: MacroExpansionLimits = MacroExpansionLimits(),
        textRunProvider: MathTextRunProvider? = null,
    ) : this(glyphSource, MathParser(macros, expansionLimits), textRunProvider)

    override fun prepare(source: String): MathPreparedFormula =
        MathPreparedFormula(parser.parse(source))

    fun layout(source: String, options: MathLayoutOptions = MathLayoutOptions()): MathLayoutResult =
        layout(prepare(source), options)

    override fun layout(
        prepared: MathPreparedFormula,
        options: MathLayoutOptions,
    ): MathLayoutResult = MathLayoutPass(glyphSource, textRunProvider).layout(prepared.parseResult, options)
}

/** Per-call mutable state; a public engine can safely serve concurrent layout requests. */
private class MathLayoutPass(
    private val glyphSource: MathFontFace,
    private val textRunProvider: MathTextRunProvider?,
) {
    private val diagnostics = mutableListOf<MathDiagnostic>()
    private val decisions = mutableListOf<MathLayoutDecision>()
    private var baseFontSizePx: Float = 24f
    private var nullDelimiterSpacePx: Float = 2.88f
    private var scriptSpacePx: Float = DEFAULT_SCRIPT_SPACE_PT * TEX_POINT_TO_PX
    private var scriptSpacePolicy: String = "PlainTeXXeTeXScriptSpace"
    private var delimiterFactor: Int = 901
    private var delimiterShortfallPx: Float = 12f
    private var textLocale: String? = null
    private var explicitArrayColumnSeparationPx: Float? = null
    private var fboxSeparationPx: Float = DEFAULT_FBOX_SEPARATION_PT * TEX_POINT_TO_PX
    private var fboxRuleThicknessPx: Float = DEFAULT_FBOX_RULE_THICKNESS_PT * TEX_POINT_TO_PX
    private var formulaMode: MathMode = MathMode.Inline
    private var displayWidthPx: Float? = null
    private var nextConstructionPaintGroupId: Int = 1

    /**
     * Keep legacy/decorated single-face adapters virtual while allowing a real family to resolve
     * a non-primary face. Kotlin interface delegation otherwise forwards the new family methods
     * past test/host wrappers that intentionally override the primary face's evidence.
     */
    private fun mathFontForFace(faceId: MathFaceId): OpenTypeMathFont =
        if (faceId == glyphSource.faceId) glyphSource.mathFont else glyphSource.mathFontFor(faceId)

    private fun mathFontForFaceOrNull(faceId: MathFaceId): OpenTypeMathFont? =
        if (faceId == glyphSource.faceId) glyphSource.mathFont else glyphSource.mathFontForOrNull(faceId)

    private fun measureGlyphForFace(
        faceId: MathFaceId,
        glyphId: UShort,
        size: Float,
        style: MathStyle,
        range: SourceRange,
    ): MeasuredMathRun = if (faceId == glyphSource.faceId) {
        glyphSource.measureGlyph(glyphId, size, style, range)
    } else {
        glyphSource.measureGlyphForFace(faceId, glyphId, size, style, range)
    }

    private fun measureGlyphOutlineForFace(
        faceId: MathFaceId,
        glyphId: UShort,
        size: Float,
        style: MathStyle,
        range: SourceRange,
    ): MeasuredMathRun = if (faceId == glyphSource.faceId) {
        glyphSource.measureGlyphOutlineBounds(glyphId, size, style, range)
    } else {
        glyphSource.measureGlyphOutlineBoundsForFace(faceId, glyphId, size, style, range)
    }

    private fun measureConstructionGlyphForFace(
        faceId: MathFaceId,
        glyphId: UShort,
        size: Float,
        style: MathStyle,
        range: SourceRange,
    ): MeasuredOutlineConstructionRun = if (faceId == glyphSource.faceId) {
        glyphSource.measureOutlineConstructionGlyph(glyphId, size, style, range)
    } else {
        glyphSource.measureOutlineConstructionGlyphForFace(faceId, glyphId, size, style, range)
    }

    private fun constructionBaseCandidates(
        text: String,
        size: Float,
        range: SourceRange,
    ): List<MeasuredOutlineConstructionRun> {
        val candidates = glyphSource.shapeOutlineConstructionBaseCandidates(text, size, range)
        return if (candidates.size <= 1) {
            listOf(glyphSource.shapeOutlineConstructionBase(text, size, range))
        } else {
            candidates
        }
    }

    fun layout(parsed: MathParseResult, options: MathLayoutOptions): MathLayoutResult {
        val source = parsed.source
        baseFontSizePx = options.fontSizePx
        formulaMode = options.mode
        displayWidthPx = options.displayWidthPx
        nextConstructionPaintGroupId = 1
        nullDelimiterSpacePx = options.nullDelimiterSpacePx
            ?: options.fontSizePx * DEFAULT_NULL_DELIMITER_SPACE_EM
        scriptSpacePx = options.scriptSpacePx ?: DEFAULT_SCRIPT_SPACE_PT * TEX_POINT_TO_PX
        scriptSpacePolicy = if (options.scriptSpacePx == null) {
            "PlainTeXXeTeXScriptSpace"
        } else {
            "ExplicitTeXScriptSpace"
        }
        delimiterFactor = options.delimiterFactor
        delimiterShortfallPx = options.delimiterShortfallPx
            ?: options.fontSizePx * DEFAULT_DELIMITER_SHORTFALL_EM
        textLocale = options.textLocale
        explicitArrayColumnSeparationPx = options.arrayColumnSeparationPx
        fboxSeparationPx = options.fboxSeparationPx ?: DEFAULT_FBOX_SEPARATION_PT * TEX_POINT_TO_PX
        fboxRuleThicknessPx = options.fboxRuleThicknessPx ?: DEFAULT_FBOX_RULE_THICKNESS_PT * TEX_POINT_TO_PX
        diagnostics += parsed.diagnostics
        val initialStyle = options.initialStyle ?: MathStyle.initial(options.mode)
        val horizontal = layoutList(parsed.root, initialStyle)
        val fragments = horizontal.items.mapIndexed { itemIndex, item ->
            val trailingGlue = horizontal.items.getOrNull(itemIndex + 1)?.glueBefore ?: MathGlueAdjustment.Zero
            val breakKind = when (item.atomClass) {
                MathAtomClass.Punctuation -> MathBreakKind.PunctuationTrailing
                MathAtomClass.Binary -> MathBreakKind.BinaryOperatorTrailing
                MathAtomClass.Relation -> MathBreakKind.RelationTrailing
                else -> null
            }
            val opportunity = breakKind?.let {
                MathBreakOpportunity(
                    afterFragmentIndex = itemIndex,
                    sourceOffset = item.node.range.endExclusive,
                    kind = it,
                    discardedTrailingGlue = trailingGlue,
                    priority = adjustmentPriority(item.atomClass, null),
                )
            }
            MathInlineFragment(
                index = itemIndex,
                sourceRange = item.node.range,
                atomClass = item.atomClass,
                box = item.laid.box,
                leadingKernPx = item.leadingKernPx,
                trailingItalicCorrectionPx = item.trailingItalicCorrectionPx,
                trailingGlue = trailingGlue,
                breakAfter = opportunity,
            )
        }
        val breaks = fragments.mapNotNull { it.breakAfter }
        val lineMetrics = formulaLineMetrics(horizontal.laid.box, initialStyle)
        decision(
            "Os2TypographicMathLineExtents",
            parsed.root.range,
            "fontAscentPx" to lineMetrics.fontAscentPx,
            "fontDescentPx" to lineMetrics.fontDescentPx,
            "fontLineGapPx" to lineMetrics.fontLineGapPx,
            "mathLeadingPx" to lineMetrics.mathLeadingPx,
            "inkAscentPx" to lineMetrics.inkAscentPx,
            "inkDescentPx" to lineMetrics.inkDescentPx,
            "texBoxAscentPx" to horizontal.laid.box.ascent,
            "texBoxDescentPx" to horizontal.laid.box.descent,
            "hostReservePolicy" to "MaxOfTypographicLineTeXBoxAndPaintInk",
            "logicalAscentPx" to lineMetrics.logicalAscentPx,
            "logicalDescentPx" to lineMetrics.logicalDescentPx,
        )
        val resultDiagnostics = diagnostics.toList()
        val resultDecisions = decisions.toList()
        val dump = buildDump(
            source,
            options.mode,
            initialStyle,
            horizontal.laid.box,
            fragments,
            breaks,
            lineMetrics,
            resultDiagnostics,
            resultDecisions,
        )
        return MathLayoutResult(
            source = source,
            mode = options.mode,
            initialStyle = initialStyle,
            box = horizontal.laid.box,
            fragments = fragments,
            breakOpportunities = breaks,
            diagnostics = resultDiagnostics,
            lineMetrics = lineMetrics,
            decisions = resultDecisions,
            debugDump = dump,
        )
    }

    private val constants: OpenTypeMathConstants get() = glyphSource.mathFont.constants

    private fun layoutNode(
        node: MathNode,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride? = null,
    ): LaidNode = when (node) {
        is MathList -> layoutList(node, style, alphabetOverride).laid
        is MathGroup -> layoutGroup(node, style, alphabetOverride)
        is MathBoxed -> layoutBoxed(node, style, alphabetOverride)
        is MathBbox -> layoutBbox(node, style, alphabetOverride)
        is MathSymbol -> layoutSymbol(node, style, alphabetOverride)
        is MathOperator -> layoutOperator(node, style, alphabetOverride)
        is MathOperatorName -> layoutOperatorName(node, style, alphabetOverride)
        is MathOperatorNoad -> layoutOperatorNoad(node, style, alphabetOverride)
        is MathText -> layoutText(node, style)
        is MathAccent -> layoutAccent(node, style, alphabetOverride)
        is MathBraceNoad -> layoutBraceNoad(node, style, alphabetOverride)
        is MathRuleDecoration -> layoutRuleDecoration(node, style, alphabetOverride)
        is MathOverUnder -> layoutOverUnder(node, style, alphabetOverride)
        is MathExtensibleArrow -> layoutExtensibleArrow(node, style, alphabetOverride)
        is MathExplicitSpace -> layoutExplicitSpace(node, style)
        is MathTable -> layoutTable(node, style, alphabetOverride)
        is MathDisplayEnvironment -> layoutDisplayEnvironment(node, alphabetOverride)
        is MathDisplayRows -> layoutDisplayRows(node, alphabetOverride)
        is MathTaggedEquation -> layoutTaggedEquation(node, alphabetOverride)
        is MathEquationTag -> layoutMisplacedEquationTag(node, style)
        is MathExplicitRowBreak -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
        is MathScripts -> when (val base = node.base) {
            is MathOperator -> layoutOperatorScripts(node, base, style, alphabetOverride)
            is MathOperatorName -> layoutOperatorNameScripts(node, base, style, alphabetOverride)
            is MathOperatorNoad -> layoutOperatorNoadScripts(node, base, style, alphabetOverride)
            is MathBraceNoad -> layoutBraceNoadScripts(node, base, style, alphabetOverride)
            else -> layoutScripts(node, style, alphabetOverride)
        }
        is MathFraction -> layoutFraction(node, style, alphabetOverride)
        is MathRadical -> layoutRadical(node, style, alphabetOverride)
        is MathFixedDelimiter -> layoutFixedDelimiter(node, style)
        is MathDelimited -> layoutDelimited(node, style, alphabetOverride)
        is MathMiddleDelimiter -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Inner,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
        is MathStyleDeclaration -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
        is MathColorDeclaration -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
        is MathAlphabetDeclaration -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
        is MathAlphabetScope -> layoutAlphabetScopeNode(node, style)
        is MathVersionScope -> layoutMathVersionScopeNode(node, style)
        is MathErrorNode -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutExplicitSpace(node: MathExplicitSpace, style: MathStyle): LaidNode {
        val advance = node.mu * fontSize(style) / 18f
        val width = advance.coerceAtLeast(0f)
        decision(
            "TeXExplicitMathSpace",
            node.range,
            "command" to node.command,
            "mu" to node.mu,
            "style" to style,
            "fontSizePx" to fontSize(style),
            "advancePx" to advance,
            "boxWidthPx" to width,
            "policy" to if (advance < 0f) "TeXFixedSignedMuKern" else "NamedTeXMuSkip",
        )
        return LaidNode(
            node = node,
            box = MathBox(
                width = width,
                ascent = 0f,
                descent = 0f,
                inkBounds = MathRect(0f, 0f, 0f, 0f),
                glyphs = emptyList(),
                rules = emptyList(),
                range = node.range,
                texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                    ascent = 0f,
                    descent = 0f,
                    policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                    evidence = setOf(MathTeXCleanBoxEvidence.Empty),
                ),
            ),
            atomClass = MathAtomClass.Ordinary,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
            horizontalKernPx = advance.coerceAtMost(0f),
        )
    }

    private fun layoutTable(
        node: MathTable,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val preservesEntryStyle = node.environment in setOf(
            MathTableEnvironment.Aligned,
            MathTableEnvironment.Split,
        )
        val cellStyle = if (preservesEntryStyle) {
            style
        } else {
            when (style.level) {
                MathStyleLevel.Display, MathStyleLevel.Text -> MathStyle.Text
                MathStyleLevel.Script -> MathStyle.Script
                MathStyleLevel.ScriptScript -> MathStyle.ScriptScript
            }
        }
        val rowLayouts = node.rows.map { row ->
            row.cells.mapIndexed { column, cell ->
                val horizontal = layoutList(cell.body, cellStyle, alphabetOverride)
                val needsAlignedRelationAnchor = preservesEntryStyle && column % 2 == 1
                val preambleGlue = if (needsAlignedRelationAnchor) {
                    horizontal.items.firstOrNull()?.let { first ->
                        atomGlue(MathAtomClass.Ordinary, first.atomClass, first.laid.style, cell.range).naturalPx
                    } ?: 0f
                } else {
                    0f
                }
                if (preambleGlue > 0f) {
                    decision(
                        "TeXAlignedRightColumnPreamble",
                        cell.range,
                        "column" to column,
                        "firstAtomClass" to horizontal.items.first().atomClass,
                        "leadingGluePx" to preambleGlue,
                        "policy" to "AmsmathAlignedEmptyOrdBeforeRightColumn",
                    )
                    horizontal.laid.box.translated(preambleGlue, 0f).copy(
                        width = horizontal.laid.box.width + preambleGlue,
                        range = cell.range,
                    )
                } else {
                    horizontal.laid.box
                }
            }
        }
        val rowTagLayouts = node.rows.map { row -> row.tag?.let { layoutEquationTagBox(it, style) } }
        val columnCount = maxOf(
            node.columnAlignments.size,
            rowLayouts.maxOfOrNull { it.size } ?: 0,
        )
        val alignments = List(columnCount) { column ->
            node.columnAlignments.getOrNull(column) ?: when (node.environment) {
                MathTableEnvironment.Aligned,
                MathTableEnvironment.Split,
                -> if (column % 2 == 0) MathTableColumnAlignment.Right else MathTableColumnAlignment.Left
                MathTableEnvironment.Cases -> MathTableColumnAlignment.Left
                else -> MathTableColumnAlignment.Center
            }
        }
        val columnWidths = List(columnCount) { column ->
            rowLayouts.maxOfOrNull { row -> row.getOrNull(column)?.width ?: 0f } ?: 0f
        }
        val size = fontSize(cellStyle)
        val cases = node.environment == MathTableEnvironment.Cases
        val strutAscentEm = if (cases) TEX_CASES_STRUT_ASCENT_EM else TEX_ARRAY_STRUT_ASCENT_EM
        val strutDescentEm = if (cases) TEX_CASES_STRUT_DESCENT_EM else TEX_ARRAY_STRUT_DESCENT_EM
        val minimumRowAscent = strutAscentEm * size
        val minimumRowDescent = strutDescentEm * size
        val rowAdditionalSpacingPx = node.rows.map { row ->
            row.additionalSpacing?.let { resolveTeXDimension(it, size) } ?: 0f
        }
        val rowAscents = rowLayouts.mapIndexed { rowIndex, row ->
            maxOf(
                minimumRowAscent,
                row.maxOfOrNull { it.texCleanBoxMetrics.ascent } ?: 0f,
                rowTagLayouts[rowIndex]?.texCleanBoxMetrics?.ascent ?: 0f,
            )
        }
        val rowDescents = rowLayouts.mapIndexed { rowIndex, row ->
            val optionalStrutExtension = if (preservesEntryStyle) 0f else rowAdditionalSpacingPx[rowIndex]
            maxOf(
                minimumRowDescent + optionalStrutExtension,
                row.maxOfOrNull { it.texCleanBoxMetrics.descent } ?: 0f,
                rowTagLayouts[rowIndex]?.texCleanBoxMetrics?.descent ?: 0f,
            )
        }
        val arrayColumnSeparation = explicitArrayColumnSeparationPx ?: TEX_ARRAY_COLUMN_SEPARATION_EM * size
        val columnGaps = List((columnCount - 1).coerceAtLeast(0)) { boundary ->
            when (node.environment) {
                MathTableEnvironment.Matrix,
                MathTableEnvironment.ParenthesizedMatrix,
                MathTableEnvironment.BracketedMatrix,
                MathTableEnvironment.Determinant,
                MathTableEnvironment.Array,
                -> arrayColumnSeparation * 2f

                MathTableEnvironment.Aligned,
                MathTableEnvironment.Split,
                -> if (boundary % 2 == 1) TEX_ALIGNED_PAIR_GAP_EM * size else 0f

                else -> TEX_ARRAY_INTERCOLUMN_EM * size
            }
        }
        val rowGapEm = if (preservesEntryStyle && node.rows.size > 1) TEX_ALIGNED_ROW_GAP_EM else 0f
        val baseRowGap = rowGapEm * size
        val rowGaps = List((node.rows.size - 1).coerceAtLeast(0)) { rowIndex ->
            baseRowGap + if (preservesEntryStyle) rowAdditionalSpacingPx[rowIndex] else 0f
        }
        val trailingExplicitRowSpacing = if (preservesEntryStyle) {
            rowAdditionalSpacingPx.lastOrNull() ?: 0f
        } else {
            0f
        }
        val outerPadding = if (node.environment == MathTableEnvironment.Array) {
            arrayColumnSeparation
        } else {
            0f
        }
        val bodyWidth = outerPadding * 2f + columnWidths.sum() + columnGaps.sum()
        val bodyHeight = rowAscents.zip(rowDescents).sumOf { (ascent, descent) ->
            (ascent + descent).toDouble()
        }.toFloat() + rowGaps.sum() + trailingExplicitRowSpacing
        val axisHeight = scale(constants.axisHeight, style)
        val bodyTop = -axisHeight - bodyHeight / 2f
        var rowTop = bodyTop
        val glyphs = mutableListOf<MathGlyphPlacement>()
        val rules = mutableListOf<MathRulePlacement>()
        val paintGroups = mutableListOf<MathConstructionPaintGroup>()
        val positionedChildren = mutableListOf<Pair<MathBox, Float>>()
        val rowBaselines = mutableListOf<Float>()
        rowLayouts.forEachIndexed { rowIndex, row ->
            val baselineY = rowTop + rowAscents[rowIndex]
            rowBaselines += baselineY
            var columnLeft = outerPadding
            row.forEachIndexed { column, cell ->
                val offset = when (alignments[column]) {
                    MathTableColumnAlignment.Left -> 0f
                    MathTableColumnAlignment.Center -> (columnWidths[column] - cell.width) / 2f
                    MathTableColumnAlignment.Right -> columnWidths[column] - cell.width
                }
                val shifted = cell.translated(columnLeft + offset, baselineY)
                glyphs += shifted.glyphs
                rules += shifted.rules
                paintGroups += shifted.constructionPaintGroups
                positionedChildren += cell to baselineY
                columnLeft += columnWidths[column] + columnGaps.getOrElse(column) { 0f }
            }
            rowTop += rowAscents[rowIndex] + rowDescents[rowIndex] +
                rowGaps.getOrElse(rowIndex) { 0f }
        }
        val bodyBottom = bodyTop + bodyHeight
        val paintedBody = geometryExtents(
            bodyWidth,
            glyphs,
            rules,
            node.range,
            paintGroups,
        )
        val bodyBox = paintedBody.copy(
            ascent = (-bodyTop).coerceAtLeast(0f),
            descent = bodyBottom.coerceAtLeast(0f),
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = (-bodyTop).coerceAtLeast(0f),
                descent = bodyBottom.coerceAtLeast(0f),
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = positionedChildren.flatMap { it.first.texCleanBoxMetrics.evidence }.toSet() +
                    MathTeXCleanBoxEvidence.CompletedChildBox,
            ),
        )
        val fenced = wrapTableDelimiters(node, bodyBox, style)
        val completed = if (node.rows.any { it.tag != null }) {
            completeTaggedRows(
                body = fenced,
                rows = node.rows,
                tagBoxes = rowTagLayouts,
                rowBaselines = rowBaselines,
                range = node.range,
                layoutRole = "AlignmentRows",
            )
        } else {
            fenced
        }
        decision(
            "TeXMathTable",
            node.range,
            "environmentName" to node.environmentName,
            "environment" to node.environment,
            "rowCount" to node.rows.size,
            "columnCount" to columnCount,
            "columnAlignments" to alignments.joinToString(","),
            "columnWidthsPx" to columnWidths.joinToString(","),
            "rowAscentsPx" to rowAscents.joinToString(","),
            "rowDescentsPx" to rowDescents.joinToString(","),
            "rowMetricPolicy" to "MaxOfTeXCleanCellBoxAndEnvironmentStrut",
            "cellStyle" to cellStyle,
            "axisHeightPx" to axisHeight,
            "arrayStrutAscentEm" to strutAscentEm,
            "arrayStrutDescentEm" to strutDescentEm,
            "interColumnPolicy" to if (preservesEntryStyle) {
                "ZeroWithinPairAndTwoEmBetweenEquationPairs"
            } else {
                "ArrayColumnSeparationOrEnvironmentGap"
            },
            "arrayColumnSeparationPx" to arrayColumnSeparation,
            "columnGapsPx" to columnGaps.joinToString(","),
            "rowGapEm" to rowGapEm,
            "baseRowGapPx" to baseRowGap,
            "rowAdditionalSpacingPx" to rowAdditionalSpacingPx.joinToString(","),
            "rowGapsPx" to rowGaps.joinToString(","),
            "trailingExplicitRowSpacingPx" to trailingExplicitRowSpacing,
            "rowSpacingPolicy" to if (preservesEntryStyle) {
                "AmsmathExtraInterRowGlue"
            } else {
                "LaTeXArrayPreviousRowStrutDepthExtension"
            },
            "bodyWidthPx" to bodyWidth,
            "bodyAscentPx" to bodyBox.ascent,
            "bodyDescentPx" to bodyBox.descent,
            "logicalAdvancePx" to completed.width,
            "groupBreakPolicy" to "UnbreakableTeXTableInnerNoad",
            "policy" to "LaTeXEnvironmentSpecificStyleArrayStrutAndAxisCenteredVcenter",
        )
        return LaidNode(
            node,
            completed,
            MathAtomClass.Inner,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutDisplayEnvironment(
        node: MathDisplayEnvironment,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val displayStyle = MathStyle.Display
        val body = layoutNode(node.body, displayStyle, alphabetOverride)
        val explicitTag = node.tag
        val box = if (explicitTag == null) {
            body.box.copy(range = node.range)
        } else {
            completeTaggedEquationBox(
                body = body.box,
                tag = explicitTag,
                style = displayStyle,
                range = node.range,
                layoutRole = "SingleDisplayEnvironment",
            )
        }
        decision(
            "MarkdownMathDisplayEnvironment",
            node.range,
            "environment" to node.kind.sourceName,
            "layoutRole" to if (node.kind.alignment) "DisplayAlignment" else "SingleDisplayEquation",
            "entryStyle" to displayStyle,
            "sourceRequestsNumbering" to node.kind.sourceRequestsNumbering,
            "numberingPolicy" to "SuppressedByMarkdownFormulaHost",
            "explicitTag" to (node.tag != null),
            "atomClass" to "NoneAtDocumentLevel",
            "groupBreakPolicy" to "ExplicitRowsOnly",
            "policy" to "AmsmathDisplayWrapperWithIntrinsicFormulaBox",
        )
        return LaidNode(
            node = node,
            box = box,
            atomClass = MathAtomClass.Ordinary,
            italicCorrectionPx = 0f,
            style = displayStyle,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutDisplayRows(
        node: MathDisplayRows,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        if (formulaMode != MathMode.Display) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.ExplicitMultilineRequiresDisplay,
                "Top-level \\\\ rows require display math mode",
                node.range,
            )
        }
        var carriedStyle: MathStyleDeclaration? = null
        var carriedAlphabet: MathAlphabetDeclaration? = null
        val tableRows = node.rows.map { row ->
            val inherited = listOfNotNull(carriedStyle, carriedAlphabet)
            val syntheticBody = MathList(
                children = inherited + row.body.children,
                range = row.body.range,
            )
            row.body.children.forEach { child ->
                when (child) {
                    is MathStyleDeclaration -> carriedStyle = child
                    is MathAlphabetDeclaration -> carriedAlphabet = child
                    else -> Unit
                }
            }
            MathTableRow(
                cells = listOf(MathTableCell(syntheticBody, range = row.body.range)),
                rowSeparatorRange = row.rowSeparatorRange,
                additionalSpacing = row.additionalSpacing,
                range = row.range,
                tag = row.tag,
            )
        }
        val syntheticTable = MathTable(
            environmentName = "markdown-display-rows",
            environment = MathTableEnvironment.Aligned,
            rows = tableRows,
            columnAlignments = listOf(MathTableColumnAlignment.Center),
            beginCommandRange = SourceRange(node.range.start, node.range.start),
            beginNameRange = SourceRange(node.range.start, node.range.start),
            endCommandRange = null,
            endNameRange = null,
            range = node.range,
        )
        val table = layoutTable(syntheticTable, MathStyle.Display, alphabetOverride)
        decision(
            "MarkdownExplicitDisplayRows",
            node.range,
            "rowCount" to node.rows.size,
            "entryMode" to formulaMode,
            "entryStyle" to MathStyle.Display,
            "rowAlignment" to "CenteredIndependentlyAtMaximumAdvance",
            "rowKernel" to "AmsmathAlignedStrutAndBaselineGap",
            "styleDeclarationPolicy" to "ContainingListDeclarationsCarryAcrossRows",
            "trailingSeparatorCreatesVisibleRow" to false,
            "groupBreakPolicy" to "ExplicitRowsOnlyNoAutomaticInternalBreak",
            "dialect" to "MarkdownDisplayKaTeXCompatibilityExtension",
        )
        return table.copy(
            node = node,
            box = table.box.copy(range = node.range),
            atomClass = MathAtomClass.Ordinary,
            style = MathStyle.Display,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutTaggedEquation(
        node: MathTaggedEquation,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val style = MathStyle.Display
        val body = layoutList(node.body, style, alphabetOverride).laid.box
        val box = completeTaggedEquationBox(
            body = body,
            tag = node.tag,
            style = style,
            range = node.range,
            layoutRole = "TopLevelMarkdownDisplay",
        )
        return LaidNode(
            node = node,
            box = box,
            atomClass = MathAtomClass.Ordinary,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutMisplacedEquationTag(node: MathEquationTag, style: MathStyle): LaidNode {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MisplacedEquationTag,
            "Equation tag is only valid at the top level of a display row",
            node.range,
        )
        return LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
    }

    private fun completeTaggedRows(
        body: MathBox,
        rows: List<MathTableRow>,
        tagBoxes: List<MathBox?>,
        rowBaselines: List<Float>,
        range: SourceRange,
        layoutRole: String,
    ): MathBox {
        val width = resolvedEquationTagDisplayWidth(range) ?: body.width
        val bodyX = (width - body.width) / 2f
        val shiftedBody = body.translated(bodyX, 0f)
        val glyphs = shiftedBody.glyphs.toMutableList()
        val rules = shiftedBody.rules.toMutableList()
        val groups = shiftedBody.constructionPaintGroups.toMutableList()
        val children = mutableListOf(body to 0f)
        rows.forEachIndexed { index, row ->
            val tag = row.tag ?: return@forEachIndexed
            val tagBox = checkNotNull(tagBoxes[index])
            val tagX = width - tagBox.width
            val baselineY = rowBaselines.getOrElse(index) { 0f }
            checkEquationTagFit(bodyX, body.width, tagX, tag, width, layoutRole)
            val shifted = tagBox.translated(tagX, baselineY)
            glyphs += shifted.glyphs
            rules += shifted.rules
            groups += shifted.constructionPaintGroups
            children += tagBox to baselineY
            equationTagDecision(tag, tagBox, body.width, width, bodyX, tagX, baselineY, layoutRole)
        }
        return geometryExtentsPreservingLogicalChildren(
            width.coerceAtLeast(0f),
            glyphs,
            rules,
            range,
            children,
            groups,
        )
    }

    private fun completeTaggedEquationBox(
        body: MathBox,
        tag: MathEquationTag,
        style: MathStyle,
        range: SourceRange,
        layoutRole: String,
    ): MathBox {
        if (formulaMode != MathMode.Display) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MisplacedEquationTag,
                "Equation tag requires display math mode",
                tag.range,
            )
        }
        val tagBox = layoutEquationTagBox(tag, style)
        val width = resolvedEquationTagDisplayWidth(tag.range)
            ?: (body.width + tagBox.width + baseFontSizePx / 2f)
        val bodyX = (width - body.width) / 2f
        val tagX = width - tagBox.width
        checkEquationTagFit(bodyX, body.width, tagX, tag, width, layoutRole)
        val shiftedBody = body.translated(bodyX, 0f)
        val shiftedTag = tagBox.translated(tagX, 0f)
        equationTagDecision(tag, tagBox, body.width, width, bodyX, tagX, 0f, layoutRole)
        return geometryExtentsPreservingLogicalChildren(
            width,
            shiftedBody.glyphs + shiftedTag.glyphs,
            shiftedBody.rules + shiftedTag.rules,
            range,
            listOf(body to 0f, tagBox to 0f),
            shiftedBody.constructionPaintGroups + shiftedTag.constructionPaintGroups,
        )
    }

    private fun resolvedEquationTagDisplayWidth(range: SourceRange): Float? {
        val width = displayWidthPx
        if (width == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingEquationTagDisplayWidth,
                "Equation tag layout requires an explicit completed display width",
                range,
            )
        }
        return width
    }

    private fun checkEquationTagFit(
        bodyX: Float,
        bodyWidth: Float,
        tagX: Float,
        tag: MathEquationTag,
        width: Float,
        layoutRole: String,
    ) {
        val minimumSeparation = baseFontSizePx / 2f
        if (bodyX < 0f || bodyX + bodyWidth + minimumSeparation > tagX) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.EquationTagDoesNotFit,
                "Equation body and tag do not fit the supplied display width",
                tag.range,
            )
        }
        decision(
            "AmsmathEquationTagFit",
            tag.range,
            "displayWidthPx" to width,
            "bodyLeftPx" to bodyX,
            "bodyRightPx" to (bodyX + bodyWidth),
            "tagLeftPx" to tagX,
            "minimumSeparationPx" to minimumSeparation,
            "fits" to (bodyX >= 0f && bodyX + bodyWidth + minimumSeparation <= tagX),
            "layoutRole" to layoutRole,
            "policy" to "AmsmathMinTagSeparationHalfEmOrFormulaFallback",
        )
    }

    private fun layoutEquationTagBox(tag: MathEquationTag, style: MathStyle): MathBox {
        val textStyle = styleForLevel(MathStyleLevel.Text)
        val wrapperLeftRange = SourceRange(tag.commandRange.start, tag.commandRange.start + 1)
        val wrapperRightRange = SourceRange(tag.commandRange.endExclusive - 1, tag.commandRange.endExclusive)
        val segments = if (tag.starred) {
            tag.segments
        } else {
            listOf(MathTextSegment("(", wrapperLeftRange)) + tag.segments +
                MathTextSegment(")", wrapperRightRange)
        }
        return layoutTextSegments(
            segments = segments,
            style = textStyle,
            range = tag.range,
            origin = MathTextOrigin.EquationTag,
        )
    }

    private fun equationTagDecision(
        tag: MathEquationTag,
        tagBox: MathBox,
        bodyWidth: Float,
        width: Float,
        bodyX: Float,
        tagX: Float,
        tagBaselineY: Float,
        layoutRole: String,
    ) = decision(
        "AmsmathEquationTag",
        tag.range,
        "text" to tag.text,
        "starred" to tag.starred,
        "commandRange" to tag.commandRange,
        "contentRange" to tag.contentRange,
        "argumentRange" to tag.argumentRange,
        "displayWidthPx" to width,
        "bodyWidthPx" to bodyWidth,
        "bodyX" to bodyX,
        "tagWidthPx" to tagBox.width,
        "tagAscentPx" to tagBox.ascent,
        "tagDescentPx" to tagBox.descent,
        "tagInkTopPx" to tagBox.inkBounds.top,
        "tagInkBottomPx" to tagBox.inkBounds.bottom,
        "tagFaceIds" to tagBox.glyphs.map { it.faceId }.distinct().joinToString(","),
        "tagX" to tagX,
        "tagBaselineY" to tagBaselineY,
        "tagTextStyle" to MathStyle.Text,
        "wrapperPolicy" to if (tag.starred) "TagStarUnwrapped" else "TagParenthesesFromOperatorsFamily",
        "layoutRole" to layoutRole,
        "policy" to "AmsmathDisplayBodyCenteredTagRightAlignedAtHostDisplayWidth",
    )

    private fun resolveTeXDimension(dimension: MathTeXDimension, emSizePx: Float): Float {
        val pixels = when (dimension.unit) {
            MathTeXDimensionUnit.Point -> dimension.value * TEX_POINT_TO_PX
            MathTeXDimensionUnit.BigPoint -> dimension.value * BIG_POINT_TO_PX
            MathTeXDimensionUnit.Em -> dimension.value * emSizePx
            MathTeXDimensionUnit.Centimeter -> dimension.value * CSS_PIXELS_PER_INCH / CENTIMETERS_PER_INCH
            MathTeXDimensionUnit.Millimeter -> dimension.value * CSS_PIXELS_PER_INCH / MILLIMETERS_PER_INCH
            MathTeXDimensionUnit.Inch -> dimension.value * CSS_PIXELS_PER_INCH
        }
        decision(
            "TeXExplicitRowSpacing",
            dimension.range,
            "sourceText" to dimension.sourceText,
            "value" to dimension.value,
            "unit" to dimension.unit.sourceName,
            "emSizePx" to emSizePx,
            "resolvedPx" to pixels,
            "policy" to "TeXDimensionAt96CssPixelsPerInch",
        )
        return pixels
    }

    private fun wrapTableDelimiters(
        node: MathTable,
        body: MathBox,
        style: MathStyle,
    ): MathBox {
        val environment = node.environment ?: return body
        val leftIdentity = environment.leftDelimiter
        val rightIdentity = environment.rightDelimiter
        if (leftIdentity == null && rightIdentity == null) return body
        val size = fontSize(style)
        val axisHeight = glyphSource.mathFont.scaleDesignUnits(constants.axisHeight, size)
        val maxAxisDistance = maxOf(body.descent + axisHeight, body.ascent - axisHeight).coerceAtLeast(0f)
        val factorTarget = maxAxisDistance * delimiterFactor / 500f
        val shortfallTarget = 2f * maxAxisDistance - delimiterShortfallPx
        val target = DelimiterTargetEvidence(
            innerCleanAscentPx = body.texCleanBoxMetrics.ascent,
            innerCleanDescentPx = body.texCleanBoxMetrics.descent,
            axisHeightPx = axisHeight,
            maxAxisDistancePx = maxAxisDistance,
            factor = delimiterFactor,
            shortfallPx = delimiterShortfallPx,
            factorTargetPx = factorTarget,
            shortfallTargetPx = shortfallTarget,
            targetPx = maxOf(factorTarget, shortfallTarget).coerceAtLeast(0f),
        )
        fun spec(identity: MathDelimiterIdentity, side: MathDelimiterSide): MathDelimiterSpec {
            val range = if (side == MathDelimiterSide.Left) {
                node.beginCommandRange.cover(node.beginNameRange)
            } else {
                node.endCommandRange?.let { command ->
                    node.endNameRange?.let(command::cover) ?: command
                } ?: SourceRange(node.range.endExclusive, node.range.endExclusive)
            }
            return MathDelimiterSpec(
                sourceText = node.environmentName,
                identity = identity,
                side = side,
                commandRange = if (side == MathDelimiterSide.Left) node.beginCommandRange else node.endCommandRange ?: range,
                delimiterRange = if (side == MathDelimiterSide.Left) node.beginNameRange else node.endNameRange ?: range,
                range = range,
            )
        }
        val left = leftIdentity?.let { layoutDelimiter(spec(it, MathDelimiterSide.Left), style, target) }
        val right = rightIdentity?.let { layoutDelimiter(spec(it, MathDelimiterSide.Right), style, target) }
        var x = 0f
        val glyphs = mutableListOf<MathGlyphPlacement>()
        val rules = mutableListOf<MathRulePlacement>()
        val groups = mutableListOf<MathConstructionPaintGroup>()
        fun append(box: MathBox?) {
            if (box == null) return
            val shifted = box.translated(x, 0f)
            glyphs += shifted.glyphs
            rules += shifted.rules
            groups += shifted.constructionPaintGroups
            x += box.width
        }
        append(left)
        append(body)
        append(right)
        val painted = geometryExtents(x, glyphs, rules, node.range, groups)
        val children = listOfNotNull(left, body, right)
        return painted.copy(
            ascent = children.maxOfOrNull { it.ascent } ?: 0f,
            descent = children.maxOfOrNull { it.descent } ?: 0f,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = children.maxOfOrNull { it.texCleanBoxMetrics.ascent } ?: 0f,
                descent = children.maxOfOrNull { it.texCleanBoxMetrics.descent } ?: 0f,
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = children.flatMap { it.texCleanBoxMetrics.evidence }.toSet() +
                    MathTeXCleanBoxEvidence.CompletedChildBox,
            ),
        )
    }

    private fun layoutGroup(
        node: MathGroup,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val horizontal = layoutList(node.body, style, alphabetOverride)
        decision(
            "TeXOrdSubMlist",
            node.range,
            "outerClass" to MathAtomClass.Ordinary,
            "innerClasses" to horizontal.items.joinToString(",") { it.atomClass.name },
            "innerStyles" to horizontal.items.joinToString(",") { it.laid.style.toString() },
            "innerBreaksExported" to false,
            "scriptBaseKind" to ScriptBaseKind.CompoundBox,
        )
        return horizontal.laid.copy(
            node = node,
            box = horizontal.laid.box.copy(range = node.range),
            atomClass = MathAtomClass.Ordinary,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutBoxed(
        node: MathBoxed,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val contentStyle = styleForLevel(MathStyleLevel.Display)
        val content = layoutNode(node.body, contentStyle, alphabetOverride).completedTeXMathField().box
        val inset = fboxSeparationPx + fboxRuleThicknessPx
        val width = content.width + 2f * inset
        val ascent = content.ascent + inset
        val descent = content.descent + inset
        val shiftedContent = content.translated(inset, 0f)
        val rules = listOf(
            MathRulePlacement(0f, -ascent, width, -ascent + fboxRuleThicknessPx, node.commandRange),
            MathRulePlacement(0f, descent - fboxRuleThicknessPx, width, descent, node.commandRange),
            MathRulePlacement(0f, -ascent, fboxRuleThicknessPx, descent, node.commandRange),
            MathRulePlacement(width - fboxRuleThicknessPx, -ascent, width, descent, node.commandRange),
        )
        val painted = geometryExtents(
            width = width,
            glyphs = shiftedContent.glyphs,
            rules = shiftedContent.rules + rules,
            range = node.range,
            constructionPaintGroups = shiftedContent.constructionPaintGroups,
        )
        val box = painted.copy(
            ascent = ascent,
            descent = descent,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = content.texCleanBoxMetrics.ascent + inset,
                descent = content.texCleanBoxMetrics.descent + inset,
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = content.texCleanBoxMetrics.evidence +
                    MathTeXCleanBoxEvidence.CompletedChildBox + MathTeXCleanBoxEvidence.RuleGeometry,
            ),
        )
        decision(
            "AmsmathBoxedNoad",
            node.range,
            "commandRange" to node.commandRange,
            "outerStyle" to style,
            "contentStyle" to contentStyle,
            "fboxSeparationPx" to fboxSeparationPx,
            "fboxRuleThicknessPx" to fboxRuleThicknessPx,
            "contentWidthPx" to content.width,
            "contentAscentPx" to content.ascent,
            "contentDescentPx" to content.descent,
            "logicalWidthPx" to width,
            "logicalAscentPx" to ascent,
            "logicalDescentPx" to descent,
            "atomClass" to MathAtomClass.Ordinary,
            "scriptBaseKind" to ScriptBaseKind.CompoundBox,
            "policy" to "AmsmathFboxDisplayStyleWithTeXFboxsepAndFboxrule",
        )
        return LaidNode(
            node = node,
            box = box,
            atomClass = MathAtomClass.Ordinary,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutBbox(
        node: MathBbox,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val content = layoutNode(node.body, style, alphabetOverride).completedTeXMathField().box
        val styleSizePx = fontSize(style)
        val paddingPx = node.options.padding?.let { resolveBboxDimension(it, styleSizePx) } ?: 0f
        val border = node.options.border
        val borderWidthPx = if (border?.style == MathBboxBorderStyle.Solid) {
            resolveBboxDimension(border.width, styleSizePx)
        } else {
            0f
        }
        val inset = paddingPx + borderWidthPx
        val width = content.width + 2f * inset
        val ascent = content.ascent + inset
        val descent = content.descent + inset
        val shiftedContent = content.translated(inset, 0f)
        val decorations = buildList {
            node.options.background?.let { background ->
                add(
                    MathRulePlacement(
                        left = 0f,
                        top = -ascent,
                        right = width,
                        bottom = descent,
                        sourceRange = background.range,
                        paintColor = background.color,
                        paintLayer = MathPaintLayer.Background,
                        paintRole = MathRulePaintRole.BackgroundFill,
                    ),
                )
            }
            if (borderWidthPx > 0f && border != null) {
                val color = border.color?.color
                add(
                    MathRulePlacement(
                        0f, -ascent, width, -ascent + borderWidthPx, border.range,
                        paintColor = color,
                        paintRole = MathRulePaintRole.Border,
                    ),
                )
                add(
                    MathRulePlacement(
                        0f, descent - borderWidthPx, width, descent, border.range,
                        paintColor = color,
                        paintRole = MathRulePaintRole.Border,
                    ),
                )
                add(
                    MathRulePlacement(
                        0f, -ascent, borderWidthPx, descent, border.range,
                        paintColor = color,
                        paintRole = MathRulePaintRole.Border,
                    ),
                )
                add(
                    MathRulePlacement(
                        width - borderWidthPx, -ascent, width, descent, border.range,
                        paintColor = color,
                        paintRole = MathRulePaintRole.Border,
                    ),
                )
            }
        }
        val painted = geometryExtents(
            width = width,
            glyphs = shiftedContent.glyphs,
            rules = decorations + shiftedContent.rules,
            range = node.range,
            constructionPaintGroups = shiftedContent.constructionPaintGroups,
        )
        val extraEvidence = if (decorations.isEmpty()) emptySet() else setOf(MathTeXCleanBoxEvidence.RuleGeometry)
        val box = painted.copy(
            ascent = ascent,
            descent = descent,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = content.texCleanBoxMetrics.ascent + inset,
                descent = content.texCleanBoxMetrics.descent + inset,
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = content.texCleanBoxMetrics.evidence +
                    MathTeXCleanBoxEvidence.CompletedChildBox + extraEvidence,
            ),
        )
        decision(
            "MathJaxBboxExtension",
            node.range,
            "commandRange" to node.commandRange,
            "optionsRange" to node.optionsRange,
            "outerStyle" to style,
            "styleFontSizePx" to styleSizePx,
            "paddingSource" to node.options.padding?.sourceText,
            "paddingPx" to paddingPx,
            "backgroundSource" to node.options.background?.sourceName,
            "backgroundArgb" to node.options.background?.color?.argb?.toUInt()?.toString(16),
            "borderWidthSource" to border?.width?.sourceText,
            "borderWidthPx" to borderWidthPx,
            "borderStyle" to border?.style,
            "borderColorSource" to border?.color?.sourceName,
            "contentWidthPx" to content.width,
            "contentAscentPx" to content.ascent,
            "contentDescentPx" to content.descent,
            "logicalWidthPx" to width,
            "logicalAscentPx" to ascent,
            "logicalDescentPx" to descent,
            "paintOrder" to "BackgroundFillThenMathThenForegroundBorder",
            "atomClass" to MathAtomClass.Ordinary,
            "scriptBaseKind" to ScriptBaseKind.CompoundBox,
            "policy" to "MathJaxBboxMpaddedAndSafeBorderSubset",
        )
        return LaidNode(
            node = node,
            box = box,
            atomClass = MathAtomClass.Ordinary,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun resolveBboxDimension(dimension: MathBboxDimension, emSizePx: Float): Float {
        val pixels = when (dimension.unit) {
            MathBboxDimensionUnit.Point -> dimension.value * CSS_PIXELS_PER_INCH / 72f
            MathBboxDimensionUnit.Em -> dimension.value * emSizePx
            MathBboxDimensionUnit.Ex -> {
                val xHeight = glyphSource.mathFont.xHeight
                if (xHeight == null) {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.MissingBboxXHeight,
                        "The selected math font has no OS/2 sxHeight required by bbox ex units",
                        dimension.range,
                    )
                    0f
                } else {
                    dimension.value * glyphSource.mathFont.scaleDesignUnits(xHeight, emSizePx)
                }
            }
            MathBboxDimensionUnit.Mu -> dimension.value * emSizePx / 18f
            MathBboxDimensionUnit.Pixel -> dimension.value
            MathBboxDimensionUnit.Inch -> dimension.value * CSS_PIXELS_PER_INCH
            MathBboxDimensionUnit.Centimeter -> dimension.value * CSS_PIXELS_PER_INCH / CENTIMETERS_PER_INCH
            MathBboxDimensionUnit.Millimeter -> dimension.value * CSS_PIXELS_PER_INCH / MILLIMETERS_PER_INCH
        }
        decision(
            "MathJaxBboxDimension",
            dimension.range,
            "source" to dimension.sourceText,
            "value" to dimension.value,
            "unit" to dimension.unit,
            "styleFontSizePx" to emSizePx,
            "fontXHeightDesignUnits" to glyphSource.mathFont.xHeight,
            "resolvedPx" to pixels,
            "policy" to "MathJaxBboxCss96DpiEmMuAndOpenTypeOs2XHeight",
        )
        return pixels
    }

    private fun layoutDelimited(
        node: MathDelimited,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val segments = mutableListOf<MathList>()
        val middles = mutableListOf<MathMiddleDelimiter>()
        var segmentStart = node.body.range.start
        var pending = mutableListOf<MathNode>()
        fun finishSegment(endExclusive: Int) {
            val range = if (pending.isEmpty()) {
                SourceRange(segmentStart, segmentStart)
            } else {
                pending.first().range.cover(pending.last().range)
            }
            segments += MathList(pending.toList(), range)
            pending = mutableListOf()
            segmentStart = endExclusive
        }
        node.body.children.forEach { child ->
            if (child is MathMiddleDelimiter) {
                finishSegment(child.range.start)
                middles += child
                segmentStart = child.range.endExclusive
            } else {
                pending += child
            }
        }
        finishSegment(node.body.range.endExclusive)

        val segmentLayouts = segments.map { segment ->
            layoutList(segment, style, alphabetOverride)
        }
        val segmentTrailingPaintColors = segments.map { segment ->
            segment.children.filterIsInstance<MathColorDeclaration>().lastOrNull()?.color
        }
        val innerCleanAscent = segmentLayouts.maxOfOrNull { it.laid.box.texCleanBoxMetrics.ascent } ?: 0f
        val innerCleanDescent = segmentLayouts.maxOfOrNull { it.laid.box.texCleanBoxMetrics.descent } ?: 0f
        val delimiterSize = fontSize(style)
        val axisHeight = glyphSource.mathFont.scaleDesignUnits(constants.axisHeight, delimiterSize)
        val distanceBelowAxis = innerCleanDescent + axisHeight
        val distanceAboveAxis = innerCleanAscent - axisHeight
        val maxAxisDistance = maxOf(distanceBelowAxis, distanceAboveAxis).coerceAtLeast(0f)
        val factorTarget = maxAxisDistance * delimiterFactor / 500f
        val shortfallTarget = 2f * maxAxisDistance - delimiterShortfallPx
        val target = maxOf(factorTarget, shortfallTarget).coerceAtLeast(0f)
        val targetEvidence = DelimiterTargetEvidence(
            innerCleanAscentPx = innerCleanAscent,
            innerCleanDescentPx = innerCleanDescent,
            axisHeightPx = axisHeight,
            maxAxisDistancePx = maxAxisDistance,
            factor = delimiterFactor,
            shortfallPx = delimiterShortfallPx,
            factorTargetPx = factorTarget,
            shortfallTargetPx = shortfallTarget,
            targetPx = target,
        )

        val left = layoutDelimiter(node.left, style, targetEvidence)
        val middleLayouts = middles.mapIndexed { index, middle ->
            layoutDelimiter(middle.delimiter, style, targetEvidence).let { box ->
                segmentTrailingPaintColors[index]?.let(box::withInheritedPaintColor) ?: box
            }
        }
        val right = layoutDelimiter(node.right, style, targetEvidence).let { box ->
            segmentTrailingPaintColors.lastOrNull()?.let(box::withInheritedPaintColor) ?: box
        }
        val delimiterBoxes = listOf(left) + middleLayouts + right
        val completedAscent = maxOf(
            innerCleanAscent,
            delimiterBoxes.maxOfOrNull { it.ascent } ?: 0f,
        )
        val completedDescent = maxOf(
            innerCleanDescent,
            delimiterBoxes.maxOfOrNull { it.descent } ?: 0f,
        )
        var x = 0f
        val glyphs = mutableListOf<MathGlyphPlacement>()
        val rules = mutableListOf<MathRulePlacement>()
        val paintGroups = mutableListOf<MathConstructionPaintGroup>()
        fun append(box: MathBox) {
            val shifted = box.translated(x, 0f)
            glyphs += shifted.glyphs
            rules += shifted.rules
            paintGroups += shifted.constructionPaintGroups
            x += box.width
        }
        append(left)
        segmentLayouts.forEachIndexed { index, segment ->
            append(segment.laid.box)
            middleLayouts.getOrNull(index)?.let(::append)
        }
        append(right)
        val paintedGeometry = geometryExtents(
            width = x,
            glyphs = glyphs,
            rules = rules,
            range = node.range,
            constructionPaintGroups = paintGroups,
        )
        val box = paintedGeometry.copy(
            ascent = completedAscent,
            descent = completedDescent,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = completedAscent,
                descent = completedDescent,
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = paintedGeometry.texCleanBoxMetrics.evidence +
                    segmentLayouts.flatMap { it.laid.box.texCleanBoxMetrics.evidence } +
                    delimiterBoxes.flatMap { it.texCleanBoxMetrics.evidence } +
                    MathTeXCleanBoxEvidence.CompletedChildBox,
            ),
        )
        decision(
            "TeXContentDrivenDelimitedGroup",
            node.range,
            "atomClass" to MathAtomClass.Inner,
            "sizePolicy" to node.sizePolicy,
            "innerCleanAscentPx" to innerCleanAscent,
            "innerCleanDescentPx" to innerCleanDescent,
            "axisHeightPx" to axisHeight,
            "maxAxisDistancePx" to maxAxisDistance,
            "delimiterFactor" to delimiterFactor,
            "delimiterShortfallPx" to delimiterShortfallPx,
            "factorTargetPx" to factorTarget,
            "shortfallTargetPx" to shortfallTarget,
            "targetPx" to target,
            "middleCount" to middles.size,
            "groupBreakPolicy" to "UnbreakableContentDrivenFencedInnerNoad",
            "internalBreaksExported" to false,
            "packingPolicy" to "TeXLeftMiddleRightNoInternalMathGlue",
            "delimiterPaintStatePolicy" to "XeTeXColorStateCoversFollowingMiddleOrRightDelimiterOnly",
            "completedBoxPolicy" to "XeTeXCompletedDelimiterNoadMaxOfInnerCleanAndDelimiterLogicalExtents",
            "completedLogicalAscentPx" to completedAscent,
            "completedLogicalDescentPx" to completedDescent,
            "logicalAdvancePx" to box.width,
            "cleanAscentPx" to box.texCleanBoxMetrics.ascent,
            "cleanDescentPx" to box.texCleanBoxMetrics.descent,
        )
        return LaidNode(
            node = node,
            box = box,
            atomClass = MathAtomClass.Inner,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    /**
     * Replays amsmath's `\bBigg@`: the request is measured in a fresh textstyle math list,
     * independently of both the surrounding style and surrounding content. Each candidate math
     * face derives the request from its own exact `(` glyph box so fallback never mixes MATH
     * metrics or construction ownership across faces.
     */
    private fun layoutFixedDelimiter(
        node: MathFixedDelimiter,
        entryStyle: MathStyle,
    ): LaidNode {
        val measurementStyle = MathStyle.Text
        val measurementSize = fontSize(measurementStyle)
        val mathStrutCandidates = constructionBaseCandidates("(", measurementSize, node.range)
        val targetsByFace = mathStrutCandidates.mapNotNull { measurement ->
            val glyph = measurement.run.glyphs.singleOrNull() ?: return@mapNotNull null
            if (measurement.run.missingGlyph) return@mapNotNull null
            val mathFont = mathFontForFace(glyph.faceId)
            val mathStrutAscent = (-glyph.inkBounds.top).coerceAtLeast(0f)
            val mathStrutDescent = glyph.inkBounds.bottom.coerceAtLeast(0f)
            val bigSize = AMSMATH_BIG_SIZE_SCALE * (mathStrutAscent + mathStrutDescent)
            val requestedExtent = node.size.amsmathFactor * bigSize
            val axisHeight = mathFont.scaleDesignUnits(mathFont.constants.axisHeight, measurementSize)
            val vcenterAscent = requestedExtent / 2f + axisHeight
            val vcenterDescent = (requestedExtent / 2f - axisHeight).coerceAtLeast(0f)
            val maxAxisDistance = requestedExtent / 2f
            val factorTarget = requestedExtent * delimiterFactor / 1000f
            val shortfallTarget = requestedExtent - delimiterShortfallPx
            glyph.faceId to DelimiterTargetEvidence(
                innerCleanAscentPx = vcenterAscent,
                innerCleanDescentPx = vcenterDescent,
                axisHeightPx = axisHeight,
                maxAxisDistancePx = maxAxisDistance,
                factor = delimiterFactor,
                shortfallPx = delimiterShortfallPx,
                factorTargetPx = factorTarget,
                shortfallTargetPx = shortfallTarget,
                targetPx = maxOf(factorTarget, shortfallTarget).coerceAtLeast(0f),
                targetPolicy = "AmsmathFixedVCenterThenXeTeXDelimiterFactorShortfall",
                fixedSize = node.size,
                amsmathFactor = node.size.amsmathFactor,
                mathStrutAscentPx = mathStrutAscent,
                mathStrutDescentPx = mathStrutDescent,
                bigSizePx = bigSize,
                requestedExtentPx = requestedExtent,
                vcenterAscentPx = vcenterAscent,
                vcenterDescentPx = vcenterDescent,
            )
        }.toMap()
        val fallbackTarget = targetsByFace[glyphSource.faceId]
            ?: targetsByFace.values.firstOrNull()
            ?: DelimiterTargetEvidence(
                innerCleanAscentPx = 0f,
                innerCleanDescentPx = 0f,
                axisHeightPx = 0f,
                maxAxisDistancePx = 0f,
                factor = delimiterFactor,
                shortfallPx = delimiterShortfallPx,
                factorTargetPx = 0f,
                shortfallTargetPx = 0f,
                targetPx = 0f,
                targetPolicy = "AmsmathFixedVCenterUnavailableMathStrut",
                fixedSize = node.size,
                amsmathFactor = node.size.amsmathFactor,
            )
        if (targetsByFace.isEmpty()) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingGlyph,
                "The selected formula-wide math face has no exact ( glyph for amsmath fixed delimiter sizing",
                node.range,
            )
        }
        val delimiterBox = layoutDelimiter(
            spec = node.delimiter,
            style = measurementStyle,
            target = fallbackTarget,
            decisionName = "TeXFixedSizeDelimiter",
            groupBreakPolicy = "SingleFixedSizeDelimiterAtom",
            invisibleAdvancePx = 0f,
            targetForFace = targetsByFace::get,
            extraDecisionDetails = mapOf(
                "entryStyle" to entryStyle,
                "measurementStyle" to measurementStyle,
                "role" to node.role,
                "atomClass" to node.atomClass,
                "contentDriven" to false,
            ),
        )
        val selectedFaceId = delimiterBox.glyphs.firstOrNull()?.faceId
        val selectedTarget = selectedFaceId?.let(targetsByFace::get) ?: fallbackTarget
        val completedAscent = maxOf(delimiterBox.ascent, selectedTarget.vcenterAscentPx ?: 0f)
        val completedDescent = maxOf(delimiterBox.descent, selectedTarget.vcenterDescentPx ?: 0f)
        val box = delimiterBox.copy(
            ascent = completedAscent,
            descent = completedDescent,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = completedAscent,
                descent = completedDescent,
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = delimiterBox.texCleanBoxMetrics.evidence + MathTeXCleanBoxEvidence.CompletedChildBox,
            ),
        )
        decision(
            "AmsmathFixedDelimiterNoad",
            node.range,
            "command" to node.size.commandStem,
            "role" to node.role,
            "atomClass" to node.atomClass,
            "entryStyle" to entryStyle,
            "measurementStyle" to measurementStyle,
            "measurementFontSizePx" to measurementSize,
            "constructionFaceId" to selectedFaceId,
            "mathStrutAscentPx" to selectedTarget.mathStrutAscentPx,
            "mathStrutDescentPx" to selectedTarget.mathStrutDescentPx,
            "bigSizeScale" to AMSMATH_BIG_SIZE_SCALE,
            "bigSizePx" to selectedTarget.bigSizePx,
            "amsmathFactor" to node.size.amsmathFactor,
            "requestedExtentPx" to selectedTarget.requestedExtentPx,
            "vcenterAscentPx" to selectedTarget.vcenterAscentPx,
            "vcenterDescentPx" to selectedTarget.vcenterDescentPx,
            "targetPx" to selectedTarget.targetPx,
            "targetPolicy" to selectedTarget.targetPolicy,
            "logicalAdvancePx" to box.width,
            "logicalAscentPx" to box.ascent,
            "logicalDescentPx" to box.descent,
            "contentDriven" to false,
        )
        return LaidNode(
            node = node,
            box = box,
            atomClass = node.atomClass,
            italicCorrectionPx = 0f,
            style = entryStyle,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutDelimiter(
        spec: MathDelimiterSpec,
        style: MathStyle,
        target: DelimiterTargetEvidence,
        decisionName: String = "TeXContentDrivenDelimiter",
        groupBreakPolicy: String = "UnbreakableContentDrivenFencedInnerNoad",
        invisibleAdvancePx: Float = nullDelimiterSpacePx,
        targetForFace: ((MathFaceId) -> DelimiterTargetEvidence?)? = null,
        extraDecisionDetails: Map<String, Any?> = emptyMap(),
    ): MathBox {
        val identity = spec.identity
        if (identity == null || identity == MathDelimiterIdentity.Invisible) {
            val nullSpace = if (identity == MathDelimiterIdentity.Invisible) invisibleAdvancePx else 0f
            decision(
                decisionName,
                spec.range,
                "sourceSpelling" to spec.sourceText,
                "commandRange" to spec.commandRange,
                "delimiterRange" to spec.delimiterRange,
                "scalar" to null,
                "identity" to (identity?.debugName ?: "unsupported"),
                "side" to spec.side,
                "style" to style,
                "fontSizePx" to fontSize(style),
                "innerCleanAscentPx" to target.innerCleanAscentPx,
                "innerCleanDescentPx" to target.innerCleanDescentPx,
                "axisHeightPx" to target.axisHeightPx,
                "delimiterFactor" to target.factor,
                "delimiterShortfallPx" to target.shortfallPx,
                "factorTargetPx" to target.factorTargetPx,
                "shortfallTargetPx" to target.shortfallTargetPx,
                "targetPx" to target.targetPx,
                "targetPolicy" to target.targetPolicy,
                "delimitedSubFormulaMinHeightUsed" to false,
                "construction" to "None",
                "glyphIds" to "",
                "componentOffsetsDesignUnits" to "",
                "achievedAdvancePx" to 0f,
                "centerShiftPx" to 0f,
                "logicalAdvancePx" to 0f,
                "nullDelimiterSpacePx" to nullSpace,
                "packedAdvancePx" to nullSpace,
                "capability" to if (identity == MathDelimiterIdentity.Invisible) {
                    "SupportedInvisibleDelimiter"
                } else {
                    "UnsupportedDelimiterRecovery"
                },
                "fallback" to false,
                "groupBreakPolicy" to groupBreakPolicy,
                *extraDecisionDetails.map { it.key to it.value }.toTypedArray(),
            )
            return emptyBox(spec.range).copy(width = nullSpace)
        }

        val size = fontSize(style)
        val text = scalarString(checkNotNull(identity.scalar))
        val delimiterCandidates = constructionBaseCandidates(text, size, spec.range)
            .mapNotNull { measurement ->
                val glyph = measurement.run.glyphs.singleOrNull() ?: return@mapNotNull null
                if (measurement.run.missingGlyph) return@mapNotNull null
                val candidateTarget = if (targetForFace == null) {
                    target
                } else {
                    targetForFace(glyph.faceId) ?: return@mapNotNull null
                }
                Triple(measurement, candidateTarget, selectVerticalConstruction(
                    baseGlyphId = glyph.glyphId,
                    normalRun = measurement.run,
                    targetHeight = candidateTarget.targetPx,
                    size = size,
                    style = style,
                    range = spec.range,
                    assemblyPolicy = MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue,
                ))
            }
        val selectedDelimiter = delimiterCandidates.firstOrNull { it.third?.reachesTarget == true }
            ?: delimiterCandidates.firstOrNull()
        val baseMeasurement = selectedDelimiter?.first
            ?: glyphSource.shapeOutlineConstructionBase(text, size, spec.range)
        val effectiveTarget = selectedDelimiter?.second
            ?: baseMeasurement.run.glyphs.singleOrNull()?.faceId?.let { targetForFace?.invoke(it) }
            ?: target
        val baseRun = baseMeasurement.run
        val constructionFaceId = baseRun.glyphs.singleOrNull()?.faceId ?: glyphSource.faceId
        val constructionMathFont = mathFontForFace(constructionFaceId)
        val baseGlyphId = baseRun.glyphs.singleOrNull()?.glyphId
        if (baseRun.missingGlyph || baseGlyphId == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingGlyph,
                "The selected formula-wide math face has no ${identity.debugName} delimiter glyph",
                spec.delimiterRange,
            )
        }
        val construction = selectedDelimiter?.third ?: baseGlyphId?.let {
            selectVerticalConstruction(
                baseGlyphId = it,
                normalRun = baseRun,
                targetHeight = effectiveTarget.targetPx,
                size = size,
                style = style,
                range = spec.range,
                assemblyPolicy = MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue,
            )
        }
        val componentMeasurements = construction?.components?.map { component ->
            component to measureConstructionGlyphForFace(
                constructionFaceId,
                component.glyphId,
                size,
                style,
                spec.range,
            )
        }
        val placed = construction?.let {
            placeVerticalConstruction(
                construction = it,
                componentRuns = componentMeasurements.orEmpty().map { measurement ->
                    measurement.first to measurement.second.run
                },
                componentOutlineEvidences = componentMeasurements.orEmpty().map { it.second.evidence },
                size = size,
                style = style,
                sourceRange = spec.range,
                centerComponentsHorizontally = false,
            )
        }
        val baseBox = measuredRunBox(baseRun, spec.range, style, size)
        val rawBox = if (placed == null) {
            baseBox
        } else {
            geometryExtents(placed.width, placed.glyphs, emptyList(), spec.range)
        }
        val achievedAdvance = construction?.let {
            constructionMathFont.scaleDesignUnits(it.advanceMeasurement, size)
        } ?: rawBox.inkBounds.height
        val axisY = -effectiveTarget.axisHeightPx
        val centeringAscent = if (construction?.kind == MathConstructionKind.Assembly) {
            achievedAdvance
        } else {
            (-rawBox.inkBounds.top).coerceAtLeast(0f)
        }
        val centeringDescent = if (construction?.kind == MathConstructionKind.Assembly) {
            0f
        } else {
            rawBox.inkBounds.bottom.coerceAtLeast(0f)
        }
        val centerShift = (centeringAscent - centeringDescent) / 2f - effectiveTarget.axisHeightPx
        val shiftedGlyphs = rawBox.glyphs.map { glyph ->
            glyph.copy(
                baselineY = glyph.baselineY + centerShift,
                inkBounds = glyph.inkBounds.translated(0f, centerShift),
            )
        }
        val outlineMeasurements = if (construction == null) {
            listOf(baseMeasurement)
        } else {
            componentMeasurements.orEmpty().map { it.second }
        }
        val outlineAvailable = outlineMeasurements.isNotEmpty() &&
            outlineMeasurements.all { it.outlineCapability == MathConstructionOutlineCapability.Replayable }
        if (!outlineAvailable && shiftedGlyphs.isNotEmpty()) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingConstructionOutlineEvidence,
                "The math font adapter cannot provide replayable outline evidence for ${identity.debugName}",
                spec.range,
            )
        }
        val paintGroup = shiftedGlyphs.takeIf { it.isNotEmpty() }?.let {
            MathConstructionPaintGroup(
                id = nextConstructionPaintGroupId++,
                kind = MathConstructionPaintKind.Delimiter,
                shapeKind = when (construction?.kind) {
                    MathConstructionKind.Assembly -> MathConstructionShapeKind.Assembly
                    MathConstructionKind.Variant -> MathConstructionShapeKind.Variant
                    MathConstructionKind.BaseGlyph, null -> MathConstructionShapeKind.BaseGlyph
                },
                sourceRange = spec.range,
                outlinePolicy = MathConstructionOutlinePolicy.RequireOutlineUnion,
                faceId = constructionFaceId,
            )
        }
        val groupedGlyphs = shiftedGlyphs.map { glyph ->
            glyph.copy(constructionGroupId = paintGroup?.id)
        }
        val visual = geometryExtents(
            width = rawBox.width,
            glyphs = groupedGlyphs,
            rules = emptyList(),
            range = spec.range,
            constructionPaintGroups = listOfNotNull(paintGroup),
        )
        val logicalAscent = if (construction?.kind == MathConstructionKind.Assembly) {
            (achievedAdvance - centerShift).coerceAtLeast(0f)
        } else {
            visual.ascent
        }
        val logicalDescent = if (construction?.kind == MathConstructionKind.Assembly) {
            centerShift.coerceAtLeast(0f)
        } else {
            visual.descent
        }
        val box = visual.copy(
            ascent = logicalAscent,
            descent = logicalDescent,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = logicalAscent,
                descent = logicalDescent,
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = visual.texCleanBoxMetrics.evidence,
            ),
        )
        val reachesTarget = achievedAdvance + GEOMETRY_EPSILON_PX >= effectiveTarget.targetPx
        if (construction == null && !reachesTarget) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingMathConstruction,
                "${identity.debugName} has no MATH construction covering ${effectiveTarget.targetPx}px",
                spec.range,
            )
        } else if (construction != null && !construction.reachesTarget) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MathVariantTooShort,
                "${identity.debugName} MATH construction does not reach the TeX delimiter target",
                spec.range,
                DiagnosticSeverity.Warning,
            )
        }
        val assemblyValidation = construction?.assemblyValidation
            ?: baseGlyphId?.let(constructionMathFont::verticalAssemblyValidation)
        decision(
            decisionName,
            spec.range,
            "sourceSpelling" to spec.sourceText,
            "commandRange" to spec.commandRange,
            "delimiterRange" to spec.delimiterRange,
            "scalar" to unicodeLabel(checkNotNull(identity.scalar)),
            "identity" to identity.debugName,
            "side" to spec.side,
            "style" to style,
            "fontSizePx" to size,
            "innerCleanAscentPx" to effectiveTarget.innerCleanAscentPx,
            "innerCleanDescentPx" to effectiveTarget.innerCleanDescentPx,
            "axisHeightPx" to effectiveTarget.axisHeightPx,
            "delimiterFactor" to effectiveTarget.factor,
            "delimiterShortfallPx" to effectiveTarget.shortfallPx,
            "factorTargetPx" to effectiveTarget.factorTargetPx,
            "shortfallTargetPx" to effectiveTarget.shortfallTargetPx,
            "targetPx" to effectiveTarget.targetPx,
            "targetPolicy" to effectiveTarget.targetPolicy,
            "fixedSize" to effectiveTarget.fixedSize,
            "amsmathFactor" to effectiveTarget.amsmathFactor,
            "mathStrutAscentPx" to effectiveTarget.mathStrutAscentPx,
            "mathStrutDescentPx" to effectiveTarget.mathStrutDescentPx,
            "bigSizePx" to effectiveTarget.bigSizePx,
            "requestedExtentPx" to effectiveTarget.requestedExtentPx,
            "vcenterAscentPx" to effectiveTarget.vcenterAscentPx,
            "vcenterDescentPx" to effectiveTarget.vcenterDescentPx,
            "delimitedSubFormulaMinHeightUsed" to false,
            "baseGlyphId" to baseGlyphId,
            "constructionFaceId" to constructionFaceId,
            "fontClass" to baseRun.glyphs.firstOrNull()?.fontClass,
            "requestedWeight" to baseRun.glyphs.firstOrNull()?.requestedWeight,
            "resolvedWeight" to baseRun.glyphs.firstOrNull()?.resolvedWeight,
            "fallbackReason" to baseRun.glyphs.firstOrNull()?.fallbackReason,
            "construction" to (construction?.kind ?: "NormalGlyphFallback"),
            "constructionPolicy" to construction?.constructionPolicy,
            "glyphIds" to groupedGlyphs.joinToString(",") { it.glyphId.toString() },
            "componentOffsetsDesignUnits" to construction?.components?.joinToString(",") { it.offset.toString() },
            "componentBaselineOriginsPx" to placed?.componentBaselineOriginsPx?.joinToString(","),
            "componentBottomOriginsPx" to placed?.componentBottomOriginsPx?.joinToString(","),
            "componentHorizontalOriginsPx" to placed?.componentHorizontalOriginsPx?.joinToString(","),
            "connectorOverlapsDesignUnits" to construction?.connectorOverlaps,
            "extenderRepetitions" to construction?.extenderRepetitions,
            "achievedAdvancePx" to achievedAdvance,
            "reachesTarget" to reachesTarget,
            "centeringMetric" to if (construction?.kind == MathConstructionKind.Assembly) {
                "XeTeXAssemblyNominalAdvance"
            } else {
                "ExactGlyphBoundingBox"
            },
            "centerShiftPx" to centerShift,
            "logicalAdvancePx" to box.width,
            "logicalAscentPx" to box.ascent,
            "logicalDescentPx" to box.descent,
            "inkTopPx" to box.inkBounds.top,
            "inkBottomPx" to box.inkBounds.bottom,
            "outlineEvidence" to outlineMeasurements.joinToString(",") { it.evidence.evidenceLabel() },
            "outlineCapability" to outlineMeasurements.joinToString(",") { it.outlineCapability.toString() },
            "capability" to if (outlineAvailable) "ReplayableOutlineUnion" else "OutlineEvidenceUnavailable",
            "fallback" to (construction == null),
            "assemblyValid" to assemblyValidation?.valid,
            "assemblyInvalidReasons" to assemblyValidation?.invalidReasons,
            "assemblyValidationPolicy" to assemblyValidation?.validationPolicy,
            "assemblySpecificationDivergence" to assemblyValidation?.specificationDivergence,
            "assemblyPolicy" to MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue,
            "groupBreakPolicy" to groupBreakPolicy,
            *extraDecisionDetails.map { it.key to it.value }.toTypedArray(),
        )
        return box
    }

    private fun layoutAlphabetScopeNode(node: MathAlphabetScope, style: MathStyle): LaidNode {
        val override = MathAlphabetOverride(node.family, node.alphabet)
        decision(
            "TeXMathAlphabetScope",
            node.range,
            "family" to node.family,
            "alphabet" to node.alphabet,
            "appliesTo" to MathFamilyBinding.Variable,
        )
        return layoutScopedBody(node, node.body, style, override)
    }

    private fun layoutMathVersionScopeNode(node: MathVersionScope, style: MathStyle): LaidNode {
        val override = MathAlphabetOverride(version = node.version)
        decision(
            "TeXMathVersionScope",
            node.range,
            "version" to node.version,
            "appliesTo" to "AllMathAtoms",
            "glyphPolicy" to "FamilySpecificUnicodeMathAlphabetOrExplicitUnsupportedCapability",
        )
        return layoutScopedBody(node, node.body, style, override)
    }

    private fun layoutScopedBody(
        scopeNode: MathNode,
        body: MathNode,
        style: MathStyle,
        override: MathAlphabetOverride,
    ): LaidNode {
        val horizontal = when (body) {
            is MathGroup -> layoutList(body.body, style, override)
            is MathList -> layoutList(body, style, override)
            else -> null
        }
        return if (horizontal != null) {
            val single = horizontal.items.singleOrNull()
            if (single?.node is MathSymbol) single.laid.copy(
                node = scopeNode,
                box = single.laid.box.copy(range = scopeNode.range),
            ) else horizontal.laid.copy(
                node = scopeNode,
                box = horizontal.laid.box.copy(range = scopeNode.range),
                atomClass = single?.atomClass ?: MathAtomClass.Ordinary,
                italicCorrectionPx = 0f,
                scriptBaseKind = single?.laid?.scriptBaseKind ?: ScriptBaseKind.CompoundBox,
            )
        } else {
            layoutNode(body, style, override).copy(node = scopeNode)
        }
    }

    private fun layoutSymbol(
        node: MathSymbol,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val size = fontSize(style)
        val request = symbolRequest(node, style, alphabetOverride)
        val resolved = glyphSource.resolveSymbol(request, size)
        val run = resolved.run
        if (!resolved.supported) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.UnsupportedMathAlphabet,
                "The selected formula-wide math face cannot resolve ${node.identity.debugName} " +
                    "in ${request.family}/${request.alphabet}",
                node.range,
            )
        }
        if (run.missingGlyph) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingGlyph,
                "The selected formula-wide math face has no ${request.family}/${request.alphabet} glyph " +
                    "for ${node.identity.debugName}",
                node.range,
            )
        }
        val lastGlyph = run.glyphs.lastOrNull()
        val symbolMathFont = lastGlyph?.let { mathFontForFaceOrNull(it.faceId) }
        val italicCorrection = lastGlyph?.let { symbolMathFont?.italicCorrection(it.glyphId, size) } ?: 0f
        decision(
            "TeXMathSymbolResolution",
            node.range,
            "sourceText" to node.sourceText,
            "identity" to node.identity.debugName,
            "baseScalar" to unicodeLabel(node.identity.baseScalar),
            "atomClass" to node.atomClass,
            "familyBinding" to node.familyBinding,
            "declaredFamily" to node.family,
            "declaredAlphabet" to node.alphabet,
            "resolvedFamily" to request.family,
            "resolvedAlphabet" to request.alphabet,
            "backendScalar" to unicodeLabel(resolved.backendScalar),
            "glyphIds" to run.glyphs.joinToString(",") { it.glyphId.toString() },
            "faceIds" to run.glyphs.joinToString(",") { it.faceId.toString() },
            "fontClass" to run.glyphs.firstOrNull()?.fontClass,
            "requestedWeight" to run.glyphs.firstOrNull()?.requestedWeight,
            "resolvedWeight" to run.glyphs.firstOrNull()?.resolvedWeight,
            "fallbackReason" to run.glyphs.firstOrNull()?.fallbackReason,
            "italicCorrectionPx" to italicCorrection,
            "shaping" to "single-noad",
        )
        val placements = run.glyphs.map { glyph ->
            MathGlyphPlacement(
                glyphId = glyph.glyphId,
                x = glyph.x,
                baselineY = glyph.baselineOffsetPx,
                advance = glyph.advance,
                inkBounds = glyph.inkBounds.translated(glyph.x, glyph.baselineOffsetPx),
                fontSizePx = size,
                sourceRange = node.range,
                style = style,
                faceId = glyph.faceId,
                fontClass = glyph.fontClass,
                requestedWeight = glyph.requestedWeight,
                resolvedWeight = glyph.resolvedWeight,
                fallbackReason = glyph.fallbackReason,
            )
        }
        return LaidNode(
            node = node,
            box = geometryExtents(run.width, placements, emptyList(), node.range),
            atomClass = node.atomClass,
            italicCorrectionPx = italicCorrection,
            style = style,
            scriptBaseKind = when {
                placements.size != 1 -> ScriptBaseKind.CompoundBox
                mathFontForFaceOrNull(placements.single().faceId)
                    ?.extendedShapeGlyphs?.contains(placements.single().glyphId) == true -> ScriptBaseKind.ExtendedShape
                else -> ScriptBaseKind.Character
            },
        )
    }

    private fun symbolRequest(
        node: MathSymbol,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): MathSymbolGlyphRequest = MathSymbolGlyphRequest(
        identity = node.identity,
        family = when {
            alphabetOverride?.version != null -> node.family
            node.familyBinding == MathFamilyBinding.Variable -> alphabetOverride?.family ?: node.family
            else -> node.family
        },
        alphabet = when (alphabetOverride?.version) {
            MathVersion.Bold -> when (node.family) {
                MathFamily.Letters -> MathAlphabet.BoldItalic
                MathFamily.Operators, MathFamily.Symbols, MathFamily.LargeSymbols -> MathAlphabet.Bold
            }
            null -> if (node.familyBinding == MathFamilyBinding.Variable) {
                alphabetOverride?.alphabet ?: node.alphabet
            } else {
                node.alphabet
            }
        },
        style = style,
        sourceRange = node.range,
    )

    private fun layoutText(node: MathText, style: MathStyle): LaidNode {
        val box = layoutTextSegments(node.segments, style, node.range, node.origin)
        decision(
            "TeXEmbeddedText",
            node.range,
            "commandRange" to node.commandRange,
            "contentRange" to node.contentRange,
            "text" to node.text,
            "origin" to node.origin,
            "segmentCount" to node.segments.size,
            "spaceCount" to node.text.count { it.isWhitespace() || it == '\u00A0' },
            "style" to style,
            "fontSizePx" to fontSize(style),
            "shaping" to "TextRunNotMathNoadSequence",
            "measurementPaintSource" to "HostMathTextRunProvider",
            "textLocale" to textLocale,
            "faceIds" to box.glyphs.map { it.faceId }.distinct().joinToString(","),
            "requestedWeights" to box.glyphs.map { it.requestedWeight }.distinct().joinToString(","),
            "resolvedWeights" to box.glyphs.map { it.resolvedWeight }.distinct().joinToString(","),
            "mathFallbackReasons" to box.glyphs.mapNotNull { it.fallbackReason }.distinct().joinToString(","),
            "hostRoles" to box.glyphs.mapNotNull { it.hostTextDecision?.hostRole }.distinct().joinToString(","),
            "hostFontKeys" to box.glyphs.mapNotNull { it.hostTextDecision?.fontKey }.distinct().joinToString(","),
            "hostSelectionReasons" to box.glyphs.mapNotNull { it.hostTextDecision?.selectionReason }.distinct().joinToString(","),
            "hostSubstitutionReasons" to box.glyphs.mapNotNull { it.hostTextDecision?.substitutionReason }.distinct().joinToString(","),
            "hostCapabilityIssues" to box.glyphs.mapNotNull { it.hostTextDecision?.capabilityIssue?.code }.distinct().joinToString(","),
            "baselinePolicy" to "HostRunBaselineWithPerGlyphShapingOffsets",
            "glyphBaselineOffsetsPx" to box.glyphs.joinToString(",") { it.baselineY.toString() },
            "logicalAscentPx" to box.ascent,
            "logicalDescentPx" to box.descent,
            "inkTopPx" to box.inkBounds.top,
            "inkBottomPx" to box.inkBounds.bottom,
        )
        return LaidNode(
            node = node,
            box = box,
            atomClass = MathAtomClass.Ordinary,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutTextSegments(
        segments: List<MathTextSegment>,
        style: MathStyle,
        range: SourceRange,
        origin: MathTextOrigin? = null,
    ): MathBox {
        val size = fontSize(style)
        var x = 0f
        var hostLogicalAscent = 0f
        var hostLogicalDescent = 0f
        val placements = mutableListOf<MathGlyphPlacement>()
        segments.forEach { segment ->
            val run = if (origin == null) {
                // Declared operator names remain an operators-family math run, not host prose.
                glyphSource.shapeText(segment.text, size, segment.range)
            } else {
                val provider = textRunProvider
                if (provider == null) {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.MissingTextRunProvider,
                        "Text atom '${segment.text}' requires an injected host MathTextRunProvider",
                        segment.range,
                    )
                    return@forEach
                }
                when (val result = provider.shapeTextAtom(
                    MathTextRunRequest(
                        text = segment.text,
                        sourceRange = segment.range,
                        fontSizePx = size,
                        requestedWeight = glyphSource.requestedWeight,
                        locale = textLocale,
                        origin = origin,
                    ),
                )) {
                    is MathTextRunProviderResult.Ready -> result.run
                    is MathTextRunProviderResult.CapabilityIssue -> {
                        diagnostics += result.issue.asDiagnostic()
                        return@forEach
                    }
                }
            }
            if (origin != null) {
                validateHostTextRun(segment, run)?.let { invalid ->
                    diagnostics += invalid
                    return@forEach
                }
                hostLogicalAscent = max(hostLogicalAscent, run.ascent)
                hostLogicalDescent = max(hostLogicalDescent, run.descent)
                val invalidGlyph = run.glyphs.firstOrNull { glyph ->
                    val host = glyph.hostTextDecision
                    glyph.fallbackReason != null || host == null || host.faceId != glyph.faceId ||
                        host.requestedWeight != glyph.requestedWeight ||
                        host.resolvedWeight != glyph.resolvedWeight ||
                        host.clusterRangeUtf16.start != glyph.textCluster ||
                        host.sourceRange.start != segment.range.start + glyph.textCluster ||
                        host.sourceRange.endExclusive > segment.range.endExclusive
                }
                if (invalidGlyph != null) {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.InvalidHostTextRunEvidence,
                        "Host text glyph ${invalidGlyph.glyphId} does not carry a matching structured host face decision",
                        segment.range,
                    )
                }
            }
            if (run.missingGlyph) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MissingGlyph,
                    "The selected formula-wide face cannot shape embedded text '${segment.text}'",
                    segment.range,
                )
            }
            val clusterBoundaries = (run.glyphs.map { it.textCluster } + segment.text.length)
                .distinct()
                .sorted()
            run.glyphs.forEach { glyph ->
                val sourceRange = textClusterSourceRange(
                    segment,
                    glyph.textCluster,
                    clusterBoundaries.firstOrNull { it > glyph.textCluster },
                )
                placements += MathGlyphPlacement(
                    glyphId = glyph.glyphId,
                    x = x + glyph.x,
                    baselineY = glyph.baselineOffsetPx,
                    advance = glyph.advance,
                    inkBounds = glyph.inkBounds.translated(x + glyph.x, glyph.baselineOffsetPx),
                    fontSizePx = size,
                    sourceRange = sourceRange,
                    style = style,
                    faceId = glyph.faceId,
                    fontClass = glyph.fontClass,
                    requestedWeight = glyph.requestedWeight,
                    resolvedWeight = glyph.resolvedWeight,
                    fallbackReason = glyph.fallbackReason,
                    hostTextDecision = glyph.hostTextDecision?.copy(sourceRange = sourceRange),
                )
            }
            x += run.width
        }
        val geometry = geometryExtents(x, placements, emptyList(), range)
        return if (origin == null) {
            geometry
        } else {
            geometry.copy(
                ascent = hostLogicalAscent,
                descent = hostLogicalDescent,
                texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                    ascent = hostLogicalAscent,
                    descent = hostLogicalDescent,
                    policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                    evidence = setOf(MathTeXCleanBoxEvidence.HostTextRunMetrics),
                ),
            )
        }
    }

    private fun validateHostTextRun(
        segment: MathTextSegment,
        run: MeasuredMathRun,
    ): MathDiagnostic? {
        fun invalid(message: String) = MathDiagnostic(
            DiagnosticCode.InvalidHostTextRunEvidence,
            message,
            segment.range,
        )
        if (!run.width.isFinite() || run.width < 0f ||
            !run.ascent.isFinite() || run.ascent < 0f ||
            !run.descent.isFinite() || run.descent < 0f
        ) {
            return invalid("Host text run has non-finite or negative logical metrics")
        }
        run.glyphs.forEach { glyph ->
            val bounds = glyph.inkBounds
            if (!glyph.x.isFinite() || !glyph.advance.isFinite() || glyph.advance < 0f ||
                !glyph.baselineOffsetPx.isFinite() ||
                !bounds.left.isFinite() || !bounds.top.isFinite() ||
                !bounds.right.isFinite() || !bounds.bottom.isFinite() ||
                bounds.right < bounds.left || bounds.bottom < bounds.top
            ) {
                return invalid("Host text glyph ${glyph.glyphId} has invalid placement or ink metrics")
            }
            if (glyph.textCluster !in segment.text.indices) {
                return invalid("Host text glyph ${glyph.glyphId} has out-of-range UTF-16 cluster ${glyph.textCluster}")
            }
            val host = glyph.hostTextDecision
                ?: return invalid("Host text glyph ${glyph.glyphId} is missing structured face evidence")
            val cluster = host.clusterRangeUtf16
            if (cluster.start != glyph.textCluster || cluster.endExclusive <= cluster.start ||
                cluster.endExclusive > segment.text.length
            ) {
                return invalid("Host text glyph ${glyph.glyphId} has an invalid cluster range $cluster")
            }
            if (host.faceId != glyph.faceId || host.requestedWeight != glyph.requestedWeight ||
                host.resolvedWeight != glyph.resolvedWeight || glyph.fallbackReason != null
            ) {
                return invalid("Host text glyph ${glyph.glyphId} has inconsistent face or weight evidence")
            }
            if (host.sourceRange.start < segment.range.start ||
                host.sourceRange.endExclusive > segment.range.endExclusive || host.sourceRange.isEmpty
            ) {
                return invalid("Host text glyph ${glyph.glyphId} has an out-of-range source mapping")
            }
            host.capabilityIssue?.let { return it.asDiagnostic() }
        }
        return null
    }

    private fun textClusterSourceRange(
        segment: MathTextSegment,
        cluster: Int,
        nextCluster: Int?,
    ): SourceRange {
        if (segment.text.length != segment.range.length) return segment.range
        val start = (segment.range.start + cluster).coerceIn(segment.range.start, segment.range.endExclusive)
        val end = (segment.range.start + (nextCluster ?: segment.text.length))
            .coerceIn(start, segment.range.endExclusive)
        return SourceRange(start, end)
    }

    private fun MathHostTextCapabilityIssue.asDiagnostic(): MathDiagnostic = MathDiagnostic(
        code = when (code) {
            MathHostTextCapabilityIssueCode.NonReplayableHostTextRun,
            MathHostTextCapabilityIssueCode.PlatformMultiFaceStringDraw,
            -> DiagnosticCode.NonReplayableHostTextRun

            MathHostTextCapabilityIssueCode.UnsupportedBidirectionalText,
            MathHostTextCapabilityIssueCode.UnsupportedComplexScript,
            -> DiagnosticCode.UnsupportedHostTextShaping

            MathHostTextCapabilityIssueCode.InvalidHostTextRunEvidence ->
                DiagnosticCode.InvalidHostTextRunEvidence
        },
        message = message,
        range = sourceRange,
    )

    private fun resolveTopAccentAttachment(
        faceId: MathFaceId,
        glyphId: UShort,
        fontSizePx: Float,
        fallbackAdvancePx: Float,
        range: SourceRange,
        role: String,
    ): AccentAttachmentEvidence = try {
        val font = mathFontForFaceOrNull(faceId)
            ?: return AccentAttachmentEvidence(fallbackAdvancePx / 2f, "TextFaceAdvanceCenter")
        val ignoredDevice = font.topAccentAttachmentDeviceAdjustments[glyphId]
        AccentAttachmentEvidence(
            font.topAccentAttachment(glyphId, fontSizePx, fallbackAdvancePx),
            if (ignoredDevice != null) {
                "XeTeXHarfBuzzZeroPpemMathTopAccentAttachment"
            } else if (glyphId in font.topAccentAttachments) {
                "MathTopAccentAttachment"
            } else {
                "OpenTypeAdvanceCenterFallback"
            },
            ignoredDevice,
        )
    } catch (failure: OpenTypeMathException) {
        diagnostics += MathDiagnostic(
            failure.diagnosticCode,
            "$role top-accent attachment cannot consume its device/variation adjustment: ${failure.message}",
            range,
        )
        AccentAttachmentEvidence(fallbackAdvancePx / 2f, "ExplicitUnsupportedDeviceAdjustmentCenterRecovery")
    }

    private fun layoutAccent(
        node: MathAccent,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val isBottom = node.identity.placement == MathAccentPlacement.Bottom
        val nucleusStyle = style.cramped()
        // XeTeX clean_box uses the exact native glyph bbox for a character nucleus and the
        // already-completed TeX box for a compound nucleus. Reuse the same placement kernel as
        // ordinary side scripts so accent clearance and replayed glyph bounds cannot diverge.
        val base = layoutNode(node.base, nucleusStyle, alphabetOverride)
            .withNativeOutlineBoxForSideScriptPlacement()
        val size = fontSize(style)
        val accentText = scalarString(node.identity.scalar)
        val normal = glyphSource.shapeConstructionBase(accentText, size, node.commandRange)
        val normalGlyph = normal.glyphs.singleOrNull()
        if (normal.missingGlyph || normalGlyph == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingGlyph,
                "The selected formula-wide math face has no ${node.identity.debugName} accent glyph",
                node.commandRange,
            )
            return base.copy(node = node, box = base.box.copy(range = node.range))
        }
        val constructionFaceId = normalGlyph.faceId
        val constructionMathFont = mathFontForFace(constructionFaceId)
        val normalOutline = measureGlyphOutlineForFace(
            constructionFaceId,
            normalGlyph.glyphId,
            size,
            style,
            node.commandRange,
        )
        val normalMeasuredGlyph = normalOutline.glyphs.single()
        val normalWidth = normalMeasuredGlyph.inkBounds.width.coerceAtLeast(normal.width)
        val targetWidth = base.box.width
        val hasHorizontalConstruction = normalGlyph.glyphId in constructionMathFont.horizontalConstructions
        val construction = if (node.identity.wide || hasHorizontalConstruction) {
            constructionMathFont.horizontalConstruction(
                MathHorizontalConstructionRequest(
                    baseGlyphId = normalGlyph.glyphId,
                    targetSizePx = targetWidth,
                    fontSizePx = size,
                    normalGlyphWidthPx = normalWidth,
                    normalGlyphOrthogonalExtentPx = normalMeasuredGlyph.inkBounds.height,
                ),
                glyphGrowthExtentPx = { glyphId ->
                    measureGlyphOutlineForFace(
                        constructionFaceId, glyphId, size, style, node.commandRange,
                    ).glyphs.single().inkBounds.width
                },
            ) { glyphId ->
                measureGlyphOutlineForFace(
                    constructionFaceId, glyphId, size, style, node.commandRange,
                )
                    .glyphs.single().inkBounds.height
            }
        } else {
            null
        }
        val selected = construction ?: org.tiqian.math.font.opentype.MathVerticalConstruction(
            kind = MathConstructionKind.BaseGlyph,
            components = listOf(MathGlyphComponent(normalGlyph.glyphId, 0f)),
            advanceMeasurement = normalWidth * constructionMathFont.unitsPerEm / size,
            reachesTarget = !node.identity.wide,
            constructionPolicy = if (node.identity.wide) {
                "VisibleNormalAccentAfterMissingHorizontalConstruction"
            } else {
                "TeXFixedMathAccentNormalGlyph"
            },
            orthogonalAdvancePx = normalMeasuredGlyph.inkBounds.height,
        ).also { fallback ->
            if (node.identity.wide) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MissingMathConstruction,
                    "${node.identity.debugName} has no horizontal MATH construction covering ${targetWidth}px",
                    node.commandRange,
                )
            }
        }
        // TeX uses the largest font-provided accent variant when a horizontal assembly is absent.
        // Unlike a radical or delimiter, an accent is allowed to leave base overhang; the exact
        // under-coverage remains auditable in the decision instead of becoming formula fallback.
        val groupId = if (selected.kind == MathConstructionKind.Assembly) nextConstructionPaintGroupId++ else null
        val accentGlyphs = selected.components.map { component ->
            val measured = measureGlyphOutlineForFace(
                constructionFaceId,
                component.glyphId,
                size,
                style,
                node.commandRange,
            ).glyphs.single()
            val componentX = constructionMathFont.scaleDesignUnits(component.offset, size)
            MathGlyphPlacement(
                glyphId = component.glyphId,
                x = componentX,
                baselineY = 0f,
                advance = measured.advance,
                inkBounds = measured.inkBounds.translated(componentX, 0f),
                fontSizePx = size,
                sourceRange = node.commandRange,
                style = style,
                constructionGroupId = groupId,
                faceId = measured.faceId,
                fontClass = measured.fontClass,
                requestedWeight = measured.requestedWeight,
                resolvedWeight = measured.resolvedWeight,
                fallbackReason = measured.fallbackReason,
            )
        }
        val achievedWidth = constructionMathFont.scaleDesignUnits(selected.advanceMeasurement, size)
        val accentAttachmentEvidence = if (selected.components.size == 1) {
            val placement = accentGlyphs.single()
            resolveTopAccentAttachment(
                placement.faceId, placement.glyphId, size, achievedWidth, node.commandRange, "accent",
            ).let { it.copy(valuePx = placement.x + it.valuePx) }
        } else {
            AccentAttachmentEvidence(achievedWidth / 2f, "AssemblyLogicalCenter")
        }
        val accentAttachment = accentAttachmentEvidence.valuePx
        val baseGlyph = base.box.singleGlyphOrNull()
        val baseAttachmentEvidence = if (isBottom) {
            AccentAttachmentEvidence(base.box.width / 2f, "XeTeXBottomAccentNucleusLogicalCenter")
        } else if (baseGlyph != null) {
            resolveTopAccentAttachment(
                baseGlyph.faceId, baseGlyph.glyphId, baseGlyph.fontSizePx, baseGlyph.advance, node.base.range, "base",
            ).let { it.copy(valuePx = baseGlyph.x + it.valuePx) }
        } else {
            AccentAttachmentEvidence(base.box.width / 2f, "CompoundBoxLogicalCenter")
        }
        val baseAttachment = baseAttachmentEvidence.valuePx
        val accentX = baseAttachment - accentAttachment
        val accentBaseHeight = scale(constants.accentBaseHeight, style)
        val baseInkAscent = (-base.box.inkBounds.top).coerceAtLeast(0f)
        val baseCleanAscent = base.box.texCleanBoxMetrics.ascent
        val baseCleanDescent = base.box.texCleanBoxMetrics.descent
        val accentBaselineY = if (isBottom) {
            baseCleanDescent
        } else {
            -(baseCleanAscent - accentBaseHeight).coerceAtLeast(0f)
        }
        val positionedAccent = accentGlyphs.map { glyph ->
            glyph.copy(
                x = glyph.x + accentX,
                baselineY = accentBaselineY,
                inkBounds = glyph.inkBounds.translated(accentX, accentBaselineY),
            )
        }
        val groups = buildList {
            addAll(base.box.constructionPaintGroups)
            if (groupId != null) {
                add(
                    MathConstructionPaintGroup(
                        id = groupId,
                        kind = MathConstructionPaintKind.Accent,
                        shapeKind = MathConstructionShapeKind.Assembly,
                        sourceRange = node.commandRange,
                        outlinePolicy = MathConstructionOutlinePolicy.RequireOutlineUnion,
                        faceId = constructionFaceId,
                    ),
                )
            }
        }
        val glyphs = base.box.glyphs + positionedAccent
        val inkGeometry = geometryExtents(
            width = base.box.width,
            glyphs = glyphs,
            rules = base.box.rules,
            range = node.range,
            constructionPaintGroups = groups,
        )
        val accentTop = positionedAccent.minOfOrNull { it.inkBounds.top } ?: 0f
        val accentBottom = positionedAccent.maxOfOrNull { it.inkBounds.bottom } ?: 0f
        val cleanAscent = if (isBottom) base.box.texCleanBoxMetrics.ascent else max(baseCleanAscent, -accentTop)
        val cleanDescent = if (isBottom) max(baseCleanDescent, accentBottom) else baseCleanDescent
        val box = inkGeometry.copy(
            ascent = if (isBottom) base.box.ascent else max(base.box.ascent, -accentTop),
            descent = if (isBottom) max(base.box.descent, accentBottom) else base.box.descent,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = cleanAscent,
                descent = cleanDescent,
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = inkGeometry.texCleanBoxMetrics.evidence +
                    base.box.texCleanBoxMetrics.evidence + MathTeXCleanBoxEvidence.CompletedChildBox,
            ),
        )
        decision(
            "OpenTypeMathAccent",
            node.range,
            "identity" to node.identity.debugName,
            "wide" to node.identity.wide,
            "placement" to node.identity.placement,
            "style" to style,
            "nucleusStyle" to nucleusStyle,
            "nucleusBoxPolicy" to "XeTeXNativeGlyphOutlineOrCompletedChildBox",
            "baseWidthPx" to base.box.width,
            "baseInkAscentPx" to baseInkAscent,
            "baseCleanAscentPx" to baseCleanAscent,
            "accentBaseHeightPx" to accentBaseHeight,
            "verticalPlacementPolicy" to if (isBottom) {
                "XeTeXBottomMathAccentAfterCompletedNucleusDepth"
            } else {
                "XeTeXMakeMathAccentMinCleanBoxHeightAndAccentBaseHeight"
            },
            "flattenedAccentBaseHeightPx" to scale(constants.flattenedAccentBaseHeight, style),
            "baseAttachmentPx" to baseAttachment,
            "baseAttachmentPolicy" to baseAttachmentEvidence.policy,
            "baseAttachmentIgnoredDeviceAdjustment" to baseAttachmentEvidence.ignoredDeviceAdjustment,
            "accentAttachmentPx" to accentAttachment,
            "accentAttachmentPolicy" to accentAttachmentEvidence.policy,
            "accentAttachmentIgnoredDeviceAdjustment" to accentAttachmentEvidence.ignoredDeviceAdjustment,
            "accentX" to accentX,
            "accentBaselineY" to accentBaselineY,
            "construction" to selected.kind,
            "constructionPolicy" to selected.constructionPolicy,
            "glyphIds" to selected.components.joinToString(",") { it.glyphId.toString() },
            "componentOffsetsDesignUnits" to selected.components.joinToString(",") { it.offset.toString() },
            "connectorOverlapsDesignUnits" to selected.connectorOverlaps,
            "targetWidthPx" to targetWidth,
            "achievedWidthPx" to achievedWidth,
            "reachesTarget" to selected.reachesTarget,
            "coveragePolicy" to if (selected.reachesTarget) "CoversTarget" else "TeXLargestAvailableAccentVariantAllowsBaseOverhang",
            "assemblyValidation" to selected.assemblyValidation,
            "paintPolicy" to if (groupId == null) "DirectSingleGlyphReplay" else "SemanticOutlineUnion",
            "logicalAdvancePx" to box.width,
            "cleanAscentPx" to box.texCleanBoxMetrics.ascent,
            "cleanDescentPx" to box.texCleanBoxMetrics.descent,
        )
        return LaidNode(
            node = node,
            box = box,
            atomClass = MathAtomClass.Ordinary,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutRuleDecoration(
        node: MathRuleDecoration,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val isOver = node.kind == MathRuleDecorationKind.Overline
        // TeX make_overline cleans its nucleus in cramped style, while make_under keeps
        // the current style. This distinction matters for fractions and scripts nested
        // inside the decoration; it is not a renderer-side visual adjustment.
        val nucleusStyle = if (isOver) style.cramped() else style
        val base = layoutNode(node.base, nucleusStyle, alphabetOverride)
        val gap = scale(if (isOver) constants.overbarVerticalGap else constants.underbarVerticalGap, style)
        val thickness = scale(if (isOver) constants.overbarRuleThickness else constants.underbarRuleThickness, style)
        val extra = scale(if (isOver) constants.overbarExtraAscender else constants.underbarExtraDescender, style)
        val rule = if (isOver) {
            val bottom = base.box.inkBounds.top - gap
            MathRulePlacement(0f, bottom - thickness, base.box.width, bottom, node.commandRange)
        } else {
            val top = base.box.inkBounds.bottom + gap
            MathRulePlacement(0f, top, base.box.width, top + thickness, node.commandRange)
        }
        val geometry = geometryExtents(
            base.box.width,
            base.box.glyphs,
            base.box.rules + rule,
            node.range,
            base.box.constructionPaintGroups,
        )
        val logicalAscent = if (isOver) max(base.box.ascent, -rule.top + extra) else base.box.ascent
        val logicalDescent = if (isOver) base.box.descent else max(base.box.descent, rule.bottom + extra)
        val cleanAscent = if (isOver) max(base.box.texCleanBoxMetrics.ascent, -rule.top + extra) else base.box.texCleanBoxMetrics.ascent
        val cleanDescent = if (isOver) base.box.texCleanBoxMetrics.descent else max(base.box.texCleanBoxMetrics.descent, rule.bottom + extra)
        val box = geometry.copy(
            ascent = logicalAscent,
            descent = logicalDescent,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                cleanAscent,
                cleanDescent,
                MathTeXCleanBoxPolicy.CompletedLayoutBox,
                geometry.texCleanBoxMetrics.evidence + base.box.texCleanBoxMetrics.evidence +
                    MathTeXCleanBoxEvidence.CompletedChildBox + MathTeXCleanBoxEvidence.RuleGeometry,
            ),
        )
        decision(
            "OpenTypeMathRuleDecoration",
            node.range,
            "kind" to node.kind,
            "style" to style,
            "nucleusStyle" to nucleusStyle,
            "baseInkTopPx" to base.box.inkBounds.top,
            "baseInkBottomPx" to base.box.inkBounds.bottom,
            "verticalGapPx" to gap,
            "ruleThicknessPx" to thickness,
            "extraReservePx" to extra,
            "ruleLeftPx" to rule.left,
            "ruleTopPx" to rule.top,
            "ruleRightPx" to rule.right,
            "ruleBottomPx" to rule.bottom,
            "logicalAscentPx" to box.ascent,
            "logicalDescentPx" to box.descent,
            "cleanAscentPx" to box.texCleanBoxMetrics.ascent,
            "cleanDescentPx" to box.texCleanBoxMetrics.descent,
            "geometryPolicy" to if (isOver) "OpenTypeOverbarInkGapRuleExtraAscender" else "OpenTypeUnderbarInkGapRuleExtraDescender",
        )
        return LaidNode(
            node = node,
            box = box,
            atomClass = MathAtomClass.Ordinary,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutOperatorName(
        node: MathOperatorName,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        if (node.origin == MathOperatorNameOrigin.OperatorNameCommand) {
            val textBox = layoutTextSegments(node.nameSegments.orEmpty(), style, node.range)
            decision(
                "TeXDeclaredOperatorName",
                node.range,
                "name" to node.name,
                "origin" to node.origin,
                "nameRange" to node.nameRange,
                "atomClass" to MathAtomClass.Operator,
                "limitsPolicy" to node.limitsPolicy,
                "limitsPolicyExplicit" to node.hasExplicitLimitsPolicy,
                "shaping" to "SingleUprightTextRunPerSourceSegment",
            )
            return LaidNode(
                node = node,
                box = textBox,
                atomClass = MathAtomClass.Operator,
                italicCorrectionPx = 0f,
                style = style,
                scriptBaseKind = ScriptBaseKind.CompoundBox,
            )
        }
        // Render the name as upright roman letters, then present the whole run as one Operator-class
        // atom so inter-atom spacing (`2\sin x`, `\sin x`) is correct. Every letter maps back to the
        // command's source range, so selection and source-partitioning treat the name as one unit.
        val letters = node.name.map { ch ->
            MathSymbol(
                sourceText = ch.toString(),
                identity = MathSymbolIdentity.LatinLetter(ch),
                atomClass = MathAtomClass.Ordinary,
                family = MathFamily.Operators,
                familyBinding = MathFamilyBinding.Fixed,
                alphabet = MathAlphabet.Roman,
                range = node.commandRange,
            )
        }
        val horizontal = layoutList(MathList(letters, node.commandRange), style, alphabetOverride)
        decision(
            "TeXMathOperatorName",
            node.range,
            "name" to node.name,
            "limitsPolicy" to node.limitsPolicy,
            "limitsPolicyExplicit" to node.hasExplicitLimitsPolicy,
            "commandRange" to node.commandRange,
            "modifierRange" to node.limitsModifierRange,
            "atomClass" to MathAtomClass.Operator,
        )
        return horizontal.laid.copy(
            node = node,
            box = horizontal.laid.box.copy(range = node.range),
            atomClass = MathAtomClass.Operator,
            italicCorrectionPx = 0f,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutOperatorNoad(
        node: MathOperatorNoad,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val nucleus = layoutNode(node.nucleus, style, alphabetOverride).completedTeXMathField()
        decision(
            "TeXMathOperatorNoad",
            node.range,
            "commandRange" to node.commandRange,
            "nucleusRange" to node.nucleus.range,
            "limitsPolicy" to node.limitsPolicy,
            "limitsPolicyExplicit" to node.hasExplicitLimitsPolicy,
            "modifierRange" to node.limitsModifierRange,
            "atomClass" to MathAtomClass.Operator,
            "nucleusPolicy" to "XeTeXCleanBoxSubMlistCurrentStyle",
            "italicCorrectionPx" to 0f,
        )
        return LaidNode(
            node = node,
            box = nucleus.box.copy(range = node.range),
            atomClass = MathAtomClass.Operator,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutBraceNoad(
        node: MathBraceNoad,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val identity = if (node.kind == MathBraceKind.Over) {
            MathAccentIdentity.OverBrace
        } else {
            MathAccentIdentity.UnderBrace
        }
        val accent = layoutAccent(
            MathAccent(
                identity = identity,
                commandRange = node.commandRange,
                base = node.base,
                range = node.range,
            ),
            style,
            alphabetOverride,
        )
        decision(
            "TeXBraceOperatorNoad",
            node.range,
            "kind" to node.kind,
            "commandRange" to node.commandRange,
            "baseRange" to node.base.range,
            "limitsPolicy" to node.limitsPolicy,
            "limitsPolicyExplicit" to node.hasExplicitLimitsPolicy,
            "modifierRange" to node.limitsModifierRange,
            "accentIdentity" to identity,
            "atomClass" to MathAtomClass.Operator,
            "constructionPolicy" to "XeTeXGrowingTopOrBottomMathAccentWrappedInLimitsOpNoad",
        )
        return accent.copy(
            node = node,
            box = accent.box.copy(range = node.range),
            atomClass = MathAtomClass.Operator,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutOperator(
        node: MathOperator,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride? = null,
    ): LaidNode {
        alphabetOverride?.version?.let { version ->
            diagnostics += MathDiagnostic(
                DiagnosticCode.UnsupportedMathAlphabet,
                "The selected formula-wide math face has no $version LargeSymbols math version for ${node.identity.debugName}",
                node.commandRange,
            )
            decision(
                "TeXMathVersionCapability",
                node.commandRange,
                "version" to version,
                "identity" to node.identity.debugName,
                "family" to MathFamily.LargeSymbols,
                "capability" to "UnsupportedNoFormulaWideBoldLargeSymbolsFace",
            )
        }
        val size = fontSize(style)
        val resolved = glyphSource.resolveOperator(
            MathOperatorGlyphRequest(node.identity, style, node.commandRange),
            size,
        )
        val operatorFaceId = resolved.run.glyphs.firstOrNull()?.faceId ?: glyphSource.faceId
        val operatorMathFont = mathFontForFace(operatorFaceId)
        if (resolved.run.missingGlyph) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingGlyph,
                "The selected formula-wide math face has no LargeSymbols glyph for ${node.identity.debugName}",
                node.commandRange,
            )
        }

        val display = style.level == MathStyleLevel.Display
        val normalGlyphExtent = resolved.run.glyphs.maxOfOrNull { it.inkBounds.height } ?: 0f
        // XeTeX make_op uses the larger of DisplayOperatorMinHeight and 5/4 of the
        // normal native glyph's exact height+depth as the variant-selection target.
        val displayOperatorMinHeight = if (display) {
            operatorMathFont.scaleDesignUnits(operatorMathFont.constants.displayOperatorMinHeight, size)
        } else {
            0f
        }
        val normalGlyphFiveQuarters = if (display) normalGlyphExtent * 5f / 4f else 0f
        val targetHeight = max(displayOperatorMinHeight, normalGlyphFiveQuarters)
        val construction = if (display) {
            resolved.constructionBaseGlyphId?.let {
                selectVerticalConstruction(
                    baseGlyphId = it,
                    normalRun = resolved.run,
                    targetHeight = targetHeight,
                    size = size,
                    style = style,
                    range = node.commandRange,
                    assemblyPolicy = MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue,
                )
            }
        } else {
            null
        }
        val assemblyValidation = construction?.assemblyValidation
            ?: resolved.constructionBaseGlyphId?.let(operatorMathFont::verticalAssemblyValidation)
        val rawBox = if (construction != null) {
            operatorConstructionBox(construction, node, style, size, operatorFaceId)
        } else {
            measuredRunBox(resolved.run, node.commandRange, style, size)
        }
        val axisY = -operatorMathFont.scaleDesignUnits(operatorMathFont.constants.axisHeight, size)
        val inkCenterBefore = (rawBox.inkBounds.top + rawBox.inkBounds.bottom) / 2f
        val centerShift = axisY - inkCenterBefore
        val centeredPlacements = rawBox.glyphs.map { placement ->
            placement.copy(
                baselineY = placement.baselineY + centerShift,
                inkBounds = placement.inkBounds.translated(0f, centerShift),
            )
        }
        val box = geometryExtents(rawBox.width, centeredPlacements, rawBox.rules, node.range)
        val achievedAdvance = construction?.let {
            operatorMathFont.scaleDesignUnits(it.advanceMeasurement, size)
        } ?: rawBox.inkBounds.height
        // XeTeX exhausts the variant ladder and keeps the last available glyph when the
        // suggested target is not reached. Unlike radicals and delimiters, this is a complete
        // operator selection, not a missing rendering capability.
        val suggestedTargetReached = !display || achievedAdvance + GEOMETRY_EPSILON_PX >= targetHeight
        val exhaustedVariantLadder = display && !suggestedTargetReached

        val finalGlyphId = when (construction?.kind) {
            MathConstructionKind.BaseGlyph,
            MathConstructionKind.Variant ->
                construction.components.singleOrNull()?.glyphId
            MathConstructionKind.Assembly -> null
            null -> resolved.run.glyphs.lastOrNull()?.glyphId
        }
        val italicCorrectionSource = if (
            construction?.kind == MathConstructionKind.Assembly
        ) {
            "GlyphAssembly"
        } else if (finalGlyphId in operatorMathFont.italicCorrectionDeviceAdjustments) {
            "XeTeXHarfBuzzZeroPpemMathItalicsCorrection"
        } else {
            "MathItalicsCorrectionInfo"
        }
        val italicCorrection = construction?.assemblyItalicCorrection?.let {
            operatorMathFont.scaleDesignUnits(it, size)
        } ?: finalGlyphId?.let {
            operatorMathFont.italicCorrection(it, size)
        } ?: 0f
        decision(
            "TeXOperatorNoad",
            node.range,
            "sourceText" to node.sourceText,
            "commandRange" to node.commandRange,
            "identity" to node.identity.debugName,
            "atomClass" to node.atomClass,
            "family" to node.family,
            "baseScalar" to unicodeLabel(node.identity.baseScalar),
            "backendScalar" to unicodeLabel(resolved.backendScalar),
            "style" to style,
            "fontSizePx" to size,
            "constructionBaseGlyphId" to resolved.constructionBaseGlyphId,
            "glyphIds" to box.glyphs.joinToString(",") { it.glyphId.toString() },
            "construction" to (construction?.kind ?: "BaseGlyph"),
            "constructionPolicy" to when {
                exhaustedVariantLadder && construction != null ->
                    "XeTeXMakeOpLargestAvailableBelowSuggestedTarget"
                exhaustedVariantLadder -> "XeTeXMakeOpNormalGlyphAfterExhaustedVariantLadder"
                else -> construction?.constructionPolicy
            },
            "assemblyValid" to assemblyValidation?.valid,
            "assemblyInvalidReasons" to assemblyValidation?.invalidReasons,
            "assemblyValidationPolicy" to assemblyValidation?.validationPolicy,
            "assemblySpecificationDivergence" to assemblyValidation?.specificationDivergence,
            "assemblyCheckedConnectionCount" to assemblyValidation?.checkedConnectionCount,
            "displayOperatorMinHeightPx" to displayOperatorMinHeight,
            "normalGlyphExtentPx" to normalGlyphExtent,
            "normalGlyphFiveQuartersPx" to normalGlyphFiveQuarters,
            "variantSelectionTargetPx" to targetHeight,
            "variantSelectionTargetPolicy" to "XeTeXMakeOpMaxDisplayOperatorMinHeightAndFiveQuartersNormalGlyph",
            "achievedAdvancePx" to achievedAdvance,
            "reachesTarget" to suggestedTargetReached,
            "suggestedTargetReached" to suggestedTargetReached,
            "selectionComplete" to true,
            "exhaustedVariantLadder" to exhaustedVariantLadder,
            "axisY" to axisY,
            "inkCenterBefore" to inkCenterBefore,
            "centerShiftPx" to centerShift,
            "inkCenterAfter" to (box.inkBounds.top + box.inkBounds.bottom) / 2f,
            "italicCorrectionPx" to italicCorrection,
            "italicCorrectionSource" to italicCorrectionSource,
            "italicCorrectionIgnoredDeviceAdjustment" to
                finalGlyphId?.let(operatorMathFont.italicCorrectionDeviceAdjustments::get),
            "limitsPolicy" to node.limitsPolicy,
            "limitsPolicyExplicit" to node.hasExplicitLimitsPolicy,
            "limitsModifierRange" to node.limitsModifierRange,
        )
        return LaidNode(
            node = node,
            box = box,
            atomClass = MathAtomClass.Operator,
            italicCorrectionPx = italicCorrection,
            style = style,
            scriptBaseKind = if (
                (construction?.kind != null && construction.kind != MathConstructionKind.BaseGlyph) ||
                box.glyphs.singleOrNull()?.glyphId in operatorMathFont.extendedShapeGlyphs
            ) {
                ScriptBaseKind.ExtendedShape
            } else {
                ScriptBaseKind.Character
            },
        )
    }

    private fun measuredRunBox(
        run: MeasuredMathRun,
        range: SourceRange,
        style: MathStyle,
        size: Float,
    ): MathBox {
        val placements = run.glyphs.map { glyph ->
            MathGlyphPlacement(
                glyphId = glyph.glyphId,
                x = glyph.x,
                baselineY = glyph.baselineOffsetPx,
                advance = glyph.advance,
                inkBounds = glyph.inkBounds.translated(glyph.x, glyph.baselineOffsetPx),
                fontSizePx = size,
                sourceRange = range,
                style = style,
                faceId = glyph.faceId,
                fontClass = glyph.fontClass,
                requestedWeight = glyph.requestedWeight,
                resolvedWeight = glyph.resolvedWeight,
                fallbackReason = glyph.fallbackReason,
            )
        }
        return geometryExtents(run.width, placements, emptyList(), range)
    }

    /** One normal-glyph-first entry point shared by operators, radicals, and delimiters. */
    private fun selectVerticalConstruction(
        baseGlyphId: UShort,
        normalRun: MeasuredMathRun,
        targetHeight: Float,
        size: Float,
        style: MathStyle,
        range: SourceRange,
        assemblyPolicy: MathVerticalAssemblyPolicy = MathVerticalAssemblyPolicy.MathMLCoreUniformOverlap,
    ): MathVerticalConstruction? {
        val faceId = normalRun.glyphs.singleOrNull()?.faceId ?: glyphSource.faceId
        val mathFont = mathFontForFace(faceId)
        return mathFont.verticalConstruction(
            MathVerticalConstructionRequest(
                baseGlyphId = baseGlyphId,
                targetSizePx = targetHeight,
                fontSizePx = size,
                normalGlyphHeightPx = normalRun.glyphs.maxOfOrNull { it.inkBounds.height } ?: 0f,
                normalGlyphAdvanceWidthPx = normalRun.width,
                assemblyPolicy = assemblyPolicy,
            ),
            glyphVerticalExtentPx = { glyphId ->
                measureGlyphOutlineForFace(faceId, glyphId, size, style, range)
                    .glyphs.singleOrNull()?.inkBounds?.height
                    ?: measureGlyphForFace(faceId, glyphId, size, style, range)
                        .let { it.ascent + it.descent }
            },
        ) { glyphId ->
            measureGlyphForFace(faceId, glyphId, size, style, range).width
        }
    }

    private fun operatorConstructionBox(
        construction: MathVerticalConstruction,
        node: MathOperator,
        style: MathStyle,
        size: Float,
        faceId: MathFaceId,
    ): MathBox {
        val componentRuns = construction.components.map { component ->
            component to measureGlyphOutlineForFace(faceId, component.glyphId, size, style, node.commandRange)
        }
        val placed = placeVerticalConstruction(
            construction = construction,
            componentRuns = componentRuns,
            size = size,
            style = style,
            sourceRange = node.commandRange,
            centerComponentsHorizontally = false,
        )
        decision(
            "OpenTypeOperatorConstruction",
            node.range,
            "kind" to construction.kind,
            "componentGlyphIds" to construction.components.joinToString(",") { it.glyphId.toString() },
            "componentOffsetsDesignUnits" to construction.components.joinToString(",") { it.offset.toString() },
            "advanceMeasurementDesignUnits" to construction.advanceMeasurement,
            "extenderRepetitions" to construction.extenderRepetitions,
            "connectorOverlapsDesignUnits" to construction.connectorOverlaps,
            "assemblyItalicCorrectionDesignUnits" to construction.assemblyItalicCorrection,
            "constructionPolicy" to construction.constructionPolicy,
            "assemblyValid" to construction.assemblyValidation?.valid,
            "assemblyInvalidReasons" to construction.assemblyValidation?.invalidReasons,
            "assemblyValidationPolicy" to construction.assemblyValidation?.validationPolicy,
            "assemblySpecificationDivergence" to construction.assemblyValidation?.specificationDivergence,
            "assemblyCheckedConnectionCount" to construction.assemblyValidation?.checkedConnectionCount,
            "uniformConnectorOverlapDesignUnits" to construction.uniformConnectorOverlap,
            "componentHorizontalOriginsPx" to placed.componentHorizontalOriginsPx.joinToString(","),
            "componentBottomOriginsPx" to placed.componentBottomOriginsPx.joinToString(","),
            "componentBaselineOriginsPx" to placed.componentBaselineOriginsPx.joinToString(","),
            "placementOrigin" to placed.placementOrigin,
            "placementPolicy" to placed.placementPolicy,
        )
        return geometryExtents(placed.width, placed.glyphs, emptyList(), node.range)
    }

    /**
     * A vertical assembly keeps one font-space x origin for every part, as required by the
     * OpenType orthogonal alignment contract. MathML Core's bottom-to-top advance coordinate
     * is converted independently to each glyph baseline; no per-part LSB cancellation is
     * allowed. A ready-made variant retains normal baseline shaping.
     */
    private fun placeVerticalConstruction(
        construction: MathVerticalConstruction,
        componentRuns: List<Pair<MathGlyphComponent, MeasuredMathRun>>,
        componentOutlineEvidences: List<MathConstructionOutlineEvidence>? = null,
        size: Float,
        style: MathStyle,
        sourceRange: SourceRange,
        centerComponentsHorizontally: Boolean,
    ): PlacedVerticalConstruction {
        val width = construction.orthogonalAdvancePx
        val assembly = construction.kind == MathConstructionKind.Assembly
        val horizontalOrigins = mutableListOf<Float>()
        val bottomOrigins = mutableListOf<Float>()
        val baselineOrigins = mutableListOf<Float>()
        val topStrokeCandidates = mutableListOf<MathConstructionOutlineEvidence.Available>()
        val placements = componentRuns.flatMapIndexed { componentIndex, (component, run) ->
            val componentFont = run.glyphs.firstOrNull()?.faceId?.let(glyphSource::mathFontFor) ?: glyphSource.mathFont
            val componentBottomY = -componentFont.scaleDesignUnits(component.offset, size)
            if (assembly) bottomOrigins += componentBottomY
            val runOriginX = when {
                assembly -> 0f
                centerComponentsHorizontally -> (width - run.width) / 2f
                else -> 0f
            }
            val runBaselineY = if (assembly) {
                val glyph = run.glyphs.singleOrNull()
                if (glyph == null) 0f else componentBottomY - glyph.inkBounds.bottom
            } else {
                0f
            }
            val outlineEvidence = componentOutlineEvidences?.getOrNull(componentIndex)
            if (outlineEvidence is MathConstructionOutlineEvidence.Available) {
                topStrokeCandidates += outlineEvidence.copy(
                    topStroke = MathConstructionTopStroke(
                        topPx = outlineEvidence.topStroke.topPx + runBaselineY,
                        bottomPx = outlineEvidence.topStroke.bottomPx + runBaselineY,
                        rightPx = outlineEvidence.topStroke.rightPx + runOriginX,
                    ),
                )
            }
            run.glyphs.map { glyph ->
                val componentX = when {
                    assembly -> glyph.x
                    centerComponentsHorizontally -> (width - run.width) / 2f + glyph.x
                    else -> glyph.x
                }
                val baselineY = if (assembly) {
                    componentBottomY - glyph.inkBounds.bottom
                } else {
                    0f
                }
                horizontalOrigins += componentX
                baselineOrigins += baselineY
                MathGlyphPlacement(
                    glyphId = glyph.glyphId,
                    x = componentX,
                    baselineY = baselineY,
                    advance = glyph.advance,
                    inkBounds = glyph.inkBounds.translated(componentX, baselineY),
                    fontSizePx = size,
                    sourceRange = sourceRange,
                    style = style,
                    faceId = glyph.faceId,
                    fontClass = glyph.fontClass,
                    requestedWeight = glyph.requestedWeight,
                    resolvedWeight = glyph.resolvedWeight,
                    fallbackReason = glyph.fallbackReason,
                )
            }
        }
        val constructionInkTop = placements.minOfOrNull { it.inkBounds.top } ?: 0f
        val constructionInkBottom = placements.maxOfOrNull { it.inkBounds.bottom } ?: 0f
        val allOutlineEvidenceAvailable = componentOutlineEvidences != null &&
            componentOutlineEvidences.size == componentRuns.size &&
            componentOutlineEvidences.all { it is MathConstructionOutlineEvidence.Available }
        val topStrokeEvidence = if (allOutlineEvidenceAvailable) {
            topStrokeCandidates.minByOrNull { it.topStroke.topPx }
        } else {
            null
        }
        val outlineEvidenceFailure = componentOutlineEvidences
            ?.filterIsInstance<MathConstructionOutlineEvidence.Unavailable>()
            ?.firstOrNull()
            ?.reason
        return PlacedVerticalConstruction(
            width = width,
            glyphs = placements,
            boxAscentPx = (-constructionInkTop).coerceAtLeast(0f),
            boxDescentPx = constructionInkBottom.coerceAtLeast(0f),
            topStrokeEvidence = topStrokeEvidence,
            outlineEvidenceFailure = outlineEvidenceFailure,
            componentHorizontalOriginsPx = horizontalOrigins,
            componentBottomOriginsPx = bottomOrigins,
            componentBaselineOriginsPx = baselineOrigins,
            placementOrigin = if (assembly) "shared-font-x/bottom" else "normal-glyph-baseline",
            placementPolicy = if (assembly) {
                "MathMLCore5.3.1SharedFontOriginBottom"
            } else {
                "NormalGlyphShaping"
            },
        )
    }

    /** Uses the composable TeX box metric produced with the radicand; no content guessing. */
    private fun refineRadicalCleanBox(box: MathBox, node: MathRadical): MathBox {
        val clean = box.texCleanBoxMetrics
        // Replay exact outlines for the radical's painted radicand without deriving its TeX box
        // from that flattened union. The already completed clean metric remains authoritative.
        val paintedGlyphs = box.glyphs.map { placement ->
            if (mathFontForFaceOrNull(placement.faceId) == null) return@map placement
            val glyph = measureGlyphOutlineForFace(
                placement.faceId,
                placement.glyphId,
                placement.fontSizePx,
                placement.style,
                placement.sourceRange,
            ).glyphs.singleOrNull() ?: return@map placement
            placement.copy(inkBounds = glyph.inkBounds.translated(placement.x, placement.baselineY))
        }
        val painted = geometryExtents(
            box.width,
            paintedGlyphs,
            box.rules,
            box.range,
            box.constructionPaintGroups,
        )
        val refined = painted.copy(
            ascent = clean.ascent,
            descent = clean.descent,
            texCleanBoxMetrics = clean,
        )
        val exactOutlineBoundsAvailable =
            MathTeXCleanBoxEvidence.FontReportedGlyphBounds !in clean.evidence
        val cleanMinusPaintedInkAbove = clean.ascent - (-box.inkBounds.top).coerceAtLeast(0f)
        val cleanMinusPaintedInkBelow = clean.descent - box.inkBounds.bottom.coerceAtLeast(0f)
        decision(
            "TeXRadicalCleanBox",
            node.radicand.range,
            "policy" to clean.policy,
            "evidence" to clean.evidence,
            "exactGlyphOutlineBoundsAvailable" to exactOutlineBoundsAvailable,
            "logicalAdvanceBeforePx" to box.width,
            "logicalAdvanceAfterPx" to refined.width,
            "logicalAscentBeforePx" to box.ascent,
            "logicalDescentBeforePx" to box.descent,
            "inkTopBeforePx" to box.inkBounds.top,
            "inkBottomBeforePx" to box.inkBounds.bottom,
            "cleanAscentPx" to refined.ascent,
            "cleanDescentPx" to refined.descent,
            "cleanHeightPx" to refined.height,
            "inkTopAfterPx" to refined.inkBounds.top,
            "inkBottomAfterPx" to refined.inkBounds.bottom,
            "cleanMinusPaintedInkAbovePx" to cleanMinusPaintedInkAbove,
            "cleanMinusPaintedInkBelowPx" to cleanMinusPaintedInkBelow,
            "completedChildBoxMetricsPreserved" to
                (clean.policy == MathTeXCleanBoxPolicy.CompletedLayoutBox),
        )
        return refined
    }

    private fun layoutRadical(
        node: MathRadical,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val radicandStyle = style.cramped()
        val radicand = refineRadicalCleanBox(
            layoutNode(node.radicand, radicandStyle, alphabetOverride).box,
            node,
        )
        val degreeStyle = MathStyle.ScriptScript
        val degree = node.degree?.let { layoutNode(it, degreeStyle, alphabetOverride).box }
        val size = fontSize(style)
        data class RadicalFaceCandidate(
            val measurement: MeasuredOutlineConstructionRun,
            val faceId: MathFaceId,
            val mathFont: OpenTypeMathFont,
            val gapMin: Float,
            val ruleThickness: Float,
            val extraAscender: Float,
            val targetHeight: Float,
            val baseBox: MathBox,
            val construction: MathVerticalConstruction?,
        )
        val faceCandidates = constructionBaseCandidates(
            RADICAL_SIGN,
            size,
            node.commandRange,
        ).mapNotNull { measurement ->
            val run = measurement.run
            val glyph = run.glyphs.singleOrNull() ?: return@mapNotNull null
            if (run.missingGlyph) return@mapNotNull null
            val faceMathFont = mathFontForFace(glyph.faceId)
            val faceConstants = faceMathFont.constants
            val candidateGap = faceMathFont.scaleDesignUnits(
                if (style.level == MathStyleLevel.Display) {
                    faceConstants.radicalDisplayStyleVerticalGap
                } else {
                    faceConstants.radicalVerticalGap
                },
                size,
            )
            val candidateRule = faceMathFont.scaleDesignUnits(faceConstants.radicalRuleThickness, size)
            val candidateExtra = faceMathFont.scaleDesignUnits(faceConstants.radicalExtraAscender, size)
            val candidateTarget = radicand.height + candidateGap + candidateRule
            val candidateBox = measuredRunBox(run, node.commandRange, style, size)
            RadicalFaceCandidate(
                measurement,
                glyph.faceId,
                faceMathFont,
                candidateGap,
                candidateRule,
                candidateExtra,
                candidateTarget,
                candidateBox,
                selectVerticalConstruction(
                    baseGlyphId = glyph.glyphId,
                    normalRun = run,
                    targetHeight = candidateTarget,
                    size = size,
                    style = style,
                    range = node.commandRange,
                    assemblyPolicy = MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue,
                ),
            )
        }
        val selectedFace = faceCandidates.firstOrNull { it.construction?.reachesTarget == true }
            ?: faceCandidates.firstOrNull()
        val baseMeasurement = selectedFace?.measurement
            ?: glyphSource.shapeOutlineConstructionBase(RADICAL_SIGN, size, node.commandRange)
        val baseRun = baseMeasurement.run
        val constructionFaceId = selectedFace?.faceId
            ?: baseRun.glyphs.singleOrNull()?.faceId
            ?: glyphSource.faceId
        val constructionMathFont = selectedFace?.mathFont ?: mathFontForFace(constructionFaceId)
        val constructionConstants = constructionMathFont.constants
        val baseGlyphId = baseRun.glyphs.singleOrNull()?.glyphId
        if (baseRun.missingGlyph || baseGlyphId == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingGlyph,
                "The selected formula-wide math face has no radical sign glyph",
                node.commandRange,
            )
        }

        val gapMin = selectedFace?.gapMin ?: constructionMathFont.scaleDesignUnits(
            if (style.level == MathStyleLevel.Display) constructionConstants.radicalDisplayStyleVerticalGap
            else constructionConstants.radicalVerticalGap,
            size,
        )
        val ruleThickness = selectedFace?.ruleThickness
            ?: constructionMathFont.scaleDesignUnits(constructionConstants.radicalRuleThickness, size)
        val extraAscender = selectedFace?.extraAscender
            ?: constructionMathFont.scaleDesignUnits(constructionConstants.radicalExtraAscender, size)
        // XeTeX make_radical selects the delimiter from clean_box height + depth. A leaf native
        // math glyph contributes its exact glyph bbox to that box, while a compound nucleus
        // contributes the already-completed logical box (including an inner radical's reserve).
        // The painted subtree ink union is deliberately not a substitute for clean_box.
        val targetHeight = selectedFace?.targetHeight ?: radicand.height + gapMin + ruleThickness
        val baseRadical = selectedFace?.baseBox ?: measuredRunBox(baseRun, node.commandRange, style, size)
        val baseGlyphHeight = baseRadical.inkBounds.height
        val construction = selectedFace?.construction ?: baseGlyphId?.let {
            selectVerticalConstruction(it, baseRun, targetHeight, size, style, node.commandRange,
                MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue)
        }
        val baseGlyphCoversTarget = construction?.kind == MathConstructionKind.BaseGlyph
        val constructionMeasurements = construction?.components?.map { component ->
            component to measureConstructionGlyphForFace(
                constructionFaceId,
                component.glyphId,
                size,
                style,
                node.commandRange,
            )
        }
        val constructionRuns = constructionMeasurements?.map { (component, measurement) ->
            component to measurement.run
        }
        val placedConstruction = if (construction == null || constructionRuns == null) {
            null
        } else {
            placeVerticalConstruction(
                construction = construction,
                componentRuns = constructionRuns,
                componentOutlineEvidences = constructionMeasurements.map { it.second.evidence },
                size = size,
                style = style,
                sourceRange = node.commandRange,
                centerComponentsHorizontally = false,
            )
        }
        val assemblyValidation = construction?.assemblyValidation
            ?: baseGlyphId?.let(constructionMathFont::verticalAssemblyValidation)
        val assemblyTable = baseGlyphId?.let {
            constructionMathFont.verticalConstructions[it]?.assembly
        }
        val achievedAdvance = construction?.let {
            constructionMathFont.scaleDesignUnits(it.advanceMeasurement, size)
        } ?: baseGlyphHeight
        val constructionExcess = (achievedAdvance - targetHeight).coerceAtLeast(0f)
        val actualClearance = gapMin + constructionExcess / 2f
        val rawRadical = if (placedConstruction == null) {
            baseRadical
        } else {
            geometryExtents(
                placedConstruction.width,
                placedConstruction.glyphs,
                emptyList(),
                node.commandRange,
            )
        }
        val radicalGlyphAscent = when (construction?.kind) {
            // GlyphAssembly advance is the nominal stretch/selection extent. Its actual box
            // ascent comes from the union of the same outlines that the renderer paints and can
            // protrude beyond that nominal extent. Selection advance and paint bounds stay
            // separate; the latter anchors the radical's top stroke to the rule top.
            MathConstructionKind.Assembly -> placedConstruction!!.boxAscentPx
            MathConstructionKind.BaseGlyph,
            MathConstructionKind.Variant -> constructionRuns!!.single().second.ascent
            null -> baseRun.ascent
        }
        val radicalGlyphDescent = when (construction?.kind) {
            MathConstructionKind.Assembly -> placedConstruction!!.boxDescentPx
            MathConstructionKind.BaseGlyph,
            MathConstructionKind.Variant -> constructionRuns!!.single().second.descent
            null -> baseRun.descent
        }
        val radicalGlyphBlockSize = radicalGlyphAscent + radicalGlyphDescent
        val radicalBoundsSources = when (construction?.kind) {
            MathConstructionKind.Assembly -> constructionRuns.orEmpty().map { it.second.boundsSource }.distinct()
            MathConstructionKind.BaseGlyph,
            MathConstructionKind.Variant -> listOf(constructionRuns!!.single().second.boundsSource)
            null -> listOf(baseRun.boundsSource)
        }
        val allRadicalBoundsAreOutline = radicalBoundsSources.isNotEmpty() &&
            radicalBoundsSources.all { it == MathGlyphBoundsSource.Outline }
        val radicalLogicalAdvancePolicy = if (construction?.kind == MathConstructionKind.Assembly) {
            "MathAssemblyOrthogonalAdvanceAllPartRecords"
        } else {
            "MeasuredMathRunLogicalWidthIndependentOfBoundsSource"
        }
        val topStrokeEvidence = if (construction == null) {
            baseMeasurement.evidence as? MathConstructionOutlineEvidence.Available
        } else {
            placedConstruction?.topStrokeEvidence
        }
        val outlineEvidenceFailure = if (construction == null) {
            (baseMeasurement.evidence as? MathConstructionOutlineEvidence.Unavailable)?.reason
        } else {
            placedConstruction?.outlineEvidenceFailure
        }
        val outlineEvidenceAvailable = topStrokeEvidence != null
        val topStroke = topStrokeEvidence?.topStroke
        val constructionLabel = construction?.kind?.toString() ?: "Unavailable"
        val selectionStep = when (construction?.kind) {
            MathConstructionKind.BaseGlyph -> "NormalGlyphHeight"
            MathConstructionKind.Variant -> "MathGlyphVariantRecord"
            MathConstructionKind.Assembly -> "GlyphAssembly"
            null -> "NoCoveringConstruction"
        }
        if (!baseGlyphCoversTarget && construction == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingMathConstruction,
                "The radical sign has no MATH construction covering ${targetHeight}px",
                node.commandRange,
            )
        } else if (!construction.reachesTarget) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MathVariantTooShort,
                "The radical MATH construction does not cover the required radicand height",
                node.commandRange,
                DiagnosticSeverity.Warning,
            )
        }
        if (!outlineEvidenceAvailable) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingConstructionOutlineEvidence,
                "The math font adapter cannot provide replayable radical top-stroke outline evidence: " +
                    outlineEvidenceFailure,
                node.commandRange,
            )
        }

        // XeTeX make_radical adds half of the selected delimiter's positive nominal excess to
        // the minimum clearance, then shifts the delimiter box by -(clean height + clearance).
        // Outline top-stroke evidence remains a paint-only anchor below.
        val ruleBottomInB = -radicand.ascent - actualClearance
        val ruleTopInB = ruleBottomInB - ruleThickness
        val radicalTopStrokeTopPx = topStroke?.topPx ?: -radicalGlyphAscent
        val radicalTopStrokeBottomPx = topStroke?.bottomPx ?: (-radicalGlyphAscent + ruleThickness)
        val radicalTopStrokeRightPx = topStroke?.rightPx ?: rawRadical.width
        val radicalBaselineInB = ruleTopInB - radicalTopStrokeTopPx
        val radicalInkTopInB = radicalBaselineInB + rawRadical.inkBounds.top
        // Tectonic 0.17.0/XeTeX rewrites the OpenType delimiter box to height=rule and
        // depth=nominalAdvance-rule, shifts it by ruleBottom, and builds overbar as
        // kern(rule), rule(rule), kern(clearance), cleanBox. This is the TeX logical box;
        // painted outline overhang remains solely in MathBox visual bounds.
        val texDelimiterBoxHeight = ruleThickness
        val texDelimiterBoxDepth = (achievedAdvance - ruleThickness).coerceAtLeast(0f)
        val texDelimiterBoxShift = ruleBottomInB
        val texDelimiterAscentInB = (texDelimiterBoxHeight - texDelimiterBoxShift).coerceAtLeast(0f)
        val texDelimiterDescentInB = (texDelimiterBoxDepth + texDelimiterBoxShift).coerceAtLeast(0f)
        val overbarLeadingReserve = ruleThickness
        val overbarAscentInB = radicand.ascent + actualClearance + ruleThickness + overbarLeadingReserve
        val unindexedAscent = max(overbarAscentInB, texDelimiterAscentInB)
        val unindexedDescent = max(radicand.descent, texDelimiterDescentInB).coerceAtLeast(0f)
        val actualGap = -radicand.ascent - ruleBottomInB
        val radicandXInB = rawRadical.width
        val radicalInB = rawRadical.translated(0f, radicalBaselineInB)
        val radicandInB = radicand.translated(radicandXInB, 0f)
        val constructionPaintGroup = MathConstructionPaintGroup(
            id = nextConstructionPaintGroupId++,
            kind = MathConstructionPaintKind.Radical,
            shapeKind = when (construction?.kind) {
                MathConstructionKind.Assembly -> MathConstructionShapeKind.Assembly
                MathConstructionKind.Variant -> MathConstructionShapeKind.Variant
                MathConstructionKind.BaseGlyph, null -> MathConstructionShapeKind.BaseGlyph
            },
            sourceRange = node.commandRange,
            outlinePolicy = MathConstructionOutlinePolicy.RequireOutlineUnion,
            faceId = constructionFaceId,
        )
        val groupedRadicalInB = radicalInB.copy(
            glyphs = radicalInB.glyphs.map {
                it.copy(constructionGroupId = constructionPaintGroup.id)
            },
            constructionPaintGroups = radicalInB.constructionPaintGroups + constructionPaintGroup,
        )
        val ruleInB = MathRulePlacement(
            left = radicalTopStrokeRightPx,
            top = ruleTopInB,
            right = radicandXInB + radicand.width,
            bottom = ruleBottomInB,
            sourceRange = node.commandRange,
            constructionGroupId = constructionPaintGroup.id,
        )
        val unindexedGeometry = geometryExtents(
            width = radicandXInB + radicand.width,
            glyphs = groupedRadicalInB.glyphs + radicandInB.glyphs,
            rules = groupedRadicalInB.rules + radicandInB.rules + ruleInB,
            range = node.range,
            constructionPaintGroups =
                groupedRadicalInB.constructionPaintGroups + radicandInB.constructionPaintGroups,
        )
        val unindexedBox = unindexedGeometry.copy(
            ascent = unindexedAscent,
            descent = unindexedDescent,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = unindexedAscent,
                descent = unindexedDescent,
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = unindexedGeometry.texCleanBoxMetrics.evidence +
                    MathTeXCleanBoxEvidence.CompletedChildBox,
            ),
        )

        // unicode-math's XeTeX root wrapper supplies signed MATH kerns and raises the degree by
        // (height(B) - depth(B)) * RadicalDegreeBottomRaisePercent. Horizontal TeX clamping is
        // independent and remains unchanged.
        val kernBeforeDegree = if (degree == null) 0f else constructionMathFont.scaleDesignUnits(
            constructionConstants.radicalKernBeforeDegree,
            size,
        )
        val kernAfterDegree = if (degree == null) 0f else constructionMathFont.scaleDesignUnits(
            constructionConstants.radicalKernAfterDegree,
            size,
        )
        val degreeHorizontalPlacement = degree?.let {
            resolveRadicalDegreeHorizontalPlacement(
                degreeWidthPx = it.width,
                kernBeforeDegreePx = kernBeforeDegree,
                kernAfterDegreePx = kernAfterDegree,
            )
        }
        val adjustedKernAfterDegree = degreeHorizontalPlacement?.adjustedKernAfterDegreePx ?: 0f
        val degreeX = degreeHorizontalPlacement?.degreeX
        val unindexedX = degreeHorizontalPlacement?.radicalX ?: 0f
        val logicalWidth = unindexedX + unindexedBox.width
        val degreeRaisePercent = constructionConstants.radicalDegreeBottomRaisePercent
        val degreeRaiseReferencePx = if (degree == null) null else unindexedBox.ascent - unindexedBox.descent
        val degreeRaisePx = if (degree == null) {
            null
        } else {
            degreeRaiseReferencePx!! * degreeRaisePercent / 100f
        }
        val degreeBaselineY = if (degree == null) {
            null
        } else {
            -degreeRaisePx!!
        }
        val shiftedUnindexed = unindexedBox.translated(unindexedX, 0f)
        val shiftedDegree = degree?.translated(degreeX!!, degreeBaselineY!!)
        val shiftedRadical = groupedRadicalInB.translated(unindexedX, 0f)
        val shiftedRadicand = radicandInB.translated(unindexedX, 0f)
        val coversRadicandBottom =
            shiftedRadical.inkBounds.bottom + GEOMETRY_EPSILON_PX >= shiftedRadicand.inkBounds.bottom
        if (!coversRadicandBottom) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MathVariantTooShort,
                "The radical construction does not visually cover the radicand depth",
                node.commandRange,
                DiagnosticSeverity.Warning,
            )
        }
        val inkBox = geometryExtentsPreservingLogicalChildren(
            width = logicalWidth,
            glyphs = shiftedUnindexed.glyphs + shiftedDegree?.glyphs.orEmpty(),
            rules = shiftedUnindexed.rules + shiftedDegree?.rules.orEmpty(),
            range = node.range,
            children = buildList {
                add(unindexedBox to 0f)
                degree?.let { add(it to degreeBaselineY!!) }
            },
        )
        val box = inkBox
        val rule = shiftedUnindexed.rules.last()
        val radicalX = unindexedX
        val radicandX = unindexedX + radicandXInB
        val degreeLogicalBottomY = if (degree == null) null else degreeBaselineY!! + degree.descent
        val degreeInkBottomY = shiftedDegree?.inkBounds?.bottom

        decision(
            "TeXRadicalNoad",
            node.range,
            "sourceText" to node.sourceText,
            "commandRange" to node.commandRange,
            "degreeRange" to node.degreeRange,
            "radicandRange" to node.radicand.range,
            "atomClass" to node.atomClass,
            "style" to style,
            "radicandStyle" to radicandStyle,
            "degreeStyle" to if (degree == null) null else degreeStyle,
            "scriptBaseKind" to ScriptBaseKind.CompoundBox,
            "italicCorrectionPx" to 0f,
        )
        decision(
            "OpenTypeRadicalConstruction",
            node.commandRange,
            "baseGlyphId" to baseGlyphId,
            "constructionFaceId" to constructionFaceId,
            "fontClass" to baseRun.glyphs.firstOrNull()?.fontClass,
            "requestedWeight" to baseRun.glyphs.firstOrNull()?.requestedWeight,
            "resolvedWeight" to baseRun.glyphs.firstOrNull()?.resolvedWeight,
            "fallbackReason" to baseRun.glyphs.firstOrNull()?.fallbackReason,
            "construction" to constructionLabel,
            "componentGlyphIds" to construction?.components?.joinToString(",") { it.glyphId.toString() },
            "componentOffsetsDesignUnits" to construction?.components?.joinToString(",") { it.offset.toString() },
            "connectorOverlapsDesignUnits" to construction?.connectorOverlaps,
            "extenderRepetitions" to construction?.extenderRepetitions,
            "assemblyMinimumConnectorOverlapDesignUnits" to assemblyTable?.minimumConnectorOverlap,
            "assemblySourcePartRecords" to assemblyTable?.parts?.joinToString(";") {
                "${it.glyphId}:${it.startConnectorLength}:${it.endConnectorLength}:" +
                    "${it.fullAdvance}:${it.extender}"
            },
            "constructionPolicy" to (construction?.constructionPolicy ?: if (assemblyValidation?.valid == false) {
                "MathMLCore5.3.2FailureAfterInvalidAssembly"
            } else null),
            "assemblyValid" to assemblyValidation?.valid,
            "assemblyInvalidReasons" to assemblyValidation?.invalidReasons,
            "assemblyValidationPolicy" to assemblyValidation?.validationPolicy,
            "assemblySpecificationDivergence" to assemblyValidation?.specificationDivergence,
            "assemblyCheckedConnectionCount" to assemblyValidation?.checkedConnectionCount,
            "uniformConnectorOverlapDesignUnits" to construction?.uniformConnectorOverlap,
            "assemblyNaturalAdvanceDesignUnits" to construction?.assemblyNaturalAdvance,
            "assemblyStretchCapacityDesignUnits" to construction?.assemblyStretchCapacity,
            "assemblyAppliedStretchDesignUnits" to construction?.assemblyAppliedStretch,
            "assemblyGlyphExtentsDesignUnits" to construction?.assemblyGlyphExtents,
            "assemblyMaximumConnectorOverlapsDesignUnits" to
                construction?.assemblyMaximumConnectorOverlaps,
            "assemblyMinimumConnectorOverlapsDesignUnits" to
                construction?.assemblyMinimumConnectorOverlaps,
            "orthogonalAdvancePx" to construction?.orthogonalAdvancePx,
            "selectionStep" to selectionStep,
            "selectionPolicy" to "MathMLCore5.3.2NormalGlyphFirst",
            "targetMetric" to "TeXCleanBoxHeightPlusGapAndRule",
            "cleanBoxPolicy" to "XeTeXMakeRadicalCleanBoxCrampedStyle",
            "cleanRadicandAscentPx" to radicand.ascent,
            "cleanRadicandDescentPx" to radicand.descent,
            "cleanRadicandHeightPx" to radicand.height,
            "radicandInkHeightPx" to radicand.inkBounds.height,
            "radicandLogicalHeightPx" to radicand.height,
            "baseGlyphHeightPx" to baseGlyphHeight,
            "baseGlyphBoundsSource" to baseRun.boundsSource,
            "componentBoundsSources" to constructionRuns?.map { it.second.boundsSource }?.distinct(),
            "baseOutlineEvidence" to baseMeasurement.evidence.evidenceLabel(),
            "componentOutlineEvidences" to constructionMeasurements?.map { it.second.evidence.evidenceLabel() },
            "baseGlyphCoversTarget" to baseGlyphCoversTarget,
            "targetHeightPx" to targetHeight,
            "achievedAdvancePx" to achievedAdvance,
            "reachesTarget" to (achievedAdvance + GEOMETRY_EPSILON_PX >= targetHeight),
            "constructionBoxAscentPx" to placedConstruction?.boxAscentPx,
            "constructionBoxDescentPx" to placedConstruction?.boxDescentPx,
            "constructionBoxHeightPx" to placedConstruction?.let { it.boxAscentPx + it.boxDescentPx },
            "constructionExtentPolicy" to if (construction?.kind == MathConstructionKind.Assembly) {
                if (allRadicalBoundsAreOutline) {
                    "NominalAdvanceForSelectionActualPlacedOutlineBoundsForBox"
                } else {
                    "NominalAdvanceForSelectionReportedBoundsFallbackForBox"
                }
            } else {
                if (allRadicalBoundsAreOutline) "ShapedOutlineBounds" else "ShapedReportedBoundsFallback"
            },
            "componentBottomOriginsPx" to placedConstruction?.componentBottomOriginsPx?.joinToString(","),
            "componentBaselineOriginsPx" to placedConstruction?.componentBaselineOriginsPx?.joinToString(","),
            "componentHorizontalOriginsPx" to placedConstruction?.componentHorizontalOriginsPx?.joinToString(","),
            "placementOrigin" to (placedConstruction?.placementOrigin ?: "normal-glyph-baseline"),
            "placementPolicy" to (placedConstruction?.placementPolicy ?: "NormalGlyphShaping"),
        )
        decision(
            "OpenTypeMathRadical",
            node.range,
            "style" to style,
            "radicandStyle" to radicandStyle,
            "degreeStyle" to if (degree == null) null else degreeStyle,
            "unindexedBoxPolicy" to "XeTeXMakeRadicalCleanBoxNominalDelimiterAndOverbar",
            "degreePlacementPolicy" to if (degree == null) {
                null
            } else {
                "UnicodeMathXeTeXRootHeightMinusDepthRaise"
            },
            "degreePlacementSpecificationDivergence" to if (degree == null) {
                null
            } else {
                "unicode-math-xetex-r@@t;NotLuaTeXOrMathMLBlockSizeMapping"
            },
            "radicalVerticalGapPx" to gapMin,
            "minimumRadicalGapPx" to gapMin,
            "constructionExcessPx" to constructionExcess,
            "constructionExcessMetric" to "SelectedOpenTypeConstructionAdvanceMinusStretchTarget",
            "clearancePolicy" to "MinimumGapPlusHalfPositiveConstructionExcess",
            "clearanceSpecificationDivergence" to
                "TeXMakeRadicalClearanceWithOpenTypeConstructionAdvance;NotMathMLCore3.3.3.2Clearance",
            "actualRadicalGapPx" to actualGap,
            "radicalRuleThicknessPx" to ruleThickness,
            "radicalExtraAscenderPx" to extraAscender,
            "radicalExtraAscenderUsed" to false,
            "overbarLeadingReservePx" to overbarLeadingReserve,
            "overbarLeadingReservePolicy" to "XeTeXOverbarLeadingRuleThickness",
            "overbarLeadingReserveSpecificationDivergence" to
                "Tectonic0.17.0DoesNotConsumeOpenTypeMATH.RadicalExtraAscender",
            "radicalKernBeforeDegreePx" to kernBeforeDegree,
            "radicalKernAfterDegreePx" to kernAfterDegree,
            "usedRadicalKernBeforeDegreePx" to degreeHorizontalPlacement?.rawKernBeforeDegreePx,
            "radicalDegreeAfterKernClampLowerBoundPx" to
                degreeHorizontalPlacement?.afterKernClampLowerBoundPx,
            "adjustedRadicalKernAfterDegreePx" to adjustedKernAfterDegree,
            "degreeHorizontalPlacementPolicy" to if (degree == null) {
                null
            } else {
                "TeXMakeRadicalSignedBeforeAndWidthPlusBeforeAfterClamp"
            },
            "radicalDegreeBottomRaisePercent" to degreeRaisePercent,
            "degreeRaiseReferencePx" to degreeRaiseReferencePx,
            "degreeRaiseReferenceAscentPx" to if (degree == null) null else unindexedBox.ascent,
            "degreeRaiseReferenceDescentPx" to if (degree == null) null else unindexedBox.descent,
            "degreeRaiseReferenceMetric" to if (degree == null) {
                null
            } else {
                "UnindexedRadicalBoxHeightMinusDepth"
            },
            "degreeRaiseReferencePolicy" to if (degree == null) {
                null
            } else {
                "unicode-math-xetex-r@@t-times-OpenTypeMATH.RadicalDegreeBottomRaisePercent"
            },
            "radicalGlyphAscentPx" to radicalGlyphAscent,
            "radicalGlyphDescentPx" to radicalGlyphDescent,
            "radicalGlyphBlockSizePx" to radicalGlyphBlockSize,
            "radicalGlyphBoxMetricSource" to if (construction?.kind == MathConstructionKind.Assembly) {
                if (allRadicalBoundsAreOutline) "PlacedAssemblyOutlineBounds" else "PlacedAssemblyReportedBoundsFallback"
            } else {
                if (allRadicalBoundsAreOutline) {
                    "ShapedConstructionOutlineBounds"
                } else {
                    "ShapedConstructionReportedBoundsFallback"
                }
            },
            "radicalGlyphBoundsSources" to radicalBoundsSources,
            "radicalBoxAdvancePx" to rawRadical.width,
            "radicalLogicalAdvancePolicy" to radicalLogicalAdvancePolicy,
            "radicalPaintOriginY" to radicalBaselineInB,
            "radicalTopStrokeEvidence" to (topStrokeEvidence?.evidenceLabel()
                ?: "Unavailable($outlineEvidenceFailure)"),
            "radicalTopStrokeEvidenceSource" to topStrokeEvidence?.source,
            "radicalTopStrokeEvidenceFailure" to outlineEvidenceFailure,
            "radicalTopStrokeTopPx" to radicalTopStrokeTopPx,
            "radicalTopStrokeBottomPx" to radicalTopStrokeBottomPx,
            "radicalTopStrokeRightPx" to radicalTopStrokeRightPx,
            "overbarAnchorPolicy" to if (outlineEvidenceAvailable) {
                "FontAdapterTopStrokeTopAndRight"
            } else if (allRadicalBoundsAreOutline) {
                "SelectedConstructionOutlineBoundsAndLogicalAdvanceFallback"
            } else {
                "ReportedBoundsAndLogicalAdvanceFallback"
            },
            "overbarThicknessSource" to "OpenTypeMATH.RadicalRuleThickness",
            "overbarLeftPolicy" to if (outlineEvidenceAvailable) {
                "FontAdapterTopStrokeRight"
            } else {
                "RadicalBoxAdvanceFallback"
            },
            "targetHeightPx" to targetHeight,
            "achievedAdvancePx" to achievedAdvance,
            "texDelimiterBoxHeightPx" to texDelimiterBoxHeight,
            "texDelimiterBoxDepthPx" to texDelimiterBoxDepth,
            "texDelimiterBoxShiftPx" to texDelimiterBoxShift,
            "texDelimiterContributedAscentPx" to texDelimiterAscentInB,
            "texDelimiterContributedDescentPx" to texDelimiterDescentInB,
            "radicandAscentPx" to radicand.ascent,
            "radicandDescentPx" to radicand.descent,
            "unindexedAscentPx" to unindexedBox.ascent,
            "unindexedDescentPx" to unindexedBox.descent,
            "unindexedBlockSizePx" to unindexedBox.height,
            "unindexedX" to unindexedX,
            "degreeWidthPx" to degree?.width,
            "degreeAscentPx" to degree?.ascent,
            "degreeDescentPx" to degree?.descent,
            "degreeRaisePx" to degreeRaisePx,
            "degreeBaselineY" to degreeBaselineY,
            "degreeLogicalBottomY" to degreeLogicalBottomY,
            "degreeInkBottomY" to degreeInkBottomY,
            "degreeX" to degreeX,
            "radicalX" to radicalX,
            "radicandX" to radicandX,
            "radicandWidthPx" to radicand.width,
            "radicalInkTopPx" to shiftedRadical.inkBounds.top,
            "radicalInkBottomPx" to shiftedRadical.inkBounds.bottom,
            "radicandInkTopPx" to shiftedRadicand.inkBounds.top,
            "radicandInkBottomPx" to shiftedRadicand.inkBounds.bottom,
            "coversRadicandBottom" to coversRadicandBottom,
            "ruleLeft" to rule.left,
            "ruleTop" to rule.top,
            "ruleRight" to rule.right,
            "ruleBottom" to rule.bottom,
            "logicalWidthPx" to logicalWidth,
            "visualLeftPx" to box.visualLeft,
            "visualRightPx" to box.visualRight,
            "reservedTopPx" to -unindexedBox.ascent,
        )
        return LaidNode(
            node = node,
            box = box,
            atomClass = MathAtomClass.Ordinary,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutOperatorScripts(
        node: MathScripts,
        operator: MathOperator,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val base = layoutOperator(operator, style, alphabetOverride)
        return layoutScriptsWithOperatorLimits(
            node = node,
            base = base,
            semantics = OperatorLimitsSemantics(
                identity = operator.identity.debugName,
                declaredPolicy = operator.limitsPolicy,
                explicit = operator.hasExplicitLimitsPolicy,
                modifierRange = operator.limitsModifierRange,
                sideScriptHorizontalPolicy = SideScriptHorizontalPolicy.XeTeXOperatorNoLimits,
                sideScriptGeometry = "XeTeXMakeOpWidthDeltaPlusSharedSideScriptKernel",
            ),
            style = style,
            alphabetOverride = alphabetOverride,
        )
    }

    private fun layoutOperatorNameScripts(
        node: MathScripts,
        operator: MathOperatorName,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode = layoutScriptsWithOperatorLimits(
        node = node,
        base = layoutOperatorName(operator, style, alphabetOverride),
        semantics = OperatorLimitsSemantics(
            identity = "operator-name:${operator.name}",
            declaredPolicy = operator.limitsPolicy,
            explicit = operator.hasExplicitLimitsPolicy,
            modifierRange = operator.limitsModifierRange,
            sideScriptHorizontalPolicy = SideScriptHorizontalPolicy.OrdinaryNucleus,
            sideScriptGeometry = "UprightOperatorNamePlusSharedSideScriptKernel",
        ),
        style = style,
        alphabetOverride = alphabetOverride,
    )

    private fun layoutOperatorNoadScripts(
        node: MathScripts,
        operator: MathOperatorNoad,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode = layoutScriptsWithOperatorLimits(
        node = node,
        base = layoutOperatorNoad(operator, style, alphabetOverride),
        semantics = OperatorLimitsSemantics(
            identity = "mathop",
            declaredPolicy = operator.limitsPolicy,
            explicit = operator.hasExplicitLimitsPolicy,
            modifierRange = operator.limitsModifierRange,
            sideScriptHorizontalPolicy = SideScriptHorizontalPolicy.OrdinaryNucleus,
            sideScriptGeometry = "XeTeXSubMlistOperatorPlusSharedSideScriptKernel",
        ),
        style = style,
        alphabetOverride = alphabetOverride,
    )

    private fun layoutBraceNoadScripts(
        node: MathScripts,
        brace: MathBraceNoad,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode = layoutScriptsWithOperatorLimits(
        node = node,
        base = layoutBraceNoad(brace, style, alphabetOverride),
        semantics = OperatorLimitsSemantics(
            identity = "${brace.kind.name.lowercase()}brace",
            declaredPolicy = brace.limitsPolicy,
            explicit = brace.hasExplicitLimitsPolicy,
            modifierRange = brace.limitsModifierRange,
            sideScriptHorizontalPolicy = SideScriptHorizontalPolicy.OrdinaryNucleus,
            sideScriptGeometry = "XeTeXBraceAccentOperatorPlusSharedSideScriptKernel",
        ),
        style = style,
        alphabetOverride = alphabetOverride,
    )

    private fun layoutScriptsWithOperatorLimits(
        node: MathScripts,
        base: LaidNode,
        semantics: OperatorLimitsSemantics,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val effectivePolicy = when (semantics.declaredPolicy) {
            MathLimitsPolicy.Limits -> MathLimitsPolicy.Limits
            MathLimitsPolicy.NoLimits -> MathLimitsPolicy.NoLimits
            MathLimitsPolicy.Auto -> if (style.level == MathStyleLevel.Display) {
                MathLimitsPolicy.Limits
            } else {
                MathLimitsPolicy.NoLimits
            }
        }
        val reason = when {
            semantics.explicit -> "explicit-postfix-modifier"
            semantics.declaredPolicy == MathLimitsPolicy.Auto && style.level == MathStyleLevel.Display -> "auto-display"
            semantics.declaredPolicy == MathLimitsPolicy.Auto -> "auto-non-display"
            else -> "plain-tex-operator-default"
        }
        decision(
            "TeXOperatorLimitsPolicy",
            node.range,
            "identity" to semantics.identity,
            "declaredPolicy" to semantics.declaredPolicy,
            "effectivePolicy" to effectivePolicy,
            "explicit" to semantics.explicit,
            "modifierRange" to semantics.modifierRange,
            "style" to style,
            "reason" to reason,
            "upperPresent" to (node.superscript != null),
            "lowerPresent" to (node.subscript != null),
        )
        return if (effectivePolicy == MathLimitsPolicy.Limits) {
            layoutStackedOperatorLimits(node, base, style, alphabetOverride)
        } else {
            decision(
                "TeXOperatorSideScripts",
                node.range,
                "identity" to semantics.identity,
                "style" to style,
                "geometry" to semantics.sideScriptGeometry,
                "italicCorrectionDeltaPx" to base.italicCorrectionPx,
                "subscriptPresent" to (node.subscript != null),
                "makeOpWidthReductionPx" to if (node.subscript != null) base.italicCorrectionPx else 0f,
            )
            layoutScriptsWithBase(
                node,
                base,
                style,
                alphabetOverride,
                semantics.sideScriptHorizontalPolicy,
            )
        }
    }

    private fun layoutStackedOperatorLimits(
        node: MathScripts,
        rawBase: LaidNode,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val base = rawBase.copy(box = rawBase.box.completedTeXBox())
        val upper = node.superscript
            ?.let { layoutNode(it, style.superscript(), alphabetOverride) }
            ?.completedTeXMathField()
        val lower = node.subscript
            ?.let { layoutNode(it, style.subscript(), alphabetOverride) }
            ?.completedTeXMathField()
        val placement = placeStackedLimits(base, upper, lower, style, base.italicCorrectionPx, node.range)
        decision(
            "OpenTypeMathOperatorLimits",
            node.range,
            "style" to style,
            "upperStyle" to upper?.style,
            "lowerStyle" to lower?.style,
            "upperLimitGapMinPx" to placement.upperGapMin,
            "upperLimitBaselineRiseMinPx" to placement.upperBaselineRiseMin,
            "lowerLimitGapMinPx" to placement.lowerGapMin,
            "lowerLimitBaselineDropMinPx" to placement.lowerBaselineDropMin,
            "upperOuterPaddingPx" to placement.upperOuterPadding,
            "lowerOuterPaddingPx" to placement.lowerOuterPadding,
            "outerLimitPaddingPolicy" to "XeTeXBigOpSpacing5FromOpenTypeMATHStackGapMin",
            "upperShiftPx" to placement.upperShift,
            "lowerShiftPx" to placement.lowerShift,
            "actualUpperGapPx" to placement.actualUpperGap,
            "actualUpperBaselineRisePx" to placement.actualUpperRise,
            "actualLowerGapPx" to placement.actualLowerGap,
            "actualLowerBaselineDropPx" to placement.actualLowerDrop,
            "operatorItalicCorrectionPx" to base.italicCorrectionPx,
            "upperCenterOffsetPx" to placement.halfItalicCorrection,
            "lowerCenterOffsetPx" to -placement.halfItalicCorrection,
            "logicalWidthPx" to placement.logicalWidth,
            "logicalWidthPolicy" to "max-unskewed-operator-and-limits",
            "operatorWidthPx" to base.box.width,
            "upperWidthPx" to upper?.box?.width,
            "lowerWidthPx" to lower?.box?.width,
            "operatorX" to placement.baseX,
            "upperX" to placement.upperX,
            "lowerX" to placement.lowerX,
        )
        return LaidNode(
            node = node,
            box = placement.box,
            atomClass = MathAtomClass.Operator,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutOverUnder(
        node: MathOverUnder,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val base = layoutNode(node.base, style, alphabetOverride)
            .let { it.copy(box = it.box.completedTeXBox()) }
        val annotationStyle = when (node.kind) {
            MathOverUnderKind.Underset -> style.subscript()
            MathOverUnderKind.Overset, MathOverUnderKind.StackRel -> style.superscript()
        }
        val annotation = layoutNode(node.annotation, annotationStyle, alphabetOverride)
            .completedTeXMathField()
        val upper = annotation.takeIf { node.kind != MathOverUnderKind.Underset }
        val lower = annotation.takeIf { node.kind == MathOverUnderKind.Underset }
        // amsmath overset/underset suppress operator slant; stackrel retains TeX mathop skew.
        val italicCorrection = if (node.kind == MathOverUnderKind.StackRel) base.italicCorrectionPx else 0f
        val placement = placeStackedLimits(base, upper, lower, style, italicCorrection, node.range)
        decision(
            "TeXOverUnderNoad",
            node.range,
            "kind" to node.kind,
            "atomClass" to node.atomClass,
            "style" to style,
            "annotationStyle" to annotationStyle,
            "commandRange" to node.commandRange,
            "baseRange" to node.base.range,
            "annotationRange" to node.annotation.range,
            "upperLimitGapMinPx" to placement.upperGapMin,
            "upperLimitBaselineRiseMinPx" to placement.upperBaselineRiseMin,
            "lowerLimitGapMinPx" to placement.lowerGapMin,
            "lowerLimitBaselineDropMinPx" to placement.lowerBaselineDropMin,
            "upperOuterPaddingPx" to placement.upperOuterPadding,
            "lowerOuterPaddingPx" to placement.lowerOuterPadding,
            "outerLimitPaddingPolicy" to "XeTeXBigOpSpacing5FromOpenTypeMATHStackGapMin",
            "upperShiftPx" to placement.upperShift,
            "lowerShiftPx" to placement.lowerShift,
            "actualUpperGapPx" to placement.actualUpperGap,
            "actualLowerGapPx" to placement.actualLowerGap,
            "baseItalicCorrectionPx" to base.italicCorrectionPx,
            "usedItalicCorrectionPx" to italicCorrection,
            "logicalWidthPx" to placement.logicalWidth,
            "baseX" to placement.baseX,
            "annotationX" to (placement.upperX ?: placement.lowerX),
            "geometryKernel" to "OpenTypeMathUpperLowerLimitConstants",
            "baseShiftPolicy" to if (node.kind == MathOverUnderKind.StackRel) {
                "TeXMathOpRelationBase"
            } else {
                "AmsmathSuppressBaseShift"
            },
        )
        return LaidNode(
            node = node,
            box = placement.box,
            atomClass = node.atomClass,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutForMeasurement(
        node: MathNode,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): MeasurementLayoutNode {
        val decisionStart = decisions.size
        val diagnosticStart = diagnostics.size
        val constructionGroupStart = nextConstructionPaintGroupId
        val laid = layoutNode(node, style, alphabetOverride).completedTeXMathField()
        val measurementDecisions = decisions.subList(decisionStart, decisions.size).toList()
        val measurementDiagnostics = diagnostics.subList(diagnosticStart, diagnostics.size).toList()
        decisions.subList(decisionStart, decisions.size).clear()
        diagnostics.subList(diagnosticStart, diagnostics.size).clear()
        nextConstructionPaintGroupId = constructionGroupStart
        return MeasurementLayoutNode(laid, measurementDiagnostics, measurementDecisions)
    }

    private fun layoutExtensibleArrow(
        node: MathExtensibleArrow,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val upperStyle = style.superscript()
        val lowerStyle = style.subscript()
        val upper = layoutNode(node.above, upperStyle, alphabetOverride)
            .completedTeXMathField()
        val lower = node.below?.let { below ->
            layoutNode(below, lowerStyle, alphabetOverride).completedTeXMathField()
        }
        // amsmath measures both labels in a fresh scriptstyle box, even when the arrow itself is
        // nested in Script/ScriptScript. The measurement is not painted and must not leak its
        // decisions or construction ownership into the production result.
        val measuredUpper = layoutForMeasurement(node.above, MathStyle.Script, alphabetOverride)
        val measuredLower = node.below?.let { layoutForMeasurement(it, MathStyle.Script, alphabetOverride) }
        (measuredUpper.diagnostics + measuredLower?.diagnostics.orEmpty()).forEach { diagnostic ->
            if (diagnostic !in diagnostics) diagnostics += diagnostic
        }

        val arrowStyle = MathStyle.Display
        val arrowSize = fontSize(arrowStyle)
        val measurementMu = fontSize(MathStyle.Script) / TEX_MU_PER_EM
        val measureLeftMu = if (node.identity == MathExtensibleArrowIdentity.Right) 5f else 9f
        val measureRightMu = if (node.identity == MathExtensibleArrowIdentity.Right) 9f else 5f
        val measuredLabelWidth = max(
            measuredUpper.node.box.width,
            measuredLower?.node?.box?.width ?: 0f,
        )
        val labelTargetWidth = measuredLabelWidth + (measureLeftMu + measureRightMu) * measurementMu

        val arrowHeadCandidates = constructionBaseCandidates(
            scalarString(node.identity.arrowHeadScalar), arrowSize, node.commandRange,
        )
        val relbarCandidates = constructionBaseCandidates(AMSMATH_RELBAR, arrowSize, node.commandRange)
        val faceEvidence = arrowHeadCandidates.firstNotNullOfOrNull { head ->
            val headGlyph = head.run.glyphs.singleOrNull()
            if (head.run.missingGlyph || headGlyph == null) return@firstNotNullOfOrNull null
            relbarCandidates.firstOrNull { bar ->
                val barGlyph = bar.run.glyphs.singleOrNull()
                !bar.run.missingGlyph && barGlyph != null && barGlyph.faceId == headGlyph.faceId
            }?.let { bar -> AmsmathArrowFaceEvidence(headGlyph.faceId, head, bar) }
        }
        if (faceEvidence == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingGlyph,
                "${node.identity.debugName} requires one math face containing both the arrow head and U+2212 relbar",
                node.commandRange,
            )
            return LaidNode(
                node = node,
                box = emptyBox(node.range),
                atomClass = MathAtomClass.Relation,
                italicCorrectionPx = 0f,
                style = style,
                scriptBaseKind = ScriptBaseKind.CompoundBox,
            )
        }

        val headRun = faceEvidence.head.run
        val relbarRun = faceEvidence.relbar.run
        val headGlyph = headRun.glyphs.single()
        val relbarGlyph = relbarRun.glyphs.single()
        val fillMu = arrowSize / TEX_MU_PER_EM
        val endpointOverlap = 7f * fillMu
        val leaderInnerOverlap = 2f * fillMu
        val leftRun = if (node.identity == MathExtensibleArrowIdentity.Left) headRun else relbarRun
        val rightRun = if (node.identity == MathExtensibleArrowIdentity.Right) headRun else relbarRun
        val leftGlyph = leftRun.glyphs.single()
        val rightGlyph = rightRun.glyphs.single()
        val naturalFillWidth = leftRun.width + rightRun.width - 2f * endpointOverlap
        val leaderBoxWidth = relbarRun.width - 2f * leaderInnerOverlap
        if (naturalFillWidth < 0f || leaderBoxWidth <= 0f) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.InvalidExtensibleArrowFill,
                "${node.identity.debugName} cannot form positive amsmath leader geometry from the selected glyph advances",
                node.commandRange,
            )
        }
        val targetWidth = max(naturalFillWidth.coerceAtLeast(0f), labelTargetWidth)
        val leaderGlueWidth = (targetWidth - naturalFillWidth).coerceAtLeast(0f)
        val leaderCount = if (leaderBoxWidth > 0f) floor(leaderGlueWidth / leaderBoxWidth).toInt() else 0
        val centeredLeaderRemainder = if (leaderCount > 0) {
            (leaderGlueWidth - leaderCount * leaderBoxWidth) / 2f
        } else {
            leaderGlueWidth / 2f
        }
        val leaderGlueStart = leftRun.width - endpointOverlap
        val leaderOrigins = List(leaderCount) { index ->
            leaderGlueStart + centeredLeaderRemainder + index * leaderBoxWidth - leaderInnerOverlap
        }
        val rightOrigin = targetWidth - rightRun.width
        val group = MathConstructionPaintGroup(
            id = nextConstructionPaintGroupId++,
            kind = MathConstructionPaintKind.ExtensibleArrow,
            shapeKind = MathConstructionShapeKind.Assembly,
            sourceRange = node.commandRange,
            outlinePolicy = MathConstructionOutlinePolicy.RequireOutlineUnion,
            faceId = faceEvidence.faceId,
        )
        fun placement(glyph: MeasuredMathGlyph, x: Float): MathGlyphPlacement = MathGlyphPlacement(
            glyphId = glyph.glyphId,
            x = x,
            baselineY = 0f,
            advance = glyph.advance,
            inkBounds = glyph.inkBounds.translated(x, 0f),
            fontSizePx = arrowSize,
            sourceRange = node.commandRange,
            style = arrowStyle,
            constructionGroupId = group.id,
            faceId = glyph.faceId,
            fontClass = glyph.fontClass,
            requestedWeight = glyph.requestedWeight,
            resolvedWeight = glyph.resolvedWeight,
            fallbackReason = glyph.fallbackReason,
        )
        val arrowGlyphs = buildList {
            add(placement(leftGlyph, 0f))
            leaderOrigins.forEach { add(placement(relbarGlyph, it)) }
            add(placement(rightGlyph, rightOrigin))
        }
        val outlinesReplayable = faceEvidence.head.outlineCapability == MathConstructionOutlineCapability.Replayable &&
            faceEvidence.relbar.outlineCapability == MathConstructionOutlineCapability.Replayable
        if (!outlinesReplayable) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingConstructionOutlineEvidence,
                "The math font adapter cannot replay the complete ${node.identity.debugName} arrow fill",
                node.commandRange,
            )
        }
        val arrowInkBox = geometryExtents(
            targetWidth,
            arrowGlyphs,
            emptyList(),
            node.commandRange,
            listOf(group),
        )
        // amsmath smashes every relbar vertically; only the terminal arrow head contributes the
        // nucleus height/depth. Painted relbar ink remains in inkBounds and in the semantic union.
        val arrowBox = arrowInkBox.copy(
            ascent = headRun.ascent,
            descent = headRun.descent,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = headRun.ascent,
                descent = headRun.descent,
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = setOf(MathTeXCleanBoxEvidence.GlyphOutline),
            ),
        )
        val arrowBase = LaidNode(
            node = node,
            box = arrowBox,
            atomClass = MathAtomClass.Operator,
            italicCorrectionPx = 0f,
            style = arrowStyle,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
        fun paddedLimit(limit: LaidNode?, limitStyle: MathStyle): LaidNode? = limit?.let {
            val threeMu = 3f * fontSize(limitStyle) / TEX_MU_PER_EM
            val leftKern = if (node.identity == MathExtensibleArrowIdentity.Left) threeMu else 0f
            val rightKern = if (node.identity == MathExtensibleArrowIdentity.Right) threeMu else 0f
            it.copy(box = it.box.withHorizontalKerns(leftKern, rightKern))
        }
        val paddedUpper = paddedLimit(upper, upperStyle)
        val paddedLower = paddedLimit(lower, lowerStyle)
        val placement = placeStackedLimits(arrowBase, paddedUpper, paddedLower, style, 0f, node.range)
        decision(
            "AmsmathXeTeXExtensibleArrow",
            node.range,
            "identity" to node.identity,
            "atomClass" to node.atomClass,
            "style" to style,
            "arrowMeasurementStyle" to arrowStyle,
            "labelMeasurementStyle" to MathStyle.Script,
            "upperStyle" to upperStyle,
            "lowerStyle" to if (lower == null) null else lowerStyle,
            "commandRange" to node.commandRange,
            "upperRange" to node.above.range,
            "lowerRange" to node.belowRange,
            "arrowFontSizePx" to arrowSize,
            "faceId" to faceEvidence.faceId,
            "arrowHeadScalar" to unicodeLabel(node.identity.arrowHeadScalar),
            "arrowHeadGlyphId" to headGlyph.glyphId,
            "relbarScalar" to "U+2212",
            "relbarGlyphId" to relbarGlyph.glyphId,
            "measuredUpperWidthPx" to measuredUpper.node.box.width,
            "measuredLowerWidthPx" to measuredLower?.node?.box?.width,
            "measuredUpperGlyphs" to measuredUpper.node.box.glyphs.joinToString(",") {
                "${it.glyphId}@${it.fontSizePx}:${it.advance}"
            },
            "measurementScriptPlacements" to
                (measuredUpper.decisions + measuredLower?.decisions.orEmpty())
                    .filter { it.name == "OpenTypeMathScriptPlacement" }
                    .joinToString("|") { it.details.toString() },
            "measurementMuPx" to measurementMu,
            "measurementLeftPaddingMu" to measureLeftMu,
            "measurementRightPaddingMu" to measureRightMu,
            "labelTargetWidthPx" to labelTargetWidth,
            "naturalFillWidthPx" to naturalFillWidth,
            "targetWidthPx" to targetWidth,
            "targetPolicy" to "AmsmathExtArrowMeasureScriptLabelsAndDisplayArrowFill",
            "fillMuPx" to fillMu,
            "endpointOverlapMu" to 7,
            "leaderInnerOverlapMu" to 2,
            "leaderBoxWidthPx" to leaderBoxWidth,
            "leaderGlueWidthPx" to leaderGlueWidth,
            "leaderCount" to leaderCount,
            "leaderCenteredRemainderPx" to centeredLeaderRemainder,
            "leaderOriginsPx" to leaderOrigins.joinToString(","),
            "rightEndpointOriginPx" to rightOrigin,
            "fillPolicy" to "AmsmathArrowfillCLeadersRelbar",
            "relbarVerticalPolicy" to "AmsmathMathSmash",
            "upperLimitGapMinPx" to placement.upperGapMin,
            "lowerLimitGapMinPx" to placement.lowerGapMin,
            "upperBaselineRiseMinPx" to placement.upperBaselineRiseMin,
            "lowerBaselineDropMinPx" to placement.lowerBaselineDropMin,
            "upperOuterPaddingPx" to placement.upperOuterPadding,
            "lowerOuterPaddingPx" to placement.lowerOuterPadding,
            "outerLimitPaddingPolicy" to "XeTeXBigOpSpacing5FromOpenTypeMATHStackGapMin",
            "upperShiftPx" to placement.upperShift,
            "lowerShiftPx" to placement.lowerShift,
            "upperX" to placement.upperX,
            "lowerX" to placement.lowerX,
            "logicalWidthPx" to placement.box.width,
            "logicalAscentPx" to placement.box.ascent,
            "logicalDescentPx" to placement.box.descent,
            "paintGroupId" to group.id,
            "outlineReplayable" to outlinesReplayable,
        )
        return LaidNode(
            node = node,
            box = placement.box,
            atomClass = MathAtomClass.Relation,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun placeStackedLimits(
        base: LaidNode,
        upper: LaidNode?,
        lower: LaidNode?,
        style: MathStyle,
        italicCorrectionPx: Float,
        range: SourceRange,
    ): StackedLimitsPlacement {
        val upperGapMin = scale(constants.upperLimitGapMin, style)
        val upperBaselineRiseMin = scale(constants.upperLimitBaselineRiseMin, style)
        val lowerGapMin = scale(constants.lowerLimitGapMin, style)
        val lowerBaselineDropMin = scale(constants.lowerLimitBaselineDropMin, style)
        // XeTeX maps legacy `big_op_spacing5` to OpenType MATH StackGapMin. This is
        // logical padding outside the top/bottom limit, not a translation of painted content.
        val outerLimitPadding = scale(constants.stackGapMin, style)
        val upperOuterPadding = if (upper == null) 0f else outerLimitPadding
        val lowerOuterPadding = if (lower == null) 0f else outerLimitPadding
        val upperShift = upper?.let {
            base.box.ascent + max(upperBaselineRiseMin, it.box.descent + upperGapMin)
        }
        val lowerShift = lower?.let {
            base.box.descent + max(lowerBaselineDropMin, it.box.ascent + lowerGapMin)
        }
        val halfItalicCorrection = italicCorrectionPx / 2f
        val logicalWidth = maxOf(base.box.width, upper?.box?.width ?: 0f, lower?.box?.width ?: 0f)
        val baseX = (logicalWidth - base.box.width) / 2f
        val upperX = upper?.let { (logicalWidth - it.box.width) / 2f + halfItalicCorrection }
        val lowerX = lower?.let { (logicalWidth - it.box.width) / 2f - halfItalicCorrection }
        val shiftedBase = base.box.translated(baseX, 0f)
        val shiftedUpper = upper?.let { it.box.translated(upperX!!, -upperShift!!) }
        val shiftedLower = lower?.let { it.box.translated(lowerX!!, lowerShift!!) }
        val unpaddedBox = geometryExtentsPreservingLogicalChildren(
            logicalWidth,
            shiftedBase.glyphs + shiftedUpper?.glyphs.orEmpty() + shiftedLower?.glyphs.orEmpty(),
            shiftedBase.rules + shiftedUpper?.rules.orEmpty() + shiftedLower?.rules.orEmpty(),
            range,
            buildList {
                add(base.box to 0f)
                upper?.let { add(it.box to -upperShift!!) }
                lower?.let { add(it.box to lowerShift!!) }
            },
        )
        val box = unpaddedBox.copy(
            ascent = unpaddedBox.ascent + upperOuterPadding,
            descent = unpaddedBox.descent + lowerOuterPadding,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = unpaddedBox.texCleanBoxMetrics.ascent + upperOuterPadding,
                descent = unpaddedBox.texCleanBoxMetrics.descent + lowerOuterPadding,
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = unpaddedBox.texCleanBoxMetrics.evidence + MathTeXCleanBoxEvidence.CompletedChildBox,
            ),
        )
        return StackedLimitsPlacement(
            box = box,
            base = base,
            upper = upper,
            lower = lower,
            upperGapMin = upperGapMin,
            upperBaselineRiseMin = upperBaselineRiseMin,
            lowerGapMin = lowerGapMin,
            lowerBaselineDropMin = lowerBaselineDropMin,
            upperOuterPadding = upperOuterPadding,
            lowerOuterPadding = lowerOuterPadding,
            upperShift = upperShift,
            lowerShift = lowerShift,
            halfItalicCorrection = halfItalicCorrection,
            logicalWidth = logicalWidth,
            baseX = baseX,
            upperX = upperX,
            lowerX = lowerX,
        )
    }

    private fun layoutScripts(node: MathScripts, style: MathStyle, alphabetOverride: MathAlphabetOverride?): LaidNode =
        layoutScriptsWithBase(node, layoutNode(node.base, style, alphabetOverride), style, alphabetOverride)

    private fun layoutScriptsWithBase(
        node: MathScripts,
        rawBase: LaidNode,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
        horizontalPolicy: SideScriptHorizontalPolicy = SideScriptHorizontalPolicy.OrdinaryNucleus,
    ): LaidNode {
        val base = rawBase.withNativeOutlineBoxForSideScriptPlacement()
        val superscript = node.superscript
            ?.let { layoutNode(it, style.superscript(), alphabetOverride) }
            ?.withNativeOutlineBoxForSideScriptPlacement()
        val subscript = node.subscript
            ?.let { layoutNode(it, style.subscript(), alphabetOverride) }
            ?.withNativeOutlineBoxForSideScriptPlacement()
        val standardSuperscriptShift = scale(
            if (style.cramped) constants.superscriptShiftUpCramped else constants.superscriptShiftUp,
            style,
        )
        val standardSubscriptShift = scale(constants.subscriptShiftDown, style)
        val appliesBaselineDrop = base.scriptBaseKind != ScriptBaseKind.Character
        val basePaintedMetrics = base.box.sideScriptVerticalMetrics()
        val superscriptPaintedMetrics = superscript?.box?.sideScriptVerticalMetrics()
        val subscriptPaintedMetrics = subscript?.box?.sideScriptVerticalMetrics()
        val baseVerticalMetrics = base.box.texCleanSideScriptVerticalMetrics()
        val superscriptVerticalMetrics = superscript?.box?.texCleanSideScriptVerticalMetrics()
        val subscriptVerticalMetrics = subscript?.box?.texCleanSideScriptVerticalMetrics()
        val verticalConstraints = SideScriptVerticalConstraints(
            superscriptShiftUpPx = standardSuperscriptShift,
            subscriptShiftDownPx = standardSubscriptShift,
            superscriptBottomMinPx = scale(constants.superscriptBottomMin, style),
            superscriptBaselineDropMaxPx = scale(constants.superscriptBaselineDropMax, style.superscript()),
            subscriptTopMaxPx = scale(constants.subscriptTopMax, style),
            subscriptBaselineDropMinPx = scale(constants.subscriptBaselineDropMin, style.subscript()),
            subSuperscriptGapMinPx = scale(constants.subSuperscriptGapMin, style),
            superscriptBottomMaxWithSubscriptPx = scale(constants.superscriptBottomMaxWithSubscript, style),
        )
        val verticalPlacement = resolveSideScriptVerticalPlacement(
            base = baseVerticalMetrics,
            superscript = superscriptVerticalMetrics,
            subscript = subscriptVerticalMetrics,
            appliesBaselineDrop = appliesBaselineDrop,
            constraints = verticalConstraints,
        )
        val superscriptShift = verticalPlacement.superscriptShiftPx
        val subscriptShift = verticalPlacement.subscriptShiftPx

        val superscriptKern = superscript?.let { superscriptMathKern(base, it, superscriptShift, node.range) } ?: 0f
        val subscriptKern = subscript?.let { subscriptMathKern(base, it, subscriptShift, node.range) } ?: 0f
        // XeTeX appends the fixed `\scriptspace` to each completed script field. Adding the
        // same fixed amount after the maximum script edge is algebraically identical and avoids
        // duplicating logical padding when both scripts are present.
        val spaceAfterScript = scriptSpacePx
        val superscriptFieldWidth = superscript?.completedTeXScriptFieldWidth()
        val subscriptFieldWidth = subscript?.completedTeXScriptFieldWidth()
        val horizontalPlacement = resolveSideScriptHorizontalPlacement(
            baseWidthPx = base.box.width,
            italicCorrectionPx = base.italicCorrectionPx,
            superscriptWidthPx = superscriptFieldWidth,
            subscriptWidthPx = subscriptFieldWidth,
            superscriptKernPx = superscriptKern,
            subscriptKernPx = subscriptKern,
            spaceAfterScriptPx = spaceAfterScript,
            policy = horizontalPolicy,
        )
        val superscriptX = horizontalPlacement.superscriptXPx
        val subscriptX = horizontalPlacement.subscriptXPx
        val glyphs = buildList {
            addAll(base.box.glyphs)
            superscript?.let { addAll(it.box.translated(checkNotNull(superscriptX), -superscriptShift).glyphs) }
            subscript?.let { addAll(it.box.translated(checkNotNull(subscriptX), subscriptShift).glyphs) }
        }
        val rules = buildList {
            addAll(base.box.rules)
            superscript?.let { addAll(it.box.translated(checkNotNull(superscriptX), -superscriptShift).rules) }
            subscript?.let { addAll(it.box.translated(checkNotNull(subscriptX), subscriptShift).rules) }
        }
        val width = horizontalPlacement.logicalWidthPx
        decision(
            "OpenTypeMathScriptPlacement",
            node.range,
            "style" to style,
            "superscriptStyle" to superscript?.style,
            "subscriptStyle" to subscript?.style,
            "superscriptShiftPx" to superscriptShift,
            "subscriptShiftPx" to subscriptShift,
            "superscriptKernPx" to superscriptKern,
            "subscriptKernPx" to subscriptKern,
            "horizontalPlacementPolicy" to horizontalPlacement.policy,
            "baseOriginalLogicalWidthPx" to horizontalPlacement.originalBaseWidthPx,
            "superscriptLogicalWidthPx" to superscriptFieldWidth,
            "subscriptLogicalWidthPx" to subscriptFieldWidth,
            "superscriptTerminalItalicCorrectionPx" to superscript?.italicCorrectionPx?.coerceAtLeast(0f),
            "subscriptTerminalItalicCorrectionPx" to subscript?.italicCorrectionPx?.coerceAtLeast(0f),
            "childLogicalAdvancePolicy" to "XeTeXCleanScriptFieldIncludingTerminalItalicCorrection",
            "italicCorrectionDeltaPx" to horizontalPlacement.italicCorrectionDeltaPx,
            "operatorWidthReductionPx" to horizontalPlacement.operatorWidthReductionPx,
            "nucleusLogicalWidthPx" to horizontalPlacement.nucleusLogicalWidthPx,
            "superscriptItalicDeltaPx" to horizontalPlacement.superscriptItalicDeltaPx,
            "superscriptXPx" to superscriptX,
            "subscriptXPx" to subscriptX,
            "logicalWidthPx" to width,
            "spaceAfterScriptPx" to spaceAfterScript,
            "spaceAfterScriptPolicy" to scriptSpacePolicy,
            "baseKind" to base.scriptBaseKind,
            "superscriptKind" to superscript?.scriptBaseKind,
            "subscriptKind" to subscript?.scriptBaseKind,
            "baselineDropApplied" to appliesBaselineDrop,
            "verticalPlacementMetricPolicy" to
                "XeTeXNativeGlyphOutlineOrCompletedChildBoxForOrdinarySideScripts",
            "logicalReservePolicy" to "ExactOutlinePlacementWithCompletedChildBoxes",
            "baseLogicalAscentPx" to baseVerticalMetrics.logicalAscentPx,
            "baseLogicalDescentPx" to baseVerticalMetrics.logicalDescentPx,
            "baseInkTopPx" to baseVerticalMetrics.inkTopPx,
            "baseInkBottomPx" to baseVerticalMetrics.inkBottomPx,
            "baseInkAscentPx" to baseVerticalMetrics.inkAscentPx,
            "baseInkDescentPx" to baseVerticalMetrics.inkDescentPx,
            "basePaintedInkTopPx" to basePaintedMetrics.inkTopPx,
            "basePaintedInkBottomPx" to basePaintedMetrics.inkBottomPx,
            "superscriptLogicalAscentPx" to superscriptVerticalMetrics?.logicalAscentPx,
            "superscriptLogicalDescentPx" to superscriptVerticalMetrics?.logicalDescentPx,
            "superscriptInkTopPx" to superscriptVerticalMetrics?.inkTopPx,
            "superscriptInkBottomPx" to superscriptVerticalMetrics?.inkBottomPx,
            "superscriptInkAscentPx" to superscriptVerticalMetrics?.inkAscentPx,
            "superscriptInkDescentPx" to superscriptVerticalMetrics?.inkDescentPx,
            "superscriptPaintedInkTopPx" to superscriptPaintedMetrics?.inkTopPx,
            "superscriptPaintedInkBottomPx" to superscriptPaintedMetrics?.inkBottomPx,
            "subscriptLogicalAscentPx" to subscriptVerticalMetrics?.logicalAscentPx,
            "subscriptLogicalDescentPx" to subscriptVerticalMetrics?.logicalDescentPx,
            "subscriptInkTopPx" to subscriptVerticalMetrics?.inkTopPx,
            "subscriptInkBottomPx" to subscriptVerticalMetrics?.inkBottomPx,
            "subscriptInkAscentPx" to subscriptVerticalMetrics?.inkAscentPx,
            "subscriptInkDescentPx" to subscriptVerticalMetrics?.inkDescentPx,
            "subscriptPaintedInkTopPx" to subscriptPaintedMetrics?.inkTopPx,
            "subscriptPaintedInkBottomPx" to subscriptPaintedMetrics?.inkBottomPx,
            "standardSuperscriptShiftUpPx" to standardSuperscriptShift,
            "standardSubscriptShiftDownPx" to standardSubscriptShift,
            "superscriptBottomMinPx" to verticalConstraints.superscriptBottomMinPx,
            "superscriptBaselineDropMaxPx" to verticalConstraints.superscriptBaselineDropMaxPx,
            "superscriptBaselineDropStyle" to style.superscript(),
            "subscriptTopMaxPx" to verticalConstraints.subscriptTopMaxPx,
            "subscriptBaselineDropMinPx" to verticalConstraints.subscriptBaselineDropMinPx,
            "subscriptBaselineDropStyle" to style.subscript(),
            "subSuperscriptGapMinPx" to verticalConstraints.subSuperscriptGapMinPx,
            "superscriptBottomMaxWithSubscriptPx" to
                verticalConstraints.superscriptBottomMaxWithSubscriptPx,
            "superscriptShiftBeforePairGapPx" to verticalPlacement.superscriptShiftBeforePairGapPx,
            "subscriptShiftBeforePairGapPx" to verticalPlacement.subscriptShiftBeforePairGapPx,
            "pairGapBeforeAdjustmentPx" to verticalPlacement.pairGapBeforeAdjustmentPx,
            "pairGapDeficitPx" to verticalPlacement.pairGapDeficitPx,
            "superscriptPairGapMovePx" to verticalPlacement.superscriptPairGapMovePx,
            "subscriptPairGapMovePx" to verticalPlacement.subscriptPairGapMovePx,
            "finalInkGapPx" to verticalPlacement.finalInkGapPx,
            "superscriptInkBottomAfterShiftPx" to superscriptVerticalMetrics?.let {
                -superscriptShift + it.inkBottomPx
            },
            "subscriptInkTopAfterShiftPx" to subscriptVerticalMetrics?.let {
                subscriptShift + it.inkTopPx
            },
            "texCleanBoxPlacementPolicy" to "SamePlacementAsPaintedGlyphs",
            "texCleanBoxSuperscriptShiftPx" to superscriptShift,
            "texCleanBoxSubscriptShiftPx" to subscriptShift,
            "boxKernPolicy" to "single-glyph-corners-else-zero",
        )
        val scriptBox = geometryExtentsPreservingLogicalChildren(
            width,
            glyphs,
            rules,
            node.range,
            buildList {
                add(base.box to 0f)
                superscript?.let { add(it.box to -superscriptShift) }
                subscript?.let { add(it.box to subscriptShift) }
            },
        )
        return LaidNode(
            node = node,
            box = scriptBox,
            atomClass = base.atomClass,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    /**
     * XeTeX native character noads use the glyph's exact bounding box in `make_scripts`.
     * Replace the replayed leaf box itself so the constraint calculation, painted baseline,
     * completed MathBox, and recursive clean-box metric all share one placement. Compound boxes
     * already carry their completed TeX metrics and must not be flattened back to glyph ink.
     */
    private fun LaidNode.withNativeOutlineBoxForSideScriptPlacement(): LaidNode {
        if (scriptBaseKind != ScriptBaseKind.Character || box.glyphs.isEmpty()) {
            return copy(box = box.completedTeXBox())
        }
        val evidence = mutableSetOf<MathTeXCleanBoxEvidence>()
        val outlineGlyphs = box.glyphs.map { placement ->
            if (mathFontForFaceOrNull(placement.faceId) == null) {
                evidence += MathTeXCleanBoxEvidence.GlyphOutline
                return@map placement
            }
            val measured = measureGlyphOutlineForFace(
                placement.faceId,
                placement.glyphId,
                placement.fontSizePx,
                placement.style,
                placement.sourceRange,
            )
            evidence += if (measured.boundsSource == MathGlyphBoundsSource.Outline) {
                MathTeXCleanBoxEvidence.GlyphOutline
            } else {
                MathTeXCleanBoxEvidence.FontReportedGlyphBounds
            }
            val glyph = measured.glyphs.singleOrNull() ?: return@map placement
            placement.copy(inkBounds = glyph.inkBounds.translated(placement.x, placement.baselineY))
        }
        val left = outlineGlyphs.minOfOrNull { it.inkBounds.left } ?: 0f
        val top = outlineGlyphs.minOfOrNull { it.inkBounds.top } ?: 0f
        val right = outlineGlyphs.maxOfOrNull { it.inkBounds.right } ?: 0f
        val bottom = outlineGlyphs.maxOfOrNull { it.inkBounds.bottom } ?: 0f
        val logicalAdvance = outlineGlyphs.maxOfOrNull { it.x + it.advance } ?: box.width
        val ascent = (-top).coerceAtLeast(0f)
        val descent = bottom.coerceAtLeast(0f)
        return copy(
            box = box.copy(
                width = logicalAdvance,
                ascent = ascent,
                descent = descent,
                inkBounds = MathRect(left, top, right, bottom),
                glyphs = outlineGlyphs,
                texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                    ascent = ascent,
                    descent = descent,
                    policy = MathTeXCleanBoxPolicy.GlyphOutlineUnion,
                    evidence = evidence,
                ),
            ),
        )
    }

    private fun MathBox.completedTeXBox(): MathBox = copy(
        ascent = texCleanBoxMetrics.ascent,
        descent = texCleanBoxMetrics.descent,
    )

    /** XeTeX `clean_box` completes a math field with its terminal italic correction. */
    private fun LaidNode.completedTeXMathField(): LaidNode = copy(
        box = box.completedTeXBox().copy(width = box.width + italicCorrectionPx.coerceAtLeast(0f)),
        italicCorrectionPx = 0f,
    )

    /** XeTeX `clean_box` retains a character field's terminal italic correction. */
    private fun LaidNode.completedTeXScriptFieldWidth(): Float =
        box.width + italicCorrectionPx.coerceAtLeast(0f)

    private fun superscriptMathKern(
        base: LaidNode,
        script: LaidNode,
        shift: Float,
        range: SourceRange,
    ): Float {
        if (base.scriptBaseKind == ScriptBaseKind.CompoundBox || script.scriptBaseKind == ScriptBaseKind.CompoundBox) {
            decision("OpenTypeMathKern", range, "kind" to "superscript", "strategy" to "box-zero", "kernPx" to 0f)
            return 0f
        }
        val baseGlyph = base.box.singleGlyphOrNull()
        val scriptGlyph = script.box.singleGlyphOrNull()
        if (baseGlyph == null || scriptGlyph == null) {
            decision("OpenTypeMathKern", range, "kind" to "superscript", "strategy" to "box-zero", "kernPx" to 0f)
            return 0f
        }
        if (baseGlyph.faceId != scriptGlyph.faceId) {
            decision("OpenTypeMathKern", range, "kind" to "superscript", "strategy" to "cross-face-zero", "kernPx" to 0f)
            return 0f
        }
        val mathFont = mathFontForFaceOrNull(baseGlyph.faceId) ?: return 0f
        val first = mathFont.mathKern(
            baseGlyph.glyphId,
            MathKernCorner.TopRight,
            shift - script.box.inkBounds.bottom,
            baseGlyph.fontSizePx,
        ) + mathFont.mathKern(
            scriptGlyph.glyphId,
            MathKernCorner.BottomLeft,
            -script.box.inkBounds.bottom,
            scriptGlyph.fontSizePx,
        )
        val second = mathFont.mathKern(
            baseGlyph.glyphId,
            MathKernCorner.TopRight,
            -base.box.inkBounds.top,
            baseGlyph.fontSizePx,
        ) + mathFont.mathKern(
            scriptGlyph.glyphId,
            MathKernCorner.BottomLeft,
            -shift - base.box.inkBounds.top,
            scriptGlyph.fontSizePx,
        )
        val kern = minOf(first, second)
        decision(
            "OpenTypeMathKern",
            range,
            "kind" to "superscript",
            "strategy" to "two-correction-heights",
            "candidate1Px" to first,
            "candidate2Px" to second,
            "kernPx" to kern,
        )
        return kern
    }

    private fun subscriptMathKern(
        base: LaidNode,
        script: LaidNode,
        shift: Float,
        range: SourceRange,
    ): Float {
        if (base.scriptBaseKind == ScriptBaseKind.CompoundBox || script.scriptBaseKind == ScriptBaseKind.CompoundBox) {
            decision("OpenTypeMathKern", range, "kind" to "subscript", "strategy" to "box-zero", "kernPx" to 0f)
            return 0f
        }
        val baseGlyph = base.box.singleGlyphOrNull()
        val scriptGlyph = script.box.singleGlyphOrNull()
        if (baseGlyph == null || scriptGlyph == null) {
            decision("OpenTypeMathKern", range, "kind" to "subscript", "strategy" to "box-zero", "kernPx" to 0f)
            return 0f
        }
        if (baseGlyph.faceId != scriptGlyph.faceId) {
            decision("OpenTypeMathKern", range, "kind" to "subscript", "strategy" to "cross-face-zero", "kernPx" to 0f)
            return 0f
        }
        val mathFont = mathFontForFaceOrNull(baseGlyph.faceId) ?: return 0f
        val first = mathFont.mathKern(
            baseGlyph.glyphId,
            MathKernCorner.BottomRight,
            -shift - script.box.inkBounds.top,
            baseGlyph.fontSizePx,
        ) + mathFont.mathKern(
            scriptGlyph.glyphId,
            MathKernCorner.TopLeft,
            -script.box.inkBounds.top,
            scriptGlyph.fontSizePx,
        )
        val second = mathFont.mathKern(
            baseGlyph.glyphId,
            MathKernCorner.BottomRight,
            -base.box.inkBounds.bottom,
            baseGlyph.fontSizePx,
        ) + mathFont.mathKern(
            scriptGlyph.glyphId,
            MathKernCorner.TopLeft,
            shift - base.box.inkBounds.bottom,
            scriptGlyph.fontSizePx,
        )
        val kern = minOf(first, second)
        decision(
            "OpenTypeMathKern",
            range,
            "kind" to "subscript",
            "strategy" to "two-correction-heights",
            "candidate1Px" to first,
            "candidate2Px" to second,
            "kernPx" to kern,
        )
        return kern
    }

    private fun layoutFraction(node: MathFraction, style: MathStyle, alphabetOverride: MathAlphabetOverride?): LaidNode {
        val fractionStyle = node.styleOverride?.let(::styleForLevel) ?: style
        val numerator = refineFractionChildBox(
            layoutNode(node.numerator, fractionStyle.fractionNumerator(), alphabetOverride).box,
            node,
            "numerator",
        ).let { if (node.numeratorStrut) applyContinuedFractionNumeratorStrut(it, node, fractionStyle) else it }
        val denominator = refineFractionChildBox(
            layoutNode(node.denominator, fractionStyle.fractionDenominator(), alphabetOverride).box,
            node,
            "denominator",
        )
        val display = fractionStyle.level == MathStyleLevel.Display
        val stack = layoutFractionStack(node, fractionStyle, numerator, denominator, display)
        val fractionNoad = addNullFractionDelimiters(stack, node)
        val withDelimiters = if (node.hasParentheses) {
            addBinomialParentheses(fractionNoad, stack, node, fractionStyle)
        } else {
            fractionNoad
        }
        decision(
            "TeXFractionCommand",
            node.range,
            "origin" to node.origin,
            "commandRange" to node.commandRange,
            "outerStyle" to style,
            "fractionStyle" to fractionStyle,
            "styleOverride" to node.styleOverride,
            "numeratorAlignment" to node.numeratorAlignment,
            "alignmentRange" to node.alignmentRange,
            "numeratorStrut" to node.numeratorStrut,
            "retainRightNullDelimiterSpace" to node.retainRightNullDelimiterSpace,
        )
        return LaidNode(
            node,
            withDelimiters,
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
    }

    private fun applyContinuedFractionNumeratorStrut(
        box: MathBox,
        node: MathFraction,
        style: MathStyle,
    ): MathBox {
        val size = fontSize(style)
        val minimumAscent = TEX_ARRAY_STRUT_ASCENT_EM * size
        val minimumDescent = TEX_ARRAY_STRUT_DESCENT_EM * size
        val ascent = max(box.ascent, minimumAscent)
        val descent = max(box.descent, minimumDescent)
        val result = box.copy(
            ascent = ascent,
            descent = descent,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = max(box.texCleanBoxMetrics.ascent, minimumAscent),
                descent = max(box.texCleanBoxMetrics.descent, minimumDescent),
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = box.texCleanBoxMetrics.evidence + MathTeXCleanBoxEvidence.CompletedChildBox,
            ),
        )
        decision(
            "AmsmathContinuedFractionNumeratorStrut",
            node.range,
            "style" to style,
            "fontSizePx" to size,
            "strutAscentEm" to TEX_ARRAY_STRUT_ASCENT_EM,
            "strutDescentEm" to TEX_ARRAY_STRUT_DESCENT_EM,
            "minimumAscentPx" to minimumAscent,
            "minimumDescentPx" to minimumDescent,
            "inputAscentPx" to box.ascent,
            "inputDescentPx" to box.descent,
            "outputAscentPx" to result.ascent,
            "outputDescentPx" to result.descent,
            "policy" to "AmsmathCfracTextSizeStrut",
        )
        return result
    }

    /** Consumes the child's already completed TeX box; no flattened-ink reconstruction. */
    private fun refineFractionChildBox(box: MathBox, node: MathFraction, role: String): MathBox {
        val clean = box.texCleanBoxMetrics
        val refined = box.copy(ascent = clean.ascent, descent = clean.descent)
        decision(
            "TeXFractionChildBoxMetrics",
            node.range,
            "role" to role,
            "policy" to "CompletedChildTeXCleanBoxMetrics",
            "cleanBoxPolicy" to clean.policy,
            "cleanBoxEvidence" to clean.evidence,
            "logicalAdvanceBeforePx" to box.width,
            "logicalAdvanceAfterPx" to refined.width,
            "inkTopBeforePx" to box.inkBounds.top,
            "inkBottomBeforePx" to box.inkBounds.bottom,
            "inkTopAfterPx" to refined.inkBounds.top,
            "inkBottomAfterPx" to refined.inkBounds.bottom,
            "completedAscentBeforePx" to box.ascent,
            "completedDescentBeforePx" to box.descent,
            "cleanAscentPx" to clean.ascent,
            "cleanDescentPx" to clean.descent,
        )
        return refined
    }

    private fun addNullFractionDelimiters(stack: MathBox, node: MathFraction): MathBox {
        val shiftedStack = stack.translated(nullDelimiterSpacePx, 0f)
        val rightSpace = if (node.retainRightNullDelimiterSpace) nullDelimiterSpacePx else 0f
        decision(
            "TeXFractionNullDelimiters",
            node.range,
            "leftSpacePx" to nullDelimiterSpacePx,
            "rightSpacePx" to rightSpace,
            "parameter" to "nullDelimiterSpacePx",
            "styleInvariant" to true,
            "rightSpacePolicy" to if (node.retainRightNullDelimiterSpace) {
                "TeXFractionNullDelimiterSpace"
            } else {
                "AmsmathCfracTrailingNullDelimiterSpaceCancellation"
            },
        )
        return geometryExtentsPreservingLogicalChildren(
            width = stack.width + nullDelimiterSpacePx + rightSpace,
            glyphs = shiftedStack.glyphs,
            rules = shiftedStack.rules,
            range = node.range,
            children = listOf(stack to 0f),
        )
    }

    /** Shared OpenType MATH vertical kernel for barred fractions and ruleless binomial stacks. */
    private fun layoutFractionStack(
        node: MathFraction,
        style: MathStyle,
        numerator: MathBox,
        denominator: MathBox,
        display: Boolean,
    ): MathBox {
        val contentWidth = max(numerator.width, denominator.width)
        val numeratorX = when (node.numeratorAlignment) {
            MathFractionAlignment.Center -> (contentWidth - numerator.width) / 2f
            MathFractionAlignment.Left -> 0f
            MathFractionAlignment.Right -> contentWidth - numerator.width
        }
        val denominatorX = (contentWidth - denominator.width) / 2f
        val axisY = -scale(constants.axisHeight, style)
        var numeratorShift: Float
        var denominatorShift: Float
        val rules: List<MathRulePlacement>

        if (node.kind == FractionKind.Barred) {
            val thickness = scale(constants.fractionRuleThickness, style)
            val ruleTop = axisY - thickness / 2f
            val ruleBottom = axisY + thickness / 2f
            numeratorShift = scale(
                if (display) constants.fractionNumeratorDisplayStyleShiftUp else constants.fractionNumeratorShiftUp,
                style,
            )
            denominatorShift = scale(
                if (display) constants.fractionDenominatorDisplayStyleShiftDown else constants.fractionDenominatorShiftDown,
                style,
            )
            val numeratorGap = scale(
                if (display) constants.fractionNumDisplayStyleGapMin else constants.fractionNumeratorGapMin,
                style.fractionNumerator(),
            )
            val denominatorGap = scale(
                if (display) constants.fractionDenomDisplayStyleGapMin else constants.fractionDenominatorGapMin,
                style.fractionDenominator(),
            )
            numeratorShift = max(numeratorShift, numerator.descent + numeratorGap - ruleTop)
            denominatorShift = max(denominatorShift, denominator.ascent + denominatorGap + ruleBottom)
            val actualNumeratorGap = ruleTop - (-numeratorShift + numerator.descent)
            val actualDenominatorGap = (denominatorShift - denominator.ascent) - ruleBottom
            rules = listOf(MathRulePlacement(0f, ruleTop, contentWidth, ruleBottom, node.range))
            decision(
                "OpenTypeMathFractionStack",
                node.range,
                "kind" to "barred",
                "style" to style,
                "axisPx" to -axisY,
                "ruleThicknessPx" to thickness,
                "numeratorShiftPx" to numeratorShift,
                "denominatorShiftPx" to denominatorShift,
                "numeratorGapMinPx" to numeratorGap,
                "denominatorGapMinPx" to denominatorGap,
                "gapConstantScalePolicy" to "XeTeXPostCleanBoxChildMathFontSize",
                "actualNumeratorGapPx" to actualNumeratorGap,
                "actualDenominatorGapPx" to actualDenominatorGap,
                "numeratorAlignment" to node.numeratorAlignment,
                "numeratorX" to numeratorX,
                "denominatorX" to denominatorX,
            )
        } else {
            // TeX Rule 15c uses num1 in display and num3 otherwise, together with denom1/2,
            // then enforces stack clearance. XeTeX's same-OTF trace maps that asymmetry to
            // FractionNumeratorDisplayStyleShiftUp in display, StackTopShiftUp otherwise,
            // and the corresponding FractionDenominator shift. OpenType's generic
            // StackTopDisplay/StackBottom shifts do not reproduce TeX's `\atop` box.
            numeratorShift = scale(
                if (display) constants.fractionNumeratorDisplayStyleShiftUp else constants.stackTopShiftUp,
                style,
            )
            denominatorShift = scale(
                if (display) constants.fractionDenominatorDisplayStyleShiftDown else constants.fractionDenominatorShiftDown,
                style,
            )
            val minimumGap = scale(
                if (display) constants.stackDisplayStyleGapMin else constants.stackGapMin,
                style.fractionDenominator(),
            )
            val actualGap = (denominatorShift - denominator.ascent) - (-numeratorShift + numerator.descent)
            val missingGap = (minimumGap - actualGap).coerceAtLeast(0f)
            numeratorShift += missingGap / 2f
            denominatorShift += missingGap / 2f
            val finalGap = (denominatorShift - denominator.ascent) - (-numeratorShift + numerator.descent)
            rules = emptyList()
            decision(
                "OpenTypeMathFractionStack",
                node.range,
                "kind" to "ruleless",
                "style" to style,
                "numeratorShiftPx" to numeratorShift,
                "denominatorShiftPx" to denominatorShift,
                "gapMinPx" to minimumGap,
                "gapConstantScalePolicy" to "XeTeXPostCleanBoxChildMathFontSize",
                "symmetricGapCorrectionPx" to missingGap / 2f,
                "actualGapPx" to finalGap,
                "shiftPolicy" to "TeXRule15cNum1Num3Denom1Denom2WithOpenTypeStackGap",
                "numeratorAlignment" to node.numeratorAlignment,
                "numeratorX" to numeratorX,
                "denominatorX" to denominatorX,
            )
        }

        val shiftedNumerator = numerator.translated(numeratorX, -numeratorShift)
        val shiftedDenominator = denominator.translated(denominatorX, denominatorShift)
        return geometryExtentsPreservingLogicalChildren(
            width = contentWidth,
            glyphs = shiftedNumerator.glyphs + shiftedDenominator.glyphs,
            rules = shiftedNumerator.rules + shiftedDenominator.rules + rules,
            range = node.range,
            children = listOf(
                numerator to -numeratorShift,
                denominator to denominatorShift,
            ),
        )
    }

    private fun addBinomialParentheses(
        fractionNoad: MathBox,
        stack: MathBox,
        node: MathFraction,
        style: MathStyle,
    ): MathBox {
        val targetReferenceSize = fontSize(style)
        val targetEmFactor = when (style.level) {
            MathStyleLevel.Display -> LATEX_XETEX_GENFRAC_DISPLAY_DELIMITER_EM
            MathStyleLevel.Text -> LATEX_XETEX_GENFRAC_TEXT_DELIMITER_EM
            MathStyleLevel.Script -> LATEX_XETEX_GENFRAC_SCRIPT_DELIMITER_EM
            MathStyleLevel.ScriptScript -> LATEX_XETEX_GENFRAC_SCRIPT_SCRIPT_DELIMITER_EM
        }
        val targetHeight = targetReferenceSize * targetEmFactor

        // LaTeX2e's XeTeX genfrac fallback creates each delimiter in an inner text-style
        // formula around a style-selected, zero-width vcenter. The OpenType MATH table has
        // no fraction delim1/delim2 constants, so these named fallback factors are the same
        // ones used by amsmath (2.39/1/1.45/1.35 em). This is deliberately separate from
        // the content-driven \left/\right policy and from DelimitedSubFormulaMinHeight.
        val delimiterStyle = MathStyle.Text
        val delimiterFontSize = fontSize(delimiterStyle)
        val axisY = -scale(constants.axisHeight, delimiterStyle)

        fun construction(baseRun: MeasuredMathRun, side: String): MathVerticalConstruction? {
            val baseGlyphId = baseRun.glyphs.singleOrNull()?.glyphId
            val selected = baseGlyphId?.let {
                selectVerticalConstruction(
                    baseGlyphId = it,
                    normalRun = baseRun,
                    targetHeight = targetHeight,
                    size = delimiterFontSize,
                    style = delimiterStyle,
                    range = node.range,
                )
            }
            return selected
        }

        fun chooseDelimiter(text: String, side: String): Pair<MeasuredMathRun, MathVerticalConstruction?> {
            val candidates = constructionBaseCandidates(text, delimiterFontSize, node.range)
                .map { it.run }
                .filter { !it.missingGlyph && it.glyphs.size == 1 }
                .map { it to construction(it, side) }
            return candidates.firstOrNull { it.second?.reachesTarget == true }
                ?: candidates.firstOrNull()
                ?: (glyphSource.shapeOutlineConstructionBase(text, delimiterFontSize, node.range).run to null)
        }
        val (leftBase, leftConstruction) = chooseDelimiter("(", "left")
        val (rightBase, rightConstruction) = chooseDelimiter(")", "right")
        listOf("left" to leftConstruction, "right" to rightConstruction).forEach { (side, selected) ->
            if (selected == null) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MissingMathConstruction,
                    "The $side parenthesis has no MATH construction covering ${targetHeight}px",
                    node.range,
                )
            }
        }
        fun delimiterBox(
            side: String,
            construction: MathVerticalConstruction?,
            baseRun: MeasuredMathRun,
        ): MathBox {
            val baseGlyphId = baseRun.glyphs.singleOrNull()?.glyphId
            val delimiterFaceId = baseRun.glyphs.singleOrNull()?.faceId ?: glyphSource.faceId
            val delimiterMathFont = mathFontForFace(delimiterFaceId)
            val assemblyValidation = construction?.assemblyValidation
                ?: baseGlyphId?.let(delimiterMathFont::verticalAssemblyValidation)
            val componentRuns = construction?.components?.map { component ->
                component to measureConstructionGlyphForFace(
                    delimiterFaceId,
                    component.glyphId,
                    delimiterFontSize,
                    delimiterStyle,
                    node.range,
                ).run
            }
            val placedConstruction = construction?.let {
                placeVerticalConstruction(
                    construction = it,
                    componentRuns = componentRuns.orEmpty(),
                    size = delimiterFontSize,
                    style = delimiterStyle,
                    sourceRange = node.range,
                    centerComponentsHorizontally = true,
                )
            }
            val rawPlacements = if (placedConstruction == null) {
                baseRun.glyphs.map { glyph ->
                    MathGlyphPlacement(
                        glyphId = glyph.glyphId,
                        x = glyph.x,
                        baselineY = glyph.baselineOffsetPx,
                        advance = glyph.advance,
                        inkBounds = glyph.inkBounds.translated(glyph.x, glyph.baselineOffsetPx),
                        fontSizePx = delimiterFontSize,
                        sourceRange = node.range,
                        style = delimiterStyle,
                        faceId = glyph.faceId,
                        fontClass = glyph.fontClass,
                        requestedWeight = glyph.requestedWeight,
                        resolvedWeight = glyph.resolvedWeight,
                        fallbackReason = glyph.fallbackReason,
                    )
                }
            } else {
                placedConstruction.glyphs
            }
            val inkTop = rawPlacements.minOfOrNull { it.inkBounds.top } ?: 0f
            val inkBottom = rawPlacements.maxOfOrNull { it.inkBounds.bottom } ?: 0f
            val centerShift = axisY - (inkTop + inkBottom) / 2f
            val placements = rawPlacements.map { placement ->
                placement.copy(
                    baselineY = placement.baselineY + centerShift,
                    inkBounds = placement.inkBounds.translated(0f, centerShift),
                )
            }
            val advance = placedConstruction?.width ?: baseRun.width
            val box = geometryExtents(advance, placements, emptyList(), node.range)
            val achievedAdvance = construction?.let {
                delimiterMathFont.scaleDesignUnits(it.advanceMeasurement, delimiterFontSize)
            } ?: baseRun.ascent + baseRun.descent
            val inkHeight = box.inkBounds.height
            val coversStackTop = box.inkBounds.top <= stack.inkBounds.top + GEOMETRY_EPSILON_PX
            val coversStackBottom = box.inkBounds.bottom + GEOMETRY_EPSILON_PX >= stack.inkBounds.bottom
            if ((construction != null && !construction.reachesTarget) ||
                achievedAdvance + GEOMETRY_EPSILON_PX < targetHeight
            ) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MathVariantTooShort,
                    "$side parenthesis construction does not cover the binomial target",
                    node.range,
                    DiagnosticSeverity.Warning,
                )
            }
            decision(
                "BinomialDelimiter",
                node.range,
                "side" to side,
                "style" to style,
                "delimiterStyle" to delimiterStyle,
                "baseGlyphId" to baseGlyphId,
                "construction" to (construction?.kind ?: "BaseGlyph"),
                "targetPolicy" to "LaTeX2eXeTeXGenfracFixedStyleTarget",
                "targetSource" to "amsmath-genfrac-XeTeX-fallback-no-OpenType-delim1-delim2",
                "targetEmFactor" to targetEmFactor,
                "targetReferenceFontSizePx" to targetReferenceSize,
                "delimiterFontSizePx" to delimiterFontSize,
                "delimiterAxisPolicy" to "InnerTextStyleVarDelimiterAxis",
                "axisY" to axisY,
                "boundsSource" to (componentRuns?.joinToString(",") { it.second.boundsSource.toString() }
                    ?: baseRun.boundsSource.toString()),
                "delimitedSubFormulaMinHeightUsed" to false,
                "targetPx" to targetHeight,
                "achievedAdvancePx" to achievedAdvance,
                "reachesTarget" to (achievedAdvance + GEOMETRY_EPSILON_PX >= targetHeight),
                "inkHeightPx" to inkHeight,
                "stackTopPx" to stack.inkBounds.top,
                "stackBottomPx" to stack.inkBounds.bottom,
                "delimiterTopPx" to box.inkBounds.top,
                "delimiterBottomPx" to box.inkBounds.bottom,
                "stackCoverageRequired" to false,
                "coversStackTop" to coversStackTop,
                "coversStackBottom" to coversStackBottom,
                "extenderRepetitions" to construction?.extenderRepetitions,
                "connectorOverlaps" to construction?.connectorOverlaps,
                "placementOrigin" to (placedConstruction?.placementOrigin ?: "normal-glyph-baseline"),
                "placementPolicy" to (placedConstruction?.placementPolicy ?: "NormalGlyphShaping"),
                "constructionPolicy" to (construction?.constructionPolicy ?: if (assemblyValidation?.valid == false) {
                    "MathMLCore5.3.2FailureAfterInvalidAssembly"
                } else null),
                "assemblyValid" to assemblyValidation?.valid,
                "assemblyInvalidReasons" to assemblyValidation?.invalidReasons,
                "assemblyValidationPolicy" to assemblyValidation?.validationPolicy,
                "assemblySpecificationDivergence" to assemblyValidation?.specificationDivergence,
                "assemblyCheckedConnectionCount" to assemblyValidation?.checkedConnectionCount,
                "uniformConnectorOverlapDesignUnits" to construction?.uniformConnectorOverlap,
                "orthogonalAdvancePx" to construction?.orthogonalAdvancePx,
            )
            return box
        }

        val leftBox = delimiterBox("left", leftConstruction, leftBase)
        val rightBox = delimiterBox("right", rightConstruction, rightBase)
        // The primitive fraction noad contains a null delimiter box on both sides. LaTeX2e's
        // XeTeX genfrac wrapper places real delimiters outside it and cancels those two spaces
        // with explicit negative kerns. The visible stack therefore starts at leftBox.width and
        // the right delimiter starts at leftBox.width + stack.width; ink never changes advance.
        val fractionNoadX = leftBox.width - nullDelimiterSpacePx
        val stackX = fractionNoadX + nullDelimiterSpacePx
        val rightX = fractionNoadX + fractionNoad.width - nullDelimiterSpacePx
        val shiftedFractionNoad = fractionNoad.translated(fractionNoadX, 0f)
        val shiftedRight = rightBox.translated(rightX, 0f)
        decision(
            "TeXBinomialFractionNoadPacking",
            node.range,
            "leftDelimiterX" to 0f,
            "leftDelimiterAdvancePx" to leftBox.width,
            "leftNullDelimiterSpacePx" to nullDelimiterSpacePx,
            "leftCancellationKernPx" to -nullDelimiterSpacePx,
            "fractionNoadX" to fractionNoadX,
            "fractionNoadAdvancePx" to fractionNoad.width,
            "stackX" to stackX,
            "stackAdvancePx" to stack.width,
            "rightNullDelimiterSpacePx" to nullDelimiterSpacePx,
            "rightCancellationKernPx" to -nullDelimiterSpacePx,
            "rightDelimiterX" to rightX,
            "rightDelimiterAdvancePx" to rightBox.width,
            "totalAdvancePx" to (rightX + rightBox.width),
            "policy" to "TeXFractionNullDelimiterCancellationNoInkCollisionKern",
        )
        return geometryExtentsPreservingLogicalChildren(
            rightX + rightBox.width,
            leftBox.glyphs + shiftedFractionNoad.glyphs + shiftedRight.glyphs,
            leftBox.rules + shiftedFractionNoad.rules + shiftedRight.rules,
            node.range,
            listOf(
                leftBox to 0f,
                fractionNoad to 0f,
                rightBox to 0f,
            ),
        )
    }

    private fun layoutList(
        list: MathList,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride? = null,
    ): HorizontalLayout {
        val raw = flattenListChildren(list, style, alphabetOverride)
        val classes = raw.map { it.laid.atomClass }.toMutableList()
        val noadIndices = raw.indices.filter { raw[it].participatesInNoadSpacing }
        for (position in noadIndices.indices) {
            val index = noadIndices[position]
            val previousIndex = noadIndices.getOrNull(position - 1)
            val previous = previousIndex?.let(classes::get)
            val current = classes[index]
            if (previous == MathAtomClass.Binary && current in binaryRightCanceller) {
                classes[checkNotNull(previousIndex)] = MathAtomClass.Ordinary
            }
            val resolvedPrevious = previousIndex?.let(classes::get)
            if (current == MathAtomClass.Binary && (resolvedPrevious == null || resolvedPrevious in binaryLeftCanceller)) {
                classes[index] = MathAtomClass.Ordinary
            }
        }
        noadIndices.lastOrNull()?.let { last ->
            if (classes[last] == MathAtomClass.Binary) classes[last] = MathAtomClass.Ordinary
        }
        raw.indices.forEach { index ->
            if (raw[index].laid.atomClass != classes[index]) {
                decision(
                    "TeXBinaryAtomReclassification",
                    raw[index].node.range,
                    "from" to raw[index].laid.atomClass,
                    "to" to classes[index],
                    "listRange" to "${list.range.start}..${list.range.endExclusive}",
                )
            }
        }

        val spacedItems = raw.mapIndexed { index, item ->
            val leftClass = noadIndices.lastOrNull { it < index }?.let(classes::get)
            val rightClass = classes[index]
            val glue = if (!item.participatesInNoadSpacing || leftClass == null) {
                MathGlueAdjustment.Zero
            } else {
                atomGlue(leftClass, rightClass, item.laid.style, item.node.range)
            }
            item.copy(glueBefore = glue, atomClass = rightClass)
        }
        val items = spacedItems.mapIndexed { index, item ->
            val rightClass = noadIndices.firstOrNull { it > index }?.let(classes::get)
            val correction = item.laid.italicCorrectionPx.coerceAtLeast(0f)
            if (correction > 0f) {
                decision(
                    "OpenTypeItalicCorrectionBoundary",
                    item.node.range,
                    "rightClass" to rightClass,
                    "correctionPx" to correction,
                    "owner" to when (item.node) {
                        is MathList -> "compatible-ord-run-final-glyph"
                        is MathOperator -> "operator-noad"
                        else -> "character-noad"
                    },
                    "policy" to "nucleus-owned-not-next-atom-classified",
                )
            }
            item.copy(trailingItalicCorrectionPx = correction)
        }
        var x = 0f
        val glyphs = mutableListOf<MathGlyphPlacement>()
        val rules = mutableListOf<MathRulePlacement>()
        items.forEach { item ->
            x += item.leadingKernPx
            x += item.glueBefore.naturalPx
            val shifted = item.laid.box.translated(x, 0f)
            glyphs += shifted.glyphs
            rules += shifted.rules
            x += item.laid.box.width + item.trailingItalicCorrectionPx
        }
        val box = geometryExtentsPreservingLogicalChildren(
            x.coerceAtLeast(0f),
            glyphs,
            rules,
            list.range,
            items.map { it.laid.box to 0f },
        )
        val atomClass = items.singleOrNull()?.atomClass ?: MathAtomClass.Ordinary
        return HorizontalLayout(
            LaidNode(
                list,
                box,
                atomClass,
                0f,
                style,
                items.singleOrNull()?.laid?.scriptBaseKind ?: ScriptBaseKind.CompoundBox,
            ),
            items,
        )
    }

    private fun flattenListChildren(
        list: MathList,
        initialStyle: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): List<HorizontalItem> = layoutPendingItems(
        flattenPendingListChildren(list, initialStyle, alphabetOverride),
    )

    private fun flattenPendingListChildren(
        list: MathList,
        initialStyle: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): List<PendingHorizontalItem> {
        var currentStyle = initialStyle
        var currentAlphabetOverride = alphabetOverride
        var currentPaintColor: MathPaintColor? = null
        return buildList {
            list.children.forEach { child ->
                if (child is MathStyleDeclaration) {
                    val nextStyle = styleForLevel(child.requestedLevel)
                    decision(
                        "TeXMathStyleDeclaration",
                        child.range,
                        "from" to currentStyle,
                        "to" to nextStyle,
                        "listRange" to "${list.range.start}..${list.range.endExclusive}",
                    )
                    currentStyle = nextStyle
                } else if (child is MathAlphabetDeclaration) {
                    currentAlphabetOverride = MathAlphabetOverride(child.family, child.alphabet)
                    decision(
                        "TeXMathAlphabetDeclaration",
                        child.range,
                        "family" to child.family,
                        "alphabet" to child.alphabet,
                        "listRange" to "${list.range.start}..${list.range.endExclusive}",
                        "policy" to "LegacyTeXListDeclaration",
                    )
                } else if (child is MathColorDeclaration) {
                    currentPaintColor = child.color
                    decision(
                        "XColorMathDeclaration",
                        child.range,
                        "sourceName" to child.sourceName,
                        "commandRange" to child.commandRange,
                        "nameRange" to child.nameRange,
                        "resolvedArgb" to child.color.argb.toUInt().toString(16).padStart(8, '0'),
                        "listRange" to "${list.range.start}..${list.range.endExclusive}",
                        "scopePolicy" to "TeXDeclarationUntilCurrentMathListGroupEnd",
                        "resolutionPolicy" to "XColorBaseNamesPlusCaseInsensitiveDvipsRoyalBlueCompatibility",
                    )
                } else {
                    addAll(flattenPendingHorizontal(child, currentStyle, currentAlphabetOverride, currentPaintColor))
                }
            }
        }
    }

    private fun flattenPendingHorizontal(
        node: MathNode,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
        paintColor: MathPaintColor?,
    ): List<PendingHorizontalItem> = when (node) {
        is MathAlphabetScope -> {
            val override = MathAlphabetOverride(node.family, node.alphabet)
            decision(
                "TeXMathAlphabetScope",
                node.range,
                "family" to node.family,
                "alphabet" to node.alphabet,
                "appliesTo" to MathFamilyBinding.Variable,
            )
            when (val body = node.body) {
                is MathGroup -> flattenPendingListChildren(body.body, style, override).map {
                    it.copy(paintColor = it.paintColor ?: paintColor)
                }
                is MathList -> flattenPendingListChildren(body, style, override).map {
                    it.copy(paintColor = it.paintColor ?: paintColor)
                }
                else -> listOf(PendingHorizontalItem(body, style, override, paintColor))
            }
        }
        else -> listOf(PendingHorizontalItem(node, style, alphabetOverride, paintColor))
    }

    private fun layoutPendingItems(pending: List<PendingHorizontalItem>): List<HorizontalItem> {
        var index = 0
        while (index < pending.size) {
            val first = pending[index]
            val firstSymbol = first.node as? MathSymbol
            if (firstSymbol?.atomClass != MathAtomClass.Ordinary) {
                index += 1
                continue
            }
            val request = symbolRequest(firstSymbol, first.style, first.alphabetOverride)
            var endExclusive = index + 1
            while (endExclusive < pending.size) {
                val candidate = pending[endExclusive]
                val symbol = candidate.node as? MathSymbol ?: break
                if (
                    symbol.atomClass != MathAtomClass.Ordinary ||
                    candidate.style != first.style ||
                    candidate.paintColor != first.paintColor
                ) break
                val candidateRequest = symbolRequest(symbol, candidate.style, candidate.alphabetOverride)
                if (candidateRequest.family != request.family || candidateRequest.alphabet != request.alphabet) break
                endExclusive += 1
            }
            if (endExclusive - index >= 2) {
                decision(
                    "XeTeXNativeMathOrdNoadSequence",
                    pending[index].node.range.cover(pending[endExclusive - 1].node.range),
                    "noadCount" to (endExclusive - index),
                    "family" to request.family,
                    "alphabet" to request.alphabet,
                    "style" to first.style,
                    "shapingPolicy" to "OneNativeMathGlyphFieldPerSourceNoad",
                    "italicCorrectionPolicy" to "EachCompletedNoadOwnsItsCorrection",
                )
            }
            index = endExclusive
        }
        return pending.map { it.layoutIndividually() }
    }

    private fun PendingHorizontalItem.layoutIndividually(): HorizontalItem {
        val laid = layoutNode(node, style, alphabetOverride).let { result ->
            if (paintColor == null) result else result.copy(box = result.box.withInheritedPaintColor(paintColor))
        }
        return HorizontalItem(
            node = node,
            laid = laid,
            glueBefore = MathGlueAdjustment.Zero,
            atomClass = MathAtomClass.Ordinary,
            participatesInNoadSpacing = node !is MathExplicitSpace,
            leadingKernPx = laid.horizontalKernPx,
        )
    }

    private fun atomGlue(
        left: MathAtomClass,
        right: MathAtomClass,
        rightStyle: MathStyle,
        range: SourceRange,
    ): MathGlueAdjustment {
        val tight = rightStyle.level == MathStyleLevel.Script || rightStyle.level == MathStyleLevel.ScriptScript
        val kind = TeXMathSpacing.kind(left, right, tight)
        val priority = adjustmentPriority(left, right)
        val mu = fontSize(rightStyle) / 18f
        // TeX's thinmuskip/thickmuskip are shrink-free, but an inline formula justified inside a CJK
        // line lets its relation and punctuation spaces fully compress (shrink to zero, like binary's
        // medmuskip) as well as stretch, so a break-trailing space is equally shrinkable and
        // discardable. Deliberate deviation from TeX; see MathGeometryAuditTest.
        val glue = when (kind) {
            MathGlueKind.None -> MathGlueAdjustment.Zero
            MathGlueKind.Thin -> if (priority == MathAdjustmentPriority.Punctuation) {
                glue(kind, 3f * mu, 0f, 6f * mu, priority)
            } else {
                glue(kind, 3f * mu, 3f * mu, 3f * mu, priority)
            }
            MathGlueKind.Medium -> glue(kind, 4f * mu, 0f, 6f * mu, priority)
            MathGlueKind.Thick -> glue(kind, 5f * mu, 0f, 10f * mu, priority)
        }
        decision(
            "TeXMathAtomSpacing",
            range,
            "left" to left,
            "right" to right,
            "style" to rightStyle,
            "table" to if (tight) "tight" else "display-text",
            "kind" to kind,
            "naturalPx" to glue.naturalPx,
            "minimumPx" to glue.minimumPx,
            "maximumPx" to glue.maximumPx,
            "priority" to glue.priority,
        )
        return glue
    }

    private fun glue(
        kind: MathGlueKind,
        natural: Float,
        minimum: Float,
        maximum: Float,
        priority: MathAdjustmentPriority,
    ): MathGlueAdjustment = MathGlueAdjustment(
        kind,
        natural,
        minimum,
        maximum,
        natural - minimum,
        maximum - natural,
        priority,
    )

    private fun adjustmentPriority(left: MathAtomClass, right: MathAtomClass?): MathAdjustmentPriority = when {
        left == MathAtomClass.Punctuation -> MathAdjustmentPriority.Punctuation
        left == MathAtomClass.Relation || right == MathAtomClass.Relation -> MathAdjustmentPriority.Relation
        left == MathAtomClass.Binary || right == MathAtomClass.Binary -> MathAdjustmentPriority.BinaryOperator
        else -> MathAdjustmentPriority.Other
    }

    private fun fontSize(style: MathStyle): Float = when (style.level) {
        MathStyleLevel.Display, MathStyleLevel.Text -> baseFontSizePx
        MathStyleLevel.Script -> baseFontSizePx * constants.scriptPercentScaleDown / 100f
        MathStyleLevel.ScriptScript -> baseFontSizePx * constants.scriptScriptPercentScaleDown / 100f
    }

    private fun scale(designUnits: Int, style: MathStyle): Float =
        glyphSource.mathFont.scaleDesignUnits(designUnits, fontSize(style))

    private fun styleForLevel(level: MathStyleLevel): MathStyle = when (level) {
        MathStyleLevel.Display -> MathStyle.Display
        MathStyleLevel.Text -> MathStyle.Text
        MathStyleLevel.Script -> MathStyle.Script
        MathStyleLevel.ScriptScript -> MathStyle.ScriptScript
    }

    private fun formulaLineMetrics(box: MathBox, style: MathStyle): MathFormulaLineMetrics {
        val size = fontSize(style)
        val metrics = glyphSource.mathFont.lineMetrics
        val fontAscent = glyphSource.mathFont.scaleDesignUnits(metrics.typoAscender, size).coerceAtLeast(0f)
        val fontDescent = (-glyphSource.mathFont.scaleDesignUnits(metrics.typoDescender, size)).coerceAtLeast(0f)
        val lineGap = glyphSource.mathFont.scaleDesignUnits(metrics.typoLineGap, size).coerceAtLeast(0f)
        val mathLeading = scale(constants.mathLeading, style).coerceAtLeast(0f)
        val inkAscent = (-box.inkBounds.top).coerceAtLeast(0f)
        val inkDescent = box.inkBounds.bottom.coerceAtLeast(0f)
        val hostContentAscent = max(box.ascent, inkAscent)
        val hostContentDescent = max(box.descent, inkDescent)
        return MathFormulaLineMetrics(
            fontAscentPx = fontAscent,
            fontDescentPx = fontDescent,
            fontLineGapPx = lineGap,
            mathLeadingPx = mathLeading,
            inkAscentPx = inkAscent,
            inkDescentPx = inkDescent,
            logicalAscentPx = max(fontAscent + lineGap, hostContentAscent + mathLeading),
            logicalDescentPx = max(fontDescent, hostContentDescent),
        )
    }

    private fun geometryExtents(
        width: Float,
        glyphs: List<MathGlyphPlacement>,
        rules: List<MathRulePlacement>,
        range: SourceRange,
        constructionPaintGroups: List<MathConstructionPaintGroup> = emptyList(),
    ): MathBox {
        val cleanEvidence = mutableSetOf<MathTeXCleanBoxEvidence>()
        val cleanGlyphs = glyphs.map { placement ->
            if (mathFontForFaceOrNull(placement.faceId) == null) {
                cleanEvidence += MathTeXCleanBoxEvidence.GlyphOutline
                return@map placement
            }
            val measured = measureGlyphOutlineForFace(
                placement.faceId,
                placement.glyphId,
                placement.fontSizePx,
                placement.style,
                placement.sourceRange,
            )
            cleanEvidence += if (measured.boundsSource == MathGlyphBoundsSource.Outline) {
                MathTeXCleanBoxEvidence.GlyphOutline
            } else {
                MathTeXCleanBoxEvidence.FontReportedGlyphBounds
            }
            val glyph = measured.glyphs.singleOrNull() ?: return@map placement
            placement.copy(inkBounds = glyph.inkBounds.translated(placement.x, placement.baselineY))
        }
        if (rules.isNotEmpty()) cleanEvidence += MathTeXCleanBoxEvidence.RuleGeometry
        if (cleanGlyphs.isEmpty() && rules.isEmpty()) cleanEvidence += MathTeXCleanBoxEvidence.Empty
        val left = minOf(
            glyphs.minOfOrNull { it.inkBounds.left } ?: 0f,
            rules.minOfOrNull { it.left } ?: 0f,
        )
        val top = minOf(
            glyphs.minOfOrNull { it.inkBounds.top } ?: 0f,
            rules.minOfOrNull { it.top } ?: 0f,
        )
        val right = maxOf(
            glyphs.maxOfOrNull { it.inkBounds.right } ?: 0f,
            rules.maxOfOrNull { it.right } ?: 0f,
        )
        val bottom = maxOf(
            glyphs.maxOfOrNull { it.inkBounds.bottom } ?: 0f,
            rules.maxOfOrNull { it.bottom } ?: 0f,
        )
        val ascent = (-top).coerceAtLeast(0f)
        val descent = bottom.coerceAtLeast(0f)
        val cleanTop = minOf(
            cleanGlyphs.minOfOrNull { it.inkBounds.top } ?: 0f,
            rules.minOfOrNull { it.top } ?: 0f,
        )
        val cleanBottom = maxOf(
            cleanGlyphs.maxOfOrNull { it.inkBounds.bottom } ?: 0f,
            rules.maxOfOrNull { it.bottom } ?: 0f,
        )
        return MathBox(
            width,
            ascent,
            descent,
            MathRect(left, top, right, bottom),
            glyphs,
            rules,
            range,
            constructionPaintGroups.distinctBy { it.id },
            MathTeXCleanBoxMetrics(
                ascent = (-cleanTop).coerceAtLeast(0f),
                descent = cleanBottom.coerceAtLeast(0f),
                policy = MathTeXCleanBoxPolicy.GlyphOutlineUnion,
                evidence = cleanEvidence,
            ),
        )
    }

    /**
     * Compose visual bounds come from glyph/rule ink, while recursive TeX layout must retain
     * logical box extents such as RadicalExtraAscender even when no ink occupies that reserve.
     */
    private fun geometryExtentsPreservingLogicalChildren(
        width: Float,
        glyphs: List<MathGlyphPlacement>,
        rules: List<MathRulePlacement>,
        range: SourceRange,
        children: List<Pair<MathBox, Float>>,
        constructionPaintGroups: List<MathConstructionPaintGroup> = emptyList(),
    ): MathBox {
        val paintGroups = (constructionPaintGroups + children.flatMap { it.first.constructionPaintGroups })
            .distinctBy { it.id }
        val inkBox = geometryExtents(width, glyphs, rules, range, paintGroups)
        val logicalAscent = children.maxOfOrNull { (box, baselineY) ->
            (box.ascent - baselineY).coerceAtLeast(0f)
        } ?: 0f
        val logicalDescent = children.maxOfOrNull { (box, baselineY) ->
            (box.descent + baselineY).coerceAtLeast(0f)
        } ?: 0f
        // Children are the completed TeX box list. Flattened paint must not supersede the
        // child's clean-box metric; side scripts now place these same child boxes directly.
        val cleanAscent = children.maxOfOrNull { (box, baselineY) ->
            (box.texCleanBoxMetrics.ascent - baselineY).coerceAtLeast(0f)
        } ?: inkBox.texCleanBoxMetrics.ascent
        val cleanDescent = children.maxOfOrNull { (box, baselineY) ->
            (box.texCleanBoxMetrics.descent + baselineY).coerceAtLeast(0f)
        } ?: inkBox.texCleanBoxMetrics.descent
        return inkBox.copy(
            // TeX box height/depth come from the positioned child box list. Painted ink may
            // overhang that box and remains available through inkBounds/visual extents; it must
            // not silently become recursive noad reserve or host line-height reserve.
            ascent = logicalAscent,
            descent = logicalDescent,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = cleanAscent,
                descent = cleanDescent,
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = inkBox.texCleanBoxMetrics.evidence +
                    children.flatMap { it.first.texCleanBoxMetrics.evidence } +
                    MathTeXCleanBoxEvidence.CompletedChildBox,
            ),
        )
    }

    private fun emptyBox(range: SourceRange): MathBox = MathBox(
        0f,
        0f,
        0f,
        MathRect(0f, 0f, 0f, 0f),
        emptyList(),
        emptyList(),
        range,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            0f,
            0f,
            MathTeXCleanBoxPolicy.GlyphOutlineUnion,
            setOf(MathTeXCleanBoxEvidence.Empty),
        ),
    )

    private fun decision(name: String, range: SourceRange, vararg details: Pair<String, Any?>) {
        decisions += MathLayoutDecision(name, range, details.associate { it.first to it.second.toString() })
    }

    private fun buildDump(
        source: String,
        mode: MathMode,
        style: MathStyle,
        box: MathBox,
        fragments: List<MathInlineFragment>,
        breaks: List<MathBreakOpportunity>,
        lineMetrics: MathFormulaLineMetrics,
        diagnostics: List<MathDiagnostic>,
        decisions: List<MathLayoutDecision>,
    ): String = buildString {
        appendLine("source=$source")
        appendLine("mode=$mode style=$style upm=${glyphSource.mathFont.unitsPerEm}")
        appendLine(
            "math axis=${constants.axisHeight} rule=${constants.fractionRuleThickness} " +
                "script=${constants.scriptPercentScaleDown}/${constants.scriptScriptPercentScaleDown}",
        )
        appendLine(
            "box advance=${box.width} ink=${box.inkBounds.left},${box.inkBounds.top}," +
                "${box.inkBounds.right},${box.inkBounds.bottom} visual=${box.visualLeft}..${box.visualRight}",
        )
        appendLine(
            "line font=${lineMetrics.fontAscentPx}/${lineMetrics.fontDescentPx}/${lineMetrics.fontLineGapPx} " +
                "mathLeading=${lineMetrics.mathLeadingPx} ink=${lineMetrics.inkAscentPx}/${lineMetrics.inkDescentPx} " +
                "logical=${lineMetrics.logicalAscentPx}/${lineMetrics.logicalDescentPx}",
        )
        decisions.forEach { decision ->
            appendLine(
                "decision ${decision.name} range=${decision.range.start}..${decision.range.endExclusive} " +
                    decision.details.entries.joinToString(" ") { "${it.key}=${it.value}" },
            )
        }
        box.glyphs.forEachIndexed { index, glyph ->
            appendLine(
                "glyph[$index] id=${glyph.glyphId} range=${glyph.sourceRange.start}..${glyph.sourceRange.endExclusive} " +
                    "style=${glyph.style} size=${glyph.fontSizePx} x=${glyph.x} baseline=${glyph.baselineY} " +
                    "face=${glyph.faceId} class=${glyph.fontClass} weight=${glyph.requestedWeight}->${glyph.resolvedWeight} " +
                    "mathFallback=${glyph.fallbackReason} " +
                    "hostDecision=${glyph.hostTextDecision} " +
                    "ink=${glyph.inkBounds.left},${glyph.inkBounds.top},${glyph.inkBounds.right},${glyph.inkBounds.bottom} " +
                    "constructionGroup=${glyph.constructionGroupId}",
            )
        }
        box.rules.forEachIndexed { index, rule ->
            appendLine(
                "rule[$index] ${rule.left},${rule.top},${rule.right},${rule.bottom} " +
                    "layer=${rule.paintLayer} role=${rule.paintRole} color=${rule.paintColor} " +
                    "constructionGroup=${rule.constructionGroupId}",
            )
        }
        box.constructionPaintGroups.forEach { group ->
            appendLine(
                "constructionPaintGroup[${group.id}] kind=${group.kind} shape=${group.shapeKind} " +
                    "face=${group.faceId} " +
                    "range=${group.sourceRange.start}..${group.sourceRange.endExclusive} " +
                    "outlinePolicy=${group.outlinePolicy}",
            )
        }
        fragments.forEach { fragment ->
            appendLine(
                "fragment[${fragment.index}] range=${fragment.sourceRange.start}..${fragment.sourceRange.endExclusive} " +
                    "advance=${fragment.box.width} ink=${fragment.box.inkBounds.left}..${fragment.box.inkBounds.right} " +
                    "leadingKern=${fragment.leadingKernPx} " +
                    "italicCorrection=${fragment.trailingItalicCorrectionPx} " +
                    "glue=${fragment.trailingGlue.kind}/${fragment.trailingGlue.naturalPx}/" +
                    "${fragment.trailingGlue.minimumPx}/${fragment.trailingGlue.maximumPx}/" +
                    "${fragment.trailingGlue.priority}",
            )
        }
        breaks.forEach { opportunity ->
            appendLine(
                "break after=${opportunity.afterFragmentIndex} offset=${opportunity.sourceOffset} " +
                    "kind=${opportunity.kind} priority=${opportunity.priority} " +
                    "discard=${opportunity.discardedTrailingGlue.naturalPx}",
            )
        }
        diagnostics.forEach { diagnostic ->
            appendLine(
                "diagnostic ${diagnostic.severity}/${diagnostic.code} " +
                    "range=${diagnostic.range.start}..${diagnostic.range.endExclusive}",
            )
        }
    }

    private data class LaidNode(
        val node: MathNode,
        val box: MathBox,
        val atomClass: MathAtomClass,
        val italicCorrectionPx: Float,
        val style: MathStyle,
        val scriptBaseKind: ScriptBaseKind,
        val horizontalKernPx: Float = 0f,
    )

    private data class StackedLimitsPlacement(
        val box: MathBox,
        val base: LaidNode,
        val upper: LaidNode?,
        val lower: LaidNode?,
        val upperGapMin: Float,
        val upperBaselineRiseMin: Float,
        val lowerGapMin: Float,
        val lowerBaselineDropMin: Float,
        val upperOuterPadding: Float,
        val lowerOuterPadding: Float,
        val upperShift: Float?,
        val lowerShift: Float?,
        val halfItalicCorrection: Float,
        val logicalWidth: Float,
        val baseX: Float,
        val upperX: Float?,
        val lowerX: Float?,
    ) {
        val actualUpperGap: Float?
            get() = upper?.let { upperShift!! - base.box.ascent - it.box.descent }
        val actualUpperRise: Float?
            get() = upperShift?.minus(base.box.ascent)
        val actualLowerGap: Float?
            get() = lower?.let { lowerShift!! - base.box.descent - it.box.ascent }
        val actualLowerDrop: Float?
            get() = lowerShift?.minus(base.box.descent)

    }

    private data class AccentAttachmentEvidence(
        val valuePx: Float,
        val policy: String,
        val ignoredDeviceAdjustment: MathDeviceAdjustment? = null,
    )

    private data class MeasurementLayoutNode(
        val node: LaidNode,
        val diagnostics: List<MathDiagnostic>,
        val decisions: List<MathLayoutDecision>,
    )

    private data class AmsmathArrowFaceEvidence(
        val faceId: MathFaceId,
        val head: MeasuredOutlineConstructionRun,
        val relbar: MeasuredOutlineConstructionRun,
    )

    private data class DelimiterTargetEvidence(
        val innerCleanAscentPx: Float,
        val innerCleanDescentPx: Float,
        val axisHeightPx: Float,
        val maxAxisDistancePx: Float,
        val factor: Int,
        val shortfallPx: Float,
        val factorTargetPx: Float,
        val shortfallTargetPx: Float,
        val targetPx: Float,
        val targetPolicy: String = "XeTeXMakeLeftRightAxisFactorShortfall",
        val fixedSize: MathFixedDelimiterSize? = null,
        val amsmathFactor: Float? = null,
        val mathStrutAscentPx: Float? = null,
        val mathStrutDescentPx: Float? = null,
        val bigSizePx: Float? = null,
        val requestedExtentPx: Float? = null,
        val vcenterAscentPx: Float? = null,
        val vcenterDescentPx: Float? = null,
    )

    private data class PlacedVerticalConstruction(
        val width: Float,
        val glyphs: List<MathGlyphPlacement>,
        val boxAscentPx: Float,
        val boxDescentPx: Float,
        val topStrokeEvidence: MathConstructionOutlineEvidence.Available?,
        val outlineEvidenceFailure: MathConstructionOutlineUnavailableReason?,
        val componentHorizontalOriginsPx: List<Float>,
        val componentBottomOriginsPx: List<Float>,
        val componentBaselineOriginsPx: List<Float>,
        val placementOrigin: String,
        val placementPolicy: String,
    )

    private data class HorizontalItem(
        val node: MathNode,
        val laid: LaidNode,
        val glueBefore: MathGlueAdjustment,
        val atomClass: MathAtomClass,
        val leadingKernPx: Float = 0f,
        val trailingItalicCorrectionPx: Float = 0f,
        /** False for explicit glue/kern nodes, which never become TeX noads or alter Bin repair. */
        val participatesInNoadSpacing: Boolean = true,
    )

    private data class PendingHorizontalItem(
        val node: MathNode,
        val style: MathStyle,
        val alphabetOverride: MathAlphabetOverride?,
        val paintColor: MathPaintColor?,
    )

    private data class HorizontalLayout(
        val laid: LaidNode,
        val items: List<HorizontalItem>,
    )

    private data class MathAlphabetOverride(
        val family: MathFamily? = null,
        val alphabet: MathAlphabet? = null,
        val version: MathVersion? = null,
    )

    private data class OperatorLimitsSemantics(
        val identity: String,
        val declaredPolicy: MathLimitsPolicy,
        val explicit: Boolean,
        val modifierRange: SourceRange?,
        val sideScriptHorizontalPolicy: SideScriptHorizontalPolicy,
        val sideScriptGeometry: String,
    )

    private companion object {
        const val GEOMETRY_EPSILON_PX = 0.02f
        const val TEX_MU_PER_EM = 18f
        const val LATEX_XETEX_GENFRAC_DISPLAY_DELIMITER_EM = 2.39f
        const val LATEX_XETEX_GENFRAC_TEXT_DELIMITER_EM = 1f
        const val LATEX_XETEX_GENFRAC_SCRIPT_DELIMITER_EM = 1.45f
        const val LATEX_XETEX_GENFRAC_SCRIPT_SCRIPT_DELIMITER_EM = 1.35f
        // Reviewed amsmath/XeTeX showbox policy: matrix/aligned rows use a 0.7em/0.3em strut,
        // cases applies arraystretch=1.2, and aligned inserts its named inter-row separation.
        const val TEX_ARRAY_STRUT_ASCENT_EM = 0.7f
        const val TEX_ARRAY_STRUT_DESCENT_EM = 0.3f
        const val TEX_CASES_STRUT_ASCENT_EM = 0.84f
        const val TEX_CASES_STRUT_DESCENT_EM = 0.36f
        const val TEX_ALIGNED_ROW_GAP_EM = 1f / 6f
        const val TEX_ALIGNED_PAIR_GAP_EM = 2f
        const val TEX_ARRAY_INTERCOLUMN_EM = 1f
        const val TEX_ARRAY_COLUMN_SEPARATION_EM = 0.5f
        const val CSS_PIXELS_PER_INCH = 96f
        const val TEX_POINTS_PER_INCH = 72.27f
        const val BIG_POINTS_PER_INCH = 72f
        const val CENTIMETERS_PER_INCH = 2.54f
        const val MILLIMETERS_PER_INCH = 25.4f
        const val TEX_POINT_TO_PX = CSS_PIXELS_PER_INCH / TEX_POINTS_PER_INCH
        const val AMSMATH_BIG_SIZE_SCALE = 1.2f
        const val DEFAULT_SCRIPT_SPACE_PT = 0.5f
        const val DEFAULT_FBOX_SEPARATION_PT = 3f
        const val DEFAULT_FBOX_RULE_THICKNESS_PT = 0.4f
        const val BIG_POINT_TO_PX = CSS_PIXELS_PER_INCH / BIG_POINTS_PER_INCH

        val binaryLeftCanceller = setOf(
            MathAtomClass.Binary,
            MathAtomClass.Opening,
            MathAtomClass.Relation,
            MathAtomClass.Operator,
            MathAtomClass.Punctuation,
        )
        val binaryRightCanceller = setOf(
            MathAtomClass.Relation,
            MathAtomClass.Closing,
            MathAtomClass.Punctuation,
        )

    }
}

private fun MathConstructionOutlineEvidence.evidenceLabel(): String = when (this) {
    is MathConstructionOutlineEvidence.Available -> "Available($source)"
    is MathConstructionOutlineEvidence.Unavailable -> "Unavailable($reason)"
}

private fun unicodeLabel(scalar: Int): String = "U+${scalar.toString(16).uppercase().padStart(4, '0')}"

private const val DEFAULT_NULL_DELIMITER_SPACE_EM = 0.12f
private const val DEFAULT_DELIMITER_SHORTFALL_EM = 0.5f
private const val RADICAL_SIGN = "\u221A"
private const val AMSMATH_RELBAR = "\u2212"

private fun scalarString(scalar: Int): String = if (scalar <= 0xFFFF) {
    scalar.toChar().toString()
} else {
    val adjusted = scalar - 0x10000
    charArrayOf(
        ((adjusted ushr 10) + 0xD800).toChar(),
        ((adjusted and 0x3FF) + 0xDC00).toChar(),
    ).concatToString()
}

private enum class ScriptBaseKind {
    Character,
    CompoundBox,
    ExtendedShape,
}

private fun MathBox.singleGlyphOrNull(): MathGlyphPlacement? =
    if (rules.isEmpty() && glyphs.size == 1) glyphs.single() else null

private fun MathBox.sideScriptVerticalMetrics(): SideScriptBoxVerticalMetrics =
    SideScriptBoxVerticalMetrics(
        logicalAscentPx = ascent,
        logicalDescentPx = descent,
        inkTopPx = inkBounds.top,
        inkBottomPx = inkBounds.bottom,
    )

private fun MathBox.texCleanSideScriptVerticalMetrics(): SideScriptBoxVerticalMetrics =
    SideScriptBoxVerticalMetrics(
        logicalAscentPx = texCleanBoxMetrics.ascent,
        logicalDescentPx = texCleanBoxMetrics.descent,
        inkTopPx = -texCleanBoxMetrics.ascent,
        inkBottomPx = texCleanBoxMetrics.descent,
    )

private fun MathBox.translated(dx: Float, dy: Float): MathBox = copy(
    inkBounds = inkBounds.translated(dx, dy),
    glyphs = glyphs.map { glyph ->
        glyph.copy(
            x = glyph.x + dx,
            baselineY = glyph.baselineY + dy,
            inkBounds = glyph.inkBounds.translated(dx, dy),
        )
    },
    rules = rules.map { rule ->
        rule.copy(
            left = rule.left + dx,
            right = rule.right + dx,
            top = rule.top + dy,
            bottom = rule.bottom + dy,
        )
    },
)

/** Applies a surrounding color declaration without overwriting a nested declaration. */
private fun MathBox.withInheritedPaintColor(color: MathPaintColor): MathBox = copy(
    glyphs = glyphs.map { glyph ->
        if (glyph.paintColor == null) glyph.copy(paintColor = color) else glyph
    },
    rules = rules.map { rule ->
        if (rule.paintColor == null) rule.copy(paintColor = color) else rule
    },
    constructionPaintGroups = constructionPaintGroups.map { group ->
        if (group.paintColor == null) group.copy(paintColor = color) else group
    },
)

private fun MathBox.withHorizontalKerns(left: Float, right: Float): MathBox {
    require(left >= 0f && right >= 0f) { "math limit kerns must not be negative" }
    val shifted = translated(left, 0f)
    return shifted.copy(width = left + width + right)
}
