package org.tiqian.math.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tiqian.math.core.*

class MathEquationTagParserTest {
    @Test
    fun topLevelTagBecomesACompletedTaggedEquation() {
        val source = "x+y\\tag{1}"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val tagged = assertIs<MathTaggedEquation>(parsed.root.children.single())
        assertEquals("x+y", source.substring(tagged.body.range.start, tagged.body.range.endExclusive))
        assertEquals("1", tagged.tag.text)
        assertEquals("\\tag", source.substring(tagged.tag.commandRange.start, tagged.tag.commandRange.endExclusive))
        assertEquals("1", source.substring(tagged.tag.contentRange.start, tagged.tag.contentRange.endExclusive))
    }

    @Test
    fun equationAndAlignRowsOwnTheirTags() {
        val equationSource = "\\begin{equation}x\\tag*{A}\\end{equation}"
        val equation = assertIs<MathDisplayEnvironment>(
            MathParser().parse(equationSource).root.children.single(),
        )
        assertEquals("A", equation.tag?.text)
        assertEquals(true, equation.tag?.starred)

        val alignSource = "\\begin{align}a&=b\\tag{1}\\\\c&=d\\tag2\\end{align}"
        val parsed = MathParser().parse(alignSource)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val display = assertIs<MathDisplayEnvironment>(parsed.root.children.single())
        val table = assertIs<MathTable>(display.body)
        assertEquals(listOf("1", "2"), table.rows.map { it.tag?.text })
        assertTrue(table.rows.all { row -> row.cells.none { cell -> cell.body.children.any { it is MathEquationTag } } })

        val markdownRows = assertIs<MathDisplayRows>(MathParser().parse("a\\\\b\\tag{2}").root.children.single())
        assertEquals(listOf(null, "2"), markdownRows.rows.map { it.tag?.text })
    }

    @Test
    fun duplicateAndMissingTagsRecoverWithoutDroppingFollowingSource() {
        val duplicate = MathParser().parse("x\\tag{1}+y\\tag{2}+z")
        assertEquals(1, duplicate.diagnostics.count { it.code == DiagnosticCode.MultipleEquationTags })
        val tagged = assertIs<MathTaggedEquation>(duplicate.root.children.single())
        assertEquals("1", tagged.tag.text)
        assertTrue(tagged.body.children.isNotEmpty())

        val missing = MathParser().parse("x+\\tag")
        assertEquals(1, missing.diagnostics.count { it.code == DiagnosticCode.MissingEquationTagArgument })
        val missingTagged = assertIs<MathTaggedEquation>(missing.root.children.single())
        assertTrue(missingTagged.body.children.isNotEmpty())
    }

    @Test
    fun directTagInsideOutermostBoxIsPromotedToTheDisplayEquation() {
        val source = "\\boxed{x\\tag{2}}"
        val parsed = MathParser().parse(source)

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val tagged = assertIs<MathTaggedEquation>(parsed.root.children.single())
        val boxed = assertIs<MathBoxed>(tagged.body.children.single())
        val group = assertIs<MathGroup>(boxed.body)
        assertEquals(listOf("x"), group.body.children.filterIsInstance<MathSymbol>().map { it.sourceText })
        assertTrue(group.body.children.none { it is MathEquationTag })
        assertEquals("2", tagged.tag.text)
        assertEquals("\\tag{2}", source.substring(tagged.tag.range.start, tagged.tag.range.endExclusive))
    }

    @Test
    fun tagsInDeeperMathListsRemainInvalidAndOnlyAnEmptyFinalBoxedRowIsAccepted() {
        val fraction = MathParser().parse("\\frac{x\\tag{2}}{y}")
        assertTrue(fraction.root.children.none { it is MathTaggedEquation })
        val numerator = assertIs<MathGroup>(assertIs<MathFraction>(fraction.root.children.single()).numerator)
        assertTrue(numerator.body.children.any { it is MathEquationTag })

        val boxedRow = MathParser().parse("\\boxed{x\\tag{2}\\\\}")
        assertTrue(boxedRow.diagnostics.isEmpty(), boxedRow.diagnostics.toString())
        val boxedTagged = assertIs<MathTaggedEquation>(boxedRow.root.children.single())
        val boxed = assertIs<MathBoxed>(boxedTagged.body.children.single())
        assertEquals(SourceRange(15, 17), boxed.terminalRowSeparator?.separatorRange)
        assertEquals(listOf("x"), assertIs<MathGroup>(boxed.body).body.children
            .filterIsInstance<MathSymbol>().map { it.sourceText })
        assertEquals("2", boxedTagged.tag.text)

        listOf("\\boxed{x\\\\}", "\\boxed{x\\\\y}").forEach { source ->
            val invalid = MathParser().parse(source)
            assertTrue(invalid.diagnostics.any { it.code == DiagnosticCode.UnexpectedRowSeparator }, source)
        }
    }
}
