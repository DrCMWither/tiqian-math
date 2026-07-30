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
    fun parsesRadicalNoadDegreeAndUtf16SourceRangesWithoutRewritingSource() {
        val source = "𝑥+\\sqrt[3]{x^2+1}+\\sqrt{\\frac{a}{b}}"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val radicals = result.root.children.filterIsInstance<MathRadical>()
        assertEquals(2, radicals.size)
        val indexed = radicals[0]
        assertEquals("\\sqrt", indexed.sourceText)
        assertEquals(SourceRange(3, 8), indexed.commandRange)
        assertEquals(SourceRange(8, 11), indexed.degreeRange)
        assertEquals(SourceRange(3, 18), indexed.range)
        val degree = assertIs<MathList>(indexed.degree)
        assertEquals(SourceRange(9, 10), degree.range)
        assertEquals("3", assertIs<MathSymbol>(degree.children.single()).sourceText)
        assertEquals(SourceRange(11, 18), indexed.radicand.range)
        assertIs<MathGroup>(indexed.radicand)
        assertIs<MathFraction>(assertIs<MathGroup>(radicals[1].radicand).body.children.single())

        val unbraced = MathParser().parse("\\sqrt[3]x+y")
        assertTrue(unbraced.diagnostics.isEmpty(), unbraced.diagnostics.toString())
        assertEquals(3, unbraced.root.children.size)
        assertIs<MathSymbol>(assertIs<MathRadical>(unbraced.root.children.first()).radicand)

        val ordinaryBrackets = MathParser().parse("[x]").root.children.filterIsInstance<MathSymbol>()
        assertEquals(3, ordinaryBrackets.size)
        assertEquals(MathNamedSymbol.LeftBracket, assertIs<MathSymbolIdentity.Named>(ordinaryBrackets.first().identity).symbol)

        val generalDelimiters = MathParser().parse("\\left(x\\right)")
        assertTrue(generalDelimiters.diagnostics.isEmpty(), generalDelimiters.diagnostics.toString())
        assertIs<MathDelimited>(generalDelimiters.root.children.single())
    }

    @Test
    fun radicalSyntaxErrorsRecoverWithStructuredDiagnostics() {
        val missingDegree = MathParser().parse("\\sqrt[]{x}+y")
        assertTrue(missingDegree.diagnostics.any { it.code == DiagnosticCode.MissingRadicalDegree })
        assertIs<MathRadical>(missingDegree.root.children.first())
        assertIs<MathSymbol>(missingDegree.root.children.last())

        val unclosedDegree = MathParser().parse("\\sqrt[3+x")
        assertTrue(unclosedDegree.diagnostics.any { it.code == DiagnosticCode.UnclosedRadicalDegree })
        assertTrue(unclosedDegree.diagnostics.any { it.code == DiagnosticCode.MissingRadicalRadicand })
        assertIs<MathRadical>(unclosedDegree.root.children.single())

        val missingRadicand = MathParser().parse("x+\\sqrt")
        assertTrue(missingRadicand.diagnostics.any { it.code == DiagnosticCode.MissingRadicalRadicand })
        assertIs<MathRadical>(missingRadicand.root.children.last())

        val unclosedRadicand = MathParser().parse("\\sqrt{x+1")
        assertTrue(unclosedRadicand.diagnostics.any { it.code == DiagnosticCode.UnclosedRadicalRadicand })
        assertIs<MathRadical>(unclosedRadicand.root.children.single())
    }

    @Test
    fun parsesLargeOperatorsWithPlainTexDefaultsAndExplicitLimitPolicies() {
        val source = "\\sum_{i=1}^n+\\prod\\nolimits_k+\\int_0^1+\\oint\\limits_C"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val scripted = result.root.children.filterIsInstance<MathScripts>()
        assertEquals(4, scripted.size)

        val sum = assertIs<MathOperator>(scripted[0].base)
        assertEquals(MathLargeOperatorIdentity.Sum, sum.identity)
        assertEquals(MathLimitsPolicy.Auto, sum.limitsPolicy)
        assertTrue(!sum.hasExplicitLimitsPolicy)
        assertEquals("\\sum", sum.sourceText)
        assertEquals(SourceRange(0, 4), sum.commandRange)
        assertEquals(SourceRange(0, 4), sum.range)

        val product = assertIs<MathOperator>(scripted[1].base)
        assertEquals(MathLargeOperatorIdentity.Product, product.identity)
        assertEquals(MathLimitsPolicy.NoLimits, product.limitsPolicy)
        assertEquals(
            SourceRange(source.indexOf("\\nolimits"), source.indexOf("\\nolimits") + "\\nolimits".length),
            product.limitsModifierRange,
        )
        assertEquals(source.indexOf("\\prod") + "\\prod\\nolimits".length, product.range.endExclusive)

        val integral = assertIs<MathOperator>(scripted[2].base)
        assertEquals(MathLargeOperatorIdentity.Integral, integral.identity)
        assertEquals(MathLimitsPolicy.NoLimits, integral.limitsPolicy)
        assertTrue(!integral.hasExplicitLimitsPolicy)

        val contour = assertIs<MathOperator>(scripted[3].base)
        assertEquals(MathLargeOperatorIdentity.ContourIntegral, contour.identity)
        assertEquals(MathLimitsPolicy.Limits, contour.limitsPolicy)
        assertEquals(SourceRange(source.indexOf("\\oint"), source.indexOf("_C")), contour.range)
    }

    @Test
    fun operatorLimitModifiersCanSwitchBeforeBetweenOrAfterScripts() {
        val result = MathParser().parse(
            "\\sum\\limits_i^n+\\sum_i\\nolimits^n+\\int_0^1\\limits+\\prod\\limits\\nolimits_k",
        )

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val operators = result.root.children.filterIsInstance<MathScripts>().map {
            assertIs<MathOperator>(it.base)
        }
        assertEquals(
            listOf(
                MathLimitsPolicy.Limits,
                MathLimitsPolicy.NoLimits,
                MathLimitsPolicy.Limits,
                MathLimitsPolicy.NoLimits,
            ),
            operators.map { it.limitsPolicy },
        )
        assertTrue(operators.all { it.hasExplicitLimitsPolicy })
        assertTrue(operators.all { it.sourceText.length == it.commandRange.length })
        assertTrue(operators.all { it.range.endExclusive == it.limitsModifierRange?.endExclusive })
    }

    @Test
    fun limitsModifierOutsideAnOperatorRecoversExplicitly() {
        val result = MathParser().parse("x\\limits+y\\nolimits")

        assertEquals(2, result.diagnostics.count { it.code == DiagnosticCode.MisplacedLimitsModifier })
        assertEquals(2, result.root.children.count { it is MathErrorNode })
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
