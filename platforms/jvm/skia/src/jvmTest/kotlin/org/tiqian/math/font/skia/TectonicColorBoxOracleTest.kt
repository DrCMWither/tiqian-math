package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathPaintColor
import org.tiqian.math.core.MathStyle
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/**
 * Reviewed Tectonic 0.17.0/XeTeX showbox oracle at 24bp with the repository OTFs.
 * Reproducers: `preview/tectonic/color-box-oracle-{lete,stix}.tex`.
 */
class TectonicColorBoxOracleTest {
    @Test
    fun xcolorDeclarationsPreserveGeometryAndOwnNestedPaintState() = withOracles { oracle, engine ->
        val plain = engine.layout("{{a+{b}+c}+d}", options())
        val colored = engine.layout("{{\\color{red}a+{\\color{blue}b}+c}+d}", options())

        assertNear(oracle.colorScope.widthPt.px(), colored.box.width, "${oracle.label} color scope width")
        assertNear(plain.box.width, colored.box.width, "${oracle.label} color-neutral width")
        assertNear(plain.box.ascent, colored.box.ascent, "${oracle.label} color-neutral ascent")
        assertNear(plain.box.descent, colored.box.descent, "${oracle.label} color-neutral descent")
        val red = MathPaintColor(255, 0, 0)
        val blue = MathPaintColor(0, 0, 255)
        val byText = colored.box.glyphs.groupBy { colored.source.substring(it.sourceRange.start, it.sourceRange.endExclusive) }
        assertEquals(red, byText.getValue("a").single().paintColor)
        assertEquals(blue, byText.getValue("b").single().paintColor)
        assertEquals(red, byText.getValue("c").single().paintColor)
        assertEquals(null, byText.getValue("d").single().paintColor)
        assertEquals(2, colored.decisions.count { it.name == "XColorMathDeclaration" })
        assertTrue(colored.diagnostics.isEmpty(), "${oracle.label}: ${colored.diagnostics}")
    }

    @Test
    fun explicitColorOwnsRadicalGlyphsRulesAndConstructionReplay() = withOracles { oracle, engine ->
        val result = engine.layout("{\\color{royalblue}\\sqrt{\\frac{a}{b}}}", options())
        val royalBlue = MathPaintColor(0, 128, 255)

        assertBox(oracle.colorConstruction, result, oracle.label)
        assertTrue(result.box.glyphs.isNotEmpty(), oracle.label)
        assertTrue(result.box.rules.isNotEmpty(), oracle.label)
        assertTrue(result.box.constructionPaintGroups.isNotEmpty(), oracle.label)
        assertTrue(result.box.glyphs.all { it.paintColor == royalBlue }, "${oracle.label} glyph paint ownership")
        assertTrue(result.box.rules.all { it.paintColor == royalBlue }, "${oracle.label} rule paint ownership")
        assertTrue(
            result.box.constructionPaintGroups.all { it.paintColor == royalBlue },
            "${oracle.label} construction paint ownership",
        )
        assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
    }

    @Test
    fun xetexColorStateCoversTheFollowingMiddleOrRightDelimiterThenResets() = withOracles { oracle, engine ->
        val red = MathPaintColor(255, 0, 0)
        val blue = MathPaintColor(0, 0, 255)
        val beforeMiddle = engine.layout("\\left(\\color{red}a\\middle|b\\right)", options())
        val beforeRight = engine.layout("\\left(a\\middle|\\color{blue}b\\right)", options())

        assertNear(oracle.colorDelimiterWidthPt.px(), beforeMiddle.box.width, "${oracle.label} color middle width")
        assertNear(oracle.colorDelimiterWidthPt.px(), beforeRight.box.width, "${oracle.label} color right width")
        assertEquals(listOf(null, red, red, null, null), beforeMiddle.box.glyphs.map { it.paintColor })
        assertEquals(listOf(null, null, null, blue, blue), beforeRight.box.glyphs.map { it.paintColor })
        assertEquals(
            "XeTeXColorStateCoversFollowingMiddleOrRightDelimiterOnly",
            beforeMiddle.decisions.single { it.name == "TeXContentDrivenDelimitedGroup" }
                .details["delimiterPaintStatePolicy"],
        )
        assertTrue(beforeMiddle.diagnostics.isEmpty(), "${oracle.label}: ${beforeMiddle.diagnostics}")
        assertTrue(beforeRight.diagnostics.isEmpty(), "${oracle.label}: ${beforeRight.diagnostics}")
    }

    @Test
    fun amsmathBoxedUsesDisplayContentAndExactFboxGeometry() = withOracles { oracle, engine ->
        listOf(
            oracle.boxedX,
            oracle.boxedFraction,
            oracle.boxedSpacing,
            oracle.colorBoxed,
        ).forEach { expected ->
            val result = engine.layout(expected.source, options())
            assertBox(expected, result, oracle.label)
            val boxed = result.decisions.single { it.name == "AmsmathBoxedNoad" }
            assertEquals("Display", boxed.details["contentStyle"], "${oracle.label}/${expected.source}")
            assertEquals("Ordinary", boxed.details["atomClass"], "${oracle.label}/${expected.source}")
            assertTrue(result.diagnostics.isEmpty(), "${oracle.label}/${expected.source}: ${result.diagnostics}")
        }

        val colored = engine.layout(oracle.colorBoxed.source, options())
        val red = MathPaintColor(255, 0, 0)
        assertTrue(colored.box.glyphs.all { it.paintColor == red }, oracle.label)
        assertTrue(colored.box.rules.all { it.paintColor == red }, oracle.label)
        assertEquals(4, colored.box.rules.count { it.sourceRange.length == "\\boxed".length })
    }

