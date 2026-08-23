package org.tiqian.math.layout

import kotlin.test.Test
import kotlin.test.assertEquals

class SideScriptHorizontalPlacementTest {
    @Test
    fun xetexOperatorUpperOnlyKeepsTheOriginalNucleusAdvance() {
        val placement = operatorPlacement(superscriptWidth = 8f, subscriptWidth = null)

        assertEquals(20f, placement.nucleusLogicalWidthPx)
        assertEquals(0f, placement.operatorWidthReductionPx)
        assertEquals(0f, placement.superscriptItalicDeltaPx)
        assertEquals(21f, placement.superscriptXPx)
        assertEquals(31f, placement.logicalWidthPx)
    }

    @Test
    fun xetexOperatorLowerOnlySubtractsItalicDeltaFromTheNucleusWidth() {
        val placement = operatorPlacement(superscriptWidth = null, subscriptWidth = 7f)

        assertEquals(14f, placement.nucleusLogicalWidthPx)
        assertEquals(6f, placement.operatorWidthReductionPx)
        assertEquals(12f, placement.subscriptXPx)
        assertEquals(21f, placement.logicalWidthPx)
    }

    @Test
    fun xetexOperatorPairReturnsDeltaOnlyToTheSuperscriptRow() {
        val placement = operatorPlacement(superscriptWidth = 8f, subscriptWidth = 7f)

        assertEquals(14f, placement.nucleusLogicalWidthPx)
        assertEquals(20f, placement.superscriptItalicDeltaPx + placement.nucleusLogicalWidthPx)
        assertEquals(21f, placement.superscriptXPx)
        assertEquals(12f, placement.subscriptXPx)
        assertEquals(31f, placement.logicalWidthPx)
    }

    @Test
    fun ordinaryNucleusRetainsItsExistingItalicCorrectionContract() {
        val placement = resolveSideScriptHorizontalPlacement(
            baseWidthPx = 20f,
            italicCorrectionPx = 6f,
            superscriptWidthPx = 8f,
            subscriptWidthPx = 7f,
            superscriptKernPx = 1f,
            subscriptKernPx = -2f,
            spaceAfterScriptPx = 2f,
            policy = SideScriptHorizontalPolicy.OrdinaryNucleus,
        )

        assertEquals(20f, placement.nucleusLogicalWidthPx)
        assertEquals(27f, placement.superscriptXPx)
        assertEquals(18f, placement.subscriptXPx)
        assertEquals(37f, placement.logicalWidthPx)
    }

    private fun operatorPlacement(
        superscriptWidth: Float?,
        subscriptWidth: Float?,
    ): SideScriptHorizontalPlacement = resolveSideScriptHorizontalPlacement(
        baseWidthPx = 20f,
        italicCorrectionPx = 6f,
        superscriptWidthPx = superscriptWidth,
        subscriptWidthPx = subscriptWidth,
        superscriptKernPx = 1f,
        subscriptKernPx = -2f,
        spaceAfterScriptPx = 2f,
        policy = SideScriptHorizontalPolicy.XeTeXOperatorNoLimits,
    )
}
