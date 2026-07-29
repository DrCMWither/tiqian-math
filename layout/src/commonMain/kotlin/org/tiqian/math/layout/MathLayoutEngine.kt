package org.tiqian.math.layout

import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.MathConstructionKind
import org.tiqian.math.font.opentype.MathGlyphComponent
import org.tiqian.math.font.opentype.MathKernCorner
import org.tiqian.math.font.opentype.MathVerticalConstruction
import org.tiqian.math.font.opentype.OpenTypeMathConstants
import org.tiqian.math.parser.MacroExpansionLimits
import org.tiqian.math.parser.MathMacroDefinition
import org.tiqian.math.parser.MathParser
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
) {
    init {
        require(fontSizePx > 0f) { "math font size must be positive" }
        require(nullDelimiterSpacePx == null || nullDelimiterSpacePx >= 0f) {
            "null delimiter space must not be negative"
        }
    }
}

class MathLayoutEngine(
    private val glyphSource: MathFontFace,
    macros: List<MathMacroDefinition> = emptyList(),
    expansionLimits: MacroExpansionLimits = MacroExpansionLimits(),
) {
    private val parser = MathParser(macros, expansionLimits)

    fun layout(source: String, options: MathLayoutOptions = MathLayoutOptions()): MathLayoutResult =
        MathLayoutPass(glyphSource, parser).layout(source, options)
}

