package org.tiqian.math.font.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.tiqian.math.core.MathConstructionPaintKind
import org.tiqian.math.core.MathConstructionShapeKind
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathGlyphBoundsSource
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

@RunWith(AndroidJUnit4::class)
class AndroidMathBackendInstrumentedTest {
    @Test
    fun pinnedNativeBackendShapesMeasuresAndReplaysKnownGlyphsFromTheSameFace() {
        withAcceptanceFaces { oracle ->
            oracle.face.use { face ->
                assertEquals("FreeType 2.13.2; HarfBuzz 8.3.0", face.nativeVersions(), oracle.label)
                val result = requireReady(face, "x+2", MathLayoutOptions(fontSizePx = 40f))
                assertEquals(oracle.xPlusTwoGlyphs, result.box.glyphs.map { it.glyphId }, oracle.label)
                assertTrue(result.box.width > 0f && result.box.ascent > 0f, oracle.label)

                result.box.glyphs.forEach { placement ->
                    val measured = face.measureGlyph(
                        placement.glyphId,
                        placement.fontSizePx,
                        placement.style,
                        placement.sourceRange,
                    )
                    assertEquals(MathGlyphBoundsSource.Outline, measured.boundsSource, oracle.label)
                    val path = face.glyphPath(placement.glyphId, placement.fontSizePx)
                    assertTrue(path != null && !path.isEmpty, "${oracle.label}/glyph=${placement.glyphId}")
                    val pathBounds = RectF().also { path.computeBounds(it, true) }
                    val measuredBounds = measured.glyphs.single().inkBounds
                    assertNear(measuredBounds.left, pathBounds.left, "${oracle.label} path left")
                    assertNear(measuredBounds.top, pathBounds.top, "${oracle.label} path top")
                    assertNear(measuredBounds.right, pathBounds.right, "${oracle.label} path right")
                    assertNear(measuredBounds.bottom, pathBounds.bottom, "${oracle.label} path bottom")
                }
                assertRasterHasInk(face, result, oracle.label)
            }
        }
    }

    @Test
    fun completeNoadsUseOneReplayableLayoutAndNeverUseAndroidTextGlyphApis() {
        withAcceptanceFaces { oracle ->
            oracle.face.use { face ->
                val sources = listOf(
                    "x_1^2",
                    "\\frac{a}{b}",
                    "\\binom{n}{k}",
                    "\\int\\limits_0^1",
                    "\\oint\\limits_0^1",
                )
                sources.forEach { source ->
                    val result = requireReady(face, source, MathLayoutOptions(fontSizePx = 32f))
                    assertTrue(result.box.glyphs.isNotEmpty(), "${oracle.label}/$source")
                    assertTrue(result.box.width > 0f, "${oracle.label}/$source")
                    assertTrue(result.box.inkBounds.height > 0f, "${oracle.label}/$source")
                    assertTrue(result.box.glyphs.all {
                        face.glyphPath(it.glyphId, it.fontSizePx) != null
                    }, "${oracle.label}/$source every final glyph id has a FreeType path")
                    assertRasterHasInk(face, result, "${oracle.label}/$source")
                }
            }
        }
    }

    @Test
    fun radicalsAndDelimitersCoverBaseVariantAndAssemblyWithCachedOutlineUnion() {
        withAcceptanceFaces { oracle ->
            oracle.face.use { face ->
                val deep = (1..12).fold("x") { value, _ -> "\\frac{$value}{y}" }
                val cases = listOf(
                    ConstructionCase(
                        "radical-base",
                        "\\sqrt[2]{x}",
                        MathMode.Display,
                        MathConstructionPaintKind.Radical,
                        MathConstructionShapeKind.BaseGlyph,
                    ),
                    ConstructionCase(
                        "radical-variant",
                        "\\sqrt[3]{\\frac{a}{b}}",
                        MathMode.Display,
                        MathConstructionPaintKind.Radical,
                        MathConstructionShapeKind.Variant,
                    ),
                    ConstructionCase(
                        "radical-assembly",
                        "\\sqrt[5]{$deep}",
                        MathMode.Display,
                        MathConstructionPaintKind.Radical,
                        MathConstructionShapeKind.Assembly,
                    ),
                    ConstructionCase(
                        "delimiter-base",
                        "\\left(x\\right)",
                        MathMode.Inline,
                        MathConstructionPaintKind.Delimiter,
                        MathConstructionShapeKind.BaseGlyph,
                    ),
                    ConstructionCase(
                        "delimiter-variant",
                        "\\left(\\frac{a}{b}\\right)",
                        MathMode.Inline,
                        MathConstructionPaintKind.Delimiter,
                        MathConstructionShapeKind.Variant,
                    ),
                    ConstructionCase(
                        "delimiter-assembly",
                        "\\left($deep\\right)",
                        MathMode.Display,
                        MathConstructionPaintKind.Delimiter,
                        MathConstructionShapeKind.Assembly,
                    ),
                )
                cases.forEach { case ->
                    val result = requireReady(
                        face,
                        case.source,
                        MathLayoutOptions(mode = case.mode, fontSizePx = 48f),
                    )
                    val groups = result.box.constructionPaintGroups.filter { it.kind == case.kind }
                    assertTrue(groups.isNotEmpty(), "${oracle.label}/${case.label}")
                    assertTrue(
                        groups.any { it.shapeKind == case.shapeKind },
                        "${oracle.label}/${case.label}: ${groups.map { it.shapeKind }}",
                    )
                    groups.forEach { group ->
                        val first = assertIs<AndroidMathConstructionPathResult.Available>(
                            face.constructionPath(result.box, group),
                            "${oracle.label}/${case.label}/group=${group.id}",
                        )
                        assertTrue(!first.path.isEmpty, "${oracle.label}/${case.label}")
                        val second = assertIs<AndroidMathConstructionPathResult.Available>(
                            face.constructionPath(result.box, group),
                        )
                        assertTrue(second.cacheHit, "${oracle.label}/${case.label} union path is cached")
                    }
                    assertRasterHasInk(face, result, "${oracle.label}/${case.label}")
                }
                val stats = face.constructionPathCacheStats()
                assertTrue(stats.entries >= cases.size, "${oracle.label}: $stats")
                assertTrue(stats.hits >= cases.size, "${oracle.label}: $stats")
            }
        }
    }

