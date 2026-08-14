package org.tiqian.math.font.android

import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathFontClass
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathReplayFaceOwnership
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.MathStyleLevel
import org.tiqian.math.core.SourceRange
import org.tiqian.math.core.unicodeScalarString
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.opentype.OpenTypeMathReader
import org.tiqian.math.font.opentype.OpenTypeGlyphReplayFont
import org.tiqian.math.font.opentype.VerifiedOpenTypeMathSnapshotLoader
import org.tiqian.math.font.opentype.mathGlyphReplayScalar
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
import java.io.File
import kotlin.math.max

/**
 * One Android formula-wide face backed by one immutable OpenType font and Android Typeface.
 * A deterministic Plane-15 cmap exposes every resolved glyph id to Paint without system fallback.
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
    fontBytes: ByteArray,
    override val faceId: MathFaceId,
    override val fontClass: MathFontClass,
    override val resolvedWeight: MathFontWeight,
    override val requestedWeight: MathFontWeight,
) : MathComposeFontFace, AndroidReplayFace, AndroidReplayCatalog, AutoCloseable {
    private val typeface = createReplayTypeface(OpenTypeGlyphReplayFont.attach(fontBytes))
    @Volatile private var closed = false
    private val glyphPathCache = NativeGlyphPathCache(maximumEntries = 512)
    private val constructionPathCache = AndroidMathConstructionPathCache(this)
    private val shapedRunCache = AndroidMeasuredRunCache<ShapeCacheKey>(MAX_SHAPED_RUN_CACHE_ENTRIES)
    private val glyphMeasurementCache =
        AndroidMeasuredRunCache<GlyphMeasurementCacheKey>(MAX_GLYPH_MEASUREMENT_CACHE_ENTRIES)

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
        check(!closed) { "Android math font face is closed" }
        if (text.isEmpty()) return MeasuredMathRun(emptyList(), 0f, 0f, 0f, false)
        val key = ShapeCacheKey(text, fontSizePx.toRawBits(), style.level)
        return shapedRunCache.getOrPut(key) {
            val glyphs = mutableListOf<MeasuredMathGlyph>()
            var penX = 0f
            var ascent = 0f
            var descent = 0f
            var missing = false
            text.forEachUnicodeScalar { scalar, scalarOffset ->
                val glyphId = mathFont.glyphForScalar(scalar, style.nativeStyleLevel()) ?: 0.toUShort()
                val measured = measuredGlyph(glyphId, fontSizePx)
                glyphs += measured.copy(x = penX, textCluster = scalarOffset)
                penX += measured.advance
                ascent = max(ascent, -measured.inkBounds.top)
                descent = max(descent, measured.inkBounds.bottom)
                missing = missing || glyphId == 0.toUShort()
            }
            MeasuredMathRun(
                glyphs = glyphs,
                width = penX,
                ascent = ascent.coerceAtLeast(0f),
                descent = descent.coerceAtLeast(0f),
                missingGlyph = missing,
                boundsSource = MathGlyphBoundsSource.Outline,
            )
        }
    }

    override fun measureGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun {
        check(!closed) { "Android math font face is closed" }
        val key = GlyphMeasurementCacheKey(glyphId, fontSizePx.toRawBits())
        return glyphMeasurementCache.getOrPut(key) {
            val glyph = measuredGlyph(glyphId, fontSizePx)
            MeasuredMathRun(
                glyphs = listOf(glyph),
                width = glyph.advance,
                ascent = (-glyph.inkBounds.top).coerceAtLeast(0f),
                descent = glyph.inkBounds.bottom.coerceAtLeast(0f),
                missingGlyph = glyphId == 0.toUShort(),
                boundsSource = MathGlyphBoundsSource.Outline,
            )
        }
    }

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
    override fun glyphPath(glyphId: UShort, fontSizePx: Float): Path? = synchronized(glyphPathCache) {
        check(!closed) { "Android math font face is closed" }
        glyphPathCache.get(glyphId, fontSizePx)?.let(::Path) ?: run {
            val text = replayText(glyphId)
            val path = Path()
            replayPaint(fontSizePx).getTextPath(text, 0, text.length, 0f, 0f, path)
            if (path.isEmpty && glyphId != 0.toUShort()) return@synchronized null
            glyphPathCache.put(glyphId, fontSizePx, path)
            Path(path)
        }
    }

    fun constructionPath(
        box: org.tiqian.math.core.MathBox,
        group: org.tiqian.math.core.MathConstructionPaintGroup,
    ): AndroidMathConstructionPathResult {
        check(!closed) { "Android math font face is closed" }
        return constructionPathCache.path(box, group)
    }

    fun constructionPathCacheStats(): AndroidMathConstructionPathCacheStats =
        constructionPathCache.stats()

    internal fun measurementCacheStats(): AndroidMathMeasurementCacheStats = AndroidMathMeasurementCacheStats(
        shapedRuns = shapedRunCache.stats(),
        glyphMeasurements = glyphMeasurementCache.stats(),
    )

    fun nativeVersions(): String = "Android Typeface"

    override fun replayFace(faceId: MathFaceId): AndroidReplayFace? = if (faceId == this.faceId) this else null

    override fun constructionFace(faceId: MathFaceId): AndroidMathFontFace? = if (faceId == this.faceId) this else null

    private fun measuredGlyph(glyphId: UShort, fontSizePx: Float): MeasuredMathGlyph {
        val text = replayText(glyphId)
        val paint = replayPaint(fontSizePx)
        val advance = paint.getRunAdvance(text, 0, text.length, 0, text.length, false, text.length)
        val path = Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
        val bounds = RectF()
        if (!path.isEmpty) path.computeBounds(bounds, true)
        return MeasuredMathGlyph(
            glyphId = glyphId,
            x = 0f,
            advance = advance,
            inkBounds = if (path.isEmpty) MathRect(0f, 0f, 0f, 0f)
            else MathRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
            faceId = faceId,
            fontClass = fontClass,
            requestedWeight = requestedWeight,
            resolvedWeight = resolvedWeight,
        )
    }

    private fun replayPaint(fontSizePx: Float) = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@AndroidMathFontFace.typeface
        textSize = fontSizePx
        isSubpixelText = true
    }

    private fun replayText(glyphId: UShort): String =
        String(Character.toChars(mathGlyphReplayScalar(glyphId)))

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

    override fun close() {
        if (closed) return
        closed = true
        constructionPathCache.clear()
        synchronized(glyphPathCache) { glyphPathCache.clear() }
        shapedRunCache.clear()
        glyphMeasurementCache.clear()
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

        internal fun fromPrebakedBytes(
            fontBytes: ByteArray,
            snapshotBytes: ByteArray,
            faceId: MathFaceId,
            fontClass: MathFontClass,
            weight: MathFontWeight,
            expectedSha256: String? = null,
        ): AndroidMathFontFace {
            val immutableBytes = fontBytes.copyOf()
            val mathFont = if (expectedSha256 == null) {
                VerifiedOpenTypeMathSnapshotLoader.load(immutableBytes, snapshotBytes)
            } else {
                VerifiedOpenTypeMathSnapshotLoader.load(immutableBytes, snapshotBytes, expectedSha256)
            }
            return AndroidMathFontFace(
                mathFont,
                immutableBytes,
                faceId,
                fontClass,
                weight,
                weight,
            )
        }

        fun fromAsset(context: Context, assetPath: String): AndroidMathFontFace =
            fromBytes(context.applicationContext.assets.open(assetPath).use { it.readBytes() })

        fun fromResource(context: Context, resourceId: Int): AndroidMathFontFace =
            fromBytes(context.applicationContext.resources.openRawResource(resourceId).use { it.readBytes() })

        fun loadLete(context: Context): AndroidMathFontFace =
            fromPrebakedBytes(
                context.applicationContext.assets.open(LeteAssetPath).use { it.readBytes() },
                context.applicationContext.assets.open(AndroidMathFontFamily.LeteRegularSnapshotAsset).use { it.readBytes() },
                faceId = MathFaceId("lete-sans-math-regular"),
                fontClass = MathFontClass.SansSerif,
                weight = MathFontWeight.Regular,
            )
    }
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

internal data class AndroidMathMeasurementCacheStats(
    val shapedRuns: AndroidMeasuredRunCacheStats,
    val glyphMeasurements: AndroidMeasuredRunCacheStats,
)

internal data class AndroidMeasuredRunCacheStats(
    val entries: Int,
    val hits: Long,
    val misses: Long,
)

private data class ShapeCacheKey(
    val text: String,
    val fontSizeBits: Int,
    val styleLevel: MathStyleLevel,
)

private data class GlyphMeasurementCacheKey(
    val glyphId: UShort,
    val fontSizeBits: Int,
)

private class AndroidMeasuredRunCache<K>(
    private val maximumEntries: Int,
) {
    private val entries = object : LinkedHashMap<K, MeasuredMathRun>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, MeasuredMathRun>?): Boolean =
            size > maximumEntries
    }
    private var hits = 0L
    private var misses = 0L

    @Synchronized
    fun getOrPut(key: K, produce: () -> MeasuredMathRun): MeasuredMathRun {
        entries[key]?.let {
            hits += 1
            return it
        }
        misses += 1
        return produce().also { entries[key] = it }
    }

    @Synchronized
    fun stats(): AndroidMeasuredRunCacheStats = AndroidMeasuredRunCacheStats(entries.size, hits, misses)

    @Synchronized
    fun clear() = entries.clear()
}

private const val MAX_SHAPED_RUN_CACHE_ENTRIES = 256
private const val MAX_GLYPH_MEASUREMENT_CACHE_ENTRIES = 512

private inline fun String.forEachUnicodeScalar(action: (scalar: Int, utf16Offset: Int) -> Unit) {
    var offset = 0
    while (offset < length) {
        val scalar = Character.codePointAt(this, offset)
        action(scalar, offset)
        offset += Character.charCount(scalar)
    }
}

internal fun createReplayTypeface(bytes: ByteArray): Typeface {
    val file = File.createTempFile("tiqian-math-", ".otf")
    return try {
        file.writeBytes(bytes)
        Typeface.createFromFile(file)
    } finally {
        file.delete()
    }
}
