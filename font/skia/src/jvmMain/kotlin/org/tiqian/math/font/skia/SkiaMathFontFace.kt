package org.tiqian.math.font.skia

import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Point
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
import org.tiqian.math.layout.MathOperatorGlyphRequest
import org.tiqian.math.layout.MathSymbolGlyphRequest
import org.tiqian.math.layout.MeasuredMathGlyph
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.ResolvedMathSymbol
import org.tiqian.math.layout.ResolvedMathSymbolRun
import org.tiqian.math.layout.ResolvedMathOperator
import kotlin.math.max

/**
 * A formula-wide face: one sfnt, one MATH table, and one Skia typeface. There is
 * intentionally no fallback font manager in the shaping path.
 */
class SkiaMathFontFace(
    override val mathFont: OpenTypeMathFont,
) : MathFontFace, AutoCloseable {
    val typeface: Typeface
    private val shaper = Shaper.makeShaperDrivenWrapper()

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
        val selection = resolveBackendScalar(request)
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
        val selections = requests.map(::resolveBackendScalar)
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
    ): MeasuredMathRun {
        if (text.isEmpty()) return MeasuredMathRun(emptyList(), 0f, 0f, 0f, false)
        val font = Font(typeface, fontSizePx)
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
    ): MeasuredMathRun {
        val font = Font(typeface, fontSizePx)
        return try {
            val ids = shortArrayOf(glyphId.toShort())
            measuredRun(font, ids, floatArrayOf(0f), intArrayOf(0), font.getWidths(ids).single())
        } finally {
            font.close()
        }
    }

    fun font(fontSizePx: Float): Font = Font(typeface, fontSizePx)

    private fun measuredRun(
        font: Font,
        glyphIds: ShortArray,
        xPositions: FloatArray,
        clusters: IntArray,
        runAdvance: Float,
    ): MeasuredMathRun {
        val widths = font.getWidths(glyphIds)
        val bounds = font.getBounds(glyphIds)
        val glyphs = glyphIds.indices.map { glyphIndex ->
            val bound = bounds[glyphIndex]
            MeasuredMathGlyph(
                glyphId = glyphIds[glyphIndex].toUShort(),
                x = xPositions.getOrElse(glyphIndex) { widths.take(glyphIndex).sum() },
                advance = widths[glyphIndex],
                inkBounds = MathRect(bound.left, bound.top, bound.right, bound.bottom),
                textCluster = clusters.getOrElse(glyphIndex) { 0 },
            )
        }
        val ascent = glyphs.maxOfOrNull { (-it.inkBounds.top).coerceAtLeast(0f) } ?: 0f
        val descent = glyphs.maxOfOrNull { it.inkBounds.bottom.coerceAtLeast(0f) } ?: 0f
        return MeasuredMathRun(
            glyphs = glyphs,
            width = max(runAdvance, glyphs.maxOfOrNull { it.x + it.advance } ?: 0f),
            ascent = ascent,
            descent = descent,
            missingGlyph = glyphIds.any { it.toInt() == 0 },
        )
    }

    override fun close() {
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

    private data class BackendScalarSelection(val scalar: Int, val supported: Boolean)

    private data class BackendTextSpan(
        val startUtf16: Int,
        val endUtf16: Int,
        val sourceRange: SourceRange,
    )

    private fun resolveBackendScalar(request: MathSymbolGlyphRequest): BackendScalarSelection {
        if (request.alphabet == MathAlphabet.MathNormal) {
            val scalar = if (request.family == MathFamily.Letters) {
                encodeMathAlphabetScalar(request.identity.baseScalar, MathAlphabet.Italic)
                    ?: request.identity.baseScalar
            } else {
                request.identity.baseScalar
            }
            return BackendScalarSelection(scalar, supported = true)
        }
        if (request.alphabet == MathAlphabet.Roman) {
            return BackendScalarSelection(request.identity.baseScalar, supported = true)
        }
        val scalar = encodeMathAlphabetScalar(request.identity.baseScalar, request.alphabet)
            ?: return BackendScalarSelection(request.identity.baseScalar, supported = false)
        return BackendScalarSelection(scalar, supported = true)
    }

    private fun List<MathSymbolGlyphRequest>.coveredRange(): SourceRange =
        SourceRange(first().sourceRange.start, last().sourceRange.endExclusive)
}
