package org.tiqian.math.font.skia

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathAlphabet
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathFormulaCapabilityCategory
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MathOperatorNameAndVersionTest {
    @Test
    fun operatorNamesUseTheSharedLimitsPolicyAndStackingKernel() = withSemanticFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val source = "\\lim_{n\\to\\infty}"
        val display = engine.layout(source, MathLayoutOptions(MathMode.Display, 32f))
        assertTrue(display.diagnostics.isEmpty(), "$label/display: ${display.diagnostics}")

        val displayPolicy = display.decisions.single { it.name == "TeXOperatorLimitsPolicy" }
        assertEquals("operator-name:lim", displayPolicy.details["identity"], label)
        assertEquals("Auto", displayPolicy.details["declaredPolicy"], label)
        assertEquals("Limits", displayPolicy.details["effectivePolicy"], label)
        assertEquals("auto-display", displayPolicy.details["reason"], label)
        val stacked = display.decisions.single { it.name == "OpenTypeMathOperatorLimits" }
        val baseGlyphs = display.box.glyphs.filter { it.sourceRange == SourceRange(0, 4) }
        val limitGlyphs = display.box.glyphs.filter { it.sourceRange != SourceRange(0, 4) }
        assertEquals(3, baseGlyphs.size, "$label lim glyphs")
        assertTrue(baseGlyphs.all { it.baselineY == 0f }, "$label base baseline")
        assertTrue(limitGlyphs.isNotEmpty() && limitGlyphs.all { it.baselineY > 0f }, "$label stacked lower limit")
        assertNear(
            stacked.details.getValue("operatorX").toFloat() +
                stacked.details.getValue("operatorWidthPx").toFloat() / 2f,
            stacked.details.getValue("lowerX").toFloat() +
                stacked.details.getValue("lowerWidthPx").toFloat() / 2f,
            "$label centered lower limit",
        )

        val inline = engine.layout(source, MathLayoutOptions(MathMode.Inline, 32f))
        assertTrue(inline.diagnostics.isEmpty(), "$label/inline: ${inline.diagnostics}")
        assertEquals(
            "NoLimits",
            inline.decisions.single { it.name == "TeXOperatorLimitsPolicy" }.details["effectivePolicy"],
            label,
        )
        assertTrue(inline.decisions.none { it.name == "OpenTypeMathOperatorLimits" }, label)
        assertTrue(inline.decisions.any { it.name == "OpenTypeMathScriptPlacement" }, label)

        val sinDisplay = engine.layout("\\sin_n", MathLayoutOptions(MathMode.Display, 32f))
        assertEquals(
            "NoLimits",
            sinDisplay.decisions.single { it.name == "TeXOperatorLimitsPolicy" }.details["effectivePolicy"],
            "$label sin default",
        )
        assertTrue(sinDisplay.decisions.none { it.name == "OpenTypeMathOperatorLimits" }, label)

        val forcedSin = engine.layout("\\sin\\limits_n", MathLayoutOptions(MathMode.Inline, 32f))
        assertTrue(forcedSin.diagnostics.isEmpty(), "$label/forced sin: ${forcedSin.diagnostics}")
        assertEquals(
            "Limits",
            forcedSin.decisions.single { it.name == "TeXOperatorLimitsPolicy" }.details["effectivePolicy"],
            label,
        )
        assertTrue(forcedSin.decisions.any { it.name == "OpenTypeMathOperatorLimits" }, label)
        assertTrue(
            forcedSin.box.glyphs.filter { it.sourceRange == SourceRange(0, 4) }.size == 3,
            "$label operator-name glyph ranges stay on commandRange",
        )
        assertTrue(forcedSin.box.glyphs.none { it.sourceRange == SourceRange(4, 11) }, label)

        val suppressedLim = engine.layout("\\lim\\nolimits_n", MathLayoutOptions(MathMode.Display, 32f))
        assertEquals(
            "NoLimits",
            suppressedLim.decisions.single { it.name == "TeXOperatorLimitsPolicy" }.details["effectivePolicy"],
            label,
        )
        assertTrue(suppressedLim.decisions.none { it.name == "OpenTypeMathOperatorLimits" }, label)
    }

    @Test
    fun boldsymbolUsesABoldMathVersionOrRequiresWholeFormulaFallback() = withSemanticFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val normalLambda = engine.layout("\\lambda", MathLayoutOptions(fontSizePx = 40f))
        val boldLambda = engine.layout("\\boldsymbol{\\lambda}", MathLayoutOptions(fontSizePx = 40f))
        assertTrue(boldLambda.diagnostics.isEmpty(), "$label lambda: ${boldLambda.diagnostics}")
        assertNotEquals(normalLambda.box.glyphs.single().glyphId, boldLambda.box.glyphs.single().glyphId, label)
        assertNotEquals(normalLambda.box.width, boldLambda.box.width, "$label real bold geometry")
        assertEquals(
            "BoldItalic",
            boldLambda.decisions.single { it.name == "TeXMathSymbolResolution" }.details["resolvedAlphabet"],
            label,
        )
        assertTrue(boldLambda.decisions.any { it.name == "TeXMathVersionScope" }, label)
        assertIs<MathFormulaCapabilityResult.Ready>(
            face.formulaCapabilityEngine().evaluate("\\boldsymbol{\\lambda}"),
            label,
        )

        val normalSin = engine.layout("\\sin", MathLayoutOptions(fontSizePx = 40f))
        val boldSin = engine.layout("\\boldsymbol{\\sin}", MathLayoutOptions(fontSizePx = 40f))
        assertTrue(boldSin.diagnostics.isEmpty(), "$label sin: ${boldSin.diagnostics}")
        assertNotEquals(normalSin.box.glyphs.map { it.glyphId }, boldSin.box.glyphs.map { it.glyphId }, label)
        assertTrue(
            boldSin.decisions.filter { it.name == "TeXMathSymbolResolution" }
                .all { it.details["resolvedAlphabet"] == "Bold" },
            label,
        )

        listOf("\\boldsymbol{+}", "\\boldsymbol{\\sum}").forEach { source ->
            val lowLevel = engine.layout(source, MathLayoutOptions(fontSizePx = 40f))
            assertTrue(lowLevel.diagnostics.any { it.code == DiagnosticCode.UnsupportedMathAlphabet }, "$label/$source")
            val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                face.formulaCapabilityEngine().evaluate(source),
                "$label/$source",
            )
            assertTrue(
                fallback.reasons.any { it.category == MathFormulaCapabilityCategory.UnsupportedSyntax },
                "$label/$source: ${fallback.reasons}",
            )
        }
    }

    @Test
    fun directStyledUnicodeAndAlphabetCommandsResolveIdentically() = withSemanticFaces { label, face ->
        val engine = MathLayoutEngine(face)
        listOf(
            StyledCase(0x1D49C, "\\mathcal{A}", MathAlphabet.Script),
            StyledCase(0x211B, "\\mathcal{R}", MathAlphabet.Script),
            StyledCase(0x1D524, "\\mathfrak{g}", MathAlphabet.Fraktur),
            StyledCase(0x212D, "\\mathfrak{C}", MathAlphabet.Fraktur),
            StyledCase(0x1D569, "\\mathbb{x}", MathAlphabet.DoubleStruck),
            StyledCase(0x211D, "\\mathbb{R}", MathAlphabet.DoubleStruck),
            StyledCase(0x1D670, "\\mathtt{A}", MathAlphabet.Monospace),
            StyledCase(0x1D7FD, "\\mathtt{7}", MathAlphabet.Monospace),
        ).forEach { case ->
            val directSource = String(Character.toChars(case.scalar))
            val direct = engine.layout(directSource, MathLayoutOptions(fontSizePx = 40f))
            val command = engine.layout(case.command, MathLayoutOptions(fontSizePx = 40f))
            assertTrue(direct.diagnostics.isEmpty(), "$label/U+${case.scalar.toString(16)}: ${direct.diagnostics}")
            assertTrue(command.diagnostics.isEmpty(), "$label/${case.command}: ${command.diagnostics}")
            val directGlyph = direct.box.glyphs.single()
            val commandGlyph = command.box.glyphs.single()
            assertEquals(commandGlyph.glyphId, directGlyph.glyphId, "$label/${case.command} glyph")
            assertNear(commandGlyph.advance, directGlyph.advance, "$label/${case.command} advance")
            assertEquals(commandGlyph.inkBounds, directGlyph.inkBounds, "$label/${case.command} ink")
            val directDecision = direct.decisions.single { it.name == "TeXMathSymbolResolution" }
            val commandDecision = command.decisions.single { it.name == "TeXMathSymbolResolution" }
            assertEquals(case.alphabet.name, directDecision.details["declaredAlphabet"], case.command)
            assertEquals(commandDecision.details["backendScalar"], directDecision.details["backendScalar"], case.command)
            assertIs<MathFormulaCapabilityResult.Ready>(face.formulaCapabilityEngine().evaluate(directSource), label)
        }
    }
}

private data class StyledCase(
    val scalar: Int,
    val command: String,
    val alphabet: MathAlphabet,
)

private inline fun withSemanticFaces(block: (String, SkiaMathFontFace) -> Unit) {
    SkiaMathFontFace(LeteSansMath.load()).use { block("Lete", it) }
    SkiaMathFontFace(StixTwoMath.load()).use { block("STIX", it) }
}

private fun assertNear(expected: Float, actual: Float, label: String, epsilon: Float = 0.02f) {
    assertTrue(abs(expected - actual) <= epsilon, "$label expected=$expected actual=$actual")
}
