package org.tiqian.math.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.skia.SkiaMathFontFace
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.breakIntoLines
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class TiqianMathRenderTest {
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

private fun assertNear(expected: Float, actual: Float) {
    assertTrue(abs(expected - actual) <= 0.03f, "expected $expected, got $actual")
}
