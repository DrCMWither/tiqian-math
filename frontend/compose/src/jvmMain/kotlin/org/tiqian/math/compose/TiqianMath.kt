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
import org.tiqian.math.font.skia.formulaCapabilityEngine
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathFormulaStrictException
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
 * Strict dogfood entry point. Unsupported or unreplayable formulas fail during composition,
 * before a render plan, measure policy, glyph blob, or construction path is handed to drawing.
 * Production hosts should normally use [TiqianMathOrFallback].
 */
@Composable
fun TiqianMath(
    source: String,
    modifier: Modifier = Modifier,
    mode: MathMode = MathMode.Inline,
    style: TextStyle = LocalTextStyle.current,
    /** Compatibility override; prefer [style].fontSize so density and fontScale are honored. */
    fontSizePx: Float? = null,
    /** Explicit TeX `\nulldelimiterspace` in layout pixels; null retains the engine policy. */
    nullDelimiterSpacePx: Float? = null,
    /** Explicit TeX `\scriptspace` in layout pixels; null retains the font MATH constant. */
    scriptSpacePx: Float? = null,
    /** TeX `\delimiterfactor` used by content-driven `\left ... \right`. */
    delimiterFactor: Int = 901,
    /** Explicit TeX `\delimitershortfall` in layout pixels; null retains the engine policy. */
    delimiterShortfallPx: Float? = null,
    color: Color = Color.Unspecified,
    softWrap: Boolean = true,
    fontFace: SkiaMathFontFace? = null,
    onMathLayout: (MathLayoutResult) -> Unit = {},
) {
    val resolved = rememberResolvedFormulaCapability(
        source = source,
        mode = mode,
        style = style,
        fontSizePx = fontSizePx,
        nullDelimiterSpacePx = nullDelimiterSpacePx,
        scriptSpacePx = scriptSpacePx,
        delimiterFactor = delimiterFactor,
        delimiterShortfallPx = delimiterShortfallPx,
        color = color,
        fontFace = fontFace,
    )
    when (val capability = resolved.capability) {
        is MathFormulaCapabilityResult.Ready -> ReadyTiqianMath(
            result = capability.layoutResult,
            modifier = modifier,
            requestedLineHeightPx = resolved.requestedLineHeightPx,
            color = resolved.color,
            softWrap = softWrap,
            face = resolved.face,
            onMathLayout = onMathLayout,
        )
        is MathFormulaCapabilityResult.FallbackRequired -> throw MathFormulaStrictException(capability)
    }
}

/**
 * Production-safe formula boundary. The host owns [fallback] and receives the exact source,
 * diagnostics, and formula-wide reasons; Tiqian draws only [MathFormulaCapabilityResult.Ready].
 */
@Composable
fun TiqianMathOrFallback(
    source: String,
    modifier: Modifier = Modifier,
    mode: MathMode = MathMode.Inline,
    style: TextStyle = LocalTextStyle.current,
    fontSizePx: Float? = null,
    nullDelimiterSpacePx: Float? = null,
    scriptSpacePx: Float? = null,
    delimiterFactor: Int = 901,
    delimiterShortfallPx: Float? = null,
    color: Color = Color.Unspecified,
    softWrap: Boolean = true,
    fontFace: SkiaMathFontFace? = null,
    onMathLayout: (MathLayoutResult) -> Unit = {},
    fallback: @Composable (MathFormulaCapabilityResult.FallbackRequired) -> Unit,
) {
    val resolved = rememberResolvedFormulaCapability(
        source = source,
        mode = mode,
        style = style,
        fontSizePx = fontSizePx,
        nullDelimiterSpacePx = nullDelimiterSpacePx,
        scriptSpacePx = scriptSpacePx,
        delimiterFactor = delimiterFactor,
        delimiterShortfallPx = delimiterShortfallPx,
        color = color,
        fontFace = fontFace,
    )
    when (val capability = resolved.capability) {
        is MathFormulaCapabilityResult.Ready -> ReadyTiqianMath(
            result = capability.layoutResult,
            modifier = modifier,
            requestedLineHeightPx = resolved.requestedLineHeightPx,
            color = resolved.color,
            softWrap = softWrap,
            face = resolved.face,
            onMathLayout = onMathLayout,
        )
        is MathFormulaCapabilityResult.FallbackRequired -> fallback(capability)
    }
}

private data class ResolvedFormulaCapability(
    val face: SkiaMathFontFace,
    val capability: MathFormulaCapabilityResult,
    val requestedLineHeightPx: Float?,
    val color: Color,
)

@Composable
private fun rememberResolvedFormulaCapability(
    source: String,
    mode: MathMode,
    style: TextStyle,
    fontSizePx: Float?,
    nullDelimiterSpacePx: Float?,
    scriptSpacePx: Float?,
    delimiterFactor: Int,
    delimiterShortfallPx: Float?,
    color: Color,
    fontFace: SkiaMathFontFace?,
): ResolvedFormulaCapability {
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
    val capabilityEngine = remember(resolvedFace) { resolvedFace.formulaCapabilityEngine() }
    val capability = remember(
        source,
        mode,
        resolvedFontSizePx,
        nullDelimiterSpacePx,
        scriptSpacePx,
        delimiterFactor,
        delimiterShortfallPx,
        capabilityEngine,
    ) {
        capabilityEngine.evaluate(
            source,
            MathLayoutOptions(
                mode = mode,
                fontSizePx = resolvedFontSizePx,
                nullDelimiterSpacePx = nullDelimiterSpacePx,
                scriptSpacePx = scriptSpacePx,
                delimiterFactor = delimiterFactor,
                delimiterShortfallPx = delimiterShortfallPx,
            ),
        )
    }
    return ResolvedFormulaCapability(
        face = resolvedFace,
        capability = capability,
        requestedLineHeightPx = requestedLineHeightPx,
        color = resolvedColor,
    )
}

@Composable
private fun ReadyTiqianMath(
    result: MathLayoutResult,
    modifier: Modifier,
    requestedLineHeightPx: Float?,
    color: Color,
    softWrap: Boolean,
    face: SkiaMathFontFace,
    onMathLayout: (MathLayoutResult) -> Unit,
) {
    SideEffect { onMathLayout(result) }

    var renderPlan = RenderPlan.unbroken(result, requestedLineHeightPx)
    Layout(
        modifier = modifier,
        content = {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    drawMathPlan(canvas.skiaCanvas, face, renderPlan, color.toArgb())
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
