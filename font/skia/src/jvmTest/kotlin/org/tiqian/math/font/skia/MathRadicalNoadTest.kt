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

class MathRadicalNoadTest {
    @Test
    fun degreeRaiseUsesCompletedXeTeXRadicalHeightMinusDepthForBothFonts() =
        withRadicalFaces { label, face ->
            val size = 48f
            val assemblyRadicand = (1..12).fold("x") { radicand, _ -> "\\frac{$radicand}{y}" }
            val cases = listOf(
                Triple("base", "\\sqrt[2]{x}", "BaseGlyph"),
                Triple("variant", "\\sqrt[3]{\\frac{a}{b}}", "Variant"),
                Triple("assembly", "\\sqrt[5]{$assemblyRadicand}", "Assembly"),
            )
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
                assertEquals("false", baseGeometry.details["radicalExtraAscenderUsed"])
                assertNear(
                    baseGeometry.float("unindexedBlockSizePx"),
                    reserveGeometry.float("unindexedBlockSizePx"),
                    "$label/$caseLabel Tectonic 0.17.0 ignores RadicalExtraAscender",
                )
                assertNear(
                    baseGeometry.float("degreeRaisePx"),
                    reserveGeometry.float("degreeRaisePx"),
                    "$label/$caseLabel unused extra ascender must not change degree raise",
                )
                assertNear(
                    baseGeometry.float("degreeRaiseReferencePx"),
                    reserveGeometry.float("degreeRaiseReferencePx"),
                    "$label/$caseLabel unused extra ascender must not change the completed B reference",
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
                assertNear(
                    selectedAscent + selectedDescent,
                    selectedBlockSize,
                    "$label/$caseLabel selected radical box height closes over ascent and descent",
                )
                assertNear(
                    baseGeometry.float("unindexedAscentPx") - baseGeometry.float("unindexedDescentPx"),
                    baseGeometry.float("degreeRaiseReferencePx"),
                    "$label/$caseLabel degree raise references height(B)-depth(B)",
                )
                assertNear(
                    baseGeometry.float("degreeRaiseReferencePx") *
                        baseGeometry.float("radicalDegreeBottomRaisePercent") / 100f,
                    baseGeometry.float("degreeRaisePx"),
                    "$label/$caseLabel degree raise applies the MATH percentage",
                )
                assertEquals(
                    "UnindexedRadicalBoxHeightMinusDepth",
                    baseGeometry.details["degreeRaiseReferenceMetric"],
                    "$label/$caseLabel reference metric",
                )
                assertEquals(
                    "unicode-math-xetex-r@@t-times-OpenTypeMATH.RadicalDegreeBottomRaisePercent",
                    baseGeometry.details["degreeRaiseReferencePolicy"],
                    "$label/$caseLabel reference policy",
                )
                assertNear(
                    baseGeometry.float("unindexedAscentPx"),
                    baseGeometry.float("degreeRaiseReferenceAscentPx"),
                    "$label/$caseLabel decision records B height",
                )
                assertNear(
                    baseGeometry.float("unindexedDescentPx"),
                    baseGeometry.float("degreeRaiseReferenceDescentPx"),
                    "$label/$caseLabel decision records B depth",
                )
                assertEquals(
                    "UnicodeMathXeTeXRootHeightMinusDepthRaise",
                    baseGeometry.details["degreePlacementPolicy"],
                    "$label/$caseLabel placement policy",
                )
                assertEquals(
                    "unicode-math-xetex-r@@t;NotLuaTeXOrMathMLBlockSizeMapping",
                    baseGeometry.details["degreePlacementSpecificationDivergence"],
                    "$label/$caseLabel specification mapping",
                )
                assertNear(
                    -baseGeometry.float("degreeRaisePx") + baseGeometry.float("degreeDescentPx"),
                    baseGeometry.float("degreeLogicalBottomY"),
                    "$label/$caseLabel degree box is placed from its raised baseline",
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
                    -geometry.float("radicandAscentPx") - expectedActualGap,
                    geometry.float("ruleBottom"),
                    "$label/$caseLabel overbar bottom closes against clean-box height",
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
                "XeTeXMakeRadicalCleanBoxNominalDelimiterAndOverbar",
                indexedGeometry.details["unindexedBoxPolicy"],
            )
            assertEquals(
                "UnicodeMathXeTeXRootHeightMinusDepthRaise",
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
                -indexedGeometry.float("degreeRaisePx") + indexedGeometry.float("degreeDescentPx"),
                indexedGeometry.float("degreeLogicalBottomY"),
                "$label degree bottom is based on the raised degree baseline",
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
    fun nestedRadicalLogicalBoxFeedsTheOuterTeXCleanBoxTarget() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val size = 72f
            val source = "\\sqrt{\\sqrt{x}}"
            val outerCommandRange = SourceRange(0, 5)
            val result = MathLayoutEngine(delegate).layout(source, MathLayoutOptions(fontSizePx = size))
            val inner = result.decisions.filter { it.name == "OpenTypeMathRadical" }.first()
            val outer = result.decisions.filter { it.name == "OpenTypeMathRadical" }.last()
            val outerChoice = result.decisions.single {
                it.name == "OpenTypeRadicalConstruction" && it.range == outerCommandRange
            }
            assertEquals("TeXCleanBoxHeightPlusGapAndRule", outerChoice.details["targetMetric"])
            assertEquals("MathMLCore5.3.2NormalGlyphFirst", outerChoice.details["selectionPolicy"])
            assertNear(
                inner.float("unindexedAscentPx"),
                outer.float("radicandAscentPx"),
                "outer clean_box keeps the inner radical height",
            )
            assertNear(
                inner.float("unindexedDescentPx"),
                outer.float("radicandDescentPx"),
                "outer clean_box keeps the inner radical depth",
            )
            assertNear(
                outer.float("radicandAscentPx") + outer.float("radicandDescentPx") +
                    outer.float("minimumRadicalGapPx") + outer.float("radicalRuleThicknessPx"),
                outerChoice.float("targetHeightPx"),
                "outer stretch target consumes the complete clean box",
            )
            assertTrue(
                outerChoice.float("targetHeightPx") >
                    outerChoice.float("radicandInkHeightPx") + outer.float("minimumRadicalGapPx") +
                    outer.float("radicalRuleThicknessPx"),
                "the nested fixture disproves the former visible-ink-only target",
            )
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
            assertEquals("false", ascender.details["radicalExtraAscenderUsed"])
            assertEquals(
                "Tectonic0.17.0DoesNotConsumeOpenTypeMATH.RadicalExtraAscender",
                ascender.details["overbarLeadingReserveSpecificationDivergence"],
            )
            assertNear(
                inlineBase.float("reservedTopPx"),
                ascender.float("reservedTopPx"),
                "Tectonic-compatible radical box does not consume RadicalExtraAscender",
            )
            assertEquals(
                layout(delegate, delegate.mathFont.copy(constants = zero), "\\sqrt{x}", size).box,
                ascenderResult.box,
                "changing only RadicalExtraAscender cannot move Tectonic 0.17.0 geometry",
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
            assertNear(
                -indexedWithAscender.float("degreeRaisePx") +
                    indexedWithAscender.float("degreeDescentPx"),
                indexedWithAscender.float("degreeLogicalBottomY"),
                "degree uses the raised baseline",
            )
            assertNear(
                indexedRaiseBase.float("degreeRaisePx"),
                indexedWithAscender.float("degreeRaisePx"),
                "unused RadicalExtraAscender does not alter height(B)-depth(B)",
            )
            assertNear(
                indexedRaiseBase.float("degreeBaselineY"),
                indexedWithAscender.float("degreeBaselineY"),
                "unused RadicalExtraAscender does not move the degree baseline",
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
}

private fun MathLayoutResult.radicalGeometryDecision(): MathLayoutDecision =
    decisions.first { it.name == "OpenTypeMathRadical" }

private fun MathLayoutDecision.float(name: String): Float =
    checkNotNull(details[name]) { "$name is absent from $this" }.toFloat()

private fun assertNear(expected: Float, actual: Float, message: String) {
    assertTrue(abs(expected - actual) <= EPSILON, "$message: expected=$expected actual=$actual")
}

private fun assertAtLeast(actual: Float, minimum: Float, message: String) {
    assertTrue(actual + EPSILON >= minimum, "$message: actual=$actual minimum=$minimum")
}

private const val EPSILON = 0.05f
