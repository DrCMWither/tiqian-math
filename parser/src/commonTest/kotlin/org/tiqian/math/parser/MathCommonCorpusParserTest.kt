package org.tiqian.math.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tiqian.math.core.*

class MathCommonCorpusParserTest {
    @Test
    fun explicitSpacesRemainSourceAwareMuNodes() {
        val source = "a\\,b\\:c\\;d\\ e\\quad f\\qquad g"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val spaces = parsed.root.children.filterIsInstance<MathExplicitSpace>()
        assertEquals(listOf(3f, 4f, 5f, 6f, 18f, 36f), spaces.map { it.mu })
        assertEquals(listOf("\\,", "\\:", "\\;", "\\ ", "\\quad", "\\qquad"), spaces.map { it.command })
        spaces.forEach { assertEquals(it.command, source.substring(it.range.start, it.range.endExclusive)) }
    }

    @Test
    fun commonArticleAliasesResolveToExplicitMathIdentities() {
        val parsed = MathParser().parse(
            "\\dots\\cdots\\vdots\\ddots\\prime\\implies\\iff\\geqslant" +
                "\\langle x\\rangle\\lbrack y\\rbrack\\therefore\\triangleq",
        )
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val symbols = parsed.root.children.filterIsInstance<MathSymbol>()
            .mapNotNull { (it.identity as? MathSymbolIdentity.Named)?.symbol }
        assertEquals(
            listOf(
                MathNamedSymbol.HorizontalEllipsis,
                MathNamedSymbol.CenteredEllipsis,
                MathNamedSymbol.VerticalEllipsis,
                MathNamedSymbol.DiagonalEllipsis,
                MathNamedSymbol.Prime,
                MathNamedSymbol.DoubleRightArrow,
                MathNamedSymbol.DoubleLeftRightArrow,
                MathNamedSymbol.GreaterThanOrSlantedEqual,
                MathNamedSymbol.LeftAngleBracket,
                MathNamedSymbol.RightAngleBracket,
                MathNamedSymbol.LeftBracket,
                MathNamedSymbol.RightBracket,
                MathNamedSymbol.Therefore,
                MathNamedSymbol.TriangleEqual,
            ),
            symbols,
        )
    }

    @Test
    fun narrowAccentsAndLegacyRomanAreSemanticDeclarations() {
        val parsed = MathParser().parse("\\tilde{x}+\\dot y+\\ddot{z}+{\\rm Pl}x")
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        assertEquals(
            listOf(MathAccentIdentity.Tilde, MathAccentIdentity.Dot, MathAccentIdentity.DoubleDot),
            parsed.root.children.filterIsInstance<MathAccent>().map { it.identity },
        )
        val group = assertIs<MathGroup>(parsed.root.children[6])
        val declaration = assertIs<MathAlphabetDeclaration>(group.body.children.first())
        assertEquals(MathAlphabet.Roman, declaration.alphabet)
        assertEquals(MathFamily.Operators, declaration.family)
    }

    @Test
    fun commonLargeOperatorsKeepRealOperatorNoadSemantics() {
        val parsed = MathParser().parse("\\bigcup_i A_i+\\bigcap_j B_j+\\bigotimes_k C_k")
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        assertEquals(
            listOf(
                MathLargeOperatorIdentity.BigUnion,
                MathLargeOperatorIdentity.BigIntersection,
                MathLargeOperatorIdentity.BigCircledTimes,
            ),
            parsed.root.children.filterIsInstance<MathScripts>()
                .mapNotNull { (it.base as? MathOperator)?.identity },
        )
    }
}
