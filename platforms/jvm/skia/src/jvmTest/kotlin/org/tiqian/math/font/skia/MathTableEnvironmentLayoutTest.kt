package org.tiqian.math.font.skia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.abs
import org.tiqian.math.core.MathConstructionPaintKind
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathStyle
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

class MathTableEnvironmentLayoutTest {
    @Test
    fun twoFontTablesUseCompletedBoxesAxisCenteringAndRealDelimiterConstructions() = withTableFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val aligned = engine.layout(
            "\\begin{aligned}a&=b\\\\c&=\\frac{d}{e}\\end{aligned}",
            tectonicTableOptions(),
        )
        assertTrue(aligned.diagnostics.isEmpty(), "$label ${aligned.diagnostics}")
        val decision = aligned.decisions.single { it.name == "TeXMathTable" }
        assertEquals("2", decision.details["rowCount"], label)
        assertEquals("2", decision.details["columnCount"], label)
        assertEquals("Right,Left", decision.details["columnAlignments"], label)
        assertEquals("UnbreakableTeXTableInnerNoad", decision.details["groupBreakPolicy"], label)
        assertTrue(aligned.box.ascent > 0f && aligned.box.descent > 0f, label)
        assertEquals(aligned.box.ascent, aligned.box.texCleanBoxMetrics.ascent, label)
        assertEquals(aligned.box.descent, aligned.box.texCleanBoxMetrics.descent, label)
        val oracle = if (label == "Lete") {
            TableOracle(79.911665f, 60.563859f, 42.641973f, 99.26756f, 51.056936f, 33.135064f)
        } else {
            TableOracle(80.199638f, 58.947786f, 42.434032f, 106.40352f, 49.414735f, 32.900955f)
        }
        assertNear(oracle.alignedWidth, aligned.box.width, "$label aligned Tectonic width", 0.05f)
        assertNear(oracle.alignedAscent, aligned.box.ascent, "$label aligned Tectonic height", 0.05f)
        assertNear(oracle.alignedDescent, aligned.box.descent, "$label aligned Tectonic depth", 0.05f)

