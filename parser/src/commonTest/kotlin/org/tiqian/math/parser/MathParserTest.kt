package org.tiqian.math.parser

import org.tiqian.math.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MathParserTest {
    @Test
    fun tokenizerUsesUtf16RangesAndStopsControlWordsBeforeCjk() {
        val source = "𝑥+\\alpha函数"
        val tokens = MathTokenizer().tokenize(source).tokens

        assertEquals(SourceRange(0, 2), tokens[0].range)
        assertEquals("alpha", tokens[2].text)
        assertEquals(SourceRange(3, 9), tokens[2].range)
        assertEquals("函", tokens[3].text)
    }

    @Test
    fun hostMacroExpansionRetainsInvocationAndArgumentRanges() {
        val result = MathParser(
            macros = listOf(MathMacroDefinition("twice", 1, "#1+#1")),
        ).parse("a+\\twice{β}")

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val expanded = result.root.children
        assertEquals(5, expanded.size)
        assertEquals(SourceRange(9, 10), assertIs<MathSymbol>(expanded[2]).range)
        assertEquals(SourceRange(2, 11), assertIs<MathSymbol>(expanded[3]).range)
        assertEquals(SourceRange(9, 10), assertIs<MathSymbol>(expanded[4]).range)
    }

    @Test
    fun parsesPairedScriptsFractionBinomialAndStyles() {
        val source = "x_1^2+\\frac{a}{\\binom{n}{k}}+\\scriptstyle{y}"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val scripts = assertIs<MathScripts>(result.root.children.first())
        assertEquals(SourceRange(0, 5), scripts.range)
        assertTrue(scripts.superscript != null && scripts.subscript != null)
        val fraction = assertIs<MathFraction>(result.root.children[2])
        assertEquals(FractionKind.Barred, fraction.kind)
        val binomial = assertIs<MathFraction>(assertIs<MathGroup>(fraction.denominator).body.children.single())
        assertEquals(FractionKind.Ruleless, binomial.kind)
        assertTrue(binomial.hasParentheses)
        assertEquals(MathStyleLevel.Script, assertIs<MathStyleScope>(result.root.children[4]).requestedLevel)
    }

    @Test
    fun recoversAfterUnknownCommandAndUnclosedGroup() {
        val result = MathParser().parse("a+\\unknown{b+c")

        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.UnknownCommand })
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.UnclosedGroup })
        assertTrue(result.root.children.size >= 3)
        assertEquals(SourceRange(2, 10), result.diagnostics.first { it.code == DiagnosticCode.UnknownCommand }.range)
    }

    @Test
    fun realZhihuRegressionShapeParsesWithoutSourceLoss() {
        val source = "E_k=(n-1)E_{k-1}+E_{k-2}"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(SourceRange(0, source.length), result.root.range)
        assertTrue(result.root.children.filterIsInstance<MathScripts>().size >= 3)
    }

    @Test
    fun parserAssignsExplicitVariableVariantsWithoutChangingSourceRanges() {
        val source = "x+2+\\alpha+\\Gamma+𝑥"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val symbols = result.root.children.filterIsInstance<MathSymbol>()
        assertEquals(MathVariant.DefaultVariableItalic, symbols[0].variant)
        assertEquals(MathVariant.Upright, symbols[1].variant) // plus
        assertEquals(MathVariant.Upright, symbols[2].variant) // digit
        assertEquals(MathVariant.DefaultVariableItalic, symbols[4].variant) // alpha
        assertEquals(MathVariant.Upright, symbols[6].variant) // uppercase Gamma follows TeX
        assertEquals(MathVariant.ExplicitUnicode, symbols[8].variant)
        assertEquals(SourceRange(source.length - 2, source.length), symbols[8].range)
        assertEquals("𝑥", symbols[8].sourceText)
    }

    @Test
    fun romanScopeIsSemanticAndKeepsItsOriginalSource() {
        val result = MathParser().parse("x+\\mathrm{x+2}")
        val scope = assertIs<MathVariantScope>(result.root.children.last())
        assertEquals(MathVariant.Upright, scope.variant)
        assertEquals(SourceRange(2, 14), scope.range)
    }
}
