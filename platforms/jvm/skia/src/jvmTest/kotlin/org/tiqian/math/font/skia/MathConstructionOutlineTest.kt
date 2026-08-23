package org.tiqian.math.font.skia

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathConstructionOutlinePolicy
import org.tiqian.math.core.MathConstructionPaintGroup
import org.tiqian.math.core.MathConstructionPaintKind
import org.tiqian.math.core.MathConstructionShapeKind
import org.tiqian.math.core.MathGlyphPlacement
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathRulePlacement
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

class MathConstructionOutlineTest {
    @Test
    fun baseVariantAndAssemblyUnionRasterStayConnectedWithoutAlphaOverdrawAcrossScalesAndPhases() =
        withConstructionFaces { label, face ->
            val nominalSizesSp = listOf(18f, 40f)
            val densities = listOf(1f, 1.25f, 1.5f, 2f, 3f)
            val phases = listOf(0f, 0.25f, 0.5f, 0.75f)
            nominalSizesSp.forEach { nominalSize ->
                densities.forEach { density ->
                    val sizePx = nominalSize * density
                    val cases = listOf(
                        RadicalRasterCase(
                            "indexed-base",
                            "\\sqrt[3]{x}",
                            MathMode.Inline,
                            MathConstructionShapeKind.BaseGlyph,
                        ),
                        RadicalRasterCase(
                            "nested-fraction",
                            "\\sqrt{\\frac{a+b}{\\sqrt{x}}}",
                            MathMode.Display,
                            expectedShape = null,
                        ),
                        RadicalRasterCase(
                            "fraction-variant",
                            findRadicalSource(face, label, sizePx, MathConstructionShapeKind.Variant),
                            MathMode.Display,
                            MathConstructionShapeKind.Variant,
                        ),
                        RadicalRasterCase(
                            "deep-fraction-assembly",
                            findRadicalSource(face, label, sizePx, MathConstructionShapeKind.Assembly),
                            MathMode.Display,
                            MathConstructionShapeKind.Assembly,
                        ),
                    )
                    cases.forEach { case ->
                        val result = MathLayoutEngine(face).layout(
                            case.source,
                            MathLayoutOptions(case.mode, sizePx),
                        )
                        val group = result.outerRadicalGroup()
                        case.expectedShape?.let { expected ->
                            assertEquals(
                                expected,
                                group.shapeKind,
                                "$label/${case.label}/size=$nominalSize/density=$density",
                            )
                        }
                        phases.forEach { phase ->
                            assertSingleUnionRaster(
                                face,
                                result.box,
                                group,
                                phase,
                                "$label/${case.label}/size=$nominalSize/density=$density/phase=$phase",
                            )
                        }
                        }
                    }
                }
        }

