package org.tiqian.math.font.skia

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Point
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextBlobBuilder
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MeasuredMathGlyph
import org.tiqian.math.layout.MeasuredMathRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MathVariantGlyphTest {
    @Test
    fun bothFacesUseRealItalicCmapGlyphsAndPreserveUprightClasses() = withFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val italicX = engine.layout("x", MathLayoutOptions(fontSizePx = 48f))
        val uprightX = engine.layout("\\mathrm{x}", MathLayoutOptions(fontSizePx = 48f))
        val explicitX = engine.layout("𝑥", MathLayoutOptions(fontSizePx = 48f))
        val italicAlpha = engine.layout("\\alpha", MathLayoutOptions(fontSizePx = 48f))
        val uprightAlpha = face.shape("α", 48f, MathStyle.Text, SourceRange(0, 1))

        assertTrue(italicX.diagnostics.isEmpty(), "$label: ${italicX.diagnostics}")
        assertNotEquals(uprightX.box.glyphs.single().glyphId, italicX.box.glyphs.single().glyphId, "$label x")
        assertEquals(italicX.box.glyphs.single().glyphId, explicitX.box.glyphs.single().glyphId, "$label explicit x")
        assertNotEquals(uprightAlpha.glyphs.single().glyphId, italicAlpha.box.glyphs.single().glyphId, "$label alpha")

        val controls = engine.layout("2+()=\\Gamma", MathLayoutOptions(fontSizePx = 48f))
        listOf("2", "+", "(", ")", "=", "Γ").forEach { text ->
            val expected = face.shape(text, 48f, MathStyle.Text, SourceRange(0, text.length)).glyphs.single().glyphId
            val actual = controls.box.glyphs.first { glyph ->
                val selected = controls.decisions.firstOrNull { decision ->
                    decision.name == "MathVariantGlyphSelection" &&
                        decision.details["semantic"] == text &&
                        decision.details["glyphIds"] == glyph.glyphId.toString()
                }
                selected != null
            }.glyphId
            assertEquals(expected, actual, "$label upright $text")
        }
        assertTrue(italicX.debugDump.contains("variant=DefaultVariableItalic"))
        assertTrue(uprightX.debugDump.contains("variant=Upright"))
    }

    @Test
    fun missingItalicGlyphIsDiagnosedWithoutPerGlyphFallback() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val rejecting = object : MathFontFace by delegate {
                override fun shape(
                    text: String,
                    fontSizePx: Float,
                    style: MathStyle,
                    sourceRange: SourceRange,
                ): MeasuredMathRun = if (text == "𝑥") {
                    MeasuredMathRun(
                        glyphs = listOf(MeasuredMathGlyph(0u, 0f, 0f, MathRect(0f, 0f, 0f, 0f))),
                        width = 0f,
                        ascent = 0f,
                        descent = 0f,
                        missingGlyph = true,
                    )
                } else {
                    delegate.shape(text, fontSizePx, style, sourceRange)
                }
            }
            val result = MathLayoutEngine(rejecting).layout("x")
            assertEquals(0u, result.box.glyphs.single().glyphId)
            assertTrue(result.diagnostics.any { it.code == DiagnosticCode.MissingGlyph })
            assertTrue(result.debugDump.contains("glyphText=𝑥"))
        }
    }

    @Test
    fun realRasterAndInkBoundsDistinguishItalicFromUprightForBothFaces() = withFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val italic = engine.layout("x", MathLayoutOptions(fontSizePx = 72f)).box.glyphs.single()
        val upright = engine.layout("\\mathrm{x}", MathLayoutOptions(fontSizePx = 72f)).box.glyphs.single()
        assertNotEquals(italic.inkBounds, upright.inkBounds, "$label glyph ink bounds")

        val italicRaster = rasterProfile(face, italic.glyphId, 72f)
        val uprightRaster = rasterProfile(face, upright.glyphId, 72f)
        assertNotEquals(italicRaster.bounds, uprightRaster.bounds, "$label raster bounds")
        assertTrue(
            kotlin.math.abs(italicRaster.shearPx) > kotlin.math.abs(uprightRaster.shearPx) + 0.35f,
            "$label real raster is more inclined: italic=$italicRaster upright=$uprightRaster",
        )
    }
}

private data class RasterProfile(val bounds: MathRect, val shearPx: Float)

private fun rasterProfile(face: SkiaMathFontFace, glyphId: UShort, size: Float): RasterProfile {
    val surface = Surface.makeRasterN32Premul(120, 110)
    val paint = Paint().apply { color = Color.BLACK }
    val font = face.font(size)
    val builder = TextBlobBuilder()
    try {
        surface.canvas.clear(Color.TRANSPARENT)
        builder.appendRunPos(font, shortArrayOf(glyphId.toShort()), arrayOf(Point(24f, 86f)))
        builder.build()?.use { surface.canvas.drawTextBlob(it, 0f, 0f, paint) }
        val bitmap = Bitmap().apply { allocN32Pixels(120, 110) }
        try {
            assertTrue(surface.readPixels(bitmap, 0, 0))
            val pixels = buildList {
                for (y in 0 until 110) for (x in 0 until 120) {
                    if (bitmap.getAlphaf(x, y) > 0.2f) add(x to y)
                }
            }
            val left = pixels.minOf { it.first }
            val right = pixels.maxOf { it.first }
            val top = pixels.minOf { it.second }
            val bottom = pixels.maxOf { it.second }
            val band = maxOf(2, (bottom - top + 1) / 3)
            val topPixels = pixels.filter { it.second < top + band }
            val bottomPixels = pixels.filter { it.second > bottom - band }
            val topCenter = topPixels.map { it.first }.average().toFloat()
            val bottomCenter = bottomPixels.map { it.first }.average().toFloat()
            return RasterProfile(
                MathRect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat()),
                topCenter - bottomCenter,
            )
        } finally {
            bitmap.close()
        }
    } finally {
        builder.close()
        font.close()
        paint.close()
        surface.close()
    }
}

private inline fun withFaces(block: (String, SkiaMathFontFace) -> Unit) {
    listOf(
        "Lete Sans Math" to LeteSansMath.load(),
        "STIX Two Math" to StixTwoMath.load(),
    ).forEach { (label, font) -> SkiaMathFontFace(font).use { block(label, it) } }
}
