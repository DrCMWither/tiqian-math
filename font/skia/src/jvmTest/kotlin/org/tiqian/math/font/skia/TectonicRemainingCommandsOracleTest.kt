package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/** Reproducer: `preview/tectonic/remaining-command-oracle.tex`, Tectonic 0.17.0 at 24bp. */
class TectonicRemainingCommandsOracleTest {
    @Test
    fun atopNotAndHlineMatchSameFontXeTeXBoxesAndGlyphs() = withFaces { oracle, engine ->
        val hline = engine.layout("\\begin{array}{c}a\\\\\\hline b\\end{array}", options())
        assertBox(oracle.hline, hline, "${oracle.label} hline")
        val rule = hline.box.rules.single { it.sourceRange == SourceRange(19, 25) }
        assertNear(DEFAULT_RULE_PX, rule.bottom - rule.top, "${oracle.label} hline thickness")
        assertNear(0f, rule.left, "${oracle.label} hline left")
        assertNear(hline.box.width, rule.right, "${oracle.label} hline right")

        val atop = engine.layout("{a\\atop b}", options())
        assertNear(oracle.atopWidthPx, atop.box.width, "${oracle.label} atop width")
        if (oracle.atopGlyphIds == null) {
            assertNear(oracle.atop.ascentPt * TEX_PT_TO_PX, atop.box.ascent, "${oracle.label} atop ascent")
            assertNear(oracle.atop.descentPt * TEX_PT_TO_PX, atop.box.descent, "${oracle.label} atop descent")
        } else {
            // XeTeX does not apply STIX's optional ssty substitutions here. Tiqian's established
            // OpenType style contract does, so pin that explicit backend divergence separately
            // while the generalized-fraction kernel remains shared with the reviewed fraction tests.
            assertEquals(oracle.atopGlyphIds, atop.box.glyphs.map { it.glyphId })
            assertTrue(atop.box.glyphs.all { abs(it.fontSizePx - 22.4f) <= 0.001f })
        }
        assertEquals("GeneralizedAtop", atop.decisions.single {
            it.name == "TeXFractionCommand"
        }.details["origin"])

        val not = engine.layout("\\mu\\not\\equiv\\mu", options())
        assertNear(oracle.not.widthPt * TEX_PT_TO_PX, not.box.width, "${oracle.label} not width")
        val negated = not.box.glyphs.single { it.sourceRange == SourceRange(3, 13) }
        assertEquals(oracle.notGlyphId, negated.glyphId, oracle.label)
        assertEquals("U+2262", not.decisions.single { it.name == "TeXNotRelation" }.details["precomposedScalar"])

        assertTrue(listOf(atop, not, hline).all { it.diagnostics.isEmpty() }, oracle.label)
    }

    @Test
    fun cancelUsesThePackageSlopeTableAndKeepsTheArgumentAdvance() = withFaces { oracle, engine ->
        val bare = engine.layout("x+1", options())
        val cancel = engine.layout("\\cancel{x+1}", options())
        assertNear(bare.box.width, cancel.box.width, "${oracle.label} cancel logical advance")
        val rule = cancel.box.rules.single { it.paintRole == MathRulePaintRole.Cancellation }
        val line = assertNotNull(rule.lineSegment)
        assertNear(DEFAULT_RULE_PX, line.thickness, "${oracle.label} cancel thinlines")
        assertEquals("Wide", cancel.decisions.single { it.name == "LatexCancelStroke" }.details["shapeClass"])
        assertTrue(
            line.startX < 0f && line.endX > bare.box.width,
            "${oracle.label} line=${line.startX},${line.startY} -> ${line.endX},${line.endY} width=${bare.box.width}",
        )
        assertTrue(line.startY > line.endY, "${oracle.label} line=$line")
        assertTrue(cancel.box.ascent >= -rule.top && cancel.box.descent >= rule.bottom, oracle.label)
        assertTrue(cancel.diagnostics.isEmpty(), "${oracle.label}: ${cancel.diagnostics}")
    }

