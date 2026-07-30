package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathStyle
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/**
 * Reviewed Tectonic 0.17.0/XeTeX `showbox` traces at 24bp (32 CSS px). The reproducers
 * `preview/tectonic/delimiter-noad-oracle-{lete,stix}.tex` load the repository OTFs directly.
 * Values below are the trace's TeX-point boxes converted with 96/72.27, not snapshots emitted
 * by this implementation.
 */
class TectonicDelimiterNoadOracleTest {
    @Test
    fun sameFontBoxesGlyphsPlacementsAndOuterSpacingMatchReviewedShowbox() {
        oracles().forEach { oracle ->
            oracle.face.use { face ->
                val engine = MathLayoutEngine(face)
                oracle.cases.forEach { case ->
                    val result = engine.layout(case.source, options(case))
                    assertEquals(
                        case.glyphIds,
                        result.tectonicTopToBottomGlyphIds(),
                        "${oracle.label}/${case.label} glyph IDs in XeTeX vbox paint order",
                    )
                    assertNear(case.widthPt * TEX_PT_TO_PX, result.box.width, "${oracle.label}/${case.label} width")
                    if (case.assertWholeFormulaVerticalBox) {
                        assertNear(case.ascentPt * TEX_PT_TO_PX, result.box.ascent, "${oracle.label}/${case.label} height")
                        assertNear(case.descentPt * TEX_PT_TO_PX, result.box.descent, "${oracle.label}/${case.label} depth")
                        assertNear(case.ascentPt * TEX_PT_TO_PX, result.box.texCleanBoxMetrics.ascent, "${oracle.label}/${case.label} clean height")
                        assertNear(case.descentPt * TEX_PT_TO_PX, result.box.texCleanBoxMetrics.descent, "${oracle.label}/${case.label} clean depth")
                    }
                    case.glyphXsPx.forEachIndexed { index, expected ->
                        assertNear(expected, result.box.glyphs[index].x, "${oracle.label}/${case.label} glyph[$index].x")
                    }
                    val decisions = result.decisions.filter { it.name == "TeXContentDrivenDelimiter" }
                    val delimiterGlyphs = result.box.glyphs.filter { it.constructionGroupId != null }
                    case.delimiterBaselinesPx?.let { baselines ->
                        assertEquals(baselines.size, decisions.sumOf {
                            it.details["glyphIds"].orEmpty().split(',').count(String::isNotBlank)
                        }, "${oracle.label}/${case.label} delimiter component count")
                        baselines.forEachIndexed { index, expected ->
                            assertNear(
                                expected,
                                delimiterGlyphs[index].baselineY,
                                "${oracle.label}/${case.label} delimiter component[$index] baseline",
                            )
                        }
                    }
                    assertTrue(result.diagnostics.isEmpty(), "${oracle.label}/${case.label}: ${result.diagnostics}")
                }
            }
        }
    }

    @Test
    fun makeLeftRightTargetIsComputedFromCompletedCleanBoxAndTeXParameters() {
        oracles().forEach { oracle ->
            oracle.face.use { face ->
                val result = MathLayoutEngine(face).layout("\\left(\\frac{a}{b}\\right)", options())
                val group = result.decisions.single { it.name == "TeXContentDrivenDelimitedGroup" }
                val cleanH = group.float("innerCleanAscentPx")
                val cleanD = group.float("innerCleanDescentPx")
                val axis = group.float("axisHeightPx")
                val radius = maxOf(cleanD + axis, cleanH - axis)
                val factorTarget = radius * 901f / 500f
                val shortfallTarget = 2f * radius - TECTONIC_DELIMITER_SHORTFALL_PX
                assertNear(factorTarget, group.float("factorTargetPx"), "${oracle.label} factor target")
                assertNear(shortfallTarget, group.float("shortfallTargetPx"), "${oracle.label} shortfall target")
                assertNear(maxOf(factorTarget, shortfallTarget), group.float("targetPx"), "${oracle.label} target")
                assertEquals("UnbreakableContentDrivenFencedInnerNoad", group.details["groupBreakPolicy"])
                assertEquals("false", group.details["internalBreaksExported"])
            }
        }
    }

