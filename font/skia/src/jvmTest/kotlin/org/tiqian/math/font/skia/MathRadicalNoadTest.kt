package org.tiqian.math.font.skia

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Point
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextBlobBuilder
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathGlyphPlacement
import org.tiqian.math.core.MathLayoutDecision
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathRulePlacement
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
import org.tiqian.math.layout.MeasuredOutlineConstructionRun
import org.tiqian.math.layout.ResolvedMathOperator
import org.tiqian.math.layout.ResolvedMathSymbol
import org.tiqian.math.layout.ResolvedMathSymbolRun

class MathRadicalNoadTest {
    @Test
    fun degreeRaiseUsesSelectedRadicalBoxHeightInsteadOfUnindexedBoxReserveForBothFonts() =
        withRadicalFaces { label, face ->
            val size = 48f
            val assemblyRadicand = (1..12).fold("x") { radicand, _ -> "\\frac{$radicand}{y}" }
            val cases = listOf(
                Triple("base", "\\sqrt[2]{x}", "BaseGlyph"),
                Triple("variant", "\\sqrt[3]{\\frac{a}{b}}", "Variant"),
                Triple("assembly", "\\sqrt[5]{$assemblyRadicand}", "Assembly"),
            )
            val selectedBlockSizes = mutableListOf<Float>()
            val selectedRaises = mutableListOf<Float>()

            cases.forEach { (caseLabel, source, expectedConstruction) ->
                val base = MathLayoutEngine(face).layout(
                    source,
                    MathLayoutOptions(MathMode.Display, size),
                )
                val extraReserveFace = RadicalOverrideFace(
                    face,
                    face.mathFont.copy(
                        constants = face.mathFont.constants.copy(radicalExtraAscender = 3_000),
                    ),
                )
                val withExtraReserve = MathLayoutEngine(extraReserveFace).layout(
                    source,
                    MathLayoutOptions(MathMode.Display, size),
                )
                val baseConstruction = base.radicalConstructionDecision()
                val reserveConstruction = withExtraReserve.radicalConstructionDecision()
                val baseGeometry = base.radicalGeometryDecision()
                val reserveGeometry = withExtraReserve.radicalGeometryDecision()

                assertEquals(expectedConstruction, baseConstruction.details["construction"], "$label/$caseLabel")
                assertEquals(
                    expectedConstruction,
                    reserveConstruction.details["construction"],
                    "$label/$caseLabel reserve-only change keeps the selected construction",
                )
                assertNear(
                    baseConstruction.float("achievedAdvancePx"),
                    reserveConstruction.float("achievedAdvancePx"),
                    "$label/$caseLabel reserve-only change keeps the construction extent",
                )
                assertTrue(
                    reserveGeometry.float("unindexedBlockSizePx") > baseGeometry.float("unindexedBlockSizePx"),
                    "$label/$caseLabel RadicalExtraAscender must enlarge B",
                )
                assertNear(
                    baseGeometry.float("degreeRaisePx"),
                    reserveGeometry.float("degreeRaisePx"),
                    "$label/$caseLabel B reserve must not change degree raise",
                )
                assertNear(
                    baseGeometry.float("degreeRaiseReferencePx"),
                    reserveGeometry.float("degreeRaiseReferencePx"),
                    "$label/$caseLabel B reserve must not change the selected radical reference",
                )
                val selectedAscent = baseGeometry.float("radicalGlyphAscentPx")
                val selectedDescent = baseGeometry.float("radicalGlyphDescentPx")
                val selectedBlockSize = baseGeometry.float("radicalGlyphBlockSizePx")
                if (expectedConstruction == "BaseGlyph" || expectedConstruction == "Variant") {
                    assertTrue(
                        selectedDescent > 0f,
                        "$label/$caseLabel must exercise a selected radical with non-zero descent",
                    )
                }
                selectedBlockSizes += selectedBlockSize
                selectedRaises += baseGeometry.float("degreeRaisePx")
                assertNear(
                    selectedAscent + selectedDescent,
                    selectedBlockSize,
                    "$label/$caseLabel selected radical box height closes over ascent and descent",
                )
                assertNear(
                    selectedBlockSize,
                    baseGeometry.float("degreeRaiseReferencePx"),
                    "$label/$caseLabel degree raise references the complete selected radical box height",
                )
                assertNear(
                    baseGeometry.float("degreeRaiseReferencePx") *
                        baseGeometry.float("radicalDegreeBottomRaisePercent") / 100f,
                    baseGeometry.float("degreeRaisePx"),
                    "$label/$caseLabel degree raise applies the MATH percentage",
                )
                assertEquals(
                    "SelectedRadicalConstructionBoxHeight",
                    baseGeometry.details["degreeRaiseReferenceMetric"],
                    "$label/$caseLabel reference metric",
                )
                assertEquals(
                    "OpenTypeMATH.RadicalDegreeBottomRaisePercentTimesSelectedRadicalConstructionBoxHeight",
                    baseGeometry.details["degreeRaiseReferencePolicy"],
                    "$label/$caseLabel reference policy",
                )
                assertNear(
                    selectedAscent,
                    baseGeometry.float("degreeRaiseReferenceAscentPx"),
                    "$label/$caseLabel decision records the selected radical ascent",
                )
                assertNear(
                    selectedDescent,
                    baseGeometry.float("degreeRaiseReferenceDescentPx"),
                    "$label/$caseLabel decision records the selected radical descent",
                )
                assertEquals(
                    "OpenTypeRadicalConstructionBoxHeightRaiseFromCompletedBLineDescent",
                    baseGeometry.details["degreePlacementPolicy"],
                    "$label/$caseLabel placement policy",
                )
                assertEquals(
                    "OpenTypeMATHRadicalSignFullBoxHeightMapping;NotMathMLCore3.3.3.3UnindexedBlockSize",
                    baseGeometry.details["degreePlacementSpecificationDivergence"],
                    "$label/$caseLabel specification mapping",
                )
                assertNear(
                    baseGeometry.float("unindexedDescentPx") - baseGeometry.float("degreeRaisePx"),
                    baseGeometry.float("degreeLogicalBottomY"),
                    "$label/$caseLabel degree logical bottom remains relative to completed B",
                )

                val noad = base.radicalNoadDecision()
                assertEquals(SourceRange(0, source.length), noad.range, "$label/$caseLabel noad source range")
                assertEquals(
                    "SourceRange(start=5, endExclusive=8)",
                    noad.details["degreeRange"],
                    "$label/$caseLabel degree source range",
                )
                assertEquals("ScriptScript", noad.details["degreeStyle"], "$label/$caseLabel degree style")
                val degreeGlyphs = base.box.glyphs.filter { it.sourceRange == SourceRange(6, 7) }
                assertTrue(degreeGlyphs.isNotEmpty(), "$label/$caseLabel degree glyph retains source range")
                degreeGlyphs.forEach {
                    assertEquals(MathStyle.ScriptScript, it.style, "$label/$caseLabel degree glyph style")
                }
                assertNear(
                    degreeGlyphs.maxOf { it.inkBounds.bottom },
                    baseGeometry.float("degreeInkBottomY"),
                    "$label/$caseLabel degree ink bottom is replayable from source glyphs",
                )
            }

            selectedBlockSizes.zipWithNext().forEach { (smaller, larger) ->
                assertTrue(larger > smaller, "$label construction growth must increase radical box height")
            }
            selectedRaises.zipWithNext().forEach { (smaller, larger) ->
                assertTrue(larger > smaller, "$label construction growth must proportionally increase degree raise")
            }
        }

