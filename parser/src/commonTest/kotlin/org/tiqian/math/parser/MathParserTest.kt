package org.tiqian.math.parser

import org.tiqian.math.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MathParserTest {
    @Test
    fun colorIsAListDeclarationAndBoxedRetainsStructuredDisplayMathBody() {
        val source = "{\\color{red}a+{\\color{blue}b}+c}+\\boxed{\\frac{a}{b}}"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val colored = assertIs<MathGroup>(result.root.children.first())
        val outerColor = assertIs<MathColorDeclaration>(colored.body.children[0])
        assertEquals("red", outerColor.sourceName)
        assertEquals(MathPaintColor(255, 0, 0), outerColor.color)
        assertEquals(SourceRange(1, 7), outerColor.commandRange)
        assertEquals(SourceRange(8, 11), outerColor.nameRange)
        assertEquals(SourceRange(1, 12), outerColor.range)
        val nested = assertIs<MathGroup>(colored.body.children[3])
        val nestedColor = assertIs<MathColorDeclaration>(nested.body.children.first())
        assertEquals(MathPaintColor(0, 0, 255), nestedColor.color)

        val boxed = assertIs<MathBoxed>(result.root.children.last())
        assertEquals(SourceRange(source.indexOf("\\boxed"), source.indexOf("\\boxed") + 6), boxed.commandRange)
        assertEquals(SourceRange(source.indexOf("\\boxed"), source.length), boxed.range)
        assertIs<MathFraction>(assertIs<MathGroup>(boxed.body).body.children.single())
    }

    @Test
    fun unknownColorRecoversWithoutDroppingFollowingAtoms() {
        val source = "\\color{not-a-color}x+y"
        val result = MathParser().parse(source)

        val diagnostic = result.diagnostics.single { it.code == DiagnosticCode.UnknownColorName }
        assertEquals(SourceRange(7, 18), diagnostic.range)
        assertIs<MathErrorNode>(result.root.children.first())
        assertEquals(listOf("x", "+", "y"), result.root.children.drop(1).map { assertIs<MathSymbol>(it).sourceText })
    }

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
    fun mathopKeepsArbitraryNucleusAndSharesPostfixLimitsSemantics() {
        val source = "\\mathop{abc}_0^1+\\mathop{x+y}\\nolimits_z"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val scripted = result.root.children.filterIsInstance<MathScripts>()
        val automatic = assertIs<MathOperatorNoad>(scripted[0].base)
        assertEquals(MathLimitsPolicy.Auto, automatic.limitsPolicy)
        assertEquals(SourceRange(0, 7), automatic.commandRange)
        assertIs<MathGroup>(automatic.nucleus)
        assertEquals(MathAtomClass.Operator, automatic.atomClass)

        val explicit = assertIs<MathOperatorNoad>(scripted[1].base)
        assertEquals(MathLimitsPolicy.NoLimits, explicit.limitsPolicy)
        assertEquals(SourceRange(source.indexOf("\\nolimits"), source.indexOf("\\nolimits") + 9), explicit.limitsModifierRange)
        assertEquals(source.indexOf("+\\mathop") + 1, explicit.commandRange.start)
    }

    @Test
    fun displayAndContinuedFractionsRetainStyleAlignmentAndSourceSemantics() {
        val source = "\\dfrac{a}{b}+\\cfrac[l]{a}{bbbb}+\\cfrac[r]{x}{y}"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val fractions = result.root.children.filterIsInstance<MathFraction>()
        assertEquals(3, fractions.size)
        assertEquals(MathFractionOrigin.DisplayFraction, fractions[0].origin)
        assertEquals(MathStyleLevel.Display, fractions[0].styleOverride)
        assertTrue(fractions[0].retainRightNullDelimiterSpace)

        assertEquals(MathFractionOrigin.ContinuedFraction, fractions[1].origin)
        assertEquals(MathFractionAlignment.Left, fractions[1].numeratorAlignment)
        assertTrue(fractions[1].numeratorStrut)
        assertTrue(!fractions[1].retainRightNullDelimiterSpace)
        assertEquals("[l]", source.substring(fractions[1].alignmentRange!!.start, fractions[1].alignmentRange!!.endExclusive))
        assertEquals(MathFractionAlignment.Right, fractions[2].numeratorAlignment)
        assertEquals("[r]", source.substring(fractions[2].alignmentRange!!.start, fractions[2].alignmentRange!!.endExclusive))
    }

    @Test
    fun malformedContinuedFractionAlignmentRecoversWithoutDroppingFollowingAtoms() {
        val invalid = MathParser().parse("\\cfrac[x]{a}{b}+y")
        assertTrue(invalid.diagnostics.any { it.code == DiagnosticCode.InvalidContinuedFractionAlignment })
        assertIs<MathFraction>(invalid.root.children.first())
        assertIs<MathSymbol>(invalid.root.children.last())

        val unclosed = MathParser().parse("\\cfrac[l{a}{b}+y")
        assertTrue(unclosed.diagnostics.any { it.code == DiagnosticCode.UnclosedContinuedFractionAlignment })
        assertTrue(unclosed.root.children.isNotEmpty())
    }

    @Test
    fun growingBraceCommandsAreLimitsOperatorsWithSourcePreservingAccents() {
        val source = "\\overbrace{a+b}^{n}+\\underbrace{x}\\nolimits_0"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val scripted = result.root.children.filterIsInstance<MathScripts>()
        val over = assertIs<MathBraceNoad>(scripted[0].base)
        assertEquals(MathBraceKind.Over, over.kind)
        assertEquals(MathLimitsPolicy.Limits, over.limitsPolicy)
        assertEquals("\\overbrace", source.substring(over.commandRange.start, over.commandRange.endExclusive))
        assertIs<MathGroup>(over.base)

        val under = assertIs<MathBraceNoad>(scripted[1].base)
        assertEquals(MathBraceKind.Under, under.kind)
        assertEquals(MathLimitsPolicy.NoLimits, under.limitsPolicy)
        assertEquals("\\underbrace", source.substring(under.commandRange.start, under.commandRange.endExclusive))
        assertEquals("\\nolimits", source.substring(under.limitsModifierRange!!.start, under.limitsModifierRange!!.endExclusive))
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
    fun logLikeFunctionNamesParseAsOperatorNoadsWithLimitsPolicy() {
        val sin = assertIs<MathOperatorName>(MathParser().parse("\\sin").root.children.single())
        assertEquals("sin", sin.name)
        assertEquals(MathLimitsPolicy.NoLimits, sin.limitsPolicy)
        assertEquals(MathAtomClass.Operator, sin.atomClass)

        val lim = assertIs<MathOperatorName>(MathParser().parse("\\lim").root.children.single())
        assertEquals(MathLimitsPolicy.Auto, lim.limitsPolicy)

        // \sin x parses the function then a separate variable, no diagnostics.
        val applied = MathParser().parse("\\sin x")
        assertTrue(applied.diagnostics.isEmpty(), applied.diagnostics.toString())
        assertIs<MathOperatorName>(applied.root.children.first())
        assertIs<MathSymbol>(applied.root.children.last())

        // Scripts attach to the function as their base: \lim_{n} is one scripted operator.
        val scripted = MathParser().parse("\\lim_{n}")
        val scripts = assertIs<MathScripts>(scripted.root.children.single())
        assertIs<MathOperatorName>(scripts.base)

        val explicit = MathParser().parse("\\lim\\nolimits_n+\\sin\\limits^2")
        assertTrue(explicit.diagnostics.isEmpty(), explicit.diagnostics.toString())
        val explicitScripts = explicit.root.children.filterIsInstance<MathScripts>()
        val noLimits = assertIs<MathOperatorName>(explicitScripts[0].base)
        assertEquals(MathLimitsPolicy.NoLimits, noLimits.limitsPolicy)
        assertEquals(SourceRange(4, 13), noLimits.limitsModifierRange)
        assertEquals(SourceRange(0, 4), noLimits.commandRange)
        assertEquals(SourceRange(0, 13), noLimits.range)
        val forcedLimits = assertIs<MathOperatorName>(explicitScripts[1].base)
        assertEquals(MathLimitsPolicy.Limits, forcedLimits.limitsPolicy)
        assertEquals(SourceRange(20, 27), forcedLimits.limitsModifierRange)
        assertEquals(SourceRange(16, 20), forcedLimits.commandRange)
    }

    @Test
    fun mathAlphabetCommandsSelectTheirAlphabetWithoutDiagnostics() {
        val cases = mapOf(
            "mathrm" to (MathFamily.Operators to MathAlphabet.Roman),
            "mathnormal" to (MathFamily.Letters to MathAlphabet.MathNormal),
            "mathbf" to (MathFamily.Operators to MathAlphabet.Bold),
            "mathit" to (MathFamily.Letters to MathAlphabet.Italic),
            "mathsf" to (MathFamily.Operators to MathAlphabet.SansSerif),
            "mathbb" to (MathFamily.Operators to MathAlphabet.DoubleStruck),
            "mathfrak" to (MathFamily.Operators to MathAlphabet.Fraktur),
            "mathcal" to (MathFamily.Operators to MathAlphabet.Script),
            "mathscr" to (MathFamily.Operators to MathAlphabet.Script),
            "mathtt" to (MathFamily.Operators to MathAlphabet.Monospace),
        )
        cases.forEach { (command, expected) ->
            val result = MathParser().parse("\\$command{x}")
            assertTrue(result.diagnostics.isEmpty(), "$command: ${result.diagnostics}")
            val scope = assertIs<MathAlphabetScope>(result.root.children.single(), command)
            assertEquals(expected.first, scope.family, command)
            assertEquals(expected.second, scope.alphabet, command)
        }
    }

    @Test
    fun boldsymbolParsesAsBoldMathVersionOverFixedAndOperatorAtoms() {
        val source = "\\boldsymbol{\\lambda+\\sum}"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val scope = assertIs<MathVersionScope>(result.root.children.single())
        assertEquals(MathVersion.Bold, scope.version)
        assertEquals(SourceRange(0, source.length), scope.range)
        val children = assertIs<MathGroup>(scope.body).body.children
        val lambda = assertIs<MathSymbol>(children[0])
        assertEquals(MathFamilyBinding.Fixed, lambda.familyBinding)
        assertEquals(MathNamedSymbol.Lambda, assertIs<MathSymbolIdentity.Named>(lambda.identity).symbol)
        assertEquals(MathNamedSymbol.Plus, assertIs<MathSymbolIdentity.Named>(assertIs<MathSymbol>(children[1]).identity).symbol)
        assertIs<MathOperator>(children[2])
    }

    @Test
    fun directExtendedUnicodeScalarsDecodeToBaseIdentityAndRequestedAlphabet() {
        listOf(
            "𝒜" to ('A' to MathAlphabet.Script),
            "ℛ" to ('R' to MathAlphabet.Script),
            "𝔤" to ('g' to MathAlphabet.Fraktur),
            "ℭ" to ('C' to MathAlphabet.Fraktur),
            "𝕩" to ('x' to MathAlphabet.DoubleStruck),
            "ℝ" to ('R' to MathAlphabet.DoubleStruck),
            "𝙰" to ('A' to MathAlphabet.Monospace),
            "𝟽" to ('7' to MathAlphabet.Monospace),
        ).forEach { (source, expected) ->
            val result = MathParser().parse(source)
            assertTrue(result.diagnostics.isEmpty(), "$source: ${result.diagnostics}")
            val symbol = assertIs<MathSymbol>(result.root.children.single())
            val expectedIdentity = if (expected.first.isDigit()) {
                MathSymbolIdentity.Digit(expected.first)
            } else {
                MathSymbolIdentity.LatinLetter(expected.first)
            }
            assertEquals(expectedIdentity, symbol.identity, source)
            assertEquals(expected.second, symbol.alphabet, source)
            assertEquals(source, symbol.sourceText, source)
            assertEquals(SourceRange(0, source.length), symbol.range, source)
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

    @Test
    fun realZhihuAlephKeepsTeXIdentityFamilyAndUtf16Ranges() {
        val source = "\\aleph_0"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val scripts = assertIs<MathScripts>(result.root.children.single())
        val aleph = assertIs<MathSymbol>(scripts.base)
        assertEquals(MathNamedSymbol.Aleph, assertIs<MathSymbolIdentity.Named>(aleph.identity).symbol)
        assertEquals(MathAtomClass.Ordinary, aleph.atomClass)
        assertEquals(MathFamily.Symbols, aleph.family)
        assertEquals(MathFamilyBinding.Fixed, aleph.familyBinding)
        assertEquals("\\aleph", aleph.sourceText)
        assertEquals(SourceRange(0, 6), aleph.range)
        assertEquals(SourceRange(7, 8), assertIs<MathSymbol>(scripts.subscript).range)
        assertEquals(SourceRange(0, 8), scripts.range)

        val explicit = assertIs<MathSymbol>(MathParser().parse("ℵ").root.children.single())
        assertEquals(aleph.identity, explicit.identity)
        assertEquals(MathFamily.Symbols, explicit.family)
        assertEquals(SourceRange(0, 1), explicit.range)

        val roman = assertIs<MathAlphabetScope>(MathParser().parse("\\mathrm{\\aleph x}").root.children.single())
        val romanSymbols = assertIs<MathGroup>(roman.body).body.children.filterIsInstance<MathSymbol>()
        assertEquals(MathFamilyBinding.Fixed, romanSymbols[0].familyBinding)
        assertEquals(MathFamily.Symbols, romanSymbols[0].family)
        assertEquals(MathFamilyBinding.Variable, romanSymbols[1].familyBinding)
    }

    @Test
    fun standardGreekCommandsUseExplicitPlainTexIdentities() {
        val lower = listOf(
            "alpha" to MathNamedSymbol.Alpha,
            "beta" to MathNamedSymbol.Beta,
            "gamma" to MathNamedSymbol.Gamma,
            "delta" to MathNamedSymbol.Delta,
            "epsilon" to MathNamedSymbol.Epsilon,
            "varepsilon" to MathNamedSymbol.Varepsilon,
            "zeta" to MathNamedSymbol.Zeta,
            "eta" to MathNamedSymbol.Eta,
            "theta" to MathNamedSymbol.Theta,
            "vartheta" to MathNamedSymbol.Vartheta,
            "iota" to MathNamedSymbol.Iota,
            "kappa" to MathNamedSymbol.Kappa,
            "lambda" to MathNamedSymbol.Lambda,
            "mu" to MathNamedSymbol.Mu,
            "nu" to MathNamedSymbol.Nu,
            "xi" to MathNamedSymbol.Xi,
            "omicron" to MathNamedSymbol.Omicron,
            "pi" to MathNamedSymbol.Pi,
            "varpi" to MathNamedSymbol.Varpi,
            "rho" to MathNamedSymbol.Rho,
            "varrho" to MathNamedSymbol.Varrho,
            "sigma" to MathNamedSymbol.Sigma,
            "varsigma" to MathNamedSymbol.Varsigma,
            "tau" to MathNamedSymbol.Tau,
            "upsilon" to MathNamedSymbol.Upsilon,
            "phi" to MathNamedSymbol.Phi,
            "varphi" to MathNamedSymbol.Varphi,
            "chi" to MathNamedSymbol.Chi,
            "psi" to MathNamedSymbol.Psi,
            "omega" to MathNamedSymbol.Omega,
        )
        lower.forEach { (command, identity) ->
            assertSymbolCommand(command, identity, MathAtomClass.Ordinary, MathFamily.Letters, MathFamilyBinding.Fixed)
        }

        listOf(
            "Gamma" to MathNamedSymbol.CapitalGamma,
            "Delta" to MathNamedSymbol.CapitalDelta,
            "Theta" to MathNamedSymbol.CapitalTheta,
            "Lambda" to MathNamedSymbol.CapitalLambda,
            "Xi" to MathNamedSymbol.CapitalXi,
            "Pi" to MathNamedSymbol.CapitalPi,
            "Sigma" to MathNamedSymbol.CapitalSigma,
            "Upsilon" to MathNamedSymbol.CapitalUpsilon,
            "Phi" to MathNamedSymbol.CapitalPhi,
            "Psi" to MathNamedSymbol.CapitalPsi,
            "Omega" to MathNamedSymbol.CapitalOmega,
        ).forEach { (command, identity) ->
            assertSymbolCommand(command, identity, MathAtomClass.Ordinary, MathFamily.Operators, MathFamilyBinding.Variable)
        }
    }

    @Test
    fun commonPlainTexSymbolsKeepAuditableAtomClassesAndFamilies() {
        listOf(
            SymbolCommandExpectation("aleph", MathNamedSymbol.Aleph, MathAtomClass.Ordinary, MathFamily.Symbols),
            SymbolCommandExpectation("forall", MathNamedSymbol.ForAll, MathAtomClass.Ordinary, MathFamily.Symbols),
            SymbolCommandExpectation("exists", MathNamedSymbol.Exists, MathAtomClass.Ordinary, MathFamily.Symbols),
            SymbolCommandExpectation("emptyset", MathNamedSymbol.EmptySet, MathAtomClass.Ordinary, MathFamily.Symbols),
            SymbolCommandExpectation("nabla", MathNamedSymbol.Nabla, MathAtomClass.Ordinary, MathFamily.Symbols),
            SymbolCommandExpectation("hbar", MathNamedSymbol.HBar, MathAtomClass.Ordinary, MathFamily.Letters),
            SymbolCommandExpectation("ell", MathNamedSymbol.ScriptSmallL, MathAtomClass.Ordinary, MathFamily.Letters),
            SymbolCommandExpectation("Re", MathNamedSymbol.RealPart, MathAtomClass.Ordinary, MathFamily.Symbols),
            SymbolCommandExpectation("cap", MathNamedSymbol.Intersection, MathAtomClass.Binary, MathFamily.Symbols),
            SymbolCommandExpectation("cup", MathNamedSymbol.Union, MathAtomClass.Binary, MathFamily.Symbols),
            SymbolCommandExpectation("setminus", MathNamedSymbol.SetMinus, MathAtomClass.Binary, MathFamily.Symbols),
            SymbolCommandExpectation("wedge", MathNamedSymbol.LogicalAnd, MathAtomClass.Binary, MathFamily.Symbols),
            SymbolCommandExpectation("oplus", MathNamedSymbol.CircledPlus, MathAtomClass.Binary, MathFamily.Symbols),
            SymbolCommandExpectation("notin", MathNamedSymbol.NotElementOf, MathAtomClass.Relation, MathFamily.Symbols),
            SymbolCommandExpectation("subseteq", MathNamedSymbol.SubsetOrEqual, MathAtomClass.Relation, MathFamily.Symbols),
            SymbolCommandExpectation("equiv", MathNamedSymbol.Equivalent, MathAtomClass.Relation, MathFamily.Symbols),
            SymbolCommandExpectation("perp", MathNamedSymbol.Perpendicular, MathAtomClass.Relation, MathFamily.Symbols),
            SymbolCommandExpectation("parallel", MathNamedSymbol.Parallel, MathAtomClass.Relation, MathFamily.Symbols),
            SymbolCommandExpectation("vdash", MathNamedSymbol.RightTack, MathAtomClass.Relation, MathFamily.Symbols),
            SymbolCommandExpectation("models", MathNamedSymbol.Models, MathAtomClass.Relation, MathFamily.Symbols),
            SymbolCommandExpectation("leftarrow", MathNamedSymbol.LeftArrow, MathAtomClass.Relation, MathFamily.Symbols),
            SymbolCommandExpectation("Leftrightarrow", MathNamedSymbol.DoubleLeftRightArrow, MathAtomClass.Relation, MathFamily.Symbols),
            SymbolCommandExpectation("mapsto", MathNamedSymbol.MapsTo, MathAtomClass.Relation, MathFamily.Symbols),
        ).forEach { expected ->
            assertSymbolCommand(
                expected.command,
                expected.identity,
                expected.atomClass,
                expected.family,
                MathFamilyBinding.Fixed,
            )
        }

        listOf("lnot", "land", "lor", "owns", "gets", "rightarrow").forEach { alias ->
            val result = MathParser().parse("\\$alias")
            assertTrue(result.diagnostics.isEmpty(), "$alias: ${result.diagnostics}")
            assertIs<MathSymbol>(result.root.children.single())
        }

        val unsupported = MathParser().parse("\\Bbbk+\\beth")
        assertEquals(2, unsupported.diagnostics.count { it.code == DiagnosticCode.UnknownCommand })
    }

    private fun assertSymbolCommand(
        command: String,
        identity: MathNamedSymbol,
        atomClass: MathAtomClass,
        family: MathFamily,
        binding: MathFamilyBinding,
    ) {
        val source = "\\$command"
        val result = MathParser().parse(source)
        assertTrue(result.diagnostics.isEmpty(), "$command: ${result.diagnostics}")
        val symbol = assertIs<MathSymbol>(result.root.children.single())
        assertEquals(identity, assertIs<MathSymbolIdentity.Named>(symbol.identity).symbol, command)
        assertEquals(atomClass, symbol.atomClass, command)
        assertEquals(family, symbol.family, command)
        assertEquals(binding, symbol.familyBinding, command)
        assertEquals(source, symbol.sourceText, command)
        assertEquals(SourceRange(0, source.length), symbol.range, command)
    }
}

private data class SymbolCommandExpectation(
    val command: String,
    val identity: MathNamedSymbol,
    val atomClass: MathAtomClass,
    val family: MathFamily,
)
