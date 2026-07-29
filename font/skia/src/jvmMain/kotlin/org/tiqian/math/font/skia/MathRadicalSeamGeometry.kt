package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.math.min
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathOp
import org.jetbrains.skia.Rect
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathConstructionPaintGroup
import org.tiqian.math.core.MathConstructionPaintKind

/** One vertical cross-section through a horizontal stroke near the radical/overbar seam. */
data class MathRadicalSeamBand(
    val topPx: Float,
    val bottomPx: Float,
) {
    val centerPx: Float get() = (topPx + bottomPx) / 2f
    val thicknessPx: Float get() = bottomPx - topPx
}

/**
 * Diagnostic geometry measured from the actual glyph outlines replayed by Skia. Layout ink
 * bounds are deliberately not used as an oracle here.
 */
data class MathRadicalSeamGeometry(
    val glyphStroke: MathRadicalSeamBand,
    val overbar: MathRadicalSeamBand,
    val glyphStrokeRightPx: Float,
    val overbarLeftPx: Float,
    val horizontalOverlapPx: Float,
    val topEdgeErrorPx: Float,
    val bottomEdgeErrorPx: Float,
    val centerlineErrorPx: Float,
    val thicknessErrorPx: Float,
    /** Floating-point allowance for the shared mathematical top edge. */
    val coordinateAlignmentTolerancePx: Float,
    /** Generic design-unit allowance for an outline stroke/MATH constant rounding difference. */
    val strokeThicknessTolerancePx: Float,
    val policy: String = "ActualGlyphOutlineSeamCrossSection",
) {
    val edgesAndThicknessMatch: Boolean
        get() = horizontalOverlapPx + coordinateAlignmentTolerancePx >= 0f &&
            abs(topEdgeErrorPx) <= coordinateAlignmentTolerancePx &&
            abs(bottomEdgeErrorPx) <= strokeThicknessTolerancePx &&
            abs(centerlineErrorPx) <= strokeThicknessTolerancePx / 2f &&
            abs(thicknessErrorPx) <= strokeThicknessTolerancePx
}

/**
 * Measures the top horizontal stroke immediately inside the radical/overbar seam. The probe
 * location and tolerance are expressed in font design units, so this diagnostic has no
 * font-name branch or fixed pixel offset.
 */
fun SkiaMathFontFace.radicalSeamGeometry(
    box: MathBox,
    group: MathConstructionPaintGroup,
): MathRadicalSeamGeometry {
    require(group.kind == MathConstructionPaintKind.Radical)
    val glyphs = box.glyphs.filter { it.constructionGroupId == group.id }
    val overbarPlacement = box.rules.single { it.constructionGroupId == group.id }
    require(glyphs.isNotEmpty()) { "Radical construction group ${group.id} has no glyphs" }
    val fontSizePx = glyphs.first().fontSizePx
    require(glyphs.all { it.fontSizePx == fontSizePx }) {
        "One radical construction must use one font size"
    }
    val designUnitPx = fontSizePx / mathFont.unitsPerEm
    val fontRuleThicknessPx = mathFont.scaleDesignUnits(mathFont.constants.radicalRuleThickness, fontSizePx)
    val coordinateAlignmentTolerancePx = designUnitPx / 16f
    val strokeThicknessTolerancePx = 2f * designUnitPx

    positionedGlyphUnion(glyphs.map { glyph ->
        val positioned = font(glyph.fontSizePx).use { skiaFont ->
            val outline = checkNotNull(skiaFont.getPath(glyph.glyphId.toShort())) {
                "Glyph ${glyph.glyphId} has no outline for radical seam diagnostics"
            }
            PathBuilder(outline).use { builder ->
                builder.offset(glyph.x, glyph.baselineY).detach()
            }.also { outline.close() }
        }
        positioned
    }).use { glyphOutline ->
        val outlineBounds = glyphOutline.computeTightBounds()
        val topZoneBottom = outlineBounds.top + 2f * fontRuleThicknessPx
        val topZone = intersect(
            glyphOutline,
            Rect.makeLTRB(
                outlineBounds.left - designUnitPx,
                outlineBounds.top - designUnitPx,
                outlineBounds.right + designUnitPx,
                topZoneBottom,
            ),
        )
        topZone.use { zone ->
            require(!zone.isEmpty) { "Radical top-stroke zone is empty" }
            val glyphStrokeRight = zone.computeTightBounds().right
            val sampleX = min(glyphStrokeRight, overbarPlacement.left) - 4f * designUnitPx
            val crossSection = intersect(
                glyphOutline,
                Rect.makeLTRB(
                    sampleX - designUnitPx,
                    outlineBounds.top - designUnitPx,
                    sampleX + designUnitPx,
                    topZoneBottom,
                ),
            )
            crossSection.use { cross ->
                require(!cross.isEmpty) { "Radical top-stroke seam cross-section is empty" }
                val crossBounds = cross.computeTightBounds()
                val glyphBand = MathRadicalSeamBand(crossBounds.top, crossBounds.bottom)
                val overbarBand = MathRadicalSeamBand(overbarPlacement.top, overbarPlacement.bottom)
                return MathRadicalSeamGeometry(
                    glyphStroke = glyphBand,
                    overbar = overbarBand,
                    glyphStrokeRightPx = glyphStrokeRight,
                    overbarLeftPx = overbarPlacement.left,
                    horizontalOverlapPx = glyphStrokeRight - overbarPlacement.left,
                    topEdgeErrorPx = overbarBand.topPx - glyphBand.topPx,
                    bottomEdgeErrorPx = overbarBand.bottomPx - glyphBand.bottomPx,
                    centerlineErrorPx = overbarBand.centerPx - glyphBand.centerPx,
                    thicknessErrorPx = overbarBand.thicknessPx - glyphBand.thicknessPx,
                    coordinateAlignmentTolerancePx = coordinateAlignmentTolerancePx,
                    strokeThicknessTolerancePx = strokeThicknessTolerancePx,
                )
            }
        }
    }
}

private fun positionedGlyphUnion(paths: List<Path>): Path {
    require(paths.isNotEmpty())
    var combined = paths.first()
    paths.drop(1).forEach { path ->
        val union = checkNotNull(Path.makeCombining(combined, path, PathOp.UNION)) {
            "Could not union radical glyph outlines"
        }
        combined.close()
        path.close()
        combined = union
    }
    return combined
}

private fun intersect(path: Path, rectangle: Rect): Path {
    val probe = Path.Rect(rectangle)
    return try {
        checkNotNull(Path.makeCombining(path, probe, PathOp.INTERSECT)) {
            "Could not intersect radical outline with seam probe"
        }
    } finally {
        probe.close()
    }
}
