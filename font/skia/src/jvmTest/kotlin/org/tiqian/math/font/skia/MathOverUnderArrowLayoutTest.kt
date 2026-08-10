package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

class MathOverUnderArrowLayoutTest {
    @Test
    fun extensibleArrowsReplayAmsmathLeadersAcrossBothFontsAndEightStyles() = withFaces { label, face ->
        var fixedTargetWidth: Float? = null
        MathStyle.entries.forEach { style ->
            val source = "a\\xrightarrow[k-1]{p_k}b"
            val options = options(style)
            val result = MathLayoutEngine(face).layout(source, options)
            assertTrue(result.diagnostics.isEmpty(), "$label/$style ${result.diagnostics}\n${result.debugDump}")
            val decision = result.decisions.single { it.name == "AmsmathXeTeXExtensibleArrow" }
            assertEquals("Relation", decision.details["atomClass"], "$label/$style")
            assertEquals("Display", decision.details["arrowMeasurementStyle"], "$label/$style")
            assertEquals("Script", decision.details["labelMeasurementStyle"], "$label/$style")
            assertEquals(style.superscript().toString(), decision.details["upperStyle"], "$label/$style")
            assertEquals(style.subscript().toString(), decision.details["lowerStyle"], "$label/$style")
            assertEquals("AmsmathArrowfillCLeadersRelbar", decision.details["fillPolicy"], "$label/$style")
            assertEquals("AmsmathMathSmash", decision.details["relbarVerticalPolicy"], "$label/$style")
            assertEquals("7", decision.details["endpointOverlapMu"], "$label/$style")
            assertEquals("2", decision.details["leaderInnerOverlapMu"], "$label/$style")
            fixedTargetWidth?.let { assertNear(it, decision.float("targetWidthPx"), "$label/$style fixed target") }
                ?: run { fixedTargetWidth = decision.float("targetWidthPx") }
            val group = result.box.constructionPaintGroups.single {
                it.kind == MathConstructionPaintKind.ExtensibleArrow
            }
            assertEquals(SourceRange(1, 13), group.sourceRange, "$label/$style source ownership")
            assertTrue(result.box.glyphs.filter { it.constructionGroupId == group.id }.isNotEmpty())
            val capability = face.formulaCapabilityEngine().evaluate(source, options)
            assertIs<MathFormulaCapabilityResult.Ready>(capability)
        }
    }

    @Test
    fun longLabelsAddCenteredRelbarLeadersWithoutChangingRelationSpacing() = withFaces { label, face ->
        val short = MathLayoutEngine(face).layout("a\\xrightarrow{n}b", MathLayoutOptions(fontSizePx = 32f))
        val long = MathLayoutEngine(face).layout(
            "a\\xrightarrow{abcdefghijklmno}b",
            MathLayoutOptions(fontSizePx = 32f),
        )
        listOf(short, long).forEach { result ->
            assertTrue(result.diagnostics.isEmpty(), "$label/${result.source} ${result.diagnostics}")
            assertTrue(result.decisions.any {
                it.name == "TeXMathAtomSpacing" && it.details["right"] == "Relation"
            }, "$label left relation spacing")
            assertTrue(result.decisions.any {
                it.name == "TeXMathAtomSpacing" && it.details["left"] == "Relation"
            }, "$label right relation spacing")
        }
        val shortArrow = short.decisions.single { it.name == "AmsmathXeTeXExtensibleArrow" }
        val longArrow = long.decisions.single { it.name == "AmsmathXeTeXExtensibleArrow" }
        assertTrue(longArrow.float("targetWidthPx") > shortArrow.float("targetWidthPx"), label)
        assertTrue(longArrow.details.getValue("leaderCount").toInt() > shortArrow.details.getValue("leaderCount").toInt(), label)
        assertIs<MathFormulaCapabilityResult.Ready>(
            face.formulaCapabilityEngine().evaluate(long.source, MathLayoutOptions(fontSizePx = 32f)),
        )
    }

