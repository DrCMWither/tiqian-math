package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.MathTextRunProviderResult

class MathTextAccentDecorationTest {
    @Test
    fun embeddedTextBaselineMatchesSameFontXeTeXTraceInTextAndScriptStyles() = withFaces { label, face ->
        SkiaMathTextRunProvider.fromBytes(
            MathFaceId("tectonic-text-oracle-lete"),
            LeteSansMath.loadBytes(),
        ).use { textProvider ->
            val inline = MathLayoutEngine(face, textRunProvider = textProvider).layout(
                "x+\\text{rank}+x",
                MathLayoutOptions(fontSizePx = 32f),
            )
            val scripts = MathLayoutEngine(face, textRunProvider = textProvider).layout(
                "x^{\\text{rank}}+x_{\\text{rank}}",
                MathLayoutOptions(fontSizePx = 32f),
            )
            val expected = if (label == "Lete") {
                TextBaselineOracle(171.263f, 158.412f, -13.4414f, 8.00086f)
            } else {
                TextBaselineOracle(173.375f, 162.092f, -11.5212f, 6.7207f)
            }
            assertNear(expected.inlineWidthPx, inline.box.width, "$label inline XeTeX width")
            assertNear(expected.scriptFormulaWidthPx, scripts.box.width, "$label script XeTeX width")
            val inlineText = inline.box.glyphs.filter { it.faceId == textProvider.faceId }
            assertTrue(inlineText.isNotEmpty())
            assertTrue(inlineText.all { abs(it.baselineY) <= 0.001f }, "$label text hbox stays on the math baseline")
            val scriptText = scripts.box.glyphs.filter { it.faceId == textProvider.faceId }
            assertEquals(8, scriptText.size)
            assertNear(expected.superscriptBaselinePx, scriptText.take(4).map { it.baselineY }.distinct().single(), "$label XeTeX superscript baseline")
            assertNear(expected.subscriptBaselinePx, scriptText.takeLast(4).map { it.baselineY }.distinct().single(), "$label XeTeX subscript baseline")
            inline.decisions.single { it.name == "TeXEmbeddedText" }.let { decision ->
                assertEquals("HostRunBaselineWithPerGlyphShapingOffsets", decision.details["baselinePolicy"])
                assertEquals("0.0,0.0,0.0,0.0", decision.details["glyphBaselineOffsetsPx"])
            }
        }
    }

