package org.tiqian.math.font.android

import android.graphics.Path
import org.tiqian.math.core.*
import org.tiqian.math.layout.*

/** Explicit API 23+ host-text adapter for one caller-supplied face; it performs no font fallback. */
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
        private var handle = NativeMathBridge.createFace(bytes.copyOf())
        private val paths = NativeGlyphPathCache(512)

        fun shape(request: MathTextRunRequest): MeasuredMathRun = synchronized(lock) {
            check(handle != 0L) { "Android host text face is closed" }
            val run = decodeRun(
                NativeMathBridge.shape(handle, request.text, request.fontSizePx, 0),
                faceId = faceId,
                fontClass = null,
                requestedWeight = request.requestedWeight,
                resolvedWeight = resolvedWeight,
                fallbackReason = null,
            )
            val clusterBoundaries = (run.glyphs.map { it.textCluster } + request.text.length).distinct().sorted()
            run.copy(glyphs = run.glyphs.map { glyph ->
                val nextCluster = clusterBoundaries.firstOrNull { it > glyph.textCluster } ?: request.text.length
                glyph.copy(hostTextDecision = MathHostTextFaceDecision(
                    sourceRange = SourceRange(
                        request.sourceRange.start + glyph.textCluster,
                        request.sourceRange.start + nextCluster,
                    ),
                    clusterRangeUtf16 = SourceRange(glyph.textCluster, nextCluster),
                    hostRole = request.origin.name,
                    faceId = faceId,
                    fontKey = faceId.value,
                    requestedWeight = request.requestedWeight,
                    resolvedWeight = resolvedWeight,
                    selectionReason = "ExplicitStandaloneSingleFace",
                    substitutionReason = if (request.requestedWeight == resolvedWeight) null
                        else "RequestedWeightUnavailableInExplicitFace",
                ))
            })
        }

        override fun glyphPath(glyphId: UShort, fontSizePx: Float): Path? = synchronized(lock) {
            check(handle != 0L) { "Android host text face is closed" }
            synchronized(paths) {
                paths.get(glyphId, fontSizePx)?.let(::Path) ?: run {
                    val commands = NativeMathBridge.glyphOutline(handle, glyphId.toInt(), fontSizePx)
                        ?: return@synchronized null
                    val path = decodeAndroidGlyphPath(commands) ?: return@synchronized null
                    paths.put(glyphId, fontSizePx, path)
                    Path(path)
                }
            }
        }

        override fun close() {
            val open = synchronized(lock) {
                if (handle == 0L) return
                val value = handle
                handle = 0L
                value
            }
            synchronized(paths) { paths.clear() }
            NativeMathBridge.destroyFace(open)
        }
    }
}
