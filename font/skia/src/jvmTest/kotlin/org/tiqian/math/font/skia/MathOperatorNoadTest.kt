package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathLargeOperatorIdentity
import org.tiqian.math.core.MathLayoutDecision
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.opentype.MathGlyphAssembly
import org.tiqian.math.font.opentype.MathGlyphAssemblyPart
import org.tiqian.math.font.opentype.MathGlyphConstruction
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

class MathOperatorNoadTest {
    @Test
    fun plainTexPoliciesDisplayVariantsAxisAndLimitGeometryHoldForBothRealFonts() =
        withOperatorFaces { label, face ->
            val engine = MathLayoutEngine(face)
            val inlineSum = engine.layout("\\sum_i^n", MathLayoutOptions(MathMode.Inline, 44f))
            val displaySum = engine.layout("\\sum_i^n", MathLayoutOptions(MathMode.Display, 44f))
            val inlineProduct = engine.layout("\\prod_i^n", MathLayoutOptions(MathMode.Inline, 44f))
            val displayProduct = engine.layout("\\prod_i^n", MathLayoutOptions(MathMode.Display, 44f))
            val forcedInline = engine.layout("\\sum\\limits_i^n", MathLayoutOptions(MathMode.Inline, 44f))
            val forcedDisplaySide = engine.layout("\\sum\\nolimits_i^n", MathLayoutOptions(MathMode.Display, 44f))
            val defaultIntegral = engine.layout("\\int_i^n", MathLayoutOptions(MathMode.Display, 44f))
            val forcedContour = engine.layout("\\oint\\limits_i^n", MathLayoutOptions(MathMode.Inline, 44f))

            listOf(
                inlineSum,
                displaySum,
                inlineProduct,
                displayProduct,
                forcedInline,
                forcedDisplaySide,
                defaultIntegral,
                forcedContour,
            ).forEach { result ->
                assertTrue(
                    result.diagnostics.all { it.code == DiagnosticCode.MathVariantTooShort },
                    "$label/${result.source}: ${result.diagnostics}\n${result.debugDump}",
                )
                val op = result.operatorDecision()
                assertEquals("Operator", op.details["atomClass"], "$label/${result.source}")
                assertEquals("LargeSymbols", op.details["family"], "$label/${result.source}")
                assertNear(
                    op.float("axisY"),
                    op.float("inkCenterAfter"),
                    "$label/${result.source} operator ink center follows the math axis",
                )
            }

            assertPolicy(inlineSum, "NoLimits", "auto-non-display", label)
            assertPolicy(displaySum, "Limits", "auto-display", label)
            assertPolicy(inlineProduct, "NoLimits", "auto-non-display", label)
            assertPolicy(displayProduct, "Limits", "auto-display", label)
            assertPolicy(forcedInline, "Limits", "explicit-postfix-modifier", label)
            assertPolicy(forcedDisplaySide, "NoLimits", "explicit-postfix-modifier", label)
            assertPolicy(defaultIntegral, "NoLimits", "plain-tex-operator-default", label)
            assertPolicy(forcedContour, "Limits", "explicit-postfix-modifier", label)

            assertTrue(inlineSum.decisions.any { it.name == "TeXOperatorSideScripts" }, label)
            assertTrue(defaultIntegral.decisions.any { it.name == "TeXOperatorSideScripts" }, label)
            assertTrue(displaySum.decisions.any { it.name == "OpenTypeMathOperatorLimits" }, label)
            assertTrue(forcedInline.decisions.any { it.name == "OpenTypeMathOperatorLimits" }, label)
            assertTrue(forcedContour.decisions.any { it.name == "OpenTypeMathOperatorLimits" }, label)

            val inlineOperator = inlineSum.operatorDecision()
            assertEquals("BaseGlyph", inlineOperator.details["construction"], label)
            assertNear(0f, inlineOperator.float("displayOperatorMinHeightPx"), label)
            val displayOperator = displaySum.operatorDecision()
            assertTrue(displayOperator.details["construction"] != "BaseGlyph", "$label: $displayOperator")
            val reachesDisplayTarget = displayOperator.details.getValue("reachesTarget").toBoolean()
            assertEquals(
                reachesDisplayTarget,
                displayOperator.float("achievedAdvancePx") + EPSILON >=
                    displayOperator.float("displayOperatorMinHeightPx"),
                "$label target result is derived from the font's variant data",
            )
            assertEquals(
                !reachesDisplayTarget,
                displaySum.diagnostics.any { it.code == DiagnosticCode.MathVariantTooShort },
                "$label an exhausted variant ladder is explicit",
            )
            assertEquals(SourceRange(0, 4), displayOperator.range, "$label operator source range")
            assertEquals(SourceRange(0, 11), forcedInline.operatorDecision().range, "$label modifier range is retained")
            assertEquals(
                "SourceRange(start=4, endExclusive=11)",
                forcedInline.operatorDecision().details["limitsModifierRange"],
                "$label exact limits modifier range is auditable",
            )
            assertTrue(
                forcedInline.box.glyphs.any { it.sourceRange == SourceRange(0, 4) },
                "$label operator glyph maps only to the command token",
            )

            assertLimitConstantsAndHalfItalicCorrection(displaySum, label)
            assertLimitConstantsAndHalfItalicCorrection(forcedContour, label)
        }

