package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tiqian.math.core.MathLayoutDecision
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/**
 * Reviewed Tectonic 0.17.0 / XeTeX `showbox` traces at 24bp, exactly 32 CSS px
 * at 96dpi. The reproducers under `preview/tectonic` load the repository OTFs directly and
 * retain plain TeX's fixed 0.5pt `\scriptspace`.
 */
class TectonicOperatorSideScriptOracleTest {
    @Test
    fun integralContourAndForcedNolimitsMatchReviewedGlyphAndBoxGeometry() {
        operatorFonts().forEach { font ->
            font.face.use { face ->
                val engine = MathLayoutEngine(face)
                font.traces.filterNot { it.command.startsWith("sum") }.forEach { trace ->
                    listOf(
                        ScriptCase("^1", trace.upperOnly, upper = true, lower = false),
                        ScriptCase("_0", trace.lowerOnly, upper = false, lower = true),
                        ScriptCase("_0^1", trace.both, upper = true, lower = true),
                    ).forEach { scripts ->
                        val source = "\\${trace.command}${scripts.suffix}"
                        val result = engine.layout(source, options(trace.mode))
                        val placement = result.scriptDecision()
                        val upper = if (scripts.upper) {
                            result.box.glyphs.single { it.sourceRange == digitRange(source, '1') }
                        } else {
                            null
                        }
                        val lower = if (scripts.lower) {
                            result.box.glyphs.single { it.sourceRange == digitRange(source, '0') }
                        } else {
                            null
                        }
                        val label = "${font.label}/${trace.mode}/${trace.command}${scripts.suffix}"

                        assertNear(scripts.box.widthPt.px(), result.box.width, "$label width")
                        assertNear(scripts.box.ascentPt.px(), result.box.ascent, "$label height")
                        assertNear(scripts.box.descentPt.px(), result.box.descent, "$label depth")
                        assertNear(result.box.ascent, result.box.texCleanBoxMetrics.ascent, "$label clean height")
                        assertNear(result.box.descent, result.box.texCleanBoxMetrics.descent, "$label clean depth")

                        upper?.let {
                            assertNear(trace.upperXPt.px(), it.x, "$label upper x")
                            assertNear(trace.upperBaselinePt.px(), it.baselineY, "$label upper baseline")
                        }
                        lower?.let {
                            assertNear(trace.lowerXPt.px(), it.x, "$label lower x")
                            assertNear(trace.lowerBaselinePt.px(), it.baselineY, "$label lower baseline")
                        }

                        assertEquals("XeTeXOperatorNoLimits", placement.details["horizontalPlacementPolicy"], label)
                        assertEquals("ExplicitTeXScriptSpace", placement.details["spaceAfterScriptPolicy"], label)
                        assertNear(TECTONIC_SCRIPT_SPACE_PX, placement.float("spaceAfterScriptPx"), label)
                        val baseWidth = placement.float("baseOriginalLogicalWidthPx")
                        val delta = placement.float("italicCorrectionDeltaPx")
                        val expectedReduction = if (scripts.lower) delta else 0f
                        assertNear(expectedReduction, placement.float("operatorWidthReductionPx"), "$label make_op reduction")
                        assertNear(baseWidth - expectedReduction, placement.float("nucleusLogicalWidthPx"), "$label nucleus width")
                        upper?.let { assertNear(baseWidth, it.x, "$label upper replays original operator advance") }
                        lower?.let { assertNear(baseWidth - delta, it.x, "$label lower replays reduced nucleus advance") }
                    }
                }
            }
        }
    }

