package org.tiqian.math.font.skia

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Point
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextBlobBuilder
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathGlyphPlacement
import org.tiqian.math.core.MathLayoutDecision
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathRulePlacement
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.opentype.MathGlyphAssembly
import org.tiqian.math.font.opentype.MathGlyphAssemblyPart
import org.tiqian.math.font.opentype.MathGlyphConstruction
import org.tiqian.math.font.opentype.MathGlyphVariant
import org.tiqian.math.font.opentype.MathVerticalAssemblyPolicy
import org.tiqian.math.font.opentype.OpenTypeMathConstants
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathOperatorGlyphRequest
import org.tiqian.math.layout.MathSymbolGlyphRequest
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.MeasuredOutlineConstructionRun
import org.tiqian.math.layout.ResolvedMathOperator
import org.tiqian.math.layout.ResolvedMathSymbol
import org.tiqian.math.layout.ResolvedMathSymbolRun

internal fun radicalGeometry(
    delegate: SkiaMathFontFace,
    constants: OpenTypeMathConstants,
    mode: MathMode,
    source: String,
    size: Float,
): MathLayoutDecision = layout(
    delegate,
    delegate.mathFont.copy(constants = constants),
    source,
    size,
    mode,
).radicalGeometryDecision()

internal fun layout(
    delegate: SkiaMathFontFace,
    font: OpenTypeMathFont,
    source: String,
    size: Float,
    mode: MathMode = MathMode.Inline,
): MathLayoutResult = MathLayoutEngine(RadicalOverrideFace(delegate, font)).layout(
    source,
    MathLayoutOptions(mode, size),
)