    @Test
    fun extremeFixtureProvesEachNamedLimitConstantControlsItsOwnConstraint() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val zero = delegate.mathFont.constants.copy(
                upperLimitGapMin = 0,
                upperLimitBaselineRiseMin = 0,
                lowerLimitGapMin = 0,
                lowerLimitBaselineDropMin = 0,
            )
            val size = 48f
            val extreme = 4000
            val expected = delegate.mathFont.scaleDesignUnits(extreme, size)
            val baseline = limitDecision(delegate, zero, size)
            val upperGap = limitDecision(delegate, zero.copy(upperLimitGapMin = extreme), size)
            val upperRise = limitDecision(delegate, zero.copy(upperLimitBaselineRiseMin = extreme), size)
            val lowerGap = limitDecision(delegate, zero.copy(lowerLimitGapMin = extreme), size)
            val lowerDrop = limitDecision(delegate, zero.copy(lowerLimitBaselineDropMin = extreme), size)

            assertNear(expected, upperGap.float("actualUpperGapPx"), "extreme UpperLimitGapMin")
            assertNear(expected, upperRise.float("actualUpperBaselineRisePx"), "extreme UpperLimitBaselineRiseMin")
            assertNear(expected, lowerGap.float("actualLowerGapPx"), "extreme LowerLimitGapMin")
            assertNear(expected, lowerDrop.float("actualLowerBaselineDropPx"), "extreme LowerLimitBaselineDropMin")
            assertTrue(upperGap.float("upperShiftPx") > baseline.float("upperShiftPx"))
            assertTrue(upperRise.float("upperShiftPx") > baseline.float("upperShiftPx"))
            assertTrue(lowerGap.float("lowerShiftPx") > baseline.float("lowerShiftPx"))
            assertTrue(lowerDrop.float("lowerShiftPx") > baseline.float("lowerShiftPx"))
        }
    }

    @Test
    fun glyphAssemblyItalicCorrectionDrivesStackedLimitSkew() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val size = 40f
            val baseGlyph = assertNotNull(
                delegate.resolveOperator(
                    MathOperatorGlyphRequest(
                        MathLargeOperatorIdentity.Sum,
                        MathStyle.Display,
                        SourceRange(0, 4),
                    ),
                    size,
                ).constructionBaseGlyphId,
            )
            val assemblyCorrection = 777
            val construction = MathGlyphConstruction(
                variants = emptyList(),
                assembly = MathGlyphAssembly(
                    parts = listOf(
                        MathGlyphAssemblyPart(baseGlyph, 0, 200, 900, false),
                        MathGlyphAssemblyPart(baseGlyph, 200, 200, 900, true),
                        MathGlyphAssemblyPart(baseGlyph, 200, 0, 900, false),
                    ),
                    minimumConnectorOverlap = 100,
                    italicCorrection = assemblyCorrection,
                ),
            )
            val overriddenFont = delegate.mathFont.copy(
                constants = delegate.mathFont.constants.copy(displayOperatorMinHeight = 2500),
                verticalConstructions = delegate.mathFont.verticalConstructions + (baseGlyph to construction),
            )
            val result = MathLayoutEngine(OperatorOverrideFace(delegate, overriddenFont)).layout(
                "\\sum\\limits_a^b",
                MathLayoutOptions(MathMode.Display, size),
            )

            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            val operator = result.operatorDecision()
            val limits = result.limitDecision()
            val expectedCorrection = overriddenFont.scaleDesignUnits(assemblyCorrection, size)
            assertEquals("Assembly", operator.details["construction"])
            assertEquals("GlyphAssembly", operator.details["italicCorrectionSource"])
            assertNear(expectedCorrection, operator.float("italicCorrectionPx"), "assembly italic correction")
            assertNear(expectedCorrection / 2f, limits.float("upperCenterOffsetPx"), "assembly upper half skew")
            assertNear(-expectedCorrection / 2f, limits.float("lowerCenterOffsetPx"), "assembly lower half skew")
            assertTrue(result.decisions.any {
                it.name == "OpenTypeOperatorConstruction" &&
                    it.details["assemblyItalicCorrectionDesignUnits"] == assemblyCorrection.toString()
            })
        }
    }

    private fun assertPolicy(result: org.tiqian.math.core.MathLayoutResult, policy: String, reason: String, label: String) {
        val decision = result.decisions.first { it.name == "TeXOperatorLimitsPolicy" }
        assertEquals(policy, decision.details["effectivePolicy"], "$label/${result.source}")
        assertEquals(reason, decision.details["reason"], "$label/${result.source}")
    }

    private fun assertLimitConstantsAndHalfItalicCorrection(
        result: org.tiqian.math.core.MathLayoutResult,
        label: String,
    ) {
        val limits = result.limitDecision()
        assertAtLeast(limits.float("actualUpperGapPx"), limits.float("upperLimitGapMinPx"), "$label upper gap")
        assertAtLeast(
            limits.float("actualUpperBaselineRisePx"),
            limits.float("upperLimitBaselineRiseMinPx"),
            "$label upper baseline rise",
        )
        assertAtLeast(limits.float("actualLowerGapPx"), limits.float("lowerLimitGapMinPx"), "$label lower gap")
        assertAtLeast(
            limits.float("actualLowerBaselineDropPx"),
            limits.float("lowerLimitBaselineDropMinPx"),
            "$label lower baseline drop",
        )
        val operatorCenter = limits.float("operatorX") + limits.float("operatorWidthPx") / 2f
        val upperCenter = limits.float("upperX") + limits.float("upperWidthPx") / 2f
        val lowerCenter = limits.float("lowerX") + limits.float("lowerWidthPx") / 2f
        assertNear(
            limits.float("upperCenterOffsetPx"),
            upperCenter - operatorCenter,
            "$label upper center uses half italic correction",
        )
        assertNear(
            limits.float("lowerCenterOffsetPx"),
            lowerCenter - operatorCenter,
            "$label lower center uses minus half italic correction",
        )
    }

    private fun limitDecision(
        delegate: SkiaMathFontFace,
        constants: OpenTypeMathConstants,
        size: Float,
    ): MathLayoutDecision = MathLayoutEngine(
        OperatorOverrideFace(delegate, delegate.mathFont.copy(constants = constants)),
    ).layout(
        "\\sum\\limits_a^b",
        MathLayoutOptions(MathMode.Display, size),
    ).limitDecision()
}

private class OperatorOverrideFace(
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

private fun org.tiqian.math.core.MathLayoutResult.operatorDecision(): MathLayoutDecision =
    decisions.first { it.name == "TeXOperatorNoad" }

private fun org.tiqian.math.core.MathLayoutResult.limitDecision(): MathLayoutDecision =
    decisions.first { it.name == "OpenTypeMathOperatorLimits" }

private fun MathLayoutDecision.float(name: String): Float =
    checkNotNull(details[name]) { "$name is absent from $this" }.toFloat()

private inline fun withOperatorFaces(block: (String, SkiaMathFontFace) -> Unit) {
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

private const val EPSILON = 0.04f
