package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.MathLayoutDecision
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MeasuredOutlineConstructionRun
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathOperatorGlyphRequest
import org.tiqian.math.layout.MathSymbolGlyphRequest
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.ResolvedMathOperator
import org.tiqian.math.layout.ResolvedMathSymbol
import org.tiqian.math.layout.ResolvedMathSymbolRun

class MathSideScriptInkPlacementTest {
    @Test
    fun radicalLogicalReserveDoesNotMoveOrdinarySideScriptsForBothFontsAndNestedStyles() =
        withSideScriptFaces { label, delegate ->
            val enlargedFace = SideScriptOverrideFace(
                delegate,
                delegate.mathFont.copy(
                    constants = delegate.mathFont.constants.copy(radicalExtraAscender = 3_000),
                ),
            )
            val cases = listOf(
                ReserveCase("compound-base-superscript", "\\sqrt{x}^2", MathStyle.Text),
                ReserveCase("compound-base-superscript-cramped", "\\sqrt{x}^2", MathStyle.TextCramped),
                ReserveCase("compound-subscript", "x_{\\sqrt{y}}", MathStyle.Text),
                ReserveCase("compound-superscript-and-subscript", "x_{\\sqrt{y}}^{\\sqrt{z}}", MathStyle.Text),
                ReserveCase(
                    "nested-script-cramped",
                    "z_{\\sqrt{x}^2}",
                    MathStyle.Text,
                    decisionStyle = MathStyle.ScriptCramped,
                ),
            )

            cases.forEach { case ->
                val options = MathLayoutOptions(
                    mode = MathMode.Inline,
                    fontSizePx = 48f,
                    initialStyle = case.initialStyle,
                )
                val baseResult = MathLayoutEngine(delegate).layout(case.source, options)
                val enlargedResult = MathLayoutEngine(enlargedFace).layout(case.source, options)
                val baseDecision = baseResult.scriptDecision(case.decisionStyle ?: case.initialStyle)
                val enlargedDecision = enlargedResult.scriptDecision(case.decisionStyle ?: case.initialStyle)
                assertEquals(
                    baseResult.radicalConstructionKinds(),
                    enlargedResult.radicalConstructionKinds(),
                    "$label/${case.label} reserve-only change keeps radical constructions",
                )
                assertNear(
                    baseDecision.floatOrNull("superscriptShiftPx"),
                    enlargedDecision.floatOrNull("superscriptShiftPx"),
                    "$label/${case.label} superscript shift ignores logical reserve",
                )
                assertNear(
                    baseDecision.floatOrNull("subscriptShiftPx"),
                    enlargedDecision.floatOrNull("subscriptShiftPx"),
                    "$label/${case.label} subscript shift ignores logical reserve",
                )
                assertTrue(
                    enlargedResult.box.ascent > baseResult.box.ascent ||
                        enlargedDecision.details["baseLogicalAscentPx"] != baseDecision.details["baseLogicalAscentPx"] ||
                        enlargedDecision.details["subscriptLogicalAscentPx"] !=
                        baseDecision.details["subscriptLogicalAscentPx"],
                    "$label/${case.label} fixture must actually enlarge a logical reserve",
                )
                assertNear(
                    baseResult.box.inkBounds.top,
                    enlargedResult.box.inkBounds.top,
                    "$label/${case.label} reserve-only change keeps final ink top",
                )
                assertNear(
                    baseResult.box.inkBounds.bottom,
                    enlargedResult.box.inkBounds.bottom,
                    "$label/${case.label} reserve-only change keeps final ink bottom",
                )
            }
        }

