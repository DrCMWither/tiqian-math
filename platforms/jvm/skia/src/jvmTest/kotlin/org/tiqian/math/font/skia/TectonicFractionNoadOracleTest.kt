package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathLayoutDecision
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathStyle
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/**
 * Reviewed Tectonic 0.17.0 / XeTeX box traces at 24bp, exactly 32 CSS pixels at 96dpi.
 * The reproducers under `preview/tectonic` load these repository OTFs directly. The fixed
 * delimiter targets come from LaTeX2e amsmath's XeTeX `genfrac` fallback because OpenType MATH
 * has no fraction `delim1`/`delim2` constants: 2.39/1/1.45/1.35 em for D/T/S/SS. Its delimiter
 * is selected in an inner text-style math box, while the target reference uses the outer style.
 */
class TectonicFractionNoadOracleTest {
    @Test
    fun barredFractionWidthsStylesRulesAndNullDelimitersMatchReviewedTrace() {
        fractionFonts().forEach { oracle ->
            oracle.face.use { face ->
                val engine = MathLayoutEngine(face)
                listOf(
                    FractionOracleCase(
                        mode = MathMode.Inline,
                        expectedWidthPx = oracle.inlineFractionWidthPx,
                        expectedGlyphIds = oracle.inlineFractionGlyphIds,
                        expectedGlyphSizePx = 22.4f,
                        expectedNumeratorShiftPx = oracle.inlineFractionNumeratorShiftPx,
                        expectedDenominatorShiftPx = oracle.inlineFractionDenominatorShiftPx,
                        expectedNumeratorGapPx = oracle.inlineFractionNumeratorGapPx,
                        expectedDenominatorGapPx = oracle.inlineFractionDenominatorGapPx,
                    ),
                    FractionOracleCase(
                        mode = MathMode.Display,
                        expectedWidthPx = oracle.displayFractionWidthPx,
                        expectedGlyphIds = oracle.displayFractionGlyphIds,
                        expectedGlyphSizePx = 32f,
                        expectedNumeratorShiftPx = oracle.displayFractionNumeratorShiftPx,
                        expectedDenominatorShiftPx = oracle.displayFractionDenominatorShiftPx,
                        expectedNumeratorGapPx = oracle.displayFractionNumeratorGapPx,
                        expectedDenominatorGapPx = oracle.displayFractionDenominatorGapPx,
                    ),
                ).forEach { case ->
                    val result = engine.layout(
                        "\\frac{a}{b}",
                        MathLayoutOptions(
                            case.mode,
                            FONT_SIZE_PX,
                            nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
                        ),
                    )
                    val stack = result.decisions.single { it.name == "OpenTypeMathFractionStack" }
                    val nulls = result.decisions.single { it.name == "TeXFractionNullDelimiters" }
                    val rule = result.box.rules.single()
                    val expectedAxisY = -face.mathFont.scaleDesignUnits(
                        face.mathFont.constants.axisHeight,
                        FONT_SIZE_PX,
                    )
                    val expectedThickness = face.mathFont.scaleDesignUnits(
                        face.mathFont.constants.fractionRuleThickness,
                        FONT_SIZE_PX,
                    )

                    assertNear(case.expectedWidthPx, result.box.width, "${oracle.label} ${case.mode} trace width")
                    assertEquals(case.expectedGlyphIds, result.box.glyphs.map { it.glyphId }, "${oracle.label} glyph IDs")
                    result.box.glyphs.forEach {
                        assertNear(case.expectedGlyphSizePx, it.fontSizePx, "${oracle.label} fraction child style size")
                    }
                    assertEquals(if (case.mode == MathMode.Display) "Display" else "Text", stack.details["style"])
                    assertNear(expectedAxisY - expectedThickness / 2f, rule.top, "${oracle.label} rule top")
                    assertNear(expectedAxisY + expectedThickness / 2f, rule.bottom, "${oracle.label} rule bottom")
                    assertNear(case.expectedNumeratorShiftPx, stack.float("numeratorShiftPx"), "${oracle.label} numerator shift")
                    assertNear(case.expectedDenominatorShiftPx, stack.float("denominatorShiftPx"), "${oracle.label} denominator shift")
                    assertNear(case.expectedNumeratorGapPx, stack.float("actualNumeratorGapPx"), "${oracle.label} numerator gap")
                    assertNear(case.expectedDenominatorGapPx, stack.float("actualDenominatorGapPx"), "${oracle.label} denominator gap")
                    assertNear(TECTONIC_NULL_DELIMITER_SPACE_PX, nulls.float("leftSpacePx"), "${oracle.label} left null")
                    assertNear(TECTONIC_NULL_DELIMITER_SPACE_PX, nulls.float("rightSpacePx"), "${oracle.label} right null")
                }
            }
        }
    }

