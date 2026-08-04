package org.tiqian.math.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.skia.SkiaMathFontFace
import org.tiqian.math.layout.MathFormulaCapabilityCategory
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathFormulaStrictException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class MathFormulaSafetyTest {
    @Test
    fun readyFormulaDrawsMathAndNeverInvokesFallback() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var fallbackCalls = 0
            var layout: MathLayoutResult? = null
            ImageComposeScene(width = 160, height = 80, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    TiqianMathOrFallback(
                        source = "x_1^2+\\frac{a}{b}",
                        modifier = Modifier.size(130.dp, 70.dp),
                        fontSizePx = 32f,
                        fontFace = face,
                        onMathLayout = { layout = it },
                        fallback = { fallbackCalls += 1 },
                    )
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                assertTrue((0 until pixels.height).any { y ->
                    (0 until pixels.width).any { x ->
                        val pixel = pixels[x, y]
                        pixel.red < 0.35f && pixel.green < 0.35f && pixel.blue < 0.35f
                    }
                })
            }
            assertEquals(0, fallbackCalls)
            assertNotNull(layout)
        }
    }

    @Test
    fun fallbackFormulaDrawsOnlyTheHostSlotAndPassesExactEvidenceOnce() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val source = "\\sqrt{x}+\\text{bad}"
            var fallbackCalls = 0
            var mathLayoutCalls = 0
            var captured: MathFormulaCapabilityResult.FallbackRequired? = null
            ImageComposeScene(width = 120, height = 80, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    TiqianMathOrFallback(
                        source = source,
                        fontSizePx = 32f,
                        fontFace = face,
                        onMathLayout = { mathLayoutCalls += 1 },
                        fallback = { request ->
                            fallbackCalls += 1
                            captured = request
                            Canvas(Modifier.size(60.dp, 36.dp)) { drawRect(Color.Magenta) }
                        },
                    )
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                var magentaPixels = 0
                var darkPixels = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val pixel = pixels[x, y]
                    if (pixel.red > 0.9f && pixel.blue > 0.9f && pixel.green < 0.1f) magentaPixels += 1
                    if (pixel.red < 0.35f && pixel.green < 0.35f && pixel.blue < 0.35f) darkPixels += 1
                }
                assertEquals(60 * 36, magentaPixels)
                assertEquals(0, darkPixels, "no partial Tiqian glyphs are painted")
            }

            assertEquals(1, fallbackCalls)
            assertEquals(0, mathLayoutCalls)
            assertEquals(0, face.constructionOutlineCacheStats().entries, "rejected radical builds no path")
            val request = assertNotNull(captured)
            assertEquals(source, request.source)
            assertEquals(
                listOf(MathFormulaCapabilityCategory.UnsupportedSyntax),
                request.reasons.map { it.category },
            )
            val unsupported = request.diagnostics.single { it.code == DiagnosticCode.UnsupportedCommand }
            assertEquals(SourceRange(9, 14), unsupported.range)
            assertEquals("\\text", source.substring(unsupported.range.start, unsupported.range.endExclusive))
        }
    }

    @Test
    fun strictEntryFailsBeforeMeasureDrawAndConstructionPathCreation() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var mathLayoutCalls = 0
            val exception = assertFailsWith<MathFormulaStrictException> {
                ImageComposeScene(width = 120, height = 80, density = Density(1f)) {
                    TiqianMath(
                        source = "\\sqrt{x}+\\text{bad}",
                        fontSizePx = 32f,
                        fontFace = face,
                        onMathLayout = { mathLayoutCalls += 1 },
                    )
                }.use { it.render() }
            }

            assertEquals(0, mathLayoutCalls)
            assertEquals(0, face.constructionOutlineCacheStats().entries)
            assertTrue(exception.fallback.diagnostics.any { it.code == DiagnosticCode.UnsupportedCommand })
        }
    }
}
