package org.tiqian.math.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SideScriptVerticalPlacementTest {
    @Test
    fun logicalReserveCannotAffectAnyOrdinarySideScriptConstraint() {
        val base = metrics(logicalAscent = 60f, logicalDescent = 30f, inkTop = -50f, inkBottom = 20f)
        val superscript = metrics(24f, 12f, inkTop = -18f, inkBottom = 8f)
        val subscript = metrics(22f, 11f, inkTop = -17f, inkBottom = 6f)
        val expandedBase = base.copy(logicalAscentPx = 600f, logicalDescentPx = 300f)
        val expandedSuperscript = superscript.copy(logicalAscentPx = 240f, logicalDescentPx = 120f)
        val expandedSubscript = subscript.copy(logicalAscentPx = 220f, logicalDescentPx = 110f)
        val constraints = SideScriptVerticalConstraints(
            superscriptShiftUpPx = 0f,
            subscriptShiftDownPx = 0f,
            superscriptBottomMinPx = 12f,
            superscriptBaselineDropMaxPx = 10f,
            subscriptTopMaxPx = 5f,
            subscriptBaselineDropMinPx = 9f,
            subSuperscriptGapMinPx = 48f,
            superscriptBottomMaxWithSubscriptPx = 34f,
        )

        val original = resolveSideScriptVerticalPlacement(
            base,
            superscript,
            subscript,
            appliesBaselineDrop = true,
            constraints,
        )
        val expanded = resolveSideScriptVerticalPlacement(
            expandedBase,
            expandedSuperscript,
            expandedSubscript,
            appliesBaselineDrop = true,
            constraints,
        )

        assertEquals(original, expanded)
        assertEquals(48f, original.finalInkGapPx)
        assertTrue(expandedBase.logicalAscentPx > base.logicalAscentPx)
        assertTrue(expandedBase.logicalDescentPx > base.logicalDescentPx)
        assertTrue(expandedSuperscript.logicalDescentPx > superscript.logicalDescentPx)
        assertTrue(expandedSubscript.logicalAscentPx > subscript.logicalAscentPx)
    }

    @Test
    fun eachNamedConstraintRespondsToItsActualInkEdge() {
        val zero = SideScriptVerticalConstraints(
            superscriptShiftUpPx = 0f,
            subscriptShiftDownPx = 0f,
            superscriptBottomMinPx = 0f,
            superscriptBaselineDropMaxPx = 0f,
            subscriptTopMaxPx = 0f,
            subscriptBaselineDropMinPx = 0f,
            subSuperscriptGapMinPx = 0f,
            superscriptBottomMaxWithSubscriptPx = 0f,
        )
        val neutralBase = metrics(40f, 15f, inkTop = -30f, inkBottom = 10f)
        val neutralSuperscript = metrics(20f, 8f, inkTop = -15f, inkBottom = 4f)
        val neutralSubscript = metrics(18f, 7f, inkTop = -12f, inkBottom = 5f)

        val shallowerBaseSuperscript = resolveSideScriptVerticalPlacement(
            neutralBase,
            neutralSuperscript,
            null,
            appliesBaselineDrop = true,
            zero,
        )
        val tallerBaseSuperscript = resolveSideScriptVerticalPlacement(
            neutralBase.copy(inkTopPx = -50f, inkBottomPx = 25f),
            neutralSuperscript,
            null,
            appliesBaselineDrop = true,
            zero,
        )
        assertEquals(20f, tallerBaseSuperscript.superscriptShiftPx - shallowerBaseSuperscript.superscriptShiftPx)
        val shallowerBaseSubscript = resolveSideScriptVerticalPlacement(
            neutralBase,
            null,
            neutralSubscript,
            appliesBaselineDrop = true,
            zero.copy(subscriptTopMaxPx = 1_000f),
        )
        val deeperBaseSubscript = resolveSideScriptVerticalPlacement(
            neutralBase.copy(inkTopPx = -50f, inkBottomPx = 25f),
            null,
            neutralSubscript,
            appliesBaselineDrop = true,
            zero.copy(subscriptTopMaxPx = 1_000f),
        )
        assertEquals(15f, deeperBaseSubscript.subscriptShiftPx - shallowerBaseSubscript.subscriptShiftPx)

        val scriptEdges = zero.copy(
            superscriptBaselineDropMaxPx = 1_000f,
            subscriptBaselineDropMinPx = 0f,
        )
        val shallowSuperscript = resolveSideScriptVerticalPlacement(
            neutralBase,
            neutralSuperscript,
            null,
            appliesBaselineDrop = false,
            scriptEdges,
        )
        val deeperSuperscript = resolveSideScriptVerticalPlacement(
            neutralBase,
            neutralSuperscript.copy(inkBottomPx = 14f),
            null,
            appliesBaselineDrop = false,
            scriptEdges,
        )
        assertEquals(10f, deeperSuperscript.superscriptShiftPx - shallowSuperscript.superscriptShiftPx)
        val shallowSubscript = resolveSideScriptVerticalPlacement(
            neutralBase,
            null,
            neutralSubscript,
            appliesBaselineDrop = false,
            scriptEdges,
        )
        val tallerSubscript = resolveSideScriptVerticalPlacement(
            neutralBase,
            null,
            neutralSubscript.copy(inkTopPx = -32f),
            appliesBaselineDrop = false,
            scriptEdges,
        )
        assertEquals(20f, tallerSubscript.subscriptShiftPx - shallowSubscript.subscriptShiftPx)
    }

    @Test
    fun pairedScriptsCloseTheRequiredGapBetweenFinalInkEdges() {
        val base = metrics(30f, 10f, inkTop = -25f, inkBottom = 8f)
        val superscript = metrics(20f, 10f, inkTop = -16f, inkBottom = 10f)
        val subscript = metrics(20f, 10f, inkTop = -10f, inkBottom = 6f)
        val placement = resolveSideScriptVerticalPlacement(
            base,
            superscript,
            subscript,
            appliesBaselineDrop = false,
            SideScriptVerticalConstraints(
                superscriptShiftUpPx = 0f,
                subscriptShiftDownPx = 0f,
                superscriptBottomMinPx = 0f,
                superscriptBaselineDropMaxPx = 0f,
                subscriptTopMaxPx = 0f,
                subscriptBaselineDropMinPx = 0f,
                subSuperscriptGapMinPx = 40f,
                superscriptBottomMaxWithSubscriptPx = 20f,
            ),
        )

        assertEquals(0f, placement.pairGapBeforeAdjustmentPx)
        assertEquals(40f, placement.pairGapDeficitPx)
        assertEquals(20f, placement.superscriptPairGapMovePx)
        assertEquals(20f, placement.subscriptPairGapMovePx)
        assertEquals(40f, placement.finalInkGapPx)
        val replayedGap =
            (placement.subscriptShiftPx + subscript.inkTopPx) -
                (-placement.superscriptShiftPx + superscript.inkBottomPx)
        assertEquals(placement.finalInkGapPx, replayedGap)
    }

    private fun metrics(
        logicalAscent: Float,
        logicalDescent: Float,
        inkTop: Float,
        inkBottom: Float,
    ): SideScriptBoxVerticalMetrics = SideScriptBoxVerticalMetrics(
        logicalAscentPx = logicalAscent,
        logicalDescentPx = logicalDescent,
        inkTopPx = inkTop,
        inkBottomPx = inkBottom,
    )
}
