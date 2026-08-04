package org.tiqian.math.font.skia

import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathOp
import org.jetbrains.skia.Point
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.shaper.RunHandler
import org.jetbrains.skia.shaper.RunInfo
import org.jetbrains.skia.shaper.Shaper
import org.jetbrains.skia.shaper.ShapingOptions
import org.jetbrains.skia.shaper.TrivialBidiRunIterator
import org.jetbrains.skia.shaper.TrivialFontRunIterator
import org.jetbrains.skia.shaper.TrivialLanguageRunIterator
import org.jetbrains.skia.shaper.TrivialScriptRunIterator
import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathConstructionOutlineEvidence
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathConstructionOutlineCapability
import org.tiqian.math.layout.MathConstructionOutlineUnavailableReason
import org.tiqian.math.layout.MathConstructionTopStroke
import org.tiqian.math.layout.MathGlyphBoundsSource
import org.tiqian.math.layout.MathOperatorGlyphRequest
import org.tiqian.math.layout.MathSymbolGlyphRequest
import org.tiqian.math.layout.MeasuredMathGlyph
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.MeasuredOutlineConstructionRun
import org.tiqian.math.layout.ResolvedMathSymbol
import org.tiqian.math.layout.ResolvedMathSymbolRun
import org.tiqian.math.layout.ResolvedMathOperator
import org.tiqian.math.layout.resolveBackendScalar
import kotlin.math.max

/**
 * A formula-wide face: one sfnt, one MATH table, and one Skia typeface. There is
 * intentionally no fallback font manager in the shaping path.
 */
