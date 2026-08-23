package org.tiqian.math.layout

internal enum class SideScriptHorizontalPolicy {
    OrdinaryNucleus,
    XeTeXOperatorNoLimits,
}

internal data class SideScriptHorizontalPlacement(
    val policy: SideScriptHorizontalPolicy,
    val originalBaseWidthPx: Float,
    val italicCorrectionDeltaPx: Float,
    val operatorWidthReductionPx: Float,
    val nucleusLogicalWidthPx: Float,
    val superscriptItalicDeltaPx: Float,
    val superscriptXPx: Float?,
    val subscriptXPx: Float?,
    val logicalWidthPx: Float,
)

/**
 * Resolves the horizontal part of TeX `make_scripts`.
 *
 * An ordinary nucleus keeps its advance and places a superscript after its italic correction.
 * XeTeX `make_op` instead subtracts the operator delta from a nolimits nucleus whenever a
 * subscript exists; `make_scripts` then gives that delta back only to the superscript row. The
 * operator glyph stays at its original paint origin, so the reduced width is logical geometry,
 * not a visual crop.
 */
internal fun resolveSideScriptHorizontalPlacement(
    baseWidthPx: Float,
    italicCorrectionPx: Float,
    superscriptWidthPx: Float?,
    subscriptWidthPx: Float?,
    superscriptKernPx: Float,
    subscriptKernPx: Float,
    spaceAfterScriptPx: Float,
    policy: SideScriptHorizontalPolicy,
): SideScriptHorizontalPlacement {
    require(superscriptWidthPx != null || subscriptWidthPx != null) {
        "side-script placement requires at least one script"
    }
    val hasSubscript = subscriptWidthPx != null
    val operatorWidthReduction = if (
        policy == SideScriptHorizontalPolicy.XeTeXOperatorNoLimits && hasSubscript
    ) {
        italicCorrectionPx
    } else {
        0f
    }
    val nucleusWidth = baseWidthPx - operatorWidthReduction
    val superscriptItalicDelta = when (policy) {
        SideScriptHorizontalPolicy.OrdinaryNucleus -> italicCorrectionPx
        SideScriptHorizontalPolicy.XeTeXOperatorNoLimits -> if (hasSubscript) italicCorrectionPx else 0f
    }
    val superscriptX = superscriptWidthPx?.let {
        nucleusWidth + superscriptItalicDelta + superscriptKernPx
    }
    val subscriptX = subscriptWidthPx?.let { nucleusWidth + subscriptKernPx }
    val scriptRight = buildList {
        superscriptWidthPx?.let { add(checkNotNull(superscriptX) + it) }
        subscriptWidthPx?.let { add(checkNotNull(subscriptX) + it) }
    }.maxOrNull() ?: nucleusWidth
    val contentRight = if (policy == SideScriptHorizontalPolicy.OrdinaryNucleus) {
        maxOf(baseWidthPx, scriptRight)
    } else {
        scriptRight
    }
    return SideScriptHorizontalPlacement(
        policy = policy,
        originalBaseWidthPx = baseWidthPx,
        italicCorrectionDeltaPx = italicCorrectionPx,
        operatorWidthReductionPx = operatorWidthReduction,
        nucleusLogicalWidthPx = nucleusWidth,
        superscriptItalicDeltaPx = superscriptItalicDelta,
        superscriptXPx = superscriptX,
        subscriptXPx = subscriptX,
        logicalWidthPx = contentRight + spaceAfterScriptPx,
    )
}
