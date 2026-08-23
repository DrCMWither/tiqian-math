package org.tiqian.math.font.skia

import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MathFontMeasurementCacheTest {
    @Test
    fun cachesSourceIndependentShapingAndGlyphMetricsByStyleSizeAndBoundsPolicy() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val first = face.shape("x+y", 32f, MathStyle.Text, SourceRange(0, 3))
            val repeated = face.shape("x+y", 32f, MathStyle.Text, SourceRange(20, 23))
            assertSame(first, repeated, "source ownership is attached by layout, not font measurement")

            face.shape("x+y", 32f, MathStyle.Script, SourceRange(0, 3))
            val glyphId = first.glyphs.first().glyphId
            val measured = face.measureGlyph(glyphId, 32f, MathStyle.Text, SourceRange(0, 1))
            val repeatedMeasurement = face.measureGlyph(glyphId, 32f, MathStyle.Script, SourceRange(8, 9))
            assertSame(measured, repeatedMeasurement, "a resolved glyph id is independent of source and ssty")
            face.measureGlyphOutlineBounds(glyphId, 32f, MathStyle.Text, SourceRange(0, 1))
            face.measureGlyphOutlineBounds(glyphId, 32f, MathStyle.Text, SourceRange(8, 9))

            val stats = face.measurementCacheStats()
            assertEquals(2, stats.shapedRuns.entries)
            assertEquals(1, stats.shapedRuns.hits)
            assertEquals(2, stats.shapedRuns.misses)
            assertEquals(2, stats.glyphMeasurements.entries)
            assertEquals(2, stats.glyphMeasurements.hits)
            assertEquals(2, stats.glyphMeasurements.misses)
        }
    }
}
