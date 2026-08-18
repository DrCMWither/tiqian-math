package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.constructionPaintOwnershipDiagnostics

/** Reproducer: `preview/tectonic/remaining-command-oracle.tex`, Tectonic 0.17.0 at 24bp. */
class TectonicRemainingCommandsOracleTest {
    @Test
    fun remainingCommandsPinSameFontXeTeXBoxesAndKnownSstyBoundary() = withFaces { oracle, engine ->
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

        val choose = engine.layout("{a\\choose b}", options())
        // XeTeX does not apply STIX's optional ssty alternates to the fraction children,
        // while Tiqian's established OpenType style contract does. The external oracle still
        // pins the delimiter glyphs and full vertical box; chooseEngineWidthPx records that existing
        // child-run divergence rather than weakening the primitive delimiter assertions.
        assertNear(oracle.chooseEngineWidthPx, choose.box.width, "${oracle.label} choose width")
        assertNear(oracle.choose.ascentPt * TEX_PT_TO_PX, choose.box.ascent, "${oracle.label} choose ascent")
        assertNear(oracle.choose.descentPt * TEX_PT_TO_PX, choose.box.descent, "${oracle.label} choose descent")
        assertEquals(
            oracle.chooseDelimiterGlyphIds,
            listOf(choose.box.glyphs.first().glyphId, choose.box.glyphs.last().glyphId),
            "${oracle.label} choose delimiter glyphs",
        )
        assertEquals("GeneralizedChoose", choose.decisions.single {
            it.name == "TeXFractionCommand"
        }.details["origin"])
        assertEquals(2, choose.decisions.count { it.name == "GeneralizedChooseDelimiter" })
        assertTrue(choose.decisions.filter { it.name == "GeneralizedChooseDelimiter" }.all {
            it.details["chooseTargetParameter"] == "delim2" &&
                it.details["delimiterAxisPolicy"] == "XeTeXCurrentMathSizeVarDelimiterAxis"
        })
        assertTrue(choose.box.rules.isEmpty(), "${oracle.label} choose must be ruleless")
        assertTrue(
            choose.decisions.none { it.name == "TeXFractionNullDelimiters" },
            "${oracle.label} primitive choose must not synthesize null delimiter boxes",
        )
        val chooseCommandRange = SourceRange(2, 9)
        val delimiterGroups = choose.box.constructionPaintGroups.filter {
            it.kind == MathConstructionPaintKind.Delimiter
        }
        assertEquals(2, delimiterGroups.size, "${oracle.label} choose delimiter paint ownership")
        assertTrue(delimiterGroups.all { it.sourceRange == chooseCommandRange })
        assertTrue(choose.box.constructionPaintOwnershipDiagnostics().isEmpty(), choose.debugDump)
        assertTrue(
            choose.box.glyphs.filter { it.constructionGroupId != null }.all {
                it.sourceRange == chooseCommandRange
            },
            choose.debugDump,
        )

        val displayChoose = engine.layout("\\displaystyle{a\\choose b}", options())
        assertBox(oracle.chooseDisplay, displayChoose, "${oracle.label} display choose")
        assertEquals(
            oracle.chooseDelimiterGlyphIds,
            listOf(displayChoose.box.glyphs.first().glyphId, displayChoose.box.glyphs.last().glyphId),
            "${oracle.label} display choose delimiter glyphs",
        )
        assertTrue(displayChoose.decisions.filter { it.name == "GeneralizedChooseDelimiter" }.all {
            it.details["chooseTargetParameter"] == "delim1" &&
                it.details["delimitedSubFormulaMinHeightUsed"] == "true"
        })

        val not = engine.layout("\\mu\\not\\equiv\\mu", options())
        assertNear(oracle.not.widthPt * TEX_PT_TO_PX, not.box.width, "${oracle.label} not width")
        val negated = not.box.glyphs.single { it.sourceRange == SourceRange(3, 13) }
        assertEquals(oracle.notGlyphId, negated.glyphId, oracle.label)
        assertEquals("U+2262", not.decisions.single { it.name == "TeXNotRelation" }.details["precomposedScalar"])

        assertTrue(listOf(atop, choose, displayChoose, not, hline).all { it.diagnostics.isEmpty() }, oracle.label)
    }

    @Test
    fun pandoraArticleChooseFormulaIsProductionReadyWithAHostTextProvider() {
        val source = "\\begin{align} P_{\\text{blue}>0.5}=\\sum_{i=\\frac{A}{2}+1}^{A}" +
            "\\sum_{j=0}^{a}\\left(\\frac12\\right)^{a}{a\\choose j}" +
            "(0.8)^{A-a-i+j}(0.2)^{i-j}{A-a\\choose i-j} \\end{align}"
        SkiaMathTextRunProvider.fromBytes(
            faceId = MathFaceId("pandora-article-host-text"),
            fontBytes = LeteSansMath.loadBytes(),
        ).use { textProvider ->
            listOf(
                "Lete" to { SkiaMathFontFace(LeteSansMath.load()) },
                "STIX" to { SkiaMathFontFace(StixTwoMath.load()) },
            ).forEach { (label, factory) ->
                factory().use { face ->
                    val ready = assertIs<MathFormulaCapabilityResult.Ready>(
                        face.formulaCapabilityEngine(textProvider).evaluate(
                            source,
                            options().copy(mode = MathMode.Display, initialStyle = MathStyle.Display),
                        ),
                        label,
                    )
                    assertTrue(ready.diagnostics.isEmpty(), "$label ${ready.diagnostics}")
                    assertEquals(2, ready.layoutResult.decisions.count {
                        it.name == "TeXFractionCommand" && it.details["origin"] == "GeneralizedChoose"
                    })
                }
            }
        }
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
        val choose: Box,
        val chooseDisplay: Box,
        val atopWidthPx: Float,
        val atopGlyphIds: List<UShort>?,
        val chooseEngineWidthPx: Float,
        val chooseDelimiterGlyphIds: List<UShort>,
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
                Box(28.88387f, 26.58403f, 13.09221f),
                Box(33.0033f, 26.58403f, 17.03342f),
                12.01186f * TEX_PT_TO_PX,
                null,
                28.88387f * TEX_PT_TO_PX,
                listOf(1836u, 1851u),
                Box(58.89072f, 16.598f, 4.14348f),
                629u,
                Box(23.7313f, 31.46956f, 17.97772f),
            ),
            Oracle(
                "STIX",
                { SkiaMathFontFace(StixTwoMath.load()) },
                Box(11.75891f, 19.40083f, 14.29651f),
                Box(28.82364f, 23.39204f, 14.29651f),
                Box(32.83467f, 26.95834f, 15.70831f),
                16.829649f,
                listOf(4421u, 4422u),
                39.497604f,
                listOf(1302u, 1314u),
                Box(59.05934f, 16.33302f, 5.22752f),
                1808u,
                Box(23.36995f, 30.56613f, 18.13435f),
            ),
        )
    }
}
