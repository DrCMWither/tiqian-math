package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathLayoutDecision
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.opentype.MathGlyphAssembly
import org.tiqian.math.font.opentype.MathGlyphAssemblyPart
import org.tiqian.math.font.opentype.MathGlyphConstruction
import org.tiqian.math.font.opentype.MathGlyphVariant
import org.tiqian.math.font.opentype.OpenTypeMathConstants
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathOperatorGlyphRequest
import org.tiqian.math.layout.MathSymbolGlyphRequest
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.ResolvedMathOperator
import org.tiqian.math.layout.ResolvedMathSymbol
import org.tiqian.math.layout.ResolvedMathSymbolRun

class MathRadicalNoadTest {
    @Test
    fun crampedRadicandsScriptsRulesAndDegreeGeometryHoldForBothRealFonts() =
        withRadicalFaces { label, face ->
            val size = 48f
            val engine = MathLayoutEngine(face)
            val inline = engine.layout("\\sqrt{x}", MathLayoutOptions(MathMode.Inline, size))
            val display = engine.layout("\\sqrt{x}", MathLayoutOptions(MathMode.Display, size))
            val indexed = engine.layout("\\sqrt[g_j+abc]{x^2+1}", MathLayoutOptions(MathMode.Display, size))
            val fraction = engine.layout("\\sqrt{\\frac{a}{b}}", MathLayoutOptions(MathMode.Display, size))
            val nested = engine.layout("\\sqrt{1+\\sqrt{x}}", MathLayoutOptions(MathMode.Inline, size))
            val scripted = engine.layout("z_{\\sqrt{y}}", MathLayoutOptions(MathMode.Inline, size))
            val scriptedBase = engine.layout("\\sqrt{x}^2", MathLayoutOptions(MathMode.Inline, size))

            listOf(inline, display, indexed, fraction, nested, scripted, scriptedBase).forEach { result ->
                assertTrue(
                    result.diagnostics.all { it.code == DiagnosticCode.MathVariantTooShort },
                    "$label/${result.source}: ${result.diagnostics}\n${result.debugDump}",
                )
                result.decisions.filter { it.name == "TeXRadicalNoad" }.forEach { noad ->
                    assertEquals("Ordinary", noad.details["atomClass"], "$label/${result.source}")
                    assertEquals("CompoundBox", noad.details["scriptBaseKind"], "$label/${result.source}")
                    assertNear(0f, noad.float("italicCorrectionPx"), "$label/${result.source} radical IC")
                }
                result.radicalGeometryDecisions().forEach { geometry ->
                    assertNear(
                        geometry.float("radicalRuleThicknessPx"),
                        geometry.float("ruleBottom") - geometry.float("ruleTop"),
                        "$label/${result.source} rule thickness",
                    )
                    assertAtLeast(
                        geometry.float("actualRadicalGapPx"),
                        geometry.float("radicalVerticalGapPx"),
                        "$label/${result.source} radical gap",
                    )
                    if (geometry.range == SourceRange(0, result.source.length)) {
                        assertNear(
                            geometry.float("logicalWidthPx"),
                            result.box.width,
                            "$label/${result.source} logical advance is the top-level noad width",
                        )
                    }
                }
            }

            assertEquals("TextCramped", inline.radicalNoadDecision().details["radicandStyle"])
            assertEquals("DisplayCramped", display.radicalNoadDecision().details["radicandStyle"])
            assertEquals("ScriptCramped", scripted.radicalNoadDecision().details["radicandStyle"])
            assertEquals("ScriptScript", indexed.radicalNoadDecision().details["degreeStyle"])
            val scriptedBasePlacement = scriptedBase.decisions.single { it.name == "OpenTypeMathScriptPlacement" }
            assertEquals("CompoundBox", scriptedBasePlacement.details["baseKind"], "$label radical is a compound script base")
            assertEquals("true", scriptedBasePlacement.details["baselineDropApplied"], "$label radical uses box baseline drop")
            assertTrue(
                scriptedBase.decisions.any { it.name == "OpenTypeMathKern" && it.details["strategy"] == "box-zero" },
                "$label radical never reuses a character MathKern table",
            )
            indexed.box.glyphs.filter { it.sourceRange.start in setOf(6, 9, 10, 11, 12) }.forEach {
                assertEquals(MathStyle.ScriptScript, it.style, "$label radical degree is scriptscript")
            }
            assertEquals(
                MathStyle.ScriptScriptCramped,
                indexed.box.glyphs.first { it.sourceRange == SourceRange(8, 9) }.style,
                "$label scripts inside the root degree follow the scriptscript transition",
            )
            val inlineGeometry = inline.radicalGeometryDecision()
            val displayGeometry = display.radicalGeometryDecision()
            val indexedGeometry = indexed.radicalGeometryDecision()
            assertEquals("MathMLCore3.3.3.2", indexedGeometry.details["unindexedBoxPolicy"])
            assertEquals("MathMLCore3.3.3.3", indexedGeometry.details["degreePlacementPolicy"])
            assertNear(
                face.mathFont.scaleDesignUnits(face.mathFont.constants.radicalVerticalGap, size),
                inlineGeometry.float("radicalVerticalGapPx"),
                "$label inline gap constant",
            )
            assertNear(
                face.mathFont.scaleDesignUnits(face.mathFont.constants.radicalDisplayStyleVerticalGap, size),
                displayGeometry.float("radicalVerticalGapPx"),
                "$label display gap constant",
            )
            assertNear(
                face.mathFont.scaleDesignUnits(face.mathFont.constants.radicalKernBeforeDegree, size),
                indexedGeometry.float("radicalKernBeforeDegreePx"),
                "$label degree before kern",
            )
            assertNear(
                face.mathFont.scaleDesignUnits(face.mathFont.constants.radicalKernAfterDegree, size),
                indexedGeometry.float("radicalKernAfterDegreePx"),
                "$label degree after kern",
            )
            assertNear(
                indexedGeometry.float("degreeInkBottomY"),
                indexed.box.glyphs.filter { it.sourceRange.start in 6 until 13 }.maxOf { it.inkBounds.bottom },
                "$label recorded degree ink bottom matches replayed placements",
            )
            assertNear(
                indexedGeometry.float("unindexedDescentPx") - indexedGeometry.float("degreeRaisePx"),
                indexedGeometry.float("degreeLogicalBottomY"),
                "$label degree bottom is based on B line descent and block size",
            )
            val scriptSize = size * face.mathFont.constants.scriptPercentScaleDown / 100f
            val constructionBase = face.shapeConstructionBase("√", scriptSize, SourceRange(3, 8)).glyphs.single().glyphId
            assertEquals(
                constructionBase.toString(),
                scripted.radicalConstructionDecision().details["baseGlyphId"],
                "$label script radical queries MathVariants with the base cmap glyph",
            )
            val commandRange = SourceRange(0, 5)
            assertTrue(inline.box.glyphs.any { it.sourceRange == commandRange }, "$label radical glyph source")
            assertEquals(commandRange, inline.box.rules.single().sourceRange, "$label radical rule source")
            assertEquals(SourceRange(0, 8), inline.radicalNoadDecision().range, "$label radical noad range")
            assertNear(-inlineGeometry.float("reservedTopPx"), inline.box.ascent, "$label B line ascent")
            assertTrue(fraction.box.rules.size >= 2, "$label root and fraction rules share LayoutResult")
            assertEquals(2, nested.decisions.count { it.name == "TeXRadicalNoad" }, "$label nested radical noads")
        }

