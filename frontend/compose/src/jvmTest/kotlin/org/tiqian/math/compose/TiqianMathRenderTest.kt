package org.tiqian.math.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.skia.SkiaMathFontFamily
import org.tiqian.math.font.skia.SkiaMathFontFace
import org.tiqian.math.font.skia.SkiaReplayCatalog
import org.tiqian.math.font.skia.SkiaReplayFace
import org.tiqian.math.font.skia.SkiaMathTextRunProvider
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathHostTextFaceDecision
import org.tiqian.math.core.SourceRange
import org.tiqian.math.core.MathFontFallbackReason
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.core.MathStyle
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.MathTextRunRequest
import org.tiqian.math.layout.MathTextRunProviderResult
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.breakIntoLines
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class TiqianMathRenderTest {
    @Test
    fun legacySingleFaceRememberApiPreservesTheSurroundingWeightRequest() {
        var observed: MathLayoutResult? = null
        ImageComposeScene(width = 180, height = 100, density = Density(1f)) {
            val face = rememberMathFontFace(LeteSansMath.loadBytes())
            TiqianMath(
                source = "x+1",
                style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
                fontFace = face,
                onMathLayout = { observed = it },
            )
        }.use { it.render() }
        val glyphs = assertNotNull(observed).box.glyphs
        assertTrue(glyphs.all { it.requestedWeight == MathFontWeight.Bold })
        assertTrue(glyphs.all { it.resolvedWeight == MathFontWeight.Regular })
        assertTrue(glyphs.all { it.fallbackReason == MathFontFallbackReason.RequestedWeightUnavailable })
    }

    @Test
    fun surroundingTextStyleWeightSelectsLeteBoldAndForwardsWeightToExplicitHostTextProvider() {
        var regular: MathLayoutResult? = null
        var bold: MathLayoutResult? = null
        SkiaMathFontFamily.loadBundledLete().use { regularFamily ->
            val boldFamily = regularFamily.selectWeight(MathFontWeight.Bold) as SkiaMathFontFamily
            ComposeTestHostTextProvider(
                SkiaMathFontFace(
                    LeteSansMath.load(),
                    MathFaceId("compose-test-host-text"),
                    resolvedWeight = MathFontWeight.Bold,
                    requestedWeight = MathFontWeight.Bold,
                ),
            ).use { provider ->
                ImageComposeScene(width = 520, height = 180, density = Density(1f)) {
                    TiqianMath(
                        "x+\\aleph_0",
                        style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Normal),
                        fontFace = regularFamily,
                        onMathLayout = { regular = it },
                    )
                    TiqianMath(
                        "x+\\text{中文}+x^{中文2}+\\aleph_0",
                        style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
                        fontFace = boldFamily,
                        textRunProvider = provider,
                        onMathLayout = { bold = it },
                    )
                }.use { it.render() }
                val regularGlyphs = assertNotNull(regular).box.glyphs
                val boldGlyphs = assertNotNull(bold).box.glyphs
                assertTrue(regularGlyphs.any { it.faceId.value == "lete-sans-math-regular" })
                assertTrue(boldGlyphs.any { it.faceId.value == "lete-sans-math-bold" })
                val hostGlyphs = boldGlyphs.filter { it.faceId == provider.faceId }
                assertTrue(hostGlyphs.isNotEmpty())
                assertTrue(hostGlyphs.all { it.requestedWeight == MathFontWeight.Bold })
                assertTrue(hostGlyphs.any { it.fontSizePx < 32f }, "script text is shaped at the actual MATH script size")
                assertTrue(boldGlyphs.any {
                    it.faceId.value == "lete-sans-math-regular" &&
                        it.fallbackReason == MathFontFallbackReason.MissingGlyphInRequestedWeight
                }, "Lete Bold's missing aleph falls back to Lete Regular without changing the formula weight request")
            }
        }
    }

    @Test
    fun collidingHostFaceIdUsesOnlyFallbackBeforeAnyMathPaint() {
        SkiaMathFontFamily.loadBundledLete().use { math ->
            ComposeTestHostTextProvider(
                SkiaMathFontFace(
                    LeteSansMath.load(),
                    MathFaceId("lete-sans-math-regular"),
                ),
            ).use { provider ->
                var captured: org.tiqian.math.layout.MathFormulaCapabilityResult.FallbackRequired? = null
                ImageComposeScene(width = 100, height = 70, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMathOrFallback(
                            source = "\\text{中}",
                            fontFace = math,
                            textRunProvider = provider,
                            fallback = {
                                captured = it
                                Canvas(Modifier.size(50.dp, 30.dp)) { drawRect(Color.Magenta) }
                            },
                        )
                    }
                }.use { scene ->
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    var magenta = 0
                    var dark = 0
                    repeat(pixels.height) { y ->
                        repeat(pixels.width) { x ->
                            val pixel = pixels[x, y]
                            if (pixel.red > 0.8f && pixel.blue > 0.8f && pixel.green < 0.2f) magenta++
                            if (pixel.red < 0.3f && pixel.green < 0.3f && pixel.blue < 0.3f) dark++
                        }
                    }
                    assertEquals(50 * 30, magenta)
                    assertEquals(0, dark, "conflicting face ownership must not paint Tiqian glyphs")
                }
                val fallback = assertNotNull(captured)
                assertTrue(fallback.diagnostics.any {
                    it.code == org.tiqian.math.core.DiagnosticCode.ReplayFaceOwnershipConflict
                })
            }
        }
    }

    @Test
    fun explicitTeXNullDelimiterSpaceReachesTheFractionNoad() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var observed: MathLayoutResult? = null
            ImageComposeScene(width = 180, height = 140, density = Density(1f)) {
                TiqianMath(
                    source = "\\frac{a}{b}",
                    fontSizePx = 32f,
                    nullDelimiterSpacePx = 1.5940225f,
                    fontFace = face,
                    onMathLayout = { observed = it },
                )
            }.use { scene ->
                scene.render()
                val decision = assertNotNull(observed).decisions.single {
                    it.name == "TeXFractionNullDelimiters"
                }
                assertNear(1.5940225f, decision.details.getValue("leftSpacePx").toFloat())
                assertNear(1.5940225f, decision.details.getValue("rightSpacePx").toFloat())
            }
        }
    }

    @Test
    fun explicitTeXScriptSpaceReachesTheSharedSideScriptKernel() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var observed: MathLayoutResult? = null
            ImageComposeScene(width = 180, height = 140, density = Density(1f)) {
                TiqianMath(
                    source = "\\int_0^1",
                    fontSizePx = 32f,
                    scriptSpacePx = 0.66417605f,
                    fontFace = face,
                    onMathLayout = { observed = it },
                )
            }.use { scene ->
                scene.render()
                val decision = assertNotNull(observed).decisions.single {
                    it.name == "OpenTypeMathScriptPlacement"
                }
                assertNear(0.66417605f, decision.details.getValue("spaceAfterScriptPx").toFloat())
                assertEquals("ExplicitTeXScriptSpace", decision.details["spaceAfterScriptPolicy"])
            }
        }
    }

    @Test
    fun actualRendererReplaysEachRadicalAsOneCachedConstructionPath() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var observed: MathLayoutResult? = null
            ImageComposeScene(width = 420, height = 280, density = Density(1f)) {
                Box(Modifier.fillMaxSize()) {
                    TiqianMath(
                        source = "\\sqrt[3]{\\frac{a+b}{\\sqrt{x}}}",
                        modifier = Modifier.padding(12.dp),
                        mode = MathMode.Display,
                        fontSizePx = 48f,
                        fontFace = face,
                        color = Color.Black.copy(alpha = 0.5f),
                        onMathLayout = { observed = it },
                    )
                }
            }.use { scene ->
                val firstPixels = scene.render().toComposeImageBitmap().toPixelMap()
                val layout = assertNotNull(observed)
                assertEquals(2, layout.box.constructionPaintGroups.size)
                var maximumAlpha = 0f
                for (y in 0 until firstPixels.height) for (x in 0 until firstPixels.width) {
                    maximumAlpha = maxOf(maximumAlpha, firstPixels[x, y].alpha)
                }
                assertTrue(maximumAlpha in 0.45f..0.53f, "one translucent paint pass prevents overlap darkening")
                val afterFirst = face.constructionOutlineCacheStats()
                assertEquals(2, afterFirst.builds)
                assertEquals(2, afterFirst.entries)

                layout.box.constructionPaintGroups.forEach { group ->
                    face.constructionOutline(layout.box, group)
                }
                val afterReplay = face.constructionOutlineCacheStats()
                assertEquals(afterFirst.builds, afterReplay.builds, "replay performs no PathOps rebuild")
                assertTrue(afterReplay.hits >= afterFirst.hits + 2, "each construction path is replayed from cache")
            }
        }
    }

    @Test
    fun actualRendererReplaysContentDrivenDelimitersAsCachedConstructionPaths() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var observed: MathLayoutResult? = null
            val source = "\\left\\langle a\\middle|\\frac{\\frac{\\frac{x}{y}}{y}}{y}\\right\\rangle"
            ImageComposeScene(width = 520, height = 420, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    TiqianMath(
                        source = source,
                        modifier = Modifier.padding(16.dp),
                        mode = MathMode.Display,
                        fontSizePx = 40f,
                        nullDelimiterSpacePx = 1.2f * 96f / 72.27f,
                        delimiterFactor = 901,
                        delimiterShortfallPx = 5f * 96f / 72.27f,
                        fontFace = face,
                        color = Color.Black.copy(alpha = 0.5f),
                        onMathLayout = { observed = it },
                    )
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                val layout = assertNotNull(observed)
                assertEquals(3, layout.box.constructionPaintGroups.count {
                    it.kind == org.tiqian.math.core.MathConstructionPaintKind.Delimiter
                })
                assertTrue(layout.decisions.any { it.name == "TeXContentDrivenDelimitedGroup" })
                var maximumAlpha = 0f
                var darkPixels = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val pixel = pixels[x, y]
                    if (pixel.red < 0.8f) darkPixels++
                    maximumAlpha = maxOf(maximumAlpha, 1f - pixel.red)
                }
                assertTrue(darkPixels > 300, "delimiter formula was actually rasterized")
                assertTrue(maximumAlpha in 0.45f..0.53f, "assembly overlap is union-painted once")
                val stats = face.constructionOutlineCacheStats()
                assertEquals(layout.box.constructionPaintGroups.size, stats.builds.toInt())
                layout.box.constructionPaintGroups.forEach { face.constructionOutline(layout.box, it) }
                assertTrue(face.constructionOutlineCacheStats().hits >= layout.box.constructionPaintGroups.size)
            }
        }
    }

    @Test
    fun actualRendererReplaysTextOperatorAccentsAndRuleDecorationsFromOneLayoutResult() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("compose-standalone-text"),
                LeteSansMath.loadBytes(),
            ).use { textProvider ->
                var observed: MathLayoutResult? = null
                val source = "\\text{rate }+\\operatorname{rank}_A+\\vec{abcdefghijklmno}+" +
                    "\\overline{x+y}+\\underline{\\frac{a}{b}}"
                ImageComposeScene(width = 900, height = 260, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = source,
                            modifier = Modifier.padding(16.dp),
                            mode = MathMode.Display,
                            fontSizePx = 40f,
                            fontFace = face,
                            textRunProvider = textProvider,
                            color = Color.Black.copy(alpha = 0.5f),
                            onMathLayout = { observed = it },
                        )
                    }
                }.use { scene ->
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    val layout = assertNotNull(observed)
                    assertTrue(layout.diagnostics.isEmpty(), layout.diagnostics.toString())
                    assertTrue(layout.box.glyphs.any { it.faceId == textProvider.faceId })
                    assertTrue(layout.decisions.any { it.name == "TeXEmbeddedText" })
                    assertTrue(layout.decisions.any { it.name == "TeXDeclaredOperatorName" })
                    assertTrue(layout.decisions.any { it.name == "OpenTypeMathAccent" })
                    assertEquals(2, layout.decisions.count { it.name == "OpenTypeMathRuleDecoration" })
                    val accentGroups = layout.box.constructionPaintGroups.filter {
                        it.kind == org.tiqian.math.core.MathConstructionPaintKind.Accent
                    }
                    assertTrue(accentGroups.isNotEmpty(), "wide vector should exercise horizontal assembly replay")
                    var maximumAlpha = 0f
                    var painted = 0
                    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                        val alpha = 1f - pixels[x, y].red
                        maximumAlpha = maxOf(maximumAlpha, alpha)
                        if (alpha > 0.15f) painted++
                    }
                    assertTrue(painted > 500, "all extended structures were rasterized")
                    assertTrue(maximumAlpha in 0.45f..0.53f, "assembly overlap is union-painted once")
                    val stats = face.constructionOutlineCacheStats()
                    assertTrue(stats.entries >= accentGroups.size)
                }
            }
        }
    }

    @Test
    fun renderPlanMeasuresInkOverhangAndUsesSafeLogicalBaseline() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val result = MathLayoutEngine(face).layout("x", MathLayoutOptions(MathMode.Inline, 40f))
            val plan = RenderPlan.unbroken(result)
            assertNear(result.box.visualWidth, plan.width)
            assertNear(-result.box.visualLeft, plan.boxes.single().x)
            assertNear(result.lineMetrics.logicalAscentPx, plan.firstBaseline)
            assertNear(result.lineMetrics.logicalHeightPx, plan.height)
            assertTrue(plan.firstBaseline > result.box.ascent, "host baseline includes logical font safety")
            result.box.glyphs.forEach { glyph ->
                val left = plan.boxes.single().x + glyph.inkBounds.left
                val right = plan.boxes.single().x + glyph.inkBounds.right
                val top = plan.firstBaseline + glyph.inkBounds.top
                val bottom = plan.firstBaseline + glyph.inkBounds.bottom
                assertTrue(left >= -0.02f && right <= plan.width + 0.02f, "horizontal ink is measured")
                assertTrue(top >= -0.02f && bottom <= plan.height + 0.02f, "vertical ink is inside safe line extents")
            }
        }
    }

    @Test
    fun composeMeasuresAndDrawsTheEngineOwnedGlyphAndRulePlacements() {
        var observed: MathLayoutResult? = null
        ImageComposeScene(width = 300, height = 180) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                TiqianMath(
                    source = "x_1^2+\\frac{a+b}{\\binom{n}{k}}=y_2^3",
                    modifier = Modifier.width(180.dp),
                    fontSizePx = 30f,
                    onMathLayout = { observed = it },
                )
            }
        }.use { scene ->
            val pixels = scene.render().toComposeImageBitmap().toPixelMap()
            var dark = 0
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val color = pixels[x, y]
                    if (color.red < 0.35f && color.green < 0.35f && color.blue < 0.35f) dark++
                }
            }
            val layout = assertNotNull(observed)
            assertTrue(layout.box.glyphs.isNotEmpty())
            assertTrue(layout.box.rules.isNotEmpty())
            assertTrue(layout.breakOpportunities.isNotEmpty())
            assertTrue(dark > 250, "expected replayed glyph/rule ink, got $dark dark pixels")
        }
    }

    @Test
    fun localTextStyleDensityFontScaleAndPaddingReachActualComposeMeasurement() {
        val density = Density(density = 2f, fontScale = 1.5f)
        val style = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, color = Color.Black)
        val plain = measureInCompose("x", style, density, Modifier)
        val padded = measureInCompose("x", style, density, Modifier.padding(4.dp))

        assertNear(60f, plain.layout.box.glyphs.single().fontSizePx)
        assertEquals(84, plain.measurement.height, "28sp consumes density=2 and fontScale=1.5")
        assertTrue(plain.measurement.firstBaseline in 1 until plain.measurement.height)
        assertEquals(plain.measurement.width + 16, padded.measurement.width)
        assertEquals(plain.measurement.height + 16, padded.measurement.height)
        assertEquals(plain.measurement.firstBaseline + 8, padded.measurement.firstBaseline)
    }

    @Test
    fun realInlineTextHostUsesFirstBaselineAndOnlyTallFormulaExpandsTheRow() {
        val style = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, color = Color.Black)
        val simple = measureInlineHost("x", style)
        val tall = measureInlineHost("\\frac{x}{y}", style)

        assertEquals(simple.textBaseline, simple.formulaBaseline, "Row aligns actual FirstBaseline values")
        assertEquals(40, simple.formulaHeight, "simple symbol fits requested text line height")
        assertTrue(
            simple.rowHeight <= simple.textHeight + 1,
            "simple inline math differs from the text row only by integer baseline rounding",
        )
        assertEquals(tall.textBaseline, tall.formulaBaseline, "tall formula still shares the host baseline")
        assertTrue(tall.formulaHeight > simple.formulaHeight, "fraction expands by intrinsic safe geometry")
        assertTrue(tall.rowHeight > simple.rowHeight)
    }

    @Test
    fun actualComposeMultilineBoundsBaselinePaddingAndRasterAreConsistent() {
        val style = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, color = Color.Black)
        var measured: MeasureSnapshot? = null
        var observed: MathLayoutResult? = null
        val scene = ImageComposeScene(width = 140, height = 260, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                CompositionLocalProvider(LocalTextStyle provides style) {
                    MeasureProbe(onMeasured = { measured = it }) {
                        TiqianMath(
                            source = "a+b+c+d+e+f",
                            modifier = Modifier.width(120.dp).padding(8.dp),
                            onMathLayout = { observed = it },
                        )
                    }
                }
            }
        }
        scene.use {
            val pixels = it.render().toComposeImageBitmap().toPixelMap()
            val snapshot = assertNotNull(measured)
            val layout = assertNotNull(observed)
            val broken = layout.breakIntoLines(104f)
            assertTrue(broken.lines.size > 1)
            assertEquals(120, snapshot.width)
            assertTrue(snapshot.height > 2 * 38, "multiple actual Compose lines are measured")
            assertTrue(snapshot.firstBaseline >= 8 && snapshot.firstBaseline < snapshot.height - 8)

            val ink = darkPixelBounds(pixels)
            assertTrue(
                ink.left >= 8 && ink.right <= snapshot.width - 8,
                "horizontal padding and overhang are measured: ink=$ink size=$snapshot",
            )
            assertTrue(ink.top >= 8 && ink.bottom < snapshot.height - 8, "vertical ink is not cropped")
            assertTrue(darkRowBands(pixels, snapshot.width, snapshot.height).size >= 2, "raster contains multiple separated math lines")
        }
    }

    @Test
    fun explicitDisplayRowsReachComposeMeasureAndReplayWithoutSyntheticLineBreaking() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var measured: MeasureSnapshot? = null
            var observed: MathLayoutResult? = null
            val scene = ImageComposeScene(width = 360, height = 260, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    MeasureProbe(onMeasured = { measured = it }) {
                        TiqianMath(
                            source = "\\begin{align*}a&=b\\\\[.2cm]c&=\\frac{d}{e}\\end{align*}",
                            modifier = Modifier.padding(12.dp),
                            mode = MathMode.Display,
                            fontFace = face,
                            style = TextStyle(fontSize = 32.sp, lineHeight = 44.sp, color = Color.Black),
                            softWrap = false,
                            onMathLayout = { observed = it },
                        )
                    }
                }
            }
            scene.use {
                val pixels = it.render().toComposeImageBitmap().toPixelMap()
                val snapshot = assertNotNull(measured)
                val layout = assertNotNull(observed)
                assertTrue(layout.diagnostics.isEmpty(), layout.diagnostics.toString())
                assertTrue(layout.decisions.any { decision ->
                    decision.name == "MarkdownMathDisplayEnvironment" &&
                        decision.details["layoutRole"] == "DisplayAlignment"
                })
                assertTrue(layout.decisions.any { decision -> decision.name == "TeXExplicitRowSpacing" })
                assertTrue(snapshot.height > 80, "explicit rows contribute their completed TeX box")
                val ink = darkPixelBounds(pixels)
                assertTrue(ink.left >= 12 && ink.top >= 12, "display wrapper respects Compose padding: $ink")
                assertTrue(darkRowBands(pixels, 360, 260).size >= 2, "both explicit rows are replayed")
            }
        }
    }

    @Test
    fun topLevelMarkdownDisplayRowsReachTheSameComposeReplayPath() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var measured: MeasureSnapshot? = null
            var observed: MathLayoutResult? = null
            val scene = ImageComposeScene(width = 360, height = 260, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    MeasureProbe(onMeasured = { measured = it }) {
                        TiqianMath(
                            source = "a=b\\\\[.2cm]c=\\frac{d}{e}",
                            modifier = Modifier.padding(12.dp),
                            mode = MathMode.Display,
                            fontFace = face,
                            style = TextStyle(fontSize = 32.sp, lineHeight = 44.sp, color = Color.Black),
                            softWrap = false,
                            onMathLayout = { observed = it },
                        )
                    }
                }
            }
            scene.use {
                val pixels = it.render().toComposeImageBitmap().toPixelMap()
                val snapshot = assertNotNull(measured)
                val layout = assertNotNull(observed)
                assertTrue(layout.diagnostics.isEmpty(), layout.diagnostics.toString())
                assertTrue(layout.decisions.any { decision ->
                    decision.name == "MarkdownExplicitDisplayRows" &&
                        decision.details["rowCount"] == "2"
                })
                assertTrue(snapshot.height > 80, "explicit top-level rows affect actual Compose measure")
                val ink = darkPixelBounds(pixels)
                assertTrue(ink.left >= 12 && ink.top >= 12, "raw display rows respect padding: $ink")
                assertTrue(darkRowBands(pixels, 360, 260).size >= 2, "both raw display rows are replayed")
            }
        }
    }

    @Test
    fun displayOperatorLimitsReachActualComposeMeasureBaselineAndRaster() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var measured: MeasureSnapshot? = null
            var observed: MathLayoutResult? = null
            ImageComposeScene(width = 360, height = 260, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    CompositionLocalProvider(
                        LocalTextStyle provides TextStyle(fontSize = 40.sp, lineHeight = 48.sp, color = Color.Black),
                    ) {
                        MeasureProbe(onMeasured = { measured = it }) {
                            TiqianMath(
                                source = "\\sum_i^n+\\int\\limits_0^1",
                                modifier = Modifier.padding(10.dp),
                                mode = MathMode.Display,
                                fontFace = face,
                                onMathLayout = { observed = it },
                            )
                        }
                    }
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                val snapshot = assertNotNull(measured)
                val layout = assertNotNull(observed)
                assertTrue(layout.decisions.count { it.name == "TeXOperatorNoad" } == 2)
                assertTrue(layout.decisions.count { it.name == "OpenTypeMathOperatorLimits" } == 2)
                assertTrue(snapshot.firstBaseline in 10 until snapshot.height - 10)
                assertTrue(snapshot.height > 48, "stacked limits expand actual Compose height")
                val ink = darkPixelBounds(pixels)
                assertTrue(ink.left >= 10 && ink.right < snapshot.width - 10, "operator ink is not horizontally cropped")
                assertTrue(ink.top >= 10 && ink.bottom < snapshot.height - 10, "operator limits are not vertically cropped")
            }
        }
    }

    @Test
    fun indexedNestedRadicalRulesReachActualComposeMeasureBaselineAndRaster() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var measured: MeasureSnapshot? = null
            var observed: MathLayoutResult? = null
            ImageComposeScene(width = 440, height = 360, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    CompositionLocalProvider(
                        LocalTextStyle provides TextStyle(fontSize = 42.sp, lineHeight = 50.sp, color = Color.Black),
                    ) {
                        MeasureProbe(onMeasured = { measured = it }) {
                            TiqianMath(
                                source = "\\sqrt[3]{\\frac{a+b}{\\sqrt{x}}}",
                                modifier = Modifier.padding(12.dp),
                                mode = MathMode.Display,
                                fontFace = face,
                                onMathLayout = { observed = it },
                            )
                        }
                    }
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                val snapshot = assertNotNull(measured)
                val layout = assertNotNull(observed)
                assertEquals(2, layout.decisions.count { it.name == "TeXRadicalNoad" })
                assertEquals(2, layout.decisions.count { it.name == "OpenTypeRadicalConstruction" })
                assertEquals(3, layout.box.rules.size, "two radical rules and one fraction rule are replayed")
                assertTrue(snapshot.firstBaseline in 12 until snapshot.height - 12)
                assertTrue(snapshot.height > 50, "nested radical expands actual Compose height")

                val ink = darkPixelBounds(pixels)
                assertTrue(ink.left >= 12 && ink.right < snapshot.width - 12, "radical ink is not horizontally cropped")
                assertTrue(ink.top >= 12 && ink.bottom < snapshot.height - 12, "radical ink is not vertically cropped")
                val outerRule = layout.box.rules.minBy { it.top }
                assertRuleRasterMatchesPlacement(
                    pixels = pixels,
                    ruleLeft = 12f - layout.box.visualLeft + outerRule.left,
                    ruleRight = 12f - layout.box.visualLeft + outerRule.right,
                    ruleTop = snapshot.firstBaseline + outerRule.top,
                    ruleBottom = snapshot.firstBaseline + outerRule.bottom,
                )
            }
        }
    }

    @Test
    fun extensibleArrowAndOverUnderStacksReplayTheSharedLayoutWithoutCropping() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var measured: MeasureSnapshot? = null
            var observed: MathLayoutResult? = null
            ImageComposeScene(width = 640, height = 260, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    MeasureProbe(onMeasured = { measured = it }) {
                        TiqianMath(
                            source = "X\\xrightarrow[k-1]{p_k}Y+\\overset{u}{=}+\\underset{d}{x}",
                            modifier = Modifier.padding(14.dp),
                            mode = MathMode.Display,
                            fontFace = face,
                            style = TextStyle(fontSize = 34.sp, lineHeight = 58.sp, color = Color.Black),
                            onMathLayout = { observed = it },
                        )
                    }
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                val snapshot = assertNotNull(measured)
                val layout = assertNotNull(observed)
                assertTrue(layout.diagnostics.isEmpty(), layout.diagnostics.toString())
                assertEquals(1, layout.decisions.count { it.name == "AmsmathXeTeXExtensibleArrow" })
                assertEquals(2, layout.decisions.count { it.name == "TeXOverUnderNoad" })
                assertEquals(
                    1,
                    layout.box.constructionPaintGroups.count {
                        it.kind == org.tiqian.math.core.MathConstructionPaintKind.ExtensibleArrow
                    },
                )
                assertTrue(snapshot.firstBaseline in 14 until snapshot.height - 14)
                val ink = darkPixelBounds(pixels)
                assertTrue(ink.left >= 14 && ink.right < snapshot.width - 14, "arrow/stack ink is not horizontally cropped")
                assertTrue(ink.top >= 14 && ink.bottom < snapshot.height - 14, "arrow/stack ink is not vertically cropped")
            }
        }
    }
}