    @Test
    fun nestedRadicalPaintOwnershipAndOutlineCacheAreReplayableForBothFonts() =
        withConstructionFaces { label, face ->
            val result = MathLayoutEngine(face).layout(
                "\\sqrt[3]{\\frac{a+b}{\\sqrt{x}}}",
                MathLayoutOptions(MathMode.Display, 52f),
            )
            assertEquals(2, result.box.constructionPaintGroups.size, label)
            assertEquals(
                result.box.constructionPaintGroups,
                result.fragments.single().box.constructionPaintGroups,
                "$label fragment replay retains semantic paint ownership",
            )
            result.box.constructionPaintGroups.forEach { group ->
                assertEquals(MathConstructionPaintKind.Radical, group.kind)
                assertEquals(MathConstructionOutlinePolicy.RequireOutlineUnion, group.outlinePolicy)
                val glyphs = result.box.glyphs.filter { it.constructionGroupId == group.id }
                val rules = result.box.rules.filter { it.constructionGroupId == group.id }
                assertTrue(glyphs.isNotEmpty(), "$label/group ${group.id} owns glyph outlines")
                assertEquals(1, rules.size, "$label/group ${group.id} owns exactly its overbar")

                val first = assertIs<MathConstructionOutlineResult.Available>(
                    face.constructionOutline(result.box, group),
                )
                assertTrue(!first.cacheHit, "$label/group ${group.id} first build")
                val bounds = first.path.bounds
                val expectedLeft = minOf(glyphs.minOf { it.inkBounds.left }, rules.single().left)
                val expectedTop = minOf(glyphs.minOf { it.inkBounds.top }, rules.single().top)
                val expectedRight = maxOf(glyphs.maxOf { it.inkBounds.right }, rules.single().right)
                val expectedBottom = maxOf(glyphs.maxOf { it.inkBounds.bottom }, rules.single().bottom)
                assertTrue(bounds.left + 0.04f >= expectedLeft, "$label path stays in measured left bound")
                assertTrue(bounds.top + 0.04f >= expectedTop, "$label path stays in measured top bound")
                assertTrue(bounds.right <= expectedRight + 0.04f, "$label path stays in measured right bound")
                assertTrue(bounds.bottom <= expectedBottom + 0.04f, "$label path stays in measured bottom bound")
                assertTrue(bounds.left <= rules.single().left + 0.04f, "$label union contains overbar left")
                assertTrue(bounds.top <= rules.single().top + 0.04f, "$label union contains overbar top")
                assertTrue(bounds.right + 0.04f >= rules.single().right, "$label union contains overbar right")
                assertTrue(bounds.bottom + 0.04f >= rules.single().bottom, "$label union contains overbar bottom")

                val second = assertIs<MathConstructionOutlineResult.Available>(
                    face.constructionOutline(result.box, group),
                )
                assertTrue(second.cacheHit, "$label/group ${group.id} second replay hits cache")
                assertTrue(first.path === second.path, "$label cache returns one face-owned path")
            }
            val stats = face.constructionOutlineCacheStats()
            assertEquals(2, stats.entries, label)
            assertEquals(2, stats.builds, label)
            assertEquals(2, stats.hits, label)
        }

    @Test
    fun unavailableGlyphOutlineIsAnExplicitCapabilityResult() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val range = SourceRange(0, 5)
            val group = MathConstructionPaintGroup(
                id = 7,
                kind = MathConstructionPaintKind.Radical,
                shapeKind = MathConstructionShapeKind.Assembly,
                sourceRange = range,
                outlinePolicy = MathConstructionOutlinePolicy.RequireOutlineUnion,
            )
            val box = MathBox(
                width = 20f,
                ascent = 20f,
                descent = 5f,
                inkBounds = MathRect(0f, -20f, 20f, 5f),
                glyphs = listOf(
                    MathGlyphPlacement(
                        glyphId = UShort.MAX_VALUE,
                        x = 0f,
                        baselineY = 0f,
                        advance = 10f,
                        inkBounds = MathRect(0f, -20f, 10f, 5f),
                        fontSizePx = 40f,
                        sourceRange = range,
                        style = MathStyle.Text,
                        constructionGroupId = group.id,
                    ),
                ),
                rules = listOf(MathRulePlacement(10f, -20f, 20f, -18f, range, group.id)),
                range = range,
                constructionPaintGroups = listOf(group),
            )
            val unavailable = assertIs<MathConstructionOutlineResult.Unavailable>(
                face.constructionOutline(box, group),
            )
            assertEquals(MathConstructionOutlineUnavailableReason.GlyphOutlineUnavailable, unavailable.reason)
            assertEquals(UShort.MAX_VALUE, unavailable.glyphId)
            assertEquals(0, face.constructionOutlineCacheStats().entries)
        }
    }
}

private data class RadicalRasterCase(
    val label: String,
    val source: String,
    val mode: MathMode,
    val expectedShape: MathConstructionShapeKind?,
)