    @Test
    fun tallRadicandsReachRealFontAssembliesAndKeepComponentOriginsAuditable() =
        withRadicalFaces { label, face ->
            val size = 52f
            var source = "x"
            var assemblyResult: MathLayoutResult? = null
            for (depth in 1..8) {
                source = "\\frac{$source}{y}"
                val candidate = MathLayoutEngine(face).layout(
                    "\\sqrt[3]{$source}",
                    MathLayoutOptions(MathMode.Display, size),
                )
                if (candidate.radicalConstructionDecision().details["construction"] == "Assembly") {
                    assemblyResult = candidate
                    break
                }
            }
            val result = assertNotNull(assemblyResult, "$label reaches its real radical assembly")
            val construction = result.radicalConstructionDecision()
            assertEquals("Assembly", construction.details["construction"])
            assertEquals("shared-left/bottom", construction.details["placementOrigin"])
            assertEquals("MathMLCore5.3.1LeftBottom", construction.details["placementPolicy"])
            assertTrue(construction.details["componentGlyphIds"].orEmpty().contains(','), construction.toString())
            assertTrue(construction.details["componentOffsetsDesignUnits"].orEmpty().contains(','), construction.toString())
            assertTrue(construction.float("achievedAdvancePx") + EPSILON >= construction.float("targetHeightPx"))
            assertTrue(result.diagnostics.none { it.code == DiagnosticCode.MissingMathConstruction }, result.debugDump)
            val components = result.box.glyphs.filter { it.sourceRange == SourceRange(0, 5) }
            assertTrue(components.size >= 3, "$label radical has a multi-part real assembly")
            components.sortedBy { it.inkBounds.top }.zipWithNext().forEach { (upper, lower) ->
                assertTrue(
                    upper.inkBounds.bottom + 0.55f >= lower.inkBounds.top,
                    "$label radical has no raster-scale assembly seam: ${upper.inkBounds} then ${lower.inkBounds}",
                )
            }
            val geometry = result.radicalGeometryDecision()
            assertNear(
                geometry.float("unindexedDescentPx") - geometry.float("degreeRaisePx"),
                geometry.float("degreeLogicalBottomY"),
                "$label tall indexed assembly positions the degree from completed box B",
            )
        }

