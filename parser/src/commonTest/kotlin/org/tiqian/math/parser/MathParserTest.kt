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
    fun parsesPairedScriptsFractionBinomialAndStyleDeclarations() {
        val source = "x_1^2+\\frac{a}{\\binom{n}{k}}+\\scriptstyle y"
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
        assertEquals(MathStyleLevel.Script, assertIs<MathStyleDeclaration>(result.root.children[4]).requestedLevel)
        assertIs<MathSymbol>(result.root.children[5])
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
    fun parserAssignsMathcodeFamilyAndAlphabetWithoutChangingSourceRanges() {
        val source = "x+2+\\alpha+\\Gamma+𝑥+𝛼"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val symbols = result.root.children.filterIsInstance<MathSymbol>()
        assertEquals(MathSymbolIdentity.LatinLetter('x'), symbols[0].identity)
        assertEquals(MathFamily.Letters, symbols[0].family)
        assertEquals(MathFamilyBinding.Variable, symbols[0].familyBinding)
        assertEquals(MathAlphabet.MathNormal, symbols[0].alphabet)
        assertEquals(MathNamedSymbol.Plus, assertIs<MathSymbolIdentity.Named>(symbols[1].identity).symbol)
        assertEquals(MathSymbolIdentity.Digit('2'), symbols[2].identity)
        assertEquals(MathFamily.Operators, symbols[2].family)
        assertEquals(MathNamedSymbol.Alpha, assertIs<MathSymbolIdentity.Named>(symbols[4].identity).symbol)
        assertEquals(MathFamilyBinding.Fixed, symbols[4].familyBinding)
        assertEquals(MathNamedSymbol.CapitalGamma, assertIs<MathSymbolIdentity.Named>(symbols[6].identity).symbol)
        assertEquals(MathFamily.Operators, symbols[6].family)
        assertEquals(MathSymbolIdentity.LatinLetter('x'), symbols[8].identity)
        assertEquals(MathAlphabet.Italic, symbols[8].alphabet)
        assertEquals(SourceRange(source.indexOf("𝑥"), source.indexOf("𝑥") + 2), symbols[8].range)
        assertEquals("𝑥", symbols[8].sourceText)
        assertEquals(MathNamedSymbol.Alpha, assertIs<MathSymbolIdentity.Named>(symbols[10].identity).symbol)
        assertEquals(MathAlphabet.Italic, symbols[10].alphabet)
        assertEquals(SourceRange(source.length - 2, source.length), symbols[10].range)
    }

    @Test
    fun romanScopeSelectsOperatorsAlphabetWithoutRewritingFixedFamilySymbols() {
        val result = MathParser().parse("\\mathrm{\\alpha x}")
        val scope = assertIs<MathAlphabetScope>(result.root.children.single())
        assertEquals(MathFamily.Operators, scope.family)
        assertEquals(MathAlphabet.Roman, scope.alphabet)
        val body = assertIs<MathGroup>(scope.body).body.children.filterIsInstance<MathSymbol>()
        assertEquals(MathFamilyBinding.Fixed, body[0].familyBinding)
        assertEquals(MathFamily.Letters, body[0].family)
        assertEquals(MathFamilyBinding.Variable, body[1].familyBinding)
        assertEquals(MathFamily.Letters, body[1].family)
        assertEquals(SourceRange(0, 17), scope.range)
    }

    @Test
    fun explicitMathematicalScalarsDecodeToBaseIdentityAndRequestedAlphabet() {
        val italic = assertIs<MathSymbol>(MathParser().parse("𝑥").root.children.single())
        assertEquals(MathSymbolIdentity.LatinLetter('x'), italic.identity)
        assertEquals(MathAlphabet.Italic, italic.alphabet)
        assertEquals("𝑥", italic.sourceText)

        val bold = assertIs<MathSymbol>(MathParser().parse("𝐱").root.children.single())
        assertEquals(MathSymbolIdentity.LatinLetter('x'), bold.identity)
        assertEquals(MathAlphabet.Bold, bold.alphabet)
        assertEquals(SourceRange(0, 2), bold.range)

        val boldAlpha = assertIs<MathSymbol>(MathParser().parse("𝛂").root.children.single())
        assertEquals(MathNamedSymbol.Alpha, assertIs<MathSymbolIdentity.Named>(boldAlpha.identity).symbol)
        assertEquals(MathFamily.Letters, boldAlpha.family)
        assertEquals(MathFamilyBinding.Fixed, boldAlpha.familyBinding)
        assertEquals(MathAlphabet.Bold, boldAlpha.alphabet)
    }

    @Test
    fun futureMathAlphabetCommandsAreExplicitlyUnsupported() {
        listOf("mathnormal", "mathit", "mathbf", "boldsymbol", "mathsf").forEach { command ->
            val result = MathParser().parse("\\$command{x}")
            assertTrue(result.diagnostics.any { it.code == DiagnosticCode.UnsupportedCommand }, command)
        }
    }

    @Test
    fun styleCommandsAreDeclarationsAndRemainLocalToTheirMlist() {
        val ungrouped = MathParser().parse("\\scriptstyle x+y")
        assertTrue(ungrouped.diagnostics.isEmpty(), ungrouped.diagnostics.toString())
        assertIs<MathStyleDeclaration>(ungrouped.root.children.first())
        assertEquals(4, ungrouped.root.children.size)

        val grouped = MathParser().parse("{\\scriptstyle x+y}z")
        val group = assertIs<MathGroup>(grouped.root.children.first())
        assertIs<MathStyleDeclaration>(group.body.children.first())
        assertIs<MathSymbol>(grouped.root.children.last())

        val trailing = MathParser().parse("x+\\scriptstyle")
        assertTrue(trailing.diagnostics.isEmpty(), trailing.diagnostics.toString())
        assertIs<MathStyleDeclaration>(trailing.root.children.last())
    }

    @Test
    fun ordinaryGroupsRemainSubMlistsInTheAst() {
        listOf("a{+}b", "a{b+c}d", "{x}^2").forEach { source ->
            val result = MathParser().parse(source)
            assertTrue(result.diagnostics.isEmpty(), "$source: ${result.diagnostics}")
            assertTrue(
                result.root.children.any { it is MathGroup } ||
                    result.root.children.filterIsInstance<MathScripts>().any { it.base is MathGroup },
                source,
            )
        }
    }

    @Test
    fun supportedSymbolTableUsesTeXIdentitiesAndAtomClasses() {
        val source = "-*/:!?\\{\\}\\epsilon\\varepsilon\\phi\\varphi"
        val symbols = MathParser().parse(source).root.children.filterIsInstance<MathSymbol>()
        val expected = listOf(
            MathNamedSymbol.Minus to MathAtomClass.Binary,
            MathNamedSymbol.AsteriskOperator to MathAtomClass.Binary,
            MathNamedSymbol.Slash to MathAtomClass.Ordinary,
            MathNamedSymbol.Colon to MathAtomClass.Relation,
            MathNamedSymbol.ExclamationMark to MathAtomClass.Closing,
            MathNamedSymbol.QuestionMark to MathAtomClass.Closing,
            MathNamedSymbol.LeftBrace to MathAtomClass.Opening,
            MathNamedSymbol.RightBrace to MathAtomClass.Closing,
            MathNamedSymbol.Epsilon to MathAtomClass.Ordinary,
            MathNamedSymbol.Varepsilon to MathAtomClass.Ordinary,
            MathNamedSymbol.Phi to MathAtomClass.Ordinary,
            MathNamedSymbol.Varphi to MathAtomClass.Ordinary,
        )
        assertEquals(expected.size, symbols.size)
        expected.zip(symbols).forEach { (expectation, symbol) ->
            assertEquals(expectation.first, assertIs<MathSymbolIdentity.Named>(symbol.identity).symbol)
            assertEquals(expectation.second, symbol.atomClass)
        }
        assertEquals(0x2212, symbols[0].identity.baseScalar)
        assertEquals(0x2217, symbols[1].identity.baseScalar)
        assertEquals(0x03F5, symbols[8].identity.baseScalar)
        assertEquals(0x03B5, symbols[9].identity.baseScalar)
        assertEquals(0x03D5, symbols[10].identity.baseScalar)
        assertEquals(0x03C6, symbols[11].identity.baseScalar)
    }
}
