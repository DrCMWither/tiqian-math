package org.tiqian.math.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathDelimited
import org.tiqian.math.core.MathDelimiterIdentity
import org.tiqian.math.core.MathDelimiterSide
import org.tiqian.math.core.MathFraction
import org.tiqian.math.core.MathMiddleDelimiter
import org.tiqian.math.core.MathScripts
import org.tiqian.math.core.MathSymbol
import org.tiqian.math.core.SourceRange

class MathDelimiterParserTest {
    @Test
    fun parsesPairedMiddleInvisibleNestedAndScriptsWithoutRewritingSource() {
        val source = "\\left\\langle a\\middle|\\left.\\frac{b}{c}\\right]\\right\\rangle_0^1"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val scripts = assertIs<MathScripts>(result.root.children.single())
        val outer = assertIs<MathDelimited>(scripts.base)
        assertEquals(MathDelimiterIdentity.LeftAngleBracket, outer.left.identity)
        assertEquals(MathDelimiterIdentity.RightAngleBracket, outer.right.identity)
        assertEquals("\\langle", outer.left.sourceText)
        assertEquals(SourceRange(0, 12), outer.left.range)
        val middle = outer.body.children.filterIsInstance<MathMiddleDelimiter>().single()
        assertEquals(MathDelimiterSide.Middle, middle.delimiter.side)
        assertEquals(MathDelimiterIdentity.VerticalBar, middle.delimiter.identity)
        val nested = outer.body.children.filterIsInstance<MathDelimited>().single()
        assertEquals(MathDelimiterIdentity.Invisible, nested.left.identity)
        assertEquals(MathDelimiterIdentity.RightBracket, nested.right.identity)
        assertIs<MathFraction>(nested.body.children.single())
        assertEquals(SourceRange(0, source.length - 4), outer.range)
        assertEquals(SourceRange(0, source.length), scripts.range)
    }

    @Test
    fun coversTheSupportedDelimiterVocabularyAsSemanticIdentities() {
        val cases = mapOf(
            "\\left( x\\right)" to Pair(MathDelimiterIdentity.LeftParenthesis, MathDelimiterIdentity.RightParenthesis),
            "\\left[ x\\right]" to Pair(MathDelimiterIdentity.LeftBracket, MathDelimiterIdentity.RightBracket),
            "\\left\\{x\\right\\}" to Pair(MathDelimiterIdentity.LeftBrace, MathDelimiterIdentity.RightBrace),
            "\\left|x\\right\\vert" to Pair(MathDelimiterIdentity.VerticalBar, MathDelimiterIdentity.VerticalBar),
            "\\left\\|x\\right\\Vert" to Pair(MathDelimiterIdentity.DoubleVerticalBar, MathDelimiterIdentity.DoubleVerticalBar),
            "\\left/x\\right\\backslash" to Pair(MathDelimiterIdentity.Solidus, MathDelimiterIdentity.ReverseSolidus),
            "\\left\\lfloor x\\right\\rfloor" to Pair(MathDelimiterIdentity.LeftFloor, MathDelimiterIdentity.RightFloor),
            "\\left\\lceil x\\right\\rceil" to Pair(MathDelimiterIdentity.LeftCeiling, MathDelimiterIdentity.RightCeiling),
            "\\left\\uparrow x\\right\\Downarrow" to Pair(MathDelimiterIdentity.UpArrow, MathDelimiterIdentity.DoubleDownArrow),
            "\\left\\Updownarrow x\\right\\downarrow" to Pair(MathDelimiterIdentity.DoubleUpDownArrow, MathDelimiterIdentity.DownArrow),
        )
        cases.forEach { (source, expected) ->
            val result = MathParser().parse(source)
            assertTrue(result.diagnostics.isEmpty(), "$source: ${result.diagnostics}")
            val delimited = assertIs<MathDelimited>(result.root.children.single())
            assertEquals(expected.first, delimited.left.identity, source)
            assertEquals(expected.second, delimited.right.identity, source)
        }
    }

    @Test
    fun malformedCommandsRecoverWithoutDroppingFollowingAtoms() {
        val missingLeft = MathParser().parse("\\left\\right)x+1")
        assertTrue(missingLeft.diagnostics.any { it.code == DiagnosticCode.MissingDelimiterAfterLeft })
        assertTrue(missingLeft.root.children.flatMap { node ->
            if (node is MathDelimited) node.body.children else listOf(node)
        }.filterIsInstance<MathSymbol>().any { it.sourceText == "x" })

        val missingMiddle = MathParser().parse("\\left(a\\middle\\right)b")
        assertTrue(missingMiddle.diagnostics.any { it.code == DiagnosticCode.MissingDelimiterAfterMiddle })
        assertTrue(missingMiddle.root.children.any { it is MathSymbol && it.sourceText == "b" })

        val missingRight = MathParser().parse("a+\\left(b+c")
        assertTrue(missingRight.diagnostics.any { it.code == DiagnosticCode.MissingRightDelimiter })
        val group = assertIs<MathDelimited>(missingRight.root.children.last())
        assertEquals(MathDelimiterIdentity.Invisible, group.right.identity)

        val missingAfterRight = MathParser().parse("\\left(x\\right")
        assertTrue(missingAfterRight.diagnostics.any {
            it.code == DiagnosticCode.MissingDelimiterAfterRight &&
                it.range == SourceRange(7, 13)
        })

        val stray = MathParser().parse("a\\right)+b\\middle|c")
        assertTrue(stray.diagnostics.any { it.code == DiagnosticCode.UnexpectedRightDelimiter })
        assertTrue(stray.diagnostics.any { it.code == DiagnosticCode.MiddleOutsideDelimitedGroup })
        assertEquals(listOf("a", "+", "b", "c"), stray.root.children.filterIsInstance<MathSymbol>().map { it.sourceText })

        val unsupported = MathParser().parse("\\left\\foo x\\right)y")
        assertTrue(unsupported.diagnostics.any { it.code == DiagnosticCode.UnsupportedDelimiter })
        assertTrue(unsupported.root.children.any { it is MathSymbol && it.sourceText == "y" })
    }
}
