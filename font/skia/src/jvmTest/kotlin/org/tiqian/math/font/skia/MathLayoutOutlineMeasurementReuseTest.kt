package org.tiqian.math.font.skia

import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MeasuredMathRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MathLayoutOutlineMeasurementReuseTest {
    @Test
    fun oneLayoutPassMeasuresEachResolvedGlyphStyleAndSizeOnce() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val calls = mutableMapOf<OutlineKey, Int>()
            val countingFace = object : MathFontFace by delegate {
                override fun measureGlyphOutlineBounds(
                    glyphId: UShort,
                    fontSizePx: Float,
                    style: MathStyle,
                    sourceRange: SourceRange,
                ): MeasuredMathRun {
                    val key = OutlineKey(faceId, glyphId, fontSizePx.toRawBits(), style)
                    calls[key] = calls.getOrElse(key) { 0 } + 1
                    return delegate.measureGlyphOutlineBounds(glyphId, fontSizePx, style, sourceRange)
                }
            }

            val result = MathLayoutEngine(countingFace).layout(
                "x+x+x+x+x",
                MathLayoutOptions(fontSizePx = 32f),
            )

            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            assertTrue(calls.isNotEmpty())
            assertTrue(calls.values.all { it == 1 }, "outline measurements must be memoized per pass: $calls")
            assertEquals(2, calls.size, "the repeated expression contains only x and plus glyph metrics")
        }
    }

    private data class OutlineKey(
        val faceId: MathFaceId,
        val glyphId: UShort,
        val fontSizeBits: Int,
        val style: MathStyle,
    )
}
