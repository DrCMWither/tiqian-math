package org.tiqian.math.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tiqian.math.core.*

class MathRemainingTeXCommandsParserTest {
    @Test
    fun atopSplitsTheContainingMathListInsteadOfBehavingLikeAPrefixCommand() {
        val source = "{g_1;S=X\\cup Y \\atop i+j=n-2}"
        val parsed = MathParser().parse(source)

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val fraction = assertIs<MathFraction>(assertIs<MathGroup>(parsed.root.children.single()).body.children.single())
        assertEquals(MathFractionOrigin.GeneralizedAtop, fraction.origin)
        assertEquals(FractionKind.Ruleless, fraction.kind)
        assertEquals("\\atop", source.substring(fraction.commandRange.start, fraction.commandRange.endExclusive))
        assertEquals("g_1;S=X\\cup Y", source.substring(fraction.numerator.range.start, fraction.numerator.range.endExclusive))
        assertEquals("i+j=n-2", source.substring(fraction.denominator.range.start, fraction.denominator.range.endExclusive))
    }

    @Test
    fun chooseSplitsTheContainingMathListAndRequestsParenthesizedRulelessPacking() {
        val source = "{A-a\\choose i-j}"
        val parsed = MathParser().parse(source)

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val fraction = assertIs<MathFraction>(assertIs<MathGroup>(parsed.root.children.single()).body.children.single())
        assertEquals(MathFractionOrigin.GeneralizedChoose, fraction.origin)
        assertEquals(FractionKind.Ruleless, fraction.kind)
        assertTrue(fraction.hasParentheses)
        assertEquals("\\choose", source.substring(fraction.commandRange.start, fraction.commandRange.endExclusive))
        assertEquals("A-a", source.substring(fraction.numerator.range.start, fraction.numerator.range.endExclusive))
        assertEquals("i-j", source.substring(fraction.denominator.range.start, fraction.denominator.range.endExclusive))
    }

    @Test
    fun malformedGeneralizedFractionsAreExplicitAndRecoverable() {
        val missingNumerator = MathParser().parse("{\\atop b}")
        assertTrue(missingNumerator.diagnostics.any {
            it.code == DiagnosticCode.MissingGeneralizedFractionNumerator
        })
        val missingDenominator = MathParser().parse("{a\\atop}")
        assertTrue(missingDenominator.diagnostics.any {
            it.code == DiagnosticCode.MissingGeneralizedFractionDenominator
        })
        val ambiguous = MathParser().parse("{a\\atop b\\atop c}")
        assertTrue(ambiguous.diagnostics.any { it.code == DiagnosticCode.AmbiguousGeneralizedFraction })
        val mixed = MathParser().parse("{a\\choose b\\atop c}")
        assertTrue(mixed.diagnostics.any { it.code == DiagnosticCode.AmbiguousGeneralizedFraction })

        val missingChooseNumerator = MathParser().parse("{\\choose b}")
        assertTrue(missingChooseNumerator.diagnostics.any {
            it.code == DiagnosticCode.MissingGeneralizedFractionNumerator
        })
        val missingChooseDenominator = MathParser().parse("{a\\choose}")
        assertTrue(missingChooseDenominator.diagnostics.any {
            it.code == DiagnosticCode.MissingGeneralizedFractionDenominator
        })
    }

    @Test
    fun notAndCancelRetainTheirOwnCommandsAndMathBodies() {
        val source = "\\mu\\not\\equiv\\mu+\\cancel{x+1}"
        val parsed = MathParser().parse(source)

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val negation = assertIs<MathNegation>(parsed.root.children[1])
        assertEquals("\\not", source.substring(negation.commandRange.start, negation.commandRange.endExclusive))
        assertEquals(MathNamedSymbol.Equivalent, assertIs<MathSymbolIdentity.Named>(
            assertIs<MathSymbol>(negation.base).identity,
        ).symbol)
        val cancel = assertIs<MathCancel>(parsed.root.children[4])
        assertEquals("\\cancel", source.substring(cancel.commandRange.start, cancel.commandRange.endExclusive))
        assertIs<MathGroup>(cancel.body)
    }

    @Test
    fun textbfCarriesPerSegmentHostWeightAndBfRemainsAListDeclaration() {
        val text = assertIs<MathText>(MathParser().parse("\\textbf{1}").root.children.single())
        assertEquals(listOf(MathFontWeight.Bold), text.segments.map { it.requestedWeight })

        val nested = assertIs<MathText>(MathParser().parse("\\text{a\\textbf{b}c}").root.children.single())
        assertEquals(listOf(null, MathFontWeight.Bold, null), nested.segments.map { it.requestedWeight })

        val declaration = MathParser().parse("\\bf{0}+1")
        assertIs<MathVersionDeclaration>(declaration.root.children.first())
        assertIs<MathGroup>(declaration.root.children[1])
        assertTrue(declaration.diagnostics.isEmpty(), declaration.diagnostics.toString())
    }

    @Test
    fun hlineBelongsToAnExactArrayBoundaryAndIsRejectedElsewhere() {
        val source = "\\begin{array}{c}a\\\\\\hline b\\end{array}"
        val parsed = MathParser().parse(source)

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val table = assertIs<MathTable>(parsed.root.children.single())
        val rule = table.horizontalRules.single()
        assertEquals(1, rule.boundaryIndex)
        assertEquals("\\hline", source.substring(rule.commandRange.start, rule.commandRange.endExclusive))

        val misplaced = MathParser().parse("a+\\hline b")
        assertTrue(misplaced.diagnostics.any { it.code == DiagnosticCode.MisplacedHorizontalRule })
        assertIs<MathSymbol>(misplaced.root.children.last())
    }
}
