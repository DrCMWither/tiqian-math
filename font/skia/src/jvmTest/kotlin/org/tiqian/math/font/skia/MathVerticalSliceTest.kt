package org.tiqian.math.font.skia

import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.opentype.MathConstructionKind
import org.tiqian.math.font.opentype.OpenTypeMathConstants
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.*
import org.tiqian.math.parser.MathMacroDefinition
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MathVerticalSliceTest {
    @Test
    fun sameParserStyleLayoutAndFragmentPipelineRunsForBothRealFonts() = withRealFaces { label, face ->
        val source = "E_k=(n-1)E_{k-1}+E_{k-2}+\\frac{a+b}{\\binom{n}{k}}=y_2^3"
        val engine = MathLayoutEngine(
            face,
            macros = listOf(MathMacroDefinition("pair", 2, "#1+#2")),
        )

        listOf(MathMode.Inline, MathMode.Display).forEach { mode ->
            val result = engine.layout(source, MathLayoutOptions(mode, 36f))
            assertTrue(result.diagnostics.isEmpty(), "$label/$mode: ${result.diagnostics}")
            assertEquals(SourceRange(0, 3), result.fragments.first().sourceRange, "$label/$mode source range")
            assertEquals(if (mode == MathMode.Inline) MathStyle.Text else MathStyle.Display, result.initialStyle)
            assertTrue(result.box.glyphs.isNotEmpty(), "$label/$mode has glyph layout")
            assertTrue(result.box.rules.isNotEmpty(), "$label/$mode has fraction rule")
            assertTrue(result.breakOpportunities.any { it.kind == MathBreakKind.BinaryOperatorTrailing })
            assertTrue(result.breakOpportunities.any { it.kind == MathBreakKind.RelationTrailing })
            assertTrue(result.debugDump.contains("math axis="), "$label layout dump has MATH data")
            assertTrue(result.debugDump.contains("decision BinomialDelimiter"), "$label layout dump has delimiter choice")
        }

        // Every one of the eight states enters glyph layout, not just the transition table.
        MathStyle.entries.forEach { style ->
            val result = engine.layout(
                "z",
                MathLayoutOptions(MathMode.Inline, 40f, initialStyle = style),
            )
            val glyph = result.box.glyphs.single()
            assertEquals(style, glyph.style, "$label propagates $style")
            assertNear(expectedSize(face.mathFont, 40f, style), glyph.fontSizePx, "$label size for $style")
        }

        val expanded = MathLayoutEngine(
            face,
            macros = listOf(MathMacroDefinition("pair", 2, "#1+#2")),
        ).layout("\\pair{x_1^2}{\\frac{a}{b}}", MathLayoutOptions(fontSizePx = 32f))
        assertTrue(expanded.diagnostics.isEmpty(), "$label macro pipeline: ${expanded.diagnostics}")
        assertTrue(expanded.box.rules.size == 1 && expanded.box.glyphs.size >= 5)
    }

    @Test
    fun mathConstantsDetermineScriptsFractionsBaselinesAndInkExtentsForBothFonts() = withRealFaces { label, face ->
        val size = 50f
        val constants = face.mathFont.constants

        val scriptSource = "x_1^2"
        val scripts = MathLayoutEngine(face).layout(
            scriptSource,
            MathLayoutOptions(MathMode.Display, size),
        )
        val sub = scripts.glyphAt(scriptSource.indexOf('1'))
        val sup = scripts.glyphAt(scriptSource.indexOf('2'))
        assertTrue(sup.baselineY < 0f && sub.baselineY > 0f, "$label script baselines straddle formula baseline")
        val scriptGap = sub.inkBounds.top - sup.inkBounds.bottom
        assertAtLeast(
            scriptGap,
            face.mathFont.scaleDesignUnits(constants.subSuperscriptGapMin, size),
            "$label paired-script safety gap",
        )
        assertNear(size * constants.scriptPercentScaleDown / 100f, sup.fontSizePx, "$label script scale")
        assertTrue(scripts.box.ascent >= -sup.inkBounds.top && scripts.box.descent >= sub.inkBounds.bottom)

        val fractionSource = "\\frac{a}{b}"
        val fraction = MathLayoutEngine(face).layout(
            fractionSource,
            MathLayoutOptions(MathMode.Display, size),
        )
        val rule = fraction.box.rules.single()
        val numerator = fraction.glyphAt(fractionSource.lastIndexOf('a'))
        val denominator = fraction.glyphAt(fractionSource.lastIndexOf('b'))
        assertNear(
            -face.mathFont.scaleDesignUnits(constants.axisHeight, size),
            (rule.top + rule.bottom) / 2f,
            "$label fraction axis",
        )
        assertNear(
            face.mathFont.scaleDesignUnits(constants.fractionRuleThickness, size),
            rule.bottom - rule.top,
            "$label fraction rule",
        )
        assertAtLeast(
            rule.top - numerator.inkBounds.bottom,
            face.mathFont.scaleDesignUnits(constants.fractionNumDisplayStyleGapMin, size),
            "$label numerator clearance",
        )
        assertAtLeast(
            denominator.inkBounds.top - rule.bottom,
            face.mathFont.scaleDesignUnits(constants.fractionDenomDisplayStyleGapMin, size),
            "$label denominator clearance",
        )
        assertTrue(fraction.box.ascent > 0f && fraction.box.descent > 0f, "$label reports baseline extents")
    }

    @Test
    fun delimiterVariantAndAssemblySelectionComesFromEachFontMathTable() = withRealFaces { label, face ->
        val size = 48f
        val base = face.shape("(", size, MathStyle.Display, SourceRange(0, 1)).glyphs.single().glyphId
        val data = assertNotNull(face.mathFont.verticalConstructions[base], "$label has parenthesis construction")
        assertTrue(data.variants.isNotEmpty(), "$label has parenthesis variants")

        val variantTarget = face.mathFont.scaleDesignUnits(data.variants.first().advanceMeasurement, size)
        val variant = assertNotNull(face.mathFont.verticalConstruction(base, variantTarget, size))
        assertEquals(MathConstructionKind.Variant, variant.kind, "$label chooses a sufficient variant")
        assertTrue(variant.reachesTarget)

        val assemblyData = assertNotNull(data.assembly, "$label has parenthesis assembly")
        val assemblyTarget = face.mathFont.scaleDesignUnits(
            data.variants.last().advanceMeasurement + assemblyData.parts.sumOf { it.fullAdvance },
            size,
        )
        val assembly = assertNotNull(face.mathFont.verticalConstruction(base, assemblyTarget, size))
        assertEquals(MathConstructionKind.Assembly, assembly.kind, "$label chooses assembly above variants")
        assertTrue(assembly.components.size > assemblyData.parts.count { !it.extender })
        assertTrue(assembly.reachesTarget, "$label assembly covers target")

        val nested = MathLayoutEngine(face).layout(
            "\\binom{\\frac{\\frac{\\frac{a}{b}}{c}}{d}}{k}",
            MathLayoutOptions(MathMode.Display, size),
        )
        assertTrue(nested.debugDump.contains("BinomialDelimiter"), "$label pipeline records delimiter selection")
        assertFalse(nested.diagnostics.any { it.code == DiagnosticCode.MathVariantTooShort }, "$label delimiter covers stack")
    }

    @Test
    fun operatorTrailingBreakHasNoVisibleEdgeGlueForBothFonts() = withRealFaces { label, face ->
        val result = MathLayoutEngine(face).layout("a+b+c", MathLayoutOptions(fontSizePx = 40f))
        val plus = result.fragments[1]
        val maximumFirstLine = result.fragments[0].box.width + result.fragments[0].trailingAdvancePx + plus.box.width
        val broken = result.breakIntoLines(maximumFirstLine + 0.01f)

        assertTrue(broken.lines.size >= 2, "$label breaks the formula")
        assertEquals(listOf(0, 1), broken.lines.first().fragments.map { it.fragmentIndex }, "$label operator remains trailing")
        assertNear(maximumFirstLine, broken.lines.first().logicalWidth, "$label discards post-operator glue")
        assertNear(0f, broken.lines[1].fragments.first().x, "$label next line has no leading glue")
        broken.lines.forEach { line ->
            val last = line.fragments.last().fragmentIndex
            val replayWidth = line.fragments.sumOf {
                val fragment = result.fragments[it.fragmentIndex]
                (fragment.box.width + fragment.trailingItalicCorrectionPx).toDouble()
            }.toFloat() +
                line.fragments.dropLast(1).sumOf { it.resolvedTrailingAdvancePx.toDouble() }.toFloat()
            assertNear(replayWidth, line.logicalWidth, "$label measurement and replay agree at fragment $last")
            assertTrue(line.width >= line.logicalWidth || line.inkBounds.right <= line.logicalWidth)
        }
    }

    @Test
    fun extremeSyntheticMathConstantsControlNamedGeometryWithoutFontIdentityBranches() {
        SkiaMathFontFace(LeteSansMath.load()).use { realFace ->
            val size = 100f
            val base = realFace.mathFont.constants
            val shifted = base.copy(
                scriptPercentScaleDown = 43,
                scriptScriptPercentScaleDown = 27,
                delimitedSubFormulaMinHeight = 2500,
                mathLeading = 310,
                axisHeight = 123,
                subscriptShiftDown = 720,
                subscriptTopMax = 1000,
                subscriptBaselineDropMin = 0,
                superscriptShiftUp = 810,
                superscriptShiftUpCramped = 610,
                superscriptBottomMin = 0,
                superscriptBaselineDropMax = 1000,
                subSuperscriptGapMin = 0,
                superscriptBottomMaxWithSubscript = 1000,
                spaceAfterScript = 190,
                stackTopShiftUp = 800,
                stackTopDisplayStyleShiftUp = 930,
                stackBottomShiftDown = 700,
                stackBottomDisplayStyleShiftDown = 940,
                stackGapMin = 0,
                stackDisplayStyleGapMin = 0,
                fractionNumeratorShiftUp = 760,
                fractionNumeratorDisplayStyleShiftUp = 920,
                fractionDenominatorShiftDown = 740,
                fractionDenominatorDisplayStyleShiftDown = 910,
                fractionNumeratorGapMin = 0,
                fractionNumDisplayStyleGapMin = 0,
                fractionRuleThickness = 90,
                fractionDenominatorGapMin = 0,
                fractionDenomDisplayStyleGapMin = 0,
            )
            val face = ConstantOverrideFace(realFace, shifted)
            val engine = MathLayoutEngine(face)

            val fraction = engine.layout("\\frac{a}{b}", MathLayoutOptions(MathMode.Display, size))
            val rule = fraction.box.rules.single()
            assertNear(-12.3f, (rule.top + rule.bottom) / 2f, "synthetic axisHeight")
            assertNear(9f, rule.bottom - rule.top, "synthetic fractionRuleThickness")
            assertNear(-92f, fraction.glyphAt(6).baselineY, "synthetic display numerator shift")
            assertNear(91f, fraction.glyphAt(9).baselineY, "synthetic display denominator shift")
            assertNear(31f, fraction.lineMetrics.mathLeadingPx, "synthetic mathLeading")

            val scripts = engine.layout("x_1^2", MathLayoutOptions(MathMode.Display, size))
            assertNear(-81f, scripts.glyphAt(4).baselineY, "synthetic superscriptShiftUp")
            assertNear(72f, scripts.glyphAt(2).baselineY, "synthetic subscriptShiftDown")
            assertNear(43f, scripts.glyphAt(4).fontSizePx, "synthetic scriptPercentScaleDown")
            val noAfterScript = ConstantOverrideFace(realFace, shifted.copy(spaceAfterScript = 0))
            val widthWithout = MathLayoutEngine(noAfterScript).layout("x^2", MathLayoutOptions(fontSizePx = size)).box.width
            val widthWith = engine.layout("x^2", MathLayoutOptions(fontSizePx = size)).box.width
            assertNear(19f, widthWith - widthWithout, "synthetic spaceAfterScript")

            val cramped = engine.layout("x_{y^z}", MathLayoutOptions(MathMode.Display, size))
            val y = cramped.glyphAt(3)
            val z = cramped.glyphAt(5)
            assertNear(-26.23f, z.baselineY - y.baselineY, "cramped shift scales at 43 percent")
            assertNear(27f, engine.layout("\\scriptscriptstyle{q}", MathLayoutOptions(fontSizePx = size)).box.glyphs.single().fontSizePx)

            val stack = engine.layout("\\binom{n}{k}", MathLayoutOptions(MathMode.Display, size))
            assertNear(-93f, stack.glyphAt(7).baselineY, "synthetic stackTopDisplayStyleShiftUp")
            assertNear(94f, stack.glyphAt(10).baselineY, "synthetic stackBottomDisplayStyleShiftDown")
            assertTrue(stack.debugDump.contains("fixedTargetPx=250.0"), "synthetic delimitedSubFormulaMinHeight")
            assertTrue(
                stack.debugDump.contains("targetPolicy=TeXFractionNoadFixedWithAxisInkSafety"),
                "fixed binomial target remains separate from axis-relative ink coverage",
            )

            verifyExtremeMinimumGaps(realFace, base, size)
        }
    }

    @Test
    fun missingGlyphIsDiagnosedWithoutPerGlyphFallbackForBothFonts() = withRealFaces { label, face ->
        val result = MathLayoutEngine(face).layout("x\uDBFF\uDFFF+y", MathLayoutOptions(fontSizePx = 30f))
        val missing = result.diagnostics.filter { it.code == DiagnosticCode.MissingGlyph }
        assertEquals(1, missing.size, "$label reports the missing glyph on the selected face")
        assertEquals(SourceRange(1, 3), missing.single().range)
        assertTrue(missing.single().message.contains("formula-wide"))
    }

    private fun verifyExtremeMinimumGaps(
        realFace: SkiaMathFontFace,
        base: OpenTypeMathConstants,
        size: Float,
    ) {
        val gaps = base.copy(
            subscriptShiftDown = 0,
            subscriptTopMax = 1000,
            subscriptBaselineDropMin = 0,
            superscriptShiftUp = 0,
            superscriptShiftUpCramped = 0,
            superscriptBottomMin = 0,
            superscriptBaselineDropMax = 1000,
            subSuperscriptGapMin = 1200,
            superscriptBottomMaxWithSubscript = 2000,
            fractionNumeratorDisplayStyleShiftUp = 0,
            fractionDenominatorDisplayStyleShiftDown = 0,
            fractionNumDisplayStyleGapMin = 610,
            fractionDenomDisplayStyleGapMin = 730,
            stackTopDisplayStyleShiftUp = 0,
            stackBottomDisplayStyleShiftDown = 0,
            stackDisplayStyleGapMin = 1400,
        )
        val face = ConstantOverrideFace(realFace, gaps)
        val scripts = MathLayoutEngine(face).layout("x_1^2", MathLayoutOptions(MathMode.Display, size))
        val scriptGap = scripts.glyphAt(2).inkBounds.top - scripts.glyphAt(4).inkBounds.bottom
        assertNear(120f, scriptGap, "synthetic subSuperscriptGapMin")

        val fraction = MathLayoutEngine(face).layout("\\frac{a}{b}", MathLayoutOptions(MathMode.Display, size))
        val rule = fraction.box.rules.single()
        assertNear(61f, rule.top - fraction.glyphAt(6).inkBounds.bottom, "synthetic fractionNumDisplayStyleGapMin")
        assertNear(73f, fraction.glyphAt(9).inkBounds.top - rule.bottom, "synthetic fractionDenomDisplayStyleGapMin")

        val stack = MathLayoutEngine(face).layout("\\binom{n}{k}", MathLayoutOptions(MathMode.Display, size))
        val stackGap = stack.glyphAt(10).inkBounds.top - stack.glyphAt(7).inkBounds.bottom
        assertNear(140f, stackGap, "synthetic stackDisplayStyleGapMin")
    }
}