    @Test
    fun selectedConstructionExcessIsSplitAcrossTheRadicalClearanceForBothFonts() =
        withRadicalFaces { label, face ->
            val size = 48f
            val cases = listOf(
                "x" to "\\sqrt{x}",
                "X" to "\\sqrt{X}",
                "ascender-descender" to "\\sqrt{x_j^2}",
                "variant" to "\\sqrt{\\frac{a}{b}}",
                "assembly" to ("\\sqrt{" +
                    (1..12).fold("x") { radicand, _ -> "\\frac{$radicand}{y}" } +
                    "}"),
            )
            val selectedKinds = mutableSetOf<String>()

            cases.forEach { (caseLabel, source) ->
                val result = MathLayoutEngine(face).layout(
                    source,
                    MathLayoutOptions(MathMode.Display, size),
                )
                val construction = result.radicalConstructionDecision()
                val geometry = result.radicalGeometryDecision()
                val target = construction.float("targetHeightPx")
                val selectedExtent = construction.float("achievedAdvancePx")
                val expectedExcess = (selectedExtent - target).coerceAtLeast(0f)
                val minimumGap = geometry.float("radicalVerticalGapPx")
                val expectedActualGap = minimumGap + expectedExcess / 2f

                selectedKinds += construction.details.getValue("construction")
                assertNear(
                    expectedActualGap,
                    geometry.float("actualRadicalGapPx"),
                    "$label/$caseLabel actual clearance is the minimum plus half selected-construction excess",
                )
                assertNear(
                    geometry.float("radicandInkTopPx") - expectedActualGap,
                    geometry.float("ruleBottom"),
                    "$label/$caseLabel overbar bottom closes against radicand ink",
                )
                assertRadicalBoxAlgebra(geometry, "$label/$caseLabel")
            }

            assertTrue("BaseGlyph" in selectedKinds, "$label must cover the normal radical glyph")
            assertTrue("Variant" in selectedKinds, "$label must cover a MATH radical variant")
            assertTrue("Assembly" in selectedKinds, "$label must cover a MATH radical assembly")
        }

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
                    assertRadicalBoxAlgebra(geometry, "$label/${result.source}")
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
            assertEquals(
                "TeXMakeRadicalHalfPositiveConstructionExcess",
                indexedGeometry.details["unindexedBoxPolicy"],
            )
            assertEquals(
                "OpenTypeRadicalConstructionBoxHeightRaiseFromCompletedBLineDescent",
                indexedGeometry.details["degreePlacementPolicy"],
            )
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
            val rootGlyph = face.shapeConstructionBase("√", size, SourceRange(0, 5)).glyphs.single().glyphId
            val validation = face.mathFont.verticalAssemblyValidation(rootGlyph)
            val result = assertNotNull(
                assemblyResult,
                "$label reaches its real radical assembly; validation=$validation",
            )
            val construction = result.radicalConstructionDecision()
            assertEquals("Assembly", construction.details["construction"])
            assertEquals("shared-font-x/bottom", construction.details["placementOrigin"])
            assertEquals("MathMLCore5.3.1SharedFontOriginBottom", construction.details["placementPolicy"])
            assertEquals("MathMLCore5.3.1UniformOverlap", construction.details["constructionPolicy"])
            assertEquals("true", construction.details["assemblyValid"])
            assertEquals(
                "TiqianOpenTypeTerminalConnectorCompatibility",
                construction.details["assemblyValidationPolicy"],
            )
            assertEquals(
                "MathMLCore5.3.1RequiresEveryTerminalConnectorAtLeastMinimum",
                construction.details["assemblySpecificationDivergence"],
            )
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
            assertAssemblyTopAlignedToOverbar(geometry, "$label real assembly")
            assertNear(
                geometry.float("unindexedDescentPx") - geometry.float("degreeRaisePx"),
                geometry.float("degreeLogicalBottomY"),
                "$label tall indexed assembly positions the degree from completed box B",
            )
        }

    @Test
    fun realAssemblyBoxAscentAlignsTheOverbarForBothFontsAcrossSizesAndDepths() =
        withRadicalFaces { label, face ->
            listOf(32f, 52f).forEach { size ->
                var radicand = "x"
                val assemblies = mutableListOf<Pair<Int, MathLayoutResult>>()
                for (depth in 1..14) {
                    radicand = "\\frac{$radicand}{y}"
                    val candidate = MathLayoutEngine(face).layout(
                        "\\sqrt{$radicand}",
                        MathLayoutOptions(MathMode.Display, size),
                    )
                    if (candidate.radicalConstructionDecision().details["construction"] == "Assembly") {
                        assemblies += depth to candidate
                        if (assemblies.size == 2) break
                    }
                }
                assertEquals(2, assemblies.size, "$label/$size reaches two assembly depths")
                assemblies.forEach { (depth, result) ->
                    val construction = result.radicalConstructionDecision()
                    val geometry = result.radicalGeometryDecision()
                    assertTrue(
                        construction.float("achievedAdvancePx") + EPSILON >=
                            construction.float("targetHeightPx"),
                        "$label/$size/depth=$depth nominal extent still drives selection",
                    )
                    assertEquals(
                        "NominalAdvanceForSelectionActualPlacedOutlineBoundsForBox",
                        construction.details["constructionExtentPolicy"],
                    )
                    assertEquals("PlacedAssemblyOutlineBounds", geometry.details["radicalGlyphBoxMetricSource"])
                    assertNear(
                        construction.float("constructionBoxAscentPx"),
                        geometry.float("radicalGlyphAscentPx"),
                        "$label/$size/depth=$depth consumes actual placed box ascent",
                    )
                    assertNear(
                        construction.float("constructionBoxDescentPx"),
                        geometry.float("radicalGlyphDescentPx"),
                        "$label/$size/depth=$depth consumes actual placed box descent",
                    )
                    assertAssemblyTopAlignedToOverbar(geometry, "$label/$size/depth=$depth")
                }
            }
        }

    @Test
    fun stixTallRadicalAssemblyConnectorsAndOverbarAreOneRasterContour() {
        SkiaMathFontFace(StixTwoMath.load()).use { face ->
            val size = 52f
            var source = "x"
            var result: MathLayoutResult? = null
            for (depth in 1..10) {
                source = "\\frac{$source}{y}"
                val candidate = MathLayoutEngine(face).layout(
                    "\\sqrt{$source}",
                    MathLayoutOptions(MathMode.Display, size),
                )
                if (candidate.radicalConstructionDecision().details["construction"] == "Assembly") {
                    result = candidate
                    break
                }
            }
            val assembled = assertNotNull(result, "STIX fixture reaches a real radical assembly")
            val commandRange = SourceRange(0, 5)
            val construction = assembled.radicalConstructionDecision()
            assertEquals("shared-font-x/bottom", construction.details["placementOrigin"])
            val horizontalOrigins = construction.details.getValue("componentHorizontalOriginsPx")
                .split(',')
                .map(String::toFloat)
            assertTrue(
                horizontalOrigins.max() - horizontalOrigins.min() <= EPSILON,
                "all STIX parts retain one font x origin: $horizontalOrigins",
            )

            val components = assembled.box.glyphs.filter { it.sourceRange == commandRange }
            assertTrue(components.size >= 3, "STIX fixture uses multiple real assembly parts")
            components.sortedBy { it.inkBounds.top }.zipWithNext().forEach { (upper, lower) ->
                val horizontalIntersection = minOf(upper.inkBounds.right, lower.inkBounds.right) -
                    maxOf(upper.inkBounds.left, lower.inkBounds.left)
                val verticalIntersection = minOf(upper.inkBounds.bottom, lower.inkBounds.bottom) -
                    maxOf(upper.inkBounds.top, lower.inkBounds.top)
                assertTrue(
                    horizontalIntersection > EPSILON && verticalIntersection >= -EPSILON,
                    "STIX connector must intersect in two dimensions: upper=${upper.inkBounds}, " +
                        "lower=${lower.inkBounds}",
                )
            }

            val overbar = assembled.box.rules.single { it.sourceRange == commandRange }
            val top = components.minBy { it.inkBounds.top }
            val topBarHorizontalIntersection = minOf(top.inkBounds.right, overbar.right) -
                maxOf(top.inkBounds.left, overbar.left)
            val topBarVerticalIntersection = minOf(top.inkBounds.bottom, overbar.bottom) -
                maxOf(top.inkBounds.top, overbar.top)
            assertTrue(
                topBarHorizontalIntersection >= -EPSILON && topBarVerticalIntersection >= -EPSILON,
                "STIX top part must join the overbar: top=${top.inkBounds}, bar=$overbar",
            )

            val rasterComponents = rasterConnectedComponentSizes(face, components, overbar, scale = 3)
            assertEquals(
                1,
                rasterComponents.size,
                "STIX radical sign and overbar must rasterize as one connected contour; " +
                    "component sizes=$rasterComponents",
            )
        }
    }

    @Test
    fun baseVariantHeterogeneousAssemblyAndInsufficientConstructionAreDistinctPaths() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val size = 44f
            val range = SourceRange(0, 5)
            val rootGlyph = delegate.shapeConstructionBase("√", size, range).glyphs.single().glyphId
            val rootMetrics = delegate
                .measureOutlineConstructionGlyph(rootGlyph, size, MathStyle.Text, range)
                .run.glyphs.single()
            val (otherGlyph, otherMetrics) = listOf("(", "g", "j", "∑", "∫")
                .map { candidate ->
                    val glyph = delegate.shapeConstructionBase(candidate, size, range).glyphs.single().glyphId
                    glyph to delegate
                        .measureOutlineConstructionGlyph(glyph, size, MathStyle.Text, range)
                        .run.glyphs.single()
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
            assertRadicalBoxAlgebra(base.radicalGeometryDecision(), "base radical")

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
            assertRadicalBoxAlgebra(variant.radicalGeometryDecision(), "variant radical")

            val assembly = MathGlyphAssembly(
                parts = listOf(
                    MathGlyphAssemblyPart(rootGlyph, 100, 300, 1_000, false),
                    MathGlyphAssemblyPart(otherGlyph, 300, 100, 1_100, true),
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
                assemblyFont.verticalConstructionForTest(
                    delegate,
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
            assertEquals("shared-font-x/bottom", assembled.radicalConstructionDecision().details["placementOrigin"])
            assertRadicalBoxAlgebra(assembled.radicalGeometryDecision(), "assembly radical")

            val insufficientFont = delegate.mathFont.copy(
                constants = zeroGeometry.copy(radicalVerticalGap = 2_000),
                verticalConstructions = delegate.mathFont.verticalConstructions + (
                    rootGlyph to MathGlyphConstruction(listOf(MathGlyphVariant(rootGlyph, 1)), null)
                ),
            )
            val insufficient = layout(delegate, insufficientFont, "\\sqrt{x}", size)
            assertTrue(insufficient.diagnostics.any { it.code == DiagnosticCode.MathVariantTooShort })
            assertEquals("false", insufficient.radicalConstructionDecision().details["reachesTarget"])

            val invalidAssemblyFont = delegate.mathFont.copy(
                constants = zeroGeometry.copy(radicalVerticalGap = 2_000),
                verticalConstructions = delegate.mathFont.verticalConstructions + (
                    rootGlyph to MathGlyphConstruction(
                        emptyList(),
                        MathGlyphAssembly(
                            parts = listOf(
                                MathGlyphAssemblyPart(rootGlyph, 100, 100, 100, true),
                            ),
                            minimumConnectorOverlap = 100,
                        ),
                    )
                ),
            )
            val invalid = layout(delegate, invalidAssemblyFont, "\\sqrt{x}", size)
            val invalidDecision = invalid.radicalConstructionDecision()
            assertEquals("false", invalidDecision.details["assemblyValid"])
            assertTrue(
                invalidDecision.details["assemblyInvalidReasons"].orEmpty()
                    .contains("NonPositiveExtenderGrowth"),
                invalidDecision.toString(),
            )
            assertEquals(
                "MathMLCore5.3.2FailureAfterInvalidAssembly",
                invalidDecision.details["constructionPolicy"],
            )
            assertTrue(invalid.diagnostics.any { it.code == DiagnosticCode.MissingMathConstruction })
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
    fun radicalRuleStartsAfterAllPartRecordsWidthWhenRMinSkipsTheWidestExtender() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val size = 44f
            val range = SourceRange(0, 5)
            val root = delegate.shapeConstructionBase("√", size, range).glyphs.single().glyphId
            val skippedWideExtender = delegate.shapeConstructionBase("W", size, range).glyphs.single().glyphId
            val assembly = MathGlyphAssembly(
                parts = listOf(
                    MathGlyphAssemblyPart(root, 100, 300, 1_500, false),
                    MathGlyphAssemblyPart(skippedWideExtender, 300, 300, 600, true),
                    MathGlyphAssemblyPart(root, 300, 100, 1_500, false),
                ),
                minimumConnectorOverlap = 100,
            )
            val font = delegate.mathFont.copy(
                constants = delegate.mathFont.constants.copy(
                    radicalVerticalGap = 1_000,
                    radicalRuleThickness = 0,
                    radicalExtraAscender = 0,
                ),
                verticalConstructions = delegate.mathFont.verticalConstructions +
                    (root to MathGlyphConstruction(emptyList(), assembly)),
            )
            val face = RadicalOverrideFace(
                delegate,
                font,
                advanceWidthOverrides = mapOf(skippedWideExtender to 200f),
            )
            val result = MathLayoutEngine(face).layout("\\sqrt{x}", MathLayoutOptions(fontSizePx = size))
            val construction = result.radicalConstructionDecision()
            val geometry = result.radicalGeometryDecision()
            assertEquals("Assembly", construction.details["construction"])
            assertEquals("0", construction.details["extenderRepetitions"])
            assertTrue(
                construction.details["componentGlyphIds"].orEmpty()
                    .split(',')
                    .none { it == skippedWideExtender.toString() },
            )
            assertNear(200f, construction.float("orthogonalAdvancePx"), "all-record assembly width")
            assertNear(200f, geometry.float("radicalBoxAdvancePx"), "radical box consumes construction width")
            assertNear(
                geometry.float("radicalX") + geometry.float("radicalTopStrokeRightPx"),
                geometry.float("ruleLeft"),
                "overbar starts at the selected top component's outline anchor",
            )
            assertRadicalBoxAlgebra(geometry, "rMin=0 assembly radical")
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
                -0.8f * raised.float("degreeRaiseReferencePx"),
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
            assertNear(
                indexedRaiseBase.float("degreeRaisePx"),
                indexedWithAscender.float("degreeRaisePx"),
                "RadicalExtraAscender does not alter the radical-sign ascender reference",
            )
            assertNear(
                indexedRaiseBase.float("degreeBaselineY"),
                indexedWithAscender.float("degreeBaselineY"),
                "top-only B reserve does not move the degree from the unchanged line-under edge",
            )
        }
    }

    @Test
    fun signedDegreeKernsUseTheTeXMakeRadicalAfterClamp() {
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
            assertNear(-60f, geometry.float("usedRadicalKernBeforeDegreePx"), "TeX preserves signed before kern")
            assertNear(-60f, geometry.float("degreeX"), "index starts at the signed before kern")
            val expectedAfterClampLowerBound = -(geometry.float("degreeWidthPx") - 60f)
            assertNear(
                expectedAfterClampLowerBound,
                geometry.float("radicalDegreeAfterKernClampLowerBoundPx"),
                "after lower bound includes degree width and signed before kern",
            )
            assertNear(
                expectedAfterClampLowerBound,
                geometry.float("adjustedRadicalKernAfterDegreePx"),
                "after kern cannot overlap more than degree width plus signed before kern",
            )
            assertNear(0f, geometry.float("unindexedX"), "fully overlapping index leaves B at logical origin")
            assertTrue(result.box.width >= 0f, result.debugDump)
            assertNear(geometry.float("logicalWidthPx"), result.box.width, "clamped width is the public advance")
            assertEquals(
                "TeXMakeRadicalSignedBeforeAndWidthPlusBeforeAfterClamp",
                geometry.details["degreeHorizontalPlacementPolicy"],
            )
            assertTrue(
                result.debugDump.contains(
                    "radicalDegreeAfterKernClampLowerBoundPx=$expectedAfterClampLowerBound",
                ),
                result.debugDump,
            )
            assertTrue(
                result.debugDump.contains(
                    "degreeHorizontalPlacementPolicy=TeXMakeRadicalSignedBeforeAndWidthPlusBeforeAfterClamp",
                ),
                result.debugDump,
            )
            assertEquals("0.0", result.radicalNoadDecision().details["italicCorrectionPx"])
        }
    }

    @Test
    fun realFontDegreeKernsUseTheSameTeXHorizontalRule() =
        withRadicalFaces { label, face ->
            val size = 40f
            val result = MathLayoutEngine(face).layout(
                "\\sqrt[3]{x^2+1}",
                MathLayoutOptions(MathMode.Inline, size),
            )
            val geometry = result.radicalGeometryDecision()
            val rawBefore = face.mathFont.scaleDesignUnits(
                face.mathFont.constants.radicalKernBeforeDegree,
                size,
            )
            val rawAfter = face.mathFont.scaleDesignUnits(
                face.mathFont.constants.radicalKernAfterDegree,
                size,
            )
            val degreeWidth = geometry.float("degreeWidthPx")
            val lowerBound = -(degreeWidth + rawBefore)

            assertTrue(rawBefore > 0f, "$label real MATH fixture has a positive before kern")
            assertTrue(rawAfter < 0f, "$label real MATH fixture has a negative after kern")
            assertTrue(rawAfter >= lowerBound, "$label real MATH after kern must exercise the no-clamp branch")
            val previousIncorrectAfter = maxOf(-degreeWidth, rawAfter)
            assertTrue(
                previousIncorrectAfter > rawAfter,
                "$label fixture must distinguish the previous -degreeWidth clamp",
            )
            assertNear(rawBefore, geometry.float("radicalKernBeforeDegreePx"), "$label raw before")
            assertNear(rawAfter, geometry.float("radicalKernAfterDegreePx"), "$label raw after")
            assertNear(rawBefore, geometry.float("usedRadicalKernBeforeDegreePx"), "$label signed before is used")
            assertNear(
                lowerBound,
                geometry.float("radicalDegreeAfterKernClampLowerBoundPx"),
                "$label after clamp lower bound",
            )
            assertNear(rawAfter, geometry.float("adjustedRadicalKernAfterDegreePx"), "$label after needs no clamp")
            assertNear(rawBefore, geometry.float("degreeX"), "$label degree x")
            assertNear(rawBefore + degreeWidth + rawAfter, geometry.float("radicalX"), "$label radical x")
            assertTrue(
                geometry.float("radicalX") < rawBefore + degreeWidth + previousIncorrectAfter,
                "$label TeX clamp must move the radical left of the previous implementation",
            )
        }

    @Test
    fun degreeKernConstantsCannotMoveAnUnindexedRadical() =
        withRadicalFaces { label, face ->
            val size = 40f
            val source = "\\sqrt{x}"
            val baseline = MathLayoutEngine(face).layout(source, MathLayoutOptions(MathMode.Inline, size))
            val changedFace = RadicalOverrideFace(
                face,
                face.mathFont.copy(
                    constants = face.mathFont.constants.copy(
                        radicalKernBeforeDegree = -4_000,
                        radicalKernAfterDegree = 4_000,
                    ),
                ),
            )
            val changed = MathLayoutEngine(changedFace).layout(source, MathLayoutOptions(MathMode.Inline, size))

            assertEquals(baseline.box, changed.box, "$label unindexed radical box")
            assertEquals(baseline.fragments, changed.fragments, "$label unindexed radical fragments")
            assertEquals(baseline.lineMetrics, changed.lineMetrics, "$label unindexed radical line metrics")
            assertEquals("null", changed.radicalGeometryDecision().details["degreeHorizontalPlacementPolicy"])
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
    private val advanceWidthOverrides: Map<UShort, Float> = emptyMap(),
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
    ): MeasuredMathRun = delegate.measureGlyph(glyphId, fontSizePx, style, sourceRange).let { run ->
        advanceWidthOverrides[glyphId]?.let { run.copy(width = it) } ?: run
    }

    override fun shapeOutlineConstructionBase(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = delegate.shapeOutlineConstructionBase(text, fontSizePx, sourceRange)

    override fun measureOutlineConstructionGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = delegate.measureOutlineConstructionGlyph(
        glyphId,
        fontSizePx,
        style,
        sourceRange,
    ).let { measurement ->
        advanceWidthOverrides[glyphId]?.let { width ->
            measurement.copy(run = measurement.run.copy(width = width))
        } ?: measurement
    }
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

/** TeX/OpenType radical box equations; the separate outline oracle audits the painted seam. */
private fun assertRadicalBoxAlgebra(geometry: MathLayoutDecision, label: String) {
    val ruleThickness = geometry.float("radicalRuleThicknessPx")
    val minimumGap = geometry.float("radicalVerticalGapPx")
    val radicandInkHeight = geometry.float("radicandInkBottomPx") - geometry.float("radicandInkTopPx")
    assertNear(
        radicandInkHeight + minimumGap + ruleThickness,
        geometry.float("targetHeightPx"),
        "$label stretch target uses radicand ink only",
    )
    val expectedExcess = (
        geometry.float("achievedAdvancePx") - geometry.float("targetHeightPx")
    ).coerceAtLeast(0f)
    assertNear(
        expectedExcess,
        geometry.float("constructionExcessPx"),
        "$label construction excess uses the selected stretch extent",
    )
    assertNear(
        minimumGap + expectedExcess / 2f,
        geometry.float("actualRadicalGapPx"),
        "$label clearance receives half the positive construction excess",
    )
    assertNear(
        geometry.float("radicandInkTopPx") - geometry.float("actualRadicalGapPx"),
        geometry.float("ruleBottom"),
        "$label overbar bottom closes against radicand ink",
    )
    assertNear(
        geometry.float("ruleTop") + ruleThickness,
        geometry.float("ruleBottom"),
        "$label overbar thickness",
    )
    assertNear(
        maxOf(
            geometry.float("radicandAscentPx"),
            -geometry.float("ruleTop") + geometry.float("radicalExtraAscenderPx"),
            -(geometry.float("radicalPaintOriginY") - geometry.float("radicalGlyphAscentPx")),
        ),
        geometry.float("unindexedAscentPx"),
        "$label unindexed ascent reserves radicand, overbar safety, and radical ink independently",
    )
    assertNear(
        maxOf(
            geometry.float("radicandDescentPx"),
            geometry.float("radicalPaintOriginY") + geometry.float("radicalGlyphDescentPx"),
        ).coerceAtLeast(0f),
        geometry.float("unindexedDescentPx"),
        "$label unindexed descent encloses radicand and selected radical construction",
    )
    assertNear(
        geometry.float("ruleTop"),
        geometry.float("radicalPaintOriginY") + geometry.float("radicalTopStrokeTopPx"),
        "$label font-adapter top-stroke anchor meets the overbar",
    )
    assertNear(
        geometry.float("radicalX") + geometry.float("radicalTopStrokeRightPx"),
        geometry.float("ruleLeft"),
        "$label overbar starts at the font-adapter top-stroke right edge",
    )
    assertNear(
        geometry.float("radicalX") + geometry.float("radicalBoxAdvancePx") +
            geometry.float("radicandWidthPx"),
        geometry.float("ruleRight"),
        "$label overbar reaches the radicand logical right edge without changing radical advance",
    )
    assertEquals("Available(GlyphOutlineCrossSection)", geometry.details["radicalTopStrokeEvidence"])
    assertEquals(
        "SelectedOpenTypeConstructionAdvanceMinusStretchTarget",
        geometry.details["constructionExcessMetric"],
    )
    assertEquals(
        "MinimumGapPlusHalfPositiveConstructionExcess",
        geometry.details["clearancePolicy"],
    )
    assertEquals("FontAdapterTopStrokeTopAndRight", geometry.details["overbarAnchorPolicy"])
    assertEquals("OpenTypeMATH.RadicalRuleThickness", geometry.details["overbarThicknessSource"])
    assertEquals("FontAdapterTopStrokeRight", geometry.details["overbarLeftPolicy"])
}

private fun assertAssemblyTopAlignedToOverbar(geometry: MathLayoutDecision, label: String) {
    assertNear(
        geometry.float("ruleTop"),
        geometry.float("radicalPaintOriginY") + geometry.float("radicalTopStrokeTopPx"),
        "$label assembly top-stroke anchor meets overbar",
    )
    assertNear(
        geometry.float("ruleTop") - geometry.float("radicalTopStrokeTopPx"),
        geometry.float("radicalPaintOriginY"),
        "$label assembly local top stroke drives the paint origin",
    )
}

private inline fun withRadicalFaces(block: (String, SkiaMathFontFace) -> Unit) {
    listOf(
        "Lete Sans Math" to LeteSansMath.load(),
        "STIX Two Math" to StixTwoMath.load(),
    ).forEach { (label, font) -> SkiaMathFontFace(font).use { block(label, it) } }
}

private fun rasterConnectedComponentSizes(
    face: SkiaMathFontFace,
    glyphs: List<MathGlyphPlacement>,
    rule: MathRulePlacement,
    scale: Int,
): List<Int> {
    require(glyphs.isNotEmpty())
    val scalePx = scale.toFloat()
    val left = minOf(glyphs.minOf { it.inkBounds.left }, rule.left)
    val top = minOf(glyphs.minOf { it.inkBounds.top }, rule.top)
    val right = maxOf(glyphs.maxOf { it.inkBounds.right }, rule.right)
    val bottom = maxOf(glyphs.maxOf { it.inkBounds.bottom }, rule.bottom)
    val padding = 8
    val width = ceil((right - left) * scalePx).toInt() + 2 * padding
    val height = ceil((bottom - top) * scalePx).toInt() + 2 * padding
    val originX = padding - left * scalePx
    val originY = padding - top * scalePx
    val surface = Surface.makeRasterN32Premul(width, height)
    val paint = Paint().apply { color = Color.BLACK }
    val font = face.font(glyphs.first().fontSizePx * scalePx)
    val builder = TextBlobBuilder()
    try {
        surface.canvas.clear(Color.TRANSPARENT)
        builder.appendRunPos(
            font,
            glyphs.map { it.glyphId.toShort() }.toShortArray(),
            glyphs.map {
                Point(originX + it.x * scalePx, originY + it.baselineY * scalePx)
            }.toTypedArray(),
        )
        builder.build()?.use { surface.canvas.drawTextBlob(it, 0f, 0f, paint) }
        surface.canvas.drawRect(
            Rect.makeLTRB(
                originX + rule.left * scalePx,
                originY + rule.top * scalePx,
                originX + rule.right * scalePx,
                originY + rule.bottom * scalePx,
            ),
            paint,
        )
        val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
        try {
            assertTrue(surface.readPixels(bitmap, 0, 0))
            val filled = BooleanArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                filled[y * width + x] = bitmap.getAlphaf(x, y) > 0.08f
            }
            val visited = BooleanArray(filled.size)
            val sizes = mutableListOf<Int>()
            for (start in filled.indices) {
                if (!filled[start] || visited[start]) continue
                visited[start] = true
                val queue = ArrayDeque<Int>()
                queue.add(start)
                var componentSize = 0
                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()
                    componentSize++
                    val currentX = current % width
                    val currentY = current / width
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nextX = currentX + dx
                        val nextY = currentY + dy
                        if (nextX !in 0 until width || nextY !in 0 until height) continue
                        val next = nextY * width + nextX
                        if (filled[next] && !visited[next]) {
                            visited[next] = true
                            queue.add(next)
                        }
                    }
                }
                sizes += componentSize
            }
            assertTrue(sizes.isNotEmpty(), "STIX radical raster must contain visible pixels")
            return sizes.sortedDescending()
        } finally {
            bitmap.close()
        }
    } finally {
        builder.close()
        font.close()
        paint.close()
        surface.close()
    }
}

private fun assertNear(expected: Float, actual: Float, message: String) {
    assertTrue(abs(expected - actual) <= EPSILON, "$message: expected=$expected actual=$actual")
}

private fun assertAtLeast(actual: Float, minimum: Float, message: String) {
    assertTrue(actual + EPSILON >= minimum, "$message: actual=$actual minimum=$minimum")
}

private const val EPSILON = 0.05f
