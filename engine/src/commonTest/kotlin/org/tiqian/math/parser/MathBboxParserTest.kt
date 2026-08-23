package org.tiqian.math.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tiqian.math.core.*

class MathBboxParserTest {
    @Test
    fun parsesMathJaxPaddingBackgroundAndSafeBorderWithSourceRanges() {
        val source = "\\bbox [5px, #CAF, border: 1px solid purple]{x^2}"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val bbox = assertIs<MathBbox>(result.root.children.single())
        assertEquals("\\bbox", source.substring(bbox.commandRange.start, bbox.commandRange.endExclusive))
        assertEquals("[5px, #CAF, border: 1px solid purple]", bbox.optionsRange?.let {
            source.substring(it.start, it.endExclusive)
        })
        assertEquals(MathBboxDimension(5f, MathBboxDimensionUnit.Pixel, "5px", SourceRange(7, 10)), bbox.options.padding)
        assertEquals(MathPaintColor(204, 170, 255), bbox.options.background?.color)
        assertEquals(MathBboxBorderStyle.Solid, bbox.options.border?.style)
        assertEquals(1f, bbox.options.border?.width?.value)
        assertEquals(MathPaintColor(191, 0, 64), bbox.options.border?.color?.color)
        assertIs<MathScripts>(assertIs<MathGroup>(bbox.body).body.children.single())
    }

    @Test
    fun malformedOptionsRecoverWithoutDroppingFollowingMath() {
        val unclosed = MathParser().parse("\\bbox[5px{x}+y")
        assertTrue(unclosed.diagnostics.any { it.code == DiagnosticCode.UnclosedBboxOptions })
        assertIs<MathBbox>(unclosed.root.children.first())
        assertEquals(listOf("+", "y"), unclosed.root.children.drop(1).map { assertIs<MathSymbol>(it).sourceText })

        val markerAfterRecoveredBody = MathParser().parse("\\bbox[#CAF{x}+#")
        assertTrue(markerAfterRecoveredBody.diagnostics.any { it.code == DiagnosticCode.InvalidParameterMarker })

        val duplicate = MathParser().parse("\\bbox[1px,2px]{x}")
        assertTrue(duplicate.diagnostics.any { it.code == DiagnosticCode.DuplicateBboxOption })

        val unsupported = MathParser().parse("\\bbox[box-shadow: 1px]{x}")
        assertTrue(unsupported.diagnostics.any { it.code == DiagnosticCode.UnsupportedBboxStyle })
    }

    @Test
    fun borderWidthWithoutAStyleRetainsCssNoneSemantics() {
        val bbox = assertIs<MathBbox>(MathParser().parse("\\bbox[#CAF,20px,border:1px]{x}").root.children.single())
        assertEquals(MathBboxBorderStyle.None, bbox.options.border?.style)
        assertEquals(null, bbox.options.border?.color)
    }

    @Test
    fun commentBetweenCommandAndOptionsDoesNotExposeHexColorAsATeXParameter() {
        val source = "\\bbox% ignored\n[#CAF]{x}"
        val result = MathParser().parse(source)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val bbox = assertIs<MathBbox>(result.root.children.single())
        assertEquals(MathPaintColor(204, 170, 255), bbox.options.background?.color)
    }

    @Test
    fun bboxMayOwnARealCorpusDisplayEnvironmentWithoutRelaxingOtherGroups() {
        val source = "\\bbox[5px]{\\begin{align}a&=b\\\\c&=d\\end{align}}"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val bbox = assertIs<MathBbox>(parsed.root.children.single())
        assertIs<MathDisplayEnvironment>(assertIs<MathGroup>(bbox.body).body.children.single())

        val ordinaryGroup = MathParser().parse("{\\begin{align}a&=b\\end{align}}")
        assertTrue(ordinaryGroup.diagnostics.any { it.code == DiagnosticCode.MisplacedDisplayEnvironment })
    }
}
