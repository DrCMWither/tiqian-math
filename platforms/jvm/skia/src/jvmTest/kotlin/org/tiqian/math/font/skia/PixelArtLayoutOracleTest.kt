package org.tiqian.math.font.skia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathPaintColor
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/**
 * Geometry oracles for the Zhihu pixel-art trio: `\rule` boxes with px units carry the active
 * `\color` hex paint, `\rlap` keeps zero logical width with right-lapping ink, and the real
 * column shape (nested rlap chains) lays out to columns-times-strip-width with no diagnostics.
 */
class PixelArtLayoutOracleTest {
    private fun layout(source: String) = SkiaMathFontFamily.loadBundledLete().use { math ->
        MathLayoutEngine(math).layout(
            source,
            MathLayoutOptions(mode = MathMode.Display, fontSizePx = 48f, displayWidthPx = 1248f),
        )
    }

    @Test
    fun coloredRuleBoxCarriesHexPaintAndPxGeometry() {
        val result = layout("\\color{#BB9}{\\rule{4px}{320px}}")
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val rule = result.box.rules.single()
        assertEquals(4f, rule.right - rule.left, 0.01f)
        assertEquals(320f, rule.bottom - rule.top, 0.01f)
        assertEquals(0f, rule.bottom, 0.01f)
        assertEquals(MathPaintColor(0xBB, 0xBB, 0x99), rule.paintColor)
        assertEquals(4f, result.box.width, 0.01f)
    }

    @Test
    fun raisedRuleShiftsAboveTheBaseline() {
        val result = layout("\\rule[8px]{4px}{16px}")
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val rule = result.box.rules.single()
        assertEquals(-8f, rule.bottom, 0.01f)
        assertEquals(-24f, rule.top, 0.01f)
    }

    @Test
    fun rlapChainOverlapsStripsAtZeroWidth() {
        val column = "\\rlap{\\color{#BB9}{\\rule{4px}{32px}}}{\\color{#696}{\\rule{4px}{16px}}}"
        val result = layout(column + column)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        // Two columns of two strips: each rlap contributes no logical width, so the row
        // advances only by the visible strips.
        assertEquals(8f, result.box.width, 0.01f)
        assertEquals(4, result.box.rules.size)
        val colors = result.box.rules.mapNotNull { it.paintColor }.toSet()
        assertEquals(
            setOf(MathPaintColor(0xBB, 0xBB, 0x99), MathPaintColor(0x66, 0x99, 0x66)),
            colors,
        )
    }

    @Test
    fun llapInkExtendsLeftOfThePen() {
        val result = layout("\\llap{\\rule{6px}{6px}}")
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(0f, result.box.width, 0.01f)
        assertTrue(result.box.inkBounds.left <= -5.9f, "ink=${result.box.inkBounds}")
    }
}