/** Per-call mutable state; a public engine can safely serve concurrent layout requests. */
private class MathLayoutPass(
    private val glyphSource: MathFontFace,
    private val parser: MathParser,
) {
    private val diagnostics = mutableListOf<MathDiagnostic>()
    private val decisions = mutableListOf<MathLayoutDecision>()
    private var baseFontSizePx: Float = 24f
    private var nullDelimiterSpacePx: Float = 2.88f

    fun layout(source: String, options: MathLayoutOptions): MathLayoutResult {
        baseFontSizePx = options.fontSizePx
        nullDelimiterSpacePx = options.nullDelimiterSpacePx
            ?: options.fontSizePx * DEFAULT_NULL_DELIMITER_SPACE_EM
        val parsed = parser.parse(source)
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
                box = item.laid.box,
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
        is MathSymbol -> layoutSymbol(node, style, alphabetOverride)
        is MathOperator -> layoutOperator(node, style)
        is MathScripts -> if (node.base is MathOperator) {
            layoutOperatorScripts(node, node.base as MathOperator, style, alphabetOverride)
        } else {
            layoutScripts(node, style, alphabetOverride)
        }
        is MathFraction -> layoutFraction(node, style, alphabetOverride)
        is MathStyleDeclaration -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
        is MathAlphabetScope -> layoutAlphabetScopeNode(node, style)
        is MathErrorNode -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
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

    private fun layoutAlphabetScopeNode(node: MathAlphabetScope, style: MathStyle): LaidNode {
        val override = MathAlphabetOverride(node.family, node.alphabet)
        decision(
            "TeXMathAlphabetScope",
            node.range,
            "family" to node.family,
            "alphabet" to node.alphabet,
            "appliesTo" to MathFamilyBinding.Variable,
        )
        val horizontal = when (val body = node.body) {
            is MathGroup -> layoutList(body.body, style, override)
            is MathList -> layoutList(body, style, override)
            else -> null
        }
        return if (horizontal != null) {
            val single = horizontal.items.singleOrNull()
            if (single?.node is MathSymbol) single.laid.copy(
                node = node,
                box = single.laid.box.copy(range = node.range),
            ) else horizontal.laid.copy(
                node = node,
                box = horizontal.laid.box.copy(range = node.range),
                atomClass = single?.atomClass ?: MathAtomClass.Ordinary,
                italicCorrectionPx = 0f,
                scriptBaseKind = single?.laid?.scriptBaseKind ?: ScriptBaseKind.CompoundBox,
            )
        } else {
            layoutNode(node.body, style, override).copy(node = node)
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
        val lastGlyph = run.glyphs.lastOrNull()?.glyphId
        val italicCorrection = lastGlyph?.let { glyphSource.mathFont.italicCorrection(it, size) } ?: 0f
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
            "italicCorrectionPx" to italicCorrection,
            "shaping" to "single-noad",
        )
        val placements = run.glyphs.map { glyph ->
            MathGlyphPlacement(
                glyphId = glyph.glyphId,
                x = glyph.x,
                baselineY = 0f,
                advance = glyph.advance,
                inkBounds = glyph.inkBounds.translated(glyph.x, 0f),
                fontSizePx = size,
                sourceRange = node.range,
                style = style,
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
                placements.single().glyphId in glyphSource.mathFont.extendedShapeGlyphs -> ScriptBaseKind.ExtendedShape
                else -> ScriptBaseKind.Character
            },
        )
    }

    private fun layoutSymbolRun(items: List<PendingHorizontalItem>): HorizontalItem {
        require(items.size >= 2)
        val symbols = items.map { it.node as MathSymbol }
        val style = items.first().style
        val requests = items.map { symbolRequest(it.node as MathSymbol, it.style, it.alphabetOverride) }
        val size = fontSize(style)
        val resolved = glyphSource.resolveSymbols(requests, size)
        val coveredRange = SourceRange(symbols.first().range.start, symbols.last().range.endExclusive)
        val finalItalicCorrection = resolved.run.glyphs.lastOrNull()?.glyphId
            ?.let { glyphSource.mathFont.italicCorrection(it, size) }
            ?: 0f

        symbols.indices.forEach { index ->
            val symbol = symbols[index]
            val request = requests[index]
            val glyphIds = resolved.run.glyphs.indices
                .filter { resolved.glyphSourceRanges[it] == symbol.range }
                .map { resolved.run.glyphs[it].glyphId }
            if (!resolved.supported[index]) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.UnsupportedMathAlphabet,
                    "The selected formula-wide math face cannot resolve ${symbol.identity.debugName} " +
                        "in ${request.family}/${request.alphabet}",
                    symbol.range,
                )
            }
            if (glyphIds.any { it == 0.toUShort() }) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MissingGlyph,
                    "The selected formula-wide math face has no ${request.family}/${request.alphabet} glyph " +
                        "for ${symbol.identity.debugName}",
                    symbol.range,
                )
            }
            decision(
                "TeXMathSymbolResolution",
                symbol.range,
                "sourceText" to symbol.sourceText,
                "identity" to symbol.identity.debugName,
                "baseScalar" to unicodeLabel(symbol.identity.baseScalar),
                "atomClass" to symbol.atomClass,
                "familyBinding" to symbol.familyBinding,
                "declaredFamily" to symbol.family,
                "declaredAlphabet" to symbol.alphabet,
                "resolvedFamily" to request.family,
                "resolvedAlphabet" to request.alphabet,
                "backendScalar" to unicodeLabel(resolved.backendScalars[index]),
                "glyphIds" to glyphIds.joinToString(","),
                "italicCorrectionPx" to if (index == symbols.lastIndex) finalItalicCorrection else 0f,
                "shaping" to "compatible-ord-run",
            )
        }
        val placements = resolved.run.glyphs.mapIndexed { index, glyph ->
            MathGlyphPlacement(
                glyphId = glyph.glyphId,
                x = glyph.x,
                baselineY = 0f,
                advance = glyph.advance,
                inkBounds = glyph.inkBounds.translated(glyph.x, 0f),
                fontSizePx = size,
                sourceRange = resolved.glyphSourceRanges[index],
                style = style,
            )
        }
        val runNode = MathList(symbols, coveredRange)
        decision(
            "TeXCompatibleOrdRunShaping",
            coveredRange,
            "noadCount" to symbols.size,
            "family" to requests.first().family,
            "alphabet" to requests.first().alphabet,
            "style" to style,
            "backendScalars" to resolved.backendScalars.joinToString(",") { unicodeLabel(it) },
            "glyphIds" to resolved.run.glyphs.joinToString(",") { it.glyphId.toString() },
            "finalItalicCorrectionPx" to finalItalicCorrection,
            "policy" to "one-shaping-call-final-glyph-correction",
        )
        val laid = LaidNode(
            node = runNode,
            box = geometryExtents(resolved.run.width, placements, emptyList(), coveredRange),
            atomClass = MathAtomClass.Ordinary,
            italicCorrectionPx = finalItalicCorrection,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
        return HorizontalItem(runNode, laid, MathGlueAdjustment.Zero, MathAtomClass.Ordinary)
    }

    private fun symbolRequest(
        node: MathSymbol,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): MathSymbolGlyphRequest = MathSymbolGlyphRequest(
        identity = node.identity,
        family = if (node.familyBinding == MathFamilyBinding.Variable) {
            alphabetOverride?.family ?: node.family
        } else {
            node.family
        },
        alphabet = if (node.familyBinding == MathFamilyBinding.Variable) {
            alphabetOverride?.alphabet ?: node.alphabet
        } else {
            node.alphabet
        },
        style = style,
        sourceRange = node.range,
    )

    private fun layoutOperator(node: MathOperator, style: MathStyle): LaidNode {
        val size = fontSize(style)
        val resolved = glyphSource.resolveOperator(
            MathOperatorGlyphRequest(node.identity, style, node.commandRange),
            size,
        )
        if (resolved.run.missingGlyph) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingGlyph,
                "The selected formula-wide math face has no LargeSymbols glyph for ${node.identity.debugName}",
                node.commandRange,
            )
        }

        val display = style.level == MathStyleLevel.Display
        val targetHeight = if (display) scale(constants.displayOperatorMinHeight, style) else 0f
        val construction = if (display) {
            resolved.constructionBaseGlyphId?.let {
                glyphSource.mathFont.verticalConstruction(it, targetHeight, size)
            }
        } else {
            null
        }
        val rawBox = if (construction != null) {
            operatorConstructionBox(construction, node, style, size)
        } else {
            measuredRunBox(resolved.run, node.commandRange, style, size)
        }
        val axisY = -scale(constants.axisHeight, style)
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
            glyphSource.mathFont.scaleDesignUnits(it.advanceMeasurement, size)
        } ?: rawBox.inkBounds.height
        if (display && construction == null && achievedAdvance + GEOMETRY_EPSILON_PX < targetHeight) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingMathConstruction,
                "${node.identity.debugName} has no MATH construction covering ${targetHeight}px",
                node.commandRange,
            )
        } else if (display && construction != null && !construction.reachesTarget) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MathVariantTooShort,
                "${node.identity.debugName} MATH construction does not reach DisplayOperatorMinHeight",
                node.commandRange,
                DiagnosticSeverity.Warning,
            )
        }

        val finalGlyphId = when (construction?.kind) {
            MathConstructionKind.Variant ->
                construction.components.singleOrNull()?.glyphId
            MathConstructionKind.Assembly -> null
            null -> resolved.run.glyphs.lastOrNull()?.glyphId
        }
        val italicCorrectionSource = if (
            construction?.kind == MathConstructionKind.Assembly
        ) {
            "GlyphAssembly"
        } else {
            "MathItalicsCorrectionInfo"
        }
        val italicCorrection = construction?.assemblyItalicCorrection?.let {
            glyphSource.mathFont.scaleDesignUnits(it, size)
        } ?: finalGlyphId?.let {
            glyphSource.mathFont.italicCorrection(it, size)
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
            "displayOperatorMinHeightPx" to targetHeight,
            "achievedAdvancePx" to achievedAdvance,
            "reachesTarget" to if (display) achievedAdvance + GEOMETRY_EPSILON_PX >= targetHeight else true,
            "axisY" to axisY,
            "inkCenterBefore" to inkCenterBefore,
            "centerShiftPx" to centerShift,
            "inkCenterAfter" to (box.inkBounds.top + box.inkBounds.bottom) / 2f,
            "italicCorrectionPx" to italicCorrection,
            "italicCorrectionSource" to italicCorrectionSource,
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
                construction != null ||
                box.glyphs.singleOrNull()?.glyphId in glyphSource.mathFont.extendedShapeGlyphs
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
                baselineY = 0f,
                advance = glyph.advance,
                inkBounds = glyph.inkBounds.translated(glyph.x, 0f),
                fontSizePx = size,
                sourceRange = range,
                style = style,
            )
        }
        return geometryExtents(run.width, placements, emptyList(), range)
    }

    private fun operatorConstructionBox(
        construction: MathVerticalConstruction,
        node: MathOperator,
        style: MathStyle,
        size: Float,
    ): MathBox {
        val componentRuns = construction.components.map { component ->
            component to glyphSource.measureGlyph(component.glyphId, size, style, node.commandRange)
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
            "componentBaselineOriginsPx" to construction.components.joinToString(",") {
                (-glyphSource.mathFont.scaleDesignUnits(it.offset, size)).toString()
            },
            "placementOrigin" to if (
                construction.kind == MathConstructionKind.Assembly
            ) "shared-left/advance-offset" else "glyph-baseline",
        )
        return geometryExtents(placed.width, placed.glyphs, emptyList(), node.range)
    }

    /**
     * Places vertical MATH construction parts at their advance offsets. The offset is a glyph
     * origin, so ink bounds can describe any top/bottom overhang without changing connector
     * distances. Horizontal centering is retained only for the existing delimiter-box policy.
     */
    private fun placeVerticalConstruction(
        construction: MathVerticalConstruction,
        componentRuns: List<Pair<MathGlyphComponent, MeasuredMathRun>>,
        size: Float,
        style: MathStyle,
        sourceRange: SourceRange,
        centerComponentsHorizontally: Boolean,
    ): PlacedVerticalConstruction {
        val width = componentRuns.maxOfOrNull { it.second.width } ?: 0f
        val placements = componentRuns.flatMap { (component, run) ->
            val componentX = if (centerComponentsHorizontally) (width - run.width) / 2f else 0f
            val baselineY = -glyphSource.mathFont.scaleDesignUnits(component.offset, size)
            run.glyphs.map { glyph ->
                val x = componentX + glyph.x
                MathGlyphPlacement(
                    glyphId = glyph.glyphId,
                    x = x,
                    baselineY = baselineY,
                    advance = glyph.advance,
                    inkBounds = glyph.inkBounds.translated(x, baselineY),
                    fontSizePx = size,
                    sourceRange = sourceRange,
                    style = style,
                )
            }
        }
        return PlacedVerticalConstruction(width, placements)
    }

    private fun layoutOperatorScripts(
        node: MathScripts,
        operator: MathOperator,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val base = layoutOperator(operator, style)
        val effectivePolicy = when (operator.limitsPolicy) {
            MathLimitsPolicy.Limits -> MathLimitsPolicy.Limits
            MathLimitsPolicy.NoLimits -> MathLimitsPolicy.NoLimits
            MathLimitsPolicy.Auto -> if (style.level == MathStyleLevel.Display) {
                MathLimitsPolicy.Limits
            } else {
                MathLimitsPolicy.NoLimits
            }
        }
        val reason = when {
            operator.hasExplicitLimitsPolicy -> "explicit-postfix-modifier"
            operator.limitsPolicy == MathLimitsPolicy.Auto && style.level == MathStyleLevel.Display -> "auto-display"
            operator.limitsPolicy == MathLimitsPolicy.Auto -> "auto-non-display"
            else -> "plain-tex-operator-default"
        }
        decision(
            "TeXOperatorLimitsPolicy",
            node.range,
            "identity" to operator.identity.debugName,
            "declaredPolicy" to operator.limitsPolicy,
            "effectivePolicy" to effectivePolicy,
            "explicit" to operator.hasExplicitLimitsPolicy,
            "modifierRange" to operator.limitsModifierRange,
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
                "identity" to operator.identity.debugName,
                "style" to style,
                "geometry" to "operator-centered-base-plus-ordinary-side-script-kernel",
            )
            layoutScriptsWithBase(node, base, style, alphabetOverride)
        }
    }

    private fun layoutStackedOperatorLimits(
        node: MathScripts,
        base: LaidNode,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val upper = node.superscript?.let { layoutNode(it, style.superscript(), alphabetOverride) }
        val lower = node.subscript?.let { layoutNode(it, style.subscript(), alphabetOverride) }
        val upperGapMin = scale(constants.upperLimitGapMin, style)
        val upperBaselineRiseMin = scale(constants.upperLimitBaselineRiseMin, style)
        val lowerGapMin = scale(constants.lowerLimitGapMin, style)
        val lowerBaselineDropMin = scale(constants.lowerLimitBaselineDropMin, style)
        val upperShift = upper?.let {
            base.box.ascent + max(upperBaselineRiseMin, it.box.descent + upperGapMin)
        }
        val lowerShift = lower?.let {
            base.box.descent + max(lowerBaselineDropMin, it.box.ascent + lowerGapMin)
        }
        val halfItalicCorrection = base.italicCorrectionPx / 2f
        val logicalWidth = maxOf(
            base.box.width,
            upper?.box?.width ?: 0f,
            lower?.box?.width ?: 0f,
        )
        val baseX = (logicalWidth - base.box.width) / 2f
        val upperX = upper?.let { (logicalWidth - it.box.width) / 2f + halfItalicCorrection }
        val lowerX = lower?.let { (logicalWidth - it.box.width) / 2f - halfItalicCorrection }
        val shiftedBase = base.box.translated(baseX, 0f)
        val shiftedUpper = upper?.let { it.box.translated(upperX!!, -upperShift!!) }
        val shiftedLower = lower?.let { it.box.translated(lowerX!!, lowerShift!!) }
        val actualUpperGap = upper?.let { upperShift!! - base.box.ascent - it.box.descent }
        val actualUpperRise = upperShift?.minus(base.box.ascent)
        val actualLowerGap = lower?.let { lowerShift!! - base.box.descent - it.box.ascent }
        val actualLowerDrop = lowerShift?.minus(base.box.descent)
        decision(
            "OpenTypeMathOperatorLimits",
            node.range,
            "style" to style,
            "upperStyle" to upper?.style,
            "lowerStyle" to lower?.style,
            "upperLimitGapMinPx" to upperGapMin,
            "upperLimitBaselineRiseMinPx" to upperBaselineRiseMin,
            "lowerLimitGapMinPx" to lowerGapMin,
            "lowerLimitBaselineDropMinPx" to lowerBaselineDropMin,
            "upperShiftPx" to upperShift,
            "lowerShiftPx" to lowerShift,
            "actualUpperGapPx" to actualUpperGap,
            "actualUpperBaselineRisePx" to actualUpperRise,
            "actualLowerGapPx" to actualLowerGap,
            "actualLowerBaselineDropPx" to actualLowerDrop,
            "operatorItalicCorrectionPx" to base.italicCorrectionPx,
            "upperCenterOffsetPx" to halfItalicCorrection,
            "lowerCenterOffsetPx" to -halfItalicCorrection,
            "logicalWidthPx" to logicalWidth,
            "logicalWidthPolicy" to "max-unskewed-operator-and-limits",
            "operatorWidthPx" to base.box.width,
            "upperWidthPx" to upper?.box?.width,
            "lowerWidthPx" to lower?.box?.width,
            "operatorX" to baseX,
            "upperX" to upperX,
            "lowerX" to lowerX,
        )
        return LaidNode(
            node = node,
            box = geometryExtents(
                logicalWidth,
                shiftedBase.glyphs + shiftedUpper?.glyphs.orEmpty() + shiftedLower?.glyphs.orEmpty(),
                shiftedBase.rules + shiftedUpper?.rules.orEmpty() + shiftedLower?.rules.orEmpty(),
                node.range,
            ),
            atomClass = MathAtomClass.Operator,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    private fun layoutScripts(node: MathScripts, style: MathStyle, alphabetOverride: MathAlphabetOverride?): LaidNode =
        layoutScriptsWithBase(node, layoutNode(node.base, style, alphabetOverride), style, alphabetOverride)

    private fun layoutScriptsWithBase(
        node: MathScripts,
        base: LaidNode,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val superscript = node.superscript?.let { layoutNode(it, style.superscript(), alphabetOverride) }
        val subscript = node.subscript?.let { layoutNode(it, style.subscript(), alphabetOverride) }
        var superscriptShift = scale(
            if (style.cramped) constants.superscriptShiftUpCramped else constants.superscriptShiftUp,
            style,
        )
        var subscriptShift = scale(constants.subscriptShiftDown, style)

        val appliesBaselineDrop = base.scriptBaseKind != ScriptBaseKind.Character
        superscript?.let { laid ->
            superscriptShift = max(superscriptShift, laid.box.descent + scale(constants.superscriptBottomMin, style))
            if (appliesBaselineDrop) {
                superscriptShift = max(
                    superscriptShift,
                    base.box.ascent - scale(constants.superscriptBaselineDropMax, style),
                )
            }
        }
        subscript?.let { laid ->
            subscriptShift = max(subscriptShift, laid.box.ascent - scale(constants.subscriptTopMax, style))
            if (appliesBaselineDrop) {
                subscriptShift = max(
                    subscriptShift,
                    base.box.descent + scale(constants.subscriptBaselineDropMin, style),
                )
            }
        }
        if (superscript != null && subscript != null) {
            val currentGap = (subscriptShift - subscript.box.ascent) -
                (-superscriptShift + superscript.box.descent)
            val missingGap = scale(constants.subSuperscriptGapMin, style) - currentGap
            if (missingGap > 0f) {
                val superBottomHeight = superscriptShift - superscript.box.descent
                val availableSuperMove = (
                    scale(constants.superscriptBottomMaxWithSubscript, style) - superBottomHeight
                    ).coerceAtLeast(0f)
                val superMove = minOf(missingGap, availableSuperMove)
                superscriptShift += superMove
                subscriptShift += missingGap - superMove
            }
        }

        val superscriptKern = superscript?.let { superscriptMathKern(base, it, superscriptShift, node.range) } ?: 0f
        val subscriptKern = subscript?.let { subscriptMathKern(base, it, subscriptShift, node.range) } ?: 0f
        val superscriptX = base.box.width + base.italicCorrectionPx + superscriptKern
        val subscriptX = base.box.width + subscriptKern
        val glyphs = buildList {
            addAll(base.box.glyphs)
            superscript?.let { addAll(it.box.translated(superscriptX, -superscriptShift).glyphs) }
            subscript?.let { addAll(it.box.translated(subscriptX, subscriptShift).glyphs) }
        }
        val rules = buildList {
            addAll(base.box.rules)
            superscript?.let { addAll(it.box.translated(superscriptX, -superscriptShift).rules) }
            subscript?.let { addAll(it.box.translated(subscriptX, subscriptShift).rules) }
        }
        val scriptRight = maxOf(
            base.box.width,
            superscript?.let { superscriptX + it.box.width } ?: base.box.width,
            subscript?.let { subscriptX + it.box.width } ?: base.box.width,
        )
        val width = scriptRight + scale(constants.spaceAfterScript, style)
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
            "baseKind" to base.scriptBaseKind,
            "baselineDropApplied" to appliesBaselineDrop,
            "boxKernPolicy" to "single-glyph-corners-else-zero",
        )
        return LaidNode(
            node = node,
            box = geometryExtents(width, glyphs, rules, node.range),
            atomClass = base.atomClass,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

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
        val first = glyphSource.mathFont.mathKern(
            baseGlyph.glyphId,
            MathKernCorner.TopRight,
            shift - script.box.inkBounds.bottom,
            baseGlyph.fontSizePx,
        ) + glyphSource.mathFont.mathKern(
            scriptGlyph.glyphId,
            MathKernCorner.BottomLeft,
            -script.box.inkBounds.bottom,
            scriptGlyph.fontSizePx,
        )
        val second = glyphSource.mathFont.mathKern(
            baseGlyph.glyphId,
            MathKernCorner.TopRight,
            -base.box.inkBounds.top,
            baseGlyph.fontSizePx,
        ) + glyphSource.mathFont.mathKern(
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
        val first = glyphSource.mathFont.mathKern(
            baseGlyph.glyphId,
            MathKernCorner.BottomRight,
            -shift - script.box.inkBounds.top,
            baseGlyph.fontSizePx,
        ) + glyphSource.mathFont.mathKern(
            scriptGlyph.glyphId,
            MathKernCorner.TopLeft,
            -script.box.inkBounds.top,
            scriptGlyph.fontSizePx,
        )
        val second = glyphSource.mathFont.mathKern(
            baseGlyph.glyphId,
            MathKernCorner.BottomRight,
            -base.box.inkBounds.bottom,
            baseGlyph.fontSizePx,
        ) + glyphSource.mathFont.mathKern(
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
        val numerator = layoutNode(node.numerator, style.fractionNumerator(), alphabetOverride).box
        val denominator = layoutNode(node.denominator, style.fractionDenominator(), alphabetOverride).box
        val display = style.level == MathStyleLevel.Display
        val stack = layoutFractionStack(node, style, numerator, denominator, display)
        val withDelimiters = if (node.hasParentheses) {
            addBinomialParentheses(stack, node, style)
        } else {
            addNullFractionDelimiters(stack, node)
        }
        return LaidNode(
            node,
            withDelimiters,
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
    }

    private fun addNullFractionDelimiters(stack: MathBox, node: MathFraction): MathBox {
        val shiftedStack = stack.translated(nullDelimiterSpacePx, 0f)
        decision(
            "TeXFractionNullDelimiters",
            node.range,
            "leftSpacePx" to nullDelimiterSpacePx,
            "rightSpacePx" to nullDelimiterSpacePx,
            "parameter" to "nullDelimiterSpacePx",
            "styleInvariant" to true,
        )
        return geometryExtents(
            width = stack.width + 2f * nullDelimiterSpacePx,
            glyphs = shiftedStack.glyphs,
            rules = shiftedStack.rules,
            range = node.range,
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
        val numeratorX = (contentWidth - numerator.width) / 2f
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
                style,
            )
            val denominatorGap = scale(
                if (display) constants.fractionDenomDisplayStyleGapMin else constants.fractionDenominatorGapMin,
                style,
            )
            numeratorShift = max(numeratorShift, numerator.descent + numeratorGap - ruleTop)
            denominatorShift = max(denominatorShift, denominator.ascent + denominatorGap + ruleBottom)
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
            )
        } else {
            numeratorShift = scale(
                if (display) constants.stackTopDisplayStyleShiftUp else constants.stackTopShiftUp,
                style,
            )
            denominatorShift = scale(
                if (display) constants.stackBottomDisplayStyleShiftDown else constants.stackBottomShiftDown,
                style,
            )
            val minimumGap = scale(
                if (display) constants.stackDisplayStyleGapMin else constants.stackGapMin,
                style,
            )
            val actualGap = (denominatorShift - denominator.ascent) - (-numeratorShift + numerator.descent)
            val missingGap = (minimumGap - actualGap).coerceAtLeast(0f)
            numeratorShift += missingGap / 2f
            denominatorShift += missingGap / 2f
            rules = emptyList()
            decision(
                "OpenTypeMathFractionStack",
                node.range,
                "kind" to "ruleless",
                "style" to style,
                "numeratorShiftPx" to numeratorShift,
                "denominatorShiftPx" to denominatorShift,
                "gapMinPx" to minimumGap,
                "symmetricGapCorrectionPx" to missingGap / 2f,
            )
        }

        val shiftedNumerator = numerator.translated(numeratorX, -numeratorShift)
        val shiftedDenominator = denominator.translated(denominatorX, denominatorShift)
        return geometryExtents(
            width = contentWidth,
            glyphs = shiftedNumerator.glyphs + shiftedDenominator.glyphs,
            rules = shiftedNumerator.rules + shiftedDenominator.rules + rules,
            range = node.range,
        )
    }

    private fun addBinomialParentheses(stackInput: MathBox, node: MathFraction, style: MathStyle): MathBox {
        val stack = stackInput
        val size = fontSize(style)
        val leftBase = glyphSource.shapeConstructionBase("(", size, node.range)
        val rightBase = glyphSource.shapeConstructionBase(")", size, node.range)
        val axisY = -scale(constants.axisHeight, style)
        // TeX Rule 15e uses a style-selected fixed fraction-noad delimiter size. In this
        // OpenType slice the font's named fixed subformula height is used directly; future
        // \left/\right layout must use a separate content-driven policy.
        val fixedTargetHeight = scale(constants.delimitedSubFormulaMinHeight, style)
        val axisInkSafetyHeight = 2f * max(
            axisY - stack.inkBounds.top,
            stack.inkBounds.bottom - axisY,
        )
        val targetHeight = max(fixedTargetHeight, axisInkSafetyHeight)

        fun construction(baseRun: MeasuredMathRun, side: String): MathVerticalConstruction? {
            val baseGlyphId = baseRun.glyphs.singleOrNull()?.glyphId
            val selected = baseGlyphId?.let { glyphSource.mathFont.verticalConstruction(it, targetHeight, size) }
            if (selected == null && baseRun.ascent + baseRun.descent + GEOMETRY_EPSILON_PX < targetHeight) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MissingMathConstruction,
                    "The $side parenthesis has no MATH construction covering ${targetHeight}px",
                    node.range,
                )
            }
            return selected
        }

        val leftConstruction = construction(leftBase, "left")
        val rightConstruction = construction(rightBase, "right")
        fun delimiterBox(
            side: String,
            construction: MathVerticalConstruction?,
            baseRun: MeasuredMathRun,
        ): MathBox {
            val rawPlacements = if (construction == null) {
                baseRun.glyphs.map { glyph ->
                    MathGlyphPlacement(
                        glyphId = glyph.glyphId,
                        x = glyph.x,
                        baselineY = 0f,
                        advance = glyph.advance,
                        inkBounds = glyph.inkBounds.translated(glyph.x, 0f),
                        fontSizePx = size,
                        sourceRange = node.range,
                        style = style,
                    )
                }
            } else {
                val componentRuns = construction.components.map { component ->
                    component to glyphSource.measureGlyph(component.glyphId, size, style, node.range)
                }
                placeVerticalConstruction(
                    construction = construction,
                    componentRuns = componentRuns,
                    size = size,
                    style = style,
                    sourceRange = node.range,
                    centerComponentsHorizontally = true,
                ).glyphs
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
            val advance = placements.maxOfOrNull { it.x + it.advance } ?: baseRun.width
            val box = geometryExtents(advance, placements, emptyList(), node.range)
            val achievedAdvance = construction?.let {
                glyphSource.mathFont.scaleDesignUnits(it.advanceMeasurement, size)
            } ?: baseRun.ascent + baseRun.descent
            val inkHeight = box.inkBounds.height
            val coversTop = box.inkBounds.top <= stack.inkBounds.top + GEOMETRY_EPSILON_PX
            val coversBottom = box.inkBounds.bottom + GEOMETRY_EPSILON_PX >= stack.inkBounds.bottom
            if (
                (construction != null && !construction.reachesTarget) ||
                achievedAdvance + GEOMETRY_EPSILON_PX < targetHeight ||
                !coversTop ||
                !coversBottom
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
                "baseGlyphId" to baseRun.glyphs.singleOrNull()?.glyphId,
                "construction" to (construction?.kind ?: "BaseGlyph"),
                "targetPolicy" to "TeXFractionNoadFixedWithAxisInkSafety",
                "fixedTargetPx" to fixedTargetHeight,
                "axisInkSafetyTargetPx" to axisInkSafetyHeight,
                "targetPx" to targetHeight,
                "achievedAdvancePx" to achievedAdvance,
                "inkHeightPx" to inkHeight,
                "stackTopPx" to stack.inkBounds.top,
                "stackBottomPx" to stack.inkBounds.bottom,
                "delimiterTopPx" to box.inkBounds.top,
                "delimiterBottomPx" to box.inkBounds.bottom,
                "coversTop" to coversTop,
                "coversBottom" to coversBottom,
                "extenderRepetitions" to construction?.extenderRepetitions,
                "connectorOverlaps" to construction?.connectorOverlaps,
            )
            return box
        }

        val leftBox = delimiterBox("left", leftConstruction, leftBase)
        val rightBox = delimiterBox("right", rightConstruction, rightBase)
        val initialStackX = leftBox.width
        val leftCollisionKern = (
            leftBox.inkBounds.right - (initialStackX + stack.inkBounds.left)
            ).coerceAtLeast(0f)
        val stackX = initialStackX + leftCollisionKern
        val initialRightX = stackX + stack.width
        val rightCollisionKern = (
            stackX + stack.inkBounds.right - (initialRightX + rightBox.inkBounds.left)
            ).coerceAtLeast(0f)
        val rightX = initialRightX + rightCollisionKern
        val shiftedStack = stack.translated(stackX, 0f)
        val shiftedRight = rightBox.translated(rightX, 0f)
        decision(
            "BinomialHorizontalCollisionKern",
            node.range,
            "leftPx" to leftCollisionKern,
            "rightPx" to rightCollisionKern,
            "policy" to "logical-advance-preserved-ink-collision-only",
        )
        return geometryExtents(
            rightX + rightBox.width,
            leftBox.glyphs + shiftedStack.glyphs + shiftedRight.glyphs,
            leftBox.rules + shiftedStack.rules + shiftedRight.rules,
            node.range,
        )
    }

    private fun layoutList(
        list: MathList,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride? = null,
    ): HorizontalLayout {
        val raw = flattenListChildren(list, style, alphabetOverride)
        val classes = raw.map { it.laid.atomClass }.toMutableList()
        for (index in classes.indices) {
            val previous = classes.getOrNull(index - 1)
            val current = classes[index]
            if (previous == MathAtomClass.Binary && current in binaryRightCanceller) {
                classes[index - 1] = MathAtomClass.Ordinary
            }
            val resolvedPrevious = classes.getOrNull(index - 1)
            if (current == MathAtomClass.Binary && (resolvedPrevious == null || resolvedPrevious in binaryLeftCanceller)) {
                classes[index] = MathAtomClass.Ordinary
            }
        }
        if (classes.lastOrNull() == MathAtomClass.Binary) classes[classes.lastIndex] = MathAtomClass.Ordinary
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
            val leftClass = classes.getOrNull(index - 1)
            val rightClass = classes[index]
            val glue = if (leftClass == null) {
                MathGlueAdjustment.Zero
            } else {
                atomGlue(leftClass, rightClass, item.laid.style, item.node.range)
            }
            item.copy(glueBefore = glue, atomClass = rightClass)
        }
        val items = spacedItems.mapIndexed { index, item ->
            val rightClass = classes.getOrNull(index + 1)
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
            x += item.glueBefore.naturalPx
            val shifted = item.laid.box.translated(x, 0f)
            glyphs += shifted.glyphs
            rules += shifted.rules
            x += item.laid.box.width + item.trailingItalicCorrectionPx
        }
        val box = geometryExtents(x, glyphs, rules, list.range)
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
                } else {
                    addAll(flattenPendingHorizontal(child, currentStyle, alphabetOverride))
                }
            }
        }
    }

    private fun flattenPendingHorizontal(
        node: MathNode,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
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
                is MathGroup -> flattenPendingListChildren(body.body, style, override)
                is MathList -> flattenPendingListChildren(body, style, override)
                else -> listOf(PendingHorizontalItem(body, style, override))
            }
        }
        else -> listOf(PendingHorizontalItem(node, style, alphabetOverride))
    }

    private fun layoutPendingItems(pending: List<PendingHorizontalItem>): List<HorizontalItem> = buildList {
        var index = 0
        while (index < pending.size) {
            val first = pending[index]
            val key = first.ordRunKey()
            if (key == null) {
                add(first.layoutIndividually())
                index += 1
                continue
            }
            var endExclusive = index + 1
            while (endExclusive < pending.size && pending[endExclusive].ordRunKey() == key) {
                endExclusive += 1
            }
            if (endExclusive - index >= 2) {
                add(layoutSymbolRun(pending.subList(index, endExclusive)))
            } else {
                add(first.layoutIndividually())
            }
            index = endExclusive
        }
    }

    private fun PendingHorizontalItem.layoutIndividually(): HorizontalItem = HorizontalItem(
        node = node,
        laid = layoutNode(node, style, alphabetOverride),
        glueBefore = MathGlueAdjustment.Zero,
        atomClass = MathAtomClass.Ordinary,
    )

    private fun PendingHorizontalItem.ordRunKey(): OrdRunKey? {
        val symbol = node as? MathSymbol ?: return null
        if (symbol.atomClass != MathAtomClass.Ordinary) return null
        val request = symbolRequest(symbol, style, alphabetOverride)
        return OrdRunKey(style, request.family, request.alphabet)
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
        val glue = when (kind) {
            MathGlueKind.None -> MathGlueAdjustment.Zero
            MathGlueKind.Thin -> if (priority == MathAdjustmentPriority.Punctuation) {
                glue(kind, 3f * mu, 3f * mu, 6f * mu, priority)
            } else {
                glue(kind, 3f * mu, 3f * mu, 3f * mu, priority)
            }
            MathGlueKind.Medium -> glue(kind, 4f * mu, 0f, 6f * mu, priority)
            MathGlueKind.Thick -> glue(kind, 5f * mu, 5f * mu, 10f * mu, priority)
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
        return MathFormulaLineMetrics(
            fontAscentPx = fontAscent,
            fontDescentPx = fontDescent,
            fontLineGapPx = lineGap,
            mathLeadingPx = mathLeading,
            inkAscentPx = box.ascent,
            inkDescentPx = box.descent,
            logicalAscentPx = max(fontAscent + lineGap, box.ascent + mathLeading),
            logicalDescentPx = max(fontDescent, box.descent),
        )
    }

    private fun geometryExtents(
        width: Float,
        glyphs: List<MathGlyphPlacement>,
        rules: List<MathRulePlacement>,
        range: SourceRange,
    ): MathBox {
        val left = minOf(glyphs.minOfOrNull { it.inkBounds.left } ?: 0f, rules.minOfOrNull { it.left } ?: 0f)
        val top = minOf(glyphs.minOfOrNull { it.inkBounds.top } ?: 0f, rules.minOfOrNull { it.top } ?: 0f)
        val right = maxOf(glyphs.maxOfOrNull { it.inkBounds.right } ?: 0f, rules.maxOfOrNull { it.right } ?: 0f)
        val bottom = maxOf(glyphs.maxOfOrNull { it.inkBounds.bottom } ?: 0f, rules.maxOfOrNull { it.bottom } ?: 0f)
        return MathBox(
            width,
            (-top).coerceAtLeast(0f),
            bottom.coerceAtLeast(0f),
            MathRect(left, top, right, bottom),
            glyphs,
            rules,
            range,
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
                    "ink=${glyph.inkBounds.left},${glyph.inkBounds.top},${glyph.inkBounds.right},${glyph.inkBounds.bottom}",
            )
        }
        box.rules.forEachIndexed { index, rule ->
            appendLine("rule[$index] ${rule.left},${rule.top},${rule.right},${rule.bottom}")
        }
        fragments.forEach { fragment ->
            appendLine(
                "fragment[${fragment.index}] range=${fragment.sourceRange.start}..${fragment.sourceRange.endExclusive} " +
                    "advance=${fragment.box.width} ink=${fragment.box.inkBounds.left}..${fragment.box.inkBounds.right} " +
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
    )

    private data class PlacedVerticalConstruction(
        val width: Float,
        val glyphs: List<MathGlyphPlacement>,
    )

    private data class HorizontalItem(
        val node: MathNode,
        val laid: LaidNode,
        val glueBefore: MathGlueAdjustment,
        val atomClass: MathAtomClass,
        val trailingItalicCorrectionPx: Float = 0f,
    )

    private data class PendingHorizontalItem(
        val node: MathNode,
        val style: MathStyle,
        val alphabetOverride: MathAlphabetOverride?,
    )

    private data class OrdRunKey(
        val style: MathStyle,
        val family: MathFamily,
        val alphabet: MathAlphabet,
    )

    private data class HorizontalLayout(
        val laid: LaidNode,
        val items: List<HorizontalItem>,
    )

    private data class MathAlphabetOverride(
        val family: MathFamily,
        val alphabet: MathAlphabet,
    )

    private companion object {
        const val GEOMETRY_EPSILON_PX = 0.02f

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

private fun unicodeLabel(scalar: Int): String = "U+${scalar.toString(16).uppercase().padStart(4, '0')}"

private const val DEFAULT_NULL_DELIMITER_SPACE_EM = 0.12f

private enum class ScriptBaseKind {
    Character,
    CompoundBox,
    ExtendedShape,
}

private fun MathBox.singleGlyphOrNull(): MathGlyphPlacement? =
    if (rules.isEmpty() && glyphs.size == 1) glyphs.single() else null

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
