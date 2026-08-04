package org.tiqian.math.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
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
import org.tiqian.math.font.android.AndroidMathFontFace

@RunWith(AndroidJUnit4::class)
class AndroidTiqianMathDeviceTest {
    @get:Rule
    val compose = createComposeRule()

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

            val image = compose.onNodeWithTag(FormulaTag).assertIsDisplayed().captureToImage().toPixelMap()
            val darkPixels = (0 until image.height).sumOf { y ->
                (0 until image.width).count { x ->
                    val pixel = image[x, y]
                    pixel.red < 0.4f && pixel.green < 0.4f && pixel.blue < 0.4f
                }
            }
            assertTrue(darkPixels > 200, "native Android Path replay produced $darkPixels dark pixels")
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
            compose.onNodeWithContentDescription("Math formula error: UnsupportedSyntax")
                .assertIsDisplayed()
        }
    }
}

private const val FormulaTag = "android-tiqian-math"
