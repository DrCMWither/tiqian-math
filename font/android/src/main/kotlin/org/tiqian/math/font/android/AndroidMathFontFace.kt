package org.tiqian.math.font.android

import android.content.Context
import android.graphics.Path
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathFontClass
import org.tiqian.math.core.MathFontFallbackReason
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathReplayFaceOwnership
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.MathStyleLevel
import org.tiqian.math.core.SourceRange
import org.tiqian.math.core.unicodeScalarString
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.opentype.OpenTypeMathReader
import org.tiqian.math.layout.MathConstructionOutlineCapability
import org.tiqian.math.layout.MathConstructionOutlineEvidence
import org.tiqian.math.layout.MathConstructionOutlineUnavailableReason
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathGlyphBoundsSource
import org.tiqian.math.layout.MathOperatorGlyphRequest
import org.tiqian.math.layout.MathSymbolGlyphRequest
import org.tiqian.math.layout.MeasuredMathGlyph
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.MeasuredOutlineConstructionRun
import org.tiqian.math.layout.ResolvedMathOperator
import org.tiqian.math.layout.ResolvedMathSymbol
import org.tiqian.math.layout.ResolvedMathSymbolRun
import org.tiqian.math.layout.resolveBackendScalar
import java.util.LinkedHashMap
import kotlin.math.max

/**
 * One Android formula-wide face backed by one immutable byte array, one HarfBuzz face, and one
 * FreeType face. No Android system-font fallback or character-level Paint measurement is used.
 */
interface AndroidReplayFace {
    val faceId: MathFaceId
    val resolvedWeight: MathFontWeight
    fun glyphPath(glyphId: UShort, fontSizePx: Float): Path?
}

interface AndroidReplayCatalog {
    fun replayFace(faceId: MathFaceId): AndroidReplayFace?
    fun constructionFace(faceId: MathFaceId): AndroidMathFontFace?
    fun replayFaceOwnership(faceId: MathFaceId): MathReplayFaceOwnership =
        if (replayFace(faceId) == null) MathReplayFaceOwnership.Missing else MathReplayFaceOwnership.Unique
}

