package org.tiqian.math.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tiqian.math.core.*

class MathCommonCorpusParserTest {
    @Test
    fun explicitSpacesRemainSourceAwareMuNodes() {
        val source = "a\\!b\\,c\\:d\\;e\\ f\\quad g\\qquad h"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val spaces = parsed.root.children.filterIsInstance<MathExplicitSpace>()
        assertEquals(listOf(-3f, 3f, 4f, 5f, 6f, 18f, 36f), spaces.map { it.mu })
        assertEquals(listOf("\\!", "\\,", "\\:", "\\;", "\\ ", "\\quad", "\\qquad"), spaces.map { it.command })
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
        val parsed = MathParser().parse(
            "\\coprod_i A_i+\\bigwedge_j B_j+\\bigvee_k C_k+\\bigcap_l D_l+\\bigcup_m E_m+" +
                "\\bigodot_n F_n+\\bigoplus_p G_p+\\bigotimes_q H_q+\\biguplus_r I_r+\\smallint_0^1",
        )
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        assertEquals(
            listOf(
                MathLargeOperatorIdentity.Coproduct,
                MathLargeOperatorIdentity.BigLogicalAnd,
                MathLargeOperatorIdentity.BigLogicalOr,
                MathLargeOperatorIdentity.BigIntersection,
                MathLargeOperatorIdentity.BigUnion,
                MathLargeOperatorIdentity.BigCircledDot,
                MathLargeOperatorIdentity.BigCircledPlus,
                MathLargeOperatorIdentity.BigCircledTimes,
                MathLargeOperatorIdentity.BigDisjointUnion,
                MathLargeOperatorIdentity.SmallIntegral,
            ),
            parsed.root.children.filterIsInstance<MathScripts>()
                .mapNotNull { (it.base as? MathOperator)?.identity },
        )
    }

    @Test
    fun amsmathModuloSubstackAndAccentCommandsHaveStructuredSemantics() {
        val parsed = MathParser().parse(
            "a\\bmod b+a\\mod b+a\\pmod b+\\sum_{\\substack{i=1\\\\j=2}}^n+" +
                "\\acute{x}+\\grave{x}+\\breve{x}+\\check{x}+\\mathring{x}+" +
                "\\overrightarrow{AB}+\\underleftarrow{AB}",
        )
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        assertEquals(
            listOf(MathModuloKind.Binary, MathModuloKind.Plain, MathModuloKind.Parenthesized),
            parsed.root.children.filterIsInstance<MathModulo>().map { it.kind },
        )
        val sum = parsed.root.children.filterIsInstance<MathScripts>().single()
        val substackGroup = assertIs<MathGroup>(sum.subscript)
        val substack = assertIs<MathTable>(substackGroup.body.children.single())
        assertEquals(MathTableEnvironment.Substack, substack.environment)
        assertEquals(2, substack.rows.size)
        assertEquals(listOf("i=1", "j=2"), substack.rows.map { row ->
            row.cells.single().body.range.let { parsed.source.substring(it.start, it.endExclusive) }
        })
        assertEquals(
            listOf(
                MathAccentIdentity.Acute,
                MathAccentIdentity.Grave,
                MathAccentIdentity.Breve,
                MathAccentIdentity.Check,
                MathAccentIdentity.Ring,
                MathAccentIdentity.OverRightArrow,
                MathAccentIdentity.UnderLeftArrow,
            ),
            parsed.root.children.filterIsInstance<MathAccent>().map { it.identity },
        )
    }
}
