package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/**
 * Zhihu fancy-usage support: LaTeX size declarations, the shared color grammar, MathJax aliases,
 * and the TeX wordmarks. Sources come from the reference answers under「回答字体怎样变大?」.
 */
class FancyUsageOracleTest {
    private fun layout(source: String, block: (MathLayoutResult) -> Unit) {
        SkiaMathFontFamily.loadBundledLete().use { math ->
            TestHostTextProvider(
                SkiaMathFontFace(
                    org.tiqian.math.font.opentype.LeteSansMath.load(),
                    MathFaceId("fancy-oracle-host"),
                ),
            ).use { provider ->
                block(
                    MathLayoutEngine(math, textRunProvider = provider).layout(
                        source,
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = 40f,
                            displayWidthPx = 1248f,
                            textLocale = "zh-Hans",
                            softWrapDisplay = true,
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun sizeDeclarationScalesGlyphsWithLatexRatios() {
        layout("\\Huge{A}\\small{B}C") {
            assertTrue(it.diagnostics.isEmpty(), it.diagnostics.toString())
            val sizes = it.box.glyphs.map { glyph -> glyph.fontSizePx }
            assertEquals(3, sizes.size)
            assertTrue(abs(sizes[0] - 40f * 2.488f) < 0.1f, "Huge glyph: ${sizes[0]}")
            assertTrue(abs(sizes[1] - 40f * 0.9f) < 0.1f, "small glyph: ${sizes[1]}")
            // TeX declaration scope: \small runs to the end of the list, so C stays small.
            assertTrue(abs(sizes[2] - 40f * 0.9f) < 0.1f, "declaration applies to the list tail: ${sizes[2]}")
            assertTrue(
                it.decisions.count { d -> d.name == "LatexSizeDeclaration" } == 2,
                "both declarations recorded",
            )
        }
    }

    @Test
    fun pairGlueScalesWithTheSizeDeclaration() {
        // MathJax reference measurement: {\huge a+b} spacing is exactly \huge times the
        // unscaled spacing (5.42px -> 11.23px at 2.074); TeX's mu follows the current font quad.
        fun operatorGaps(source: String): List<Float> = mutableListOf<Float>().also { gaps ->
            layout(source) {
                val glyphs = it.box.glyphs.sortedBy { g -> g.x }
                glyphs.zipWithNext { a, b -> gaps += b.x - (a.x + a.advance) }
            }
        }
        val base = operatorGaps("a+b")
        val huge = operatorGaps("{\\huge a+b}")
        base.zip(huge).forEach { (b, h) ->
            assertTrue(
                abs(h - b * 2.074f) < 0.6f,
                "pair glue must scale with the atoms: base=$b huge=$h",
            )
        }
    }

    @Test
    fun braceGroupEndsASizeDeclarationScope() {
        layout("{\\Huge A}B") {
            assertTrue(it.diagnostics.isEmpty(), it.diagnostics.toString())
            val sizes = it.box.glyphs.sortedBy { glyph -> glyph.x }.map { glyph -> glyph.fontSizePx }
            assertTrue(abs(sizes[0] - 40f * 2.488f) < 0.1f, "Huge inside the group: ${sizes[0]}")
            assertTrue(abs(sizes[1] - 40f) < 0.1f, "the group boundary must end the scope: ${sizes[1]}")
        }
    }

    @Test
    fun sizeDeclarationScalesHostCjkText() {
        layout("{\\huge 国}国") {
            assertTrue(it.diagnostics.isEmpty(), it.diagnostics.toString())
            val sizes = it.box.glyphs.sortedBy { glyph -> glyph.x }.map { glyph -> glyph.fontSizePx }
            assertEquals(2, sizes.size)
            assertTrue(
                abs(sizes[0] - 40f * 2.074f) < 0.1f && abs(sizes[1] - 40f) < 0.1f,
                "huge CJK glyph scales inside its group only: $sizes",
            )
        }
    }

    @Test
    fun colorGrammarAcceptsCssSvgKeywords() {
        layout("\\color{deepskyblue}{x}") {
            assertTrue(it.diagnostics.isEmpty(), it.diagnostics.toString())
            val paint = it.box.glyphs.single().paintColor
            assertEquals(0x00BFFF, paint!!.argb and 0xFFFFFF)
        }
    }

    @Test
    fun boldAliasMatchesMathbf() {
        layout("\\bold{x}") { bold ->
            assertTrue(bold.diagnostics.isEmpty(), bold.diagnostics.toString())
            layout("\\mathbf{x}") { mathbf ->
                assertEquals(
                    mathbf.box.glyphs.single().glyphId,
                    bold.box.glyphs.single().glyphId,
                    "\\bold must select the same bold glyph as \\mathbf",
                )
            }
        }
    }

    @Test
    fun backslashIsAnOrdinarySymbol() {
        layout("a\\backslash b") {
            assertTrue(it.diagnostics.isEmpty(), it.diagnostics.toString())
            assertEquals(3, it.box.glyphs.size)
        }
    }

    @Test
    fun latexLogoRaisesItsAAndLowersItsE() {
        layout("\\LaTeX") {
            assertTrue(it.diagnostics.isEmpty(), it.diagnostics.toString())
            val glyphs = it.box.glyphs.sortedBy { glyph -> glyph.x }
            assertEquals(5, glyphs.size)
            val a = glyphs[1]
            val e = glyphs[3]
            assertTrue(a.fontSizePx < glyphs[0].fontSizePx * 0.75f, "A shrinks to script size")
            assertTrue(a.baselineY < -0.1f, "A raises above the baseline: ${a.baselineY}")
            assertTrue(e.baselineY > 0.1f, "E lowers below the baseline: ${e.baselineY}")
            assertEquals(1, it.decisions.count { d -> d.name == "TexLogoComposition" })
        }
    }

    @Test
    fun boxedHostsAlignEnvironments() {
        layout(
            "\\boxed{\\begin{align} &\\large{\\color{orange}{爱和恨}}\\huge{\\color{green}{天各一方}},\\\\" +
                "  &\\Huge{\\color{purple}{梦}}却\\huge{\\color{deepskyblue}{安然无恙}}\\\\ \\end{align} }",
        ) {
            assertTrue(it.diagnostics.isEmpty(), it.diagnostics.toString())
        }
    }
}
