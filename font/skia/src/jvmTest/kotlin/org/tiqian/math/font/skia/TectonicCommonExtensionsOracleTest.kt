package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathStyle
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/**
 * Reviewed Tectonic 0.17.0/XeTeX showbox oracle at 24bp with the repository OTFs.
 * Reproducers: `preview/tectonic/common-extension-oracle-{lete,stix}.tex`.
 */
class TectonicCommonExtensionsOracleTest {
    @Test
    fun displayAndContinuedFractionsMatchAmsmathXeTeXBoxes() = withOracles { oracle, engine ->
        oracle.fractions.forEach { expected ->
            val result = engine.layout(expected.source, options())
            assertBox(expected, result.box.width, result.box.ascent, result.box.descent, oracle.label)
            val command = result.decisions.single { it.name == "TeXFractionCommand" }
            assertEquals(expected.origin, command.details["origin"], "${oracle.label}/${expected.source}")
            assertEquals("Display", command.details["fractionStyle"], "${oracle.label}/${expected.source}")
            assertTrue(result.diagnostics.isEmpty(), "${oracle.label}/${expected.source}: ${result.diagnostics}")
        }

        val left = engine.layout("\\cfrac[l]{a}{bbbb}", options())
        val right = engine.layout("\\cfrac[r]{a}{bbbb}", options())
        val leftStack = left.decisions.single { it.name == "OpenTypeMathFractionStack" }
        val rightStack = right.decisions.single { it.name == "OpenTypeMathFractionStack" }
        assertNear(0f, leftStack.float("numeratorX"), "${oracle.label} cfrac left")
        assertTrue(rightStack.float("numeratorX") > 0f, "${oracle.label} cfrac right")
        assertNear(left.box.width, right.box.width, "${oracle.label} aligned cfrac width")
    }

    @Test
    fun primitiveMathopUsesSharedXeTeXLimitsAndSideScriptGeometry() = withOracles { oracle, engine ->
        oracle.operators.forEach { expected ->
            val result = engine.layout(expected.source, options())
            assertBox(expected, result.box.width, result.box.ascent, result.box.descent, oracle.label)
            assertTrue(result.decisions.any { it.name == "TeXMathOperatorNoad" }, oracle.label)
            assertEquals(
                expected.stacked,
                result.decisions.any { it.name == "OpenTypeMathOperatorLimits" },
                "${oracle.label}/${expected.source}",
            )
            assertTrue(result.diagnostics.isEmpty(), "${oracle.label}/${expected.source}: ${result.diagnostics}")
        }
    }

    @Test
    fun growingTopAndBottomBraceNoadsMatchXeTeXBoxesAndConstructionTiers() = withOracles { oracle, engine ->
        oracle.braces.forEach { expected ->
            val result = engine.layout(expected.source, options())
            assertBox(expected, result.box.width, result.box.ascent, result.box.descent, oracle.label)
            val accent = result.decisions.single { it.name == "OpenTypeMathAccent" }
            assertEquals(expected.placement, accent.details["placement"], "${oracle.label}/${expected.source}")
            assertEquals(expected.construction, accent.details["construction"], "${oracle.label}/${expected.source}")
            assertEquals(expected.glyphIds, accent.details["glyphIds"], "${oracle.label}/${expected.source} glyph ids")
            assertTrue(result.decisions.any { it.name == "TeXBraceOperatorNoad" }, oracle.label)
            assertTrue(result.decisions.any { it.name == "OpenTypeMathOperatorLimits" }, oracle.label)
            val commandEnd = expected.source.indexOf('{')
            assertTrue(commandEnd > 0, expected.source)
            assertTrue(
                result.box.glyphs.filter { it.sourceRange.start == 0 && it.sourceRange.endExclusive == commandEnd }.isNotEmpty(),
                "${oracle.label}/${expected.source} brace glyph source ownership",
            )
            assertTrue(result.diagnostics.isEmpty(), "${oracle.label}/${expected.source}: ${result.diagnostics}")
        }
        val spacing = engine.layout(oracle.braceSpacing.source, options())
        assertBox(oracle.braceSpacing, spacing.box.width, spacing.box.ascent, spacing.box.descent, oracle.label)
        assertEquals(
            2,
            spacing.decisions.count { it.name == "TeXMathAtomSpacing" && it.details["kind"] == "Thin" },
            "${oracle.label} Ord-Op-Ord spacing",
        )
    }

