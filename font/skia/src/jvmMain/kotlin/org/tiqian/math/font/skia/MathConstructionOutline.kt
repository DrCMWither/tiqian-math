package org.tiqian.math.font.skia

import org.jetbrains.skia.Path
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathOp
import org.jetbrains.skia.Rect
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathConstructionOutlinePolicy
import org.tiqian.math.core.MathConstructionPaintGroup
import org.tiqian.math.core.MathConstructionPaintKind
import org.tiqian.math.core.MathConstructionShapeKind
import java.util.LinkedHashMap

sealed interface MathConstructionOutlineResult {
    class Available internal constructor(
        /** Face-owned cached path. Callers must not close or mutate it. */
        val path: Path,
        val cacheHit: Boolean,
    ) : MathConstructionOutlineResult

    data class Unavailable(
        val groupId: Int,
        val reason: MathConstructionOutlineUnavailableReason,
        val glyphId: UShort? = null,
    ) : MathConstructionOutlineResult
}

enum class MathConstructionOutlineUnavailableReason {
    NoGroupedGlyph,
    GlyphOutlineUnavailable,
    PathUnionFailed,
}

data class MathConstructionOutlineCacheStats(
    val entries: Int,
    val builds: Long,
    val hits: Long,
)

class MathConstructionOutlineUnavailableException(
    val result: MathConstructionOutlineResult.Unavailable,
) : IllegalStateException(
    "Construction outline ${result.resultLabel()} is unavailable: ${result.reason}" +
        (result.glyphId?.let { " (glyph $it)" } ?: ""),
)

private fun MathConstructionOutlineResult.Unavailable.resultLabel(): String = "group $groupId"

internal class MathConstructionOutlineCache(
    private val face: SkiaMathFontFace,
    private val maximumEntries: Int = 128,
) : AutoCloseable {
    private val paths = object : LinkedHashMap<ConstructionPathKey, Path>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConstructionPathKey, Path>?): Boolean {
            val remove = size > maximumEntries
            if (remove) eldest?.value?.close()
            return remove
        }
    }
    private var builds = 0L
    private var hits = 0L

    @Synchronized
    fun outline(box: MathBox, group: MathConstructionPaintGroup): MathConstructionOutlineResult {
        require(group.outlinePolicy == MathConstructionOutlinePolicy.RequireOutlineUnion)
        val glyphs = box.glyphs.filter { it.constructionGroupId == group.id }
        val rules = box.rules.filter { it.constructionGroupId == group.id }
        if (glyphs.isEmpty()) {
            return MathConstructionOutlineResult.Unavailable(
                group.id,
                MathConstructionOutlineUnavailableReason.NoGroupedGlyph,
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
            return MathConstructionOutlineResult.Available(it, cacheHit = true)
        }

        var combined: Path? = null
        fun merge(piece: Path): Boolean {
            val previous = combined
            if (previous == null) {
                combined = piece
                return true
            }
            val union = Path.makeCombining(previous, piece, PathOp.UNION)
            previous.close()
            piece.close()
            if (union == null || union.isEmpty) {
                union?.close()
                combined = null
                return false
            }
            combined = union
            return true
        }

        glyphs.forEach { glyph ->
            val positioned = face.font(glyph.fontSizePx).use { font ->
                val outline = font.getPath(glyph.glyphId.toShort())
                if (outline == null || outline.isEmpty) {
                    outline?.close()
                    combined?.close()
                    combined = null
                    return MathConstructionOutlineResult.Unavailable(
                        group.id,
                        MathConstructionOutlineUnavailableReason.GlyphOutlineUnavailable,
                        glyph.glyphId,
                    )
                }
                PathBuilder(outline).use { builder ->
                    builder.offset(glyph.x, glyph.baselineY).detach()
                }.also { outline.close() }
            }
            if (!merge(positioned)) {
                return MathConstructionOutlineResult.Unavailable(
                    group.id,
                    MathConstructionOutlineUnavailableReason.PathUnionFailed,
                    glyph.glyphId,
                )
            }
        }
        rules.filter { it.right > it.left && it.bottom > it.top }.forEach { rule ->
            val rectangle = Path.Rect(Rect.makeLTRB(rule.left, rule.top, rule.right, rule.bottom))
            if (!merge(rectangle)) {
                return MathConstructionOutlineResult.Unavailable(
                    group.id,
                    MathConstructionOutlineUnavailableReason.PathUnionFailed,
                )
            }
        }
        val path = combined ?: return MathConstructionOutlineResult.Unavailable(
            group.id,
            MathConstructionOutlineUnavailableReason.PathUnionFailed,
        )
        paths[key] = path
        builds += 1
        return MathConstructionOutlineResult.Available(path, cacheHit = false)
    }

    @Synchronized
    fun stats(): MathConstructionOutlineCacheStats = MathConstructionOutlineCacheStats(
        entries = paths.size,
        builds = builds,
        hits = hits,
    )

    @Synchronized
    override fun close() {
        paths.values.forEach(Path::close)
        paths.clear()
    }
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