    @Test
    fun sameFontAmsmathBoxesMatchReviewedTectonicShowbox() = withFaces { label, face ->
        val factor = 96f / 72.27f
        val cases = when (label) {
            "Lete" -> listOf(
                ArrowOracle("\\xrightarrow{p_k}", MathStyle.Text, 29.61990f, 33.03177f, 0f),
                ArrowOracle("\\xrightarrow[k-1]{p_k}", MathStyle.Text, 43.87502f, 33.03177f, 20.87550f),
                ArrowOracle("\\xrightarrow{\\alpha+\\beta+\\gamma}", MathStyle.Text, 66.11734f, 35.24037f, 0f),
                ArrowOracle("\\xleftarrow[k-1]{p_k}", MathStyle.Text, 43.87502f, 33.03177f, 20.87550f),
                ArrowOracle("\\xrightarrow[k-1]{p_k}", MathStyle.Script, 43.87502f, 28.95313f, 15.70604f),
            )
            else -> listOf(
                ArrowOracle("\\xrightarrow{p_k}", MathStyle.Text, 33.00452f, 30.96817f, 0f),
                ArrowOracle("\\xrightarrow[k-1]{p_k}", MathStyle.Text, 45.08913f, 30.96817f, 19.98776f),
                ArrowOracle("\\xrightarrow{\\alpha+\\beta+\\gamma}", MathStyle.Text, 71.58090f, 34.76312f, 0f),
                ArrowOracle("\\xleftarrow[k-1]{p_k}", MathStyle.Text, 45.08913f, 30.99226f, 19.98776f),
                ArrowOracle("\\xrightarrow[k-1]{p_k}", MathStyle.Script, 45.08913f, 28.80751f, 15.33879f),
            )
        }
        cases.forEach { oracle ->
            val result = MathLayoutEngine(face).layout(oracle.source, options(oracle.style))
            assertTrue(result.diagnostics.isEmpty(), "$label/${oracle.source} ${result.diagnostics}\n${result.debugDump}")
            val decision = result.decisions.single { it.name == "AmsmathXeTeXExtensibleArrow" }
            assertNear(
                oracle.widthPt * factor,
                result.box.width,
                "$label/${oracle.source} width decision=${decision.details}",
                0.08f,
            )
            assertNear(oracle.ascentPt * factor, result.box.ascent, "$label/${oracle.source} ascent", 0.08f)
            assertNear(oracle.descentPt * factor, result.box.descent, "$label/${oracle.source} descent", 0.08f)
            assertNear(result.box.width, decision.float("targetWidthPx"), "$label/${oracle.source} decision width")
        }
    }

    @Test
    fun sameFontAmsmathOverUnderBoxesMatchReviewedTectonicShowbox() = withFaces { label, face ->
        val factor = 96f / 72.27f
        val cases = if (label == "Lete") {
            listOf(
                OverUnderOracle("\\overset{u}{=}", 16.50165f, 27.06422f, 0f),
                OverUnderOracle("\\underset{d}{+}", 16.50165f, 13.82765f, 21.33081f),
                OverUnderOracle("\\stackrel{def}{=}", 26.40747f, 32.91570f, 0f),
            )
        } else {
            listOf(
                OverUnderOracle("\\overset{u}{=}", 17.34480f, 29.05620f, 0f),
                OverUnderOracle("\\underset{d}{+}", 17.34480f, 13.44221f, 20.95135f),
                OverUnderOracle("\\stackrel{def}{=}", 30.43774f, 33.08646f, 0f),
            )
        }
        cases.forEach { oracle ->
            val result = MathLayoutEngine(face).layout(oracle.source, options(MathStyle.Text))
            val decision = result.decisions.single { it.name == "TeXOverUnderNoad" }
            assertTrue(result.diagnostics.isEmpty(), "$label/${oracle.source}: ${result.diagnostics}")
            assertNear(oracle.widthPt * factor, result.box.width, "$label/${oracle.source} width ${decision.details}", 0.08f)
            assertNear(oracle.ascentPt * factor, result.box.ascent, "$label/${oracle.source} ascent ${decision.details}", 0.08f)
            assertNear(oracle.descentPt * factor, result.box.descent, "$label/${oracle.source} descent ${decision.details}", 0.08f)
            assertEquals("XeTeXBigOpSpacing5FromOpenTypeMATHStackGapMin", decision.details["outerLimitPaddingPolicy"])
        }
    }