    @Test
    fun nativeFaceOwnsBytesIsThreadSafeAndRejectsUseAfterClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val face = AndroidMathFontFace.loadLete(context)
        val constructionResult = requireReady(
            face,
            "\\sqrt{\\frac{a}{b}}",
            MathLayoutOptions(mode = MathMode.Display, fontSizePx = 48f),
        )
        val constructionGroup = constructionResult.box.constructionPaintGroups.single {
            it.kind == MathConstructionPaintKind.Radical
        }
        assertIs<AndroidMathConstructionPathResult.Available>(
            face.constructionPath(constructionResult.box, constructionGroup),
        )
        val cachedGlyph = constructionResult.box.glyphs.first()
        assertNotNull(face.glyphPath(cachedGlyph.glyphId, cachedGlyph.fontSizePx))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = List(32) {
                Callable {
                    val run = face.shape("x+1", 32f, MathStyle.Text, SourceRange(0, 3))
                    run.glyphs.map { glyph ->
                        requireNotNull(face.glyphPath(glyph.glyphId, 32f))
                        glyph.glyphId
                    }
                }
            }
            val results = pool.invokeAll(tasks).map { it.get(30, TimeUnit.SECONDS) }
            assertTrue(results.distinct().size == 1, results.toString())
        } finally {
            pool.shutdownNow()
            face.close()
        }
        assertFailsWith<IllegalStateException> {
            face.shape("x", 32f, MathStyle.Text, SourceRange(0, 1))
        }
        assertFailsWith<IllegalStateException> {
            face.glyphPath(cachedGlyph.glyphId, cachedGlyph.fontSizePx)
        }
        assertFailsWith<IllegalStateException> {
            face.constructionPath(constructionResult.box, constructionGroup)
        }
    }
}

private data class AcceptanceFace(
    val label: String,
    val face: AndroidMathFontFace,
    val xPlusTwoGlyphs: List<UShort>,
)

private inline fun withAcceptanceFaces(block: (AcceptanceFace) -> Unit) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    block(
        AcceptanceFace(
            "Lete Sans Math",
            AndroidMathFontFace.loadLete(instrumentation.targetContext),
            listOf(3650u, 12u, 19u),
        ),
    )
    block(
        AcceptanceFace(
            "STIX Two Math",
            AndroidMathFontFace.fromAsset(
                instrumentation.context,
                "org/tiqian/math/fonts/STIXTwoMath-Regular.otf",
            ),
            listOf(3354u, 1196u, 1139u),
        ),
    )
}

private data class ConstructionCase(
    val label: String,
    val source: String,
    val mode: MathMode,
    val kind: MathConstructionPaintKind,
    val shapeKind: MathConstructionShapeKind,
)

private fun requireReady(
    face: AndroidMathFontFace,
    source: String,
    options: MathLayoutOptions,
): MathLayoutResult = when (val capability = face.formulaCapabilityEngine().evaluate(source, options)) {
    is MathFormulaCapabilityResult.Ready -> capability.layoutResult
    is MathFormulaCapabilityResult.FallbackRequired -> error(
        "Expected Android-ready formula $source, got ${capability.reasons}\n${capability.diagnostics}",
    )
}

private fun assertRasterHasInk(
    face: AndroidMathFontFace,
    result: MathLayoutResult,
    label: String,
) {
    val ink = result.box.inkBounds
    val width = ceil(ink.width + RasterPadding * 2f).toInt().coerceAtLeast(1)
    val height = ceil(ink.height + RasterPadding * 2f).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    AndroidMathRenderer(face).drawBox(
        canvas,
        result.box,
        originX = RasterPadding - ink.left,
        baselineFromTop = RasterPadding - ink.top,
        color = Color.BLACK,
    )
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val nonWhite = pixels.count { it != Color.WHITE }
    assertTrue(nonWhite > 20, "$label rendered only $nonWhite non-white pixels")
    bitmap.recycle()
}

private fun assertNear(expected: Float, actual: Float, label: String, epsilon: Float = 0.02f) {
    assertTrue(kotlin.math.abs(expected - actual) <= epsilon, "$label expected=$expected actual=$actual")
}

private const val RasterPadding = 8f
