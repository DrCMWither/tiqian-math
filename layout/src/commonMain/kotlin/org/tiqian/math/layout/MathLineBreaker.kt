package org.tiqian.math.layout

import org.tiqian.math.core.MathAdjustmentPriority
import org.tiqian.math.core.MathBrokenLayout
import org.tiqian.math.core.MathBrokenLine
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathLineAdjustmentMode
import org.tiqian.math.core.MathLineFragmentPlacement
import org.tiqian.math.core.MathRect
import kotlin.math.abs

/**
 * Greedy reference breaker over the public fragment contract.
 *
 * Break selection, adjustment, reported geometry, and rendering placements all use the same
 * resolved glue values. Glue after the last visible fragment is never placed on a line.
 */
fun MathLayoutResult.breakIntoLines(
    maxWidthPx: Float,
    adjustmentMode: MathLineAdjustmentMode = MathLineAdjustmentMode.Fit,
): MathBrokenLayout {
    require(maxWidthPx > 0f) { "line width must be positive" }
    if (fragments.isEmpty()) return MathBrokenLayout(emptyList(), 0f, 0f)

    // Legal breaks partition the formula into indivisible segments. A segment that cannot fit
    // is reported as an overflowing line; no synthetic atom-boundary break is invented.
    val segmentEnds = buildList {
        fragments.indices.forEach { index -> if (fragments[index].breakAfter != null) add(index) }
        if (lastOrNull() != fragments.lastIndex) add(fragments.lastIndex)
    }
    val segments = buildList {
        var start = 0
        segmentEnds.forEach { end ->
            add(start..end)
            start = end + 1
        }
    }
    val ranges = mutableListOf<IntRange>()
    var currentStart = segments.first().first
    var currentEnd = segments.first().last
    segments.drop(1).forEach { segment ->
        val candidate = currentStart..segment.last
        val minimumGeometry = geometry(candidate, internalGlue(candidate) { it.minimumPx })
        if (minimumGeometry.visualWidth > maxWidthPx + GEOMETRY_EPSILON_PX) {
            ranges += currentStart..currentEnd
            currentStart = segment.first
            currentEnd = segment.last
        } else {
            currentEnd = segment.last
        }
    }
    ranges += currentStart..currentEnd

    var top = 0f
    val lines = ranges.mapIndexed { lineIndex, range ->
        val natural = internalGlue(range) { it.naturalPx }
        val naturalGeometry = geometry(range, natural)
        val visualOverhang = naturalGeometry.visualWidth - naturalGeometry.logicalWidth
        val desiredLogicalWidth = (maxWidthPx - visualOverhang).coerceAtLeast(0f)
        val shouldStretch = adjustmentMode == MathLineAdjustmentMode.Justify && lineIndex != ranges.lastIndex
        val targetLogicalWidth = when {
            naturalGeometry.visualWidth > maxWidthPx + GEOMETRY_EPSILON_PX -> desiredLogicalWidth
            shouldStretch -> desiredLogicalWidth
            else -> naturalGeometry.logicalWidth
        }
        val resolved = resolveGlue(range, natural, targetLogicalWidth)
        val lineGeometry = geometry(range, resolved)
        val safeMetrics = lineMetrics.forInk(lineGeometry.inkAscent, lineGeometry.inkDescent)
        val baseline = top + safeMetrics.logicalAscentPx
        top = baseline + safeMetrics.logicalDescentPx
        MathBrokenLine(
            fragments = lineGeometry.placements,
            logicalWidth = lineGeometry.logicalWidth,
            inkBounds = lineGeometry.inkBounds,
            visualLeft = lineGeometry.visualLeft,
            visualRight = lineGeometry.visualRight,
            width = lineGeometry.visualWidth,
            inkAscent = lineGeometry.inkAscent,
            inkDescent = lineGeometry.inkDescent,
            ascent = safeMetrics.logicalAscentPx,
            descent = safeMetrics.logicalDescentPx,
            baselineFromTop = baseline,
            unbreakableOverflow = lineGeometry.visualWidth > maxWidthPx + GEOMETRY_EPSILON_PX,
        )
    }
    val height = lines.lastOrNull()?.let { it.baselineFromTop + it.descent } ?: 0f
    return MathBrokenLayout(lines, lines.maxOfOrNull { it.width } ?: 0f, height)
}

