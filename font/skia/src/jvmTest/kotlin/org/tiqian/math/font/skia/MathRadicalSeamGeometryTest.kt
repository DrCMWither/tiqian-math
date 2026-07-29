package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathConstructionPaintGroup
import org.tiqian.math.core.MathConstructionPaintKind
import org.tiqian.math.core.MathConstructionShapeKind
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathRulePlacement
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathGlyphBoundsSource
import org.tiqian.math.layout.MathOperatorGlyphRequest
import org.tiqian.math.layout.MathSymbolGlyphRequest
import org.tiqian.math.layout.MeasuredMathGlyph
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.ResolvedMathOperator
import org.tiqian.math.layout.ResolvedMathSymbol
import org.tiqian.math.layout.ResolvedMathSymbolRun

class MathRadicalSeamGeometryTest {
    @Test
    fun legacyFontReportedConstructionBoundsReproduceTheHistoricalSeamFailure() =
        withSeamFaces { label, face ->
            val legacy = LegacyFontReportedConstructionFace(face)
            seamFontSizes().forEach { fontSizePx ->
                radicalCases().forEach { case ->
                    val result = MathLayoutEngine(legacy).layout(
                        case.source,
                        MathLayoutOptions(MathMode.Display, fontSizePx),
                    )
                    val seam = face.radicalSeamGeometry(result.box, result.outerRadicalGroup())
                    assertTrue(
                        !seam.edgesAndThicknessMatch,
                        "$label/${case.label}/$fontSizePx legacy font-reported bounds must reproduce the seam bug: $seam",
                    )
                    assertTrue(
                        abs(seam.centerlineErrorPx) > seam.strokeThicknessTolerancePx * 8f,
                        "$label/${case.label}/$fontSizePx historical displacement is materially visible: $seam",
                    )
                    println("SEAM-BEFORE face=$label kind=${case.label} size=$fontSizePx $seam")
                }
            }
        }

    @Test
    fun realOutlineSeamEdgesCentersAndThicknessMatchForBothFontsAndAllConstructionKinds() =
        withSeamFaces { label, face ->
            seamFontSizes().forEach { fontSizePx ->
                radicalCases().forEach { case ->
                    val result = MathLayoutEngine(face).layout(
                        case.source,
                        MathLayoutOptions(MathMode.Display, fontSizePx),
                    )
                    val group = result.outerRadicalGroup()
                    assertEquals(case.kind, group.shapeKind, "$label/${case.label}/$fontSizePx")
                    val constructionDecision = result.decisions.first {
                        it.name == "OpenTypeRadicalConstruction" && it.range.start == 0
                    }
                    assertEquals(
                        MathGlyphBoundsSource.Outline.toString(),
                        constructionDecision.details["baseGlyphBoundsSource"],
                    )
                    assertEquals(
                        listOf(MathGlyphBoundsSource.Outline).toString(),
                        constructionDecision.details["componentBoundsSources"],
                    )
                    val seam = face.radicalSeamGeometry(result.box, group)
                    assertEquals("ActualGlyphOutlineSeamCrossSection", seam.policy)
                    assertTrue(
                        abs(seam.topEdgeErrorPx) <= seam.coordinateAlignmentTolerancePx,
                        "$label/${case.label}/$fontSizePx mathematical top-edge mismatch: $seam",
                    )
                    assertTrue(
                        seam.edgesAndThicknessMatch,
                        "$label/${case.label}/$fontSizePx real outline seam mismatch: $seam\n${result.debugDump}",
                    )
                    assertTrue(
                        seam.horizontalOverlapPx >= 0f,
                        "$label/${case.label}/$fontSizePx glyph top stroke reaches the overbar: $seam",
                    )
                    println("SEAM-GEOMETRY face=$label kind=${case.label} size=$fontSizePx $seam")
                }
            }
        }