    @Test
    fun actualBaseAndScriptInkEdgesDriveNamedConstraintsForBothFonts() =
        withSideScriptFaces { label, delegate ->
            val size = 48f
            val constants = delegate.mathFont.constants.copy(
                superscriptShiftUp = 0,
                superscriptShiftUpCramped = 0,
                superscriptBottomMin = 0,
                superscriptBaselineDropMax = 0,
                subscriptShiftDown = 0,
                subscriptTopMax = 0,
                subscriptBaselineDropMin = 0,
                subSuperscriptGapMin = 800,
                superscriptBottomMaxWithSubscript = 1_000,
            )
            val face = SideScriptOverrideFace(delegate, delegate.mathFont.copy(constants = constants))
            val engine = MathLayoutEngine(face)
            val baselineDropEngine = MathLayoutEngine(
                SideScriptOverrideFace(
                    delegate,
                    delegate.mathFont.copy(constants = constants.copy(subscriptTopMax = 1_000)),
                ),
            )

            val shortBaseSource = "{x}"
            val tallBaseSource = "{\\sqrt{\\frac{x}{y}}}"
            val shortBase = engine.layout(shortBaseSource, MathLayoutOptions(MathMode.Inline, size)).box
            val tallBase = engine.layout(tallBaseSource, MathLayoutOptions(MathMode.Inline, size)).box
            assertTrue(tallBase.inkBounds.top < shortBase.inkBounds.top, "$label taller compound base ink")
            assertTrue(tallBase.inkBounds.bottom > shortBase.inkBounds.bottom, "$label deeper compound base ink")

            val shortBaseSuperscript = engine.layout(
                "$shortBaseSource^2",
                MathLayoutOptions(MathMode.Inline, size),
            ).scriptDecision(MathStyle.Text)
            val tallBaseSuperscript = engine.layout(
                "$tallBaseSource^2",
                MathLayoutOptions(MathMode.Inline, size),
            ).scriptDecision(MathStyle.Text)
            assertNear(
                -shortBase.inkBounds.top,
                shortBaseSuperscript.float("superscriptShiftPx"),
                "$label SuperscriptBaselineDropMax uses short base ink top",
            )
            assertNear(
                -tallBase.inkBounds.top,
                tallBaseSuperscript.float("superscriptShiftPx"),
                "$label SuperscriptBaselineDropMax uses tall base ink top",
            )

            val shortBaseSubscript = baselineDropEngine.layout(
                "${shortBaseSource}_2",
                MathLayoutOptions(MathMode.Inline, size),
            ).scriptDecision(MathStyle.Text)
            val tallBaseSubscript = baselineDropEngine.layout(
                "${tallBaseSource}_2",
                MathLayoutOptions(MathMode.Inline, size),
            ).scriptDecision(MathStyle.Text)
            assertNear(
                shortBase.inkBounds.bottom,
                shortBaseSubscript.float("subscriptShiftPx"),
                "$label SubscriptBaselineDropMin uses short base ink bottom",
            )
            assertNear(
                tallBase.inkBounds.bottom,
                tallBaseSubscript.float("subscriptShiftPx"),
                "$label SubscriptBaselineDropMin uses deep base ink bottom",
            )

            val shallowScriptSource = "{y}"
            val deepScriptSource = "\\sqrt{y_j}"
            val shallowScript = engine.layout(
                shallowScriptSource,
                MathLayoutOptions(MathMode.Inline, size, initialStyle = MathStyle.Script),
            ).box
            val deepScript = engine.layout(
                deepScriptSource,
                MathLayoutOptions(MathMode.Inline, size, initialStyle = MathStyle.Script),
            ).box
            assertTrue(deepScript.inkBounds.bottom > shallowScript.inkBounds.bottom, "$label deeper superscript ink")
            val shallowSuperscript = engine.layout(
                "x^{$shallowScriptSource}",
                MathLayoutOptions(MathMode.Inline, size),
            ).scriptDecision(MathStyle.Text)
            val deepSuperscript = engine.layout(
                "x^{$deepScriptSource}",
                MathLayoutOptions(MathMode.Inline, size),
            ).scriptDecision(MathStyle.Text)
            assertNear(
                shallowScript.inkBounds.bottom,
                shallowSuperscript.float("superscriptShiftPx"),
                "$label SuperscriptBottomMin uses shallow script ink bottom",
            )
            assertNear(
                deepScript.inkBounds.bottom,
                deepSuperscript.float("superscriptShiftPx"),
                "$label SuperscriptBottomMin uses deep script ink bottom",
            )
            assertEquals("CompoundBox", deepSuperscript.details["superscriptKind"])

            assertTrue(deepScript.inkBounds.top < shallowScript.inkBounds.top, "$label taller subscript ink")
            val shallowSubscript = engine.layout(
                "x_{$shallowScriptSource}",
                MathLayoutOptions(MathMode.Inline, size),
            ).scriptDecision(MathStyle.Text)
            val tallSubscript = engine.layout(
                "x_{$deepScriptSource}",
                MathLayoutOptions(MathMode.Inline, size),
            ).scriptDecision(MathStyle.Text)
            assertNear(
                -shallowScript.inkBounds.top,
                shallowSubscript.float("subscriptShiftPx"),
                "$label SubscriptTopMax uses shallow script ink top",
            )
            assertNear(
                -deepScript.inkBounds.top,
                tallSubscript.float("subscriptShiftPx"),
                "$label SubscriptTopMax uses tall script ink top",
            )
            assertEquals("CompoundBox", tallSubscript.details["subscriptKind"])

            val pairedSource = "x_{g}^{g}"
            val paired = engine.layout(pairedSource, MathLayoutOptions(MathMode.Inline, size))
            val pairedDecision = paired.scriptDecision(MathStyle.Text)
            val subscriptGlyph = paired.box.glyphs.single { it.sourceRange == SourceRange(3, 4) }
            val superscriptGlyph = paired.box.glyphs.single { it.sourceRange == SourceRange(7, 8) }
            val replayedGap = subscriptGlyph.inkBounds.top - superscriptGlyph.inkBounds.bottom
            val expectedGap = face.mathFont.scaleDesignUnits(constants.subSuperscriptGapMin, size)
            assertNear(expectedGap, replayedGap, "$label paired-script final glyph ink gap")
            assertNear(replayedGap, pairedDecision.float("finalInkGapPx"), "$label decision replays final ink gap")
            assertEquals(
                "OpenTypeMATH1.9InkEdgesForOrdinarySideScripts",
                pairedDecision.details["verticalPlacementMetricPolicy"],
            )
            assertEquals(
                "PreserveTranslatedChildLogicalExtentsAfterInkConstrainedPlacement",
                pairedDecision.details["logicalReservePolicy"],
            )
        }
}

