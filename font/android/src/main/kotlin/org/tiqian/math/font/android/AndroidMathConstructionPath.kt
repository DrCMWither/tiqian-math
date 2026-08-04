package org.tiqian.math.font.android

import android.graphics.Path
import android.graphics.RectF
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathConstructionOutlinePolicy
import org.tiqian.math.core.MathConstructionPaintGroup
import org.tiqian.math.core.MathConstructionPaintKind
import org.tiqian.math.core.MathConstructionShapeKind
import java.util.LinkedHashMap

sealed interface AndroidMathConstructionPathResult {
    class Available internal constructor(
        /** Face-owned cached path. Callers must not mutate it. */
        val path: Path,
        val cacheHit: Boolean,
    ) : AndroidMathConstructionPathResult

    data class Unavailable(
        val groupId: Int,
        val reason: AndroidMathConstructionPathUnavailableReason,
        val glyphId: UShort? = null,
    ) : AndroidMathConstructionPathResult
}

enum class AndroidMathConstructionPathUnavailableReason {
    NoGroupedGlyph,
    GlyphOutlineUnavailable,
    PathUnionFailed,
}

data class AndroidMathConstructionPathCacheStats(
    val entries: Int,
    val builds: Long,
    val hits: Long,
)

class AndroidMathConstructionPathUnavailableException(
    val result: AndroidMathConstructionPathResult.Unavailable,
) : IllegalStateException(
    "Construction outline group ${result.groupId} is unavailable: ${result.reason}" +
        (result.glyphId?.let { " (glyph $it)" } ?: ""),
)

internal class AndroidMathConstructionPathCache(
    private val face: AndroidMathFontFace,
    private val maximumEntries: Int = 128,
) {
    private val paths = object : LinkedHashMap<ConstructionPathKey, Path>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConstructionPathKey, Path>?): Boolean =
            size > maximumEntries
    }
    private var builds = 0L
    private var hits = 0L

    @Synchronized
    fun path(box: MathBox, group: MathConstructionPaintGroup): AndroidMathConstructionPathResult {
        require(group.outlinePolicy == MathConstructionOutlinePolicy.RequireOutlineUnion)
        val glyphs = box.glyphs.filter { it.constructionGroupId == group.id }
        val rules = box.rules.filter { it.constructionGroupId == group.id }
        if (glyphs.isEmpty()) {
            return AndroidMathConstructionPathResult.Unavailable(
                group.id,
                AndroidMathConstructionPathUnavailableReason.NoGroupedGlyph,
            )
        }
        val key = ConstructionPathKey(
            kind = group.kind,
            shapeKind = group.shapeKind,
            glyphs = glyphs.map {
                ConstructionGlyphKey(it.glyphId, it.fontSizePx, it.x, it.baselineY)
            },
            rules = rules.map { ConstructionRuleKey(it.left, it.top, it.right, it.bottom) },
        )
        paths[key]?.let {
            hits += 1
            return AndroidMathConstructionPathResult.Available(it, cacheHit = true)
        }

        var combined: Path? = null
        fun merge(piece: Path): Boolean {
            val previous = combined
            if (previous == null) {
                combined = piece
                return true
            }
            val union = Path()
            if (!union.op(previous, piece, Path.Op.UNION) || union.isEmpty) {
                combined = null
                return false
            }
            combined = union
            return true
        }

        glyphs.forEach { glyph ->
            val positioned = face.glyphPath(glyph.glyphId, glyph.fontSizePx)
                ?: return AndroidMathConstructionPathResult.Unavailable(
                    group.id,
                    AndroidMathConstructionPathUnavailableReason.GlyphOutlineUnavailable,
                    glyph.glyphId,
                )
            positioned.offset(glyph.x, glyph.baselineY)
            if (!merge(positioned)) {
                return AndroidMathConstructionPathResult.Unavailable(
                    group.id,
                    AndroidMathConstructionPathUnavailableReason.PathUnionFailed,
                    glyph.glyphId,
                )
            }
        }
        rules.filter { it.right > it.left && it.bottom > it.top }.forEach { rule ->
            val rectangle = Path().apply {
                addRect(
                    RectF(rule.left, rule.top, rule.right, rule.bottom),
                    Path.Direction.CW,
                )
            }
            if (!merge(rectangle)) {
                return AndroidMathConstructionPathResult.Unavailable(
                    group.id,
                    AndroidMathConstructionPathUnavailableReason.PathUnionFailed,
                )
            }
        }
        val path = combined ?: return AndroidMathConstructionPathResult.Unavailable(
            group.id,
            AndroidMathConstructionPathUnavailableReason.PathUnionFailed,
        )
        paths[key] = path
        builds += 1
        return AndroidMathConstructionPathResult.Available(path, cacheHit = false)
    }

    @Synchronized
    fun stats(): AndroidMathConstructionPathCacheStats = AndroidMathConstructionPathCacheStats(
        entries = paths.size,
        builds = builds,
        hits = hits,
    )

    @Synchronized
    fun clear() = paths.clear()
}

private data class ConstructionPathKey(
    val kind: MathConstructionPaintKind,
    val shapeKind: MathConstructionShapeKind,
    val glyphs: List<ConstructionGlyphKey>,
    val rules: List<ConstructionRuleKey>,
)

private data class ConstructionGlyphKey(
    val glyphId: UShort,
    val fontSizePx: Float,
    val x: Float,
    val baselineY: Float,
)

private data class ConstructionRuleKey(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)
