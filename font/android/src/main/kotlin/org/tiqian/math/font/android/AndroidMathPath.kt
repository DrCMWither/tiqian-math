package org.tiqian.math.font.android

import android.graphics.Path
import android.graphics.RectF
import org.tiqian.math.layout.MathConstructionOutlineEvidence
import org.tiqian.math.layout.MathConstructionOutlineUnavailableReason
import org.tiqian.math.layout.MathConstructionTopStroke

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
