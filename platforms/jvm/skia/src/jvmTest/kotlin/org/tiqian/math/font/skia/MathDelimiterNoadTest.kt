package org.tiqian.math.font.skia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathConstructionPaintKind
import org.tiqian.math.core.MathMode
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathFontFace

class MathDelimiterNoadTest {
    @Test
    fun contentTargetAxisPackingAndPaintOwnershipAreSharedAcrossAllDelimiters() {
        delimiterFaces().forEach { (label, face) ->
            face.use {
                val engine = MathLayoutEngine(face)
                val result = engine.layout(MIDDLE, tectonicOptions())
                val group = result.decisions.single { it.name == "TeXContentDrivenDelimitedGroup" }
                val delimiters = result.decisions.filter { it.name == "TeXContentDrivenDelimiter" }
                assertEquals(3, delimiters.size, label)
                assertEquals(1, delimiters.map { it.details.getValue("targetPx") }.distinct().size, label)
                assertEquals(group.details.getValue("targetPx"), delimiters.first().details.getValue("targetPx"))
                assertTrue(delimiters.all { it.details["assemblyPolicy"] == "TectonicXeTeXStretchGlue" }, label)
                assertTrue(result.box.constructionPaintGroups.all {
                    it.kind == MathConstructionPaintKind.Delimiter
                }, label)
                assertFalse(result.diagnostics.any {
                    it.code == DiagnosticCode.MissingGlyph ||
                        it.code == DiagnosticCode.MissingConstructionOutlineEvidence
                }, "$label: ${result.diagnostics}")

                val base = engine.layout("\\left(x\\right)", tectonicOptions())
                assertTrue(base.delimiterDecisions().all { it.details["construction"] == "BaseGlyph" }, label)
                val variant = engine.layout("\\left(\\frac{a}{b}\\right)", tectonicOptions())
                assertTrue(variant.delimiterDecisions().all {
                    it.details["construction"] == "Variant" || it.details["construction"] == "Assembly"
                }, label)
            }
        }
    }

    @Test
    fun unavailableConstructionDiagnosesAndKeepsTheNormalGlyphVisible() {
        SkiaMathFontFace(LeteSansMath.load()).use { realFace ->
            val face = object : MathFontFace by realFace {
                override val mathFont = realFace.mathFont.copy(verticalConstructions = emptyMap())
            }
            var deep = "x"
            repeat(8) { deep = "\\frac{$deep}{y}" }
            val result = MathLayoutEngine(face).layout("\\left($deep\\right)", tectonicOptions())

            assertEquals(2, result.diagnostics.count { it.code == DiagnosticCode.MissingMathConstruction })
            val delimiters = result.delimiterDecisions()
            assertTrue(delimiters.all { it.details["construction"] == "NormalGlyphFallback" })
            assertTrue(delimiters.all { it.details["fallback"] == "true" })
            assertEquals(listOf(9u.toUShort(), 10u.toUShort()), result.box.glyphs.filter {
                it.constructionGroupId != null
            }.map { it.glyphId })
        }
    }

    @Test
    fun invisibleDelimiterHasZeroAdvanceAndScriptsUseTheCompletedInnerBox() {
        delimiterFaces().forEach { (label, face) ->
            face.use {
                val engine = MathLayoutEngine(face)
                val invisible = engine.layout("\\left.\\frac{a}{b}\\right|", tectonicOptions())
                val decisions = invisible.decisions.filter { it.name == "TeXContentDrivenDelimiter" }
                val left = decisions.single { it.details["side"] == "Left" }
                assertEquals("0.0", left.details["logicalAdvancePx"], label)
                assertEquals("SupportedInvisibleDelimiter", left.details["capability"], label)
                assertEquals(1, invisible.box.constructionPaintGroups.size, label)

                val scripted = engine.layout("\\left(\\frac{a}{b}\\right)_0^1", tectonicOptions())
                assertTrue(scripted.decisions.any { it.name == "OpenTypeMathScriptPlacement" }, label)
                assertTrue(scripted.box.width > invisible.box.width, label)
            }
        }
    }

