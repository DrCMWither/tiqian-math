package org.tiqian.math.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toArgb
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
import org.tiqian.math.core.MathEquationTagPlacement
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathTextOrigin
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
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)

/** Display-mode rendering: equation tags, responsive wrapping, scrolling and insets. */
class TiqianMathDisplayRenderTest {
    @Test
    fun displayEquationTagConsumesTheActualComposeConstraintBeforeLayoutAndPaint() {
        var observed: MathLayoutResult? = null
        val scrollState = ScrollState(0)
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("compose-equation-tag-text"),
                LeteSansMath.loadBytes(),
            ).use { text ->
                ImageComposeScene(width = 440, height = 140, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = "x+y\\tag{1}",
                            modifier = Modifier.width(400.dp),
                            mode = MathMode.Display,
                            fontSizePx = 32f,
                            fontFace = face,
                            textRunProvider = text,
                            displayScrollState = scrollState,
                            softWrap = false,
                            onMathLayout = { observed = it },
                        )
                    }
                }.use { scene ->
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    val result = assertNotNull(observed)
                    assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
                    assertEquals(400f, result.box.width, 0.01f)
                    val tag = result.decisions.single { it.name == "AmsmathEquationTag" }
                    assertEquals(400f, tag.details.getValue("displayWidthPx").toFloat(), 0.01f)
                    assertEquals(0, scrollState.maxValue, "a fitting tagged equation must not scroll")
                    val rightBandHasInk = (350 until 400).any { x ->
                        (0 until pixels.height).any { y -> pixels[x, y].red < 0.5f }
                    }
                    assertTrue(rightBandHasInk, "right-aligned equation tag must be replayed inside the constraint")
                }
            }
        }
    }

    @Test
    fun equationTagPaintsInItsOwnSecondaryColorWhileTheBodyKeepsTheFormulaColor() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("compose-equation-tag-color-text"),
                LeteSansMath.loadBytes(),
            ).use { text ->
                ImageComposeScene(width = 440, height = 140, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = "x+y\\tag{1}",
                            modifier = Modifier.width(400.dp),
                            mode = MathMode.Display,
                            fontSizePx = 32f,
                            color = Color.Black,
                            fontFace = face,
                            textRunProvider = text,
                            softWrap = false,
                            displayEquationTagColor = Color.Red,
                        )
                    }
                }.use { scene ->
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    val redInk = { x: Int, y: Int ->
                        pixels[x, y].let { it.red > 0.6f && it.green < 0.4f && it.blue < 0.4f }
                    }
                    val darkInk = { x: Int, y: Int ->
                        pixels[x, y].let { it.red < 0.4f && it.green < 0.4f && it.blue < 0.4f }
                    }
                    val tagBandRed = (330 until 440).any { x -> (0 until pixels.height).any { y -> redInk(x, y) } }
                    val tagBandDark = (330 until 440).any { x -> (0 until pixels.height).any { y -> darkInk(x, y) } }
                    val bodyBandDark = (0 until 300).any { x -> (0 until pixels.height).any { y -> darkInk(x, y) } }
                    val bodyBandRed = (0 until 300).any { x -> (0 until pixels.height).any { y -> redInk(x, y) } }
                    assertTrue(tagBandRed, "the equation tag must paint in the tag color")
                    assertTrue(!tagBandDark, "no formula-colored ink may remain in the tag band")
                    assertTrue(bodyBandDark, "the body must keep the formula color")
                    assertTrue(!bodyBandRed, "the tag color must not leak into the body")
                }
            }
        }
    }

    @Test
    fun overfullDisplayEquationMovesItsTagBelowBeforeComposeReplay() {
        var observed: MathLayoutResult? = null
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("compose-overfull-equation-tag-text"),
                LeteSansMath.loadBytes(),
            ).use { text ->
                ImageComposeScene(width = 120, height = 120, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = "x+y\\tag{1}",
                            modifier = Modifier.width(80.3.dp),
                            mode = MathMode.Display,
                            fontSizePx = 32f,
                            fontFace = face,
                            textRunProvider = text,
                            softWrap = false,
                            onMathLayout = { observed = it },
                        )
                    }
                }.use { scene ->
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    val result = assertNotNull(observed)
                    assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
                    val tag = result.decisions.single { it.name == "AmsmathEquationTag" }
                    assertEquals("ShiftedBelowRight", tag.details["placement"])
                    assertEquals(32f, tag.details.getValue("tagBaselineY").toFloat(), 0.3f)
                    val upperInk = (0 until 45).any { y ->
                        (0 until 81).any { x -> pixels[x, y].red < 0.5f }
                    }
                    val lowerRightTagInk = (45 until 95).any { y ->
                        (38 until 81).any { x -> pixels[x, y].red < 0.5f }
                    }
                    assertTrue(upperInk, "equation body must be painted")
                    assertTrue(lowerRightTagInk, "shifted equation tag must be painted below at the right edge")
                }
            }
        }
    }

    @Test
    fun responsiveSingleLineEquationLeavesCompletedBodyGapBeforeShiftedTag() {
        val source =
            "E=M-e(M-\\pi)[e^2\\Omega_0(iy)E_0^2(iy)-y^2(M-\\pi)^2]^{-1/2}," +
                "\\{e,M\\}\\in R_2 \\tag{36}\\\\"
        var observed: MathLayoutResult? = null
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("compose-current-zhihu-tag-gap-text"),
                LeteSansMath.loadBytes(),
            ).use { text ->
                ImageComposeScene(width = 416, height = 96, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = source,
                            modifier = Modifier.width(416.dp),
                            mode = MathMode.Display,
                            fontSizePx = 16f,
                            fontFace = face,
                            textRunProvider = text,
                            softWrap = true,
                            onMathLayout = { observed = it },
                        )
                    }
                }.use { scene ->
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    val result = assertNotNull(observed)
                    assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
                    val replay = assertNotNull(result.taggedDisplayReplay)
                    val tag = replay.tags.single()
                    assertEquals(MathEquationTagPlacement.ShiftedBelowRight, tag.placement)
                    val separation = result.decisions.single {
                        it.name == "ShiftedEquationTagVerticalSeparation"
                    }
                    assertEquals("true", separation.details["responsiveElectronicBody"])
                    assertEquals("false", separation.details["responsiveMultilineBody"])
                    assertEquals(8f, separation.details.getValue("resolvedCompletedBoxGapPx").toFloat(), 0.01f)

                    val plan = RenderPlan.unbroken(result)
                    val bodyBottom = ceil(
                        plan.firstBaseline + maxOf(replay.body.descent, replay.body.inkBounds.bottom),
                    ).toInt()
                    val tagTop = floor(
                        plan.firstBaseline + tag.baselineY - maxOf(tag.box.ascent, -tag.box.inkBounds.top),
                    ).toInt()
                    assertTrue(tagTop - bodyBottom >= 7, "bodyBottom=$bodyBottom tagTop=$tagTop")
                    val unexpectedInk = (bodyBottom + 1 until tagTop - 1).firstNotNullOfOrNull { y ->
                        (0 until 416).firstOrNull { x -> pixels[x, y].red <= 0.99f }?.let { x -> x to y }
                    }
                    assertTrue(
                        unexpectedInk == null,
                        "responsive tag must leave an unpainted half-em band; " +
                            "bodyBottom=$bodyBottom tagTop=$tagTop unexpectedInk=$unexpectedInk",
                    )
                }
            }
        }
    }

    @Test
    fun taggedDisplayScrollsOnlyItsBodyAndKeepsTheTagAnchoredToTheViewport() {
        val source = "\\boxed{abcdefghijklmnopqrstuvwxyz}\\tag{49}"
        val scrollState = ScrollState(0)
        var observed: MathLayoutResult? = null
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("compose-scrolling-equation-tag-text"),
                LeteSansMath.loadBytes(),
            ).use { text ->
                ImageComposeScene(width = 240, height = 140, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = source,
                            modifier = Modifier.width(220.dp),
                            mode = MathMode.Display,
                            fontSizePx = 32f,
                            fontFace = face,
                            textRunProvider = text,
                            displayScrollState = scrollState,
                            softWrap = false,
                            onMathLayout = { observed = it },
                        )
                    }
                }.use { scene ->
                    val before = scene.render().toComposeImageBitmap().toPixelMap()
                    val result = assertNotNull(observed)
                    val replay = assertNotNull(result.taggedDisplayReplay)
                    val tag = replay.tags.single()
                    assertTrue(
                        replay.body.glyphs.none { it.hostTextDecision?.hostRole == MathTextOrigin.EquationTag.name },
                        "the TeX boxed field must not own or paint the equation tag",
                    )
                    assertTrue(
                        tag.box.glyphs.any { it.hostTextDecision?.hostRole == MathTextOrigin.EquationTag.name },
                        "the promoted tag must be replayed by the display equation",
                    )
                    assertTrue(scrollState.maxValue > 100, "body must expose real horizontal overflow")
                    val whole = RenderPlan.unbroken(result)
                    val separation = result.decisions.single {
                        it.name == "ShiftedEquationTagVerticalSeparation"
                    }
                    assertEquals("true", separation.details["horizontallyScrollableBody"])
                    assertEquals(16f, separation.details.getValue("minimumSeparationPx").toFloat(), 0.01f)
                    val bodyBottom = ceil(
                        whole.firstBaseline + maxOf(replay.body.descent, replay.body.inkBounds.bottom),
                    ).toInt()
                    val tagCompletedTop = floor(
                        whole.firstBaseline + tag.baselineY -
                            maxOf(tag.box.ascent, -tag.box.inkBounds.top),
                    ).toInt()
                    assertTrue(
                        tagCompletedTop - bodyBottom >= 15,
                        "completed body/tag gap must retain half an em",
                    )
                    val tagLeft = floor(tag.logicalX + tag.box.visualLeft).toInt().coerceAtLeast(0)
                    val tagRight = ceil(tag.logicalX + tag.box.visualRight).toInt().coerceAtMost(220)
                    val tagTop = floor(whole.firstBaseline + tag.baselineY + tag.box.inkBounds.top)
                        .toInt().coerceAtLeast(0)
                    val tagBottom = ceil(whole.firstBaseline + tag.baselineY + tag.box.inkBounds.bottom)
                        .toInt().coerceAtMost(140)
                    val beforeTag = pixelSignature(before, tagLeft, tagTop, tagRight, tagBottom)
                    val beforeBody = inkSignature(before, 0, 0, 180, tagTop.coerceAtLeast(1))
                    assertTrue(beforeTag.any { it != Color.White.toArgb() }, "tag region must contain paint")
                    assertTrue(beforeBody.isNotEmpty(), "body region must contain paint")
                    assertTrue(
                        (bodyBottom + 1 until tagCompletedTop - 1).all { y ->
                            (0 until 220).all { x -> before[x, y].red > 0.99f }
                        },
                        "the completed half-em separation must stay unpainted before scrolling",
                    )

                    runBlocking { scrollState.scrollTo(96) }
                    Snapshot.sendApplyNotifications()
                    // The headless scene applies the scroll placement invalidation on one frame
                    // and exposes the resulting pixels on the next.
                    scene.render(16_000_000L)
                    val after = scene.render(32_000_000L).toComposeImageBitmap().toPixelMap()
                    assertTrue(scrollState.value >= 95, "the injected ScrollState must control the body viewport")
                    val afterTag = pixelSignature(after, tagLeft, tagTop, tagRight, tagBottom)
                    val afterBody = inkSignature(after, 0, 0, 180, tagTop.coerceAtLeast(1))
                    assertEquals(beforeTag, afterTag, "viewport-anchored tag must not follow body scrolling")
                    assertTrue(beforeBody != afterBody, "formula body pixels must move under horizontal scroll")
                    assertTrue(
                        (bodyBottom + 1 until tagCompletedTop - 1).all { y ->
                            (0 until 220).all { x -> after[x, y].red > 0.99f }
                        },
                        "scrolling formula paint must not enter the tag separation band",
                    )
                }
            }
        }
    }

    @Test
    fun taggedDisplayUsesLegalBreaksBeforeEnablingHorizontalOverflow() {
        val source = "a+b+c+d+e+f+g+h+i+j\\tag{9}"
        val scrollState = ScrollState(0)
        var observed: MathLayoutResult? = null
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("compose-soft-wrapped-tag-text"),
                LeteSansMath.loadBytes(),
            ).use { text ->
                ImageComposeScene(width = 240, height = 220, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = source,
                            modifier = Modifier.width(180.dp),
                            mode = MathMode.Display,
                            fontSizePx = 32f,
                            fontFace = face,
                            textRunProvider = text,
                            displayScrollState = scrollState,
                            softWrap = true,
                            onMathLayout = { observed = it },
                        )
                    }
                }.use { scene ->
                    scene.render()
                    val result = assertNotNull(observed)
                    val decision = result.decisions.single { it.name == "TaggedDisplayBodyLineBreak" }
                    assertTrue(decision.details.getValue("lineCount").toInt() > 1)
                    assertEquals(0, decision.details.getValue("overfullLineCount").toInt())
                    assertEquals(0, scrollState.maxValue, "legal wrapping must avoid needless scrolling")
                    assertEquals(
                        MathEquationTagPlacement.ShiftedBelowRight,
                        assertNotNull(result.taggedDisplayReplay).tags.single().placement,
                    )
                }
            }
        }
    }

    @Test
    fun tagInsideBoxIsPromotedWhileTheBoxedMathFieldWrapsWithinOneFrame() {
        val source = "\\boxed{E=a+b+c+d+e+f+g+h+i+j\\tag{12}}"
        val scrollState = ScrollState(0)
        var observed: MathLayoutResult? = null
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("compose-wrapped-boxed-tag-text"),
                LeteSansMath.loadBytes(),
            ).use { text ->
                ImageComposeScene(width = 240, height = 240, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = source,
                            modifier = Modifier.width(180.dp),
                            mode = MathMode.Display,
                            fontSizePx = 32f,
                            fontFace = face,
                            textRunProvider = text,
                            displayScrollState = scrollState,
                            softWrap = true,
                            onMathLayout = { observed = it },
                        )
                    }
                }.use { scene ->
                    scene.render()
                    val result = assertNotNull(observed)
                    assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
                    val breakDecision = result.decisions.single { it.name == "BoxedResponsiveDisplayLineBreak" }
                    assertTrue(breakDecision.details.getValue("lineCount").toInt() > 1)
                    assertEquals(0, breakDecision.details.getValue("overfullLineCount").toInt())
                    assertEquals("64.0", breakDecision.details.getValue("defaultIndentPx"))
                    assertEquals(
                        "DisplayTextStyleTwoEm",
                        breakDecision.details.getValue("defaultIndentPolicy"),
                    )
                    assertEquals(0, scrollState.maxValue, "legal breaks inside boxed content must avoid scrolling")

                    val replay = assertNotNull(result.taggedDisplayReplay)
                    val tag = replay.tags.single()
                    assertEquals(MathEquationTagPlacement.ShiftedBelowRight, tag.placement)
                    val frameRules = replay.body.rules.filter { it.sourceRange == SourceRange(0, 6) }
                    assertEquals(4, frameRules.size, "one completed frame must enclose all wrapped lines")
                    assertTrue(frameRules.maxOf { it.bottom } < tag.baselineY + tag.box.inkBounds.top)
                    assertTrue(replay.body.glyphs.none {
                        it.hostTextDecision?.hostRole == MathTextOrigin.EquationTag.name
                    })
                }
            }
        }
    }

    @Test
    fun currentZhihuBoxedEquationKeepsItsPromotedTagOutsideTheFrame() {
        val source =
            "\\boxed{E=M+e(2-k)\\left[S_{1k}+S_{2k}-\\frac{1}{3}A_{2k}\\right]^{-1/2}, " +
                "\\{e,M\\}\\in R_k,k=1 \\ or\\ 3 \\tag{49}\\\\}"
        var observed: MathLayoutResult? = null
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("compose-current-zhihu-boxed-tag-text"),
                LeteSansMath.loadBytes(),
            ).use { text ->
                ImageComposeScene(width = 416, height = 160, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = source,
                            modifier = Modifier.width(416.dp),
                            mode = MathMode.Display,
                            fontSizePx = 16f,
                            fontFace = face,
                            textRunProvider = text,
                            softWrap = true,
                            onMathLayout = { observed = it },
                        )
                    }
                }.use { scene ->
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    val result = assertNotNull(observed)
                    assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
                    val replay = assertNotNull(result.taggedDisplayReplay)
                    val tag = replay.tags.single()
                    assertEquals(MathEquationTagPlacement.ShiftedBelowRight, tag.placement)
                    val frameRules = replay.body.rules.filter { it.sourceRange == SourceRange(0, 6) }
                    assertEquals(4, frameRules.size)
                    val plan = RenderPlan.unbroken(result)
                    val frameBottom = ceil(plan.firstBaseline + frameRules.maxOf { it.bottom }).toInt()
                    val tagTop = floor(plan.firstBaseline + tag.baselineY + tag.box.inkBounds.top).toInt()
                    assertTrue(tagTop - frameBottom >= 7, "frameBottom=$frameBottom tagTop=$tagTop")
                    // Keep one raster row next to the tag for antialias coverage; the structural
                    // assertion above owns the exact half-em geometry.
                    val unexpectedInk = (frameBottom + 1 until tagTop - 1).firstNotNullOfOrNull { y ->
                        (0 until 416).firstOrNull { x -> pixels[x, y].red <= 0.99f }?.let { x -> x to y }
                    }
                    assertTrue(
                        unexpectedInk == null,
                        "the promoted tag must leave an unpainted half-em band below the box; " +
                            "frameBottom=$frameBottom tagTop=$tagTop unexpectedInk=$unexpectedInk",
                    )
                }
            }
        }
    }

    @Test
    fun composeDensityScalesLatexFboxAbsoluteDimensionsIntoPhysicalPixels() {
        fun boxedDecisionAt(densityValue: Float): org.tiqian.math.core.MathLayoutDecision {
            var observed: MathLayoutResult? = null
            SkiaMathFontFace(LeteSansMath.load()).use { face ->
                ImageComposeScene(width = 320, height = 180, density = Density(densityValue)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = "\\boxed{x}",
                            mode = MathMode.Display,
                            fontSizePx = 32f * densityValue,
                            fontFace = face,
                            softWrap = false,
                            onMathLayout = { observed = it },
                        )
                    }
                }.use { it.render() }
            }
            return assertNotNull(observed).decisions.single { it.name == "AmsmathBoxedNoad" }
        }

        val oneX = boxedDecisionAt(1f)
        val threeX = boxedDecisionAt(3f)
        val oneXRule = oneX.details.getValue("fboxRuleThicknessPx").toFloat()
        val oneXSeparation = oneX.details.getValue("fboxSeparationPx").toFloat()
        assertEquals(oneXRule * 3f, threeX.details.getValue("fboxRuleThicknessPx").toFloat(), 0.001f)
        assertEquals(oneXSeparation * 3f, threeX.details.getValue("fboxSeparationPx").toFloat(), 0.001f)
        assertEquals(0.4f * 96f / 72.27f, oneXRule, 0.001f)
        assertEquals(3f * 96f / 72.27f, oneXSeparation, 0.001f)
    }

    @Test
    fun displayContentInsetReducesTagLayoutWidthButRemainsInsideTheScrollViewport() {
        val source = "a+b+c+d+e+f+g+h+i+j+k+l+m+n+o+p\\tag{3}"
        val scrollState = ScrollState(0)
        var observed: MathLayoutResult? = null
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("compose-inset-tag-text"),
                LeteSansMath.loadBytes(),
            ).use { text ->
                ImageComposeScene(width = 260, height = 160, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = source,
                            modifier = Modifier.width(220.dp),
                            mode = MathMode.Display,
                            fontSizePx = 32f,
                            fontFace = face,
                            textRunProvider = text,
                            displayScrollState = scrollState,
                            displayHorizontalContentInset = 16.dp,
                            softWrap = false,
                            onMathLayout = { observed = it },
                        )
                    }
                }.use { scene ->
                    scene.render()
                    val result = assertNotNull(observed)
                    val replay = assertNotNull(result.taggedDisplayReplay)
                    assertEquals(188f, replay.viewportWidthPx, 0.01f)
                    assertTrue(scrollState.maxValue > 0)
                    assertEquals(188f - replay.tags.single().box.width, replay.tags.single().logicalX, 0.01f)
                }
            }
        }
    }

    @Test
    fun twoRowTaggedDisplayMayUseTheOutsetViewportBeforeItScrolls() {
        val source =
            "E=M+e(2-k)\\left[S_{1k}(0,1,2)+S_{2k}(0,1,2)-\\frac{1}{3}A_{2k}(0,1,2)\\right]^{-1/2}," +
                "\\\\ \\{e,M\\}\\in R_k,k=1 \\ or\\ 3 \\tag{48}"
        val scrollState = ScrollState(0)
        var observed: MathLayoutResult? = null
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("compose-outset-tag-text"),
                LeteSansMath.loadBytes(),
            ).use { text ->
                ImageComposeScene(width = 1344, height = 360, density = Density(3f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = source,
                            modifier = Modifier.width(448.dp),
                            mode = MathMode.Display,
                            fontSizePx = 48f,
                            fontFace = face,
                            textRunProvider = text,
                            displayScrollState = scrollState,
                            displayHorizontalContentInset = 16.dp,
                            softWrap = false,
                            onMathLayout = { observed = it },
                        )
                    }
                }.use { scene ->
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    val replay = assertNotNull(assertNotNull(observed).taggedDisplayReplay)
                    assertEquals(1248f, replay.viewportWidthPx, 0.01f)
                    assertTrue(scrollState.maxValue in 1..48, "only the real body overflow may scroll")
                    assertTrue(
                        (1296 until 1344).any { x ->
                            (0 until pixels.height).any { y -> pixels[x, y].red < 0.5f }
                        },
                        "the initial body must remain visible beyond the article's right edge",
                    )
                }
            }
        }
    }

    @Test
    fun untaggedDisplayOwnsItsHorizontalOverflowWithoutAHostScrollModifier() {
        val scrollState = ScrollState(0)
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            ImageComposeScene(width = 260, height = 120, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    TiqianMath(
                        source = "\\boxed{abcdefghijklmnopqrstuvwxyz}",
                        modifier = Modifier.width(180.dp),
                        mode = MathMode.Display,
                        fontSizePx = 32f,
                        fontFace = face,
                        displayScrollState = scrollState,
                        softWrap = false,
                    )
                }
            }.use { scene ->
                scene.render()
                assertTrue(scrollState.maxValue > 100)

                runBlocking { scrollState.scrollTo(scrollState.maxValue) }
                Snapshot.sendApplyNotifications()
                assertEquals(scrollState.maxValue, scrollState.value)
            }
        }
    }

}
