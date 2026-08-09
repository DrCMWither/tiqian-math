package org.tiqian.math.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tiqian.math.core.*

class MathTableEnvironmentParserTest {
    @Test
    fun alignedRowsAndColumnsRetainStructuredRanges() {
        val source = "\\begin{aligned}a&=b\\\\c&=\\frac{d}{e}\\end{aligned}"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val table = assertIs<MathTable>(parsed.root.children.single())
        assertEquals(MathTableEnvironment.Aligned, table.environment)
        assertEquals(2, table.rows.size)
        assertEquals(listOf(2, 2), table.rows.map { it.cells.size })
        assertEquals("aligned", source.substring(table.beginNameRange.start, table.beginNameRange.endExclusive))
        assertEquals("aligned", source.substring(table.endNameRange!!.start, table.endNameRange!!.endExclusive))
        assertNotNull(table.rows.first().cells.first().columnSeparatorRange)
        assertNotNull(table.rows.first().rowSeparatorRange)
        assertIs<MathFraction>(table.rows[1].cells[1].body.children.last())
    }

    @Test
    fun arraysCarryExplicitColumnAlignmentAndMatricesCarryFences() {
        val array = MathParser().parse("\\begin{array}{lcr}a&b&c\\end{array}")
        val table = assertIs<MathTable>(array.root.children.single())
        assertEquals(
            listOf(MathTableColumnAlignment.Left, MathTableColumnAlignment.Center, MathTableColumnAlignment.Right),
            table.columnAlignments,
        )
        assertTrue(array.diagnostics.isEmpty(), array.diagnostics.toString())

        val matrix = MathParser().parse("\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}")
        val pmatrix = assertIs<MathTable>(matrix.root.children.single())
        assertEquals(MathDelimiterIdentity.LeftParenthesis, pmatrix.environment?.leftDelimiter)
        assertEquals(MathDelimiterIdentity.RightParenthesis, pmatrix.environment?.rightDelimiter)
        assertTrue(matrix.diagnostics.isEmpty(), matrix.diagnostics.toString())
    }

    @Test
    fun nestedEnvironmentsAndScriptsRemainRealPrimaryNodes() {
        val source = "\\begin{pmatrix}\\frac{a}{b}&\\begin{matrix}x\\\\y\\end{matrix}\\end{pmatrix}_0^1"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val scripts = assertIs<MathScripts>(parsed.root.children.single())
        val outer = assertIs<MathTable>(scripts.base)
        assertIs<MathTable>(outer.rows.single().cells[1].body.children.single())
    }

    @Test
    fun malformedEnvironmentRecoveryRetainsFollowingFormula() {
        val missingEnd = MathParser().parse("\\begin{matrix}a&b")
        assertTrue(missingEnd.diagnostics.any { it.code == DiagnosticCode.MissingEnvironmentEnd })
        val mismatch = MathParser().parse("\\begin{matrix}a\\end{array}+x")
        assertTrue(mismatch.diagnostics.any { it.code == DiagnosticCode.MismatchedEnvironmentEnd })
        assertTrue(mismatch.root.children.any { it is MathSymbol && it.sourceText == "x" })
        val outside = MathParser().parse("a&b\\\\c")
        assertTrue(outside.diagnostics.any { it.code == DiagnosticCode.UnexpectedAlignmentTab })
        assertTrue(outside.diagnostics.any { it.code == DiagnosticCode.UnexpectedRowSeparator })
    }

    @Test
    fun documentDisplayEnvironmentsAreNotMisrepresentedAsMathListInnerNoads() {
        listOf("align", "align*", "equation", "equation*").forEach { environment ->
            val parsed = MathParser().parse("\\begin{$environment}a&=b\\end{$environment}")
            assertTrue(
                parsed.diagnostics.any { it.code == DiagnosticCode.UnsupportedEnvironment },
                "$environment must remain an explicit capability boundary",
            )
        }
    }
}
