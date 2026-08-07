package org.tiqian.math.font.skia

import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end checks that the newly added math-alphabet and function-name commands resolve real glyphs. */
class MathBasicFormatsTest {

    @Test
    fun mathAlphabetCommandsResolveRealGlyphs() = withBothFaces { label, engine ->
        // Includes a Letterlike-block hole (ℝ) and a digit-bearing alphabet (𝟽).
        listOf("\\mathbf{x}", "\\mathit{y}", "\\mathsf{z}", "\\mathbb{R}", "\\mathfrak{g}", "\\mathcal{L}", "\\mathtt{7}")
            .forEach { source ->
                val result = engine.layout(source, MathLayoutOptions(MathMode.Inline, 32f))
                assertTrue(result.diagnostics.isEmpty(), "$label $source: ${result.diagnostics}")
                assertTrue(result.box.glyphs.isNotEmpty(), "$label $source produced no glyphs")
            }
    }

    @Test
    fun functionNamesRenderAsOneUprightOperatorUnit() = withBothFaces { label, engine ->
        val sin = engine.layout("\\sin", MathLayoutOptions(MathMode.Inline, 32f))
        assertTrue(sin.diagnostics.isEmpty(), "$label \\sin: ${sin.diagnostics}")
        // "sin" is three upright glyphs, all mapping back to the single \sin command range (0..4).
        assertEquals(3, sin.box.glyphs.size, "$label \\sin glyph count")
        assertTrue(
            sin.box.glyphs.all { it.sourceRange == SourceRange(0, 4) },
            "$label \\sin glyphs must map to the command as one unit: ${sin.box.glyphs.map { it.sourceRange }}",
        )

        // \sin x + \lim_{n} lays out end to end without diagnostics.
        val applied = engine.layout("\\sin x", MathLayoutOptions(MathMode.Inline, 32f))
        assertTrue(applied.diagnostics.isEmpty(), "$label \\sin x: ${applied.diagnostics}")
        val lim = engine.layout("\\lim_{n} x", MathLayoutOptions(MathMode.Inline, 32f))
        assertTrue(lim.diagnostics.isEmpty(), "$label \\lim: ${lim.diagnostics}")
    }

    private inline fun withBothFaces(block: (String, MathLayoutEngine) -> Unit) {
        SkiaMathFontFace(LeteSansMath.load()).use { block("Lete", MathLayoutEngine(it)) }
        SkiaMathFontFace(StixTwoMath.load()).use { block("STIX", MathLayoutEngine(it)) }
    }
}