    @Test
    fun forcedNolimitsSumSharesTheReviewedMakeOpHorizontalContract() {
        operatorFonts().forEach { font ->
            font.face.use { face ->
                val engine = MathLayoutEngine(face)
                font.traces.filter { it.command.startsWith("sum") }.forEach { trace ->
                    listOf(
                        ScriptCase("^1", trace.upperOnly, upper = true, lower = false),
                        ScriptCase("_0", trace.lowerOnly, upper = false, lower = true),
                        ScriptCase("_0^1", trace.both, upper = true, lower = true),
                    ).forEach { scripts ->
                        val source = "\\${trace.command}${scripts.suffix}"
                        val result = engine.layout(source, options(trace.mode))
                        val placement = result.scriptDecision()
                        val label = "${font.label}/${trace.mode}/${trace.command}${scripts.suffix}"

                        assertNear(scripts.box.widthPt.px(), result.box.width, "$label width")
                        if (scripts.upper) {
                            val upper = result.box.glyphs.single { it.sourceRange == digitRange(source, '1') }
                            assertNear(trace.upperXPt.px(), upper.x, "$label upper x")
                        }
                        if (scripts.lower) {
                            val lower = result.box.glyphs.single { it.sourceRange == digitRange(source, '0') }
                            assertNear(trace.lowerXPt.px(), lower.x, "$label lower x")
                        }
                        assertEquals("XeTeXOperatorNoLimits", placement.details["horizontalPlacementPolicy"], label)
                        assertNear(0f, placement.float("italicCorrectionDeltaPx"), "$label zero delta")
                        assertNear(0f, placement.float("operatorWidthReductionPx"), "$label zero reduction")
                        assertEquals("Script", placement.details["superscriptBaselineDropStyle"])
                        assertEquals("ScriptCramped", placement.details["subscriptBaselineDropStyle"])
                    }
                }
            }
        }
    }

    @Test
    fun operatorBoxAdvancePlacesTheFollowingOrdinaryAtomAtTheReviewedTraceCoordinate() {
        operatorFonts().forEach { font ->
            font.face.use { face ->
                val engine = MathLayoutEngine(face)
                font.traces.filter { it.command == "int" }.forEach { trace ->
                    val source = "\\int_0^1x"
                    val result = engine.layout(source, options(trace.mode))
                    val following = result.box.glyphs.single { it.sourceRange == SourceRange(8, 9) }
                    val expectedX = (trace.both.widthPt + TECTONIC_THIN_MUSKIP_PT).px()

                    assertNear(expectedX, following.x, "${font.label}/${trace.mode} following x")
                    assertNear(trace.both.widthPt.px(), result.fragments.first().box.width, "${font.label} operator fragment width")
                }
            }
        }
    }

    @Test
    fun ordinaryAndCompoundNucleiRemainOnTheOrdinarySharedKernel() {
        operatorFonts().forEach { font ->
            font.face.use { face ->
                val engine = MathLayoutEngine(face)
                listOf(font.ordinary, font.compound).forEach { trace ->
                    val result = engine.layout(trace.source, options(MathMode.Inline))
                    val decision = result.scriptDecision()
                    val upper = result.box.glyphs.single { it.sourceRange == digitRange(trace.source, '1') }
                    val lower = result.box.glyphs.single { it.sourceRange == digitRange(trace.source, '0') }
                    val label = "${font.label}/${trace.label}"

                    assertEquals("OrdinaryNucleus", decision.details["horizontalPlacementPolicy"], label)
                    assertNear(0f, decision.float("operatorWidthReductionPx"), "$label no make_op reduction")
                    assertNear(trace.box.widthPt.px(), result.box.width, "$label width")
                    assertNear(trace.box.ascentPt.px(), result.box.ascent, "$label height")
                    assertNear(trace.box.descentPt.px(), result.box.descent, "$label depth")
                    assertNear(trace.upperXPt.px(), upper.x, "$label upper x")
                    assertNear(trace.lowerXPt.px(), lower.x, "$label lower x")
                    assertNear(trace.upperBaselinePt.px(), upper.baselineY, "$label upper baseline")
                    assertNear(trace.lowerBaselinePt.px(), lower.baselineY, "$label lower baseline")
                }
            }
        }
    }

    @Test
    fun explicitAndAutomaticStackedLimitsIgnoreTheSideScriptSpaceAndKernel() {
        operatorFonts().forEach { font ->
            font.face.use { face ->
                val engine = MathLayoutEngine(face)
                listOf(
                    "\\int\\limits_0^1" to MathMode.Inline,
                    "\\sum_0^1" to MathMode.Display,
                ).forEach { (source, mode) ->
                    val default = engine.layout(source, MathLayoutOptions(mode, FONT_SIZE_PX))
                    val tectonicSpace = engine.layout(source, options(mode))
                    val label = "${font.label}/$source"

                    assertTrue(default.decisions.any { it.name == "OpenTypeMathOperatorLimits" }, label)
                    assertTrue(default.decisions.none { it.name == "OpenTypeMathScriptPlacement" }, label)
                    assertNear(default.box.width, tectonicSpace.box.width, "$label width")
                    assertNear(default.box.ascent, tectonicSpace.box.ascent, "$label height")
                    assertNear(default.box.descent, tectonicSpace.box.descent, "$label depth")
                    assertEquals(
                        default.box.glyphs.map { Triple(it.x, it.baselineY, it.glyphId) },
                        tectonicSpace.box.glyphs.map { Triple(it.x, it.baselineY, it.glyphId) },
                        "$label paint geometry",
                    )
                }
            }
        }
    }

