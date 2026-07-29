package org.tiqian.math.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Point
import org.jetbrains.skia.Rect
import org.jetbrains.skia.TextBlobBuilder
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathBrokenLayout
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.skia.SkiaMathFontFace
import org.tiqian.math.font.skia.MathConstructionOutlineResult
import org.tiqian.math.font.skia.MathConstructionOutlineUnavailableException
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.breakIntoLines
import kotlin.math.ceil

/** Loads Tiqian's product-default face. Explicit caller-supplied faces remain caller-owned. */
@Composable
fun rememberLeteMathFontFace(): SkiaMathFontFace {
    val face = remember { SkiaMathFontFace(LeteSansMath.load()) }
    DisposableEffect(face) { onDispose(face::close) }
    return face
}

/**
 * Measures and draws one formula using one complete face and one MATH table.
 * The renderer replays glyph/rule placements and semantic construction groups returned by layout.
 */
@Composable
fun TiqianMath(
    source: String,
    modifier: Modifier = Modifier,
    mode: MathMode = MathMode.Inline,
    style: TextStyle = LocalTextStyle.current,
    /** Compatibility override; prefer [style].fontSize so density and fontScale are honored. */
    fontSizePx: Float? = null,
    color: Color = Color.Unspecified,
    softWrap: Boolean = true,
    fontFace: SkiaMathFontFace? = null,
    onMathLayout: (MathLayoutResult) -> Unit = {},
) {
    val density = LocalDensity.current
    val resolvedFontSizePx = fontSizePx ?: with(density) {
        (if (style.fontSize.isSpecified) style.fontSize else DefaultMathFontSize).toPx()
    }
    val requestedLineHeightPx = if (style.lineHeight.isSpecified) {
        with(density) { style.lineHeight.toPx() }
    } else {
        null
    }
    val resolvedColor = if (color != Color.Unspecified) {
        color
    } else if (style.color != Color.Unspecified) {
        style.color
    } else {
        Color.Black
    }
    val defaultFace = if (fontFace == null) rememberLeteMathFontFace() else null
    val resolvedFace = fontFace ?: checkNotNull(defaultFace)
    val result = remember(source, mode, resolvedFontSizePx, resolvedFace) {
        MathLayoutEngine(resolvedFace).layout(source, MathLayoutOptions(mode, resolvedFontSizePx))
    }
    SideEffect { onMathLayout(result) }

    var renderPlan = RenderPlan.unbroken(result, requestedLineHeightPx)
    Layout(
        modifier = modifier,
        content = {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    drawMathPlan(canvas.skiaCanvas, resolvedFace, renderPlan, resolvedColor.toArgb())
                }
            }
        },
    ) { measurables, constraints ->
        val broken = if (softWrap && constraints.hasBoundedWidth && result.fragments.size > 1) {
            result.breakIntoLines(constraints.maxWidth.toFloat().coerceAtLeast(1f))
        } else {
            null
        }
        renderPlan = if (broken != null) {
            RenderPlan.broken(result, broken, requestedLineHeightPx)
        } else {
            RenderPlan.unbroken(result, requestedLineHeightPx)
        }
        val width = ceil(renderPlan.width).toInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = ceil(renderPlan.height).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val child = measurables.single().measure(Constraints.fixed(width, height))
        layout(
            width,
            height,
            alignmentLines = mapOf(FirstBaseline to renderPlan.firstBaseline.toInt()),
        ) {
            child.place(0, 0)
        }
    }
}

internal data class PositionedBox(
    val box: MathBox,
    val x: Float,
    val baselineFromTop: Float,
)

internal data class RenderPlan(
    val boxes: List<PositionedBox>,
    val width: Float,
    val height: Float,
    val firstBaseline: Float,
) {
    companion object {
        fun unbroken(result: MathLayoutResult, minimumLineHeightPx: Float? = null): RenderPlan {
            val metrics = result.lineMetrics.forInk(result.box.ascent, result.box.descent)
            val extraLeading = ((minimumLineHeightPx ?: 0f) - metrics.logicalHeightPx).coerceAtLeast(0f)
            val topLeading = extraLeading / 2f
            return RenderPlan(
                boxes = listOf(
                    PositionedBox(
                        result.box,
                        -result.box.visualLeft,
                        metrics.logicalAscentPx + topLeading,
                    ),
                ),
                width = result.box.visualWidth,
                height = metrics.logicalHeightPx + extraLeading,
                firstBaseline = metrics.logicalAscentPx + topLeading,
            )
        }

        fun broken(
            result: MathLayoutResult,
            broken: MathBrokenLayout,
            minimumLineHeightPx: Float? = null,
        ): RenderPlan {
            var top = 0f
            var firstBaseline = 0f
            val boxes = broken.lines.flatMapIndexed { lineIndex, line ->
                val intrinsicHeight = line.ascent + line.descent
                val extraLeading = ((minimumLineHeightPx ?: 0f) - intrinsicHeight).coerceAtLeast(0f)
                val baseline = top + line.ascent + extraLeading / 2f
                if (lineIndex == 0) firstBaseline = baseline
                top += intrinsicHeight + extraLeading
                line.fragments.map { placement ->
                    PositionedBox(
                        box = result.fragments[placement.fragmentIndex].box,
                        x = -line.visualLeft + placement.x,
                        baselineFromTop = baseline,
                    )
                }
            }
            return RenderPlan(
                boxes = boxes,
                width = broken.width,
                height = top,
                firstBaseline = firstBaseline,
            )
        }
    }
}

private val DefaultMathFontSize = 24.sp

private fun drawMathPlan(
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