class SkiaMathFontFace(
    override val mathFont: OpenTypeMathFont,
) : MathComposeFontFace, AutoCloseable {
    val typeface: Typeface
    private val shaper = Shaper.makeShaperDrivenWrapper()
    private val constructionOutlineCache = MathConstructionOutlineCache(this)

    init {
        val data = Data.makeFromBytes(mathFont.bytes)
        try {
            typeface = requireNotNull(FontMgr.default.makeFromData(data)) {
                "Skia could not create a typeface from the supplied OpenType math font"
            }
        } finally {
            data.close()
        }
    }

    override fun resolveSymbol(
        request: MathSymbolGlyphRequest,
        fontSizePx: Float,
    ): ResolvedMathSymbol {
        val selection = request.resolveBackendScalar()
        return ResolvedMathSymbol(
            run = shape(
                unicodeScalarString(selection.scalar),
                fontSizePx,
                request.style,
                request.sourceRange,
            ),
            backendScalar = selection.scalar,
            supported = selection.supported,
        )
    }

    override fun resolveOperator(
        request: MathOperatorGlyphRequest,
        fontSizePx: Float,
    ): ResolvedMathOperator {
        val backendScalar = request.identity.baseScalar
        val backendText = unicodeScalarString(backendScalar)
        return ResolvedMathOperator(
            run = shape(backendText, fontSizePx, request.style, request.sourceRange),
            backendScalar = backendScalar,
            constructionBaseGlyphId = shapeConstructionBase(
                backendText,
                fontSizePx,
                request.sourceRange,
            ).glyphs.singleOrNull()?.glyphId,
        )
    }

    override fun resolveSymbols(
        requests: List<MathSymbolGlyphRequest>,
        fontSizePx: Float,
    ): ResolvedMathSymbolRun {
        require(requests.isNotEmpty()) { "a symbol run must not be empty" }
        require(requests.all { it.style == requests.first().style }) {
            "one shaping run cannot mix math styles"
        }
        require(requests.all {
            it.family == requests.first().family && it.alphabet == requests.first().alphabet
        }) {
            "one shaping run cannot mix math families or alphabets"
        }
        val selections = requests.map { it.resolveBackendScalar() }
        val spans = buildList {
            var utf16Offset = 0
            selections.zip(requests).forEach { (selection, request) ->
                val backendText = unicodeScalarString(selection.scalar)
                add(BackendTextSpan(utf16Offset, utf16Offset + backendText.length, request.sourceRange))
                utf16Offset += backendText.length
            }
        }
        val backendText = selections.joinToString("") { unicodeScalarString(it.scalar) }
        val run = shape(backendText, fontSizePx, requests.first().style, requests.coveredRange())
        val sourceRanges = run.glyphs.map { glyph ->
            spans.firstOrNull { glyph.textCluster in it.startUtf16 until it.endUtf16 }?.sourceRange
                ?: spans.last().sourceRange
        }
        return ResolvedMathSymbolRun(
            run = run,
            backendScalars = selections.map { it.scalar },
            supported = selections.map { it.supported },
            glyphSourceRanges = sourceRanges,
        )
    }

    override fun shape(
        text: String,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = shapeWithBoundsSource(
        text,
        fontSizePx,
        style,
        MathGlyphBoundsSource.FontReported,
    )

    override fun shapeOutlineConstructionBase(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = outlineConstructionRun(
        run = shapeWithBoundsSource(
            text,
            fontSizePx,
            MathStyle.Text,
            MathGlyphBoundsSource.Outline,
        ),
        fontSizePx = fontSizePx,
    )

    private fun shapeWithBoundsSource(
        text: String,
        fontSizePx: Float,
        style: MathStyle,
        boundsSource: MathGlyphBoundsSource,
    ): MeasuredMathRun {
        if (text.isEmpty()) return MeasuredMathRun(emptyList(), 0f, 0f, 0f, false)
        val font = font(fontSizePx)
        return try {
            val collector = GlyphCollector()
            val features = when (style.level) {
                MathStyleLevel.Script -> "ssty=1"
                MathStyleLevel.ScriptScript -> "ssty=2"
                MathStyleLevel.Display, MathStyleLevel.Text -> null
            }
            shaper.shape(
                text,
                TrivialFontRunIterator(text, font),
                TrivialBidiRunIterator(text, 0),
                TrivialScriptRunIterator(text, "Zmth"),
                TrivialLanguageRunIterator(text, "und"),
                features?.let { ShapingOptions.DEFAULT.withFeatures(it) } ?: ShapingOptions.DEFAULT,
                Float.MAX_VALUE,
                collector,
            )
            measuredRun(
                font,
                collector.glyphIds.toShortArray(),
                collector.xPositions.toFloatArray(),
                collector.clusters.toIntArray(),
                collector.advance,
                boundsSource,
            )
        } finally {
            font.close()
        }
    }

    override fun measureGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = measureGlyphWithBoundsSource(
        glyphId,
        fontSizePx,
        MathGlyphBoundsSource.FontReported,
    )

    override fun measureGlyphOutlineBounds(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = measureGlyphWithBoundsSource(
        glyphId,
        fontSizePx,
        MathGlyphBoundsSource.Outline,
    )

    override fun measureOutlineConstructionGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = outlineConstructionRun(
        run = measureGlyphWithBoundsSource(
            glyphId,
            fontSizePx,
            MathGlyphBoundsSource.Outline,
        ),
        fontSizePx = fontSizePx,
    )

    private fun measureGlyphWithBoundsSource(
        glyphId: UShort,
        fontSizePx: Float,
        boundsSource: MathGlyphBoundsSource,
    ): MeasuredMathRun {
        val font = font(fontSizePx)
        return try {
            val ids = shortArrayOf(glyphId.toShort())
            measuredRun(
                font,
                ids,
                floatArrayOf(0f),
                intArrayOf(0),
                font.getWidths(ids).single(),
                boundsSource,
            )
        } finally {
            font.close()
        }
    }

    /**
     * Math layout retains fractional design-space advances. Without subpixel positioning Skia's
     * shaper quantizes [RunInfo.advanceX] to whole device pixels even though the selected glyph's
     * OpenType advance remains fractional, which moves following noads at phase-dependent sizes.
     */
    fun font(fontSizePx: Float): Font = Font(typeface, fontSizePx).apply {
        isSubpixel = true
    }

    fun constructionOutline(
        box: MathBox,
        group: MathConstructionPaintGroup,
    ): MathConstructionOutlineResult = constructionOutlineCache.outline(box, group)

    fun constructionOutlineCacheStats(): MathConstructionOutlineCacheStats = constructionOutlineCache.stats()

    private fun measuredRun(
        font: Font,
        glyphIds: ShortArray,
        xPositions: FloatArray,
        clusters: IntArray,
        runAdvance: Float,
        boundsSource: MathGlyphBoundsSource,
    ): MeasuredMathRun {
        val widths = font.getWidths(glyphIds)
        val reportedBounds = font.getBounds(glyphIds)
        var usedReportedBounds = false
        val glyphs = glyphIds.indices.map { glyphIndex ->
            val bound = if (boundsSource == MathGlyphBoundsSource.Outline) {
                font.getPath(glyphIds[glyphIndex])?.let { outline ->
                    try {
                        if (outline.isEmpty) {
                            usedReportedBounds = true
                            reportedBounds[glyphIndex]
                        } else {
                            outline.computeTightBounds()
                        }
                    } finally {
                        outline.close()
                    }
                } ?: reportedBounds[glyphIndex].also { usedReportedBounds = true }
            } else {
                reportedBounds[glyphIndex]
            }
            MeasuredMathGlyph(
                glyphId = glyphIds[glyphIndex].toUShort(),
                x = xPositions.getOrElse(glyphIndex) { widths.take(glyphIndex).sum() },
                baselineOffsetPx = 0f,
                advance = widths[glyphIndex],
                inkBounds = MathRect(bound.left, bound.top, bound.right, bound.bottom),
                textCluster = clusters.getOrElse(glyphIndex) { 0 },
            )
        }
        val ascent = glyphs.maxOfOrNull { (-it.inkBounds.top).coerceAtLeast(0f) } ?: 0f
        val descent = glyphs.maxOfOrNull { it.inkBounds.bottom.coerceAtLeast(0f) } ?: 0f
        val glyphAdvanceWidth = glyphs.maxOfOrNull { it.x + it.advance } ?: 0f
        return MeasuredMathRun(
            glyphs = glyphs,
            width = max(runAdvance, glyphAdvanceWidth),
            ascent = ascent,
            descent = descent,
            missingGlyph = glyphIds.any { it.toInt() == 0 },
            boundsSource = if (usedReportedBounds) MathGlyphBoundsSource.FontReported else boundsSource,
        )
    }

    private fun outlineConstructionRun(
        run: MeasuredMathRun,
        fontSizePx: Float,
    ): MeasuredOutlineConstructionRun {
        val glyph = run.glyphs.singleOrNull()
            ?: return MeasuredOutlineConstructionRun(
                run,
                MathConstructionOutlineEvidence.Unavailable(
                    MathConstructionOutlineUnavailableReason.ExpectedSingleGlyphRun,
                ),
            )
        if (run.boundsSource != MathGlyphBoundsSource.Outline) {
            return MeasuredOutlineConstructionRun(
                run,
                MathConstructionOutlineEvidence.Unavailable(
                    MathConstructionOutlineUnavailableReason.GlyphOutlineUnavailable,
                ),
            )
        }
        var replayable = false
        val evidence = font(fontSizePx).use { skiaFont ->
            val outline = skiaFont.getPath(glyph.glyphId.toShort())
            if (outline == null || outline.isEmpty) {
                outline?.close()
                MathConstructionOutlineEvidence.Unavailable(
                    MathConstructionOutlineUnavailableReason.GlyphOutlineUnavailable,
                )
            } else {
                replayable = true
                try {
                    outline.topStrokeEvidence(fontSizePx, glyph.x)
                } finally {
                    outline.close()
                }
            }
        }
        return MeasuredOutlineConstructionRun(
            run,
            evidence,
            if (replayable) {
                MathConstructionOutlineCapability.Replayable
            } else {
                MathConstructionOutlineCapability.Unavailable(
                    MathConstructionOutlineUnavailableReason.GlyphOutlineUnavailable,
                )
            },
        )
    }

    private fun Path.topStrokeEvidence(
        fontSizePx: Float,
        glyphX: Float,
    ): MathConstructionOutlineEvidence {
        val designUnitPx = fontSizePx / mathFont.unitsPerEm
        val ruleThicknessPx = mathFont.scaleDesignUnits(mathFont.constants.radicalRuleThickness, fontSizePx)
        val bounds = computeTightBounds()
        val topZone = intersect(
            Rect.makeLTRB(
                bounds.left - designUnitPx,
                bounds.top - designUnitPx,
                bounds.right + designUnitPx,
                bounds.top + RADICAL_TOP_STROKE_ZONE_RULE_MULTIPLIER * ruleThicknessPx,
            ),
        ) ?: return MathConstructionOutlineEvidence.Unavailable(
            MathConstructionOutlineUnavailableReason.TopStrokeUnavailable,
        )
        topZone.use { zone ->
            if (zone.isEmpty) {
                return MathConstructionOutlineEvidence.Unavailable(
                    MathConstructionOutlineUnavailableReason.TopStrokeUnavailable,
                )
            }
            val right = zone.computeTightBounds().right
            val crossSection = intersect(
                Rect.makeLTRB(
                    right - RADICAL_TOP_STROKE_SAMPLE_INNER_INSET_DESIGN_UNITS * designUnitPx,
                    bounds.top - designUnitPx,
                    right - RADICAL_TOP_STROKE_SAMPLE_OUTER_INSET_DESIGN_UNITS * designUnitPx,
                    bounds.top + RADICAL_TOP_STROKE_ZONE_RULE_MULTIPLIER * ruleThicknessPx,
                ),
            ) ?: return MathConstructionOutlineEvidence.Unavailable(
                MathConstructionOutlineUnavailableReason.TopStrokeUnavailable,
            )
            crossSection.use { cross ->
                if (cross.isEmpty) {
                    return MathConstructionOutlineEvidence.Unavailable(
                        MathConstructionOutlineUnavailableReason.TopStrokeUnavailable,
                    )
                }
                val crossBounds = cross.computeTightBounds()
                return MathConstructionOutlineEvidence.Available(
                    MathConstructionTopStroke(
                        topPx = crossBounds.top,
                        bottomPx = crossBounds.bottom,
                        rightPx = right + glyphX,
                    ),
                )
            }
        }
    }

    private fun Path.intersect(rectangle: Rect): Path? {
        val probe = Path.Rect(rectangle)
        return try {
            Path.makeCombining(this, probe, PathOp.INTERSECT)
        } finally {
            probe.close()
        }
    }

    override fun close() {
        constructionOutlineCache.close()
        shaper.close()
        typeface.close()
    }

    private class GlyphCollector : RunHandler {
        val glyphIds = mutableListOf<Short>()
        val xPositions = mutableListOf<Float>()
        val clusters = mutableListOf<Int>()
        var advance: Float = 0f
            private set
        private var penX = 0f

        override fun beginLine() = Unit
        override fun runInfo(info: RunInfo?) = Unit
        override fun commitRunInfo() = Unit
        override fun runOffset(info: RunInfo?): Point = Point(penX, 0f)

        override fun commitRun(
            info: RunInfo?,
            glyphs: ShortArray?,
            positions: Array<Point?>?,
            clusters: IntArray?,
        ) {
            if (info == null || glyphs == null || positions == null) return
            glyphs.forEachIndexed { glyphIndex, glyphId ->
                glyphIds += glyphId
                xPositions += (positions.getOrNull(glyphIndex)?.x ?: penX)
                this.clusters += clusters?.getOrElse(glyphIndex) { 0 } ?: 0
            }
            penX += info.advanceX
            advance = penX
        }

        override fun commitLine() = Unit
    }

    private data class BackendTextSpan(
        val startUtf16: Int,
        val endUtf16: Int,
        val sourceRange: SourceRange,
    )

    private fun List<MathSymbolGlyphRequest>.coveredRange(): SourceRange =
        SourceRange(first().sourceRange.start, last().sourceRange.endExclusive)
}

/** MATH-rule-relative search zone for the radical's built-in horizontal top stroke. */
private const val RADICAL_TOP_STROKE_ZONE_RULE_MULTIPLIER = 2f

/** Cross-section window just inside the stroke's outline-derived right endpoint. */
private const val RADICAL_TOP_STROKE_SAMPLE_INNER_INSET_DESIGN_UNITS = 5f
private const val RADICAL_TOP_STROKE_SAMPLE_OUTER_INSET_DESIGN_UNITS = 3f