    private fun options(mode: MathMode): MathLayoutOptions = MathLayoutOptions(
        mode = mode,
        fontSizePx = FONT_SIZE_PX,
        scriptSpacePx = TECTONIC_SCRIPT_SPACE_PX,
    )

    private fun operatorFonts(): List<OperatorFontOracle> = listOf(
        OperatorFontOracle(
            label = "Lete Sans Math",
            face = SkiaMathFontFace(LeteSansMath.load()),
            traces = listOf(
                operatorTrace("int", MathMode.Inline, 14.21310f, 9.39511f, -13.34099f, 10.23790f,
                    BoxPt(24.49365f, 25.48236f, 6.86494f), BoxPt(19.67566f, 20.35675f, 10.37280f), BoxPt(24.49365f, 25.48236f, 10.37280f)),
                operatorTrace("oint", MathMode.Inline, 14.21310f, 9.39511f, -13.34099f, 10.23790f,
                    BoxPt(24.49365f, 25.48236f, 6.86494f), BoxPt(19.67566f, 20.35675f, 10.37280f), BoxPt(24.49365f, 25.48236f, 10.37280f)),
                operatorTrace("int", MathMode.Display, 23.56001f, 13.92401f, -26.63867f, 23.53558f,
                    BoxPt(33.84056f, 38.78004f, 20.16263f), BoxPt(24.20456f, 33.65443f, 23.67049f), BoxPt(33.84056f, 38.78004f, 23.67049f)),
                operatorTrace("oint", MathMode.Display, 23.56001f, 13.92401f, -26.63867f, 23.53558f,
                    BoxPt(33.84056f, 38.78004f, 20.16263f), BoxPt(24.20456f, 33.65443f, 23.67049f), BoxPt(33.84056f, 38.78004f, 23.67049f)),
                operatorTrace("sum\\nolimits", MathMode.Inline, 21.17511f, 21.17511f, -11.87150f, 8.76840f,
                    BoxPt(31.45566f, 24.01288f, 5.39545f), BoxPt(31.45566f, 18.88727f, 8.90330f), BoxPt(31.45566f, 24.01288f, 8.90330f)),
                operatorTrace("sum\\nolimits", MathMode.Display, 34.15962f, 34.15962f, -17.70128f, 14.59819f,
                    BoxPt(44.44017f, 29.84265f, 11.22523f), BoxPt(44.44017f, 24.71704f, 14.73310f), BoxPt(44.44017f, 29.84265f, 14.73310f)),
            ),
            ordinary = ScriptNucleusTrace("ordinary character", "x_0^1", BoxPt(24.03595f, 22.26025f, 6.35387f), 13.75540f, 11.00915f, -10.11888f, 6.21896f),
            compound = ScriptNucleusTrace("compound radical", "{\\sqrt{x}}_0^1", BoxPt(39.67036f, 28.99880f, 9.21718f), 29.38982f, 29.38982f, -16.85743f, 9.08228f),
        ),
        OperatorFontOracle(
            label = "STIX Two Math",
            face = SkiaMathFontFace(StixTwoMath.load()),
            traces = listOf(
                operatorTrace("int", MathMode.Inline, 16.47755f, 10.93686f, -13.81586f, 7.96136f,
                    BoxPt(25.56082f, 25.06348f, 5.26300f), BoxPt(21.21740f, 17.69476f, 8.18059f), BoxPt(25.56082f, 25.06348f, 8.18059f)),
                operatorTrace("oint", MathMode.Inline, 16.47755f, 10.93686f, -13.81586f, 7.96136f,
                    BoxPt(25.56082f, 25.06348f, 5.26300f), BoxPt(21.21740f, 17.69476f, 8.18059f), BoxPt(25.56082f, 25.06348f, 8.18059f)),
                operatorTrace("int", MathMode.Display, 24.86087f, 11.85226f, -30.59454f, 24.74005f,
                    BoxPt(33.94414f, 41.84216f, 22.04169f), BoxPt(22.13281f, 34.47345f, 24.95927f), BoxPt(33.94414f, 41.84216f, 24.95927f)),
                operatorTrace("oint", MathMode.Display, 24.25862f, 11.25002f, -30.59454f, 24.74005f,
                    BoxPt(33.34189f, 41.84216f, 22.04169f), BoxPt(21.53056f, 34.47345f, 24.95927f), BoxPt(33.34189f, 41.84216f, 24.95927f)),
                operatorTrace("sum\\nolimits", MathMode.Inline, 22.54823f, 22.54823f, -14.74332f, 8.88884f,
                    BoxPt(31.63150f, 25.99094f, 6.19048f), BoxPt(32.82878f, 18.62222f, 9.10806f), BoxPt(32.82878f, 25.99094f, 9.10806f)),
                operatorTrace("sum\\nolimits", MathMode.Display, 26.78807f, 26.78807f, -18.29659f, 12.44211f,
                    BoxPt(35.87134f, 29.54420f, 9.74374f), BoxPt(37.06862f, 22.17549f, 12.66133f), BoxPt(37.06862f, 29.54420f, 12.66133f)),
            ),
            ordinary = ScriptNucleusTrace("ordinary character", "x_0^1", BoxPt(23.74686f, 20.40182f, 5.95987f), 13.70721f, 13.46631f, -9.15420f, 5.74065f),
            compound = ScriptNucleusTrace("compound radical", "{\\sqrt{x}}_0^1", BoxPt(43.11522f, 30.79625f, 9.72300f), 32.83467f, 32.83467f, -19.54863f, 9.50378f),
        ),
    )