    private fun options(case: Case? = null) = MathLayoutOptions(
        mode = case?.mode ?: MathMode.Inline,
        fontSizePx = 32f,
        initialStyle = case?.initialStyle,
        nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
        scriptSpacePx = TECTONIC_SCRIPT_SPACE_PX,
        delimiterFactor = 901,
        delimiterShortfallPx = TECTONIC_DELIMITER_SHORTFALL_PX,
    )

    private fun oracles() = listOf(
        DelimiterFontOracle(
            "Lete Sans Math",
            SkiaMathFontFace(LeteSansMath.load()),
            listOf(
                Case("normal", "\\left(x\\right)", 28.93210f, 17.87549f, 4.38367f, listOf(9u, 3650u, 10u), listOf(0f, 10.080002f, 28.352009f), listOf(0f, 0f)),
                Case("ordinary-b", "\\left(b\\right)", 28.90799f, 17.87549f, 4.38367f, listOf(9u, 3629u, 10u), emptyList(), listOf(0f, 0f)),
                Case("display-normal", "\\left(x\\right)", 28.93210f, 17.87549f, 4.38367f, listOf(9u, 3650u, 10u), emptyList(), listOf(0f, 0f), mode = MathMode.Display),
                Case("fraction", "\\left(\\frac{a}{b}\\right)", 31.28392f, 26.58403f, 13.09221f, listOf(1836u, 2701u, 2702u, 1851u), listOf(0f, 14.517222f, 14.394023f, 28.756046f), listOf(-0.016f, -0.016f)),
                Case("display-fraction", "\\left(\\frac{a}{b}\\right)", 36.31870f, 30.53479f, 17.04297f, listOf(1837u, 3628u, 3629u, 1852u), emptyList(), listOf(-0.016f, -0.016f), mode = MathMode.Display),
                Case("script-fraction", "\\left(\\frac{a}{b}\\right)", 23.95935f, 18.60884f, 9.16454f, listOf(1836u, 3201u, 3202u, 1851u), emptyList(), listOf(-0.0112f, -0.0112f), initialStyle = MathStyle.Script),
                Case("inline-assembly", INLINE_ASSEMBLY_SOURCE, 56.56593f, 116.01016f, 100.01834f, listOf(813u, 814u, 814u, 814u, 814u, 814u, 815u, 3224u, 3225u, 3225u, 3225u, 3225u, 3225u, 3225u, 3225u, 2725u, 816u, 817u, 817u, 817u, 817u, 817u, 818u), emptyList(), null),
                Case("radical-content", "\\left[\\sqrt{\\frac{a}{b}}\\right]", 46.77379f, 26.58403f, 13.09221f, listOf(1866u, 1790u, 2701u, 2702u, 1881u), emptyList(), null),
                Case("middle", MIDDLE, 54.19350f, 26.58403f, 13.09221f, listOf(2016u, 3628u, 1911u, 2702u, 2703u, 2031u), listOf(0f, 12.448f, 30.272f, 45.17802f, 45.95082f, 59.540047f), listOf(-0.016f, -0.016f, -0.016f)),
                Case("middle-style-reset", MIDDLE_STYLE_RESET, 44.35692f, 17.87549f, 4.38367f, listOf(9u, 2701u, 93u, 3629u, 10u), emptyList(), listOf(0f, 0f, 0f)),
                Case("middle-multi-reset-a", MIDDLE_MULTI_RESET_A, 62.44852f, 17.87549f, 4.38367f, listOf(9u, 2701u, 93u, 3629u, 93u, 3630u, 10u), emptyList(), listOf(0f, 0f, 0f, 0f)),
                Case("middle-multi-reset-b", MIDDLE_MULTI_RESET_B, 62.32086f, 17.87549f, 4.38367f, listOf(9u, 3628u, 93u, 2702u, 93u, 3630u, 10u), emptyList(), listOf(0f, 0f, 0f, 0f)),
                Case("invisible", "\\left.\\frac{a}{b}\\right|", 23.23335f, 26.58403f, 13.09221f, listOf(2701u, 2702u, 1911u), listOf(3.311245f, 3.188045f, 17.550068f), listOf(-0.016f)),
                Case("nested", "\\left(\\left[\\frac{a}{b}\\right]\\right)", 49.97777f, 26.58403f, 13.09221f, listOf(1836u, 1866u, 2701u, 2702u, 1881u, 1851u), emptyList(), null),
                Case("scripts", "\\left(\\frac{a}{b}\\right)_0^1", 41.56447f, 31.70964f, 16.60007f, listOf(1836u, 2701u, 2702u, 1851u, 2549u, 2548u), listOf(0f, 14.517222f, 14.394023f, 28.756046f, 41.556046f, 41.556046f), listOf(-0.016f, -0.016f)),
                Case("outer-spacing", "a\\left(b\\right)c", 62.42604f, 17.87549f, 4.38367f, listOf(3628u, 9u, 3629u, 10u, 3630u), listOf(0f, 23.157333f, 33.237335f, 51.477333f, 66.89067f), listOf(0f, 0f), assertWholeFormulaVerticalBox = false),
                Case("binary-spacing", "a+\\left(b\\right)+c", 108.81404f, 17.87549f, 4.38367f, listOf(3628u, 12u, 9u, 3629u, 10u, 12u, 3630u), emptyList(), listOf(0f, 0f), assertWholeFormulaVerticalBox = false),
                Case("relation-spacing", "a=\\left(b\\right)=c", 114.16792f, 17.87549f, 4.38367f, listOf(3628u, 30u, 9u, 3629u, 10u, 30u, 3630u), emptyList(), listOf(0f, 0f), assertWholeFormulaVerticalBox = false),
                Case("brace", "\\left\\{\\frac{a}{b}\\right\\}", 31.95842f, 26.57198f, 13.08017f, listOf(1896u, 2701u, 2702u, 1926u), emptyList(), null),
                Case("double-bar", "\\left\\|\\frac{a}{b}\\right\\Vert", 41.01628f, 26.58403f, 13.09221f, listOf(1941u, 2701u, 2702u, 1941u), emptyList(), null),
                Case("angle", "\\left\\langle\\frac{a}{b}\\right\\rangle", 30.75392f, 26.58403f, 13.09221f, listOf(2016u, 2701u, 2702u, 2031u), emptyList(), null),
                Case("floor", "\\left\\lfloor\\frac{a}{b}\\right\\rfloor", 30.12761f, 26.58403f, 13.09221f, listOf(1986u, 2701u, 2702u, 2001u), emptyList(), null),
                Case("ceiling", "\\left\\lceil\\frac{a}{b}\\right\\rceil", 30.12761f, 26.58403f, 13.09221f, listOf(1956u, 2701u, 2702u, 1971u), emptyList(), null),
                Case("slash", "\\left/\\frac{a}{b}\\right\\backslash", 51.18227f, 26.58403f, 13.09221f, listOf(2151u, 2701u, 2702u, 2166u), emptyList(), null),
                Case("arrow", "\\left\\uparrow\\frac{a}{b}\\right\\Downarrow", 46.70150f, 23.57278f, 11.68246f, listOf(2221u, 2225u, 2225u, 2225u, 2701u, 2702u, 2226u, 2226u, 2226u, 2230u), emptyList(), null),
            ),
        ),
        DelimiterFontOracle(
            "STIX Two Math",
            SkiaMathFontFace(StixTwoMath.load()),
            listOf(
                Case("normal", "\\left(x\\right)", 30.90747f, 17.44182f, 5.01006f, listOf(1064u, 3354u, 1065u), listOf(0f, 11.424f, 29.632f), listOf(0.384f, 0.384f)),
                Case("ordinary-b", "\\left(b\\right)", 29.79933f, 17.44182f, 5.01006f, listOf(1064u, 3327u, 1065u), emptyList(), listOf(0.384f, 0.384f)),
                Case("display-normal", "\\left(x\\right)", 30.90747f, 17.44182f, 5.01006f, listOf(1064u, 3354u, 1065u), emptyList(), listOf(0.384f, 0.384f), mode = MathMode.Display),
                Case("fraction", "\\left(\\frac{a}{b}\\right)", 33.24242f, 26.28285f, 14.31337f, listOf(1303u, 4421u, 4422u, 1315u), listOf(0f, 15.258022f, 15.4484215f, 30.493647f), listOf(0.384f, 0.384f)),
                Case("display-fraction", "\\left(\\frac{a}{b}\\right)", 36.34279f, 26.95834f, 15.70831f, listOf(1303u, 3326u, 3327u, 1315u), emptyList(), listOf(0.384f, 0.384f), mode = MathMode.Display),
                Case("script-fraction", "\\left(\\frac{a}{b}\\right)", 25.75766f, 18.39800f, 10.05139f, listOf(1303u, 4670u, 4671u, 1315u), emptyList(), listOf(0.2688f, 0.2688f), initialStyle = MathStyle.Script),
                Case("inline-assembly", INLINE_ASSEMBLY_SOURCE, 51.68771f, 130.97585f, 116.04408f, listOf(4860u, 4861u, 4861u, 4861u, 4861u, 4861u, 4861u, 4861u, 4862u, 4699u, 4700u, 4700u, 4700u, 4700u, 4700u, 4700u, 4700u, 4451u, 4863u, 4864u, 4864u, 4864u, 4864u, 4864u, 4864u, 4864u, 4865u), emptyList(), null),
                Case("radical-content", "\\left[\\sqrt{\\frac{a}{b}}\\right]", 58.41647f, 30.06192f, 16.74190f, listOf(1328u, 1658u, 4421u, 4422u, 1340u), emptyList(), null),
                Case("middle", MIDDLE, 52.61317f, 26.67395f, 14.33023f, listOf(2495u, 3326u, 1062u, 1062u, 4422u, 4423u, 2500u), listOf(0f, 13.568f, 31.328f, 31.328f, 41.46602f, 42.72042f, 56.320847f), listOf(0.384f, 9.291928f, -10.25193f, 0.384f)),
                Case("middle-style-reset", MIDDLE_STYLE_RESET, 46.50093f, 17.44182f, 5.01006f, listOf(1064u, 4421u, 1062u, 3327u, 1065u), emptyList(), listOf(0.384f, -0.4808f, 0.384f)),
                Case("middle-multi-reset-a", MIDDLE_MULTI_RESET_A, 62.90620f, 17.44182f, 5.01006f, listOf(1064u, 4421u, 1062u, 3327u, 1062u, 3328u, 1065u), emptyList(), listOf(0.384f, -0.4808f, -0.4808f, 0.384f)),
                Case("middle-multi-reset-b", MIDDLE_MULTI_RESET_B, 63.39040f, 17.44182f, 5.01006f, listOf(1064u, 3326u, 1062u, 4422u, 1062u, 3328u, 1065u), emptyList(), listOf(0.384f, -0.4808f, -0.4808f, 0.384f)),
                Case("invisible", "\\left.\\frac{a}{b}\\right|", 20.30159f, 24.70717f, 14.31337f, listOf(4421u, 4422u, 1062u, 1062u), listOf(3.188045f, 3.3784442f, 18.42367f, 18.42367f), listOf(9.271749f, -10.231747f)),
                Case("nested", "\\left(\\left[\\frac{a}{b}\\right]\\right)", 52.94804f, 26.28285f, 14.31337f, listOf(1303u, 1327u, 4421u, 4422u, 1339u, 1315u), emptyList(), null),
                Case("scripts", "\\left(\\frac{a}{b}\\right)_0^1", 43.52296f, 33.65157f, 17.23096f, listOf(1303u, 4421u, 4422u, 1315u, 4274u, 4273u), listOf(0f, 15.258022f, 15.4484215f, 30.493647f, 44.157646f, 44.157646f), listOf(0.384f, 0.384f)),
                Case("outer-spacing", "a\\left(b\\right)c", 61.17336f, 17.44182f, 5.01006f, listOf(3326u, 1064u, 3327u, 1065u, 3328u), listOf(0f, 23.093334f, 34.517334f, 51.253338f, 68.01067f), listOf(0.384f, 0.384f), assertWholeFormulaVerticalBox = false),
                Case("binary-spacing", "a+\\left(b\\right)+c", 109.24767f, 17.44182f, 5.01006f, listOf(3326u, 1196u, 1064u, 3327u, 1065u, 1196u, 3328u), emptyList(), listOf(0.384f, 0.384f), assertWholeFormulaVerticalBox = false),
                Case("relation-spacing", "a=\\left(b\\right)=c", 114.60155f, 17.44182f, 5.01006f, listOf(3326u, 1202u, 1064u, 3327u, 1065u, 1202u, 3328u), emptyList(), listOf(0.384f, 0.384f), assertWholeFormulaVerticalBox = false),
                Case("brace", "\\left\\{\\frac{a}{b}\\right\\}", 31.60430f, 26.29489f, 14.31337f, listOf(1351u, 4421u, 4422u, 1363u), emptyList(), null),
                Case("double-bar", "\\left\\|\\frac{a}{b}\\right\\Vert", 33.24242f, 27.31873f, 14.88696f, listOf(1063u, 1063u, 4421u, 4422u, 1063u, 1063u), emptyList(), null),
                Case("angle", "\\left\\langle\\frac{a}{b}\\right\\rangle", 33.09789f, 26.28285f, 14.31337f, listOf(2495u, 4421u, 4422u, 2500u), emptyList(), null),
                Case("floor", "\\left\\lfloor\\frac{a}{b}\\right\\rfloor", 32.37520f, 26.23466f, 14.31337f, listOf(2020u, 4421u, 4422u, 2025u), emptyList(), null),
                Case("ceiling", "\\left\\lceil\\frac{a}{b}\\right\\rceil", 32.37520f, 26.23466f, 14.31337f, listOf(2010u, 4421u, 4422u, 2015u), emptyList(), null),
                Case("slash", "\\left/\\frac{a}{b}\\right\\backslash", 52.41808f, 28.96889f, 16.53712f, listOf(1382u, 4421u, 4422u, 1386u), emptyList(), null),
                Case("arrow", "\\left\\uparrow\\frac{a}{b}\\right\\Downarrow", 42.61345f, 24.70717f, 14.31337f, listOf(1508u, 4822u, 4822u, 4421u, 4422u, 1578u, 1578u, 1578u, 1578u, 1580u), emptyList(), null),
            ),
        ),
    )