    @Test
    fun binomialTargetsGlyphsAxisAndHorizontalBoxesMatchReviewedTrace() {
        fractionFonts().forEach { oracle ->
            oracle.face.use { face ->
                val engine = MathLayoutEngine(face)
                oracle.binomials.forEach { case ->
                    val result = engine.layout(
                        "\\binom{n}{k}",
                        MathLayoutOptions(
                            MathMode.Inline,
                            FONT_SIZE_PX,
                            initialStyle = case.style,
                            nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
                        ),
                    )
                    val delimiters = result.decisions.filter { it.name == "BinomialDelimiter" }
                    val left = delimiters.single { it.details["side"] == "left" }
                    val right = delimiters.single { it.details["side"] == "right" }
                    val packing = result.decisions.single { it.name == "TeXBinomialFractionNoadPacking" }
                    val stack = result.decisions.single { it.name == "OpenTypeMathFractionStack" }
                    val delimiterGlyphs = result.box.glyphs.filter { it.sourceRange.start == 0 && it.sourceRange.endExclusive == 12 }

                    assertNear(case.expectedWidthPx, result.box.width, "${oracle.label} ${case.style} trace width")
                    assertEquals(listOf(case.leftGlyphId, case.rightGlyphId), delimiterGlyphs.map { it.glyphId })
                    delimiterGlyphs.forEach {
                        assertNear(FONT_SIZE_PX, it.fontSizePx, "${oracle.label} delimiter is inner text-style")
                        assertEquals(MathStyle.Text, it.style)
                    }
                    delimiters.forEach { delimiter ->
                        assertEquals("LaTeX2eXeTeXGenfracFixedStyleTarget", delimiter.details["targetPolicy"])
                        assertEquals("false", delimiter.details["delimitedSubFormulaMinHeightUsed"])
                        assertEquals("false", delimiter.details["stackCoverageRequired"])
                        assertNear(case.targetEmFactor, delimiter.float("targetEmFactor"), "${oracle.label} target factor")
                        assertNear(case.targetPx, delimiter.float("targetPx"), "${oracle.label} target height")
                        assertNear(case.delimiterTopPx, delimiter.float("delimiterTopPx"), "${oracle.label} delimiter top")
                        assertNear(case.delimiterBottomPx, delimiter.float("delimiterBottomPx"), "${oracle.label} delimiter bottom")
                        assertNear(-oracle.textAxisPx, delimiter.float("axisY"), "${oracle.label} text axis")
                    }
                    assertNear(case.delimiterAdvancePx, packing.float("leftDelimiterAdvancePx"), "${oracle.label} left advance")
                    assertNear(case.delimiterAdvancePx, packing.float("stackX"), "${oracle.label} stack x")
                    assertNear(case.rightDelimiterX, packing.float("rightDelimiterX"), "${oracle.label} right x")
                    assertNear(case.expectedWidthPx, packing.float("totalAdvancePx"), "${oracle.label} packed advance")
                    assertNear(case.numeratorShiftPx, stack.float("numeratorShiftPx"), "${oracle.label} ruleless numerator shift")
                    assertNear(case.denominatorShiftPx, stack.float("denominatorShiftPx"), "${oracle.label} ruleless denominator shift")
                    assertNear(case.stackGapPx, stack.float("actualGapPx"), "${oracle.label} ruleless stack gap")
                    assertNear(-TECTONIC_NULL_DELIMITER_SPACE_PX, packing.float("leftCancellationKernPx"), "${oracle.label} left cancel")
                    assertNear(-TECTONIC_NULL_DELIMITER_SPACE_PX, packing.float("rightCancellationKernPx"), "${oracle.label} right cancel")
                }
            }
        }
    }

