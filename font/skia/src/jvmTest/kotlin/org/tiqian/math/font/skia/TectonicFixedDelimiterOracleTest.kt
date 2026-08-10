package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tiqian.math.core.MathMode
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/**
 * Hard oracle from Tectonic 0.17.0/XeTeX `showbox` at 24bp, loading the exact repository OTFs.
 * Reproducers: `preview/tectonic/fixed-delimiter-oracle-{lete,stix}.tex`.
 */
class TectonicFixedDelimiterOracleTest {
    @Test
    fun fourAmsmathTiersMatchSameFontXeTeXGlyphAndCompletedBox() {
        oracles().forEach { oracle ->
            oracle.face.use { face ->
                val engine = MathLayoutEngine(face)
                oracle.tiers.forEach { tier ->
                    val result = engine.layout(tier.source, options())
                    assertEquals(listOf(tier.glyphId), result.box.glyphs.map { it.glyphId }, "${oracle.label}/${tier.source}")
                    assertNear(tier.widthPt.px(), result.box.width, "${oracle.label}/${tier.source} width")
                    assertNear(tier.ascentPt.px(), result.box.ascent, "${oracle.label}/${tier.source} height")
                    assertNear(tier.descentPt.px(), result.box.descent, "${oracle.label}/${tier.source} depth")
                    assertNear(tier.ascentPt.px(), result.box.texCleanBoxMetrics.ascent, "${oracle.label}/${tier.source} clean height")
                    assertNear(tier.descentPt.px(), result.box.texCleanBoxMetrics.descent, "${oracle.label}/${tier.source} clean depth")
                    val delimiter = result.decisions.single { it.name == "TeXFixedSizeDelimiter" }
                    val noad = result.decisions.single { it.name == "AmsmathFixedDelimiterNoad" }
                    assertEquals("Text", delimiter.details["measurementStyle"])
                    assertEquals("false", delimiter.details["contentDriven"])
                    assertEquals("AmsmathFixedVCenterThenXeTeXDelimiterFactorShortfall", delimiter.details["targetPolicy"])
                    assertNear(oracle.mathStrutAscentPt.px(), noad.float("mathStrutAscentPx"), "${oracle.label} mathstrut h")
                    assertNear(oracle.mathStrutDescentPt.px(), noad.float("mathStrutDescentPx"), "${oracle.label} mathstrut d")
                    assertNear(
                        tier.factor * 1.2f * (oracle.mathStrutAscentPt + oracle.mathStrutDescentPt).px(),
                        noad.float("requestedExtentPx"),
                        "${oracle.label}/${tier.source} requested extent",
                    )
                    assertTrue(result.diagnostics.isEmpty(), "${oracle.label}/${tier.source}: ${result.diagnostics}")
                }
            }
        }
    }

