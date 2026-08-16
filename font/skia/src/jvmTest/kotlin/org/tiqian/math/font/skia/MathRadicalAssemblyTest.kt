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
import org.tiqian.math.font.opentype.MathVerticalAssemblyPolicy
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

class MathRadicalAssemblyTest {
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
            assertEquals(
                "Tectonic0.17.0XeTeXBuildOpenTypeAssemblyStretchGlue",
                construction.details["constructionPolicy"],
            )
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
                -geometry.float("degreeRaisePx") + geometry.float("degreeDescentPx"),
                geometry.float("degreeLogicalBottomY"),
                "$label tall indexed assembly places the degree from the raised baseline",
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
                    assemblyPolicy = MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue,
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
}

private fun MathLayoutResult.radicalGeometryDecision(): MathLayoutDecision =
    decisions.first { it.name == "OpenTypeMathRadical" }

private fun MathLayoutDecision.float(name: String): Float =
    checkNotNull(details[name]) { "$name is absent from $this" }.toFloat()

private fun assertNear(expected: Float, actual: Float, message: String) {
    assertTrue(abs(expected - actual) <= EPSILON, "$message: expected=$expected actual=$actual")
}

private const val EPSILON = 0.05f