private data class MeasureSnapshot(val width: Int, val height: Int, val firstBaseline: Int)
private data class MeasuredFormula(val measurement: MeasureSnapshot, val layout: MathLayoutResult)

@Composable
private fun MeasureProbe(
    onMeasured: (MeasureSnapshot) -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints)
        val baseline = placeable[FirstBaseline]
        onMeasured(MeasureSnapshot(placeable.width, placeable.height, baseline))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun measureInCompose(
    source: String,
    style: TextStyle,
    density: Density,
    modifier: Modifier,
): MeasuredFormula {
    var measured: MeasureSnapshot? = null
    var result: MathLayoutResult? = null
    ImageComposeScene(width = 400, height = 240, density = density) {
        CompositionLocalProvider(LocalTextStyle provides style) {
            MeasureProbe(onMeasured = { measured = it }) {
                TiqianMath(source, modifier = modifier, onMathLayout = { result = it })
            }
        }
    }.use { it.render() }
    return MeasuredFormula(assertNotNull(measured), assertNotNull(result))
}

private data class InlineHostMeasurement(
    val textBaseline: Int,
    val formulaBaseline: Int,
    val formulaHeight: Int,
    val textHeight: Int,
    val rowHeight: Int,
)

@OptIn(ExperimentalComposeUiApi::class)
private fun measureInlineHost(source: String, style: TextStyle): InlineHostMeasurement {
    var measurement: InlineHostMeasurement? = null
    ImageComposeScene(width = 500, height = 240, density = Density(1f)) {
        CompositionLocalProvider(LocalTextStyle provides style) {
            Layout(
                content = {
                    BasicText("正文", style = style)
                    TiqianMath(source)
                    BasicText("继续", style = style)
                },
            ) { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                val baselines = placeables.map { it[FirstBaseline] }
                val rowBaseline = baselines.max()
                val rowDescent = placeables.indices.maxOf { placeables[it].height - baselines[it] }
                val width = placeables.sumOf { it.width }
                val height = rowBaseline + rowDescent
                measurement = InlineHostMeasurement(
                    textBaseline = rowBaseline,
                    formulaBaseline = rowBaseline,
                    formulaHeight = placeables[1].height,
                    textHeight = placeables[0].height,
                    rowHeight = height,
                )
                layout(width, height) {
                    var x = 0
                    placeables.forEachIndexed { index, placeable ->
                        placeable.place(x, rowBaseline - baselines[index])
                        x += placeable.width
                    }
                }
            }
        }
    }.use { it.render() }
    return assertNotNull(measurement)
}