private fun findRadicalSource(
    face: SkiaMathFontFace,
    label: String,
    sizePx: Float,
    expected: MathConstructionShapeKind,
): String {
    val candidates = mutableListOf(
        "x",
        "x^2",
        "x^2+1",
        "\\frac{a}{b}",
        "\\frac{a+b}{c+d}",
    )
    var deep = "\\frac{a}{b}"
    repeat(12) {
        deep = "\\frac{$deep}{y}"
        candidates += deep
    }
    candidates.forEach { radicand ->
        val source = "\\sqrt{$radicand}"
        val result = MathLayoutEngine(face).layout(source, MathLayoutOptions(MathMode.Display, sizePx))
        if (result.outerRadicalGroup().shapeKind == expected) return source
    }
    error("$label did not reach $expected at ${sizePx}px")
}

private fun org.tiqian.math.core.MathLayoutResult.outerRadicalGroup(): MathConstructionPaintGroup =
    box.constructionPaintGroups.single {
        it.kind == MathConstructionPaintKind.Radical && it.sourceRange == SourceRange(0, 5)
    }

private fun assertSingleUnionRaster(
    face: SkiaMathFontFace,
    box: MathBox,
    group: MathConstructionPaintGroup,
    phase: Float,
    label: String,
) {
    val outline = assertIs<MathConstructionOutlineResult.Available>(face.constructionOutline(box, group))
    val bounds = outline.path.bounds
    val padding = 5
    val width = ceil(bounds.width).toInt() + 2 * padding + 2
    val height = ceil(bounds.height).toInt() + 2 * padding + 2
    val surface = Surface.makeRasterN32Premul(width, height)
    val paint = Paint().apply { color = Color.makeARGB(128, 0, 0, 0) }
    val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
    try {
        surface.canvas.clear(Color.TRANSPARENT)
        val save = surface.canvas.save()
        surface.canvas.translate(padding - bounds.left + phase, padding - bounds.top + phase)
        surface.canvas.drawPath(outline.path, paint)
        surface.canvas.restoreToCount(save)
        assertTrue(surface.readPixels(bitmap, 0, 0), label)

        val filled = BooleanArray(width * height)
        var visible = 0
        var maximumAlpha = 0f
        for (y in 0 until height) for (x in 0 until width) {
            val alpha = bitmap.getAlphaf(x, y)
            maximumAlpha = maxOf(maximumAlpha, alpha)
            if (alpha > 0.02f) {
                filled[y * width + x] = true
                visible += 1
            }
        }
        assertTrue(visible > 0, "$label has visible construction ink")
        assertTrue(maximumAlpha <= 0.53f, "$label is painted once; max alpha=$maximumAlpha")
        assertEquals(1, connectedComponentCount(filled, width, height), "$label has no radical seam")
    } finally {
        bitmap.close()
        paint.close()
        surface.close()
    }
}

private fun connectedComponentCount(filled: BooleanArray, width: Int, height: Int): Int {
    val visited = BooleanArray(filled.size)
    var components = 0
    for (start in filled.indices) {
        if (!filled[start] || visited[start]) continue
        components += 1
        visited[start] = true
        val queue = ArrayDeque<Int>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val x = current % width
            val y = current / width
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nextX = x + dx
                val nextY = y + dy
                if (nextX !in 0 until width || nextY !in 0 until height) continue
                val next = nextY * width + nextX
                if (filled[next] && !visited[next]) {
                    visited[next] = true
                    queue.add(next)
                }
            }
        }
    }
    return components
}

private inline fun withConstructionFaces(block: (String, SkiaMathFontFace) -> Unit) {
    listOf(
        "Lete Sans Math" to LeteSansMath.load(),
        "STIX Two Math" to StixTwoMath.load(),
    ).forEach { (label, font) -> SkiaMathFontFace(font).use { block(label, it) } }
}

private fun assertNear(expected: Float, actual: Float, label: String) {
    assertTrue(kotlin.math.abs(expected - actual) <= 0.04f, "$label: expected=$expected actual=$actual")
}