        val matrix = engine.layout(
            "\\begin{pmatrix}a&b\\\\\\frac{c}{d}&\\sqrt{x}\\end{pmatrix}_0^1",
            tectonicTableOptions(),
        )
        val plainMatrix = engine.layout(
            "\\begin{pmatrix}a&b\\\\\\frac{c}{d}&\\sqrt{x}\\end{pmatrix}",
            tectonicTableOptions(),
        )
        assertNear(oracle.matrixWidth, plainMatrix.box.width, "$label pmatrix Tectonic width", 0.05f)
        assertNear(oracle.matrixAscent, plainMatrix.box.ascent, "$label pmatrix Tectonic height", 0.05f)
        assertNear(oracle.matrixDescent, plainMatrix.box.descent, "$label pmatrix Tectonic depth", 0.05f)
        assertTrue(matrix.diagnostics.isEmpty(), "$label ${matrix.diagnostics}")
        assertEquals(2, matrix.decisions.count { it.name == "TeXContentDrivenDelimiter" }, label)
        assertEquals(
            2,
            matrix.box.constructionPaintGroups.count { it.kind == MathConstructionPaintKind.Delimiter },
            label,
        )
        assertTrue(matrix.decisions.any { it.name == "OpenTypeMathScriptPlacement" }, label)
    }

    @Test
    fun cellStylesAndSourceMappedPlacementsSurviveNestedFormulae() = withTableFaces { label, face ->
        val source = "\\begin{cases}x&x>0\\\\\\frac{a}{b}&x\\le0\\end{cases}"
        val result = MathLayoutEngine(face).layout(source, tectonicTableOptions())
        assertTrue(result.diagnostics.isEmpty(), "$label ${result.diagnostics}")
        val table = result.decisions.single { it.name == "TeXMathTable" }
        assertEquals("Text", table.details["cellStyle"], label)
        assertEquals("Left,Left", table.details["columnAlignments"], label)
        assertEquals(2, result.decisions.count { it.name == "TeXContentDrivenDelimiter" }, label)
        assertTrue(result.decisions.any {
            it.name == "TeXContentDrivenDelimiter" &&
                it.details["identity"] == "invisible" &&
                it.details["nullDelimiterSpacePx"] == TECTONIC_NULL_DELIMITER_SPACE_PX.toString()
        }, label)
        assertFalse(result.box.glyphs.isEmpty(), label)
        val expected = if (label == "Lete") {
            floatArrayOf(143.885642f, 51.040943f, 33.119044f)
        } else {
            floatArrayOf(142.029629f, 52.114809f, 35.601029f)
        }
        assertNear(expected[0], result.box.width, "$label cases Tectonic width", 0.05f)
        assertNear(expected[1], result.box.ascent, "$label cases Tectonic height", 0.05f)
        assertNear(expected[2], result.box.descent, "$label cases Tectonic depth", 0.05f)
        result.box.glyphs.forEach { glyph ->
            assertTrue(glyph.sourceRange.start >= 0 && glyph.sourceRange.endExclusive <= source.length, label)
        }
    }

    @Test
    fun alignedEquationPairsUseZeroIntraPairAndTwoEmInterPairGap() = withTableFaces { label, face ->
        val result = MathLayoutEngine(face).layout(
            "\\begin{aligned}a&=b&c&=d\\end{aligned}",
            tectonicTableOptions(),
        )
        assertTrue(result.diagnostics.isEmpty(), "$label ${result.diagnostics}")
        val decision = result.decisions.single { it.name == "TeXMathTable" }
        val gaps = decision.details.getValue("columnGapsPx").split(',').map(String::toFloat)
        assertEquals(listOf(0f, 64f, 0f), gaps, label)
        assertEquals("ZeroWithinPairAndTwoEmBetweenEquationPairs", decision.details["interColumnPolicy"])
    }

    @Test
    fun singleRowInlineAlignedExportsItsRowBaselineInsteadOfTheTableAxis() = withTableFaces { label, face ->
        val source = "\\begin{aligned}L=\\int\\sqrt{1+(f'(x))^2}\\,dx\\end{aligned}"
        val engine = MathLayoutEngine(face)
        val inline = engine.layout(source, tectonicTableOptions().copy(mode = MathMode.Inline))
        assertTrue(inline.diagnostics.isEmpty(), "$label ${inline.diagnostics}")
        val lOffset = source.indexOf('L')
        val inlineL = inline.box.glyphs.single {
            lOffset >= it.sourceRange.start && lOffset < it.sourceRange.endExclusive
        }
        assertNear(0f, inlineL.baselineY, "$label inline aligned row baseline", 0.01f)
        val baseline = inline.decisions.single { it.name == "SingleRowInlineAlignmentBaseline" }
        assertTrue(abs(baseline.details.getValue("rowBaselineBeforePx").toFloat()) > 0.1f, label)

        val display = engine.layout(source, tectonicTableOptions())
        assertTrue(display.decisions.none { it.name == "SingleRowInlineAlignmentBaseline" }, label)
    }

    @Test
    fun explicitDisplayStyleInlineAlignedStillExportsItsRowBaseline() = withTableFaces { label, face ->
        // \displaystyle sets Display style on the inline line; it must not bypass the baseline fix.
        val source = "\\displaystyle\\begin{aligned}L=\\int\\sqrt{1+(f'(x))^2}\\,dx\\end{aligned}"
        val engine = MathLayoutEngine(face)
        val inline = engine.layout(source, tectonicTableOptions().copy(mode = MathMode.Inline))
        assertTrue(inline.diagnostics.isEmpty(), "$label ${inline.diagnostics}")
        val lOffset = source.indexOf('L')
        val inlineL = inline.box.glyphs.single {
            lOffset >= it.sourceRange.start && lOffset < it.sourceRange.endExclusive
        }
        assertNear(0f, inlineL.baselineY, "$label displaystyle inline aligned row baseline", 0.01f)
        assertTrue(inline.decisions.any { it.name == "SingleRowInlineAlignmentBaseline" }, label)
    }

    @Test
    fun optionalRowSpacingMatchesTheSameFontTectonicBoxTrace() = withTableFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val alignedBase = engine.layout(
            "\\begin{aligned}a&=b\\\\c&=d\\end{aligned}",
            tectonicTableOptions(),
        )
        val alignedExtra = engine.layout(
            "\\begin{aligned}a&=b\\\\[.2cm]c&=d\\end{aligned}",
            tectonicTableOptions(),
        )
        val arrayBase = engine.layout(
            "\\begin{array}{cc}a&b\\\\c&d\\end{array}",
            tectonicTableOptions(),
        )
        val arrayExtra = engine.layout(
            "\\begin{array}{cc}a&b\\\\[.2cm]c&d\\end{array}",
            tectonicTableOptions(),
        )
        listOf(alignedBase, alignedExtra, arrayBase, arrayExtra).forEach {
            assertTrue(it.diagnostics.isEmpty(), "$label ${it.diagnostics}")
        }
        val oracle = if (label == "Lete") {
            RowSpacingOracle(
                alignedBase = floatArrayOf(76.72362f, 44.76974f, 26.84785f),
                alignedExtra = floatArrayOf(76.72362f, 48.54921f, 30.62732f),
                arrayBase = floatArrayOf(63.59107f, 42.11304f, 24.19115f),
                arrayExtra = floatArrayOf(63.59107f, 45.89250f, 27.97062f),
            )
        } else {
            RowSpacingOracle(
                alignedBase = floatArrayOf(77.01161f, 43.07366f, 26.55991f),
                alignedExtra = floatArrayOf(77.01161f, 46.85313f, 30.33937f),
                arrayBase = floatArrayOf(62.75904f, 40.41696f, 23.90320f),
                arrayExtra = floatArrayOf(62.75904f, 44.19642f, 27.68267f),
            )
        }
        assertBox(oracle.alignedBase, alignedBase, "$label aligned base")
        assertBox(oracle.alignedExtra, alignedExtra, "$label aligned extra")
        assertBox(oracle.arrayBase, arrayBase, "$label array base")
        assertBox(oracle.arrayExtra, arrayExtra, "$label array extra")
        val resolved = alignedExtra.decisions.single { it.name == "TeXExplicitRowSpacing" }
        assertEquals(".2cm", resolved.details["sourceText"])
        assertNear(0.2f * 96f / 2.54f, resolved.details.getValue("resolvedPx").toFloat(), "$label cm", 0.001f)
        assertEquals("AmsmathExtraInterRowGlue", alignedExtra.decisions.single {
            it.name == "TeXMathTable"
        }.details["rowSpacingPolicy"])
        assertEquals("LaTeXArrayPreviousRowStrutDepthExtension", arrayExtra.decisions.single {
            it.name == "TeXMathTable"
        }.details["rowSpacingPolicy"])

        val explicitPx = 0.2f * 96f / 2.54f
        listOf(
            Triple(
                "aligned",
                """\begin{aligned}a&=b\\\end{aligned}""",
                """\begin{aligned}a&=b\\[.2cm]\end{aligned}""",
            ),
            Triple(
                "array",
                """\begin{array}{cc}a&b\\\end{array}""",
                """\begin{array}{cc}a&b\\[.2cm]\end{array}""",
            ),
        ).forEach { (environment, plainSource, trailingSource) ->
            val plain = engine.layout(plainSource, tectonicTableOptions())
            val trailing = engine.layout(trailingSource, tectonicTableOptions())
            assertNear(plain.box.width, trailing.box.width, "$label $environment trailing width", 0.001f)
            assertNear(explicitPx / 2f, trailing.box.ascent - plain.box.ascent, "$label $environment trailing ascent", 0.06f)
            assertNear(explicitPx / 2f, trailing.box.descent - plain.box.descent, "$label $environment trailing descent", 0.06f)
        }
    }

    @Test
    fun displayWrappersForceDisplayStyleWithoutBecomingInnerNoads() = withTableFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val equation = engine.layout(
            "\\begin{equation}a=\\frac{b}{c}\\end{equation}",
            tectonicTableOptions().copy(mode = MathMode.Inline),
        )
        val direct = engine.layout("\\displaystyle a=\\frac{b}{c}", tectonicTableOptions())
        assertTrue(equation.diagnostics.isEmpty(), "$label ${equation.diagnostics}")
        assertNear(direct.box.width, equation.box.width, "$label equation width", 0.001f)
        assertNear(direct.box.ascent, equation.box.ascent, "$label equation ascent", 0.001f)
        assertNear(direct.box.descent, equation.box.descent, "$label equation descent", 0.001f)
        val equationDecision = equation.decisions.single { it.name == "MarkdownMathDisplayEnvironment" }
        assertEquals("SingleDisplayEquation", equationDecision.details["layoutRole"])
        assertEquals("NoneAtDocumentLevel", equationDecision.details["atomClass"])

        val align = engine.layout(
            "\\begin{align*}a&=b\\\\c&=\\frac{d}{e}\\end{align*}",
            tectonicTableOptions().copy(mode = MathMode.Inline),
        )
        val aligned = engine.layout(
            "\\begin{aligned}a&=b\\\\c&=\\frac{d}{e}\\end{aligned}",
            tectonicTableOptions(),
        )
        assertTrue(align.diagnostics.isEmpty(), "$label ${align.diagnostics}")
        assertNear(aligned.box.width, align.box.width, "$label align width", 0.001f)
        assertNear(aligned.box.ascent, align.box.ascent, "$label align ascent", 0.001f)
        assertNear(aligned.box.descent, align.box.descent, "$label align descent", 0.001f)
        assertEquals("DisplayAlignment", align.decisions.single {
            it.name == "MarkdownMathDisplayEnvironment"
        }.details["layoutRole"])
    }

    @Test
    fun markdownDisplayRowsReuseTheAlignedRowKernelAndCenterEveryRow() = withTableFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val base = engine.layout("1\\\\22", tectonicTableOptions())
        val extra = engine.layout("1\\\\[.2cm]22", tectonicTableOptions())
        val trailing = engine.layout("1\\\\22\\\\", tectonicTableOptions())
        listOf(base, extra, trailing).forEach { result ->
            assertTrue(result.diagnostics.isEmpty(), "$label ${result.diagnostics}")
            val display = result.decisions.single { it.name == "MarkdownExplicitDisplayRows" }
            assertEquals("2", display.details["rowCount"], label)
            assertEquals("CenteredIndependentlyAtMaximumAdvance", display.details["rowAlignment"], label)
            assertEquals("MarkdownDisplayKaTeXCompatibilityExtension", display.details["dialect"], label)
            assertEquals("Center", result.decisions.single {
                it.name == "TeXMathTable" && it.details["environmentName"] == "markdown-display-rows"
            }.details["columnAlignments"], label)
        }
        val first = base.box.glyphs.single { it.sourceRange.start == 0 }
        val secondRow = base.box.glyphs.filter { it.sourceRange.start >= 3 }
        val firstLogicalCenter = first.x + first.advance / 2f
        val secondLogicalLeft = secondRow.minOf { it.x }
        val secondLogicalRight = secondRow.maxOf { it.x + it.advance }
        assertNear(base.box.width / 2f, firstLogicalCenter, "$label first row center", 0.001f)
        assertNear(base.box.width / 2f, (secondLogicalLeft + secondLogicalRight) / 2f, "$label second row center", 0.001f)
        val baseBaselineDelta = secondRow.first().baselineY - first.baselineY
        val extraBaselineDelta = extra.box.glyphs.single { it.sourceRange.start >= 10 }.baselineY -
            extra.box.glyphs.single { it.sourceRange.start == 0 }.baselineY
        assertNear(0.2f * 96f / 2.54f, extraBaselineDelta - baseBaselineDelta, "$label explicit row gap", 0.001f)
        assertNear(base.box.width, trailing.box.width, "$label trailing width", 0.001f)
        assertNear(base.box.ascent, trailing.box.ascent, "$label trailing ascent", 0.001f)
        assertNear(base.box.descent, trailing.box.descent, "$label trailing descent", 0.001f)
    }

    @Test
    fun topLevelDeclarationsCarryAcrossRowsButInlineModeFailsCapability() = withTableFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val source = "\\scriptstyle a\\\\b"
        val display = engine.layout(source, tectonicTableOptions())
        assertTrue(display.diagnostics.isEmpty(), "$label ${display.diagnostics}")
        assertEquals(listOf(MathStyle.Script, MathStyle.Script), display.box.glyphs.map { it.style }, label)
        assertEquals(
            "ContainingListDeclarationsCarryAcrossRows",
            display.decisions.single { it.name == "MarkdownExplicitDisplayRows" }
                .details["styleDeclarationPolicy"],
            label,
        )

        val inline = engine.layout("a\\\\b", tectonicTableOptions().copy(mode = MathMode.Inline))
        assertTrue(inline.diagnostics.any { it.code == DiagnosticCode.ExplicitMultilineRequiresDisplay }, label)
        assertTrue(inline.box.glyphs.isNotEmpty(), "$label low-level debug layout remains inspectable")
    }

    private inline fun withTableFaces(block: (String, SkiaMathFontFace) -> Unit) {
        listOf(
            "Lete" to { SkiaMathFontFace(LeteSansMath.load()) },
            "STIX" to { SkiaMathFontFace(StixTwoMath.load()) },
        ).forEach { (label, factory) -> factory().use { block(label, it) } }
    }

    private fun tectonicTableOptions() = MathLayoutOptions(
        mode = MathMode.Display,
        fontSizePx = 32f,
        nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
        arrayColumnSeparationPx = 6.64176f,
        delimiterShortfallPx = TECTONIC_DELIMITER_SHORTFALL_PX,
    )

    private data class TableOracle(
        val alignedWidth: Float,
        val alignedAscent: Float,
        val alignedDescent: Float,
        val matrixWidth: Float,
        val matrixAscent: Float,
        val matrixDescent: Float,
    )

    private data class RowSpacingOracle(
        val alignedBase: FloatArray,
        val alignedExtra: FloatArray,
        val arrayBase: FloatArray,
        val arrayExtra: FloatArray,
    )

    private fun assertBox(expected: FloatArray, actual: org.tiqian.math.core.MathLayoutResult, message: String) {
        assertNear(expected[0], actual.box.width, "$message width", 0.06f)
        assertNear(expected[1], actual.box.ascent, "$message ascent", 0.06f)
        assertNear(expected[2], actual.box.descent, "$message descent", 0.06f)
    }

    private fun assertNear(expected: Float, actual: Float, message: String, tolerance: Float) {
        assertTrue(abs(expected - actual) <= tolerance, "$message expected=$expected actual=$actual")
    }

    private companion object {
        const val TEX_PT_TO_PX = 96f / 72.27f
        const val TECTONIC_DELIMITER_SHORTFALL_PX = 5f * TEX_PT_TO_PX
        const val TECTONIC_NULL_DELIMITER_SPACE_PX = 1.2f * TEX_PT_TO_PX
    }
}
