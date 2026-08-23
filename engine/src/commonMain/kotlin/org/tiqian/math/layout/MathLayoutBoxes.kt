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
import org.tiqian.math.layout.MathLayoutPass.Companion.CANCEL_TALL_SLOPES
import org.tiqian.math.layout.MathLayoutPass.Companion.CANCEL_TALL_WIDTH_FACTORS
import org.tiqian.math.layout.MathLayoutPass.Companion.CANCEL_WIDE_SLOPES
import org.tiqian.math.layout.MathLayoutPass.Companion.CENTIMETERS_PER_INCH
import org.tiqian.math.layout.MathLayoutPass.Companion.CSS_PIXELS_PER_INCH
import org.tiqian.math.layout.MathLayoutPass.Companion.MILLIMETERS_PER_INCH
import org.tiqian.math.layout.MathLayoutPass.Companion.NEGATED_RELATION_SCALARS
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_POINT_TO_PX
import org.tiqian.math.layout.MathLayoutPass.CancelStrokeGeometry
import org.tiqian.math.layout.MathLayoutPass.LaidNode
import org.tiqian.math.layout.MathLayoutPass.MathAlphabetOverride

internal fun MathLayoutPass.layoutExplicitSpace(node: MathExplicitSpace, style: MathStyle): LaidNode {
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

internal fun MathLayoutPass.layoutNegation(
    node: MathNegation,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val base = node.base as? MathSymbol
    val negatedScalar = base?.identity?.baseScalar?.let(NEGATED_RELATION_SCALARS::get)
    if (base == null || negatedScalar == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.UnsupportedNegatedSymbol,
            "The current TeX \\not slice requires a relation with a standard precomposed negation",
            node.range,
        )
        val laid = layoutNode(node.base, style, alphabetOverride)
        decision(
            "TeXNotRelation",
            node.range,
            "commandRange" to node.commandRange,
            "baseKind" to node.base::class.simpleName,
            "precomposedScalar" to null,
            "policy" to "ExplicitUnsupportedRatherThanSyntheticSlashGuess",
        )
        return laid.copy(node = node)
    }
    val synthetic = MathSymbol(
        sourceText = "\\not${base.sourceText}",
        identity = MathSymbolIdentity.Literal(negatedScalar),
        atomClass = base.atomClass,
        family = MathFamily.Symbols,
        familyBinding = MathFamilyBinding.Fixed,
        range = node.range,
    )
    val laid = layoutSymbol(synthetic, style, alphabetOverride)
    decision(
        "TeXNotRelation",
        node.range,
        "commandRange" to node.commandRange,
        "baseIdentity" to base.identity.debugName,
        "baseScalar" to unicodeLabel(base.identity.baseScalar),
        "precomposedScalar" to unicodeLabel(negatedScalar),
        "atomClass" to base.atomClass,
        "policy" to "XeTeXUnicodeMathPrecomposedNegatedRelation",
    )
    return laid.copy(node = node)
}

