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
}
