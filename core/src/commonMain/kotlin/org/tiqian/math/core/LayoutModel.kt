package org.tiqian.math.core

data class MathRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun translated(dx: Float, dy: Float): MathRect = MathRect(
        left + dx,
        top + dy,
        right + dx,
        bottom + dy,
    )
}

data class MathGlyphPlacement(
    val glyphId: UShort,
    val x: Float,
    /** Baseline position relative to the formula baseline; down is positive. */
    val baselineY: Float,
    val advance: Float,
    val inkBounds: MathRect,
    val fontSizePx: Float,
    val sourceRange: SourceRange,
    val style: MathStyle,
    /** Non-null when this glyph participates in one semantic outline construction. */
    val constructionGroupId: Int? = null,
    val faceId: MathFaceId = MathFaceId.LegacySingleFace,
    val fontClass: MathFontClass? = MathFontClass.Serif,
    val requestedWeight: MathFontWeight = MathFontWeight.Regular,
    val resolvedWeight: MathFontWeight = MathFontWeight.Regular,
    /** Present only for MATH-family glyph selection. */
    val fallbackReason: MathFontFallbackReason? = MathFontFallbackReason.RequestedFace,
    /** Present only for glyphs measured by a host text provider. */
    val hostTextDecision: MathHostTextFaceDecision? = null,
)

data class MathRulePlacement(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val sourceRange: SourceRange,
    /** Non-null when this rule must be painted with its attached construction outline. */
    val constructionGroupId: Int? = null,
)

enum class MathConstructionPaintKind {
    Radical,
    Delimiter,
    Accent,
    ExtensibleArrow,
}

enum class MathConstructionShapeKind {
    BaseGlyph,
    Variant,
    Assembly,
}

enum class MathConstructionOutlinePolicy {
    /** Rendering fails explicitly if any required glyph outline cannot be obtained. */
    RequireOutlineUnion,
}

/** Semantic paint ownership retained beyond the otherwise flat glyph/rule replay lists. */
data class MathConstructionPaintGroup(
    val id: Int,
    val kind: MathConstructionPaintKind,
    val shapeKind: MathConstructionShapeKind,
    val sourceRange: SourceRange,
    val outlinePolicy: MathConstructionOutlinePolicy,
    val faceId: MathFaceId = MathFaceId.LegacySingleFace,
)

enum class MathTeXCleanBoxPolicy {
    /** Height/depth are the exact available glyph-outline and rule union. */
    GlyphOutlineUnion,

    /** Height/depth are the completed TeX box assembled from positioned child boxes. */
    CompletedLayoutBox,
}

enum class MathTeXCleanBoxEvidence {
    GlyphOutline,
    FontReportedGlyphBounds,
    HostTextRunMetrics,
    RuleGeometry,
    CompletedChildBox,
    Empty,
}

/**
 * Replayable TeX `clean_box` height/depth, kept independently from host line reserve and
 * painted ink. Composite noads carry their completed child-box metrics instead of being
 * reverse-engineered later from flattened glyphs.
 */
data class MathTeXCleanBoxMetrics(
    val ascent: Float,
    val descent: Float,
    val policy: MathTeXCleanBoxPolicy,
    val evidence: Set<MathTeXCleanBoxEvidence>,
) {
    init {
        require(ascent >= 0f) { "clean-box ascent must not be negative" }
        require(descent >= 0f) { "clean-box descent must not be negative" }
        require(evidence.isNotEmpty()) { "clean-box evidence must not be empty" }
    }

    val height: Float get() = ascent + descent
}

data class MathBox(
    /** TeX logical advance. Ink may overhang either horizontal edge. */
    val width: Float,
    /** TeX box ascent/descent used by recursive math layout; ink may occupy a smaller extent. */
    val ascent: Float,
    val descent: Float,
    val inkBounds: MathRect,
    val glyphs: List<MathGlyphPlacement>,
    val rules: List<MathRulePlacement>,
    val range: SourceRange,
    val constructionPaintGroups: List<MathConstructionPaintGroup> = emptyList(),
    val texCleanBoxMetrics: MathTeXCleanBoxMetrics = MathTeXCleanBoxMetrics(
        ascent = ascent,
        descent = descent,
        policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
        evidence = setOf(MathTeXCleanBoxEvidence.FontReportedGlyphBounds),
    ),
) {
    init {
        require(width >= 0f) { "box width must not be negative" }
        require(ascent >= 0f) { "box ascent must not be negative" }
        require(descent >= 0f) { "box descent must not be negative" }
    }

    val height: Float get() = ascent + descent
    val visualLeft: Float get() = minOf(0f, inkBounds.left)
    val visualRight: Float get() = maxOf(width, inkBounds.right)
    val visualWidth: Float get() = visualRight - visualLeft
}