    @Test
    fun tallAndNestedTraceDisprovesContentCoverageAndKeepsBinomialAdvanceTeXBoxed() {
        fractionFonts().forEach { oracle ->
            oracle.face.use { face ->
                val engine = MathLayoutEngine(face)
                val tall = engine.layout(
                    TALL_BINOMIAL,
                    MathLayoutOptions(
                        MathMode.Inline,
                        FONT_SIZE_PX,
                        nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
                    ),
                )
                val simple = engine.layout(
                    "\\binom{n}{k}",
                    MathLayoutOptions(
                        MathMode.Inline,
                        FONT_SIZE_PX,
                        nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
                    ),
                )
                val tallDelimiters = tall.decisions.filter { it.name == "BinomialDelimiter" }
                val simpleDelimiters = simple.decisions.filter { it.name == "BinomialDelimiter" }

                assertNear(oracle.tallBinomialWidthPx, tall.box.width, "${oracle.label} tall trace width")
                assertEquals(
                    simpleDelimiters.map { it.details["construction"] },
                    tallDelimiters.map { it.details["construction"] },
                    "${oracle.label} content height does not choose the fraction delimiter",
                )
                assertEquals(
                    simpleDelimiters.map { it.details["baseGlyphId"] },
                    tallDelimiters.map { it.details["baseGlyphId"] },
                )
                assertTrue(tallDelimiters.all {
                    it.details["coversStackTop"] == "false" && it.details["coversStackBottom"] == "false"
                }, "${oracle.label} reviewed TeX trace leaves tall stack outside the fixed delimiters")
                assertFalse(tall.diagnostics.any { it.code == DiagnosticCode.MathVariantTooShort })

                val nested = engine.layout(
                    NESTED,
                    MathLayoutOptions(
                        MathMode.Inline,
                        FONT_SIZE_PX,
                        nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
                    ),
                )
                assertNear(oracle.nestedWidthPx, nested.box.width, "${oracle.label} nested trace width")
                assertEquals(4, nested.decisions.count { it.name == "BinomialDelimiter" })
                assertTrue(nested.decisions.filter { it.name == "BinomialDelimiter" }.all {
                    it.details["targetEmFactor"] == "1.45" && it.details["delimiterFontSizePx"] == "32.0"
                })
            }
        }
    }

    @Test
    fun realBinomialAdvanceIsInvariantUnderNullDelimiterParameterBecauseGenfracCancelsIt() {
        fractionFonts().forEach { oracle ->
            oracle.face.use { face ->
                val engine = MathLayoutEngine(face)
                val compact = engine.layout(
                    "\\binom{n}{k}",
                    MathLayoutOptions(fontSizePx = FONT_SIZE_PX, nullDelimiterSpacePx = 0.5f),
                )
                val wide = engine.layout(
                    "\\binom{n}{k}",
                    MathLayoutOptions(fontSizePx = FONT_SIZE_PX, nullDelimiterSpacePx = 8f),
                )
                assertNear(compact.box.width, wide.box.width, "${oracle.label} cancellation total")
                assertEquals(compact.box.glyphs.map { it.glyphId }, wide.box.glyphs.map { it.glyphId })
                compact.box.glyphs.zip(wide.box.glyphs).forEach { (left, right) ->
                    assertNear(left.x, right.x, "${oracle.label} cancellation glyph x")
                }
            }
        }
    }

