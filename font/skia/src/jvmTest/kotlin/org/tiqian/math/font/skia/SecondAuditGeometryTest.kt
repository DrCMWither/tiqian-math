package org.tiqian.math.font.skia

import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecondAuditGeometryTest {
    @Test
    fun fractionsAreOrdinaryNoadsAtNormalParenthesizedAndScriptBoundaries() = withSecondAuditFaces { label, face ->
        val engine = MathLayoutEngine(face)
        listOf(
            "a\\frac{b}{c}d",
            "(\\frac{a}{b})",
            "a\\frac{b}{c}_1d",
        ).forEach { source ->
            val result = engine.layout(source, MathLayoutOptions(fontSizePx = 40f))
            assertTrue(result.diagnostics.isEmpty(), "$label/$source: ${result.diagnostics}")
            assertTrue(
                result.decisions.filter { it.name == "TeXMathAtomSpacing" }
                    .all { it.details["kind"] == "None" },
                "$label/$source ordinary fraction noad has no implicit Inner glue",
            )
        }
    }

    @Test
    fun italicCorrectionIsFinalGlyphBasedAndOnlyConsumedBeforeUprightAtoms() = withSecondAuditFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val continuous = engine.layout("abc", MathLayoutOptions(fontSizePx = 40f))
        assertTrue(continuous.fragments.all { it.trailingItalicCorrectionPx == 0f }, "$label continuous variables")

        val beforeOperator = engine.layout("x+y", MathLayoutOptions(fontSizePx = 40f))
        val x = beforeOperator.fragments.first()
        val finalItalicGlyph = x.box.glyphs.single().glyphId
        val expected = face.mathFont.italicCorrection(finalItalicGlyph, 40f)
        assertNear(expected, x.trailingItalicCorrectionPx, "$label x before operator")
        assertTrue(x.trailingItalicCorrectionPx > 0f, "$label audited italic x has correction")
        assertTrue(beforeOperator.decisions.any { it.name == "OpenTypeItalicCorrectionBoundary" })

        val beforeDelimiter = engine.layout("x)", MathLayoutOptions(fontSizePx = 40f))
        assertNear(expected, beforeDelimiter.fragments.first().trailingItalicCorrectionPx, "$label x before delimiter")

        val scripted = engine.layout("x^2+y", MathLayoutOptions(fontSizePx = 40f))
        assertNear(0f, scripted.fragments.first().trailingItalicCorrectionPx, "$label script box owns its horizontal extent")
    }

    @Test
    fun baselineDropUsesCharacterCompoundAndExtendedShapeClassification() = withSecondAuditFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val character = engine.layout("E^2", MathLayoutOptions(MathMode.Display, 48f)).scriptDecision()
        assertEquals("Character", character.details["baseKind"], label)
        assertEquals("false", character.details["baselineDropApplied"], label)

        val compound = engine.layout("{xy}^2", MathLayoutOptions(MathMode.Display, 48f)).scriptDecision()
        assertEquals("CompoundBox", compound.details["baseKind"], label)
        assertEquals("true", compound.details["baselineDropApplied"], label)

        val parenthesisGlyph = face.shape("(", 48f, MathStyle.Display, SourceRange(0, 1)).glyphs.single().glyphId
        assertTrue(parenthesisGlyph in face.mathFont.extendedShapeGlyphs, "$label parenthesis is covered as extended")
        val extended = engine.layout("(^2", MathLayoutOptions(MathMode.Display, 48f)).scriptDecision()
        assertEquals("ExtendedShape", extended.details["baseKind"], label)
        assertEquals("true", extended.details["baselineDropApplied"], label)
    }

    @Test
    fun rulelessGapDeficitIsSplitSymmetricallyAroundTheExistingStack() = withSecondAuditFaces { label, delegate ->
        val constants = delegate.mathFont.constants.copy(
            stackTopShiftUp = 100,
            stackBottomShiftDown = 300,
            stackGapMin = 900,
        )
        val face = SecondAuditOverrideFace(delegate, delegate.mathFont.copy(constants = constants))
        val decision = MathLayoutEngine(face).layout("\\binom{n}{n}", MathLayoutOptions(fontSizePx = 40f))
            .decisions.first { it.name == "OpenTypeMathFractionStack" && it.details["kind"] == "ruleless" }
        val numeratorShift = decision.details.getValue("numeratorShiftPx").toFloat()
        val denominatorShift = decision.details.getValue("denominatorShiftPx").toFloat()
        val halfCorrection = decision.details.getValue("symmetricGapCorrectionPx").toFloat()
        assertTrue(halfCorrection > 0f, label)
        assertNear(halfCorrection, numeratorShift - 4f, "$label numerator half")
        assertNear(halfCorrection, denominatorShift - 12f, "$label denominator half")
    }

    @Test
    fun logicalAdvanceStaysSeparateFromInkAndOnlyCollisionKernsSeparateBinomialInk() = withSecondAuditFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val size = 40f
        val child = engine.layout(
            "x",
            MathLayoutOptions(fontSizePx = size, initialStyle = MathStyle.Script),
        )
        val fraction = engine.layout("\\frac{x}{x}", MathLayoutOptions(fontSizePx = size))
        val rule = fraction.box.rules.single()
        assertNear(child.box.width, rule.right - rule.left, "$label rule uses child logical advance")
        assertTrue(fraction.box.inkBounds.left < rule.left + 0.02f, "$label numerator overhang remains ink, not width")

        val source = "\\binom{n}{k}"
        val binomial = engine.layout(source, MathLayoutOptions(fontSizePx = size))
        val content = binomial.box.glyphs.filter {
            it.sourceRange == SourceRange(source.indexOf('n'), source.indexOf('n') + 1) ||
                it.sourceRange == SourceRange(source.indexOf('k'), source.indexOf('k') + 1)
        }
        val delimiter = binomial.box.glyphs - content.toSet()
        val left = delimiter.filter { it.inkBounds.right <= content.minOf { glyph -> glyph.inkBounds.left } + 0.02f }
        val right = delimiter.filter { it.inkBounds.left >= content.maxOf { glyph -> glyph.inkBounds.right } - 0.02f }
        assertTrue(left.isNotEmpty() && right.isNotEmpty(), "$label delimiter sides are collision-separated")
        assertTrue(left.maxOf { it.inkBounds.right } <= content.minOf { it.inkBounds.left } + 0.02f, label)
        assertTrue(content.maxOf { it.inkBounds.right } <= right.minOf { it.inkBounds.left } + 0.02f, label)
        assertTrue(binomial.decisions.filter { it.name == "BinomialDelimiter" }
            .all { it.details["coversTop"] == "true" && it.details["coversBottom"] == "true" })
    }

    @Test
    fun breakerOverflowsIndivisibleSegmentsInsteadOfInventingAtomBreaks() = withSecondAuditFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val letters = engine.layout("abcdef", MathLayoutOptions(fontSizePx = 40f))
        assertTrue(letters.breakOpportunities.isEmpty(), label)
        val oneOverflow = letters.breakIntoLines(letters.box.width / 3f)
        assertEquals(1, oneOverflow.lines.size, label)
        assertEquals(6, oneOverflow.lines.single().fragments.size, label)
        assertTrue(oneOverflow.lines.single().unbreakableOverflow, label)

        val operator = engine.layout("a+b", MathLayoutOptions(fontSizePx = 40f))
        val broken = operator.breakIntoLines(operator.fragments.first().box.width + 0.1f)
        assertEquals(listOf(2, 1), broken.lines.map { it.fragments.size }, "$label binary stays with preceding segment")
        assertTrue(broken.lines.first().unbreakableOverflow, label)
    }
}