    @Test
    fun baseVariantHeterogeneousAssemblyAndInsufficientConstructionAreDistinctPaths() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val size = 44f
            val range = SourceRange(0, 5)
            val rootGlyph = delegate.shapeConstructionBase("√", size, range).glyphs.single().glyphId
            val rootMetrics = delegate.measureGlyph(rootGlyph, size, MathStyle.Text, range).glyphs.single()
            val (otherGlyph, otherMetrics) = listOf("(", "g", "j", "∑", "∫")
                .map { candidate ->
                    val glyph = delegate.shapeConstructionBase(candidate, size, range).glyphs.single().glyphId
                    glyph to delegate.measureGlyph(glyph, size, MathStyle.Text, range).glyphs.single()
                }.first { (glyph, metrics) ->
                    glyph != rootGlyph &&
                        abs(rootMetrics.inkBounds.bottom - metrics.inkBounds.bottom) > EPSILON &&
                        abs(rootMetrics.inkBounds.height - metrics.inkBounds.height) > EPSILON
                }

            val zeroGeometry = delegate.mathFont.constants.copy(
                radicalVerticalGap = 0,
                radicalDisplayStyleVerticalGap = 0,
                radicalRuleThickness = 0,
                radicalExtraAscender = 0,
            )
            val withoutConstruction = delegate.mathFont.copy(
                constants = zeroGeometry,
                verticalConstructions = delegate.mathFont.verticalConstructions - rootGlyph,
            )
            val base = layout(delegate, withoutConstruction, "\\sqrt{x}", size)
            assertEquals("BaseGlyph", base.radicalConstructionDecision().details["construction"])
            assertTrue(base.diagnostics.none { it.code == DiagnosticCode.MissingMathConstruction }, base.debugDump)

            val variantAdvance = 4_000
            val constructionWhoseVariantsOmitBase = delegate.mathFont.copy(
                constants = zeroGeometry,
                verticalConstructions = delegate.mathFont.verticalConstructions + (
                    rootGlyph to MathGlyphConstruction(
                        variants = listOf(MathGlyphVariant(otherGlyph, variantAdvance)),
                        assembly = null,
                    )
                ),
            )
            val normalGlyph = layout(delegate, constructionWhoseVariantsOmitBase, "\\sqrt{x}", size)
            assertEquals("BaseGlyph", normalGlyph.radicalConstructionDecision().details["construction"])
            assertEquals("NormalGlyphHeight", normalGlyph.radicalConstructionDecision().details["selectionStep"])
            assertEquals("true", normalGlyph.radicalConstructionDecision().details["baseGlyphCoversTarget"])
            assertTrue(normalGlyph.box.glyphs.none { it.glyphId == otherGlyph && it.sourceRange == range })