private data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

private fun darkPixelBounds(pixels: androidx.compose.ui.graphics.PixelMap): PixelBounds {
    var left = pixels.width
    var top = pixels.height
    var right = -1
    var bottom = -1
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
        val color = pixels[x, y]
        if (color.red < 0.35f && color.green < 0.35f && color.blue < 0.35f) {
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
    }
    assertTrue(right >= left && bottom >= top, "expected raster ink")
    return PixelBounds(left, top, right, bottom)
}

private fun darkRowBands(pixels: androidx.compose.ui.graphics.PixelMap, width: Int, height: Int): List<IntRange> {
    val active = (0 until height).filter { y ->
        (0 until width).any { x ->
            val color = pixels[x, y]
            color.red < 0.35f && color.green < 0.35f && color.blue < 0.35f
        }
    }
    if (active.isEmpty()) return emptyList()
    val bands = mutableListOf<IntRange>()
    var start = active.first()
    var previous = start
    active.drop(1).forEach { row ->
        if (row > previous + 1) {
            bands += start..previous
            start = row
        }
        previous = row
    }
    bands += start..previous
    return bands
}

private fun assertRuleRasterMatchesPlacement(
    pixels: androidx.compose.ui.graphics.PixelMap,
    ruleLeft: Float,
    ruleRight: Float,
    ruleTop: Float,
    ruleBottom: Float,
) {
    val left = floor(ruleLeft).toInt().coerceAtLeast(0)
    val right = ceil(ruleRight).toInt().coerceAtMost(pixels.width - 1)
    val top = floor(ruleTop).toInt().coerceAtLeast(0)
    val bottom = ceil(ruleBottom).toInt().coerceAtMost(pixels.height - 1)
    val dark = (top..bottom).sumOf { y ->
        (left..right).count { x ->
            val color = pixels[x, y]
            color.red < 0.35f && color.green < 0.35f && color.blue < 0.35f
        }
    }
    assertTrue(
        dark >= (right - left).coerceAtLeast(1),
        "rendered radical rule follows engine placement: dark=$dark rect=$left,$top..$right,$bottom",
    )
}

private fun assertNear(expected: Float, actual: Float) {
    assertTrue(abs(expected - actual) <= 0.03f, "expected $expected, got $actual")
}

private class ComposeTestHostTextProvider(
    private val face: SkiaMathFontFace,
) : MathTextRunProvider, SkiaReplayCatalog, AutoCloseable {
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
                    fontKey = "compose-test-host",
                    requestedWeight = request.requestedWeight,
                    resolvedWeight = face.resolvedWeight,
                    selectionReason = "ComposeTestHostSelection",
                ),
            )
        }))
    }

    override fun replayFace(faceId: MathFaceId): SkiaReplayFace? = face.takeIf { it.faceId == faceId }
    override fun constructionFace(faceId: MathFaceId): SkiaMathFontFace? = null
    override fun close() = face.close()
}
