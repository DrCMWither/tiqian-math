package org.tiqian.math.demo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tiqian.math.compose.TiqianMath
import org.tiqian.math.core.MathMode
import org.tiqian.math.layout.breakResponsiveDisplayLines

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TiqianMathAndroidDemo() }
    }
}

@Composable
private fun TiqianMathAndroidDemo() {
    MaterialTheme(
        colorScheme = lightColorScheme(
            background = DemoBackground,
            surface = Color.White,
            onBackground = DemoInk,
            onSurface = DemoInk,
            primary = DemoGuide,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DemoBackground)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("提椠数学 · Android", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "响应式 display：运算符置于续行行首，按首行第一个关系运算符的实际墨迹对齐。",
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = DemoSecondary,
            )
            DemoFormulaCard("300 dp 窄栏 · 红线是共同运算符锚点", widthLimit = 300.dp, showGuide = true)
            DemoFormulaCard("当前屏幕宽度 · 整组居中", showGuide = true)
            TaggedFormulaCard()
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DemoFormulaCard(
    label: String,
    widthLimit: androidx.compose.ui.unit.Dp? = null,
    showGuide: Boolean,
) {
    var continuationAnchorPx by remember { mutableFloatStateOf(Float.NaN) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, color = DemoSecondary)
        BoxWithConstraints(
            modifier = Modifier
                .then(if (widthLimit == null) Modifier else Modifier.widthIn(max = widthLimit))
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 14.dp)
                .drawBehind {
                    if (showGuide && continuationAnchorPx.isFinite()) {
                        drawLine(
                            color = DemoGuide.copy(alpha = 0.38f),
                            start = Offset(continuationAnchorPx, 0f),
                            end = Offset(continuationAnchorPx, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                },
        ) {
            val contentWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            TiqianMath(
                source = ResponsiveFormula,
                modifier = Modifier.fillMaxWidth(),
                mode = MathMode.Display,
                style = TextStyle(fontSize = 27.sp, color = DemoInk),
                softWrap = true,
                onMathLayout = { result ->
                    continuationAnchorPx = result
                        .breakResponsiveDisplayLines(contentWidthPx)
                        .continuationAnchorPx
                },
            )
        }
    }
}

@Composable
private fun TaggedFormulaCard() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("boxed 公式 · 框内换行，tag 提升到框外右下", fontSize = 12.sp, color = DemoSecondary)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 14.dp),
        ) {
            TiqianMath(
                source = "\\boxed{$ResponsiveFormula\\tag{50}}",
                modifier = Modifier.fillMaxWidth(),
                mode = MathMode.Display,
                style = TextStyle(fontSize = 27.sp, color = DemoInk),
                softWrap = true,
            )
        }
    }
}

private const val ResponsiveFormula =
    "E_k=(n-1)E_{k-1}+E_{k-2}+" +
        "\\frac{\\sum_{i=1}^{n}i^2}{\\binom{2n}{n}}+" +
        "\\sqrt{\\frac{a+b}{c+d}}=y_2^3"

private val DemoBackground = Color(0xFFF6F3EE)
private val DemoInk = Color(0xFF171512)
private val DemoSecondary = Color(0xFF6F6961)
private val DemoGuide = Color(0xFFC74335)
