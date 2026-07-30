package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.MathLayoutDecision
import org.tiqian.math.core.MathMode
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/**
 * Reviewed Tectonic 0.17.0 / XeTeX `showbox` traces at 24bp, exactly 32 CSS px
 * at 96dpi. The reproducers under `preview/tectonic` load these repository OTFs
 * directly. Values remain in TeX points here so the conversion is explicit.
 */
class TectonicRadicalVerticalOracleTest {
    @Test
    fun cleanBoxTargetClearanceAndOuterBoxMatchReviewedTrace() {
        val failures = mutableListOf<String>()
        radicalFonts().forEach { oracle ->
            oracle.face.use { face ->
                val engine = MathLayoutEngine(face)
                oracle.cases.forEach { case ->
                    val result = engine.layout(
                        case.source,
                        MathLayoutOptions(
                            mode = case.mode,
                            fontSizePx = FONT_SIZE_PX,
                            nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
                        ),
                    )
                    val construction = result.decisions
                        .filter { it.name == "OpenTypeRadicalConstruction" }
                        .last()
                    val geometry = result.decisions
                        .filter { it.name == "OpenTypeMathRadical" }
                        .last()
                    val cleanBox = result.decisions.filter { it.name == "TeXRadicalCleanBox" }.last()
                    val cleanAscent = case.cleanAscentPt.px()
                    val cleanDescent = case.cleanDescentPt.px()
                    val expectedRule = case.ruleThicknessPt.px()
                    val expectedClearance = case.actualClearancePt.px()
                    val expectedRuleBottom = -cleanAscent - expectedClearance
                    val expectedRuleTop = expectedRuleBottom - expectedRule
                    val expectedTarget = cleanAscent + cleanDescent +
                        geometry.float("minimumRadicalGapPx") + expectedRule

                    println(
                        "radical-vertical-current=${oracle.label}/${case.label} " +
                            "clean=${geometry.details["radicandAscentPx"]}+" +
                            "${geometry.details["radicandDescentPx"]}/" +
                            "clean-minus-ink=${cleanBox.details["cleanMinusPaintedInkAbovePx"]}+" +
                            "${cleanBox.details["cleanMinusPaintedInkBelowPx"]}/" +
                            "${cleanBox.details["completedChildBoxMetricsPreserved"]} " +
                            "target=${construction.details["targetHeightPx"]} " +
                            "construction=${construction.details["construction"]}/" +
                            "${construction.details["achievedAdvancePx"]} " +
                            "gap=${geometry.details["actualRadicalGapPx"]} " +
                            "rule=${geometry.details["ruleTop"]}..${geometry.details["ruleBottom"]} " +
                            "B=${geometry.details["unindexedAscentPx"]}+" +
                            "${geometry.details["unindexedDescentPx"]} " +
                            "degree=${geometry.details["degreeBaselineY"]}",
                    )
                    if (case.label == "display assembly") {
                        result.decisions.filter { it.name == "OpenTypeMathRadical" }.forEachIndexed { index, nested ->
                            println(
                                "radical-nested-current=${oracle.label}/${index + 1} " +
                                    "clean=${nested.details["radicandAscentPx"]}+" +
                                    "${nested.details["radicandDescentPx"]} " +
                                    "construction=${nested.details["construction"]}/" +
                                    "${nested.details["achievedAdvancePx"]} " +
                                    "gap=${nested.details["actualRadicalGapPx"]} " +
                                    "B=${nested.details["unindexedAscentPx"]}+" +
                                    "${nested.details["unindexedDescentPx"]}",
                            )
                        }
                    }

                    runCatching {
                        assertEquals(case.construction, construction.details["construction"], case.message(oracle, "construction"))
                        assertEquals(case.componentGlyphIds, construction.details.getValue("componentGlyphIds").csvUShorts())
                        assertNear(
                            case.nominalAdvancePt.px(),
                            construction.float("achievedAdvancePx"),
                            case.message(oracle, "nominal advance"),
                        )
                        assertEquals(
                            "CompletedLayoutBox",
                            cleanBox.details["policy"],
                            case.message(oracle, "clean-box evidence policy"),
                        )
                        assertTrue("GlyphOutline" in cleanBox.details.getValue("evidence"))
                        assertEquals("true", cleanBox.details["exactGlyphOutlineBoundsAvailable"])
                        assertEquals("GlyphOutlineCrossSection", geometry.details["radicalTopStrokeEvidenceSource"])
                        assertNear(cleanAscent, geometry.float("radicandAscentPx"), case.message(oracle, "clean ascent"))
                        assertNear(cleanDescent, geometry.float("radicandDescentPx"), case.message(oracle, "clean descent"))
                        assertNear(expectedTarget, construction.float("targetHeightPx"), case.message(oracle, "stretch target"))
                        assertNear(expectedClearance, geometry.float("actualRadicalGapPx"), case.message(oracle, "final clearance"))
                        assertNear(expectedRuleTop, geometry.float("ruleTop"), case.message(oracle, "rule top"))
                        assertNear(expectedRuleBottom, geometry.float("ruleBottom"), case.message(oracle, "rule bottom"))
                        assertNear(case.outerAscentPt.px(), geometry.float("unindexedAscentPx"), case.message(oracle, "outer ascent"))
                        assertNear(case.outerDescentPt.px(), geometry.float("unindexedDescentPx"), case.message(oracle, "outer descent"))
                        assertNear(expectedRuleBottom, geometry.float("texDelimiterBoxShiftPx"), case.message(oracle, "delimiter box shift"))
                        case.degreeBaselinePt?.let {
                            assertNear(it.px(), geometry.float("degreeBaselineY"), case.message(oracle, "degree baseline"))
                        }
                        if (case.construction == "Assembly") {
                            assertEquals(
                                "Tectonic0.17.0XeTeXBuildOpenTypeAssemblyStretchGlue",
                                construction.details["constructionPolicy"],
                            )
                            assertEquals(
                                case.assemblySourcePartRecords,
                                construction.details["assemblySourcePartRecords"],
                                case.message(oracle, "source assembly records"),
                            )
                            assertEquals(
                                case.extenderRepetitions.toString(),
                                construction.details["extenderRepetitions"],
                                case.message(oracle, "extender repetitions"),
                            )
                            assertDesignNear(
                                case.assemblyNaturalAdvanceDesignUnits!!,
                                construction.float("assemblyNaturalAdvanceDesignUnits"),
                                case.message(oracle, "natural assembly advance"),
                            )
                            assertDesignNear(
                                case.assemblyStretchCapacityDesignUnits!!,
                                construction.float("assemblyStretchCapacityDesignUnits"),
                                case.message(oracle, "assembly stretch capacity"),
                            )
                            assertDesignNear(
                                case.assemblyAppliedStretchDesignUnits!!,
                                construction.float("assemblyAppliedStretchDesignUnits"),
                                case.message(oracle, "applied assembly stretch"),
                            )
                            case.connectorOverlapsDesignUnits!!.zip(
                                construction.details.getValue("connectorOverlapsDesignUnits").floatList(),
                            ).forEach { (expected, actual) ->
                                assertDesignNear(expected, actual, case.message(oracle, "connector overlap"))
                            }
                        }
                    }.onFailure { failures += it.message.orEmpty() }
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun nestedCleanBoxIncludesTheInnerRadicalLogicalReserveRatherThanOnlyItsInk() {
        radicalFonts().forEach { oracle ->
            oracle.face.use { face ->
                val engine = MathLayoutEngine(face)
                val inner = engine.layout(
                    "\\sqrt{x}",
                    MathLayoutOptions(fontSizePx = FONT_SIZE_PX),
                )
                val nested = engine.layout(
                    "\\sqrt{\\sqrt{x}}",
                    MathLayoutOptions(fontSizePx = FONT_SIZE_PX),
                )
                val outer = nested.decisions.filter { it.name == "OpenTypeMathRadical" }.last()
                val outerConstruction = nested.decisions
                    .filter { it.name == "OpenTypeRadicalConstruction" }
                    .last()

                assertNear(inner.box.ascent, outer.float("radicandAscentPx"), "$oracle nested clean ascent")
                assertNear(inner.box.descent, outer.float("radicandDescentPx"), "$oracle nested clean descent")
                assertTrue(
                    outerConstruction.float("targetHeightPx") > outerConstruction.float("radicandInkHeightPx"),
                    "$oracle target must include logical reserve omitted by the visible ink union",
                )
                assertEquals("TeXCleanBoxHeightPlusGapAndRule", outerConstruction.details["targetMetric"])
            }
        }
    }

    private fun radicalFonts(): List<RadicalFontOracle> = listOf(
        RadicalFontOracle(
            label = "Lete Sans Math",
            face = SkiaMathFontFace(LeteSansMath.load()),
            cases = listOf(
                RadicalOracleCase("inline x", "\\sqrt{x}", MathMode.Inline, 23.87318f, 5.70932f, 12.18954f, 0f, 27.75166f, 8.02196f, 1.83084f, "BaseGlyph", listOf(557u)),
                RadicalOracleCase("inline X", "\\sqrt{X}", MathMode.Inline, 26.40263f, 3.17987f, 17.24844f, 0f, 27.75166f, 5.49251f, 1.83084f, "BaseGlyph", listOf(557u)),
                RadicalOracleCase("scripts", "\\sqrt{x_j^2}", MathMode.Inline, 31.4218f, 13.45787f, 21.87854f, 9.88895f, 43.04883f, 5.88158f, 1.83084f, "Variant", listOf(1791u)),
                RadicalOracleCase("superscript", "\\sqrt{x^2}", MathMode.Inline, 28.35681f, 1.2257f, 21.15678f, 0f, 27.75166f, 3.53835f, 1.83084f, "BaseGlyph", listOf(557u)),
                RadicalOracleCase("subscript", "\\sqrt{x_j}", MathMode.Inline, 19.33551f, 10.247f, 12.18954f, 9.07535f, 27.75166f, 3.48428f, 1.83084f, "BaseGlyph", listOf(557u)),
                RadicalOracleCase("superscript fraction", "\\sqrt{x^{\\frac{a}{b}}}", MathMode.Inline, 34.45316f, 2.78998f, 25.68884f, 0f, 35.41229f, 5.10263f, 1.83084f, "Variant", listOf(1790u)),
                RadicalOracleCase("subscript radical", "\\sqrt{x_{\\sqrt{y}}}", MathMode.Inline, 21.9812f, 15.26193f, 12.18954f, 11.44461f, 35.41229f, 6.12997f, 1.83084f, "Variant", listOf(1790u)),
                RadicalOracleCase("fraction", "\\sqrt{\\frac{a}{b}}", MathMode.Inline, 25.53899f, 11.70415f, 19.54297f, 11.68246f, 35.41229f, 2.33434f, 1.83084f, "Variant", listOf(1790u)),
                RadicalOracleCase("nested sum", "\\sqrt{1+\\sqrt{x}}", MathMode.Inline, 30.69066f, 6.55247f, 23.87318f, 5.70932f, 35.41229f, 3.15579f, 1.83084f, "Variant", listOf(1790u)),
                RadicalOracleCase("nested only", "\\sqrt{\\sqrt{x}}", MathMode.Inline, 30.69066f, 6.55247f, 23.87318f, 5.70932f, 35.41229f, 3.15579f, 1.83084f, "Variant", listOf(1790u)),
                RadicalOracleCase("degree X", "\\sqrt[3]{X}", MathMode.Inline, 26.40263f, 3.17987f, 17.24844f, 0f, 27.75166f, 5.49251f, 1.83084f, "BaseGlyph", listOf(557u), -14.86256f),
                RadicalOracleCase("degree fraction", "\\sqrt[3]{\\frac{a}{b}}", MathMode.Inline, 25.53899f, 11.70415f, 19.54297f, 11.68246f, 35.41229f, 2.33434f, 1.83084f, "Variant", listOf(1790u), -8.8543f),
                RadicalOracleCase("display x", "\\sqrt{x}", MathMode.Display, 24.42726f, 5.15524f, 12.18954f, 0f, 27.75166f, 8.57603f, 1.83084f, "BaseGlyph", listOf(557u)),
                RadicalOracleCase("display fraction", "\\sqrt{\\frac{a}{b}}", MathMode.Display, 34.4606f, 18.0556f, 26.35594f, 17.03342f, 50.68536f, 4.44298f, 1.83084f, "Variant", listOf(1792u)),
                // showbox lists a vbox top-to-bottom; the adapter decision retains OpenType's
                // bottom-to-top part-record order, hence the reversed endpoint glyphs.
                RadicalOracleCase(
                    "display assembly", nestedRadical(18), MathMode.Display,
                    149.04485f, 9.37097f, 141.96239f, 9.37097f, 156.58498f, 3.42078f,
                    1.83084f, "Assembly", listOf(2182u, 2181u, 2181u, 2180u),
                    assemblySourcePartRecords = "2182:0:150:3800:false;2181:150:150:600:true;2180:150:0:1600:false",
                    extenderRepetitions = 2,
                    assemblyNaturalAdvanceDesignUnits = 6150f,
                    assemblyStretchCapacityDesignUnits = 390f,
                    assemblyAppliedStretchDesignUnits = 351.00195f,
                    connectorOverlapsDesignUnits = listOf(32.99935f, 32.99935f, 32.99935f),
                ),
            ),
        ),
        RadicalFontOracle(
            label = "STIX Two Math",
            face = SkiaMathFontFace(StixTwoMath.load()),
            cases = listOf(
                RadicalOracleCase("inline x", "\\sqrt{x}", MathMode.Inline, 23.42754f, 6.80542f, 11.53911f, 0.2409f, 28.59483f, 8.61218f, 1.63812f, "BaseGlyph", listOf(1657u)),
                RadicalOracleCase("inline X", "\\sqrt{X}", MathMode.Inline, 25.692f, 4.54095f, 15.82713f, 0f, 28.59483f, 6.58862f, 1.63812f, "BaseGlyph", listOf(1657u)),
                RadicalOracleCase("scripts", "\\sqrt{x_j^2}", MathMode.Inline, 30.84967f, 15.45131f, 20.46928f, 10.39482f, 44.66285f, 7.10414f, 1.63812f, "Variant", listOf(1658u)),
                RadicalOracleCase("superscript", "\\sqrt{x^2}", MathMode.Inline, 26.35118f, 3.88177f, 17.3864f, 0.2409f, 28.59483f, 5.68854f, 1.63812f, "BaseGlyph", listOf(1657u)),
                RadicalOracleCase("subscript", "\\sqrt{x_j}", MathMode.Inline, 19.11275f, 11.12021f, 11.53911f, 8.87047f, 28.59483f, 4.2974f, 1.63812f, "BaseGlyph", listOf(1657u)),
                RadicalOracleCase("superscript fraction", "\\sqrt{x^{\\frac{a}{b}}}", MathMode.Inline, 40.73964f, 5.56134f, 30.0953f, 0.2409f, 44.66285f, 7.36809f, 1.63812f, "Variant", listOf(1658u)),
                RadicalOracleCase("subscript radical", "\\sqrt{x_{\\sqrt{y}}}", MathMode.Inline, 17.399f, 12.83395f, 11.53911f, 12.29797f, 28.59483f, 2.58365f, 1.63812f, "BaseGlyph", listOf(1657u)),
                RadicalOracleCase("fraction", "\\sqrt{\\frac{a}{b}}", MathMode.Inline, 30.06192f, 16.23906f, 22.81232f, 14.31337f, 44.66285f, 3.97336f, 1.63812f, "Variant", listOf(1658u)),
                RadicalOracleCase("nested sum", "\\sqrt{1+\\sqrt{x}}", MathMode.Inline, 34.1235f, 12.17747f, 23.42754f, 6.80542f, 44.66285f, 7.41972f, 1.63812f, "Variant", listOf(1658u)),
                RadicalOracleCase("nested only", "\\sqrt{\\sqrt{x}}", MathMode.Inline, 34.1235f, 12.17747f, 23.42754f, 6.80542f, 44.66285f, 7.41972f, 1.63812f, "Variant", listOf(1658u)),
                RadicalOracleCase("degree X", "\\sqrt[3]{X}", MathMode.Inline, 25.692f, 4.54095f, 15.82713f, 0f, 28.59483f, 6.58862f, 1.63812f, "BaseGlyph", listOf(1657u), -11.63307f),
                RadicalOracleCase("degree fraction", "\\sqrt[3]{\\frac{a}{b}}", MathMode.Inline, 30.06192f, 16.23906f, 22.81232f, 14.31337f, 44.66285f, 3.97336f, 1.63812f, "Variant", listOf(1658u), -7.60257f),
                RadicalOracleCase("display x", "\\sqrt{x}", MathMode.Display, 24.45135f, 5.7816f, 11.53911f, 0.2409f, 28.59483f, 9.636f, 1.63812f, "BaseGlyph", listOf(1657u)),
                RadicalOracleCase("display fraction", "\\sqrt{\\frac{a}{b}}", MathMode.Display, 38.6765f, 20.05492f, 26.95834f, 15.70831f, 57.09329f, 8.44191f, 1.63812f, "Variant", listOf(1659u)),
                RadicalOracleCase(
                    "display assembly", nestedRadical(18), MathMode.Display,
                    159.23509f, 15.24893f, 151.86354f, 15.24893f, 172.8459f, 4.0953f,
                    1.63812f, "Assembly",
                    listOf(1661u, 1664u, 1664u, 1664u, 1664u, 1664u, 1664u, 1664u, 1664u, 1664u, 1662u),
                    assemblySourcePartRecords = "1661:200:200:1905:false;1664:650:650:651:true;1662:550:0:642:false",
                    extenderRepetitions = 9,
                    assemblyNaturalAdvanceDesignUnits = 2445f,
                    assemblyStretchCapacityDesignUnits = 4950f,
                    assemblyAppliedStretchDesignUnits = 4730.9995f,
                    connectorOverlapsDesignUnits = listOf(
                        104.42425f,
                        124.333374f,
                        124.333374f,
                        124.333374f,
                        124.333374f,
                        124.333374f,
                        124.333374f,
                        124.333374f,
                        124.333374f,
                        119.90912f,
                    ),
                ),
            ),
        ),
    )

    private data class RadicalFontOracle(
        val label: String,
        val face: SkiaMathFontFace,
        val cases: List<RadicalOracleCase>,
    )

    private data class RadicalOracleCase(
        val label: String,
        val source: String,
        val mode: MathMode,
        val outerAscentPt: Float,
        val outerDescentPt: Float,
        val cleanAscentPt: Float,
        val cleanDescentPt: Float,
        val nominalAdvancePt: Float,
        val actualClearancePt: Float,
        val ruleThicknessPt: Float,
        val construction: String,
        val componentGlyphIds: List<UShort>,
        val degreeBaselinePt: Float? = null,
        val assemblySourcePartRecords: String? = null,
        val extenderRepetitions: Int? = null,
        val assemblyNaturalAdvanceDesignUnits: Float? = null,
        val assemblyStretchCapacityDesignUnits: Float? = null,
        val assemblyAppliedStretchDesignUnits: Float? = null,
        val connectorOverlapsDesignUnits: List<Float>? = null,
    ) {
        fun message(font: RadicalFontOracle, field: String): String = "${font.label}/$label $field"
    }

    private fun Float.px(): Float = this * TEX_PT_TO_PX

    private fun MathLayoutDecision.float(key: String): Float = details.getValue(key).toFloat()

    private fun String.csvUShorts(): List<UShort> = split(',').filter(String::isNotBlank).map(String::toUShort)

    private fun String.floatList(): List<Float> =
        removePrefix("[").removeSuffix("]").split(',').filter(String::isNotBlank).map { it.trim().toFloat() }

    private fun assertNear(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) <= EPSILON_PX, "$message: expected $expected, got $actual")
    }

    private fun assertDesignNear(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) <= EPSILON_DESIGN_UNITS, "$message: expected $expected, got $actual")
    }

    private companion object {
        const val FONT_SIZE_PX = 32f
        const val TEX_PT_TO_PX = 96f / 72.27f
        const val TECTONIC_NULL_DELIMITER_SPACE_PX = 1.2f * TEX_PT_TO_PX
        const val EPSILON_PX = 0.08f
        const val EPSILON_DESIGN_UNITS = 0.1f
    }
}

private fun nestedRadical(depth: Int): String = "\\sqrt{".repeat(depth) + "x" + "}".repeat(depth)
