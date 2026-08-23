package org.tiqian.math.font.skia

import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeMathMlOracleTest {
    @Test
    fun reviewedSameFontChromeMathMlGeometryIsAnActiveOracle() {
        val rows = readOracle()
        val faces = mapOf(
            "Lete Sans Math" to LeteSansMath.load(),
            "STIX Two Math" to StixTwoMath.load(),
        )
        assertEquals(faces.keys, rows.map { it.font }.toSet())

        faces.forEach { (fontName, font) ->
            SkiaMathFontFace(font).use { face ->
                val engine = MathLayoutEngine(face)
                rows.filter { it.font == fontName }.forEach { oracle ->
                    when (oracle.case) {
                        "italic-upright" -> compareItalicUpright(engine, oracle)
                        else -> compareFormula(engine.layout(oracle.source, MathLayoutOptions(fontSizePx = 40f)), oracle)
                    }
                }
            }
        }
    }

    private fun compareFormula(actual: MathLayoutResult, oracle: OracleRow) {
        assertTrue(actual.diagnostics.isEmpty(), "$oracle: ${actual.diagnostics}")
        // Native MathML joins adjacent variables into one row, while XeTeX completes every math
        // noad with its own italic correction. Remove those named XeTeX boundaries when comparing
        // the shared glyph advances from the same font.
        val terminalCorrection = actual.fragments.lastOrNull()?.trailingItalicCorrectionPx ?: 0f
        val adjacencyExtras = when (oracle.case) {
            "fraction-adjacency" -> {
                val nullDelimiters = actual.decisions.single { it.name == "TeXFractionNullDelimiters" }
                actual.fragments.first().trailingItalicCorrectionPx +
                    nullDelimiters.details.getValue("leftSpacePx").toFloat() +
                    nullDelimiters.details.getValue("rightSpacePx").toFloat()
            }
            "variable-run" -> actual.fragments.dropLast(1).sumOf { it.trailingItalicCorrectionPx.toDouble() }.toFloat()
            else -> 0f
        }
        assertNearCssPixel(
            oracle.width,
            actual.box.width - terminalCorrection - adjacencyExtras,
            5.0f,
            "$oracle logical nucleus width",
        )
        // Chrome and Skia choose slightly different absolute script shifts; the reviewed
        // relative gaps and baselines below are correspondingly much tighter.
        assertNearCssPixel(oracle.bottom - oracle.top, actual.box.inkBounds.height, 6.5f, "$oracle vertical ink")
        when (oracle.case) {
            "variable-pair" -> {
                val x = actual.exactGlyphAt(actual.source.indexOf('x'))
                val plus = actual.exactGlyphAt(actual.source.indexOf('+'))
                val y = actual.exactGlyphAt(actual.source.indexOf('y'))
                val italicCorrection = actual.symbolDecisionAt(actual.source.indexOf('x'))
                    .details.getValue("italicCorrectionPx").toFloat()
                oracle.assertProbe("xRight", x.x + x.advance, 0.1f)
                assertNearCssPixel(
                    oracle.probes.getValue("plusLeft") - oracle.probes.getValue("xRight"),
                    plus.x - (x.x + x.advance) - italicCorrection,
                    0.25f,
                    "$oracle pre-operator TeX glue after removing named OpenType correction",
                )
                assertNearCssPixel(
                    oracle.probes.getValue("plusRight") - oracle.probes.getValue("plusLeft"),
                    plus.advance,
                    0.1f,
                    "$oracle upright operator advance",
                )
                assertNearCssPixel(
                    oracle.probes.getValue("yLeft") - oracle.probes.getValue("plusRight"),
                    y.x - (plus.x + plus.advance),
                    0.25f,
                    "$oracle post-operator TeX glue",
                )
            }
            "variable-run" -> {
                val a = actual.exactGlyphAt(actual.source.indexOf('a'))
                val b = actual.exactGlyphAt(actual.source.indexOf('b'))
                val c = actual.exactGlyphAt(actual.source.indexOf('c'))
                oracle.assertProbe("aRight", a.x + a.advance, 0.35f)
                assertNearCssPixel(
                    actual.fragments[0].trailingItalicCorrectionPx,
                    b.x - (a.x + a.advance),
                    0.05f,
                    "$oracle XeTeX a noad correction",
                )
                assertNearCssPixel(
                    oracle.probes.getValue("bRight") - oracle.probes.getValue("bLeft"),
                    b.advance,
                    0.35f,
                    "$oracle shared b glyph advance",
                )
                assertNearCssPixel(
                    actual.fragments[1].trailingItalicCorrectionPx,
                    c.x - (b.x + b.advance),
                    0.05f,
                    "$oracle XeTeX b noad correction",
                )
            }
            "paired-scripts" -> {
                val sup = actual.glyphAt(actual.source.indexOf('2'))
                val sub = actual.glyphAt(actual.source.indexOf('1'))
                val base = actual.exactGlyphAt(actual.source.indexOf('x'))
                val scriptDecision = actual.decisions.single { it.name == "OpenTypeMathScriptPlacement" }
                val italicCorrection = actual.symbolDecisionAt(actual.source.indexOf('x'))
                    .details.getValue("italicCorrectionPx").toFloat()
                val superscriptKern = scriptDecision.details.getValue("superscriptKernPx").toFloat()
                val actualGap = sub.inkBounds.top - sup.inkBounds.bottom
                val oracleGap = oracle.probes.getValue("subTop") - oracle.probes.getValue("supBottom")
                assertNearCssPixel(oracleGap, actualGap, 2.5f, "$oracle script ink gap")
                oracle.assertProbe("baseRight", base.x + base.advance, 0.1f)
                oracle.assertProbe("subLeft", sub.x, 1f)
                assertNearCssPixel(
                    italicCorrection + superscriptKern,
                    sup.x - (base.x + base.advance),
                    0.05f,
                    "$oracle superscript x offset consumes named final-glyph correction and MathKern",
                )
                assertNearCssPixel(
                    0f,
                    oracle.probes.getValue("supLeft") - oracle.probes.getValue("baseRight"),
                    0.1f,
                    "$oracle native MathML superscript starts at the base advance",
                )
                oracle.assertProbe("supBottom", sup.inkBounds.bottom, 1.1f)
                oracle.assertProbe("subTop", sub.inkBounds.top, 1.1f)
            }
            "fraction-adjacency" -> {
                assertTrue(actual.decisions.filter { it.name == "TeXMathAtomSpacing" }
                    .all { it.details["kind"] == "None" }, "$oracle fraction noad has no adjacent thin glue")
                val left = actual.exactGlyphAt(actual.source.indexOf('a'))
                val right = actual.exactGlyphAt(actual.source.lastIndexOf('d'))
                val leftCorrection = actual.fragments.first().trailingItalicCorrectionPx
                val nullDelimiters = actual.decisions.single { it.name == "TeXFractionNullDelimiters" }
                val nullDelimiterWidth = nullDelimiters.details.getValue("leftSpacePx").toFloat() +
                    nullDelimiters.details.getValue("rightSpacePx").toFloat()
                oracle.assertProbe("leftRight", left.x + left.advance, 0.1f)
                oracle.assertProbe("fracLeft", left.x + left.advance, 0.1f)
                oracle.assertProbe("fracRight", right.x - leftCorrection - nullDelimiterWidth, 2f)
                oracle.assertProbe("rightLeft", right.x - leftCorrection - nullDelimiterWidth, 2f)
            }
        }
    }

    private fun compareItalicUpright(engine: MathLayoutEngine, oracle: OracleRow) {
        val italic = engine.layout("x", MathLayoutOptions(fontSizePx = 40f))
        val upright = engine.layout("\\mathrm{x}", MathLayoutOptions(fontSizePx = 40f))
        assertNearCssPixel(
            oracle.probes.getValue("italicWidth"),
            italic.box.width - italic.fragments.single().trailingItalicCorrectionPx,
            1.0f,
            "$oracle italic x nucleus",
        )
        assertNearCssPixel(
            oracle.probes.getValue("uprightWidth"),
            upright.box.width - upright.fragments.single().trailingItalicCorrectionPx,
            1.0f,
            "$oracle upright x nucleus",
        )
        assertFalse(italic.box.glyphs.single().glyphId == upright.box.glyphs.single().glyphId, oracle.toString())
    }

    private fun readOracle(): List<OracleRow> =
        checkNotNull(javaClass.getResourceAsStream("/oracles/chrome-mathml-40px.tsv"))
            .bufferedReader()
            .useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
                    val columns = line.split('\t')
                    require(columns.size == 7) { "invalid Chrome MathML oracle row: $line" }
                    OracleRow(
                        font = columns[0],
                        case = columns[1],
                        source = columns[2],
                        width = columns[3].toFloat(),
                        top = columns[4].toFloat(),
                        bottom = columns[5].toFloat(),
                        probes = columns[6].split(';').associate { entry ->
                            val (key, value) = entry.split('=', limit = 2)
                            key to value.toFloat()
                        },
                    )
                }.toList()
            }
}

private data class OracleRow(
    val font: String,
    val case: String,
    val source: String,
    val width: Float,
    val top: Float,
    val bottom: Float,
    val probes: Map<String, Float>,
)

private fun MathLayoutResult.glyphAt(sourceOffset: Int) =
    box.glyphs.first { sourceOffset in it.sourceRange.start until it.sourceRange.endExclusive }

private fun MathLayoutResult.exactGlyphAt(sourceOffset: Int) =
    box.glyphs.first { it.sourceRange == SourceRange(sourceOffset, sourceOffset + 1) }

private fun MathLayoutResult.symbolDecisionAt(sourceOffset: Int) =
    decisions.single {
        it.name == "TeXMathSymbolResolution" &&
            it.range == SourceRange(sourceOffset, sourceOffset + 1)
    }

private fun OracleRow.assertProbe(name: String, actual: Float, tolerance: Float) {
    assertNearCssPixel(probes.getValue(name), actual, tolerance, "$this $name")
}

private fun assertNearCssPixel(expected: Float, actual: Float, tolerance: Float, message: String) {
    assertTrue(abs(expected - actual) <= tolerance, "$message: expected $expected ± $tolerance, got $actual")
}