    @Test
    fun overUnderAndStackrelShareNamedMathLimitConstantsWithoutLosingAtomClass() = withFaces { label, face ->
        MathStyle.entries.forEach { style ->
            val result = MathLayoutEngine(face).layout(
                "a\\overset{u}{=}b+\\underset{d}{x}+\\stackrel{s}{=}c",
                options(style),
            )
            assertTrue(result.diagnostics.isEmpty(), "$label/$style ${result.diagnostics}")
            val stacks = result.decisions.filter { it.name == "TeXOverUnderNoad" }
            assertEquals(3, stacks.size, "$label/$style")
            assertEquals(listOf("Relation", "Ordinary", "Relation"), stacks.map { it.details["atomClass"] })
            stacks.forEach { decision ->
                assertEquals("OpenTypeMathUpperLowerLimitConstants", decision.details["geometryKernel"])
                decision.details["actualUpperGapPx"]?.takeUnless { it == "null" }?.toFloat()?.let {
                    assertTrue(it + EPS >= decision.float("upperLimitGapMinPx"), "$label/$style upper")
                }
                decision.details["actualLowerGapPx"]?.takeUnless { it == "null" }?.toFloat()?.let {
                    assertTrue(it + EPS >= decision.float("lowerLimitGapMinPx"), "$label/$style lower")
                }
            }
        }
    }

    @Test
    fun extremeLimitConstantsDriveGenericStackPlacementThroughTheSharedKernel() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val normal = MathLayoutEngine(delegate).layout("\\overset{u}{x}+\\underset{d}{x}", options(MathStyle.Text))
            val extreme = delegate.mathFont.constants.copy(
                upperLimitGapMin = delegate.mathFont.constants.upperLimitGapMin + 900,
                upperLimitBaselineRiseMin = delegate.mathFont.constants.upperLimitBaselineRiseMin + 1300,
                lowerLimitGapMin = delegate.mathFont.constants.lowerLimitGapMin + 1700,
                lowerLimitBaselineDropMin = delegate.mathFont.constants.lowerLimitBaselineDropMin + 2100,
            )
            val changed = MathLayoutEngine(ConstantsFace(delegate, delegate.mathFont.copy(constants = extreme)))
                .layout("\\overset{u}{x}+\\underset{d}{x}", options(MathStyle.Text))
            val normalStacks = normal.decisions.filter { it.name == "TeXOverUnderNoad" }
            val changedStacks = changed.decisions.filter { it.name == "TeXOverUnderNoad" }
            assertTrue(changedStacks[0].float("upperShiftPx") > normalStacks[0].float("upperShiftPx"))
            assertTrue(changedStacks[1].float("lowerShiftPx") > normalStacks[1].float("lowerShiftPx"))
            assertNear(
                delegate.mathFont.scaleDesignUnits(extreme.upperLimitGapMin, 32f),
                changedStacks[0].float("upperLimitGapMinPx"),
                "upper gap constant",
            )
            assertNear(
                delegate.mathFont.scaleDesignUnits(extreme.lowerLimitGapMin, 32f),
                changedStacks[1].float("lowerLimitGapMinPx"),
                "lower gap constant",
            )
        }
    }

    private fun options(style: MathStyle) = MathLayoutOptions(
        mode = if (style.level == MathStyleLevel.Display) MathMode.Display else MathMode.Inline,
        fontSizePx = 32f,
        initialStyle = style,
    )

    private fun withFaces(block: (String, SkiaMathFontFace) -> Unit) {
        listOf("Lete" to LeteSansMath.load(), "STIX" to StixTwoMath.load()).forEach { (label, font) ->
            SkiaMathFontFace(font).use { block(label, it) }
        }
    }

    private class ConstantsFace(
        delegate: MathFontFace,
        override val mathFont: org.tiqian.math.font.opentype.OpenTypeMathFont,
    ) : MathFontFace by delegate

    private data class ArrowOracle(
        val source: String,
        val style: MathStyle,
        val widthPt: Float,
        val ascentPt: Float,
        val descentPt: Float,
    )

    private data class OverUnderOracle(
        val source: String,
        val widthPt: Float,
        val ascentPt: Float,
        val descentPt: Float,
    )

    private fun MathLayoutDecision.float(key: String): Float = details.getValue(key).toFloat()
    private fun assertNear(expected: Float, actual: Float, message: String, tolerance: Float = 0.05f) {
        assertTrue(abs(expected - actual) <= tolerance, "$message expected=$expected actual=$actual")
    }

    private companion object {
        const val EPS = 0.05f
    }
}
