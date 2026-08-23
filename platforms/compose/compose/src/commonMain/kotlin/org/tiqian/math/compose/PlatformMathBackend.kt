package org.tiqian.math.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.core.MathFontFamilySpec

@Composable
internal expect fun rememberPlatformLeteMathFontFace(): MathComposeFontFace

@Composable
internal expect fun rememberPlatformPackagedMathFontFamily(familyId: String): MathComposeFontFace

@Composable
internal expect fun rememberPlatformMathFontFace(fontBytes: ByteArray): MathComposeFontFace

@Composable
internal expect fun rememberPlatformMathFontFamily(spec: MathFontFamilySpec): MathComposeFontFace

internal expect fun platformFormulaCapabilityEngine(
    face: MathComposeFontFace,
    textRunProvider: MathTextRunProvider?,
): MathFormulaCapabilityEngine

internal expect fun DrawScope.drawPlatformMathPlan(
    face: MathComposeFontFace,
    textRunProvider: MathTextRunProvider?,
    plan: RenderPlan,
    color: Color,
)
