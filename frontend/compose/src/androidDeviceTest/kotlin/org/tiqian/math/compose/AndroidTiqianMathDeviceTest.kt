package org.tiqian.math.compose

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathPaintColor
import org.tiqian.math.core.MathPaintLayer
import org.tiqian.math.core.MathRulePaintRole
import org.tiqian.math.font.android.AndroidMathFontFace
import org.tiqian.math.font.android.AndroidMathFontFamily
import org.tiqian.math.font.android.AndroidReplayCatalog
import org.tiqian.math.font.android.AndroidReplayFace
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathHostTextFaceDecision
import org.tiqian.math.core.MathFontClass
import org.tiqian.math.core.MathFontFallbackReason
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.core.SourceRange
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.MathTextRunRequest
import org.tiqian.math.layout.MathTextRunProviderResult
import org.tiqian.math.layout.MeasuredMathRun

@RunWith(AndroidJUnit4::class)
class AndroidTiqianMathDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun composeTextBackendMeasuresAndReplaysHostTextWithoutAnInjectedProvider() {
        var result: MathLayoutResult? = null
        compose.setContent {
            Box(Modifier.background(Color.White)) {
                TiqianMath(
                    source = "x+\\text{中文 العربية}+原文+y^{\\text{上标}}",
                    style = TextStyle(fontSize = 28.sp, color = Color.Black),
                    textLocale = "zh-CN",
                    modifier = Modifier.testTag(DefaultHostTextFormulaTag),
                    onMathLayout = { result = it },
                )
            }
        }
        compose.waitForIdle()

