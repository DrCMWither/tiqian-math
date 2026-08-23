package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathLayoutDecision
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.MathConstructionKind
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/**
 * Hard oracle from Tectonic 0.17.0/XeTeX `showbox` at 24bp (32 CSS px), loading the exact
 * repository STIX OTF. The reproducer is `preview/tectonic/stix-xetex-capability-oracle.tex`.
 */
class TectonicStixCapabilityOracleTest {
    @Test
    fun exhaustedDisplayOperatorLadderIsACompleteXeTeXSelection() {
        SkiaMathFontFace(StixTwoMath.load()).use { face ->
            listOf(
                OperatorOracle("\\sum_0^1", 1647, StixBoxPt(26.78807f, 44.26245f, 29.71465f)),
                OperatorOracle("\\prod_0^1", 1641, StixBoxPt(34.13553f, 44.32268f, 29.77487f)),
            ).forEach { oracle ->
                val result = MathLayoutEngine(face).layout(
                    oracle.source,
                    MathLayoutOptions(MathMode.Display, FONT_SIZE_PX),
                )
                val operatorRange = SourceRange(0, oracle.source.indexOf('_'))
                val operatorGlyph = result.box.glyphs.single {
                    it.sourceRange == operatorRange && it.glyphId == oracle.operatorGlyphId.toUShort()
                }
                val decision = result.decisions.single { it.name == "TeXOperatorNoad" }

                assertTrue(result.diagnostics.isEmpty(), "${oracle.source}: ${result.diagnostics}")
                assertNear(oracle.box.widthPt.px(), result.box.width, "${oracle.source} width")
                assertNear(oracle.box.ascentPt.px(), result.box.ascent, "${oracle.source} height")
                assertNear(oracle.box.descentPt.px(), result.box.descent, "${oracle.source} depth")
                assertEquals(MathConstructionKind.Variant.toString(), decision.details["construction"])
                assertEquals("XeTeXMakeOpLargestAvailableBelowSuggestedTarget", decision.details["constructionPolicy"])
                assertEquals("true", decision.details["selectionComplete"])
                assertEquals("true", decision.details["exhaustedVariantLadder"])
                assertEquals("false", decision.details["suggestedTargetReached"])
                assertNear(
                    maxOf(decision.float("displayOperatorMinHeightPx"), decision.float("normalGlyphFiveQuartersPx")),
                    decision.float("variantSelectionTargetPx"),
                    "${oracle.source} XeTeX target",
                )
                assertEquals(oracle.operatorGlyphId.toUShort(), operatorGlyph.glyphId)
                assertFalse(result.diagnostics.any { it.code == DiagnosticCode.MathVariantTooShort })
            }
        }
    }

    @Test
    fun zeroPpemDevicePolicyAndAccentGeometryMatchXeTeX() {
        SkiaMathFontFace(StixTwoMath.load()).use { face ->
            val font = face.mathFont
            assertEquals(setOf(3309, 3316, 3326).map(Int::toUShort).toSet(), font.topAccentAttachmentDeviceAdjustments.keys)
            assertEquals(setOf(4010.toUShort()), font.italicCorrectionDeviceAdjustments.keys)
            assertTrue(font.unsupportedTopAccentAttachmentVariationAdjustments.isEmpty())
            assertTrue(font.unsupportedItalicCorrectionVariationAdjustments.isEmpty())

            listOf(
                AccentOracle("\\hat{a}", 732, StixBoxPt(13.36995f, 16.23666f, .28908f)),
                AccentOracle("\\bar{a}", 744, StixBoxPt(13.36995f, 14.69490f, .28908f)),
            ).forEach { oracle ->
                val result = MathLayoutEngine(face).layout(
                    oracle.source,
                    MathLayoutOptions(MathMode.Inline, FONT_SIZE_PX),
                )
                val accent = result.decisions.single { it.name == "OpenTypeMathAccent" }
                val baseGlyph = result.box.glyphs.single { it.sourceRange == SourceRange(5, 6) }
                val accentGlyph = result.box.glyphs.single { it.sourceRange == SourceRange(0, 4) }

                assertTrue(result.diagnostics.isEmpty(), "${oracle.source}: ${result.diagnostics}")
                assertEquals(3326.toUShort(), baseGlyph.glyphId, "${oracle.source} semantic italic a")
                assertEquals(oracle.accentGlyphId.toUShort(), accentGlyph.glyphId, "${oracle.source} accent glyph")
                assertNear(13.73128f.px(), accentGlyph.x, "${oracle.source} accent x")
                assertNear(0f, accentGlyph.baselineY, "${oracle.source} accent baseline")
                assertNear(oracle.box.widthPt.px(), result.box.width, "${oracle.source} width")
                assertNear(oracle.box.ascentPt.px(), result.box.ascent, "${oracle.source} height")
                assertNear(oracle.box.descentPt.px(), result.box.descent, "${oracle.source} depth")
                assertEquals("XeTeXHarfBuzzZeroPpemMathTopAccentAttachment", accent.details["baseAttachmentPolicy"])
                assertTrue(accent.details.getValue("baseAttachmentIgnoredDeviceAdjustment").contains("startPpem=9"))
                assertEquals("XeTeXMakeMathAccentMinCleanBoxHeightAndAccentBaseHeight", accent.details["verticalPlacementPolicy"])
                assertFalse(result.diagnostics.any { it.code == DiagnosticCode.UnsupportedMathDeviceAdjustment })
            }
        }
    }

    private fun Float.px(): Float = this * TEX_PT_TO_PX

    private fun assertNear(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) <= EPSILON_PX, "$message: expected $expected, got $actual")
    }

    private fun MathLayoutDecision.float(key: String): Float = details.getValue(key).toFloat()

    private companion object {
        const val FONT_SIZE_PX = 32f
        const val TEX_PT_TO_PX = 96f / 72.27f
        const val EPSILON_PX = .03f
    }
}

private data class OperatorOracle(val source: String, val operatorGlyphId: Int, val box: StixBoxPt)
private data class AccentOracle(val source: String, val accentGlyphId: Int, val box: StixBoxPt)
private data class StixBoxPt(val widthPt: Float, val ascentPt: Float, val descentPt: Float)