internal class RadicalOverrideFace(
    private val delegate: SkiaMathFontFace,
    override val mathFont: OpenTypeMathFont,
    private val advanceWidthOverrides: Map<UShort, Float> = emptyMap(),
) : MathFontFace {
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

    override fun measureGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.measureGlyph(glyphId, fontSizePx, style, sourceRange).let { run ->
        advanceWidthOverrides[glyphId]?.let { run.copy(width = it) } ?: run
    }

    override fun measureGlyphOutlineBounds(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.measureGlyphOutlineBounds(glyphId, fontSizePx, style, sourceRange).let { run ->
        advanceWidthOverrides[glyphId]?.let { run.copy(width = it) } ?: run
    }

    override fun shapeOutlineConstructionBase(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = delegate.shapeOutlineConstructionBase(text, fontSizePx, sourceRange)

    override fun measureOutlineConstructionGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = delegate.measureOutlineConstructionGlyph(
        glyphId,
        fontSizePx,
        style,
        sourceRange,
    ).let { measurement ->
        advanceWidthOverrides[glyphId]?.let { width ->
            measurement.copy(run = measurement.run.copy(width = width))
        } ?: measurement
    }
}

internal fun MathLayoutResult.radicalNoadDecision(): MathLayoutDecision =
    decisions.first { it.name == "TeXRadicalNoad" }

internal fun MathLayoutResult.radicalConstructionDecision(): MathLayoutDecision =
    decisions.first { it.name == "OpenTypeRadicalConstruction" }

private fun MathLayoutResult.radicalGeometryDecision(): MathLayoutDecision =
    decisions.first { it.name == "OpenTypeMathRadical" }

internal fun MathLayoutResult.radicalGeometryDecisions(): List<MathLayoutDecision> =
    decisions.filter { it.name == "OpenTypeMathRadical" }

private fun MathLayoutDecision.float(name: String): Float =
    checkNotNull(details[name]) { "$name is absent from $this" }.toFloat()

/** TeX/OpenType radical box equations; the separate outline oracle audits the painted seam. */
internal fun assertRadicalBoxAlgebra(geometry: MathLayoutDecision, label: String) {
    val ruleThickness = geometry.float("radicalRuleThicknessPx")
    val minimumGap = geometry.float("radicalVerticalGapPx")
    val cleanHeight = geometry.float("radicandAscentPx") + geometry.float("radicandDescentPx")
    assertNear(
        cleanHeight + minimumGap + ruleThickness,
        geometry.float("targetHeightPx"),
        "$label stretch target uses TeX clean_box height plus depth",
    )
    val expectedExcess = (
        geometry.float("achievedAdvancePx") - geometry.float("targetHeightPx")
    ).coerceAtLeast(0f)
    assertNear(
        expectedExcess,
        geometry.float("constructionExcessPx"),
        "$label construction excess uses the selected stretch extent",
    )
    assertNear(
        minimumGap + expectedExcess / 2f,
        geometry.float("actualRadicalGapPx"),
        "$label clearance receives half the positive construction excess",
    )
    assertNear(
        -geometry.float("radicandAscentPx") - geometry.float("actualRadicalGapPx"),
        geometry.float("ruleBottom"),
        "$label overbar bottom closes against clean_box height",
    )
    assertNear(
        geometry.float("ruleTop") + ruleThickness,
        geometry.float("ruleBottom"),
        "$label overbar thickness",
    )
    assertNear(
        maxOf(
            geometry.float("radicandAscentPx") + geometry.float("actualRadicalGapPx") +
                2f * ruleThickness,
            geometry.float("texDelimiterContributedAscentPx"),
        ),
        geometry.float("unindexedAscentPx"),
        "$label unindexed ascent follows XeTeX overbar and nominal delimiter boxes",
    )
    assertNear(
        maxOf(
            geometry.float("radicandDescentPx"),
            geometry.float("texDelimiterContributedDescentPx"),
        ).coerceAtLeast(0f),
        geometry.float("unindexedDescentPx"),
        "$label unindexed descent follows XeTeX clean and nominal delimiter boxes",
    )
    assertNear(
        geometry.float("ruleTop"),
        geometry.float("radicalPaintOriginY") + geometry.float("radicalTopStrokeTopPx"),
        "$label font-adapter top-stroke anchor meets the overbar",
    )
    assertNear(
        geometry.float("radicalX") + geometry.float("radicalTopStrokeRightPx"),
        geometry.float("ruleLeft"),
        "$label overbar starts at the font-adapter top-stroke right edge",
    )
    assertNear(
        geometry.float("radicalX") + geometry.float("radicalBoxAdvancePx") +
            geometry.float("radicandWidthPx"),
        geometry.float("ruleRight"),
        "$label overbar reaches the radicand logical right edge without changing radical advance",
    )
    assertEquals("Available(GlyphOutlineCrossSection)", geometry.details["radicalTopStrokeEvidence"])
    assertEquals("XeTeXMakeRadicalCleanBoxNominalDelimiterAndOverbar", geometry.details["unindexedBoxPolicy"])
    assertEquals("false", geometry.details["radicalExtraAscenderUsed"])
    assertEquals(
        "SelectedOpenTypeConstructionAdvanceMinusStretchTarget",
        geometry.details["constructionExcessMetric"],
    )
    assertEquals(
        "MinimumGapPlusHalfPositiveConstructionExcess",
        geometry.details["clearancePolicy"],
    )
    assertEquals("FontAdapterTopStrokeTopAndRight", geometry.details["overbarAnchorPolicy"])
    assertEquals("OpenTypeMATH.RadicalRuleThickness", geometry.details["overbarThicknessSource"])
    assertEquals("FontAdapterTopStrokeRight", geometry.details["overbarLeftPolicy"])
}

internal fun assertAssemblyTopAlignedToOverbar(geometry: MathLayoutDecision, label: String) {
    assertNear(
        geometry.float("ruleTop"),
        geometry.float("radicalPaintOriginY") + geometry.float("radicalTopStrokeTopPx"),
        "$label assembly top-stroke anchor meets overbar",
    )
    assertNear(
        geometry.float("ruleTop") - geometry.float("radicalTopStrokeTopPx"),
        geometry.float("radicalPaintOriginY"),
        "$label assembly local top stroke drives the paint origin",
    )
}

internal inline fun withRadicalFaces(block: (String, SkiaMathFontFace) -> Unit) {
    listOf(
        "Lete Sans Math" to LeteSansMath.load(),
        "STIX Two Math" to StixTwoMath.load(),
    ).forEach { (label, font) -> SkiaMathFontFace(font).use { block(label, it) } }
}

internal fun rasterConnectedComponentSizes(
    face: SkiaMathFontFace,
    glyphs: List<MathGlyphPlacement>,
    rule: MathRulePlacement,
    scale: Int,
): List<Int> {
    require(glyphs.isNotEmpty())
    val scalePx = scale.toFloat()
    val left = minOf(glyphs.minOf { it.inkBounds.left }, rule.left)
    val top = minOf(glyphs.minOf { it.inkBounds.top }, rule.top)
    val right = maxOf(glyphs.maxOf { it.inkBounds.right }, rule.right)
    val bottom = maxOf(glyphs.maxOf { it.inkBounds.bottom }, rule.bottom)
    val padding = 8
    val width = ceil((right - left) * scalePx).toInt() + 2 * padding
    val height = ceil((bottom - top) * scalePx).toInt() + 2 * padding
    val originX = padding - left * scalePx
    val originY = padding - top * scalePx
    val surface = Surface.makeRasterN32Premul(width, height)
    val paint = Paint().apply { color = Color.BLACK }
    val font = face.font(glyphs.first().fontSizePx * scalePx)
    val builder = TextBlobBuilder()
    try {
        surface.canvas.clear(Color.TRANSPARENT)
        builder.appendRunPos(
            font,
            glyphs.map { it.glyphId.toShort() }.toShortArray(),
            glyphs.map {
                Point(originX + it.x * scalePx, originY + it.baselineY * scalePx)
            }.toTypedArray(),
        )
        builder.build()?.use { surface.canvas.drawTextBlob(it, 0f, 0f, paint) }
        surface.canvas.drawRect(
            Rect.makeLTRB(
                originX + rule.left * scalePx,
                originY + rule.top * scalePx,
                originX + rule.right * scalePx,
                originY + rule.bottom * scalePx,
            ),
            paint,
        )
        val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
        try {
            assertTrue(surface.readPixels(bitmap, 0, 0))
            val filled = BooleanArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                filled[y * width + x] = bitmap.getAlphaf(x, y) > 0.08f
            }
            val visited = BooleanArray(filled.size)
            val sizes = mutableListOf<Int>()
            for (start in filled.indices) {
                if (!filled[start] || visited[start]) continue
                visited[start] = true
                val queue = ArrayDeque<Int>()
                queue.add(start)
                var componentSize = 0
                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()
                    componentSize++
                    val currentX = current % width
                    val currentY = current / width
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nextX = currentX + dx
                        val nextY = currentY + dy
                        if (nextX !in 0 until width || nextY !in 0 until height) continue
                        val next = nextY * width + nextX
                        if (filled[next] && !visited[next]) {
                            visited[next] = true
                            queue.add(next)
                        }
                    }
                }
                sizes += componentSize
            }
            assertTrue(sizes.isNotEmpty(), "STIX radical raster must contain visible pixels")
            return sizes.sortedDescending()
        } finally {
            bitmap.close()
        }
    } finally {
        builder.close()
        font.close()
        paint.close()
        surface.close()
    }
}

private fun assertNear(expected: Float, actual: Float, message: String) {
    assertTrue(abs(expected - actual) <= EPSILON, "$message: expected=$expected actual=$actual")
}

private const val EPSILON = 0.05f