    @Test
    fun allEightStylesPreserveTheirOwnBraceAndMathopStateWhileCfracOverridesLocally() = withOracles { oracle, engine ->
        MathStyle.entries.forEach { style ->
            val brace = engine.layout("\\underbrace{x}_{n}", options().copy(initialStyle = style))
            val accent = brace.decisions.single { it.name == "OpenTypeMathAccent" }
            assertEquals(style.name, accent.details["style"], "${oracle.label}/$style brace")
            assertEquals("Bottom", accent.details["placement"], "${oracle.label}/$style brace")

            val operator = engine.layout("\\mathop{x}_0^1", options().copy(initialStyle = style))
            assertEquals(
                style.level.name == "Display",
                operator.decisions.any { it.name == "OpenTypeMathOperatorLimits" },
                "${oracle.label}/$style mathop",
            )

            val cfrac = engine.layout("\\cfrac{a}{b}", options().copy(initialStyle = style))
            val fraction = cfrac.decisions.single { it.name == "TeXFractionCommand" }
            assertEquals(style.name, fraction.details["outerStyle"], "${oracle.label}/$style cfrac outer")
            assertEquals("Display", fraction.details["fractionStyle"], "${oracle.label}/$style cfrac inner")
            assertTrue(brace.diagnostics.isEmpty(), "${oracle.label}/$style brace: ${brace.diagnostics}")
            assertTrue(operator.diagnostics.isEmpty(), "${oracle.label}/$style mathop: ${operator.diagnostics}")
            assertTrue(cfrac.diagnostics.isEmpty(), "${oracle.label}/$style cfrac: ${cfrac.diagnostics}")
        }
    }

    private fun options() = MathLayoutOptions(
        mode = MathMode.Inline,
        initialStyle = MathStyle.Text,
        fontSizePx = 32f,
        nullDelimiterSpacePx = 1.2f.px(),
        scriptSpacePx = 0.5f.px(),
        delimiterShortfallPx = 5f.px(),
    )

    private fun withOracles(block: (Oracle, MathLayoutEngine) -> Unit) {
        oracles().forEach { oracle ->
            oracle.face.use { face -> block(oracle, MathLayoutEngine(face)) }
        }
    }

