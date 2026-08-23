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
        assertIs<MathDisplayRows>(outside.root.children.single())
        val grouped = MathParser().parse("{a\\\\b}+c")
        assertTrue(grouped.diagnostics.any { it.code == DiagnosticCode.UnexpectedRowSeparator })
        assertTrue(grouped.root.children.any { it is MathSymbol && it.sourceText == "c" })
    }

    @Test
    fun documentDisplayEnvironmentsHaveASeparateWrapperInsteadOfAnInnerNoad() {
        listOf("align", "align*").forEach { environment ->
            val parsed = MathParser().parse("\\begin{$environment}a&=b\\\\c&=d\\end{$environment}")
            assertTrue(parsed.diagnostics.isEmpty(), "$environment ${parsed.diagnostics}")
            val display = assertIs<MathDisplayEnvironment>(parsed.root.children.single())
            assertTrue(display.kind.alignment)
            val table = assertIs<MathTable>(display.body)
            assertEquals(MathTableEnvironment.Aligned, table.environment)
            assertEquals(2, table.rows.size)
        }
        listOf("equation", "equation*").forEach { environment ->
            val parsed = MathParser().parse("\\begin{$environment}a=\\frac{b}{c}\\end{$environment}")
            assertTrue(parsed.diagnostics.isEmpty(), "$environment ${parsed.diagnostics}")
            val display = assertIs<MathDisplayEnvironment>(parsed.root.children.single())
            assertTrue(!display.kind.alignment)
            assertIs<MathList>(display.body)
        }
    }

    @Test
    fun smallAndGatheredMatricesRetainTheirEnvironmentSemantics() {
        val small = MathParser().parse("\\begin{smallmatrix}a&b\\\\c&d\\end{smallmatrix}")
        assertTrue(small.diagnostics.isEmpty(), small.diagnostics.toString())
        val smallTable = assertIs<MathTable>(small.root.children.single())
        assertEquals(MathTableEnvironment.SmallMatrix, smallTable.environment)
        assertEquals(listOf(2, 2), smallTable.rows.map { it.cells.size })

        val gathered = MathParser().parse("\\begin{gathered}a=b\\\\c=d\\end{gathered}")
        assertTrue(gathered.diagnostics.isEmpty(), gathered.diagnostics.toString())
        val gatheredTable = assertIs<MathTable>(gathered.root.children.single())
        assertEquals(MathTableEnvironment.Gathered, gatheredTable.environment)
        assertEquals(listOf(1, 1), gatheredTable.rows.map { it.cells.size })
    }

    @Test
    fun gatherDocumentEnvironmentsUseTheCenteredDisplayTableKernel() {
        listOf("gather", "gather*").forEach { environment ->
            val parsed = MathParser().parse("\\begin{$environment}a=b\\\\c=d\\end{$environment}")
            assertTrue(parsed.diagnostics.isEmpty(), "$environment ${parsed.diagnostics}")
            val display = assertIs<MathDisplayEnvironment>(parsed.root.children.single())
            assertTrue(display.kind.alignment)
            val table = assertIs<MathTable>(display.body)
            assertEquals(MathTableEnvironment.Gathered, table.environment)
            assertEquals(listOf(1, 1), table.rows.map { it.cells.size })
        }
    }

    @Test
    fun gatheredRejectsAlignmentTabsWithoutDroppingFollowingContent() {
        listOf("gathered", "gather", "gather*").forEach { environment ->
            val source = "\\begin{$environment}a&=b\\\\c=d\\end{$environment}"
            val parsed = MathParser().parse(source)
            val diagnostic = parsed.diagnostics.single { it.code == DiagnosticCode.UnexpectedAlignmentTab }
            assertEquals("&", source.substring(diagnostic.range.start, diagnostic.range.endExclusive))
            val table = when (val root = parsed.root.children.single()) {
                is MathTable -> root
                is MathDisplayEnvironment -> assertIs<MathTable>(root.body)
                else -> error("unexpected ${root::class.simpleName}")
            }
            assertEquals(listOf(2, 1), table.rows.map { it.cells.size })
            assertEquals("=b", source.substring(table.rows.first().cells.last().range.start, table.rows.first().cells.last().range.endExclusive))
            assertEquals("c=d", source.substring(table.rows.last().range.start, table.rows.last().range.endExclusive))
        }
    }

    @Test
    fun optionalRowSpacingRetainsDimensionAndExactRanges() {
        val source = "\\begin{aligned}a&=b\\\\[.2cm]c&=d\\end{aligned}"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val table = assertIs<MathTable>(parsed.root.children.single())
        val spacing = assertNotNull(table.rows.first().additionalSpacing)
        assertEquals(0.2f, spacing.value)
        assertEquals(MathTeXDimensionUnit.Centimeter, spacing.unit)
        assertEquals(".2cm", spacing.sourceText)
        assertEquals("[.2cm]", source.substring(spacing.range.start, spacing.range.endExclusive))
        assertEquals("\\\\", source.substring(
            table.rows.first().rowSeparatorRange!!.start,
            table.rows.first().rowSeparatorRange!!.endExclusive,
        ))
    }

    @Test
    fun malformedRowSpacingAndMisplacedDisplayWrapperRecoverWithoutLosingFollowingInput() {
        val malformed = MathParser().parse("\\begin{array}{c}a\\\\[2ex]b\\end{array}+x")
        assertTrue(malformed.diagnostics.any { it.code == DiagnosticCode.InvalidRowSpacing })
        assertTrue(malformed.root.children.any { it is MathSymbol && it.sourceText == "x" })

        val nested = MathParser().parse("{\\begin{equation}a=b\\end{equation}}")
        assertTrue(nested.diagnostics.any { it.code == DiagnosticCode.MisplacedDisplayEnvironment })
        val adjacent = MathParser().parse("x+\\begin{equation}a=b\\end{equation}")
        assertTrue(adjacent.diagnostics.any { it.code == DiagnosticCode.MisplacedDisplayEnvironment })
    }

    @Test
    fun topLevelRowSeparatorsBecomeSourceAwareMarkdownDisplayRows() {
        val source = "a=b\\\\[.2cm]c=\\frac{d}{e}\\\\"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val display = assertIs<MathDisplayRows>(parsed.root.children.single())
        assertEquals(2, display.rows.size)
        assertEquals("a=b", source.substring(display.rows[0].body.range.start, display.rows[0].body.range.endExclusive))
        assertEquals("c=\\frac{d}{e}", source.substring(
            display.rows[1].body.range.start,
            display.rows[1].body.range.endExclusive,
        ))
        assertEquals("\\\\", source.substring(
            display.rows[0].rowSeparatorRange!!.start,
            display.rows[0].rowSeparatorRange!!.endExclusive,
        ))
        assertEquals("[.2cm]", source.substring(
            display.rows[0].additionalSpacing!!.range.start,
            display.rows[0].additionalSpacing!!.range.endExclusive,
        ))
        assertEquals("\\\\", source.substring(
            display.rows[1].rowSeparatorRange!!.start,
            display.rows[1].rowSeparatorRange!!.endExclusive,
        ))
    }

    @Test
    fun trailingSeparatorDoesNotMisplaceAnOtherwiseCompleteDisplayEnvironment() {
        val source = "\\begin{equation*}a=b\\end{equation*}\\\\"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val display = assertIs<MathDisplayRows>(parsed.root.children.single())
        assertEquals(1, display.rows.size)
        assertIs<MathDisplayEnvironment>(display.rows.single().body.children.single())

        val mixed = MathParser().parse("\\begin{equation*}a=b\\end{equation*}\\\\c=d")
        assertTrue(mixed.diagnostics.any { it.code == DiagnosticCode.MisplacedDisplayEnvironment })
    }
}