            val variantFont = constructionWhoseVariantsOmitBase.copy(
                constants = zeroGeometry.copy(radicalVerticalGap = 2_000),
            )
            val variant = layout(delegate, variantFont, "\\sqrt{x}", size)
            assertEquals("Variant", variant.radicalConstructionDecision().details["construction"])
            assertEquals("MathGlyphVariantRecord", variant.radicalConstructionDecision().details["selectionStep"])
            assertTrue(variant.box.glyphs.any { it.glyphId == otherGlyph && it.sourceRange == range })

            val assembly = MathGlyphAssembly(
                parts = listOf(
                    MathGlyphAssemblyPart(rootGlyph, 0, 300, 1_000, false),
                    MathGlyphAssemblyPart(otherGlyph, 300, 0, 1_100, false),
                ),
                minimumConnectorOverlap = 100,
            )
            val assemblyFont = delegate.mathFont.copy(
                constants = zeroGeometry.copy(radicalVerticalGap = 1_000),
                verticalConstructions = delegate.mathFont.verticalConstructions + (
                    rootGlyph to MathGlyphConstruction(emptyList(), assembly)
                ),
            )
            val assembled = layout(delegate, assemblyFont, "\\sqrt{x}", size)
            val selected = assertNotNull(
                assemblyFont.verticalConstruction(
                    rootGlyph,
                    assembled.radicalConstructionDecision().float("targetHeightPx"),
                    size,
                ),
            )
            assertEquals("Assembly", assembled.radicalConstructionDecision().details["construction"])
            val rootPlacement = assembled.box.glyphs.single { it.glyphId == rootGlyph && it.sourceRange == range }
            val otherPlacement = assembled.box.glyphs.single { it.glyphId == otherGlyph && it.sourceRange == range }
            val expectedOriginDelta = -assemblyFont.scaleDesignUnits(
                selected.components[1].offset - selected.components[0].offset,
                size,
            )
            assertNear(
                expectedOriginDelta,
                otherPlacement.inkBounds.bottom - rootPlacement.inkBounds.bottom,
                "heterogeneous radical component bottoms follow MATH advance offsets",
            )
            val expectedBaselineDelta = expectedOriginDelta -
                (otherMetrics.inkBounds.bottom - rootMetrics.inkBounds.bottom)
            assertNear(
                expectedBaselineDelta,
                otherPlacement.baselineY - rootPlacement.baselineY,
                "component bottoms, not baselines, are the assembly placement origins",
            )
            assertTrue(assembled.radicalConstructionDecision().details["connectorOverlapsDesignUnits"].orEmpty() != "[]")
            assertEquals("shared-left/bottom", assembled.radicalConstructionDecision().details["placementOrigin"])

