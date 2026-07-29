package org.tiqian.math.layout

import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathAlphabet
import org.tiqian.math.core.MathFamily
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.MathSymbolIdentity
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.OpenTypeMathFont

data class MeasuredMathGlyph(
    val glyphId: UShort,
    val x: Float,
    val advance: Float,
    val inkBounds: MathRect,
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

/** Platform font adapter. Layout consumes only immutable, replayable evidence. */
interface MathFontFace {
    val mathFont: OpenTypeMathFont

    fun resolveSymbol(
        request: MathSymbolGlyphRequest,
        fontSizePx: Float,
    ): ResolvedMathSymbol

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