internal fun MathLayoutPass.layoutCancel(
    node: MathCancel,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val content = layoutNode(node.body, style, alphabetOverride).completedTeXMathField().box
    val clean = content.texCleanBoxMetrics
    val totalHeight = clean.height
    val geometry = cancelStrokeGeometry(content.width, clean.ascent, clean.descent)
    val halfThickness = cancelLineThicknessPx / 2f
    val line = MathLineSegment(
        startX = geometry.startX,
        startY = geometry.startY,
        endX = geometry.endX,
        endY = geometry.endY,
        thickness = cancelLineThicknessPx,
    )
    val stroke = MathRulePlacement(
        left = minOf(geometry.startX, geometry.endX) - halfThickness,
        top = minOf(geometry.startY, geometry.endY) - halfThickness,
        right = maxOf(geometry.startX, geometry.endX) + halfThickness,
        bottom = maxOf(geometry.startY, geometry.endY) + halfThickness,
        sourceRange = node.commandRange,
        paintRole = MathRulePaintRole.Cancellation,
        lineSegment = line,
    )
    val painted = geometryExtents(
        width = content.width,
        glyphs = content.glyphs,
        rules = content.rules + stroke,
        range = node.range,
        constructionPaintGroups = content.constructionPaintGroups,
        hostTextRuns = content.hostTextRuns,
    )
    val strokeTop = minOf(geometry.startY, geometry.endY) - cancelLineThicknessPx / 2f
    val strokeBottom = maxOf(geometry.startY, geometry.endY) + cancelLineThicknessPx / 2f
    val ascent = max(content.ascent, -strokeTop)
    val descent = max(content.descent, strokeBottom)
    val box = painted.copy(
        width = content.width,
        ascent = ascent,
        descent = descent,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = max(clean.ascent, -strokeTop),
            descent = max(clean.descent, strokeBottom),
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = clean.evidence + MathTeXCleanBoxEvidence.RuleGeometry +
                MathTeXCleanBoxEvidence.CompletedChildBox,
        ),
    )
    decision(
        "LatexCancelStroke",
        node.range,
        "commandRange" to node.commandRange,
        "style" to style,
        "contentWidthPx" to content.width,
        "contentAscentPx" to clean.ascent,
        "contentDescentPx" to clean.descent,
        "contentTotalHeightPx" to totalHeight,
        "shapeClass" to geometry.shapeClass,
        "slope" to "${geometry.slopeX}:${geometry.slopeY}",
        "lineHorizontalExtentPx" to (geometry.endX - geometry.startX),
        "lineVerticalExtentPx" to (geometry.startY - geometry.endY),
        "lineThicknessPx" to cancelLineThicknessPx,
        "logicalWidthPx" to box.width,
        "logicalAscentPx" to box.ascent,
        "logicalDescentPx" to box.descent,
        "horizontalRoomPolicy" to "CancelPackageDefaultOverlapKeepsArgumentAdvance",
        "geometryPolicy" to "CancelSty2.2QuantizedPictureSlopeAndTwoPointExtension",
    )
    return LaidNode(
        node,
        box,
        MathAtomClass.Ordinary,
        0f,
        style,
        ScriptBaseKind.CompoundBox,
    )
}

private fun MathLayoutPass.cancelStrokeGeometry(width: Float, ascent: Float, descent: Float): CancelStrokeGeometry {
    val totalHeight = ascent + descent
    val twoPoint = 2f * TEX_POINT_TO_PX
    val centerX = width / 2f
    val centerY = (descent - ascent) / 2f
    val shapeClass: String
    val slopeX: Int
    val slopeY: Int
    val horizontalExtent: Float
    if (totalHeight < width) {
        shapeClass = "Wide"
        val preExtensionWidth = max(width, 2f * totalHeight)
        val slopeCase = floor(totalHeight * 5f / preExtensionWidth).toInt().coerceIn(0, 4)
        val slope = CANCEL_WIDE_SLOPES[slopeCase]
        slopeX = slope.first
        slopeY = slope.second
        horizontalExtent = preExtensionWidth + twoPoint
    } else {
        shapeClass = "Tall"
        val extendedHeight = max(totalHeight, 8f * TEX_POINT_TO_PX) + twoPoint
        val slopeCase = floor(width * 5f / extendedHeight).toInt().coerceIn(0, 4)
        val slope = CANCEL_TALL_SLOPES[slopeCase]
        slopeX = slope.first
        slopeY = slope.second
        val factor = CANCEL_TALL_WIDTH_FACTORS[slopeCase]
        horizontalExtent = factor * extendedHeight
    }
    val verticalExtent = horizontalExtent * slopeY / slopeX
    return CancelStrokeGeometry(
        startX = centerX - horizontalExtent / 2f,
        startY = centerY + verticalExtent / 2f,
        endX = centerX + horizontalExtent / 2f,
        endY = centerY - verticalExtent / 2f,
        slopeX = slopeX,
        slopeY = slopeY,
        shapeClass = shapeClass,
    )
}

internal fun MathLayoutPass.layoutGroup(
    node: MathGroup,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val horizontal = layoutList(node.body, style, alphabetOverride)
    return completeGroupLayout(node, style, horizontal)
}

