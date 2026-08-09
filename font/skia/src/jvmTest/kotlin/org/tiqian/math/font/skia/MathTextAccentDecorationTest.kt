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

class MathTextAccentDecorationTest {
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

    private fun assertNear(expected: Float, actual: Float, message: String, tolerance: Float = 0.04f) {
        assertTrue(abs(expected - actual) <= tolerance, "$message expected=$expected actual=$actual")
    }
}