    @Test
    fun embeddedTextReplaysHostPerGlyphVerticalShapingOffsetsWithoutChangingTheMathBaseline() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("host-shaped-offset-fixture"),
                LeteSansMath.loadBytes(),
            ).use { delegate ->
                val offsetPx = 4.25f
                val provider = MathTextRunProvider { request ->
                    val ready = assertIs<MathTextRunProviderResult.Ready>(delegate.shapeTextAtom(request))
                    val glyphs = ready.run.glyphs.map { it.copy(baselineOffsetPx = it.baselineOffsetPx + offsetPx) }
                    MathTextRunProviderResult.Ready(ready.run.copy(
                        glyphs = glyphs,
                        ascent = glyphs.maxOf { -(it.inkBounds.top + it.baselineOffsetPx) }.coerceAtLeast(0f),
                        descent = glyphs.maxOf { it.inkBounds.bottom + it.baselineOffsetPx }.coerceAtLeast(0f),
                    ))
                }
                val result = MathLayoutEngine(face, textRunProvider = provider).layout(
                    "\\text{rank}",
                    MathLayoutOptions(fontSizePx = 32f),
                )
                assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
                assertTrue(result.box.glyphs.all { abs(it.baselineY - offsetPx) <= 0.001f })
                val first = result.box.glyphs.first()
                val raw = assertIs<MathTextRunProviderResult.Ready>(delegate.shapeTextAtom(
                    org.tiqian.math.layout.MathTextRunRequest(
                        text = "rank",
                        sourceRange = SourceRange(6, 10),
                        fontSizePx = 32f,
                        requestedWeight = MathFontWeight.Regular,
                        origin = MathTextOrigin.TextCommand,
                    ),
                )).run.glyphs.first()
                assertNear(raw.inkBounds.top + offsetPx, first.inkBounds.top, "translated host ink top")
                assertNear(raw.inkBounds.bottom + offsetPx, first.inkBounds.bottom, "translated host ink bottom")
                val decision = result.decisions.single { it.name == "TeXEmbeddedText" }
                assertEquals("4.25,4.25,4.25,4.25", decision.details["glyphBaselineOffsetsPx"])
                assertTrue(result.debugDump.contains("baselinePolicy=HostRunBaselineWithPerGlyphShapingOffsets"))
            }
        }
    }

    @Test
    fun embeddedTextIsOneTextShapingDomainAndPreservesSpaceAdvanceInAllStyles() = withFaces { label, face ->
        SkiaMathTextRunProvider.fromBytes(
            MathFaceId("test-explicit-host-text"),
            LeteSansMath.loadBytes(),
        ).use { textProvider ->
            MathStyle.entries.forEach { style ->
                val engine = MathLayoutEngine(face, textRunProvider = textProvider)
                val spaced = engine.layout("\\text{fi  text}", options(style))
                val compact = engine.layout("\\text{fitext}", options(style))
                assertTrue(spaced.diagnostics.isEmpty(), "$label/$style ${spaced.diagnostics}")
                assertTrue(spaced.box.width > compact.box.width, "$label/$style spaces own advance")
                val decision = spaced.decisions.single { it.name == "TeXEmbeddedText" }
                assertEquals("TextRunNotMathNoadSequence", decision.details["shaping"])
                assertEquals("2", decision.details["spaceCount"])
                assertEquals(textProvider.faceId.toString(), decision.details["faceIds"])
                assertTrue(spaced.box.glyphs.all { it.style == style })
                assertTrue(spaced.box.glyphs.all { it.sourceRange.start >= 6 && it.sourceRange.endExclusive <= 14 })
                assertIs<MathFormulaCapabilityResult.Ready>(face.formulaCapabilityEngine(textProvider).evaluate(
                    "\\text{fi  text}", options(style),
                ))
            }
        }
    }

    @Test
    fun declaredOperatorNameUsesOperatorSpacingScriptsAndSharedLimitsKernel() = withFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val ordinary = engine.layout("a\\operatorname{rank}b", MathLayoutOptions(fontSizePx = 32f))
        assertTrue(ordinary.diagnostics.isEmpty(), "$label ${ordinary.diagnostics}")
        val declared = ordinary.decisions.single { it.name == "TeXDeclaredOperatorName" }
        assertEquals("Operator", declared.details["atomClass"])
        val spacing = ordinary.decisions.filter { it.name == "TeXMathAtomSpacing" }
        assertTrue(spacing.any { it.details["right"] == "Operator" }, "$label left operator spacing")
        assertTrue(spacing.any { it.details["left"] == "Operator" }, "$label right operator spacing")

        val display = engine.layout("\\operatorname*{argmax}_x", MathLayoutOptions(MathMode.Display, 32f))
        assertEquals("Limits", display.decisions.single { it.name == "TeXOperatorLimitsPolicy" }.details["effectivePolicy"])
        assertTrue(display.decisions.any { it.name == "OpenTypeMathOperatorLimits" })
        val inline = engine.layout("\\operatorname*{argmax}_x", MathLayoutOptions(MathMode.Inline, 32f))
        assertEquals("NoLimits", inline.decisions.single { it.name == "TeXOperatorLimitsPolicy" }.details["effectivePolicy"])
        assertTrue(inline.decisions.any { it.name == "OpenTypeMathScriptPlacement" })
        assertTrue(display.box.glyphs.filter { it.sourceRange.start in 15..20 }.isNotEmpty(), "$label name source mapping")
    }

    @Test
    fun topAccentsConsumeFontAttachmentsAndHorizontalVariantsAcrossEightStyles() = withFaces { label, face ->
        MathStyle.entries.forEach { style ->
            val result = MathLayoutEngine(face).layout("\\hat{x}+\\bar{x}+\\vec{x}+\\widehat{x+y+z}", options(style))
            assertTrue(result.diagnostics.isEmpty(), "$label/$style ${result.diagnostics}")
            val accents = result.decisions.filter { it.name == "OpenTypeMathAccent" }
            assertEquals(4, accents.size, "$label/$style")
            accents.forEach { decision ->
                assertNear(
                    decision.details.getValue("baseAttachmentPx").toFloat(),
                    decision.details.getValue("accentX").toFloat() +
                        decision.details.getValue("accentAttachmentPx").toFloat(),
                    "$label/$style ${decision.details["identity"]} attachment",
                )
                assertEquals(style.cramped().toString(), decision.details["nucleusStyle"])
                assertTrue(decision.details.getValue("glyphIds").isNotBlank())
            }
            val wide = accents.single { it.details["identity"] == "widehat" }
            assertNotEquals("BaseGlyph", wide.details["construction"], "$label/$style wide accent selects MATH width evidence")
            assertTrue(wide.details["coveragePolicy"] in setOf("CoversTarget", "TeXLargestAvailableAccentVariantAllowsBaseOverhang"))
        }
    }

    @Test
    fun wideAccentAssemblyRetainsSemanticPaintOwnershipWhenRequired() = withFaces { label, face ->
        val source = "\\vec{abcdefghijklmno}"
        val result = MathLayoutEngine(face).layout(source, MathLayoutOptions(MathMode.Display, 48f))
        assertTrue(result.diagnostics.isEmpty(), "$label ${result.diagnostics}")
        val decision = result.decisions.single { it.name == "OpenTypeMathAccent" }
        assertTrue(decision.details["coveragePolicy"] in setOf("CoversTarget", "TeXLargestAvailableAccentVariantAllowsBaseOverhang"))
        if (decision.details["construction"] == "Assembly") {
            val group = result.box.constructionPaintGroups.single { it.kind == MathConstructionPaintKind.Accent }
            assertEquals(MathConstructionShapeKind.Assembly, group.shapeKind)
            assertTrue(result.box.glyphs.filter { it.constructionGroupId == group.id }.size > 1)
            assertIs<MathFormulaCapabilityResult.Ready>(face.formulaCapabilityEngine().evaluate(
                source, MathLayoutOptions(MathMode.Display, 48f),
            ))
        }
    }

    @Test
    fun overlineAndUnderlineUsePinnedMathConstantsAndInkGapsInAllStyles() = withFaces { label, face ->
        MathStyle.entries.forEach { style ->
            val source = "\\overline{x}+\\underline{\\frac{a}{b}}"
            val result = MathLayoutEngine(face).layout(source, options(style))
            assertTrue(result.diagnostics.isEmpty(), "$label/$style ${result.diagnostics}")
            val decisions = result.decisions.filter { it.name == "OpenTypeMathRuleDecoration" }
            assertEquals(2, decisions.size)
            val overline = decisions.single { it.details["kind"] == "Overline" }
            val underline = decisions.single { it.details["kind"] == "Underline" }
            assertEquals(style.cramped().toString(), overline.details["nucleusStyle"], "$label/$style overline")
            assertEquals(style.toString(), underline.details["nucleusStyle"], "$label/$style underline")
            decisions.forEach { decision ->
                val rule = result.box.rules.single {
                    abs(it.top - decision.details.getValue("ruleTopPx").toFloat()) < 0.03f &&
                        abs(it.bottom - decision.details.getValue("ruleBottomPx").toFloat()) < 0.03f
                }
                assertNear(
                    decision.details.getValue("ruleThicknessPx").toFloat(),
                    rule.bottom - rule.top,
                    "$label/$style rule thickness",
                )
            }
        }
    }

    @Test
    fun extremeNamedConstantsAndAttachmentEvidenceIndependentlyControlGeometry() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val normal = MathLayoutEngine(delegate).layout("\\hat{x}", MathLayoutOptions(fontSizePx = 32f))
            val normalDecision = normal.decisions.single { it.name == "OpenTypeMathAccent" }
            val baseGlyphId = normal.box.glyphs.single { it.sourceRange == SourceRange(5, 6) }.glyphId
            val changedAttachment = delegate.mathFont.topAccentAttachments.toMutableMap().apply {
                this[baseGlyphId] = getValue(baseGlyphId) + 500
            }
            val attachmentFace = ConstantsFace(
                delegate,
                delegate.mathFont.copy(topAccentAttachments = changedAttachment),
            )
            val shifted = MathLayoutEngine(attachmentFace).layout("\\hat{x}", MathLayoutOptions(fontSizePx = 32f))
            val shiftedDecision = shifted.decisions.single { it.name == "OpenTypeMathAccent" }
            assertNear(
                normalDecision.details.getValue("accentX").toFloat() + 16f,
                shiftedDecision.details.getValue("accentX").toFloat(),
                "TopAccentAttachment controls horizontal alignment",
            )

            val extremeConstants = delegate.mathFont.constants.copy(
                overbarVerticalGap = 900,
                overbarRuleThickness = 170,
                overbarExtraAscender = 310,
                underbarVerticalGap = 700,
                underbarRuleThickness = 230,
                underbarExtraDescender = 410,
            )
            val extreme = MathLayoutEngine(ConstantsFace(delegate, delegate.mathFont.copy(constants = extremeConstants)))
                .layout("\\overline{x}+\\underline{y}", MathLayoutOptions(fontSizePx = 32f))
            val over = extreme.decisions.single { it.name == "OpenTypeMathRuleDecoration" && it.details["kind"] == "Overline" }
            val under = extreme.decisions.single { it.name == "OpenTypeMathRuleDecoration" && it.details["kind"] == "Underline" }
            assertNear(28.8f, over.details.getValue("verticalGapPx").toFloat(), "named overbar gap")
            assertNear(5.44f, over.details.getValue("ruleThicknessPx").toFloat(), "named overbar thickness")
            assertNear(9.92f, over.details.getValue("extraReservePx").toFloat(), "named overbar extra")
            assertNear(22.4f, under.details.getValue("verticalGapPx").toFloat(), "named underbar gap")
            assertNear(7.36f, under.details.getValue("ruleThicknessPx").toFloat(), "named underbar thickness")
            assertNear(13.12f, under.details.getValue("extraReservePx").toFloat(), "named underbar extra")
        }
    }

    @Test
    fun decorationsNestWithFractionsRadicalsAndAccentsWithoutLosingCleanBoxes() = withFaces { label, face ->
        val source = "\\overline{\\underline{\\widehat{\\sqrt{\\frac{a}{b}}}}}"
        val result = MathLayoutEngine(face).layout(source, MathLayoutOptions(MathMode.Display, 40f))
        assertTrue(result.diagnostics.isEmpty(), "$label ${result.diagnostics}")
        assertEquals(2, result.box.rules.count { it.sourceRange.start == 0 || it.sourceRange.start == 10 }, "$label decoration rules")
        assertTrue(result.box.rules.size >= 4, "$label includes fraction/radical/decorations")
        assertTrue(result.box.texCleanBoxMetrics.ascent >= -result.box.inkBounds.top - 0.03f)
        assertTrue(result.debugDump.contains("OpenTypeMathAccent"))
        assertTrue(result.debugDump.contains("OpenTypeMathRuleDecoration"))
    }

    private fun options(style: MathStyle) = MathLayoutOptions(
        mode = if (style.level == MathStyleLevel.Display) MathMode.Display else MathMode.Inline,
        fontSizePx = 32f,
        initialStyle = style,
    )

    private fun withFaces(block: (String, SkiaMathFontFace) -> Unit) {
        listOf(
            "Lete" to LeteSansMath.load(),
            "STIX" to StixTwoMath.load(),
        ).forEach { (label, font) -> SkiaMathFontFace(font).use { block(label, it) } }
    }

    private class ConstantsFace(
        delegate: MathFontFace,
        override val mathFont: org.tiqian.math.font.opentype.OpenTypeMathFont,
    ) : MathFontFace by delegate

    private data class TextBaselineOracle(
        val inlineWidthPx: Float,
        val scriptFormulaWidthPx: Float,
        val superscriptBaselinePx: Float,
        val subscriptBaselinePx: Float,
    )

    private fun assertNear(expected: Float, actual: Float, message: String, tolerance: Float = 0.04f) {
        assertTrue(abs(expected - actual) <= tolerance, "$message expected=$expected actual=$actual")
    }
}
