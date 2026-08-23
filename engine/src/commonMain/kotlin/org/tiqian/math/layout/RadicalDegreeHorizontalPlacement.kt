package org.tiqian.math.layout

internal data class RadicalDegreeHorizontalPlacement(
    val rawKernBeforeDegreePx: Float,
    val rawKernAfterDegreePx: Float,
    val afterKernClampLowerBoundPx: Float,
    val adjustedKernAfterDegreePx: Float,
    val degreeX: Float,
    val radicalX: Float,
)

/**
 * TeX's signed horizontal placement rule for a radical degree.
 *
 * LuaTeX `make_radical` preserves the font-provided before kern, then limits only the after
 * kern so that it cannot cancel more than `degreeWidth + beforeKern`. The resulting radical
 * origin is therefore always non-negative without independently clamping the before kern.
 */
internal fun resolveRadicalDegreeHorizontalPlacement(
    degreeWidthPx: Float,
    kernBeforeDegreePx: Float,
    kernAfterDegreePx: Float,
): RadicalDegreeHorizontalPlacement {
    require(degreeWidthPx >= 0f) { "Radical degree width must be non-negative" }
    val afterKernClampLowerBound = -(degreeWidthPx + kernBeforeDegreePx)
    val adjustedKernAfterDegree = maxOf(kernAfterDegreePx, afterKernClampLowerBound)
    return RadicalDegreeHorizontalPlacement(
        rawKernBeforeDegreePx = kernBeforeDegreePx,
        rawKernAfterDegreePx = kernAfterDegreePx,
        afterKernClampLowerBoundPx = afterKernClampLowerBound,
        adjustedKernAfterDegreePx = adjustedKernAfterDegree,
        degreeX = kernBeforeDegreePx,
        radicalX = kernBeforeDegreePx + degreeWidthPx + adjustedKernAfterDegree,
    )
}