    @Test
    fun fixedRequestIsContentAndSurroundingStyleIndependentButRoleControlsSpacing() {
        oracles().forEach { oracle ->
            oracle.face.use { face ->
                val engine = MathLayoutEngine(face)
                val simple = engine.layout("\\bigl(x\\bigr)", options())
                val fraction = engine.layout("\\bigl(\\frac{a}{b}\\bigr)", options())
                val scripted = engine.layout("A_{\\bigl(x\\bigr)}", options())
                val declaredScript = engine.layout("{\\scriptstyle\\Bigl(x\\Bigr)}", options())

                val simpleDelimiters = simple.fixedDelimiterDecisions()
                val fractionDelimiters = fraction.fixedDelimiterDecisions()
                val scriptedDelimiters = scripted.fixedDelimiterDecisions()
                assertEquals(
                    simpleDelimiters.map { it.details["targetPx"] to it.details["glyphIds"] },
                    fractionDelimiters.map { it.details["targetPx"] to it.details["glyphIds"] },
                    oracle.label,
                )
                assertEquals(
                    simpleDelimiters.map { it.details["targetPx"] to it.details["glyphIds"] },
                    scriptedDelimiters.map { it.details["targetPx"] to it.details["glyphIds"] },
                    "${oracle.label} fresh textstyle inside a script",
                )
                assertTrue(declaredScript.fixedDelimiterDecisions().all { it.details["measurementStyle"] == "Text" }, oracle.label)

                val directional = engine.layout("a\\bigl(b\\bigm|c\\bigr)d", options())
                assertEquals(oracle.directional.glyphIds, directional.box.glyphs.map { it.glyphId }, oracle.label)
                assertNear(oracle.directional.widthPt.px(), directional.box.width, "${oracle.label} directional width")
                assertNear(oracle.directional.ascentPt.px(), directional.box.ascent, "${oracle.label} directional height")
                assertNear(oracle.directional.descentPt.px(), directional.box.descent, "${oracle.label} directional depth")
                val fixed = directional.fixedDelimiterDecisions()
                assertEquals(listOf("Opening", "Relation", "Closing"), fixed.map { it.details["atomClass"] })
                assertEquals(2, directional.decisions.count {
                    it.name == "TeXMathAtomSpacing" && it.details["kind"] == "Thick"
                }, "${oracle.label} relation glue around bigm")
                assertFalse(directional.breakOpportunities.isEmpty(), "${oracle.label} relation break after bigm")
            }
        }
    }

    @Test
    fun fixedDelimiterAsCompoundScriptBaseMatchesSameFontXeTeXCompletedBox() {
        oracles().forEach { oracle ->
            oracle.face.use { face ->
                val result = MathLayoutEngine(face).layout("\\bigl(_0^1", options())
                val script = oracle.scripted
                assertEquals(script.glyphIds, result.box.glyphs.map { it.glyphId }, "${oracle.label} script glyphs")
                assertNear(script.widthPt.px(), result.box.width, "${oracle.label} script width")
                assertNear(script.ascentPt.px(), result.box.ascent, "${oracle.label} script height")
                assertNear(script.descentPt.px(), result.box.descent, "${oracle.label} script depth")
                assertTrue(result.decisions.any {
                    it.name == "OpenTypeMathScriptPlacement" && it.details["baseKind"] == "CompoundBox"
                }, oracle.label)
                assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
            }
        }
    }

    @Test
    fun invisibleFixedDelimiterHasZeroAdvanceButRetainsTheAmsmathVcenterReserve() {
        oracles().forEach { oracle ->
            oracle.face.use { face ->
                val result = MathLayoutEngine(face).layout("\\big.", options())
                assertNear(0f, result.box.width, "${oracle.label} invisible width")
                assertTrue(result.box.glyphs.isEmpty(), oracle.label)
                val noad = result.decisions.single { it.name == "AmsmathFixedDelimiterNoad" }
                assertNear(noad.float("vcenterAscentPx"), result.box.ascent, "${oracle.label} invisible height")
                assertNear(noad.float("vcenterDescentPx"), result.box.descent, "${oracle.label} invisible depth")
                assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
            }
        }
    }

    @Test
    fun vocabularyExercisesEveryConstructionTierAvailableToFixedRequests() {
        oracles().forEach { oracle ->
            oracle.face.use { face ->
                val engine = MathLayoutEngine(face)
                val sources = listOf(
                    "\\big(",
                    "\\big\\{",
                    "\\Big\\langle",
                    "\\big\\lfloor",
                    "\\Big\\Vert",
                    "\\bigg/",
                    "\\Bigg\\uparrow",
                    "\\Bigg\\Downarrow",
                )
                val constructions = sources.associateWith { source ->
                    val result = engine.layout(source, options())
                    assertTrue(result.diagnostics.isEmpty(), "${oracle.label}/$source: ${result.diagnostics}")
                    result.fixedDelimiterDecisions().single().details.getValue("construction")
                }
                assertTrue("Variant" in constructions.values, "${oracle.label}: $constructions")
                assertTrue("Assembly" in constructions.values, "${oracle.label}: $constructions")
            }
        }
    }