    private fun oracles() = listOf(
        Oracle(
            "Lete Sans Math",
            SkiaMathFontFace(LeteSansMath.load()),
            fractions = listOf(
                ExpectedBox("\\dfrac{a}{b}", 16.13129f, 26.35594f, 17.03342f, "DisplayFraction"),
                ExpectedBox("\\cfrac{a}{b}", 14.93129f, 36.44878f, 17.03342f, "ContinuedFraction"),
                ExpectedBox("\\cfrac[l]{a}{bbbb}", 56.12517f, 36.44878f, 17.03342f, "ContinuedFraction"),
                ExpectedBox("\\cfrac[r]{a}{bbbb}", 56.12517f, 36.44878f, 17.03342f, "ContinuedFraction"),
            ),
            operators = listOf(
                OperatorBox("{\\displaystyle\\mathop{abc}_0^1}", 39.21852f, 38.30222f, 20.97667f, true),
                OperatorBox("\\mathop{abc}_0^1", 49.49907f, 22.85585f, 6.15805f, false),
                OperatorBox("{\\displaystyle\\mathop{abc}\\nolimits_0^1}", 49.49907f, 22.85585f, 6.15805f, false),
                OperatorBox("\\mathop{abc}\\limits_0^1", 39.21852f, 38.30222f, 20.97667f, true),
            ),
            braces = listOf(
                BraceBox("\\overbrace{a+b}^{n}", 54.35884f, 42.83594f, 0.33727f, "Top", "Variant", "2294"),
                BraceBox("\\underbrace{a+b}_{n}", 54.35884f, 17.73024f, 28.47232f, "Bottom", "Variant", "2305"),
                BraceBox(
                    "\\overbrace{a+b+c+d+e}^{n}", 175.13335f, 47.41304f, 0.33727f, "Top", "Assembly",
                    "2464,2465,2465,2465,2465,2466,2465,2465,2465,2465,2467",
                ),
                BraceBox(
                    "\\underbrace{a+b+c+d+e}_{n}", 175.13335f, 17.73024f, 32.1581f, "Bottom", "Assembly",
                    "2468,2469,2469,2469,2469,2470,2469,2469,2469,2469,2471",
                ),
            ),
            braceSpacing = ExpectedBox("a\\underbrace{b}c", 47.24934f, 17.73024f, 9.27464f, "BraceSpacing"),
        ),
        Oracle(
            "STIX Two Math",
            SkiaMathFontFace(StixTwoMath.load()),
            fractions = listOf(
                ExpectedBox("\\dfrac{a}{b}", 15.76994f, 26.95834f, 15.70831f, "DisplayFraction"),
                ExpectedBox("\\cfrac{a}{b}", 14.56995f, 34.73833f, 15.70831f, "ContinuedFraction"),
                ExpectedBox("\\cfrac[l]{a}{bbbb}", 51.5963f, 34.73833f, 15.70831f, "ContinuedFraction"),
                ExpectedBox("\\cfrac[r]{a}{bbbb}", 51.5963f, 34.73833f, 15.70831f, "ContinuedFraction"),
            ),
            operators = listOf(
                OperatorBox("{\\displaystyle\\mathop{abc}_0^1}", 35.94228f, 39.0704f, 20.25998f, true),
                OperatorBox("\\mathop{abc}_0^1", 46.22282f, 24.35216f, 5.27866f, false),
                OperatorBox("{\\displaystyle\\mathop{abc}\\nolimits_0^1}", 46.22282f, 24.35216f, 5.27866f, false),
                OperatorBox("\\mathop{abc}\\limits_0^1", 35.94228f, 39.0704f, 20.25998f, true),
            ),
            braces = listOf(
                BraceBox("\\overbrace{a+b}^{n}", 54.02159f, 45.0929f, 0.9636f, "Top", "Variant", "2112"),
                BraceBox("\\underbrace{a+b}_{n}", 54.02159f, 16.98344f, 28.1109f, "Bottom", "Variant", "2121"),
                BraceBox(
                    "\\overbrace{a+b+c+d+e}^{n}", 172.24254f, 48.34505f, 0.9636f, "Top", "Assembly",
                    "2106,2073,2073,2073,2073,2107,2073,2073,2073,2073,2108",
                ),
                BraceBox(
                    "\\underbrace{a+b+c+d+e}_{n}", 172.24254f, 16.98344f, 31.48349f, "Bottom", "Assembly",
                    "2115,2082,2082,2082,2082,2116,2082,2082,2082,2082,2117",
                ),
            ),
            braceSpacing = ExpectedBox("a\\underbrace{b}c", 43.9731f, 16.98344f, 7.51608f, "BraceSpacing"),
        ),
    )

    private data class Oracle(
        val label: String,
        val face: SkiaMathFontFace,
        val fractions: List<ExpectedBox>,
        val operators: List<OperatorBox>,
        val braces: List<BraceBox>,
        val braceSpacing: ExpectedBox,
    )

    private open class ExpectedBox(
        val source: String,
        val widthPt: Float,
        val ascentPt: Float,
        val descentPt: Float,
        val origin: String,
    )

    private class OperatorBox(
        source: String,
        widthPt: Float,
        ascentPt: Float,
        descentPt: Float,
        val stacked: Boolean,
    ) : ExpectedBox(source, widthPt, ascentPt, descentPt, "MathOperatorNoad")

    private class BraceBox(
        source: String,
        widthPt: Float,
        ascentPt: Float,
        descentPt: Float,
        val placement: String,
        val construction: String,
        val glyphIds: String,
    ) : ExpectedBox(source, widthPt, ascentPt, descentPt, "Brace")

    private fun assertBox(expected: ExpectedBox, width: Float, ascent: Float, descent: Float, label: String) {
        assertNear(expected.widthPt.px(), width, "$label/${expected.source} width")
        assertNear(expected.ascentPt.px(), ascent, "$label/${expected.source} height")
        assertNear(expected.descentPt.px(), descent, "$label/${expected.source} depth")
    }
}

private fun Float.px(): Float = this * 96f / 72.27f

private fun org.tiqian.math.core.MathLayoutDecision.float(key: String): Float =
    details.getValue(key).toFloat()

private fun assertNear(expected: Float, actual: Float, message: String) {
    assertTrue(abs(expected - actual) <= 0.09f, "$message: expected $expected, got $actual")
}