private class SecondAuditOverrideFace(
    private val delegate: SkiaMathFontFace,
    override val mathFont: OpenTypeMathFont,
) : MathFontFace {
    override fun resolveSymbol(
        request: org.tiqian.math.layout.MathSymbolGlyphRequest,
        fontSizePx: Float,
    ): org.tiqian.math.layout.ResolvedMathSymbol = delegate.resolveSymbol(request, fontSizePx)

    override fun shape(text: String, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange): MeasuredMathRun =
        delegate.shape(text, fontSizePx, style, sourceRange)

    override fun measureGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.measureGlyph(glyphId, fontSizePx, style, sourceRange)
}

private inline fun withSecondAuditFaces(block: (String, SkiaMathFontFace) -> Unit) {
    listOf(
        "Lete Sans Math" to LeteSansMath.load(),
        "STIX Two Math" to StixTwoMath.load(),
    ).forEach { (label, font) -> SkiaMathFontFace(font).use { block(label, it) } }
}

private fun MathLayoutResult.scriptDecision(): MathLayoutDecision =
    decisions.first { it.name == "OpenTypeMathScriptPlacement" }

private fun assertNear(expected: Float, actual: Float, message: String) {
    assertTrue(abs(expected - actual) <= 0.04f, "$message: expected $expected, got $actual")
}