    private fun fractionFonts(): List<FractionFontOracle> = listOf(
        FractionFontOracle(
            label = "Lete Sans Math",
            face = SkiaMathFontFace(LeteSansMath.load()),
            textAxisPx = 8.96f,
            inlineFractionWidthPx = 15.956058f,
            displayFractionWidthPx = 21.428032f,
            inlineFractionGlyphIds = listOf(2701u, 2702u),
            displayFractionGlyphIds = listOf(3628u, 3629u),
            inlineFractionNumeratorShiftPx = 14.4015f,
            inlineFractionDenominatorShiftPx = 15.3616f,
            inlineFractionNumeratorGapPx = 4.2279f,
            inlineFractionDenominatorGapPx = 6.7355f,
            displayFractionNumeratorShiftPx = 18.562f,
            displayFractionDenominatorShiftPx = 22.4024f,
            displayFractionNumeratorGapPx = 8.3211f,
            displayFractionDenominatorGapPx = 6.7554f,
            tallBinomialWidthPx = 41.542456f,
            nestedWidthPx = 60.724045f,
            binomials = listOf(
                BinomialOracleCase(MathStyle.Text, 1f, 32f, 37.036792f, 1835u, 1850u, 12.224f, 24.8128f, -27.360943f, 9.43907f, 14.4015f, 15.3616f, 13.232f),
                BinomialOracleCase(MathStyle.Display, 2.39f, 76.48f, 46.912013f, 1839u, 1854u, 14.56f, 32.35201f, -51.056936f, 33.135064f, 18.562f, 22.4024f, 17.4123f),
                BinomialOracleCase(MathStyle.Script, 1.45f, 32.48f, 35.131191f, 1835u, 1850u, 12.224f, 22.9072f, -27.360943f, 9.43907f, 10.0811f, 10.7532f, 7.8102f),
                BinomialOracleCase(MathStyle.ScriptScript, 1.35f, 23.76f, 30.843205f, 9u, 10u, 10.08f, 20.763203f, -23.744943f, 5.823057f, 8.008f, 8.536f, 3.52f),
            ),
        ),
        FractionFontOracle(
            label = "STIX Two Math",
            face = SkiaMathFontFace(StixTwoMath.load()),
            textAxisPx = 8.256f,
            inlineFractionWidthPx = 16.829649f,
            displayFractionWidthPx = 20.948032f,
            inlineFractionGlyphIds = listOf(4421u, 4422u),
            displayFractionGlyphIds = listOf(3326u, 3327u),
            inlineFractionNumeratorShiftPx = 18.722f,
            inlineFractionDenominatorShiftPx = 18.722f,
            inlineFractionNumeratorGapPx = 9.0636f,
            inlineFractionDenominatorGapPx = 9.1806f,
            displayFractionNumeratorShiftPx = 20.4822f,
            displayFractionDenominatorShiftPx = 20.4822f,
            displayFractionNumeratorGapPx = 10.7534f,
            displayFractionDenominatorGapPx = 5.0912f,
            tallBinomialWidthPx = 43.80165f,
            nestedWidthPx = 66.52085f,
            binomials = listOf(
                BinomialOracleCase(MathStyle.Text, 1f, 32f, 39.033597f, 1301u, 1313u, 12.191987f, 26.8416f, -27.232877f, 10.719123f, 15.0416f, 18.722f, 16.8292f),
                BinomialOracleCase(MathStyle.Display, 2.39f, 76.48f, 51.296027f, 1307u, 1319u, 16.608013f, 34.688004f, -50.240877f, 33.727123f, 20.4822f, 20.4822f, 18.0843f),
                BinomialOracleCase(MathStyle.Script, 1.45f, 32.48f, 37.073574f, 1301u, 1313u, 12.191987f, 24.881605f, -27.232877f, 10.719123f, 10.5291f, 13.1054f, 9.7129f),
                BinomialOracleCase(MathStyle.ScriptScript, 1.35f, 23.76f, 35.537587f, 1064u, 1065u, 11.424f, 24.113605f, -23.168877f, 6.655123f, 8.2729f, 10.2971f, 4.6483f),
            ),
        ),
    )

    private data class FractionFontOracle(
        val label: String,
        val face: SkiaMathFontFace,
        val textAxisPx: Float,
        val inlineFractionWidthPx: Float,
        val displayFractionWidthPx: Float,
        val inlineFractionGlyphIds: List<UShort>,
        val displayFractionGlyphIds: List<UShort>,
        val inlineFractionNumeratorShiftPx: Float,
        val inlineFractionDenominatorShiftPx: Float,
        val inlineFractionNumeratorGapPx: Float,
        val inlineFractionDenominatorGapPx: Float,
        val displayFractionNumeratorShiftPx: Float,
        val displayFractionDenominatorShiftPx: Float,
        val displayFractionNumeratorGapPx: Float,
        val displayFractionDenominatorGapPx: Float,
        val tallBinomialWidthPx: Float,
        val nestedWidthPx: Float,
        val binomials: List<BinomialOracleCase>,
    )

    private data class FractionOracleCase(
        val mode: MathMode,
        val expectedWidthPx: Float,
        val expectedGlyphIds: List<UShort>,
        val expectedGlyphSizePx: Float,
        val expectedNumeratorShiftPx: Float,
        val expectedDenominatorShiftPx: Float,
        val expectedNumeratorGapPx: Float,
        val expectedDenominatorGapPx: Float,
    )

    private data class BinomialOracleCase(
        val style: MathStyle,
        val targetEmFactor: Float,
        val targetPx: Float,
        val expectedWidthPx: Float,
        val leftGlyphId: UShort,
        val rightGlyphId: UShort,
        val delimiterAdvancePx: Float,
        val rightDelimiterX: Float,
        val delimiterTopPx: Float,
        val delimiterBottomPx: Float,
        val numeratorShiftPx: Float,
        val denominatorShiftPx: Float,
        val stackGapPx: Float,
    )

    private companion object {
        const val FONT_SIZE_PX = 32f
        const val TECTONIC_NULL_DELIMITER_SPACE_PX = 1.2f * 96f / 72.27f
        const val TALL_BINOMIAL = "\\binom{\\frac{\\frac{a}{b}}{c}}{\\frac{d}{\\frac{e}{f}}}"
        const val NESTED = "\\frac{\\binom{n}{k}}{\\binom{2n}{n-k}}"
    }
}

private fun MathLayoutDecision.float(key: String): Float = details.getValue(key).toFloat()

private fun assertNear(expected: Float, actual: Float, message: String) {
    assertTrue(abs(expected - actual) <= 0.06f, "$message: expected $expected, got $actual")
}
