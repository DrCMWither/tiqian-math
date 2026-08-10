package org.tiqian.math.font.skia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

class MathBboxLayoutTest {
    @Test
    fun paddingAndSolidBorderPreserveTheInnerMathGeometryForBothFonts() {
        fonts.forEach { (label, font) ->
            SkiaMathFontFace(font).use { face ->
                val bare = MathLayoutEngine(face).layout("x^2+\\sqrt{y}", options)
                val boxed = MathLayoutEngine(face).layout(
                    "\\bbox[5px,border:1px solid red]{x^2+\\sqrt{y}}",
                    options,
                )
                assertTrue(boxed.diagnostics.isEmpty(), "$label: ${boxed.diagnostics}")
                assertEquals(bare.box.width + 12f, boxed.box.width, epsilon)
                assertEquals(bare.box.ascent + 6f, boxed.box.ascent, epsilon)
                assertEquals(bare.box.descent + 6f, boxed.box.descent, epsilon)
                assertEquals(bare.box.glyphs.map { it.glyphId }, boxed.box.glyphs.map { it.glyphId })
                bare.box.glyphs.zip(boxed.box.glyphs).forEach { (expected, actual) ->
                    assertEquals(expected.x + 6f, actual.x, epsilon, label)
                    assertEquals(expected.baselineY, actual.baselineY, epsilon, label)
                    assertEquals(expected.style, actual.style, label)
                }
                val borders = boxed.box.rules.filter { it.paintRole == MathRulePaintRole.Border }
                assertEquals(4, borders.size, label)
                assertTrue(borders.all { it.paintLayer == MathPaintLayer.Foreground })
                assertTrue(borders.all { it.paintColor == MathPaintColor(255, 0, 0) })
                assertEquals(
                    bare.box.constructionPaintGroups.map { it.kind },
                    boxed.box.constructionPaintGroups.map { it.kind },
                    label,
                )
            }
        }
    }

    @Test
    fun backgroundPaintsBehindTheMathAndCssBorderWithoutStyleRemainsInvisible() {
        fonts.forEach { (label, font) ->
            SkiaMathFontFace(font).use { face ->
                val bare = MathLayoutEngine(face).layout("x", options)
                val boxed = MathLayoutEngine(face).layout("\\bbox[#CAF,20px,border:1px]{x}", options)
                assertTrue(boxed.diagnostics.isEmpty(), "$label: ${boxed.diagnostics}")
                assertEquals(bare.box.width + 40f, boxed.box.width, epsilon)
                assertEquals(1, boxed.box.rules.count { it.paintRole == MathRulePaintRole.BackgroundFill })
                assertEquals(0, boxed.box.rules.count { it.paintRole == MathRulePaintRole.Border })
                val background = boxed.box.rules.single { it.paintRole == MathRulePaintRole.BackgroundFill }
                assertEquals(MathPaintLayer.Background, background.paintLayer)
                assertEquals(MathPaintColor(204, 170, 255), background.paintColor)
                assertEquals(0f, background.left, epsilon)
                assertEquals(boxed.box.width, background.right, epsilon)
                assertEquals(-boxed.box.ascent, background.top, epsilon)
                assertEquals(boxed.box.descent, background.bottom, epsilon)
            }
        }
    }

    @Test
    fun emMuAndExResolveAtTheActualEightStyleFontSize() {
        assertEquals(450, LeteSansMath.load().xHeight, "repository Lete OS/2.sxHeight")
        assertEquals(473, StixTwoMath.load().xHeight, "repository STIX OS/2.sxHeight")
        fonts.forEach { (label, font) ->
            SkiaMathFontFace(font).use { face ->
                val engine = MathLayoutEngine(face)
                val script = engine.layout("x^{\\bbox[1em]{y}}", options)
                val bbox = script.decisions.single { it.name == "MathJaxBboxExtension" }
                val expectedScriptSize = 32f * font.constants.scriptPercentScaleDown / 100f
                assertEquals(MathStyle.Script.toString(), bbox.details["outerStyle"], label)
                assertEquals(expectedScriptSize, bbox.float("paddingPx"), epsilon, label)

                val ex = engine.layout("\\bbox[1ex]{x}", options)
                val expectedXHeight = font.scaleDesignUnits(checkNotNull(font.xHeight), 32f)
                assertEquals(expectedXHeight, ex.decisions.single { it.name == "MathJaxBboxExtension" }.float("paddingPx"), epsilon, label)

                val mu = engine.layout("\\bbox[18mu]{x}", options)
                assertEquals(32f, mu.decisions.single { it.name == "MathJaxBboxExtension" }.float("paddingPx"), epsilon, label)
            }
        }
    }

    @Test
    fun bboxCompletesAContainedAlignEnvironmentAsOneReplayableBox() {
        fonts.forEach { (label, font) ->
            SkiaMathFontFace(font).use { face ->
                val result = MathLayoutEngine(face).layout(
                    "\\bbox[5px,border:1px solid blue]{\\begin{align}a&=b\\\\c&=d\\end{align}}",
                    MathLayoutOptions(mode = MathMode.Display, fontSizePx = 32f),
                )
                assertTrue(result.diagnostics.isEmpty(), "$label: ${result.diagnostics}")
                assertEquals(4, result.box.rules.count { it.paintRole == MathRulePaintRole.Border }, label)
                assertTrue(result.box.glyphs.isNotEmpty(), label)
                assertTrue(result.box.ascent > 32f && result.box.descent > 10f, label)
            }
        }
    }

    private fun MathLayoutDecision.float(name: String): Float = details.getValue(name).toFloat()

    private companion object {
        val options = MathLayoutOptions(mode = MathMode.Inline, fontSizePx = 32f)
        const val epsilon = 0.02f
        val fonts: List<Pair<String, OpenTypeMathFont>> = listOf(
            "Lete" to LeteSansMath.load(),
            "STIX" to StixTwoMath.load(),
        )
    }
}
