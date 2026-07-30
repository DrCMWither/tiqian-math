package org.tiqian.math.layout

internal data class SideScriptBoxVerticalMetrics(
    val logicalAscentPx: Float,
    val logicalDescentPx: Float,
    val inkTopPx: Float,
    val inkBottomPx: Float,
) {
    val inkAscentPx: Float get() = -inkTopPx
    val inkDescentPx: Float get() = inkBottomPx
}

internal data class SideScriptVerticalConstraints(
    val superscriptShiftUpPx: Float,
    val subscriptShiftDownPx: Float,
    val superscriptBottomMinPx: Float,
    val superscriptBaselineDropMaxPx: Float,
    val subscriptTopMaxPx: Float,
    val subscriptBaselineDropMinPx: Float,
    val subSuperscriptGapMinPx: Float,
    val superscriptBottomMaxWithSubscriptPx: Float,
)

internal data class SideScriptVerticalPlacement(
    val superscriptShiftBeforePairGapPx: Float,
    val subscriptShiftBeforePairGapPx: Float,
    val superscriptShiftPx: Float,
    val subscriptShiftPx: Float,
    val pairGapBeforeAdjustmentPx: Float?,
    val pairGapDeficitPx: Float?,
    val superscriptPairGapMovePx: Float,
    val subscriptPairGapMovePx: Float,
    val finalInkGapPx: Float?,
)

/**
 * XeTeX `make_scripts` placement from OpenType MATH constraints. Callers provide exact native
 * glyph boxes for leaves and completed TeX boxes for compound children.
 */
internal fun resolveSideScriptVerticalPlacement(
    base: SideScriptBoxVerticalMetrics,
    superscript: SideScriptBoxVerticalMetrics?,
    subscript: SideScriptBoxVerticalMetrics?,
    appliesBaselineDrop: Boolean,
    constraints: SideScriptVerticalConstraints,
): SideScriptVerticalPlacement {
    var superscriptShift = constraints.superscriptShiftUpPx
    var subscriptShift = constraints.subscriptShiftDownPx

    if (superscript != null) {
        superscriptShift = maxOf(
            superscriptShift,
            superscript.inkBottomPx + constraints.superscriptBottomMinPx,
        )
        if (appliesBaselineDrop) {
            superscriptShift = maxOf(
                superscriptShift,
                base.inkAscentPx - constraints.superscriptBaselineDropMaxPx,
            )
        }
    }
    if (subscript != null) {
        subscriptShift = maxOf(
            subscriptShift,
            subscript.inkAscentPx - constraints.subscriptTopMaxPx,
        )
        if (appliesBaselineDrop) {
            subscriptShift = maxOf(
                subscriptShift,
                base.inkBottomPx + constraints.subscriptBaselineDropMinPx,
            )
        }
    }

    val superscriptShiftBeforePairGap = superscriptShift
    val subscriptShiftBeforePairGap = subscriptShift
    val pairGapBeforeAdjustment = if (superscript != null && subscript != null) {
        (subscriptShift + subscript.inkTopPx) -
            (-superscriptShift + superscript.inkBottomPx)
    } else {
        null
    }
    val pairGapDeficit = pairGapBeforeAdjustment?.let {
        (constraints.subSuperscriptGapMinPx - it).coerceAtLeast(0f)
    }
    var superscriptPairGapMove = 0f
    var subscriptPairGapMove = 0f
    if (superscript != null && subscript != null && pairGapDeficit != null && pairGapDeficit > 0f) {
        val superscriptBottomHeight = superscriptShift - superscript.inkBottomPx
        val availableSuperscriptMove = (
            constraints.superscriptBottomMaxWithSubscriptPx - superscriptBottomHeight
            ).coerceAtLeast(0f)
        // XeTeX first gives the entire gap deficit to the subscript, then transfers the full
        // SuperscriptBottomMaxWithSubscript correction from subscript to superscript. This is
        // intentionally not capped by the original deficit.
        superscriptPairGapMove = availableSuperscriptMove
        subscriptPairGapMove = pairGapDeficit - superscriptPairGapMove
        superscriptShift += superscriptPairGapMove
        subscriptShift += subscriptPairGapMove
    }
    val finalInkGap = if (superscript != null && subscript != null) {
        (subscriptShift + subscript.inkTopPx) -
            (-superscriptShift + superscript.inkBottomPx)
    } else {
        null
    }

    return SideScriptVerticalPlacement(
        superscriptShiftBeforePairGapPx = superscriptShiftBeforePairGap,
        subscriptShiftBeforePairGapPx = subscriptShiftBeforePairGap,
        superscriptShiftPx = superscriptShift,
        subscriptShiftPx = subscriptShift,
        pairGapBeforeAdjustmentPx = pairGapBeforeAdjustment,
        pairGapDeficitPx = pairGapDeficit,
        superscriptPairGapMovePx = superscriptPairGapMove,
        subscriptPairGapMovePx = subscriptPairGapMove,
        finalInkGapPx = finalInkGap,
    )
}
