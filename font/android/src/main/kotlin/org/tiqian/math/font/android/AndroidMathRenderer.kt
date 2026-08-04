package org.tiqian.math.font.android

import android.graphics.Canvas
import android.graphics.Paint
import org.tiqian.math.core.MathBox

/** Replays one immutable layout box without remeasuring or resolving any glyph. */
class AndroidMathRenderer(
    private val face: AndroidMathFontFace,
) {
    fun drawBox(
        canvas: Canvas,
        box: MathBox,
        originX: Float,
        baselineFromTop: Float,
        color: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

        box.glyphs.filter { it.constructionGroupId == null }.forEach { glyph ->
            val path = face.glyphPath(glyph.glyphId, glyph.fontSizePx)
                ?: error("Glyph ${glyph.glyphId} passed Android preflight without a replayable path")
            path.offset(originX + glyph.x, baselineFromTop + glyph.baselineY)
            canvas.drawPath(path, paint)
        }
        box.rules.filter { it.constructionGroupId == null }.forEach { rule ->
            canvas.drawRect(
                originX + rule.left,
                baselineFromTop + rule.top,
                originX + rule.right,
                baselineFromTop + rule.bottom,
                paint,
            )
        }

        val knownGroupIds = box.constructionPaintGroups.mapTo(mutableSetOf()) { it.id }
        val referencedGroupIds = buildSet {
            box.glyphs.mapNotNullTo(this) { it.constructionGroupId }
            box.rules.mapNotNullTo(this) { it.constructionGroupId }
        }
        check(knownGroupIds == referencedGroupIds) {
            "Construction paint ownership mismatch: known=$knownGroupIds referenced=$referencedGroupIds"
        }
        box.constructionPaintGroups.forEach { group ->
            when (val construction = face.constructionPath(box, group)) {
                is AndroidMathConstructionPathResult.Available -> {
                    val saveCount = canvas.save()
                    try {
                        canvas.translate(originX, baselineFromTop)
                        canvas.drawPath(construction.path, paint)
                    } finally {
                        canvas.restoreToCount(saveCount)
                    }
                }
                is AndroidMathConstructionPathResult.Unavailable ->
                    throw AndroidMathConstructionPathUnavailableException(construction)
            }
        }
    }
}
