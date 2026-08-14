package org.tiqian.math.font.android

import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import org.tiqian.math.core.*
import org.tiqian.math.layout.*

/** API 23+ host-text adapter whose primary typeface comes from caller-supplied font bytes. */
class AndroidMathTextRunProvider private constructor(
    private val replay: TextReplayFace,
) : MathTextRunProvider, AndroidReplayCatalog, AutoCloseable {
    val faceId get() = replay.faceId

    override fun shapeTextAtom(request: MathTextRunRequest): MathTextRunProviderResult =
        restrictedStandaloneTextCapabilityIssue(request)?.let(MathTextRunProviderResult::CapabilityIssue)
            ?: MathTextRunProviderResult.Ready(replay.shape(request))
    override fun replayFace(faceId: MathFaceId): AndroidReplayFace? = replay.takeIf { it.faceId == faceId }
    override fun constructionFace(faceId: MathFaceId): AndroidMathFontFace? = null
    override fun close() = replay.close()

    companion object {
        fun fromBytes(
            faceId: MathFaceId,
            fontBytes: ByteArray,
            resolvedWeight: MathFontWeight = MathFontWeight.Regular,
        ): AndroidMathTextRunProvider = AndroidMathTextRunProvider(TextReplayFace(faceId, fontBytes, resolvedWeight))
    }

    private class TextReplayFace(
        override val faceId: MathFaceId,
        bytes: ByteArray,
        override val resolvedWeight: MathFontWeight,
    ) : AndroidReplayFace, AutoCloseable {
        private val lock = Any()
        private val typeface = createReplayTypeface(bytes.copyOf())
        private val paths = NativeGlyphPathCache(512)
        private val textByGlyph = LinkedHashMap<UShort, String>()
        private val glyphByText = LinkedHashMap<String, UShort>()
        private var nextGlyphId = 1

        fun shape(request: MathTextRunRequest): MeasuredMathRun = synchronized(lock) {
            val glyphId = glyphByText.getOrPut(request.text) {
                check(nextGlyphId <= 0xFFFF) { "Android standalone text replay registry is full" }
                nextGlyphId++.toUShort().also { textByGlyph[it] = request.text }
            }
            val paint = paint(request.fontSizePx)
            val width = paint.getRunAdvance(
                request.text, 0, request.text.length, 0, request.text.length, false, request.text.length,
            )
            val path = Path()
            paint.getTextPath(request.text, 0, request.text.length, 0f, 0f, path)
            val rect = RectF()
            if (!path.isEmpty) path.computeBounds(rect, true)
            val bounds = if (path.isEmpty) MathRect(0f, 0f, 0f, 0f)
            else MathRect(rect.left, rect.top, rect.right, rect.bottom)
            val glyph = MeasuredMathGlyph(
                glyphId = glyphId,
                x = 0f,
                advance = width,
                inkBounds = bounds,
                textCluster = 0,
                faceId = faceId,
                fontClass = null,
                requestedWeight = request.requestedWeight,
                resolvedWeight = resolvedWeight,
                fallbackReason = null,
                hostTextDecision = MathHostTextFaceDecision(
                    sourceRange = SourceRange(
                        request.sourceRange.start,
                        request.sourceRange.endExclusive,
                    ),
                    clusterRangeUtf16 = SourceRange(0, request.text.length),
                    hostRole = request.origin.name,
                    faceId = faceId,
                    fontKey = faceId.value,
                    requestedWeight = request.requestedWeight,
                    resolvedWeight = resolvedWeight,
                    selectionReason = "CallerTypefaceWithAndroidFallback",
                    substitutionReason = if (request.requestedWeight == resolvedWeight) null
                        else "RequestedWeightUnavailableInExplicitFace",
                ),
            )
            MeasuredMathRun(
                glyphs = listOf(glyph),
                width = width,
                ascent = (-bounds.top).coerceAtLeast(0f),
                descent = bounds.bottom.coerceAtLeast(0f),
                missingGlyph = request.text.hasMissingGlyph(paint),
                boundsSource = MathGlyphBoundsSource.Outline,
            )
        }

        override fun glyphPath(glyphId: UShort, fontSizePx: Float): Path? = synchronized(lock) {
            synchronized(paths) {
                paths.get(glyphId, fontSizePx)?.let(::Path) ?: run {
                    val text = textByGlyph[glyphId] ?: return@synchronized null
                    val path = Path()
                    paint(fontSizePx).getTextPath(text, 0, text.length, 0f, 0f, path)
                    paths.put(glyphId, fontSizePx, path)
                    Path(path)
                }
            }
        }

        private fun paint(fontSizePx: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@TextReplayFace.typeface
            textSize = fontSizePx
            isSubpixelText = true
        }

        override fun close() {
            synchronized(lock) {
                textByGlyph.clear()
                glyphByText.clear()
            }
            synchronized(paths) { paths.clear() }
        }
    }
}

private fun String.hasMissingGlyph(paint: TextPaint): Boolean {
    var offset = 0
    while (offset < length) {
        val scalar = Character.codePointAt(this, offset)
        if (!paint.hasGlyph(String(Character.toChars(scalar)))) return true
        offset += Character.charCount(scalar)
    }
    return false
}
