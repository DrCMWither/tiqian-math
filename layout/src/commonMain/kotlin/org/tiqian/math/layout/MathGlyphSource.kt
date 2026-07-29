package org.tiqian.math.layout

import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathAlphabet
import org.tiqian.math.core.MathFamily
import org.tiqian.math.core.MathLargeOperatorIdentity
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.MathSymbolIdentity
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.OpenTypeMathFont

data class MeasuredMathGlyph(
    val glyphId: UShort,
    val x: Float,
    val advance: Float,
    val inkBounds: MathRect,
    /** UTF-16 cluster offset in the exact backend string passed to the shaper. */
    val textCluster: Int = 0,
)

data class MeasuredMathRun(
    val glyphs: List<MeasuredMathGlyph>,
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val missingGlyph: Boolean,
)

/** Semantic request. No Unicode Mathematical Alphanumeric glyph scalar is stored in the AST. */
data class MathSymbolGlyphRequest(
    val identity: MathSymbolIdentity,
    val family: MathFamily,
    val alphabet: MathAlphabet,
    val style: MathStyle,
    val sourceRange: SourceRange,
)

/** Auditable result of resolving one semantic math symbol against one formula-wide face. */
data class ResolvedMathSymbol(
    val run: MeasuredMathRun,
    val backendScalar: Int,
    val supported: Boolean,
)

/** Semantic request for a TeX op noad in the fixed LargeSymbols family. */
data class MathOperatorGlyphRequest(
    val identity: MathLargeOperatorIdentity,
    val style: MathStyle,
    val sourceRange: SourceRange,
)

/**
 * The style-shaped run is used for normal operators. [constructionBaseGlyphId] deliberately
 * bypasses `ssty`, since MathVariants coverage is keyed by the base large-operator glyph.
 */
data class ResolvedMathOperator(
    val run: MeasuredMathRun,
    val backendScalar: Int,
    val constructionBaseGlyphId: UShort?,
)

/**
 * One shaping result for consecutive compatible Ord noads. [glyphSourceRanges] is parallel to
 * [MeasuredMathRun.glyphs] and maps backend shaping clusters back to the untouched input.
 */
data class ResolvedMathSymbolRun(
    val run: MeasuredMathRun,
    val backendScalars: List<Int>,
    val supported: List<Boolean>,
    val glyphSourceRanges: List<SourceRange>,
) {
    init {
        require(backendScalars.isNotEmpty())
        require(backendScalars.size == supported.size)
        require(run.glyphs.size == glyphSourceRanges.size)
    }
}

/** Platform font adapter. Layout consumes only immutable, replayable evidence. */
interface MathFontFace {
    val mathFont: OpenTypeMathFont

    fun resolveSymbol(
        request: MathSymbolGlyphRequest,
        fontSizePx: Float,
    ): ResolvedMathSymbol

    fun resolveOperator(
        request: MathOperatorGlyphRequest,
        fontSizePx: Float,
    ): ResolvedMathOperator

    /** Resolve and shape a compatible Ord run in one backend shaping call. */
    fun resolveSymbols(
        requests: List<MathSymbolGlyphRequest>,
        fontSizePx: Float,
    ): ResolvedMathSymbolRun

    fun shape(
        text: String,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun

    fun measureGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun

    /**
     * Resolves the Unicode base glyph used as the key in MathVariants coverage.
     * This deliberately bypasses `ssty` while retaining the requested size.
     */
    fun shapeConstructionBase(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): MeasuredMathRun = shape(text, fontSizePx, MathStyle.Text, sourceRange)
}