    @Test
    fun allEightOuterStylesKeepBoxedDisplayContentAndScopedColor() = withOracles { oracle, engine ->
        MathStyle.entries.forEach { style ->
            val source = "{\\color{violet}\\boxed{x}_0^1}+y"
            val result = engine.layout(
                source,
                options().copy(initialStyle = style),
            )
            val boxed = result.decisions.single { it.name == "AmsmathBoxedNoad" }
            assertEquals(style.name, boxed.details["outerStyle"], "${oracle.label}/$style outer")
            assertEquals("Display", boxed.details["contentStyle"], "${oracle.label}/$style content")
            val violet = MathPaintColor(128, 0, 128)
            val coloredGroupEnd = source.lastIndexOf('}')
            assertTrue(result.box.glyphs.filter { it.sourceRange.start < coloredGroupEnd }.all { it.paintColor == violet })
            assertEquals(null, result.box.glyphs.last().paintColor, "${oracle.label}/$style group color reset")
            val scripts = result.decisions.single { it.name == "OpenTypeMathScriptPlacement" }
            assertEquals("CompoundBox", scripts.details["baseKind"], "${oracle.label}/$style boxed script base")
            assertTrue(result.diagnostics.isEmpty(), "${oracle.label}/$style: ${result.diagnostics}")
        }
    }

    private fun options() = MathLayoutOptions(
        mode = MathMode.Inline,
        initialStyle = MathStyle.Text,
        fontSizePx = 32f,
        nullDelimiterSpacePx = 1.2f.px(),
        scriptSpacePx = 0.5f.px(),
        delimiterShortfallPx = 5f.px(),
        fboxSeparationPx = 3f.px(),
        fboxRuleThicknessPx = 0.4f.px(),
    )

    private fun withOracles(block: (Oracle, MathLayoutEngine) -> Unit) {
        oracles().forEach { oracle ->
            oracle.face.use { face -> block(oracle, MathLayoutEngine(face)) }
        }
    }

    private fun oracles() = listOf(
        Oracle(
            label = "Lete Sans Math",
            face = SkiaMathFontFace(LeteSansMath.load()),
            colorScope = ExpectedBox("{{\\color{red}a+{\\color{blue}b}+c}+d}", 135.30077f, 17.73024f, 0.33727f),
            colorConstruction = ExpectedBox("{\\color{royalblue}\\sqrt{\\frac{a}{b}}}", 28.07994f, 25.53899f, 11.70415f),
            boxedX = ExpectedBox("\\boxed{x}", 20.55539f, 15.58954f, 3.4f),
            boxedFraction = ExpectedBox("\\boxed{\\frac{a}{b}}", 22.93127f, 29.75594f, 20.43341f),
            boxedSpacing = ExpectedBox("a\\boxed{b}c", 46.01851f, 21.13023f, 3.56862f),
            colorBoxed = ExpectedBox("{\\color{red}\\boxed{\\frac{a}{b}}}", 22.93127f, 29.75594f, 20.43341f),
            colorDelimiterWidthPt = 48.34863f,
        ),
        Oracle(
            label = "STIX Two Math",
            face = SkiaMathFontFace(StixTwoMath.load()),
            colorScope = ExpectedBox("{{\\color{red}a+{\\color{blue}b}+c}+d}", 133.97581f, 16.98344f, 0.9636f),
            colorConstruction = ExpectedBox("{\\color{royalblue}\\sqrt{\\frac{a}{b}}}", 37.74725f, 30.06192f, 16.23906f),
            boxedX = ExpectedBox("\\boxed{x}", 20.5072f, 14.9391f, 3.6409f),
            boxedFraction = ExpectedBox("\\boxed{\\frac{a}{b}}", 22.56993f, 30.35834f, 19.1083f),
            boxedSpacing = ExpectedBox("a\\boxed{b}c", 42.74226f, 20.38344f, 3.68907f),
            colorBoxed = ExpectedBox("{\\color{red}\\boxed{\\frac{a}{b}}}", 22.56993f, 30.35834f, 19.1083f),
            colorDelimiterWidthPt = 49.6013f,
        ),
    )

    private data class Oracle(
        val label: String,
        val face: SkiaMathFontFace,
        val colorScope: ExpectedBox,
        val colorConstruction: ExpectedBox,
        val boxedX: ExpectedBox,
        val boxedFraction: ExpectedBox,
        val boxedSpacing: ExpectedBox,
        val colorBoxed: ExpectedBox,
        val colorDelimiterWidthPt: Float,
    )

    private data class ExpectedBox(
        val source: String,
        val widthPt: Float,
        val ascentPt: Float,
        val descentPt: Float,
    )

    private fun assertBox(expected: ExpectedBox, result: org.tiqian.math.core.MathLayoutResult, label: String) {
        assertNear(expected.widthPt.px(), result.box.width, "$label/${expected.source} width")
        assertNear(expected.ascentPt.px(), result.box.ascent, "$label/${expected.source} height")
        assertNear(expected.descentPt.px(), result.box.descent, "$label/${expected.source} depth")
        assertNear(result.box.ascent, result.box.texCleanBoxMetrics.ascent, "$label/${expected.source} clean height")
        assertNear(result.box.descent, result.box.texCleanBoxMetrics.descent, "$label/${expected.source} clean depth")
    }
}

private fun Float.px(): Float = this * 96f / 72.27f

private fun assertNear(expected: Float, actual: Float, message: String) {
    assertTrue(abs(expected - actual) <= 0.25f, "$message expected=$expected actual=$actual")
}