private class ConstantOverrideFace(
    private val delegate: SkiaMathFontFace,
    constants: OpenTypeMathConstants,
) : MathFontFace {
    override val mathFont: OpenTypeMathFont = delegate.mathFont.copy(constants = constants)

    override fun shape(text: String, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange): MeasuredMathRun =
        delegate.shape(text, fontSizePx, style, sourceRange)

    override fun measureGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.measureGlyph(glyphId, fontSizePx, style, sourceRange)
}

private inline fun withRealFaces(block: (String, SkiaMathFontFace) -> Unit) {
    listOf(
        "Lete Sans Math" to LeteSansMath.load(),
        "STIX Two Math" to StixTwoMath.load(),
    ).forEach { (label, font) -> SkiaMathFontFace(font).use { block(label, it) } }
}

private fun MathLayoutResult.glyphAt(sourceOffset: Int): MathGlyphPlacement =
    box.glyphs.firstOrNull { it.sourceRange == SourceRange(sourceOffset, sourceOffset + 1) }
        ?: box.glyphs.first { sourceOffset in it.sourceRange.start until it.sourceRange.endExclusive }

private fun expectedSize(font: OpenTypeMathFont, baseSize: Float, style: MathStyle): Float = when (style.level) {
    MathStyleLevel.Display, MathStyleLevel.Text -> baseSize
    MathStyleLevel.Script -> baseSize * font.constants.scriptPercentScaleDown / 100f
    MathStyleLevel.ScriptScript -> baseSize * font.constants.scriptScriptPercentScaleDown / 100f
}

private fun assertAtLeast(actual: Float, minimum: Float, message: String) {
    assertTrue(actual + 0.02f >= minimum, "$message: expected >= $minimum, got $actual")
}

private fun assertNear(expected: Float, actual: Float, message: String = "") {
    assertTrue(abs(expected - actual) <= 0.03f, "$message: expected $expected, got $actual")
}
