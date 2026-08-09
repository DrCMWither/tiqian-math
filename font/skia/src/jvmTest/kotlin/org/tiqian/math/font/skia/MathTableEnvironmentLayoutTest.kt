package org.tiqian.math.font.skia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.abs
import org.tiqian.math.core.MathConstructionPaintKind
import org.tiqian.math.core.MathMode
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

    private fun assertNear(expected: Float, actual: Float, message: String, tolerance: Float) {
        assertTrue(abs(expected - actual) <= tolerance, "$message expected=$expected actual=$actual")
    }

    private companion object {
        const val TEX_PT_TO_PX = 96f / 72.27f
        const val TECTONIC_DELIMITER_SHORTFALL_PX = 5f * TEX_PT_TO_PX
        const val TECTONIC_NULL_DELIMITER_SPACE_PX = 1.2f * TEX_PT_TO_PX
    }
}
