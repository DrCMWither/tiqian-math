package org.tiqian.math.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.breakResponsiveDisplayLines
import kotlin.math.ceil

/** Display-only presentation: legal math breaks first, then horizontal overflow when necessary. */
@Composable
internal fun ScrollableDisplayTiqianMath(
    result: MathLayoutResult,
    requestedLineHeightPx: Float?,
    color: Color,
    softWrap: Boolean,
    face: MathComposeFontFace,
    textRunProvider: MathTextRunProvider?,
    scrollState: ScrollState,
    horizontalInsetPx: Float,
    displayContentWidthPx: Float?,
    modifier: Modifier,
) {
    val unbroken = RenderPlan.unbroken(result, requestedLineHeightPx)
    val contentWidth = displayContentWidthPx ?: unbroken.width
    // The multi-tier break is the most expensive step on the measure path; recompute it only
    // when the formula or the width actually changes, not on every recomposition.
    val broken = remember(result, contentWidth, softWrap) {
        if (softWrap && result.fragments.size > 1) {
            result.breakResponsiveDisplayLines(contentWidth)
        } else {
            null
        }
    }
    // PinnedClauseLikeTag: when the block must scroll, its fitting clause lines anchor to the
    // viewport like an equation tag instead of traveling with the scrolled content.
    val plans = if (broken != null) {
        RenderPlan.brokenWithPinnedClauses(result, broken, requestedLineHeightPx, contentWidth)
    } else {
        unbroken.centeredIn(contentWidth) to null
    }
    val renderPlan = plans.first.insetHorizontally(horizontalInsetPx)
    val pinnedPlan = plans.second?.insetHorizontally(horizontalInsetPx)
    val requestedViewportWidth = contentWidth + horizontalInsetPx * 2f
    Layout(
        modifier = modifier,
        content = {
            Box(Modifier.horizontalScroll(scrollState)) {
                FixedTiqianMathPlan(renderPlan, Modifier, color, face, textRunProvider)
            }
            if (pinnedPlan != null) {
                FixedTiqianMathPlan(pinnedPlan, Modifier, color, face, textRunProvider)
            }
        },
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            ceil(requestedViewportWidth).toInt()
        }
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val planHeight = maxOf(renderPlan.height, pinnedPlan?.height ?: 0f)
        val height = ceil(planHeight).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val placed = measurables.map { it.measure(Constraints.fixed(width, height)) }
        layout(
            width,
            height,
            alignmentLines = mapOf(FirstBaseline to renderPlan.firstBaseline.toInt()),
        ) {
            placed.forEach { it.place(0, 0) }
        }
    }
}


/** Keeps equation tags fixed while only the independently replayable body may scroll. */
@Composable
internal fun TaggedDisplayTiqianMath(
    result: MathLayoutResult,
    requestedLineHeightPx: Float?,
    color: Color,
    face: MathComposeFontFace,
    textRunProvider: MathTextRunProvider?,
    scrollState: ScrollState,
    horizontalInsetPx: Float,
    modifier: Modifier,
) {
    val replay = checkNotNull(result.taggedDisplayReplay)
    val whole = RenderPlan.unbroken(result, requestedLineHeightPx)
    val rawVisualLeft = replay.bodyLogicalX + replay.body.visualLeft
    val rawVisualRight = replay.bodyLogicalX + replay.body.visualRight
    val contentVisualLeft = if (rawVisualLeft >= -DisplayGeometryEpsilonPx) 0f else rawVisualLeft
    val contentVisualRight = if (rawVisualRight <= replay.viewportWidthPx + DisplayGeometryEpsilonPx) {
        replay.viewportWidthPx
    } else {
        rawVisualRight
    }
    val rawBodyPlan = RenderPlan(
        boxes = listOf(
            PositionedBox(
                box = replay.body,
                x = replay.bodyLogicalX - contentVisualLeft,
                baselineFromTop = whole.firstBaseline,
            ),
        ),
        width = contentVisualRight - contentVisualLeft,
        height = whole.height,
        firstBaseline = whole.firstBaseline,
    )
    val bodyPlan = rawBodyPlan.insetHorizontally(horizontalInsetPx)
    val tagPlan = RenderPlan(
        boxes = replay.tags.map { tag ->
            PositionedBox(
                box = tag.box,
                x = horizontalInsetPx + tag.logicalX,
                baselineFromTop = whole.firstBaseline + tag.baselineY,
            )
        } + replay.pinnedClauses.map { clause ->
            // PinnedClauseLikeTag: the clause anchors to the viewport with the tag while the
            // body scrolls beneath it.
            PositionedBox(
                box = clause.box,
                x = horizontalInsetPx + clause.logicalX,
                baselineFromTop = whole.firstBaseline + clause.baselineY,
            )
        },
        width = replay.viewportWidthPx + horizontalInsetPx * 2f,
        height = whole.height,
        firstBaseline = whole.firstBaseline,
    )
    Layout(
        modifier = modifier,
        content = {
            Box(Modifier.horizontalScroll(scrollState)) {
                FixedTiqianMathPlan(bodyPlan, Modifier, color, face, textRunProvider)
            }
            FixedTiqianMathPlan(tagPlan, Modifier, color, face, textRunProvider)
        },
    ) { measurables, constraints ->
        val width = ceil(replay.viewportWidthPx + horizontalInsetPx * 2f)
            .toInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = ceil(whole.height).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val bodyWidth = ceil(replay.viewportWidthPx + horizontalInsetPx * 2f)
            .toInt().coerceIn(0, width)
        val body = measurables[0].measure(Constraints.fixed(bodyWidth, height))
        val tag = measurables[1].measure(Constraints.fixed(width, height))
        layout(
            width,
            height,
            alignmentLines = mapOf(FirstBaseline to whole.firstBaseline.toInt()),
        ) {
            body.place(0, 0)
            tag.place(0, 0)
        }
    }
}

private const val DisplayGeometryEpsilonPx = 0.01f
