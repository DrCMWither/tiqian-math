package org.tiqian.math.font.android

import android.graphics.Path
import android.graphics.RectF
import org.tiqian.math.layout.MathConstructionOutlineEvidence
import org.tiqian.math.layout.MathConstructionOutlineUnavailableReason
import org.tiqian.math.layout.MathConstructionTopStroke

internal fun decodeAndroidGlyphPath(commands: FloatArray): Path? {
    if (commands.isEmpty()) return null
    val path = Path()
    var cursor = 0
    while (cursor < commands.size) {
        when (val verb = commands[cursor++].toInt()) {
            0 -> {
                requireRemaining(commands, cursor, 2, verb)
                path.moveTo(commands[cursor], commands[cursor + 1])
                cursor += 2
            }
            1 -> {
                requireRemaining(commands, cursor, 2, verb)
                path.lineTo(commands[cursor], commands[cursor + 1])
                cursor += 2
            }
            2 -> {
                requireRemaining(commands, cursor, 4, verb)
                path.quadTo(
                    commands[cursor],
                    commands[cursor + 1],
                    commands[cursor + 2],
                    commands[cursor + 3],
                )
                cursor += 4
            }
            3 -> {
                requireRemaining(commands, cursor, 6, verb)
                path.cubicTo(
                    commands[cursor],
                    commands[cursor + 1],
                    commands[cursor + 2],
                    commands[cursor + 3],
                    commands[cursor + 4],
                    commands[cursor + 5],
                )
                cursor += 6
            }
            4 -> path.close()
            else -> error("Unknown native outline verb $verb")
        }
    }
    return if (path.isEmpty) null else path
}

private fun requireRemaining(values: FloatArray, cursor: Int, count: Int, verb: Int) {
    require(cursor + count <= values.size) { "Truncated native outline verb $verb" }
}

internal fun Path.radicalTopStrokeEvidence(
    fontSizePx: Float,
    unitsPerEm: Int,
    ruleThicknessPx: Float,
    glyphX: Float,
): MathConstructionOutlineEvidence {
    val designUnitPx = fontSizePx / unitsPerEm
    val bounds = tightBounds()
    val topZone = intersect(
        RectF(
            bounds.left - designUnitPx,
            bounds.top - designUnitPx,
            bounds.right + designUnitPx,
            bounds.top + RadicalTopStrokeZoneRuleMultiplier * ruleThicknessPx,
        ),
    ) ?: return unavailableTopStroke()
    val zoneBounds = topZone.tightBounds()
    val right = zoneBounds.right
    val crossSection = intersect(
        RectF(
            right - RadicalTopStrokeSampleInnerInsetDesignUnits * designUnitPx,
            bounds.top - designUnitPx,
            right - RadicalTopStrokeSampleOuterInsetDesignUnits * designUnitPx,
            bounds.top + RadicalTopStrokeZoneRuleMultiplier * ruleThicknessPx,
        ),
    ) ?: return unavailableTopStroke()
    val crossBounds = crossSection.tightBounds()
    if (crossBounds.isEmpty) return unavailableTopStroke()
    return MathConstructionOutlineEvidence.Available(
        MathConstructionTopStroke(
            topPx = crossBounds.top,
            bottomPx = crossBounds.bottom,
            rightPx = right + glyphX,
        ),
        source = "FreeTypeOutlineAndroidPathCrossSection",
    )
}

private fun Path.tightBounds(): RectF = RectF().also { computeBounds(it, true) }

private fun Path.intersect(rectangle: RectF): Path? {
    val probe = Path().apply { addRect(rectangle, Path.Direction.CW) }
    val result = Path()
    return if (result.op(this, probe, Path.Op.INTERSECT) && !result.isEmpty) result else null
}

private fun unavailableTopStroke() = MathConstructionOutlineEvidence.Unavailable(
    MathConstructionOutlineUnavailableReason.TopStrokeUnavailable,
)

private const val RadicalTopStrokeZoneRuleMultiplier = 2f
private const val RadicalTopStrokeSampleInnerInsetDesignUnits = 5f
private const val RadicalTopStrokeSampleOuterInsetDesignUnits = 3f
