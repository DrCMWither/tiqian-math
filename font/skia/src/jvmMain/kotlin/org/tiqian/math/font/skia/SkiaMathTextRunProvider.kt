package org.tiqian.math.font.skia

import org.jetbrains.skia.*
import org.jetbrains.skia.shaper.*
import org.tiqian.math.core.*
import org.tiqian.math.layout.*
import kotlin.math.max

/** Explicit standalone/provider-test adapter. It owns one host-selected text face, not fallback. */
class SkiaMathTextRunProvider private constructor(
    private val replay: TextReplayFace,
) : MathTextRunProvider, SkiaReplayCatalog, AutoCloseable {
    val faceId get() = replay.faceId

    override fun shapeTextAtom(request: MathTextRunRequest): MathTextRunProviderResult =
        restrictedStandaloneTextCapabilityIssue(request)?.let(MathTextRunProviderResult::CapabilityIssue)
            ?: MathTextRunProviderResult.Ready(replay.shape(request))

    override fun replayFace(faceId: MathFaceId): SkiaReplayFace? = replay.takeIf { it.faceId == faceId }
    override fun constructionFace(faceId: MathFaceId): SkiaMathFontFace? = null
    override fun close() = replay.close()

    companion object {
        fun fromBytes(
            faceId: MathFaceId,
            fontBytes: ByteArray,
            resolvedWeight: MathFontWeight = MathFontWeight.Regular,
        ): SkiaMathTextRunProvider = SkiaMathTextRunProvider(TextReplayFace(faceId, fontBytes, resolvedWeight))
    }

    private class TextReplayFace(
        override val faceId: MathFaceId,
        bytes: ByteArray,
        override val resolvedWeight: MathFontWeight,
    ) : SkiaReplayFace, AutoCloseable {
        private val typeface: Typeface
        private val shaper = Shaper.makeShaperDrivenWrapper()

        init {
            val data = Data.makeFromBytes(bytes.copyOf())
            try {
                typeface = requireNotNull(FontMgr.default.makeFromData(data)) { "Skia rejected text provider $faceId" }
            } finally {
                data.close()
            }
        }

        fun shape(request: MathTextRunRequest): MeasuredMathRun {
            if (request.text.isEmpty()) return MeasuredMathRun(emptyList(), 0f, 0f, 0f, false)
            val font = font(request.fontSizePx)
            return try {
                val collector = Collector()
                shaper.shape(
                    request.text,
                    TrivialFontRunIterator(request.text, font),
                    TrivialBidiRunIterator(request.text, 0),
                    HbIcuScriptRunIterator(request.text),
                    TrivialLanguageRunIterator(request.text, request.locale ?: "und"),
                    ShapingOptions.DEFAULT,
                    Float.MAX_VALUE,
                    collector,
                )
                val ids = collector.ids.toShortArray()
                val widths = font.getWidths(ids)
                val fallbackBounds = font.getBounds(ids)
                val clusterBoundaries = (collector.clusters + request.text.length).distinct().sorted()
                val glyphs = ids.indices.map { index ->
                    val bounds = font.getPath(ids[index])?.use { if (it.isEmpty) null else it.computeTightBounds() }
                        ?: fallbackBounds[index]
                    val cluster = collector.clusters[index]
                    val nextCluster = clusterBoundaries.firstOrNull { it > cluster } ?: request.text.length
                    val clusterRange = SourceRange(cluster, nextCluster)
                    val sourceRange = SourceRange(
                        request.sourceRange.start + cluster,
                        request.sourceRange.start + nextCluster,
                    )
                    MeasuredMathGlyph(
                        glyphId = ids[index].toUShort(),
                        x = collector.x[index],
                        baselineOffsetPx = collector.y[index],
                        advance = widths[index],
                        inkBounds = MathRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                        textCluster = cluster,
                        faceId = faceId,
                        fontClass = null,
                        requestedWeight = request.requestedWeight,
                        resolvedWeight = resolvedWeight,
                        fallbackReason = null,
                        hostTextDecision = MathHostTextFaceDecision(
                            sourceRange = sourceRange,
                            clusterRangeUtf16 = clusterRange,
                            hostRole = request.origin.name,
                            faceId = faceId,
                            fontKey = faceId.value,
                            requestedWeight = request.requestedWeight,
                            resolvedWeight = resolvedWeight,
                            selectionReason = "ExplicitStandaloneSingleFace",
                            substitutionReason = if (request.requestedWeight == resolvedWeight) null
                                else "RequestedWeightUnavailableInExplicitFace",
                        ),
                    )
                }
                MeasuredMathRun(
                    glyphs,
                    max(collector.advance, glyphs.maxOfOrNull { it.x + it.advance } ?: 0f),
                    glyphs.maxOfOrNull {
                        -(it.inkBounds.top + it.baselineOffsetPx)
                    }?.coerceAtLeast(0f) ?: 0f,
                    glyphs.maxOfOrNull {
                        it.inkBounds.bottom + it.baselineOffsetPx
                    }?.coerceAtLeast(0f) ?: 0f,
                    ids.any { it.toInt() == 0 },
                    MathGlyphBoundsSource.Outline,
                )
            } finally {
                font.close()
            }
        }

        override fun font(fontSizePx: Float) = Font(typeface, fontSizePx).apply { isSubpixel = true }
        override fun glyphPath(glyphId: UShort, fontSizePx: Float): Path? = font(fontSizePx).use {
            it.getPath(glyphId.toShort())
        }
        override fun canReplayGlyph(glyphId: UShort): Boolean =
            glyphId.toInt() != 0 && glyphId.toInt() < typeface.glyphsCount
        override fun close() { shaper.close(); typeface.close() }

        private class Collector : RunHandler {
            val ids = mutableListOf<Short>()
            val x = mutableListOf<Float>()
            val y = mutableListOf<Float>()
            val clusters = mutableListOf<Int>()
            var advance = 0f
            private var pen = 0f
            override fun beginLine() = Unit
            override fun runInfo(info: RunInfo?) = Unit
            override fun commitRunInfo() = Unit
            override fun runOffset(info: RunInfo?) = Point(pen, 0f)
            override fun commitRun(info: RunInfo?, glyphs: ShortArray?, positions: Array<Point?>?, clusters: IntArray?) {
                if (info == null || glyphs == null || positions == null) return
                glyphs.forEachIndexed { index, glyph ->
                    ids += glyph
                    x += positions[index]?.x ?: pen
                    y += positions[index]?.y ?: 0f
                    this.clusters += clusters?.getOrElse(index) { 0 } ?: 0
                }
                pen += info.advanceX
                advance = pen
            }
            override fun commitLine() = Unit
        }
    }
}