    @Test
    fun nestedTallVocabularyAndOutsideSpacingRemainRealLayout() {
        delimiterFaces().forEach { (label, face) ->
            face.use {
                val engine = MathLayoutEngine(face)
                var deep = "x"
                repeat(12) { deep = "\\frac{$deep}{y}" }
                val tall = engine.layout("\\left($deep\\right)", tectonicOptions(MathMode.Display))
                assertEquals(1, tall.decisions.count { it.name == "TeXContentDrivenDelimitedGroup" }, label)
                assertTrue(tall.decisions.filter { it.name == "TeXContentDrivenDelimiter" }.any {
                    it.details["construction"] == "Assembly"
                }, "$label did not exercise a delimiter assembly")
                assertTrue(tall.box.glyphs.mapNotNull { it.constructionGroupId }.isNotEmpty(), label)
                val nested = engine.layout(
                    "\\left(\\frac{a}{\\left[\\frac{b}{c}\\right]}\\right)",
                    tectonicOptions(),
                )
                assertEquals(2, nested.decisions.count { it.name == "TeXContentDrivenDelimitedGroup" }, label)

                val vocabulary = listOf(
                    "\\left\\{\\frac{a}{b}\\right\\}",
                    "\\left\\|\\frac{a}{b}\\right\\Vert",
                    "\\left\\langle\\frac{a}{b}\\right\\rangle",
                    "\\left\\lfloor\\frac{a}{b}\\right\\rfloor",
                    "\\left\\lceil\\frac{a}{b}\\right\\rceil",
                    "\\left/\\frac{a}{b}\\right\\backslash",
                    "\\left\\uparrow\\frac{a}{b}\\right\\Downarrow",
                )
                vocabulary.forEach { source ->
                    val result = engine.layout(source, tectonicOptions())
                    assertEquals(2, result.decisions.count { it.name == "TeXContentDrivenDelimiter" }, "$label/$source")
                    assertFalse(result.diagnostics.any { it.code == DiagnosticCode.MissingGlyph }, "$label/$source")
                }

                val spaced = engine.layout("a\\left(b\\right)c", tectonicOptions())
                val groupRange = spaced.decisions.single { it.name == "TeXContentDrivenDelimitedGroup" }.range
                assertEquals(3, spaced.fragments.size, label)
                assertEquals(groupRange, spaced.fragments[1].sourceRange)
                assertTrue(spaced.fragments[0].trailingGlue.naturalPx > 0f, label)
                assertTrue(spaced.fragments[1].trailingGlue.naturalPx > 0f, label)
                assertTrue(spaced.breakOpportunities.isEmpty(), label)

                val outsideBreaks = engine.layout("a+\\left(b+c\\right)=d", tectonicOptions())
                val innerPlusOffset = "a+\\left(b+c\\right)=d".indexOf("b+") + 1
                assertTrue(outsideBreaks.breakOpportunities.isNotEmpty(), label)
                assertTrue(
                    outsideBreaks.breakOpportunities.none { it.sourceOffset == innerPlusOffset + 1 },
                    "$label exported a break from inside the fenced box",
                )

                val factorSmall = engine.layout(
                    "\\left(\\frac{a}{b}\\right)",
                    tectonicOptions().copy(delimiterFactor = 500, delimiterShortfallPx = 100f),
                )
                val factorLarge = engine.layout(
                    "\\left(\\frac{a}{b}\\right)",
                    tectonicOptions().copy(delimiterFactor = 1500, delimiterShortfallPx = 100f),
                )
                val smallTarget = factorSmall.decisions.single {
                    it.name == "TeXContentDrivenDelimitedGroup"
                }.details.getValue("targetPx").toFloat()
                val largeTarget = factorLarge.decisions.single {
                    it.name == "TeXContentDrivenDelimitedGroup"
                }.details.getValue("targetPx").toFloat()
                assertTrue(largeTarget > smallTarget, "$label delimiterFactor was not consumed")
            }
        }
    }

    private fun delimiterFaces() = listOf(
        "Lete Sans Math" to SkiaMathFontFace(LeteSansMath.load()),
        "STIX Two Math" to SkiaMathFontFace(StixTwoMath.load()),
    )

    private fun tectonicOptions(mode: MathMode = MathMode.Inline) = MathLayoutOptions(
        mode = mode,
        fontSizePx = FONT_SIZE_PX,
        nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
        scriptSpacePx = TECTONIC_SCRIPT_SPACE_PX,
        delimiterFactor = 901,
        delimiterShortfallPx = TECTONIC_DELIMITER_SHORTFALL_PX,
    )

    private companion object {
        const val FONT_SIZE_PX = 32f
        const val TECTONIC_NULL_DELIMITER_SPACE_PX = 1.2f * 96f / 72.27f
        const val TECTONIC_DELIMITER_SHORTFALL_PX = 5f * 96f / 72.27f
        const val TECTONIC_SCRIPT_SPACE_PX = 0.5f * 96f / 72.27f
        const val MIDDLE = "\\left\\langle a\\middle|\\frac{b}{c}\\right\\rangle"
    }
}

private fun org.tiqian.math.core.MathLayoutResult.delimiterDecisions() =
    decisions.filter { it.name == "TeXContentDrivenDelimiter" }