    @Test
    fun independentOutlineOracleRejectsMovedAndResizedOverbars() =
        withSeamFaces { label, face ->
            radicalCases().forEach { case ->
                val result = MathLayoutEngine(face).layout(
                    case.source,
                    MathLayoutOptions(MathMode.Display, 32f),
                )
                val group = result.outerRadicalGroup()
                val rule = result.box.rules.single { it.constructionGroupId == group.id }
                val thickness = rule.bottom - rule.top
                val mutations = listOf(
                    "up" to rule.copy(top = rule.top - thickness, bottom = rule.bottom - thickness),
                    "down" to rule.copy(top = rule.top + thickness, bottom = rule.bottom + thickness),
                    "thick" to rule.copy(top = rule.top - thickness / 2f, bottom = rule.bottom + thickness / 2f),
                    "thin" to rule.copy(top = rule.top + thickness / 4f, bottom = rule.bottom - thickness / 4f),
                )
                mutations.forEach { (mutation, mutatedRule) ->
                    val seam = face.radicalSeamGeometry(result.box.withRule(group, mutatedRule), group)
                    assertTrue(
                        !seam.edgesAndThicknessMatch,
                        "$label/${case.label}/$mutation must be rejected by the raw-outline oracle: $seam",
                    )
                    when (mutation) {
                        "up", "down" -> assertTrue(
                            abs(seam.centerlineErrorPx) > seam.coordinateAlignmentTolerancePx,
                        )
                        "thick", "thin" -> assertTrue(
                            abs(seam.thicknessErrorPx) > seam.strokeThicknessTolerancePx,
                        )
                    }
                }
            }
        }

    @Test
    fun finalUnionRasterHasTheSameHorizontalStrokeBandOnBothSidesOfTheSeam() =
        withSeamFaces { label, face ->
            radicalCases().forEach { case ->
                val result = MathLayoutEngine(face).layout(
                    case.source,
                    MathLayoutOptions(MathMode.Display, 32f),
                )
                val group = result.outerRadicalGroup()
                listOf(0f, .25f, .5f, .75f).forEach { phase ->
                    val sample = sampleFinalUnion(face, result.box, group, phase, deviceScale = 8)
                    assertTrue(
                        abs(sample.left.top - sample.right.top) <= 1,
                        "$label/${case.label}/phase=$phase top raster edge: $sample",
                    )
                    assertTrue(
                        abs(sample.left.bottom - sample.right.bottom) <= 1,
                        "$label/${case.label}/phase=$phase bottom raster edge: $sample",
                    )
                    assertTrue(
                        abs(sample.left.thickness - sample.right.thickness) <= 1,
                        "$label/${case.label}/phase=$phase raster thickness: $sample",
                    )
                }
            }
        }
}

private data class RadicalCase(
    val label: String,
    val source: String,
    val kind: MathConstructionShapeKind,
)

private fun radicalCases(): List<RadicalCase> = listOf(
    RadicalCase("base", "\\sqrt[3]{x}", MathConstructionShapeKind.BaseGlyph),
    RadicalCase("variant", "\\sqrt{\\frac{a}{b}}", MathConstructionShapeKind.Variant),
    RadicalCase(
        "assembly",
        "\\sqrt{" + (1..12).fold("x") { radicand, _ -> "\\frac{$radicand}{y}" } + "}",
        MathConstructionShapeKind.Assembly,
    ),
)

private fun seamFontSizes(): List<Float> = listOf(24f, 32f, 48f)

private fun org.tiqian.math.core.MathLayoutResult.outerRadicalGroup(): MathConstructionPaintGroup =
    box.constructionPaintGroups.first {
        it.kind == MathConstructionPaintKind.Radical && it.sourceRange.start == 0
    }

private fun MathBox.withRule(
    group: MathConstructionPaintGroup,
    replacement: MathRulePlacement,
): MathBox = copy(
    rules = rules.map { rule ->
        if (rule.constructionGroupId == group.id) replacement else rule
    },
)

private data class RasterBand(val top: Int, val bottom: Int) {
    val thickness: Int get() = bottom - top + 1
}

private data class RasterSeamSample(val left: RasterBand, val right: RasterBand)

