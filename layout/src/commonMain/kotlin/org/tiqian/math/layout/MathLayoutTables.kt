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
import org.tiqian.math.layout.MathLayoutPass.Companion.BIG_POINT_TO_PX
import org.tiqian.math.layout.MathLayoutPass.Companion.CENTIMETERS_PER_INCH
import org.tiqian.math.layout.MathLayoutPass.Companion.CSS_PIXELS_PER_INCH
import org.tiqian.math.layout.MathLayoutPass.Companion.MILLIMETERS_PER_INCH
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_ALIGNED_PAIR_GAP_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_ALIGNED_ROW_GAP_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_ARRAY_COLUMN_SEPARATION_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_ARRAY_INTERCOLUMN_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_ARRAY_STRUT_ASCENT_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_ARRAY_STRUT_DESCENT_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_CASES_STRUT_ASCENT_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_CASES_STRUT_DESCENT_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_POINT_TO_PX
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_SMALL_MATRIX_LINE_SKIP_EM
import org.tiqian.math.layout.MathLayoutPass.DelimiterTargetEvidence
import org.tiqian.math.layout.MathLayoutPass.LaidNode
import org.tiqian.math.layout.MathLayoutPass.MathAlphabetOverride

internal fun MathLayoutPass.layoutTable(
    node: MathTable,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val substack = node.environment == MathTableEnvironment.Substack
    val smallMatrix = node.environment == MathTableEnvironment.SmallMatrix
    val gathered = node.environment == MathTableEnvironment.Gathered
    val preservesEntryStyle = node.environment in setOf(
        MathTableEnvironment.Aligned,
        MathTableEnvironment.Split,
    )
    val cellStyle = if (substack || smallMatrix) {
        MathStyle.Script
    } else if (gathered) {
        MathStyle.Display
    } else if (preservesEntryStyle) {
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
            MathTableEnvironment.Gathered -> MathTableColumnAlignment.Center
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
    val minimumRowAscent = if (substack || smallMatrix) 0f else strutAscentEm * size
    val minimumRowDescent = if (substack || smallMatrix) 0f else strutDescentEm * size
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
            MathTableEnvironment.SmallMatrix,
            MathTableEnvironment.ParenthesizedMatrix,
            MathTableEnvironment.BracketedMatrix,
            MathTableEnvironment.Determinant,
            MathTableEnvironment.Array,
            -> if (smallMatrix) {
                5f * fontSize(style) / 18f
            } else {
                arrayColumnSeparation * 2f
            }

            MathTableEnvironment.Aligned,
            MathTableEnvironment.Split,
            -> if (boundary % 2 == 1) TEX_ALIGNED_PAIR_GAP_EM * size else 0f

            MathTableEnvironment.Substack -> 0f

            else -> TEX_ARRAY_INTERCOLUMN_EM * size
        }
    }
    val rowGapEm = if (preservesEntryStyle && node.rows.size > 1) TEX_ALIGNED_ROW_GAP_EM else 0f
    val baseRowGap = if (substack) {
        scale(constants.stackGapMin, MathStyle.Script)
    } else if (smallMatrix) {
        TEX_SMALL_MATRIX_LINE_SKIP_EM * fontSize(style)
    } else if (gathered && node.rows.size > 1) {
        TEX_ALIGNED_ROW_GAP_EM * fontSize(style)
    } else {
        rowGapEm * size
    }
    val rowGaps = List((node.rows.size - 1).coerceAtLeast(0)) { rowIndex ->
        baseRowGap + if (preservesEntryStyle) rowAdditionalSpacingPx[rowIndex] else 0f
    }
    val trailingExplicitRowSpacing = if (preservesEntryStyle) {
        rowAdditionalSpacingPx.lastOrNull() ?: 0f
    } else {
        0f
    }
    val horizontalRulesByBoundary = node.horizontalRules.groupBy { it.boundaryIndex }
    val horizontalRuleTotalHeight = node.horizontalRules.size * arrayRuleThicknessPx
    val outerPadding = when {
        node.environment == MathTableEnvironment.Array -> arrayColumnSeparation
        smallMatrix -> 3f * fontSize(style) / 18f
        else -> 0f
    }
    val bodyWidth = outerPadding * 2f + columnWidths.sum() + columnGaps.sum()
    val bodyHeight = rowAscents.zip(rowDescents).sumOf { (ascent, descent) ->
        (ascent + descent).toDouble()
    }.toFloat() + rowGaps.sum() + trailingExplicitRowSpacing + horizontalRuleTotalHeight
    val axisHeight = scale(constants.axisHeight, style)
    val bodyTop = -axisHeight - bodyHeight / 2f
    var rowTop = bodyTop
    val glyphs = mutableListOf<MathGlyphPlacement>()
    val hostTextRuns = mutableListOf<MathHostTextPlacement>()
    val rules = mutableListOf<MathRulePlacement>()
    val paintGroups = mutableListOf<MathConstructionPaintGroup>()
    val positionedChildren = mutableListOf<Pair<MathBox, Float>>()
    val rowBaselines = mutableListOf<Float>()
    fun placeHorizontalRules(boundaryIndex: Int) {
        horizontalRulesByBoundary[boundaryIndex].orEmpty().forEach { horizontalRule ->
            rules += MathRulePlacement(
                left = 0f,
                top = rowTop,
                right = bodyWidth,
                bottom = rowTop + arrayRuleThicknessPx,
                sourceRange = horizontalRule.commandRange,
            )
            decision(
                "LaTeXArrayHorizontalRule",
                horizontalRule.commandRange,
                "environment" to node.environment,
                "boundaryIndex" to boundaryIndex,
                "leftPx" to 0f,
                "rightPx" to bodyWidth,
                "topPx" to rowTop,
                "bottomPx" to (rowTop + arrayRuleThicknessPx),
                "thicknessPx" to arrayRuleThicknessPx,
                "parameter" to "arrayRuleThicknessPx",
                "policy" to "LaTeXArrayHlineFullPreambleWidth",
            )
            rowTop += arrayRuleThicknessPx
        }
    }
    rowLayouts.forEachIndexed { rowIndex, row ->
        placeHorizontalRules(rowIndex)
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
            hostTextRuns += shifted.hostTextRuns
            rules += shifted.rules
            paintGroups += shifted.constructionPaintGroups
            positionedChildren += cell to baselineY
            columnLeft += columnWidths[column] + columnGaps.getOrElse(column) { 0f }
        }
        rowTop += rowAscents[rowIndex] + rowDescents[rowIndex] +
            rowGaps.getOrElse(rowIndex) { 0f }
    }
    placeHorizontalRules(node.rows.size)
    val bodyBottom = bodyTop + bodyHeight
    val paintedBody = geometryExtents(
        bodyWidth,
        glyphs,
        rules,
        node.range,
        paintGroups,
        hostTextRuns,
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
        "substackRowGapParameter" to if (substack) "fontdimen10-scriptfont-symbols/StackGapMin" else null,
        "smallMatrixOuterPaddingMu" to if (smallMatrix) 3f else null,
        "smallMatrixInterColumnMu" to if (smallMatrix) 5f else null,
        "smallMatrixLineSkipEm" to if (smallMatrix) TEX_SMALL_MATRIX_LINE_SKIP_EM else null,
        "rowAdditionalSpacingPx" to rowAdditionalSpacingPx.joinToString(","),
        "rowGapsPx" to rowGaps.joinToString(","),
        "trailingExplicitRowSpacingPx" to trailingExplicitRowSpacing,
        "rowSpacingPolicy" to if (preservesEntryStyle) {
            "AmsmathExtraInterRowGlue"
        } else {
            "LaTeXArrayPreviousRowStrutDepthExtension"
        },
        "horizontalRuleCount" to node.horizontalRules.size,
        "horizontalRuleBoundaries" to node.horizontalRules.joinToString(",") { it.boundaryIndex.toString() },
        "arrayRuleThicknessPx" to arrayRuleThicknessPx,
        "horizontalRulePolicy" to "LaTeXArrayHlineFullPreambleWidth",
        "bodyWidthPx" to bodyWidth,
        "bodyAscentPx" to bodyBox.ascent,
        "bodyDescentPx" to bodyBox.descent,
        "logicalAdvancePx" to completed.width,
        "groupBreakPolicy" to "UnbreakableTeXTableInnerNoad",
        "policy" to when {
            substack -> "AmsmathSubarrayScriptStyleFontdimen10BaselineSkipAndAxisCenteredVcenter"
            smallMatrix -> "AmsmathSmallMatrixScriptStyleThreeMuOuterFiveMuColumnsAndLineSkip"
            gathered -> "AmsmathGatheredDisplayStyleCenteredRowsAndArrayStrut"
            else -> "LaTeXEnvironmentSpecificStyleArrayStrutAndAxisCenteredVcenter"
        },
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

internal fun MathLayoutPass.layoutDisplayEnvironment(
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

internal fun MathLayoutPass.layoutDisplayRows(
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

internal fun MathLayoutPass.layoutTaggedEquation(
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

internal fun MathLayoutPass.layoutMisplacedEquationTag(node: MathEquationTag, style: MathStyle): LaidNode {
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

private fun MathLayoutPass.completeTaggedRows(
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
    val hostTextRuns = shiftedBody.hostTextRuns.toMutableList()
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
        hostTextRuns += shifted.hostTextRuns
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
        hostTextRuns,
    )
}

private fun MathLayoutPass.completeTaggedEquationBox(
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
        shiftedBody.hostTextRuns + shiftedTag.hostTextRuns,
    )
}

private fun MathLayoutPass.resolvedEquationTagDisplayWidth(range: SourceRange): Float? {
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

private fun MathLayoutPass.checkEquationTagFit(
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

private fun MathLayoutPass.layoutEquationTagBox(tag: MathEquationTag, style: MathStyle): MathBox {
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

private fun MathLayoutPass.equationTagDecision(
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

private fun MathLayoutPass.resolveTeXDimension(dimension: MathTeXDimension, emSizePx: Float): Float {
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

private fun MathLayoutPass.wrapTableDelimiters(
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
    val hostTextRuns = mutableListOf<MathHostTextPlacement>()
    val rules = mutableListOf<MathRulePlacement>()
    val groups = mutableListOf<MathConstructionPaintGroup>()
    fun append(box: MathBox?) {
        if (box == null) return
        val shifted = box.translated(x, 0f)
        glyphs += shifted.glyphs
        hostTextRuns += shifted.hostTextRuns
        rules += shifted.rules
        groups += shifted.constructionPaintGroups
        x += box.width
    }
    append(left)
    append(body)
    append(right)
    val painted = geometryExtents(x, glyphs, rules, node.range, groups, hostTextRuns)
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
