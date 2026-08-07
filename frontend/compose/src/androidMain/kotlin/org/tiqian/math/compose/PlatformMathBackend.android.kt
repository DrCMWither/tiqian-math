package org.tiqian.math.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import org.tiqian.math.font.android.AndroidMathFontFace
import org.tiqian.math.font.android.AndroidMathRenderer
import org.tiqian.math.font.android.formulaCapabilityEngine
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathFormulaCapabilityEngine

@Composable
internal actual fun rememberPlatformLeteMathFontFace(): MathComposeFontFace {
    val context = LocalContext.current.applicationContext
    val face = remember(context) { AndroidMathFontFace.loadLete(context) }
    DisposableEffect(face) { onDispose(face::close) }
    return face
}

@Composable
internal actual fun rememberPlatformMathFontFace(fontBytes: ByteArray): MathComposeFontFace {
    val face = remember(fontBytes) { AndroidMathFontFace.fromBytes(fontBytes) }
    DisposableEffect(face) { onDispose(face::close) }
    return face
}

internal actual fun platformFormulaCapabilityEngine(
    face: MathComposeFontFace,
): MathFormulaCapabilityEngine = (face as? AndroidMathFontFace)?.formulaCapabilityEngine()
    ?: error("Android Compose requires AndroidMathFontFace")

internal actual fun DrawScope.drawPlatformMathPlan(
    face: MathComposeFontFace,
    plan: RenderPlan,
    color: Color,
) {
    val androidFace = face as? AndroidMathFontFace ?: error("Android Compose requires AndroidMathFontFace")
    val renderer = AndroidMathRenderer(androidFace)
    drawIntoCanvas { canvas ->
        plan.boxes.forEach { positioned ->
            renderer.drawBox(
                canvas.nativeCanvas,
                positioned.box,
                positioned.x,
                positioned.baselineFromTop,
                color.toArgb(),
            )
        }
    }
}