private fun sampleFinalUnion(
    face: SkiaMathFontFace,
    box: MathBox,
    group: MathConstructionPaintGroup,
    phase: Float,
    deviceScale: Int,
): RasterSeamSample {
    val seam = face.radicalSeamGeometry(box, group)
    val outline = assertIs<MathConstructionOutlineResult.Available>(face.constructionOutline(box, group)).path
    val bounds = outline.bounds
    val padding = 4
    val originX = floor(bounds.left).toInt() - padding
    val originY = floor(bounds.top).toInt() - padding
    val width = (ceil(bounds.right).toInt() - originX + padding) * deviceScale
    val height = (ceil(bounds.bottom).toInt() - originY + padding) * deviceScale
    val surface = Surface.makeRasterN32Premul(width, height)
    val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
    val paint = Paint().apply { color = Color.BLACK }
    try {
        surface.canvas.clear(Color.TRANSPARENT)
        surface.canvas.save().also {
            surface.canvas.scale(deviceScale.toFloat(), deviceScale.toFloat())
            surface.canvas.translate(-originX + phase, -originY + phase)
            surface.canvas.drawPath(outline, paint)
            surface.canvas.restoreToCount(it)
        }
        assertTrue(surface.readPixels(bitmap, 0, 0))
        val designUnitPx = box.glyphs.first { it.constructionGroupId == group.id }.fontSizePx /
            face.mathFont.unitsPerEm
        val leftWorldX = minOf(seam.glyphStrokeRightPx, seam.overbarLeftPx) - 4f * designUnitPx
        val rightWorldX = seam.overbarLeftPx + seam.overbar.thicknessPx
        val leftColumn = floor((leftWorldX - originX + phase) * deviceScale).toInt().coerceIn(0, width - 1)
        val rightColumn = floor((rightWorldX - originX + phase) * deviceScale).toInt().coerceIn(0, width - 1)
        val searchTop = floor(
            (minOf(seam.glyphStroke.topPx, seam.overbar.topPx) - originY + phase - 2f) * deviceScale,
        ).toInt()
            .coerceIn(0, height - 1)
        val searchBottom = ceil(
            (maxOf(seam.glyphStroke.bottomPx, seam.overbar.bottomPx) - originY + phase + 2f) * deviceScale,
        ).toInt()
            .coerceIn(0, height - 1)
        return RasterSeamSample(
            left = bitmap.alphaBand(leftColumn, searchTop, searchBottom),
            right = bitmap.alphaBand(rightColumn, searchTop, searchBottom),
        )
    } finally {
        paint.close()
        bitmap.close()
        surface.close()
    }
}

private fun Bitmap.alphaBand(x: Int, searchTop: Int, searchBottom: Int): RasterBand {
    val rows = (searchTop..searchBottom).filter { y -> getAlphaf(x, y) >= .1f }
    require(rows.isNotEmpty()) { "No final union coverage at x=$x in $searchTop..$searchBottom" }
    return RasterBand(rows.first(), rows.last())
}

private inline fun withSeamFaces(block: (String, SkiaMathFontFace) -> Unit) {
    listOf(
        "Lete Sans Math" to LeteSansMath.load(),
        "STIX Two Math" to StixTwoMath.load(),
    ).forEach { (label, font) -> SkiaMathFontFace(font).use { block(label, it) } }
}

private class LegacyFontReportedConstructionFace(
    private val delegate: SkiaMathFontFace,
) : MathFontFace {
    override val mathFont = delegate.mathFont

    override fun resolveSymbol(request: MathSymbolGlyphRequest, fontSizePx: Float): ResolvedMathSymbol =
        delegate.resolveSymbol(request, fontSizePx)

    override fun resolveOperator(request: MathOperatorGlyphRequest, fontSizePx: Float): ResolvedMathOperator =
        delegate.resolveOperator(request, fontSizePx)

    override fun resolveSymbols(
        requests: List<MathSymbolGlyphRequest>,
        fontSizePx: Float,
    ): ResolvedMathSymbolRun = delegate.resolveSymbols(requests, fontSizePx)

    override fun shape(
        text: String,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.shape(text, fontSizePx, style, sourceRange)

    override fun shapeConstructionBase(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.shape(text, fontSizePx, MathStyle.Text, sourceRange)

    override fun shapeOutlineConstructionBase(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): MeasuredMathRun = shapeConstructionBase(text, fontSizePx, sourceRange)

    override fun measureGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.font(fontSizePx).use { font ->
        val id = glyphId.toShort()
        val width = font.getWidths(shortArrayOf(id)).single()
        val bound = font.getBounds(shortArrayOf(id)).single()
        MeasuredMathRun(
            glyphs = listOf(
                MeasuredMathGlyph(
                    glyphId = glyphId,
                    x = 0f,
                    advance = width,
                    inkBounds = MathRect(bound.left, bound.top, bound.right, bound.bottom),
                ),
            ),
            width = width,
            ascent = (-bound.top).coerceAtLeast(0f),
            descent = bound.bottom.coerceAtLeast(0f),
            missingGlyph = glyphId.toInt() == 0,
            boundsSource = MathGlyphBoundsSource.FontReported,
        )
    }

    override fun measureOutlineConstructionGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = measureGlyph(glyphId, fontSizePx, style, sourceRange)
}