class AndroidMathFontFace private constructor(
    override val mathFont: OpenTypeMathFont,
    nativeBytes: ByteArray,
    override val faceId: MathFaceId,
    override val fontClass: MathFontClass,
    override val resolvedWeight: MathFontWeight,
    override val requestedWeight: MathFontWeight,
) : MathComposeFontFace, AndroidReplayFace, AndroidReplayCatalog, AutoCloseable {
    private val faceLock = Any()
    private var nativeHandle = NativeMathBridge.createFace(nativeBytes)
    private val glyphPathCache = NativeGlyphPathCache(maximumEntries = 512)
    private val constructionPathCache = AndroidMathConstructionPathCache(this)

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
        val scalar = request.identity.baseScalar
        val text = unicodeScalarString(scalar)
        return ResolvedMathOperator(
            run = shape(text, fontSizePx, request.style, request.sourceRange),
            backendScalar = scalar,
            constructionBaseGlyphId = shapeConstructionBase(
                text,
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
            var offset = 0
            selections.zip(requests).forEach { (selection, request) ->
                val backendText = unicodeScalarString(selection.scalar)
                add(BackendSpan(offset, offset + backendText.length, request.sourceRange))
                offset += backendText.length
            }
        }
        val text = selections.joinToString("") { unicodeScalarString(it.scalar) }
        val run = shape(text, fontSizePx, requests.first().style, requests.coveredRange())
        return ResolvedMathSymbolRun(
            run = run,
            backendScalars = selections.map { it.scalar },
            supported = selections.map { it.supported },
            glyphSourceRanges = run.glyphs.map { glyph ->
                spans.firstOrNull { glyph.textCluster in it.startUtf16 until it.endUtf16 }?.range
                    ?: spans.last().range
            },
        )
    }

    override fun shape(
        text: String,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun {
        if (text.isEmpty()) return MeasuredMathRun(emptyList(), 0f, 0f, 0f, false)
        val packed = withNativeHandle { handle ->
            NativeMathBridge.shape(handle, text, fontSizePx, style.nativeStyleLevel())
        }
        return decodeRun(packed, faceId, fontClass, requestedWeight, resolvedWeight)
    }

    override fun measureGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = decodeGlyphMeasurement(
        nativeGlyphMeasurement(glyphId, fontSizePx),
        faceId,
        fontClass,
        requestedWeight,
        resolvedWeight,
    )

    override fun measureGlyphOutlineBounds(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = measureGlyph(glyphId, fontSizePx, style, sourceRange)

    override fun measureOutlineConstructionGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = outlineConstructionRun(
        measureGlyph(glyphId, fontSizePx, style, sourceRange),
        fontSizePx,
    )

    override fun shapeOutlineConstructionBase(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = outlineConstructionRun(
        shape(text, fontSizePx, MathStyle.Text, sourceRange),
        fontSizePx,
    )

    /** Returns a caller-owned copy; the cached path is never exposed for mutation. */
    override fun glyphPath(glyphId: UShort, fontSizePx: Float): Path? = withNativeHandle { handle ->
        synchronized(glyphPathCache) {
            glyphPathCache.get(glyphId, fontSizePx)?.let(::Path) ?: run {
                val commands = NativeMathBridge.glyphOutline(handle, glyphId.toInt(), fontSizePx)
                    ?: return@withNativeHandle null
                val path = decodeAndroidGlyphPath(commands) ?: return@withNativeHandle null
                glyphPathCache.put(glyphId, fontSizePx, path)
                Path(path)
            }
        }
    }

    fun constructionPath(
        box: org.tiqian.math.core.MathBox,
        group: org.tiqian.math.core.MathConstructionPaintGroup,
    ): AndroidMathConstructionPathResult = withNativeHandle {
        constructionPathCache.path(box, group)
    }

    fun constructionPathCacheStats(): AndroidMathConstructionPathCacheStats =
        constructionPathCache.stats()

    fun nativeVersions(): String = NativeMathBridge.nativeVersions()

    override fun replayFace(faceId: MathFaceId): AndroidReplayFace? = if (faceId == this.faceId) this else null

    override fun constructionFace(faceId: MathFaceId): AndroidMathFontFace? = if (faceId == this.faceId) this else null

    private fun nativeGlyphMeasurement(glyphId: UShort, fontSizePx: Float): FloatArray =
        withNativeHandle { handle -> NativeMathBridge.measureGlyph(handle, glyphId.toInt(), fontSizePx) }

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
        val path = glyphPath(glyph.glyphId, fontSizePx)
            ?: return MeasuredOutlineConstructionRun(
                run,
                MathConstructionOutlineEvidence.Unavailable(
                    MathConstructionOutlineUnavailableReason.GlyphOutlineUnavailable,
                ),
                MathConstructionOutlineCapability.Unavailable(
                    MathConstructionOutlineUnavailableReason.GlyphOutlineUnavailable,
                ),
            )
        val evidence = path.radicalTopStrokeEvidence(
            fontSizePx = fontSizePx,
            unitsPerEm = mathFont.unitsPerEm,
            ruleThicknessPx = mathFont.scaleDesignUnits(
                mathFont.constants.radicalRuleThickness,
                fontSizePx,
            ),
            glyphX = glyph.x,
        )
        return MeasuredOutlineConstructionRun(
            run = run,
            evidence = evidence,
            outlineCapability = MathConstructionOutlineCapability.Replayable,
        )
    }

    private fun <T> withNativeHandle(block: (Long) -> T): T = synchronized(faceLock) {
        check(nativeHandle != 0L) { "Android math font face is closed" }
        block(nativeHandle)
    }

    override fun close() {
        val handle = synchronized(faceLock) {
            if (nativeHandle == 0L) return
            val openHandle = nativeHandle
            nativeHandle = 0L
            openHandle
        }
        constructionPathCache.clear()
        synchronized(glyphPathCache) { glyphPathCache.clear() }
        NativeMathBridge.destroyFace(handle)
    }

    companion object {
        const val LeteAssetPath = "org/tiqian/math/fonts/LeteSansMath-Regular.otf"

        fun fromBytes(
            fontBytes: ByteArray,
            faceId: MathFaceId = MathFaceId.LegacySingleFace,
            fontClass: MathFontClass = MathFontClass.Serif,
            weight: MathFontWeight = MathFontWeight.Regular,
            requestedWeight: MathFontWeight = weight,
        ): AndroidMathFontFace {
            val immutableBytes = fontBytes.copyOf()
            return AndroidMathFontFace(
                OpenTypeMathReader().read(immutableBytes),
                immutableBytes,
                faceId,
                fontClass,
                weight,
                requestedWeight,
            )
        }

        fun fromAsset(context: Context, assetPath: String): AndroidMathFontFace =
            fromBytes(context.applicationContext.assets.open(assetPath).use { it.readBytes() })

        fun fromResource(context: Context, resourceId: Int): AndroidMathFontFace =
            fromBytes(context.applicationContext.resources.openRawResource(resourceId).use { it.readBytes() })

        fun loadLete(context: Context): AndroidMathFontFace =
            fromBytes(
                context.applicationContext.assets.open(LeteAssetPath).use { it.readBytes() },
                faceId = MathFaceId("lete-sans-math-regular"),
                fontClass = MathFontClass.SansSerif,
            )
    }
}

internal fun decodeRun(
    packed: FloatArray,
    faceId: MathFaceId = MathFaceId.LegacySingleFace,
    fontClass: MathFontClass? = MathFontClass.Serif,
    requestedWeight: MathFontWeight = MathFontWeight.Regular,
    resolvedWeight: MathFontWeight = MathFontWeight.Regular,
    fallbackReason: MathFontFallbackReason? = MathFontFallbackReason.RequestedFace,
): MeasuredMathRun {
    require(packed.size >= RunHeaderSize) { "Truncated native shaping result" }
    val glyphCount = packed[0].toInt()
    require(packed.size == RunHeaderSize + glyphCount * RunGlyphStride) {
        "Malformed native shaping result"
    }
    val glyphs = List(glyphCount) { index ->
        val base = RunHeaderSize + index * RunGlyphStride
        MeasuredMathGlyph(
            glyphId = packed[base].toInt().toUShort(),
            textCluster = packed[base + 1].toInt(),
            x = packed[base + 2],
            baselineOffsetPx = packed[base + 3],
            advance = packed[base + 4],
            inkBounds = MathRect(
                packed[base + 5],
                packed[base + 6],
                packed[base + 7],
                packed[base + 8],
            ),
            faceId = faceId,
            fontClass = fontClass,
            requestedWeight = requestedWeight,
            resolvedWeight = resolvedWeight,
            fallbackReason = fallbackReason,
        )
    }
    val glyphAdvanceWidth = glyphs.maxOfOrNull { it.x + it.advance } ?: 0f
    return MeasuredMathRun(
        glyphs = glyphs,
        width = max(packed[1], glyphAdvanceWidth),
        ascent = packed[2],
        descent = packed[3],
        missingGlyph = packed[4] != 0f,
        boundsSource = MathGlyphBoundsSource.Outline,
    )
}

internal fun decodeGlyphMeasurement(
    packed: FloatArray,
    faceId: MathFaceId = MathFaceId.LegacySingleFace,
    fontClass: MathFontClass? = MathFontClass.Serif,
    requestedWeight: MathFontWeight = MathFontWeight.Regular,
    resolvedWeight: MathFontWeight = MathFontWeight.Regular,
): MeasuredMathRun {
    require(packed.size == 7) { "Malformed native glyph measurement" }
    val glyph = MeasuredMathGlyph(
        glyphId = packed[0].toInt().toUShort(),
        x = 0f,
        advance = packed[1],
        inkBounds = MathRect(packed[2], packed[3], packed[4], packed[5]),
        faceId = faceId,
        fontClass = fontClass,
        requestedWeight = requestedWeight,
        resolvedWeight = resolvedWeight,
    )
    return MeasuredMathRun(
        glyphs = listOf(glyph),
        width = glyph.advance,
        ascent = (-glyph.inkBounds.top).coerceAtLeast(0f),
        descent = glyph.inkBounds.bottom.coerceAtLeast(0f),
        missingGlyph = glyph.glyphId == 0.toUShort(),
        boundsSource = if (packed[6] != 0f) {
            MathGlyphBoundsSource.Outline
        } else {
            MathGlyphBoundsSource.FontReported
        },
    )
}

internal fun MathStyle.nativeStyleLevel(): Int = when (level) {
    MathStyleLevel.Display, MathStyleLevel.Text -> 0
    MathStyleLevel.Script -> 1
    MathStyleLevel.ScriptScript -> 2
}

private fun List<MathSymbolGlyphRequest>.coveredRange(): SourceRange =
    SourceRange(first().sourceRange.start, last().sourceRange.endExclusive)

private data class BackendSpan(
    val startUtf16: Int,
    val endUtf16: Int,
    val range: SourceRange,
)

internal class NativeGlyphPathCache(private val maximumEntries: Int) {
    private val paths = object : LinkedHashMap<GlyphPathKey, Path>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GlyphPathKey, Path>?): Boolean =
            size > maximumEntries
    }

    fun get(glyphId: UShort, fontSizePx: Float): Path? = paths[GlyphPathKey(glyphId, fontSizePx)]
    fun put(glyphId: UShort, fontSizePx: Float, path: Path) {
        paths[GlyphPathKey(glyphId, fontSizePx)] = path
    }
    fun clear() = paths.clear()
}

private data class GlyphPathKey(val glyphId: UShort, val fontSizePx: Float)

private const val RunHeaderSize = 5
private const val RunGlyphStride = 9