    private fun options() = MathLayoutOptions(
        mode = MathMode.Inline,
        fontSizePx = 32f,
        delimiterFactor = 901,
        delimiterShortfallPx = 5f.px(),
        scriptSpacePx = 0.5f.px(),
        nullDelimiterSpacePx = 1.2f.px(),
    )

    private fun oracles() = listOf(
        Oracle(
            label = "Lete Sans Math",
            face = SkiaMathFontFace(LeteSansMath.load()),
            mathStrutAscentPt = 17.87477f,
            mathStrutDescentPt = 4.38438f,
            tiers = listOf(
                Tier("\\big(", 1f, 1835u, 9.20238f, 20.59766f, 7.10585f),
                Tier("\\Big(", 1.5f, 1836u, 9.636f, 26.7791f, 13.28726f),
                Tier("\\bigg(", 2f, 1838u, 10.52733f, 34.48555f, 20.99373f),
                Tier("\\Bigg(", 2.5f, 1839u, 10.96095f, 40.13455f, 26.64272f),
            ),
            scripted = ScriptedOracle(19.48293f, 25.72327f, 10.61371f, listOf(1835u, 2549u, 2548u)),
            directional = DirectionalOracle(
                95.3148f,
                20.59766f,
                7.10585f,
                listOf(3628u, 1835u, 3629u, 1910u, 3630u, 1850u, 3631u),
            ),
        ),
        Oracle(
            label = "STIX Two Math",
            face = SkiaMathFontFace(StixTwoMath.load()),
            mathStrutAscentPt = 17.73024f,
            mathStrutDescentPt = 4.72163f,
            tiers = listOf(
                Tier("\\big(", 1f, 1301u, 9.17828f, 20.50125f, 8.06949f),
                Tier("\\Big(", 1.5f, 1303u, 10.28642f, 26.42252f, 13.99074f),
                Tier("\\bigg(", 2f, 1305u, 11.39456f, 33.15805f, 20.72629f),
                Tier("\\Bigg(", 2.5f, 1307u, 12.50272f, 39.8936f, 27.46182f),
            ),
            scripted = ScriptedOracle(19.45883f, 27.86996f, 10.98708f, listOf(1301u, 4274u, 4273u)),
            directional = DirectionalOracle(
                87.99141f,
                20.50125f,
                8.06949f,
                listOf(3326u, 1301u, 3327u, 1062u, 1062u, 3328u, 1313u, 3329u),
            ),
        ),
    )

    private data class Oracle(
        val label: String,
        val face: SkiaMathFontFace,
        val mathStrutAscentPt: Float,
        val mathStrutDescentPt: Float,
        val tiers: List<Tier>,
        val scripted: ScriptedOracle,
        val directional: DirectionalOracle,
    )

    private data class Tier(
        val source: String,
        val factor: Float,
        val glyphId: UShort,
        val widthPt: Float,
        val ascentPt: Float,
        val descentPt: Float,
    )

    private data class ScriptedOracle(
        val widthPt: Float,
        val ascentPt: Float,
        val descentPt: Float,
        val glyphIds: List<UShort>,
    )

    private data class DirectionalOracle(
        val widthPt: Float,
        val ascentPt: Float,
        val descentPt: Float,
        val glyphIds: List<UShort>,
    )
}

private fun Float.px(): Float = this * 96f / 72.27f

private fun org.tiqian.math.core.MathLayoutDecision.float(key: String): Float =
    details.getValue(key).toFloat()

private fun org.tiqian.math.core.MathLayoutResult.fixedDelimiterDecisions() =
    decisions.filter { it.name == "TeXFixedSizeDelimiter" }

private fun assertNear(expected: Float, actual: Float, message: String) {
    assertTrue(abs(expected - actual) <= 0.07f, "$message: expected $expected, got $actual")
}