enum class MathBreakKind {
    PunctuationTrailing,
    BinaryOperatorTrailing,
    RelationTrailing,
}

enum class MathGlueKind {
    None,
    Thin,
    Medium,
    Thick,
}

enum class MathAdjustmentPriority(val order: Int) {
    Punctuation(0),
    Relation(1),
    BinaryOperator(2),
    Other(3),
    None(Int.MAX_VALUE),
}

/** Host policy for resolving the finite TeX glue carried by inline fragments. */
enum class MathLineAdjustmentMode {
    /** Preserve natural glue unless shrinking is required to fit the requested width. */
    Fit,

    /** Also stretch non-final lines, exhausting lower [MathAdjustmentPriority.order] first. */
    Justify,
}

data class MathGlueAdjustment(
    val kind: MathGlueKind,
    val naturalPx: Float,
    val minimumPx: Float,
    val maximumPx: Float,
    val shrinkPx: Float,
    val stretchPx: Float,
    val priority: MathAdjustmentPriority,
) {
    init {
        require(minimumPx <= naturalPx && naturalPx <= maximumPx)
        require(shrinkPx == naturalPx - minimumPx)
        require(stretchPx == maximumPx - naturalPx)
    }

    companion object {
        val Zero = MathGlueAdjustment(
            MathGlueKind.None,
            0f,
            0f,
            0f,
            0f,
            0f,
            MathAdjustmentPriority.None,
        )
    }
}

data class MathBreakOpportunity(
    val afterFragmentIndex: Int,
    val sourceOffset: Int,
    val kind: MathBreakKind,
    val discardedTrailingGlue: MathGlueAdjustment,
    val priority: MathAdjustmentPriority,
    val operatorStaysOnPreviousLine: Boolean = true,
)

data class MathInlineFragment(
    val index: Int,
    val sourceRange: SourceRange,
    /** TeX atom class of this fragment, so a consumer can bind delimiters/punctuation into groups. */
    val atomClass: MathAtomClass,
    /** Visible geometry only: no leading or trailing mathematical glue. */
    val box: MathBox,
    /** Fixed italic correction owned by this character noad or compatible Ord run. */
    val trailingItalicCorrectionPx: Float,
    /** Named TeX glue and host adjustment capacity following this fragment. */
    val trailingGlue: MathGlueAdjustment,
    val breakAfter: MathBreakOpportunity?,
) {
    val trailingAdvancePx: Float get() = trailingItalicCorrectionPx + trailingGlue.naturalPx
}

data class MathLineFragmentPlacement(
    val fragmentIndex: Int,
    val x: Float,
    val resolvedTrailingAdvancePx: Float,
)

data class MathBrokenLine(
    val fragments: List<MathLineFragmentPlacement>,
    /** Sum of logical advances and resolved internal glue. */
    val logicalWidth: Float,
    val inkBounds: MathRect,
    val visualLeft: Float,
    val visualRight: Float,
    val width: Float,
    val inkAscent: Float,
    val inkDescent: Float,
    val ascent: Float,
    val descent: Float,
    val baselineFromTop: Float,
    /** True only when one indivisible legal-break segment is wider than the host constraint. */
    val unbreakableOverflow: Boolean,
)

data class MathBrokenLayout(
    val lines: List<MathBrokenLine>,
    val width: Float,
    val height: Float,
)

data class MathFormulaLineMetrics(
    val fontAscentPx: Float,
    val fontDescentPx: Float,
    val fontLineGapPx: Float,
    val mathLeadingPx: Float,
    val inkAscentPx: Float,
    val inkDescentPx: Float,
    val logicalAscentPx: Float,
    val logicalDescentPx: Float,
) {
    val logicalHeightPx: Float get() = logicalAscentPx + logicalDescentPx

    fun forInk(inkAscent: Float, inkDescent: Float): MathFormulaLineMetrics = copy(
        inkAscentPx = inkAscent,
        inkDescentPx = inkDescent,
        logicalAscentPx = maxOf(fontAscentPx + fontLineGapPx, inkAscent + mathLeadingPx),
        logicalDescentPx = maxOf(fontDescentPx, inkDescent),
    )
}

data class MathLayoutDecision(
    val name: String,
    val range: SourceRange,
    val details: Map<String, String>,
)

data class MathLayoutResult(
    val source: String,
    val mode: MathMode,
    val initialStyle: MathStyle,
    val box: MathBox,
    val fragments: List<MathInlineFragment>,
    val breakOpportunities: List<MathBreakOpportunity>,
    val diagnostics: List<MathDiagnostic>,
    val lineMetrics: MathFormulaLineMetrics,
    val decisions: List<MathLayoutDecision>,
    val debugDump: String,
)
