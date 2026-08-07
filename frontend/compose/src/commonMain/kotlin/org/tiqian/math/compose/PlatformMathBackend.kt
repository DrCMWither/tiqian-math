package org.tiqian.math.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathFormulaCapabilityEngine

@Composable
internal expect fun rememberPlatformLeteMathFontFace(): MathComposeFontFace

@Composable
internal expect fun rememberPlatformMathFontFace(fontBytes: ByteArray): MathComposeFontFace

internal expect fun platformFormulaCapabilityEngine(
    face: MathComposeFontFace,
): MathFormulaCapabilityEngine

internal expect fun DrawScope.drawPlatformMathPlan(
    face: MathComposeFontFace,
    plan: RenderPlan,
    color: Color,
)
