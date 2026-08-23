package org.tiqian.math.layout

import kotlin.test.Test
import kotlin.test.assertEquals

class RadicalDegreeHorizontalPlacementTest {
    @Test
    fun rawAfterWithinWidthPlusPositiveBeforeIsNotClamped() {
        val placement = resolveRadicalDegreeHorizontalPlacement(
            degreeWidthPx = 20f,
            kernBeforeDegreePx = 5f,
            kernAfterDegreePx = -10f,
        )

        assertEquals(-25f, placement.afterKernClampLowerBoundPx)
        assertEquals(-10f, placement.adjustedKernAfterDegreePx)
        assertEquals(5f, placement.degreeX)
        assertEquals(15f, placement.radicalX)
    }

    @Test
    fun excessiveNegativeAfterIsClampedByWidthPlusPositiveBefore() {
        val placement = resolveRadicalDegreeHorizontalPlacement(
            degreeWidthPx = 20f,
            kernBeforeDegreePx = 5f,
            kernAfterDegreePx = -30f,
        )

        assertEquals(-25f, placement.afterKernClampLowerBoundPx)
        assertEquals(-25f, placement.adjustedKernAfterDegreePx)
        assertEquals(5f, placement.degreeX)
        assertEquals(0f, placement.radicalX)
    }

    @Test
    fun negativeBeforeRemainsSignedAndTightensTheAfterClamp() {
        val placement = resolveRadicalDegreeHorizontalPlacement(
            degreeWidthPx = 20f,
            kernBeforeDegreePx = -5f,
            kernAfterDegreePx = -30f,
        )

        assertEquals(-5f, placement.rawKernBeforeDegreePx)
        assertEquals(-15f, placement.afterKernClampLowerBoundPx)
        assertEquals(-15f, placement.adjustedKernAfterDegreePx)
        assertEquals(-5f, placement.degreeX)
        assertEquals(0f, placement.radicalX)
    }

    @Test
    fun extremeNegativeBeforeStillKeepsTheRadicalOriginNonNegative() {
        val placement = resolveRadicalDegreeHorizontalPlacement(
            degreeWidthPx = 10f,
            kernBeforeDegreePx = -20f,
            kernAfterDegreePx = 2f,
        )

        assertEquals(10f, placement.afterKernClampLowerBoundPx)
        assertEquals(10f, placement.adjustedKernAfterDegreePx)
        assertEquals(0f, placement.radicalX)
    }
}