private fun MathLayoutResult.internalGlue(
    range: IntRange,
    selector: (org.tiqian.math.core.MathGlueAdjustment) -> Float,
): List<Float> =
    range.map { fragmentIndex ->
        if (fragmentIndex == range.last) 0f else selector(fragments[fragmentIndex].trailingGlue)
    }

private fun MathLayoutResult.geometry(range: IntRange, resolvedGlue: List<Float>): LineGeometry {
    var x = 0f
    var inkLeft = 0f
    var inkTop = 0f
    var inkRight = 0f
    var inkBottom = 0f
    var hasInk = false
    val placements = range.mapIndexed { localIndex, fragmentIndex ->
        val fragment = fragments[fragmentIndex]
        x += fragment.leadingKernPx
        val placement = MathLineFragmentPlacement(fragmentIndex, x, resolvedGlue[localIndex])
        val translated = fragment.box.inkBounds.translated(x, 0f)
        if (!hasInk) {
            inkLeft = translated.left
            inkTop = translated.top
            inkRight = translated.right
            inkBottom = translated.bottom
            hasInk = translated.width != 0f || translated.height != 0f
        } else {
            inkLeft = minOf(inkLeft, translated.left)
            inkTop = minOf(inkTop, translated.top)
            inkRight = maxOf(inkRight, translated.right)
            inkBottom = maxOf(inkBottom, translated.bottom)
        }
        x += fragment.box.width + fragment.trailingItalicCorrectionPx + resolvedGlue[localIndex]
        placement
    }
    val ink = if (hasInk) MathRect(inkLeft, inkTop, inkRight, inkBottom) else MathRect(0f, 0f, 0f, 0f)
    val visualLeft = minOf(0f, ink.left)
    val visualRight = maxOf(x, ink.right)
    return LineGeometry(
        placements,
        x,
        ink,
        visualLeft,
        visualRight,
        visualRight - visualLeft,
        (-ink.top).coerceAtLeast(0f),
        ink.bottom.coerceAtLeast(0f),
    )
}

private fun MathLayoutResult.resolveGlue(
    range: IntRange,
    natural: List<Float>,
    targetLogicalWidth: Float,
): List<Float> {
    val resolved = natural.toMutableList()
    val naturalWidth = geometry(range, natural).logicalWidth
    var remaining = targetLogicalWidth - naturalWidth
    if (abs(remaining) <= GEOMETRY_EPSILON_PX) return resolved

    MathAdjustmentPriority.entries.sortedBy { it.order }.forEach { priority ->
        if (priority == MathAdjustmentPriority.None || abs(remaining) <= GEOMETRY_EPSILON_PX) return@forEach
        val eligible = range.mapIndexedNotNull { localIndex, fragmentIndex ->
            if (fragmentIndex == range.last) return@mapIndexedNotNull null
            val glue = fragments[fragmentIndex].trailingGlue
            if (glue.priority == priority) localIndex to glue else null
        }.toMutableList()
        while (eligible.isNotEmpty() && abs(remaining) > GEOMETRY_EPSILON_PX) {
            val share = remaining / eligible.size
            val exhausted = mutableListOf<Pair<Int, org.tiqian.math.core.MathGlueAdjustment>>()
            eligible.forEach { (index, glue) ->
                val capacity = if (remaining > 0f) glue.maximumPx - resolved[index] else resolved[index] - glue.minimumPx
                val applied = if (remaining > 0f) minOf(share, capacity) else maxOf(share, -capacity)
                resolved[index] += applied
                remaining -= applied
                if (capacity - abs(applied) <= GEOMETRY_EPSILON_PX) exhausted += index to glue
            }
            eligible.removeAll(exhausted.toSet())
        }
    }
    return resolved
}

private data class LineGeometry(
    val placements: List<MathLineFragmentPlacement>,
    val logicalWidth: Float,
    val inkBounds: MathRect,
    val visualLeft: Float,
    val visualRight: Float,
    val visualWidth: Float,
    val inkAscent: Float,
    val inkDescent: Float,
)

private const val GEOMETRY_EPSILON_PX = 0.02f