private fun MathLayoutPass.completeGroupLayout(
    node: MathGroup,
    style: MathStyle,
    horizontal: MathLayoutPass.HorizontalLayout,
): LaidNode {
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

internal fun MathLayoutPass.layoutBoxed(
    node: MathBoxed,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val contentStyle = styleForLevel(MathStyleLevel.Display)
    val inset = fboxSeparationPx + fboxRuleThicknessPx
    val responsiveGroup = node.body as? MathGroup
    val responsiveWidth = displayWidthPx?.minus(2f * inset)?.coerceAtLeast(1f)
    val preparesResponsiveContent =
        formulaMode == MathMode.Display &&
        style.level == MathStyleLevel.Display &&
        softWrapDisplay &&
        responsiveWidth != null &&
        responsiveGroup != null
    val responsiveHorizontal = if (preparesResponsiveContent) {
        layoutList(responsiveGroup.body, contentStyle, alphabetOverride)
    } else {
        null
    }
    val ordinaryContent = if (responsiveHorizontal != null) {
        completeGroupLayout(checkNotNull(responsiveGroup), contentStyle, responsiveHorizontal)
            .completedTeXMathField().box
    } else {
        layoutNode(node.body, contentStyle, alphabetOverride).completedTeXMathField().box
    }
    val responsiveFragments = responsiveHorizontal?.let { inlineFragments(it) }.orEmpty()
    val breakResolution = if (
        responsiveHorizontal != null &&
        responsiveFragments.size > 1 &&
        ordinaryContent.visualWidth > checkNotNull(responsiveWidth)
    ) {
        resolveResponsiveDisplayBreak(
            fragments = responsiveFragments,
            lineMetrics = formulaLineMetrics(responsiveHorizontal.laid.box, contentStyle),
            maxWidthPx = responsiveWidth,
            defaultContinuationIndentPx = DISPLAY_CONTINUATION_INDENT_EM * fontSize(contentStyle),
            displayRowJotPx = DISPLAY_ROW_JOT_EM * fontSize(contentStyle),
        )
    } else {
        null
    }
    val broken = breakResolution?.layout
    val content = if (
        broken != null &&
        (broken.lines.size > 1 || ordinaryContent.visualWidth > checkNotNull(responsiveWidth))
    ) {
        taggedDisplayBodyLastBaselineY = maxOf(
            taggedDisplayBodyLastBaselineY,
            broken.lines.last().baselineFromTop - broken.lines.first().baselineFromTop,
        )
        replayResponsiveDisplayBody(
            fragments = responsiveFragments,
            broken = broken,
            viewportWidthPx = checkNotNull(responsiveWidth),
            range = checkNotNull(responsiveGroup).body.range,
            // Pin only when a tagged completion is in flight to drain the pinned clauses; an
            // untagged boxed field keeps its clause in the frame, which scrolls as one unit.
            pinClausesToViewport = taggedDisplayReplayExpected,
        ).also { wrapped ->
            // Pending pinned clauses were recorded in the frame-interior viewport; shift them by
            // the frame inset so they anchor in the outer display viewport.
            if (taggedDisplayPendingPinnedClauses.isNotEmpty()) {
                val shifted = taggedDisplayPendingPinnedClauses.map { it.copy(logicalX = it.logicalX + inset) }
                taggedDisplayPendingPinnedClauses.clear()
                taggedDisplayPendingPinnedClauses += shifted
            }
            decision(
                "BoxedResponsiveDisplayLineBreak",
                node.range,
                *responsiveWrapDecisionDetails(checkNotNull(breakResolution), responsiveFragments, contentStyle),
                "availableOuterWidthPx" to displayWidthPx,
                "frameInsetEachSidePx" to inset,
                "contentViewportWidthPx" to responsiveWidth,
                "unbrokenVisualWidthPx" to ordinaryContent.visualWidth,
                "wrappedContentWidthPx" to wrapped.width,
                "framePolicy" to "SingleFrameAroundCompletedMultilineMathField",
                "policy" to "ResponsiveDisplayBoxedContentLeadingOperators",
            )
        }
    } else {
        ordinaryContent
    }
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
        hostTextRuns = shiftedContent.hostTextRuns,
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
        "terminalRowSeparatorRange" to node.terminalRowSeparator?.separatorRange,
        "terminalRowSeparatorPolicy" to if (node.terminalRowSeparator == null) {
            "None"
        } else {
            "IgnoreEmptyFinalMarkdownDisplayRowInsideOutermostBox"
        },
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

internal fun MathLayoutPass.layoutBbox(
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
        hostTextRuns = shiftedContent.hostTextRuns,
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

private fun MathLayoutPass.resolveBboxDimension(dimension: MathBboxDimension, emSizePx: Float): Float {
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