    private data class DelimiterFontOracle(
        val label: String,
        val face: SkiaMathFontFace,
        val cases: List<Case>,
    )

    private data class Case(
        val label: String,
        val source: String,
        val widthPt: Float,
        val ascentPt: Float,
        val descentPt: Float,
        val glyphIds: List<UShort>,
        val glyphXsPx: List<Float>,
        val delimiterBaselinesPx: List<Float>?,
        val assertWholeFormulaVerticalBox: Boolean = true,
        val mode: MathMode = MathMode.Inline,
        val initialStyle: MathStyle? = null,
    )

    private companion object {
        const val TEX_PT_TO_PX = 96f / 72.27f
        const val TECTONIC_NULL_DELIMITER_SPACE_PX = 1.2f * TEX_PT_TO_PX
        const val TECTONIC_SCRIPT_SPACE_PX = 0.5f * TEX_PT_TO_PX
        const val TECTONIC_DELIMITER_SHORTFALL_PX = 5f * TEX_PT_TO_PX
        const val MIDDLE = "\\left\\langle a\\middle|\\frac{b}{c}\\right\\rangle"
        const val MIDDLE_STYLE_RESET = "\\left(\\scriptstyle a\\middle|b\\right)"
        const val MIDDLE_MULTI_RESET_A = "\\left(\\scriptstyle a\\middle|b\\middle|c\\right)"
        const val MIDDLE_MULTI_RESET_B = "\\left(a\\middle|\\scriptstyle b\\middle|c\\right)"
        val INLINE_ASSEMBLY_SOURCE = "\\left(" +
            (1..8).fold("x") { content, _ -> "\\frac{$content}{y}" } +
            "\\right)"
    }
}

private fun org.tiqian.math.core.MathLayoutDecision.float(key: String): Float =
    details.getValue(key).toFloat()

/** XeTeX showbox lists vertical construction parts top-to-bottom; MATH records are bottom-to-top. */
private fun MathLayoutResult.tectonicTopToBottomGlyphIds(): List<UShort> {
    val output = mutableListOf<UShort>()
    val emittedConstructionGroups = mutableSetOf<Int>()
    box.glyphs.forEach { glyph ->
        val groupId = glyph.constructionGroupId
        if (groupId == null) {
            output += glyph.glyphId
        } else if (emittedConstructionGroups.add(groupId)) {
            output += box.glyphs.filter { it.constructionGroupId == groupId }
                .sortedBy { it.inkBounds.top }
                .map { it.glyphId }
        }
    }
    return output
}

private fun assertNear(expected: Float, actual: Float, message: String) {
    assertTrue(abs(expected - actual) <= 0.06f, "$message: expected $expected, got $actual")
}
