package org.tiqian.math.font.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.tiqian.math.core.MathConstructionPaintKind
import org.tiqian.math.core.MathConstructionShapeKind
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathHostTextFaceDecision
import org.tiqian.math.core.MathFontFallbackReason
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathGlyphBoundsSource
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.MathTextRunRequest
import org.tiqian.math.layout.MathTextRunProviderResult
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.font.opentype.VerifiedOpenTypeMathSnapshotLoader

@RunWith(AndroidJUnit4::class)
class AndroidMathBackendInstrumentedTest {
    @Test
    fun reportBundledPrebakedFamilyInitialization() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun measure(factory: () -> AndroidMathFontFamily): Long {
            val start = SystemClock.elapsedRealtimeNanos()
            factory().close()
            return SystemClock.elapsedRealtimeNanos() - start
        }

        repeat(3) {
            AndroidMathFontFamily.loadBundledLete(context).close()
        }
        val prebakedNs = LongArray(15)
        repeat(15) { index ->
            prebakedNs[index] = measure { AndroidMathFontFamily.loadBundledLete(context) }
        }
        val prebakedMedian = prebakedNs.sorted()[prebakedNs.size / 2]
        Log.i(
            "TiqianMathPrebake",
            "family-init prebakedMedianNs=" + prebakedMedian +
                " prebakedSamples=" + prebakedNs.joinToString(),
        )
        assertTrue(prebakedMedian > 0)
    }

    @Test
    fun bundledAndroidSnapshotAttachesToRuntimeFont() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = context.assets
        listOf(
            AndroidMathFontFamily.LeteRegularAsset to AndroidMathFontFamily.LeteRegularSnapshotAsset,
            AndroidMathFontFamily.LeteBoldAsset to AndroidMathFontFamily.LeteBoldSnapshotAsset,
        ).forEach { (fontPath, snapshotPath) ->
            val bytes = assets.open(fontPath).use { it.readBytes() }
            val prebaked = VerifiedOpenTypeMathSnapshotLoader.load(
                bytes,
                assets.open(snapshotPath).use { it.readBytes() },
            )
            assertTrue(prebaked.constants.axisHeight > 0)
            assertTrue(prebaked.characterGlyphs.isNotEmpty())
        }
    }

    @Test
    fun bundledAndroidSnapshotRejectsMismatchedFontBytesBeforeNativeFaceCreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = context.assets
        val bytes = assets.open(AndroidMathFontFamily.LeteRegularAsset).use { it.readBytes() }
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()

        val failure = assertFailsWith<IllegalStateException> {
            AndroidMathFontFace.fromPrebakedBytes(
                bytes,
                assets.open(AndroidMathFontFamily.LeteRegularSnapshotAsset).use { it.readBytes() },
                MathFaceId("mismatched-bundled-face"),
                org.tiqian.math.core.MathFontClass.SansSerif,
                MathFontWeight.Regular,
            )
        }
        assertTrue(failure.message.orEmpty().contains("SHA-256 mismatch"))
    }

    @Test
    fun mathFaceCachesSourceIndependentNativeShapingAndGlyphMetrics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidMathFontFace.loadLete(context).use { face ->
            val first = face.shape("x+y", 32f, MathStyle.Text, SourceRange(0, 3))
            assertSame(first, face.shape("x+y", 32f, MathStyle.Text, SourceRange(20, 23)))
            face.shape("x+y", 32f, MathStyle.Script, SourceRange(0, 3))

            val glyphId = first.glyphs.first().glyphId
            val measured = face.measureGlyph(glyphId, 32f, MathStyle.Text, SourceRange(0, 1))
            assertSame(measured, face.measureGlyph(glyphId, 32f, MathStyle.Script, SourceRange(8, 9)))

            val stats = face.measurementCacheStats()
            assertEquals(2, stats.shapedRuns.entries)
            assertEquals(1, stats.shapedRuns.hits)
            assertEquals(2, stats.shapedRuns.misses)
            assertEquals(1, stats.glyphMeasurements.entries)
            assertEquals(1, stats.glyphMeasurements.hits)
            assertEquals(1, stats.glyphMeasurements.misses)
        }
    }

    @Test
    fun remainingCorpusCommandsReplayGlyphsAndCancellationStrokeOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidMathFontFamily.loadBundledLete(context).use { math ->
            AndroidMathTextRunProvider.fromBytes(
                faceId = MathFaceId("android-remaining-host-text"),
                fontBytes = context.assets.open(AndroidMathFontFace.LeteAssetPath).use { it.readBytes() },
                resolvedWeight = MathFontWeight.Regular,
            ).use { text ->
                val ready = assertIs<MathFormulaCapabilityResult.Ready>(
                    math.androidFormulaCapabilityEngine(text).evaluate(
                        "\\cancel{x+1}+\\not\\equiv+\\textbf{1}",
                        MathLayoutOptions(fontSizePx = 32f),
                    ),
                )
                val result = ready.layoutResult
                val cancellation = result.box.rules.single {
                    it.paintRole == org.tiqian.math.core.MathRulePaintRole.Cancellation
                }
                assertNotNull(cancellation.lineSegment)
                assertTrue(result.box.glyphs.any { it.glyphId == 629u.toUShort() })
                assertTrue(result.box.glyphs.filter { it.faceId == text.faceId }.all {
                    it.requestedWeight == MathFontWeight.Bold
                })
                assertRasterHasInk(
                    combineAndroidReplayCatalogs(math, text),
                    result,
                    "remaining corpus commands",
                )
            }
        }
    }

    @Test
    fun collidingMathAndHostFaceIdsFailPreflightInsteadOfDrawingTheMathFace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidMathFontFamily.loadBundledLete(context).use { math ->
            AndroidMathTextRunProvider.fromBytes(
                MathFaceId("lete-sans-math-regular"),
                context.assets.open(AndroidMathFontFace.LeteAssetPath).use { it.readBytes() },
            ).use { provider ->
                val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                    math.androidFormulaCapabilityEngine(provider).evaluate("\\text{中}"),
                )
                val diagnostic = fallback.diagnostics.single {
                    it.code == org.tiqian.math.core.DiagnosticCode.ReplayFaceOwnershipConflict
                }
                assertEquals(SourceRange(6, 7), diagnostic.range)
                val combined = combineAndroidReplayCatalogs(math, provider)
                assertEquals(
                    org.tiqian.math.core.MathReplayFaceOwnership.Conflict,
                    combined.replayFaceOwnership(MathFaceId("lete-sans-math-regular")),
                )
                assertEquals(null, combined.replayFace(MathFaceId("lete-sans-math-regular")))
                val lowLevel = MathLayoutEngine(math, textRunProvider = provider).layout("\\text{中}")
                assertFailsWith<IllegalStateException> {
                    AndroidMathRenderer(combined).drawBox(
                        Canvas(Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)),
                        lowLevel.box,
                        0f,
                        lowLevel.box.ascent,
                        Color.BLACK,
                    )
                }
            }
        }
    }

    @Test
    fun explicitAndroidStandaloneProviderRejectsRtlBeforeNativeShaping() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidMathFontFace.loadLete(context).use { math ->
            AndroidMathTextRunProvider.fromBytes(
                MathFaceId("android-restricted-text"),
                context.assets.open(AndroidMathFontFace.LeteAssetPath).use { it.readBytes() },
            ).use { provider ->
                val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                    math.androidFormulaCapabilityEngine(provider).evaluate("\\text{abc אבג}"),
                )
                assertTrue(fallback.diagnostics.any {
                    it.code == org.tiqian.math.core.DiagnosticCode.UnsupportedHostTextShaping
                })
            }
        }
    }

    @Test
    fun explicitSingleFaceHostTextProviderShapesAndReplaysWithoutJoiningTheMathFamily() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidMathFontFace.loadLete(context).use { mathFace ->
            AndroidMathTextRunProvider.fromBytes(
                faceId = MathFaceId("android-explicit-host-text"),
                fontBytes = context.assets.open(AndroidMathFontFace.LeteAssetPath).use { it.readBytes() },
            ).use { textProvider ->
                val ready = assertIs<MathFormulaCapabilityResult.Ready>(
                    mathFace.androidFormulaCapabilityEngine(textProvider).evaluate("\\text{host}+x"),
                )
                val textGlyphs = ready.layoutResult.box.glyphs.filter { it.faceId == textProvider.faceId }
                assertTrue(textGlyphs.isNotEmpty())
                assertTrue(textGlyphs.all { it.fontClass == null })
                assertTrue(textGlyphs.all { it.fallbackReason == null })
                assertTrue(textGlyphs.all { it.hostTextDecision?.selectionReason == "ExplicitStandaloneSingleFace" })
                assertTrue(textGlyphs.all {
                    textProvider.replayFace(it.faceId)?.glyphPath(it.glyphId, it.fontSizePx) != null
                })
                assertRasterHasInk(
                    CombinedAndroidReplayCatalog(mathFace, textProvider),
                    ready.layoutResult,
                    "explicit host text provider",
                )
            }
        }
    }

    @Test
    fun bundledMathFamilyAndExplicitHostTextProviderKeepIndependentReplayOwnership() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidMathFontFamily.loadBundledLete(context).use { regular ->
            val bold = regular.selectWeight(MathFontWeight.Bold) as AndroidMathFontFamily
            AndroidMathTextRunProvider.fromBytes(
                MathFaceId("android-test-host-text"),
                context.assets.open(AndroidMathFontFace.LeteAssetPath).use { it.readBytes() },
                resolvedWeight = MathFontWeight.Bold,
            ).use { provider ->
                val source = "x+\\aleph_0+\\text{中文}+原始+x^{中文2}+\\sqrt{\\frac{\\frac{a}{b}}{c}}"
                val capability = bold.androidFormulaCapabilityEngine(provider).evaluate(
                    source,
                    MathLayoutOptions(MathMode.Display, 40f),
                )
                val result = assertIs<MathFormulaCapabilityResult.Ready>(capability).layoutResult
                assertTrue(result.box.glyphs.any { it.faceId == MathFaceId("lete-sans-math-bold") })
                assertTrue(result.box.glyphs.any {
                    it.faceId == MathFaceId("lete-sans-math-regular") &&
                        it.fallbackReason in setOf(
                            MathFontFallbackReason.MissingGlyphInRequestedWeight,
                            MathFontFallbackReason.MissingMathConstructionInRequestedWeight,
                        )
                })
                val textGlyphs = result.box.glyphs.filter { it.faceId == provider.faceId }
                assertTrue(textGlyphs.isNotEmpty())
                assertTrue(textGlyphs.all { it.requestedWeight == MathFontWeight.Bold })
                assertTrue(textGlyphs.any { it.style.level == org.tiqian.math.core.MathStyleLevel.Script && it.fontSizePx < 40f })
                result.box.glyphs.forEach { glyph ->
                    val replay = provider.replayFace(glyph.faceId) ?: bold.replayFace(glyph.faceId)
                    assertNotNull(replay)
                    assertNotNull(replay.glyphPath(glyph.glyphId, glyph.fontSizePx))
                }
                result.box.constructionPaintGroups.forEach { group ->
                    assertTrue(result.box.glyphs.filter { it.constructionGroupId == group.id }.all { it.faceId == group.faceId })
                    assertNotNull(bold.constructionFace(group.faceId))
                }
                assertRasterHasInk(CombinedAndroidReplayCatalog(bold, provider), result, "Lete weighted family")
            }
        }
    }

    @Test
    fun pinnedNativeBackendShapesMeasuresAndReplaysKnownGlyphsFromTheSameFace() {
        withAcceptanceFaces { oracle ->
            oracle.face.use { face ->
                assertEquals("Android Typeface", face.nativeVersions(), oracle.label)
                val result = requireReady(face, "x+2", MathLayoutOptions(fontSizePx = 40f))
                assertEquals(oracle.xPlusTwoGlyphs, result.box.glyphs.map { it.glyphId }, oracle.label)
                assertTrue(result.box.width > 0f && result.box.ascent > 0f, oracle.label)

                val aleph = requireReady(face, "\\aleph_0", MathLayoutOptions(fontSizePx = 40f))
                assertEquals(oracle.alephGlyph, aleph.box.glyphs.first().glyphId, "${oracle.label}/aleph")
                assertEquals(SourceRange(0, 6), aleph.box.glyphs.first().sourceRange, "${oracle.label}/aleph range")

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
    val alephGlyph: UShort,
)

private inline fun withAcceptanceFaces(block: (AcceptanceFace) -> Unit) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    block(
        AcceptanceFace(
            "Lete Sans Math",
            AndroidMathFontFace.loadLete(instrumentation.targetContext),
            listOf(3650u, 12u, 19u),
            403u,
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
            1252u,
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
    face: AndroidReplayCatalog,
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

private class AndroidTestHostTextProvider(
    private val face: AndroidMathFontFace,
) : MathTextRunProvider, AndroidReplayCatalog, AutoCloseable {
    override fun shapeTextAtom(request: MathTextRunRequest): MathTextRunProviderResult {
        val replacement = buildString { repeat(request.text.length) { append('x') } }
        val run = face.shape(replacement, request.fontSizePx, MathStyle.Text, request.sourceRange)
        return MathTextRunProviderResult.Ready(run.copy(glyphs = run.glyphs.map { glyph ->
            glyph.copy(
                fontClass = null,
                requestedWeight = request.requestedWeight,
                resolvedWeight = face.resolvedWeight,
                fallbackReason = null,
                hostTextDecision = MathHostTextFaceDecision(
                    sourceRange = SourceRange(
                        request.sourceRange.start + glyph.textCluster,
                        (request.sourceRange.start + glyph.textCluster + 1).coerceAtMost(request.sourceRange.endExclusive),
                    ),
                    clusterRangeUtf16 = SourceRange(glyph.textCluster, (glyph.textCluster + 1).coerceAtMost(request.text.length)),
                    hostRole = request.origin.name,
                    faceId = face.faceId,
                    fontKey = "android-test-host",
                    requestedWeight = request.requestedWeight,
                    resolvedWeight = face.resolvedWeight,
                    selectionReason = "AndroidTestHostSelection",
                ),
            )
        }))
    }

    override fun replayFace(faceId: MathFaceId): AndroidReplayFace? = face.takeIf { it.faceId == faceId }
    override fun constructionFace(faceId: MathFaceId): AndroidMathFontFace? = null
    override fun close() = face.close()
}

private class CombinedAndroidReplayCatalog(
    private val math: AndroidReplayCatalog,
    private val text: AndroidReplayCatalog,
) : AndroidReplayCatalog {
    private val combined = combineAndroidReplayCatalogs(math, text)
    override fun replayFace(faceId: MathFaceId): AndroidReplayFace? = combined.replayFace(faceId)
    override fun replayFaceOwnership(faceId: MathFaceId) = combined.replayFaceOwnership(faceId)

    override fun constructionFace(faceId: MathFaceId): AndroidMathFontFace? =
        math.constructionFace(faceId)
}

private const val RasterPadding = 8f