            val insufficientFont = delegate.mathFont.copy(
                constants = zeroGeometry.copy(radicalVerticalGap = 2_000),
                verticalConstructions = delegate.mathFont.verticalConstructions + (
                    rootGlyph to MathGlyphConstruction(listOf(MathGlyphVariant(rootGlyph, 1)), null)
                ),
            )
            val insufficient = layout(delegate, insufficientFont, "\\sqrt{x}", size)
            assertTrue(insufficient.diagnostics.any { it.code == DiagnosticCode.MathVariantTooShort })
            assertEquals("false", insufficient.radicalConstructionDecision().details["reachesTarget"])
        }
    }

    @Test
    fun logicalReserveDoesNotLeakIntoTheInkBasedStretchTarget() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val size = 72f
            val source = "\\sqrt{\\sqrt{x}}"
            val outerCommandRange = SourceRange(0, 5)
            val zero = delegate.mathFont.constants.copy(
                radicalVerticalGap = 0,
                radicalDisplayStyleVerticalGap = 0,
                radicalRuleThickness = 0,
                radicalExtraAscender = 0,
            )
            val withoutReserve = layout(delegate, delegate.mathFont.copy(constants = zero), source, size)
            val withReserve = layout(
                delegate,
                delegate.mathFont.copy(constants = zero.copy(radicalExtraAscender = 3_000)),
                source,
                size,
            )
            val baseChoice = withoutReserve.decisions.single {
                it.name == "OpenTypeRadicalConstruction" && it.range == outerCommandRange
            }
            val reservedChoice = withReserve.decisions.single {
                it.name == "OpenTypeRadicalConstruction" && it.range == outerCommandRange
            }
            assertEquals("RadicandInkHeightPlusGapAndRule", baseChoice.details["targetMetric"])
            assertEquals("MathMLCore5.3.2NormalGlyphFirst", baseChoice.details["selectionPolicy"])
            assertNear(
                baseChoice.float("radicandInkHeightPx"),
                reservedChoice.float("radicandInkHeightPx"),
                "inner radical keeps identical visible ink",
            )
            assertTrue(
                reservedChoice.float("radicandLogicalHeightPx") >
                    baseChoice.float("radicandLogicalHeightPx") + 100f,
                "fixture must alter only the recursive logical reserve",
            )
            assertNear(
                baseChoice.float("targetHeightPx"),
                reservedChoice.float("targetHeightPx"),
                "logical reserve does not enter radical stretch target",
            )
            assertEquals(baseChoice.details["construction"], reservedChoice.details["construction"])
            assertEquals(baseChoice.details["componentGlyphIds"], reservedChoice.details["componentGlyphIds"])
        }
    }

    @Test
    fun extremeConstantsIndependentlyControlEveryNamedRadicalCoordinate() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val size = 100f
            val zero = delegate.mathFont.constants.copy(
                radicalVerticalGap = 0,
                radicalDisplayStyleVerticalGap = 0,
                radicalRuleThickness = 0,
                radicalExtraAscender = 0,
                radicalKernBeforeDegree = 0,
                radicalKernAfterDegree = 0,
                radicalDegreeBottomRaisePercent = 0,
            )
            val inlineBase = radicalGeometry(delegate, zero, MathMode.Inline, "\\sqrt{x}", size)
            val inlineGap = radicalGeometry(
                delegate,
                zero.copy(radicalVerticalGap = 1_100),
                MathMode.Inline,
                "\\sqrt{x}",
                size,
            )
            assertNear(110f, inlineGap.float("radicalVerticalGapPx"), "RadicalVerticalGap")
            assertNear(110f, inlineGap.float("targetHeightPx") - inlineBase.float("targetHeightPx"), "inline target")

            val displayBase = radicalGeometry(delegate, zero, MathMode.Display, "\\sqrt{x}", size)
            val displayGap = radicalGeometry(
                delegate,
                zero.copy(radicalDisplayStyleVerticalGap = 1_300),
                MathMode.Display,
                "\\sqrt{x}",
                size,
            )
            assertNear(130f, displayGap.float("radicalVerticalGapPx"), "RadicalDisplayStyleVerticalGap")
            assertNear(130f, displayGap.float("targetHeightPx") - displayBase.float("targetHeightPx"), "display target")

            val rule = radicalGeometry(
                delegate,
                zero.copy(radicalRuleThickness = 170),
                MathMode.Inline,
                "\\sqrt{x}",
                size,
            )
            assertNear(17f, rule.float("ruleBottom") - rule.float("ruleTop"), "RadicalRuleThickness rule")
            assertNear(17f, rule.float("targetHeightPx") - inlineBase.float("targetHeightPx"), "rule target")

            val ascenderResult = layout(
                delegate,
                delegate.mathFont.copy(constants = zero.copy(radicalExtraAscender = 3_000)),
                "\\sqrt{x}",
                size,
            )
            val ascender = ascenderResult.radicalGeometryDecision()
            assertNear(300f, ascender.float("radicalExtraAscenderPx"), "RadicalExtraAscender")
            assertNear(
                ascender.float("ruleTop") - 300f,
                ascender.float("reservedTopPx"),
                "extra ascender reserve",
            )
            assertAtLeast(ascenderResult.box.ascent, -ascender.float("reservedTopPx"), "extra ascender box height")
            val nestedAscender = layout(
                delegate,
                delegate.mathFont.copy(constants = zero.copy(radicalExtraAscender = 3_000)),
                "\\frac{\\sqrt{x}}{y}",
                size,
            )
            val nestedRadical = nestedAscender.radicalGeometryDecision()
            val numeratorShift = nestedAscender.decisions.first { it.name == "OpenTypeMathFractionStack" }
                .float("numeratorShiftPx")
            assertAtLeast(
                nestedAscender.box.ascent,
                -nestedRadical.float("reservedTopPx") + numeratorShift,
                "RadicalExtraAscender survives fraction and null-delimiter boxing",
            )

            val indexedBase = radicalGeometry(delegate, zero, MathMode.Inline, "\\sqrt[3]{x}", size)
            val before = radicalGeometry(
                delegate,
                zero.copy(radicalKernBeforeDegree = 700),
                MathMode.Inline,
                "\\sqrt[3]{x}",
                size,
            )
            assertNear(70f, before.float("degreeX"), "RadicalKernBeforeDegree degree x")
            assertNear(70f, before.float("radicalX") - indexedBase.float("radicalX"), "before kern radical x")

            val after = radicalGeometry(
                delegate,
                zero.copy(radicalKernAfterDegree = 900),
                MathMode.Inline,
                "\\sqrt[3]{x}",
                size,
            )
            assertNear(90f, after.float("radicalX") - indexedBase.float("radicalX"), "RadicalKernAfterDegree")

            val raised = radicalGeometry(
                delegate,
                zero.copy(radicalDegreeBottomRaisePercent = 80),
                MathMode.Inline,
                "\\sqrt[3]{x}",
                size,
            )
            assertNear(
                -0.8f * raised.float("unindexedBlockSizePx"),
                raised.float("degreeLogicalBottomY") - indexedBase.float("degreeLogicalBottomY"),
                "RadicalDegreeBottomRaisePercent",
            )

            val indexedRaiseBase = radicalGeometry(
                delegate,
                zero.copy(radicalDegreeBottomRaisePercent = 60),
                MathMode.Inline,
                "\\sqrt[3]{x}",
                size,
            )
            val indexedWithAscender = radicalGeometry(
                delegate,
                zero.copy(
                    radicalExtraAscender = 3_000,
                    radicalDegreeBottomRaisePercent = 60,
                ),
                MathMode.Inline,
                "\\sqrt[3]{x}",
                size,
            )
            assertTrue(
                indexedWithAscender.float("unindexedBlockSizePx") >
                    indexedRaiseBase.float("unindexedBlockSizePx"),
                "RadicalExtraAscender must enlarge the already-built box B",
            )
            assertNear(
                indexedWithAscender.float("unindexedDescentPx") -
                    indexedWithAscender.float("degreeRaisePx"),
                indexedWithAscender.float("degreeLogicalBottomY"),
                "degree uses B line descent after ExtraAscender is applied",
            )
            assertTrue(
                indexedWithAscender.float("degreeBaselineY") < indexedRaiseBase.float("degreeBaselineY"),
                "larger B block size raises the index",
            )
        }
    }

    @Test
    fun signedDegreeKernsAreClampedBeforeLogicalWidthAndPlacement() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val size = 50f
            val constants = delegate.mathFont.constants.copy(
                radicalKernBeforeDegree = -1_200,
                radicalKernAfterDegree = -5_000,
            )
            val result = layout(
                delegate,
                delegate.mathFont.copy(constants = constants),
                "\\sqrt[g_j+abc]{x}",
                size,
            )
            val geometry = result.radicalGeometryDecision()
            assertNear(-60f, geometry.float("radicalKernBeforeDegreePx"), "raw signed before kern remains auditable")
            assertNear(0f, geometry.float("adjustedRadicalKernBeforeDegreePx"), "before kern clamps to zero")
            assertNear(0f, geometry.float("degreeX"), "index starts at adjusted before kern")
            assertNear(
                -geometry.float("degreeWidthPx"),
                geometry.float("adjustedRadicalKernAfterDegreePx"),
                "after kern cannot overlap more than the complete index width",
            )
            assertNear(0f, geometry.float("unindexedX"), "fully overlapping index leaves B at logical origin")
            assertTrue(result.box.width >= 0f, result.debugDump)
            assertNear(geometry.float("logicalWidthPx"), result.box.width, "clamped width is the public advance")
            assertEquals("0.0", result.radicalNoadDecision().details["italicCorrectionPx"])
        }
    }

    private fun radicalGeometry(
        delegate: SkiaMathFontFace,
        constants: OpenTypeMathConstants,
        mode: MathMode,
        source: String,
        size: Float,
    ): MathLayoutDecision = layout(
        delegate,
        delegate.mathFont.copy(constants = constants),
        source,
        size,
        mode,
    ).radicalGeometryDecision()

    private fun layout(
        delegate: SkiaMathFontFace,
        font: OpenTypeMathFont,
        source: String,
        size: Float,
        mode: MathMode = MathMode.Inline,
    ): MathLayoutResult = MathLayoutEngine(RadicalOverrideFace(delegate, font)).layout(
        source,
        MathLayoutOptions(mode, size),
    )
}

