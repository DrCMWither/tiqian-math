package org.tiqian.math.layout

import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.MathConstructionKind
import org.tiqian.math.font.opentype.MathGlyphComponent
import org.tiqian.math.font.opentype.MathKernCorner
import org.tiqian.math.font.opentype.MathVerticalConstruction
import org.tiqian.math.font.opentype.MathVerticalConstructionRequest
import org.tiqian.math.font.opentype.MathVerticalAssemblyPolicy
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
    private var nextConstructionPaintGroupId: Int = 1

    fun layout(source: String, options: MathLayoutOptions): MathLayoutResult {
        baseFontSizePx = options.fontSizePx
        nextConstructionPaintGroupId = 1
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
        is MathSymbol -> layoutSymbol(node, style, alphabetOverride)
        is MathOperator -> layoutOperator(node, style)
        is MathScripts -> if (node.base is MathOperator) {
            layoutOperatorScripts(node, node.base as MathOperator, style, alphabetOverride)
        } else {
            layoutScripts(node, style, alphabetOverride)
        }
        is MathFraction -> layoutFraction(node, style, alphabetOverride)
        is MathRadical -> layoutRadical(node, style, alphabetOverride)
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
                selectVerticalConstruction(
                    baseGlyphId = it,
                    normalRun = resolved.run,
                    targetHeight = targetHeight,
                    size = size,
                    style = style,
                    range = node.commandRange,
                )
            }
        } else {
            null
        }
        val assemblyValidation = construction?.assemblyValidation
            ?: resolved.constructionBaseGlyphId?.let(glyphSource.mathFont::verticalAssemblyValidation)
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
            "constructionPolicy" to (construction?.constructionPolicy ?: if (assemblyValidation?.valid == false) {
                "MathMLCore5.3.2FailureAfterInvalidAssembly"
            } else null),
            "assemblyValid" to assemblyValidation?.valid,
            "assemblyInvalidReasons" to assemblyValidation?.invalidReasons,
            "assemblyValidationPolicy" to assemblyValidation?.validationPolicy,
            "assemblySpecificationDivergence" to assemblyValidation?.specificationDivergence,
            "assemblyCheckedConnectionCount" to assemblyValidation?.checkedConnectionCount,
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
                (construction?.kind != null && construction.kind != MathConstructionKind.BaseGlyph) ||
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

    /** One normal-glyph-first entry point shared by operators, radicals, and delimiters. */
    private fun selectVerticalConstruction(
        baseGlyphId: UShort,
        normalRun: MeasuredMathRun,
        targetHeight: Float,
        size: Float,
        style: MathStyle,
        range: SourceRange,
        assemblyPolicy: MathVerticalAssemblyPolicy = MathVerticalAssemblyPolicy.MathMLCoreUniformOverlap,
    ): MathVerticalConstruction? = glyphSource.mathFont.verticalConstruction(
        MathVerticalConstructionRequest(
            baseGlyphId = baseGlyphId,
            targetSizePx = targetHeight,
            fontSizePx = size,
            normalGlyphHeightPx = normalRun.glyphs.maxOfOrNull { it.inkBounds.height } ?: 0f,
            normalGlyphAdvanceWidthPx = normalRun.width,
            assemblyPolicy = assemblyPolicy,
        ),
        glyphVerticalExtentPx = { glyphId ->
            glyphSource.measureGlyphOutlineBounds(glyphId, size, style, range)
                .glyphs.singleOrNull()?.inkBounds?.height
                ?: glyphSource.measureGlyph(glyphId, size, style, range).let { it.ascent + it.descent }
        },
    ) { glyphId ->
        glyphSource.measureGlyph(glyphId, size, style, range).width
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
            val componentBottomY = -glyphSource.mathFont.scaleDesignUnits(component.offset, size)
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
            val glyph = glyphSource.measureGlyphOutlineBounds(
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
        val baseMeasurement = glyphSource.shapeOutlineConstructionBase(RADICAL_SIGN, size, node.commandRange)
        val baseRun = baseMeasurement.run
        val baseGlyphId = baseRun.glyphs.singleOrNull()?.glyphId
        if (baseRun.missingGlyph || baseGlyphId == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingGlyph,
                "The selected formula-wide math face has no radical sign glyph",
                node.commandRange,
            )
        }

        val gapMin = scale(
            if (style.level == MathStyleLevel.Display) {
                constants.radicalDisplayStyleVerticalGap
            } else {
                constants.radicalVerticalGap
            },
            style,
        )
        val ruleThickness = scale(constants.radicalRuleThickness, style)
        val extraAscender = scale(constants.radicalExtraAscender, style)
        // XeTeX make_radical selects the delimiter from clean_box height + depth. A leaf native
        // math glyph contributes its exact glyph bbox to that box, while a compound nucleus
        // contributes the already-completed logical box (including an inner radical's reserve).
        // The painted subtree ink union is deliberately not a substitute for clean_box.
        val targetHeight = radicand.height + gapMin + ruleThickness
        val baseRadical = measuredRunBox(baseRun, node.commandRange, style, size)
        val baseGlyphHeight = baseRadical.inkBounds.height
        val construction = baseGlyphId?.let {
            selectVerticalConstruction(
                baseGlyphId = it,
                normalRun = baseRun,
                targetHeight = targetHeight,
                size = size,
                style = style,
                range = node.commandRange,
                assemblyPolicy = MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue,
            )
        }
        val baseGlyphCoversTarget = construction?.kind == MathConstructionKind.BaseGlyph
        val constructionMeasurements = construction?.components?.map { component ->
            component to glyphSource.measureOutlineConstructionGlyph(
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
            ?: baseGlyphId?.let(glyphSource.mathFont::verticalAssemblyValidation)
        val assemblyTable = baseGlyphId?.let {
            glyphSource.mathFont.verticalConstructions[it]?.assembly
        }
        val achievedAdvance = construction?.let {
            glyphSource.mathFont.scaleDesignUnits(it.advanceMeasurement, size)
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
        val kernBeforeDegree = if (degree == null) 0f else scale(constants.radicalKernBeforeDegree, style)
        val kernAfterDegree = if (degree == null) 0f else scale(constants.radicalKernAfterDegree, style)
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
        val degreeRaisePercent = constants.radicalDegreeBottomRaisePercent
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
        rawBase: LaidNode,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
    ): LaidNode {
        val base = rawBase.copy(box = rawBase.box.completedTeXBox())
        val upper = node.superscript
            ?.let { layoutNode(it, style.superscript(), alphabetOverride) }
            ?.let { it.copy(box = it.box.completedTeXBox()) }
        val lower = node.subscript
            ?.let { layoutNode(it, style.subscript(), alphabetOverride) }
            ?.let { it.copy(box = it.box.completedTeXBox()) }
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
            box = geometryExtentsPreservingLogicalChildren(
                logicalWidth,
                shiftedBase.glyphs + shiftedUpper?.glyphs.orEmpty() + shiftedLower?.glyphs.orEmpty(),
                shiftedBase.rules + shiftedUpper?.rules.orEmpty() + shiftedLower?.rules.orEmpty(),
                node.range,
                buildList {
                    add(base.box to 0f)
                    upper?.let { add(it.box to -upperShift!!) }
                    lower?.let { add(it.box to lowerShift!!) }
                },
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
        rawBase: LaidNode,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride?,
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
            superscriptBaselineDropMaxPx = scale(constants.superscriptBaselineDropMax, style),
            subscriptTopMaxPx = scale(constants.subscriptTopMax, style),
            subscriptBaselineDropMinPx = scale(constants.subscriptBaselineDropMin, style),
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
            "subscriptTopMaxPx" to verticalConstraints.subscriptTopMaxPx,
            "subscriptBaselineDropMinPx" to verticalConstraints.subscriptBaselineDropMinPx,
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
            val measured = glyphSource.measureGlyphOutlineBounds(
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
        val ascent = (-top).coerceAtLeast(0f)
        val descent = bottom.coerceAtLeast(0f)
        return copy(
            box = box.copy(
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
        val numerator = refineFractionChildBox(
            layoutNode(node.numerator, style.fractionNumerator(), alphabetOverride).box,
            node,
            "numerator",
        )
        val denominator = refineFractionChildBox(
            layoutNode(node.denominator, style.fractionDenominator(), alphabetOverride).box,
            node,
            "denominator",
        )
        val display = style.level == MathStyleLevel.Display
        val stack = layoutFractionStack(node, style, numerator, denominator, display)
        val fractionNoad = addNullFractionDelimiters(stack, node)
        val withDelimiters = if (node.hasParentheses) {
            addBinomialParentheses(fractionNoad, stack, node, style)
        } else {
            fractionNoad
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
        decision(
            "TeXFractionNullDelimiters",
            node.range,
            "leftSpacePx" to nullDelimiterSpacePx,
            "rightSpacePx" to nullDelimiterSpacePx,
            "parameter" to "nullDelimiterSpacePx",
            "styleInvariant" to true,
        )
        return geometryExtentsPreservingLogicalChildren(
            width = stack.width + 2f * nullDelimiterSpacePx,
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
        val leftBase = glyphSource.shapeOutlineConstructionBase("(", delimiterFontSize, node.range).run
        val rightBase = glyphSource.shapeOutlineConstructionBase(")", delimiterFontSize, node.range).run
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
            if (selected == null) {
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
            val baseGlyphId = baseRun.glyphs.singleOrNull()?.glyphId
            val assemblyValidation = construction?.assemblyValidation
                ?: baseGlyphId?.let(glyphSource.mathFont::verticalAssemblyValidation)
            val componentRuns = construction?.components?.map { component ->
                component to glyphSource.measureOutlineConstructionGlyph(
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
                        baselineY = 0f,
                        advance = glyph.advance,
                        inkBounds = glyph.inkBounds.translated(glyph.x, 0f),
                        fontSizePx = delimiterFontSize,
                        sourceRange = node.range,
                        style = delimiterStyle,
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
                glyphSource.mathFont.scaleDesignUnits(it.advanceMeasurement, delimiterFontSize)
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
        val box = geometryExtentsPreservingLogicalChildren(
            x,
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
            val measured = glyphSource.measureGlyphOutlineBounds(
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
                    "ink=${glyph.inkBounds.left},${glyph.inkBounds.top},${glyph.inkBounds.right},${glyph.inkBounds.bottom} " +
                    "constructionGroup=${glyph.constructionGroupId}",
            )
        }
        box.rules.forEachIndexed { index, rule ->
            appendLine(
                "rule[$index] ${rule.left},${rule.top},${rule.right},${rule.bottom} " +
                    "constructionGroup=${rule.constructionGroupId}",
            )
        }
        box.constructionPaintGroups.forEach { group ->
            appendLine(
                "constructionPaintGroup[${group.id}] kind=${group.kind} shape=${group.shapeKind} " +
                    "range=${group.sourceRange.start}..${group.sourceRange.endExclusive} " +
                    "outlinePolicy=${group.outlinePolicy}",
            )
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
        const val LATEX_XETEX_GENFRAC_DISPLAY_DELIMITER_EM = 2.39f
        const val LATEX_XETEX_GENFRAC_TEXT_DELIMITER_EM = 1f
        const val LATEX_XETEX_GENFRAC_SCRIPT_DELIMITER_EM = 1.45f
        const val LATEX_XETEX_GENFRAC_SCRIPT_SCRIPT_DELIMITER_EM = 1.35f

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
private const val RADICAL_SIGN = "\u221A"

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