    private fun operatorTrace(
        command: String,
        mode: MathMode,
        upperXPt: Float,
        lowerXPt: Float,
        upperBaselinePt: Float,
        lowerBaselinePt: Float,
        upperOnly: BoxPt,
        lowerOnly: BoxPt,
        both: BoxPt,
    ) = OperatorTrace(command, mode, upperXPt, lowerXPt, upperBaselinePt, lowerBaselinePt, upperOnly, lowerOnly, both)

    private fun digitRange(source: String, digit: Char): SourceRange {
        val start = source.indexOf(digit)
        return SourceRange(start, start + 1)
    }

    private fun Float.px(): Float = this * TEX_PT_TO_PX

    private fun assertNear(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) <= EPSILON_PX, "$message: expected $expected, got $actual")
    }

    private fun org.tiqian.math.core.MathLayoutResult.scriptDecision(): MathLayoutDecision =
        assertNotNull(decisions.singleOrNull { it.name == "OpenTypeMathScriptPlacement" })

    private fun MathLayoutDecision.float(key: String): Float = details.getValue(key).toFloat()

    private companion object {
        const val FONT_SIZE_PX = 32f
        const val TEX_PT_TO_PX = 96f / 72.27f
        const val TECTONIC_SCRIPT_SPACE_PX = 0.5f * TEX_PT_TO_PX
        const val TECTONIC_THIN_MUSKIP_PT = 4.01541f
        const val EPSILON_PX = 0.25f
    }
}

private data class OperatorFontOracle(
    val label: String,
    val face: SkiaMathFontFace,
    val traces: List<OperatorTrace>,
    val ordinary: ScriptNucleusTrace,
    val compound: ScriptNucleusTrace,
)

private data class OperatorTrace(
    val command: String,
    val mode: MathMode,
    val upperXPt: Float,
    val lowerXPt: Float,
    val upperBaselinePt: Float,
    val lowerBaselinePt: Float,
    val upperOnly: BoxPt,
    val lowerOnly: BoxPt,
    val both: BoxPt,
)

private data class ScriptCase(
    val suffix: String,
    val box: BoxPt,
    val upper: Boolean,
    val lower: Boolean,
)

private data class ScriptNucleusTrace(
    val label: String,
    val source: String,
    val box: BoxPt,
    val upperXPt: Float,
    val lowerXPt: Float,
    val upperBaselinePt: Float,
    val lowerBaselinePt: Float,
)

private data class BoxPt(
    val widthPt: Float,
    val ascentPt: Float,
    val descentPt: Float,
)
