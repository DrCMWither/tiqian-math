package org.tiqian.math.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tiqian.math.core.*

class MathOverUnderArrowParserTest {
    @Test
    fun extensibleArrowsRetainBothLabelsAndRemainScriptableRelations() {
        val source = "a\\xrightarrow[k-1]{p_k}_0^1b+\\xleftarrow{f}c"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val scripted = assertIs<MathScripts>(parsed.root.children[1])
        val right = assertIs<MathExtensibleArrow>(scripted.base)
        assertEquals(MathExtensibleArrowIdentity.Right, right.identity)
        assertEquals(MathAtomClass.Relation, right.atomClass)
        assertEquals("[k-1]", source.substring(right.belowRange!!.start, right.belowRange!!.endExclusive))
        assertEquals("\\xrightarrow", source.substring(right.commandRange.start, right.commandRange.endExclusive))
        assertEquals(SourceRange(1, source.indexOf("_0")), right.range)

        val left = assertIs<MathExtensibleArrow>(parsed.root.children[4])
        assertEquals(MathExtensibleArrowIdentity.Left, left.identity)
        assertEquals(null, left.below)
        assertEquals("{f}", source.substring(left.above.range.start, left.above.range.endExclusive))
    }

    @Test
    fun overUnderCommandsKeepAnnotationBaseAndBinRelClassSemanticsSeparate() {
        val source = "\\overset{a}{=}+\\overset{b}{x}+\\underset{i}{\\sim}+\\stackrel{def}{=}"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val stacks = parsed.root.children.filterIsInstance<MathOverUnder>()
        assertEquals(
            listOf(
                MathOverUnderKind.Overset,
                MathOverUnderKind.Overset,
                MathOverUnderKind.Underset,
                MathOverUnderKind.StackRel,
            ),
            stacks.map { it.kind },
        )
        assertEquals(
            listOf(
                MathAtomClass.Relation,
                MathAtomClass.Ordinary,
                MathAtomClass.Relation,
                MathAtomClass.Relation,
            ),
            stacks.map { it.atomClass },
        )
        stacks.forEach { stack ->
            assertTrue(stack.commandRange.start >= stack.range.start)
            assertTrue(stack.commandRange.endExclusive <= stack.range.endExclusive)
            assertTrue(stack.annotation.range.length > 0)
            assertTrue(stack.base.range.length > 0)
        }
    }

    @Test
    fun malformedArrowArgumentsProduceNamedDiagnosticsWithoutLosingFollowingInput() {
        val missing = MathParser().parse("\\xrightarrow")
        assertTrue(missing.diagnostics.any { it.code == DiagnosticCode.MissingExtensibleArrowLabel })
        assertTrue(missing.root.children.isNotEmpty())

        val unclosed = MathParser().parse("{\\xrightarrow[a}+x")
        assertTrue(unclosed.diagnostics.any { it.code == DiagnosticCode.UnclosedExtensibleArrowBelow })
        assertTrue(unclosed.root.children.isNotEmpty())
    }
}