        val layout = assertNotNull(result)
        assertTrue(layout.diagnostics.isEmpty(), layout.diagnostics.toString())
        assertTrue(layout.box.hostTextRuns.size == 4)
        assertTrue(layout.box.hostTextRuns.any { it.baselineY < 0f })
        compose.onNodeWithTag(DefaultHostTextFormulaTag).assertIsDisplayed()
    }

    @Test
    fun surroundingBoldStyleSelectsNativeBoldAndExplicitHostTextReplayAtApi23Boundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidMathFontFamily.loadBundledLete(context).use { family ->
            val providerFace = AndroidMathFontFace.fromBytes(
                context.assets.open(AndroidMathFontFace.LeteAssetPath).use { it.readBytes() },
                faceId = MathFaceId("compose-android-test-host-text"),
                fontClass = MathFontClass.SansSerif,
                weight = MathFontWeight.Bold,
                requestedWeight = MathFontWeight.Bold,
            )
            DeviceTestHostTextProvider(providerFace).use { provider ->
                var result: MathLayoutResult? = null
                compose.setContent {
                    TiqianMath(
                        source = "x+\\aleph_0+\\text{中文}+x^{中文2}",
                        style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
                        fontFace = family,
                        textRunProvider = provider,
                        modifier = Modifier.testTag(WeightedFormulaTag),
                        onMathLayout = { result = it },
                    )
                }
                compose.waitForIdle()
                val glyphs = assertNotNull(result).box.glyphs
                assertTrue(glyphs.any { it.resolvedWeight == MathFontWeight.Bold && it.faceId.value == "lete-sans-math-bold" })
                val hostGlyphs = glyphs.filter { it.faceId == provider.faceId }
                assertTrue(hostGlyphs.isNotEmpty())
                assertTrue(hostGlyphs.all { it.requestedWeight == MathFontWeight.Bold })
                assertTrue(hostGlyphs.minOf { it.fontSizePx } < hostGlyphs.maxOf { it.fontSizePx })
                assertTrue(glyphs.any {
                    it.requestedWeight == MathFontWeight.Bold && it.resolvedWeight == MathFontWeight.Regular
                })
                compose.onNodeWithTag(WeightedFormulaTag).assertIsDisplayed()
            }
        }
    }

    @Test
    fun productionComposableReplaysNativeLayoutWithBaselineFontScaleAndSoftWrap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidMathFontFace.loadLete(context).use { face ->
            var result: MathLayoutResult? = null
            var firstBaseline = -1
            var measuredHeight = -1
            var expectedFontSizePx = -1f
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.5f)) {
                    expectedFontSizePx = with(LocalDensity.current) { 20.sp.toPx() }
                    Box(Modifier.background(Color.White)) {
                        Layout(
                            content = {
                                TiqianMath(
                                    source = "E_k=(n-1)E_{k-1}+E_{k-2}+\\frac{a}{b}+\\sqrt{x}=y_2^3",
                                    modifier = Modifier.width(90.dp).testTag(FormulaTag),
                                    style = TextStyle(fontSize = 20.sp, color = Color.Black),
                                    softWrap = true,
                                    fontFace = face,
                                    onMathLayout = { result = it },
                                )
                            },
                        ) { measurables, constraints ->
                            val placeable = measurables.single().measure(constraints)
                            firstBaseline = placeable[FirstBaseline]
                            measuredHeight = placeable.height
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                    }
                }
            }
            compose.waitForIdle()

            val layout = assertNotNull(result)
            assertTrue(
                layout.box.glyphs.any { kotlin.math.abs(it.fontSizePx - expectedFontSizePx) < 0.01f },
                "20sp must use LocalDensity font scaling: expected=$expectedFontSizePx " +
                    "sizes=${layout.box.glyphs.map { it.fontSizePx }.distinct()}",
            )
            assertTrue(layout.breakOpportunities.isNotEmpty())
            assertTrue(firstBaseline in 1 until measuredHeight, "baseline=$firstBaseline height=$measuredHeight")
            assertTrue(measuredHeight > layout.lineMetrics.logicalHeightPx, "soft wrapping creates multiple lines")

            compose.onNodeWithTag(FormulaTag).assertIsDisplayed()
            // captureToImage() uses PixelCopy on API 26+; on older APIs WindowCapture's fallback
            // path is missing at runtime (NoClassDefFoundError). The layout assertions above already
            // cover the API 23 native replay, so only verify pixels where capture is supported.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val image = compose.onNodeWithTag(FormulaTag).captureToImage().toPixelMap()
                val darkPixels = (0 until image.height).sumOf { y ->
                    (0 until image.width).count { x ->
                        val pixel = image[x, y]
                        pixel.red < 0.4f && pixel.green < 0.4f && pixel.blue < 0.4f
                    }
                }
                assertTrue(darkPixels > 200, "native Android Path replay produced $darkPixels dark pixels")
            }
        }
    }

    @Test
    fun normalProductionEntryPresentsItsOwnVisibleDiagnostic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidMathFontFace.loadLete(context).use { face ->
            compose.setContent {
                TiqianMath(
                    source = "x+\\text{unsupported}",
                    fontSizePx = 32f,
                    fontFace = face,
                )
            }
            compose.onNodeWithContentDescription("Math formula error: MissingTextProvider")
                .assertIsDisplayed()
        }
    }

    @Test
    fun explicitColorsReplayThroughNativeGlyphRuleAndConstructionPaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidMathFontFace.loadLete(context).use { face ->
            var result: MathLayoutResult? = null
            val formulaMounted = mutableStateOf(true)
            try {
                compose.setContent {
                    if (formulaMounted.value) {
                        Box(Modifier.background(Color.White)) {
                            TiqianMath(
                                source = "{\\color{red}\\boxed{x}}+{\\color{blue}\\sqrt{\\frac{a}{b}}}+" +
                                    "\\bbox[#CAF,5px,border:1px solid green]{y}",
                                fontSizePx = 44f,
                                color = Color.Black,
                                fontFace = face,
                                modifier = Modifier.testTag(ColorFormulaTag),
                                onMathLayout = { result = it },
                            )
                        }
                    }
                }
                compose.waitForIdle()
                val layout = assertNotNull(result)
                assertTrue(layout.box.glyphs.any { it.paintColor == MathPaintColor(255, 0, 0) })
                assertTrue(layout.box.rules.any { it.paintColor == MathPaintColor(255, 0, 0) })
                assertTrue(layout.box.constructionPaintGroups.all { it.paintColor == MathPaintColor(0, 0, 255) })
                assertTrue(layout.box.rules.any {
                    it.paintRole == MathRulePaintRole.BackgroundFill &&
                        it.paintLayer == MathPaintLayer.Background &&
                        it.paintColor == MathPaintColor(204, 170, 255)
                })
                assertTrue(layout.box.rules.count { it.paintRole == MathRulePaintRole.Border } == 4)
                compose.onNodeWithTag(ColorFormulaTag).assertIsDisplayed()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val image = compose.onNodeWithTag(ColorFormulaTag).captureToImage().toPixelMap()
                    val redPixels = (0 until image.height).sumOf { y ->
                        (0 until image.width).count { x ->
                            val pixel = image[x, y]
                            pixel.red > 0.65f && pixel.green < 0.35f && pixel.blue < 0.35f
                        }
                    }
                    val bluePixels = (0 until image.height).sumOf { y ->
                        (0 until image.width).count { x ->
                            val pixel = image[x, y]
                            pixel.blue > 0.65f && pixel.red < 0.35f && pixel.green < 0.35f
                        }
                    }
                    assertTrue(redPixels > 100, "Android fbox replays explicit red: $redPixels")
                    assertTrue(bluePixels > 100, "Android radical construction replays explicit blue: $bluePixels")
                }
            } finally {
                // The caller owns explicit faces. Remove every draw consumer before closing the face;
                // API 23 may record its display list after the semantics assertion has completed.
                compose.runOnIdle { formulaMounted.value = false }
                compose.waitForIdle()
            }
        }
    }
}

private const val DefaultHostTextFormulaTag = "android-default-host-text-formula"
private const val FormulaTag = "android-tiqian-math"
private const val WeightedFormulaTag = "android-weighted-tiqian-math"
private const val ColorFormulaTag = "android-colored-tiqian-math"

private class DeviceTestHostTextProvider(
    private val face: AndroidMathFontFace,
) : MathTextRunProvider, AndroidReplayCatalog, AutoCloseable {
    val faceId: MathFaceId get() = face.faceId

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
                    fontKey = "android-device-test-host",
                    requestedWeight = request.requestedWeight,
                    resolvedWeight = face.resolvedWeight,
                    selectionReason = "AndroidDeviceTestHostSelection",
                ),
            )
        }))
    }

    override fun replayFace(faceId: MathFaceId): AndroidReplayFace? = face.takeIf { it.faceId == faceId }
    override fun constructionFace(faceId: MathFaceId): AndroidMathFontFace? = null
    override fun close() = face.close()
}
