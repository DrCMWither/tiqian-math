package org.tiqian.math.font.skia

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Point
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextBlobBuilder
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathAlphabet
import org.tiqian.math.core.MathFamily
import org.tiqian.math.core.MathNamedSymbol
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathSymbolIdentity
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathSymbolGlyphRequest
import org.tiqian.math.layout.MeasuredMathGlyph
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.ResolvedMathSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MathSymbolResolutionTest {
    @Test
    fun bothFacesUseRealItalicCmapGlyphsAndPreserveUprightClasses() = withFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val italicX = engine.layout("x", MathLayoutOptions(fontSizePx = 48f))
        val uprightX = engine.layout("\\mathrm{x}", MathLayoutOptions(fontSizePx = 48f))
        val explicitX = engine.layout("𝑥", MathLayoutOptions(fontSizePx = 48f))
        val romanExplicitX = engine.layout("\\mathrm{𝑥}", MathLayoutOptions(fontSizePx = 48f))
        val italicAlpha = engine.layout("\\alpha", MathLayoutOptions(fontSizePx = 48f))
        val explicitItalicAlpha = engine.layout("𝛼", MathLayoutOptions(fontSizePx = 48f))
        val romanScope = engine.layout("\\mathrm{\\alpha x}", MathLayoutOptions(fontSizePx = 48f))
        val uprightAlpha = face.shape("α", 48f, MathStyle.Text, SourceRange(0, 1))

        assertTrue(italicX.diagnostics.isEmpty(), "$label: ${italicX.diagnostics}")
        assertNotEquals(uprightX.box.glyphs.single().glyphId, italicX.box.glyphs.single().glyphId, "$label x")
        assertEquals(italicX.box.glyphs.single().glyphId, explicitX.box.glyphs.single().glyphId, "$label explicit x")
        assertEquals(uprightX.box.glyphs.single().glyphId, romanExplicitX.box.glyphs.single().glyphId, "$label explicit x follows same mathrm pipeline")
        assertNotEquals(uprightAlpha.glyphs.single().glyphId, italicAlpha.box.glyphs.single().glyphId, "$label alpha")
        assertEquals(italicAlpha.box.glyphs.single().glyphId, explicitItalicAlpha.box.glyphs.single().glyphId, "$label explicit alpha semantic normalization")
        assertEquals(italicAlpha.box.glyphs.single().glyphId, romanScope.box.glyphs[0].glyphId, "$label fixed alpha ignores mathrm")
        assertEquals(uprightX.box.glyphs.single().glyphId, romanScope.box.glyphs[1].glyphId, "$label variable x follows mathrm")

        val controls = engine.layout("2+()=\\Gamma", MathLayoutOptions(fontSizePx = 48f))
        listOf("2", "+", "(", ")", "=", "Γ").zip(
            controls.decisions.filter { it.name == "TeXMathSymbolResolution" },
        ).forEach { (text, decision) ->
            val expected = face.shape(text, 48f, MathStyle.Text, SourceRange(0, text.length)).glyphs.single().glyphId
            val actual = decision.details.getValue("glyphIds").toUShort()
            assertEquals(expected, actual, "$label upright $text")
        }
        val italicDecision = italicX.decisions.single { it.name == "TeXMathSymbolResolution" }
        assertEquals("latin-x", italicDecision.details["identity"])
        assertEquals("Letters", italicDecision.details["resolvedFamily"])
        assertEquals("MathNormal", italicDecision.details["resolvedAlphabet"])
        assertEquals("U+1D465", italicDecision.details["backendScalar"])
        val uprightDecision = uprightX.decisions.single { it.name == "TeXMathSymbolResolution" }
        assertEquals("Operators", uprightDecision.details["resolvedFamily"])
        assertEquals("Roman", uprightDecision.details["resolvedAlphabet"])
        assertEquals("U+0078", uprightDecision.details["backendScalar"])
        val romanExplicitDecision = romanExplicitX.decisions.single { it.name == "TeXMathSymbolResolution" }
        assertEquals("Italic", romanExplicitDecision.details["declaredAlphabet"])
        assertEquals("Roman", romanExplicitDecision.details["resolvedAlphabet"])
    }

    @Test
    fun correctedTeXSymbolIdentitiesReachTheBackendForBothFaces() = withFaces { label, face ->
        val source = "-*/:!?\\{\\}\\epsilon\\varepsilon\\phi\\varphi"
        val result = MathLayoutEngine(face).layout(source, MathLayoutOptions(fontSizePx = 48f))
        assertTrue(result.diagnostics.isEmpty(), "$label: ${result.diagnostics}")
        val decisions = result.decisions.filter { it.name == "TeXMathSymbolResolution" }
        assertEquals(
            listOf(
                "U+2212" to "Binary",
                "U+2217" to "Binary",
                "U+002F" to "Ordinary",
                "U+003A" to "Relation",
                "U+0021" to "Closing",
                "U+003F" to "Closing",
                "U+007B" to "Opening",
                "U+007D" to "Closing",
                "U+1D716" to "Ordinary",
                "U+1D700" to "Ordinary",
                "U+1D719" to "Ordinary",
                "U+1D711" to "Ordinary",
            ),
            decisions.map { it.details.getValue("backendScalar") to it.details.getValue("atomClass") },
            label,
        )
    }

    @Test
    fun alephNaughtUsesTheFixedTeXSymbolAndRealFontGlyphForBothFaces() = withFaces { label, face ->
        val result = MathLayoutEngine(face).layout("\\aleph_0", MathLayoutOptions(fontSizePx = 48f))

        assertTrue(result.diagnostics.isEmpty(), "$label: ${result.diagnostics}")
        val decision = result.decisions.first { it.name == "TeXMathSymbolResolution" }
        assertEquals(SourceRange(0, 6), decision.range, label)
        assertEquals("aleph", decision.details["identity"], label)
        assertEquals("Ordinary", decision.details["atomClass"], label)
        assertEquals("Symbols", decision.details["resolvedFamily"], label)
        assertEquals("MathNormal", decision.details["resolvedAlphabet"], label)
        assertEquals("U+2135", decision.details["backendScalar"], label)
        val expectedGlyph: UShort = when (label) {
            "Lete Sans Math" -> 403u
            "STIX Two Math" -> 1252u
            else -> error(label)
        }
        assertEquals(expectedGlyph, result.box.glyphs.first().glyphId, label)

        val roman = MathLayoutEngine(face).layout("\\mathrm{\\aleph}", MathLayoutOptions(fontSizePx = 48f))
        assertTrue(roman.diagnostics.isEmpty(), "$label roman: ${roman.diagnostics}")
        assertEquals(expectedGlyph, roman.box.glyphs.single().glyphId, "$label fixed symbol ignores mathrm")
        assertEquals("U+2135", roman.decisions.single { it.name == "TeXMathSymbolResolution" }.details["backendScalar"])
    }

    @Test
    fun auditedCommonCommandsResolveThroughEachRealFontCmap() = withFaces { label, face ->
        listOf(
            "\\aleph" to 0x2135,
            "\\forall" to 0x2200,
            "\\emptyset" to 0x2205,
            "\\nabla" to 0x2207,
            "\\hbar" to 0x210F,
            "\\ell" to 0x2113,
            "\\cap" to 0x2229,
            "\\cup" to 0x222A,
            "\\setminus" to 0x2216,
            "\\wedge" to 0x2227,
            "\\oplus" to 0x2295,
            "\\notin" to 0x2209,
            "\\subseteq" to 0x2286,
            "\\equiv" to 0x2261,
            "\\parallel" to 0x2225,
            "\\models" to 0x22A8,
            "\\leftarrow" to 0x2190,
            "\\Leftrightarrow" to 0x21D4,
            "\\mapsto" to 0x21A6,
            "\\vartheta" to 0x1D717,
            "\\varpi" to 0x1D71B,
            "\\varrho" to 0x1D71A,
            "\\varsigma" to 0x1D70D,
            "\\Psi" to 0x03A8,
        ).forEach { (source, backendScalar) ->
            val result = MathLayoutEngine(face).layout(source, MathLayoutOptions(fontSizePx = 40f))
            assertTrue(result.diagnostics.isEmpty(), "$label/$source: ${result.diagnostics}")
            val decision = result.decisions.single { it.name == "TeXMathSymbolResolution" }
            assertEquals("U+${backendScalar.toString(16).uppercase().padStart(4, '0')}", decision.details["backendScalar"], "$label/$source")
            assertTrue(result.box.glyphs.single().glyphId != 0.toUShort(), "$label/$source")
        }
    }

    @Test
    fun missingItalicGlyphIsDiagnosedWithoutPerGlyphFallback() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val rejecting = object : MathFontFace by delegate {
                override fun resolveSymbol(
                    request: MathSymbolGlyphRequest,
                    fontSizePx: Float,
                ): ResolvedMathSymbol = if (
                    request.identity == MathSymbolIdentity.LatinLetter('x') &&
                    request.family == MathFamily.Letters &&
                    request.alphabet == MathAlphabet.MathNormal
                ) {
                    ResolvedMathSymbol(MeasuredMathRun(
                        glyphs = listOf(MeasuredMathGlyph(0u, 0f, 0f, MathRect(0f, 0f, 0f, 0f))),
                        width = 0f,
                        ascent = 0f,
                        descent = 0f,
                        missingGlyph = true,
                    ), 0x1D465, supported = true)
                } else {
                    delegate.resolveSymbol(request, fontSizePx)
                }
            }
            val result = MathLayoutEngine(rejecting).layout("x")
            assertEquals(0u, result.box.glyphs.single().glyphId)
            assertTrue(result.diagnostics.any { it.code == DiagnosticCode.MissingGlyph })
            assertTrue(result.debugDump.contains("backendScalar=U+1D465"))
        }
    }

    @Test
    fun recognizedExplicitAlphabetsResolveOnlyAtTheFontBoundary() = withFaces { label, face ->
        listOf(
            "𝐱" to "U+1D431",
            "𝛂" to "U+1D6C2",
            "𝒙" to "U+1D499",
            "𝗑" to "U+1D5D1",
        ).forEach { (source, expectedScalar) ->
            val result = MathLayoutEngine(face).layout(source, MathLayoutOptions(fontSizePx = 48f))
            assertTrue(result.diagnostics.isEmpty(), "$label/$source: ${result.diagnostics}")
            val decision = result.decisions.single { it.name == "TeXMathSymbolResolution" }
            assertEquals(expectedScalar, decision.details["backendScalar"], "$label/$source")
            val expectedGlyph = face.shape(
                source,
                48f,
                MathStyle.Text,
                SourceRange(0, source.length),
            ).glyphs.single().glyphId
            assertEquals(expectedGlyph, result.box.glyphs.single().glyphId, "$label/$source glyph")
        }
    }

    @Test
    fun unsupportedAlphabetAndSymbolPairsAreExplicitAtTheFontBoundary() = withFaces { label, face ->
        val resolved = face.resolveSymbol(
            MathSymbolGlyphRequest(
                identity = MathSymbolIdentity.Named(MathNamedSymbol.Plus),
                family = MathFamily.Operators,
                alphabet = MathAlphabet.BoldItalic,
                style = MathStyle.Text,
                sourceRange = SourceRange(0, 1),
            ),
            48f,
        )
        assertFalse(resolved.supported, label)
        assertEquals('+'.code, resolved.backendScalar, label)
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
