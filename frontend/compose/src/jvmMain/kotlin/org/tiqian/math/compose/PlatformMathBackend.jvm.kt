package org.tiqian.math.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Point
import org.jetbrains.skia.Rect
import org.jetbrains.skia.TextBlobBuilder
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.opentype.OpenTypeMathReader
import org.tiqian.math.font.skia.MathConstructionOutlineResult
import org.tiqian.math.font.skia.MathConstructionOutlineUnavailableException
import org.tiqian.math.font.skia.SkiaMathFontFace
import org.tiqian.math.font.skia.formulaCapabilityEngine
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathFormulaCapabilityEngine

@Composable
internal actual fun rememberPlatformLeteMathFontFace(): MathComposeFontFace {
    val face = remember { SkiaMathFontFace(LeteSansMath.load()) }
    DisposableEffect(face) { onDispose(face::close) }
    return face
}

@Composable
internal actual fun rememberPlatformMathFontFace(fontBytes: ByteArray): MathComposeFontFace {
    val face = remember(fontBytes) {
        SkiaMathFontFace(OpenTypeMathReader().read(fontBytes.copyOf()))
    }
    DisposableEffect(face) { onDispose(face::close) }
    return face
}

internal actual fun platformFormulaCapabilityEngine(
    face: MathComposeFontFace,
): MathFormulaCapabilityEngine = (face as? SkiaMathFontFace)?.formulaCapabilityEngine()
    ?: error("Desktop Compose requires SkiaMathFontFace")

internal actual fun DrawScope.drawPlatformMathPlan(
    face: MathComposeFontFace,
    plan: RenderPlan,
    color: Color,
) {
    val skiaFace = face as? SkiaMathFontFace ?: error("Desktop Compose requires SkiaMathFontFace")
    drawIntoCanvas { canvas ->
        drawSkiaMathPlan(canvas.skiaCanvas, skiaFace, plan, color.toArgb())
    }
}

private fun drawSkiaMathPlan(
    canvas: org.jetbrains.skia.Canvas,
    face: SkiaMathFontFace,
    plan: RenderPlan,
    color: Int,
) {
    val paint = Paint().apply { this.color = color }
    val builder = TextBlobBuilder()
    val fonts = mutableMapOf<Float, Font>()
    try {
        plan.boxes.flatMap { positioned ->
            positioned.box.glyphs.filter { it.constructionGroupId == null }.map { glyph ->
                Triple(
                    glyph,
                    positioned.x + glyph.x,
                    positioned.baselineFromTop + glyph.baselineY,
                )
            }
        }.groupBy { it.first.fontSizePx }.forEach { (size, glyphs) ->
            val font = fonts.getOrPut(size) { face.font(size) }
            builder.appendRunPos(
                font,
                glyphs.map { it.first.glyphId.toShort() }.toShortArray(),
                glyphs.map { Point(it.second, it.third) }.toTypedArray(),
            )
        }
        builder.build()?.use { blob -> canvas.drawTextBlob(blob, 0f, 0f, paint) }

        plan.boxes.forEach { positioned ->
            positioned.box.rules.filter { it.constructionGroupId == null }.forEach { rule ->
                canvas.drawRect(
                    Rect.makeLTRB(
                        positioned.x + rule.left,
                        positioned.baselineFromTop + rule.top,
                        positioned.x + rule.right,
                        positioned.baselineFromTop + rule.bottom,
                    ),
                    paint,
                )
            }
            val knownGroupIds = positioned.box.constructionPaintGroups.mapTo(mutableSetOf()) { it.id }
            val referencedGroupIds = buildSet {
                positioned.box.glyphs.mapNotNullTo(this) { it.constructionGroupId }
                positioned.box.rules.mapNotNullTo(this) { it.constructionGroupId }
            }
            check(knownGroupIds == referencedGroupIds) {
                "Construction paint ownership mismatch: known=$knownGroupIds referenced=$referencedGroupIds"
            }
            positioned.box.constructionPaintGroups.forEach { group ->
                when (val outline = face.constructionOutline(positioned.box, group)) {
                    is MathConstructionOutlineResult.Available -> {
                        val saveCount = canvas.save()
                        try {
                            canvas.translate(positioned.x, positioned.baselineFromTop)
                            canvas.drawPath(outline.path, paint)
                        } finally {
                            canvas.restoreToCount(saveCount)
                        }
                    }
                    is MathConstructionOutlineResult.Unavailable ->
                        throw MathConstructionOutlineUnavailableException(outline)
                }
            }
        }
    } finally {
        fonts.values.forEach(Font::close)
        builder.close()
        paint.close()
    }
}
