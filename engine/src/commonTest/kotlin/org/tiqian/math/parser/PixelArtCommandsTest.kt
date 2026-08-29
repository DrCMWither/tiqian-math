package org.tiqian.math.parser

import org.tiqian.math.core.MathBboxDimensionUnit
import org.tiqian.math.core.MathColorDeclaration
import org.tiqian.math.core.MathLap
import org.tiqian.math.core.MathLapKind
import org.tiqian.math.core.MathPaintColor
import org.tiqian.math.core.MathRuleBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Zhihu pixel-art trio: `\rlap`/`\llap` zero-width laps, `\rule` with MathJax px units, and
 * HTML hex triplets in `\color` (with `#` relaxed inside the color argument).
 */
class PixelArtCommandsTest {
    private fun parse(source: String) = MathParser().parse(source)

    @Test
    fun ruleParsesPxDimensionsAndOptionalRaise() {
        val parsed = parse("\\rule{4px}{320px}")
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val rule = assertIs<MathRuleBox>(parsed.root.children.single())
        assertEquals(4f, rule.width.value)
        assertEquals(MathBboxDimensionUnit.Pixel, rule.width.unit)
        assertEquals(320f, rule.height.value)
        assertEquals(null, rule.raise)

        val raised = parse("\\rule[-2pt]{1em}{0.4pt}")
        assertTrue(raised.diagnostics.isEmpty(), raised.diagnostics.toString())
        val raisedRule = assertIs<MathRuleBox>(raised.root.children.single())
        assertEquals(-2f, raisedRule.raise?.value)
        assertEquals(MathBboxDimensionUnit.Point, raisedRule.raise?.unit)
    }

    @Test
    fun ruleWithoutDimensionIsDiagnosedNotCrashed() {
        val parsed = parse("\\rule{4px}{banana}")
        assertTrue(parsed.diagnostics.any { it.code.name == "InvalidRuleDimension" })
    }

    @Test
    fun lapCommandsParseWithSidedKinds() {
        val parsed = parse("\\rlap{a}\\llap{b}")
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val laps = parsed.root.children.filterIsInstance<MathLap>()
        assertEquals(listOf(MathLapKind.Right, MathLapKind.Left), laps.map { it.kind })
    }

    @Test
    fun colorAcceptsHexTripletsWithoutParameterMarkerDiagnostics() {
        val short = parse("\\color{#BB9}{x}")
        assertTrue(short.diagnostics.isEmpty(), short.diagnostics.toString())
        val shortColor = short.root.children.filterIsInstance<MathColorDeclaration>().single()
        assertEquals(MathPaintColor(0xBB, 0xBB, 0x99), shortColor.color)

        val long = parse("\\color{#3355aa}{x}")
        assertTrue(long.diagnostics.isEmpty(), long.diagnostics.toString())
        val longColor = long.root.children.filterIsInstance<MathColorDeclaration>().single()
        assertEquals(MathPaintColor(0x33, 0x55, 0xAA), longColor.color)

        // The relaxation must not leak: a bare `#` in the following content group is still a
        // macro-parameter error.
        val leak = parse("\\color{red}{#}")
        assertTrue(
            leak.diagnostics.any { it.code.name == "InvalidParameterMarker" },
            leak.diagnostics.toString(),
        )
    }
}