private class RadicalOverrideFace(
    private val delegate: SkiaMathFontFace,
    override val mathFont: OpenTypeMathFont,
) : MathFontFace {
    override fun resolveSymbol(request: MathSymbolGlyphRequest, fontSizePx: Float): ResolvedMathSymbol =
        delegate.resolveSymbol(request, fontSizePx)

    override fun resolveOperator(request: MathOperatorGlyphRequest, fontSizePx: Float): ResolvedMathOperator =
        delegate.resolveOperator(request, fontSizePx)

    override fun resolveSymbols(
        requests: List<MathSymbolGlyphRequest>,
        fontSizePx: Float,
    ): ResolvedMathSymbolRun = delegate.resolveSymbols(requests, fontSizePx)

    override fun shape(
        text: String,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.shape(text, fontSizePx, style, sourceRange)

    override fun measureGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.measureGlyph(glyphId, fontSizePx, style, sourceRange)
}

private fun MathLayoutResult.radicalNoadDecision(): MathLayoutDecision =
    decisions.first { it.name == "TeXRadicalNoad" }

private fun MathLayoutResult.radicalConstructionDecision(): MathLayoutDecision =
    decisions.first { it.name == "OpenTypeRadicalConstruction" }

private fun MathLayoutResult.radicalGeometryDecision(): MathLayoutDecision =
    decisions.first { it.name == "OpenTypeMathRadical" }

private fun MathLayoutResult.radicalGeometryDecisions(): List<MathLayoutDecision> =
    decisions.filter { it.name == "OpenTypeMathRadical" }

private fun MathLayoutDecision.float(name: String): Float =
    checkNotNull(details[name]) { "$name is absent from $this" }.toFloat()

private inline fun withRadicalFaces(block: (String, SkiaMathFontFace) -> Unit) {
    listOf(
        "Lete Sans Math" to LeteSansMath.load(),
        "STIX Two Math" to StixTwoMath.load(),
    ).forEach { (label, font) -> SkiaMathFontFace(font).use { block(label, it) } }
}

private fun assertNear(expected: Float, actual: Float, message: String) {
    assertTrue(abs(expected - actual) <= EPSILON, "$message: expected=$expected actual=$actual")
}

private fun assertAtLeast(actual: Float, minimum: Float, message: String) {
    assertTrue(actual + EPSILON >= minimum, "$message: actual=$actual minimum=$minimum")
}

private const val EPSILON = 0.05f
