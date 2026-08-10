package org.tiqian.math.font.skia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/** Reproducer: `preview/tectonic/equation-tag-oracle-{lete,stix}.tex`. */
class TectonicEquationTagOracleTest {
    @Test
    fun singleDisplayTagMatchesSameFontTectonicBodyCenterAndRightEdge() {
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                SkiaMathTextRunProvider.fromBytes(
                    MathFaceId("tectonic-tag-text-${oracle.label}"),
                    oracle.bytes,
                ).use { text ->
                    val result = MathLayoutEngine(face, textRunProvider = text).layout(
                        "x+y\\tag{1}",
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = FONT_SIZE_PX,
                            displayWidthPx = DISPLAY_WIDTH_PX,
                        ),
                    )
                    assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
                    val decision = result.decisions.single { it.name == "AmsmathEquationTag" }
                    assertEquals(DISPLAY_WIDTH_PX, result.box.width, EPSILON_PX)
                    assertEquals(oracle.bodyWidthPx, decision.float("bodyWidthPx"), EPSILON_PX)
                    assertEquals(oracle.bodyX, decision.float("bodyX"), EPSILON_PX)
                    assertEquals(oracle.tagWidthPx, decision.float("tagWidthPx"), EPSILON_PX)
                    assertEquals(DISPLAY_WIDTH_PX - oracle.tagWidthPx, decision.float("tagX"), EPSILON_PX)
                    assertEquals(0f, decision.float("tagBaselineY"), 0.001f)
                    assertEquals("AmsmathDisplayBodyCenteredTagRightAlignedAtHostDisplayWidth", decision.details["policy"])
                    val tagGlyphs = result.box.glyphs.filter {
                        it.hostTextDecision?.hostRole == MathTextOrigin.EquationTag.name
                    }
                    assertTrue(tagGlyphs.isNotEmpty())
                    assertEquals(DISPLAY_WIDTH_PX, tagGlyphs.maxOf { it.x + it.advance }, EPSILON_PX)
                    assertTrue(tagGlyphs.any { it.sourceRange == SourceRange(8, 9) }, "content digit keeps its source range")
                }
            }
        }
    }

    @Test
    fun missingWidthAndInlineTagAreFormulaWideCapabilityErrors() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(MathFaceId("tag-text-errors"), LeteSansMath.loadBytes()).use { text ->
                val engine = MathLayoutEngine(face, textRunProvider = text)
                val missingWidth = engine.layout("x\\tag{1}", MathLayoutOptions(mode = MathMode.Display, fontSizePx = 32f))
                assertTrue(missingWidth.diagnostics.any { it.code == DiagnosticCode.MissingEquationTagDisplayWidth })
                val inline = engine.layout(
                    "x\\tag{1}",
                    MathLayoutOptions(mode = MathMode.Inline, fontSizePx = 32f, displayWidthPx = 400f),
                )
                assertTrue(inline.diagnostics.any { it.code == DiagnosticCode.MisplacedEquationTag })
            }
        }
    }

    @Test
    fun alignmentRowsShareTheDisplayRightEdgeAndKeepTectonicBaselineDistance() {
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                SkiaMathTextRunProvider.fromBytes(
                    MathFaceId("tectonic-align-tag-text-${oracle.label}"),
                    oracle.bytes,
                ).use { text ->
                    val result = MathLayoutEngine(face, textRunProvider = text).layout(
                        "\\begin{align*}a&=b\\tag{1}\\\\c&=d\\tag{2}\\end{align*}",
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = FONT_SIZE_PX,
                            displayWidthPx = DISPLAY_WIDTH_PX,
                        ),
                    )
                    assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
                    val tags = result.decisions.filter { it.name == "AmsmathEquationTag" }
                    assertEquals(2, tags.size)
                    assertEquals(tags[0].float("tagX"), tags[1].float("tagX"), 0.01f)
                    assertEquals(
                        oracle.alignBaselineDistancePx,
                        tags[1].float("tagBaselineY") - tags[0].float("tagBaselineY"),
                        EPSILON_PX,
                    )
                    assertEquals(DISPLAY_WIDTH_PX, result.box.width, 0.01f)
                }
            }
        }
    }

    private fun MathLayoutDecision.float(name: String): Float = checkNotNull(details[name]).toFloat()

    private data class Oracle(
        val label: String,
        val mathFont: org.tiqian.math.font.opentype.OpenTypeMathFont,
        val bytes: ByteArray,
        val bodyWidthPx: Float,
        val bodyX: Float,
        val tagWidthPx: Float,
        val alignBaselineDistancePx: Float,
    )

    private companion object {
        const val FONT_SIZE_PX = 32f
        const val DISPLAY_WIDTH_PX = 400f
        const val EPSILON_PX = 0.3f

        val oracles = listOf(
            Oracle(
                "lete", LeteSansMath.load(), LeteSansMath.loadBytes(),
                71.08769f, 164.45618f, 38.72f, 38.65045f,
            ),
            Oracle(
                "stix", StixTwoMath.load(), StixTwoMath.loadBytes(),
                72.27169f, 163.86418f, 38.68799f, 38.45859f,
            ),
        )
    }
}
