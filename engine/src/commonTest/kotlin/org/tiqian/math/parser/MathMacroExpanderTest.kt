package org.tiqian.math.parser

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MathMacroExpanderTest {
    @Test
    fun formulaRecursionLimitAlsoBoundsMacroExpansionDepth() {
        assertFailsWith<IllegalArgumentException> {
            MacroExpansionLimits(maximumDepth = 513)
        }
        assertFailsWith<IllegalArgumentException> {
            MacroExpansionLimits(maximumOutputTokens = 250_001)
        }
        val expander = MathMacroExpander(
            definitions = listOf(
                MathMacroDefinition("one", 0, "x"),
                MathMacroDefinition("two", 0, "\\one"),
            ),
        )
        val limits = MathResourceLimits.Default.copy(maximumRecursionDepth = 1)

        val exact = expander.expand(tokens("\\one"), limits)
        assertTrue(exact.diagnostics.isEmpty(), exact.diagnostics.toString())

        val exceeded = expander.expand(tokens("\\two"), limits)
        assertEquals(
            listOf(DiagnosticCode.MacroExpansionDepthExceeded),
            exceeded.diagnostics.map { it.code },
        )
    }

    @Test
    fun shrinkingReplacementIsMeasuredByFinalOutputTokens() {
        val expander = MathMacroExpander(
            definitions = listOf(
                MathMacroDefinition("empty", 0, ""),
                MathMacroDefinition("many", 0, "\\empty\\empty"),
            ),
            limits = MacroExpansionLimits(maximumOutputTokens = 1),
        )

        val result = expander.expand(tokens("\\many"))

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(listOf(MathTokenKind.End), result.tokens.map { it.kind })

        val formulaBounded = expander.expand(
            tokens("\\many"),
            MathResourceLimits.Default.copy(maximumTokenCount = 1),
        )
        assertTrue(formulaBounded.diagnostics.isEmpty(), formulaBounded.diagnostics.toString())
        assertEquals(listOf(MathTokenKind.End), formulaBounded.tokens.map { it.kind })

        val exactOutputThenContraction = expander.expand(tokens("x\\many"))
        assertTrue(
            exactOutputThenContraction.diagnostics.isEmpty(),
            exactOutputThenContraction.diagnostics.toString(),
        )
        assertEquals(
            listOf(MathTokenKind.Symbol, MathTokenKind.End),
            exactOutputThenContraction.tokens.map { it.kind },
        )
    }

    @Test
    fun realExpandedOutputStillStopsAtItsTokenBudget() {
        val expander = MathMacroExpander(
            definitions = listOf(MathMacroDefinition("two", 0, "xy")),
            limits = MacroExpansionLimits(maximumOutputTokens = 1),
        )

        val result = expander.expand(tokens("\\two"))

        assertEquals(listOf(DiagnosticCode.MacroExpansionBudgetExceeded), result.diagnostics.map { it.code })
        assertEquals(listOf(MathTokenKind.Symbol, MathTokenKind.End), result.tokens.map { it.kind })
    }

    @Test
    fun replacementSourceAboveTheFormulaDefaultIsNotSilentlyErased() {
        val replacement = "\\" + "a".repeat(MathResourceLimits.Default.maximumSourceLength)
        val expander = MathMacroExpander(
            definitions = listOf(MathMacroDefinition("huge", 0, replacement)),
        )
        val invocation = tokens("\\huge")

        val accepted = expander.expand(invocation)

        assertTrue(accepted.diagnostics.isEmpty(), accepted.diagnostics.toString())
        assertEquals(listOf(MathTokenKind.ControlWord, MathTokenKind.End), accepted.tokens.map { it.kind })
        assertEquals(replacement.drop(1), accepted.tokens.first().text)
        assertEquals(SourceRange(0, 5), accepted.tokens.first().range)
    }

    @Test
    fun replacementTokensAboveTheFormulaDefaultRetainTheirTail() {
        val repeatedEmptyCount = MathResourceLimits.Default.maximumTokenCount + 1
        val expander = MathMacroExpander(
            definitions = listOf(
                MathMacroDefinition("e", 0, ""),
                MathMacroDefinition("huge", 0, "\\e".repeat(repeatedEmptyCount) + "\\+"),
            ),
        )

        val result = expander.expand(tokens("\\huge"))

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(listOf(MathTokenKind.ControlSymbol, MathTokenKind.End), result.tokens.map { it.kind })
        assertEquals("+", result.tokens.first().text)
    }

    @Test
    fun replacementBeyondTheHardCompilationEnvelopeReportsItsInvocation() {
        val replacement = "\\" + "a".repeat(1_000_000)
        val expander = MathMacroExpander(
            definitions = listOf(MathMacroDefinition("huge", 0, replacement)),
        )

        val result = expander.expand(tokens("\\huge"))

        assertEquals(listOf(DiagnosticCode.SourceLengthLimitExceeded), result.diagnostics.map { it.code })
        assertEquals(SourceRange(0, 5), result.diagnostics.single().range)
        assertTrue(result.diagnostics.single().message.startsWith("Macro \\huge replacement:"))
        assertEquals(listOf(MathTokenKind.End), result.tokens.map { it.kind })
    }

    @Test
    fun publicExpansionUsesTheDefaultFormulaBudgetUnlessExplicitlyWidened() {
        val outputCount = MathResourceLimits.Default.maximumTokenCount + 1
        val expander = MathMacroExpander(
            definitions = listOf(MathMacroDefinition("huge", 0, "x".repeat(outputCount))),
            limits = MacroExpansionLimits(maximumOutputTokens = outputCount),
        )

        val defaultBounded = expander.expand(tokens("\\huge"))
        assertEquals(listOf(DiagnosticCode.TokenCountLimitExceeded), defaultBounded.diagnostics.map { it.code })
        assertEquals(MathResourceLimits.Default.maximumTokenCount + 1, defaultBounded.tokens.size)
        assertEquals(MathTokenKind.End, defaultBounded.tokens.last().kind)

        val widened = expander.expand(
            tokens("\\huge"),
            MathResourceLimits.Default.copy(maximumTokenCount = outputCount),
        )
        assertTrue(widened.diagnostics.isEmpty(), widened.diagnostics.toString())
        assertEquals(outputCount + 1, widened.tokens.size)
        assertEquals(MathTokenKind.End, widened.tokens.last().kind)
    }

    private fun tokens(source: String): List<MathToken> = MathTokenizer().tokenize(source).tokens
}