private data class ReserveCase(
    val label: String,
    val source: String,
    val initialStyle: MathStyle,
    val decisionStyle: MathStyle? = null,
)

private class SideScriptOverrideFace(
    private val delegate: SkiaMathFontFace,
    override val mathFont: OpenTypeMathFont,
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
    ): MeasuredMathRun = delegate.measureGlyph(glyphId, fontSizePx, style, sourceRange)

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
    ): MeasuredOutlineConstructionRun =
        delegate.measureOutlineConstructionGlyph(glyphId, fontSizePx, style, sourceRange)
}

private inline fun withSideScriptFaces(block: (String, SkiaMathFontFace) -> Unit) {
    listOf(
        "Lete Sans Math" to LeteSansMath.load(),
        "STIX Two Math" to StixTwoMath.load(),
    ).forEach { (label, font) -> SkiaMathFontFace(font).use { block(label, it) } }
}

private fun org.tiqian.math.core.MathLayoutResult.scriptDecision(style: MathStyle): MathLayoutDecision =
    decisions.first {
        it.name == "OpenTypeMathScriptPlacement" && it.details["style"] == style.toString()
    }

private fun org.tiqian.math.core.MathLayoutResult.radicalConstructionKinds(): List<String?> =
    decisions.filter { it.name == "OpenTypeRadicalConstruction" }.map { it.details["construction"] }

private fun MathLayoutDecision.floatOrNull(key: String): Float? =
    details[key]?.takeUnless { it == "null" }?.toFloat()

private fun MathLayoutDecision.float(key: String): Float = details.getValue(key).toFloat()

private fun assertNear(expected: Float?, actual: Float?, message: String) {
    if (expected == null || actual == null) {
        assertEquals(expected, actual, message)
    } else {
        assertTrue(abs(expected - actual) <= 0.04f, "$message: expected $expected, got $actual")
    }
}