    @Test
    fun textbfRequestsHostBoldWhileLegacyBfUsesTheMathVersionAxis() {
        SkiaMathTextRunProvider.fromBytes(
            MathFaceId("remaining-host-bold"),
            LeteSansMath.loadBoldBytes(),
            MathFontWeight.Bold,
        ).use { text ->
            listOf(
                "Lete" to { SkiaMathFontFace(LeteSansMath.load()) },
                "STIX" to { SkiaMathFontFace(StixTwoMath.load()) },
            ).forEach { (label, factory) ->
                factory().use { face ->
                    val result = MathLayoutEngine(face, textRunProvider = text).layout("\\textbf{1}", options())
                    assertTrue(result.diagnostics.isEmpty(), "$label ${result.diagnostics}")
                    assertTrue(result.box.glyphs.all { it.requestedWeight == MathFontWeight.Bold }, label)
                    assertTrue(result.box.glyphs.all { it.resolvedWeight == MathFontWeight.Bold }, label)
                    assertTrue(result.box.glyphs.all { it.faceId == MathFaceId("remaining-host-bold") }, label)
                }
            }
        }

        SkiaMathFontFamily.loadBundledLete().use { family ->
            val result = MathLayoutEngine(family).layout("\\bf{0}", options())
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            val normal = MathLayoutEngine(family).layout("0", options())
            assertNotNull(result.box.glyphs.singleOrNull(), result.debugDump)
            assertTrue(
                result.box.glyphs.single().glyphId != normal.box.glyphs.single().glyphId,
                result.debugDump,
            )
            assertTrue(
                result.decisions.single { it.name == "TeXMathSymbolResolution" }
                    .details["resolvedAlphabet"] == "Bold",
                result.debugDump,
            )
            assertTrue(result.decisions.any { it.name == "TeXMathVersionDeclaration" })

            val grouped = MathLayoutEngine(family).layout("{\\bf 0}+1", options())
            val normalDigits = MathLayoutEngine(family).layout("01", options()).box.glyphs
            assertTrue(grouped.diagnostics.isEmpty(), grouped.debugDump)
            assertTrue(grouped.box.glyphs.first().glyphId != normalDigits.first().glyphId, grouped.debugDump)
            assertEquals(normalDigits.last().glyphId, grouped.box.glyphs.last().glyphId, grouped.debugDump)
        }
    }

    private fun options() = MathLayoutOptions(
        mode = MathMode.Inline,
        initialStyle = MathStyle.Text,
        fontSizePx = 32f,
        nullDelimiterSpacePx = 1.2f * TEX_PT_TO_PX,
        scriptSpacePx = 0.5f * TEX_PT_TO_PX,
        delimiterShortfallPx = 5f * TEX_PT_TO_PX,
        arrayColumnSeparationPx = 5f * TEX_PT_TO_PX,
        arrayRuleThicknessPx = DEFAULT_RULE_PX,
        cancelLineThicknessPx = DEFAULT_RULE_PX,
    )

    private fun withFaces(block: (Oracle, MathLayoutEngine) -> Unit) {
        oracles.forEach { oracle -> oracle.faceFactory().use { block(oracle, MathLayoutEngine(it)) } }
    }

    private fun assertBox(expected: Box, actual: MathLayoutResult, label: String) {
        assertNear(expected.widthPt * TEX_PT_TO_PX, actual.box.width, "$label width")
        assertNear(expected.ascentPt * TEX_PT_TO_PX, actual.box.ascent, "$label ascent")
        assertNear(expected.descentPt * TEX_PT_TO_PX, actual.box.descent, "$label descent")
    }

    private fun assertNear(expected: Float, actual: Float, label: String, tolerance: Float = 0.08f) {
        assertTrue(abs(expected - actual) <= tolerance, "$label expected=$expected actual=$actual")
    }

    private data class Box(val widthPt: Float, val ascentPt: Float, val descentPt: Float)
    private data class Oracle(
        val label: String,
        val faceFactory: () -> SkiaMathFontFace,
        val atop: Box,
        val atopWidthPx: Float,
        val atopGlyphIds: List<UShort>?,
        val not: Box,
        val notGlyphId: UShort,
        val hline: Box,
    )

    private companion object {
        const val TEX_PT_TO_PX = 96f / 72.27f
        const val DEFAULT_RULE_PX = 0.4f * TEX_PT_TO_PX
        val oracles = listOf(
            Oracle(
                "Lete",
                { SkiaMathFontFace(LeteSansMath.load()) },
                Box(12.01186f, 19.5092f, 11.68246f),
                12.01186f * TEX_PT_TO_PX,
                null,
                Box(58.89072f, 16.598f, 4.14348f),
                629u,
                Box(23.7313f, 31.46956f, 17.97772f),
            ),
            Oracle(
                "STIX",
                { SkiaMathFontFace(StixTwoMath.load()) },
                Box(11.75891f, 19.40083f, 14.29651f),
                16.829649f,
                listOf(4421u, 4422u),
                Box(59.05934f, 16.33302f, 5.22752f),
                1808u,
                Box(23.36995f, 30.56613f, 18.13435f),
            ),
        )
    }
}
