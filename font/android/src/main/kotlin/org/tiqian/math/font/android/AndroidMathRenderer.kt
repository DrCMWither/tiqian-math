package org.tiqian.math.font.android

import android.graphics.Canvas
import android.graphics.Paint
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathPaintColor
import org.tiqian.math.core.MathPaintLayer
import org.tiqian.math.core.MathRulePaintRole
import org.tiqian.math.core.MathReplayFaceOwnership

/** Replays one immutable layout box without remeasuring or resolving any glyph. */
class AndroidMathRenderer(
    private val faces: AndroidReplayCatalog,
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

        box.rules.filter {
            it.constructionGroupId == null && it.paintLayer == MathPaintLayer.Background
        }.forEach { rule ->
            paint.color = resolvedMathPaintArgb(rule.paintColor, color)
            canvas.drawRect(
                originX + rule.left,
                baselineFromTop + rule.top,
                originX + rule.right,
                baselineFromTop + rule.bottom,
                paint,
            )
        }

        box.glyphs.filter { it.constructionGroupId == null }.forEach { glyph ->
            check(faces.replayFaceOwnership(glyph.faceId) != MathReplayFaceOwnership.Conflict) {
                "Replay face ownership conflict for ${glyph.faceId}"
            }
            val replayFace = checkNotNull(faces.replayFace(glyph.faceId)) {
                "No Android replay face ${glyph.faceId}"
            }
            val path = replayFace.glyphPath(glyph.glyphId, glyph.fontSizePx)
                ?: error("Glyph ${glyph.glyphId} passed Android preflight without a replayable path")
            path.offset(originX + glyph.x, baselineFromTop + glyph.baselineY)
            paint.color = resolvedMathPaintArgb(glyph.paintColor, color)
            canvas.drawPath(path, paint)
        }
        box.rules.filter {
            it.constructionGroupId == null && it.paintLayer == MathPaintLayer.Foreground &&
                it.paintRole != MathRulePaintRole.Border
        }.forEach { rule ->
            paint.color = resolvedMathPaintArgb(rule.paintColor, color)
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
            val constructionFace = checkNotNull(faces.constructionFace(group.faceId)) {
                "No Android construction face ${group.faceId}"
            }
            when (val construction = constructionFace.constructionPath(box, group)) {
                is AndroidMathConstructionPathResult.Available -> {
                    val saveCount = canvas.save()
                    try {
                        canvas.translate(originX, baselineFromTop)
                        paint.color = resolvedMathPaintArgb(group.paintColor, color)
                        canvas.drawPath(construction.path, paint)
                    } finally {
                        canvas.restoreToCount(saveCount)
                    }
                }
                is AndroidMathConstructionPathResult.Unavailable ->
                    throw AndroidMathConstructionPathUnavailableException(construction)
            }
        }
        box.rules.filter {
            it.constructionGroupId == null && it.paintLayer == MathPaintLayer.Foreground &&
                it.paintRole == MathRulePaintRole.Border
        }.forEach { rule ->
            paint.color = resolvedMathPaintArgb(rule.paintColor, color)
            canvas.drawRect(
                originX + rule.left,
                baselineFromTop + rule.top,
                originX + rule.right,
                baselineFromTop + rule.bottom,
                paint,
            )
        }
    }
}

private fun resolvedMathPaintArgb(explicit: MathPaintColor?, formulaArgb: Int): Int =
    explicit?.modulatedArgb(formulaArgb) ?: formulaArgb
