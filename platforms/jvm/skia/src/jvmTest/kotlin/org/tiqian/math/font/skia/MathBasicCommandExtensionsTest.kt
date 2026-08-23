package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/** Same-font XeTeX reproducers live in the two `preview/tectonic/basic-command-oracle*.tex` files. */
class MathBasicCommandExtensionsTest {
    @Test
    fun moduloWidthsAndSubstackBoxMatchSameFontTectonic() = withFaces { oracle, face ->
        val engine = MathLayoutEngine(face)
        listOf("a\\bmod b", "a\\mod b", "a\\pmod b").zip(oracle.moduloWidthsPt).forEach { (source, widthPt) ->
            val result = engine.layout(source, options())
            assertTrue(result.diagnostics.isEmpty(), "${oracle.label}/$source ${result.diagnostics}\n${result.debugDump}")
            // This command slice owns the TeX horizontal noad/kern contract. The surrounding
            // simple-symbol h/d intentionally remains the engine's existing host-safe line metric.
            assertNear(widthPt * PT_TO_PX, result.box.width, "${oracle.label}/$source width\n${result.debugDump}")
            val decision = result.decisions.single { it.name == "AmsmathModulo" }
            assertEquals("Operators", decision.details["nameFamily"])
            assertEquals("Roman", decision.details["nameAlphabet"])
            val commandStart = source.indexOf('\\')
            val commandEnd = commandStart + source.substring(commandStart).substringBefore(' ').length
            val commandRange = SourceRange(commandStart, commandEnd)
            assertTrue(
                result.box.glyphs.filter { it.sourceRange == commandRange }.size >= 3,
                "${oracle.label}/$source generated operator letters retain $commandRange\n${result.debugDump}",
            )
            assertTrue(result.box.hostTextRuns.isEmpty(), "${oracle.label}/$source must not require host text replay")
            assertEquals("1.7777778", decision.details["muPx"])
            when (source) {
                "a\\bmod b" -> assertEquals(listOf("1.0", "1.0"), listOf(decision.details["leadingMu"], decision.details["trailingMu"]))
                "a\\mod b" -> assertEquals(listOf("12.0", "6.0"), listOf(decision.details["leadingMu"], decision.details["trailingMu"]))
                else -> assertEquals(listOf("8.0", "0.0"), listOf(decision.details["leadingMu"], decision.details["trailingMu"]))
            }
        }
        val source = "\\sum_{\\substack{i=1\\\\j=2}}^n"
        val result = engine.layout(source, options())
        val expected = oracle.substack
        assertTrue(result.diagnostics.isEmpty(), "${oracle.label}/$source ${result.diagnostics}\n${result.debugDump}")
        if (!oracle.xeTeXSkipsSsty) {
            assertNear(expected.widthPt * PT_TO_PX, result.box.width, "${oracle.label}/$source width\n${result.debugDump}")
            assertNear(expected.ascentPt * PT_TO_PX, result.box.ascent, "${oracle.label}/$source ascent")
            assertNear(
                expected.descentPt * PT_TO_PX,
                result.box.descent,
                "${oracle.label}/$source descent\n${result.debugDump}",
            )
        } else {
            // XeTeX skips STIX's optional ssty substitutions in these array cells; the engine's
            // established OpenType style contract applies them. Keep the TeX table constants and
            // source/style assertions external while the shared ssty boundary remains explicit.
            assertTrue(result.box.glyphs.filter { it.sourceRange.start >= 16 }.all { it.style.level == MathStyleLevel.Script })
        }
        val substack = engine.layout("\\sum_{\\substack{i=1\\\\j=2}}^n", options())
        val table = substack.decisions.single { it.name == "TeXMathTable" }
        assertEquals("Substack", table.details["environment"])
        assertEquals("Script", table.details["cellStyle"])
        assertEquals(
            "fontdimen10-scriptfont-symbols/StackGapMin",
            table.details["substackRowGapParameter"],
        )
    }

    @Test
    fun newAccentsAndLargeOperatorsUseTheExistingMathKernelsForBothFonts() = withFaces { oracle, face ->
        val accents = MathLayoutEngine(face).layout(
            "\\acute{x}+\\grave{x}+\\breve{x}+\\check{x}+\\mathring{x}+" +
                "\\overrightarrow{AB}+\\underleftarrow{AB}",
            options(),
        )
        assertTrue(accents.diagnostics.isEmpty(), "${oracle.label} ${accents.diagnostics}\n${accents.debugDump}")
        val decisions = accents.decisions.filter { it.name == "OpenTypeMathAccent" }
        assertEquals(7, decisions.size)
        assertEquals(
            setOf("acute", "grave", "breve", "check", "ring", "overrightarrow", "underleftarrow"),
            decisions.map { it.details.getValue("identity") }.toSet(),
        )
        decisions.forEach { decision ->
            assertTrue(decision.details.getValue("glyphIds").isNotEmpty())
            assertNear(
                decision.details.getValue("baseAttachmentPx").toFloat(),
                decision.details.getValue("accentX").toFloat() +
                    decision.details.getValue("accentAttachmentPx").toFloat(),
                "${oracle.label}/${decision.details["identity"]} attachment",
            )
        }

        val source = "\\coprod_i^n+\\bigwedge_i^n+\\bigvee_i^n+\\bigodot_i^n+" +
            "\\bigoplus_i^n+\\biguplus_i^n+\\smallint_0^1"
        val operators = MathLayoutEngine(face).layout(source, options().copy(mode = MathMode.Display))
        assertTrue(operators.diagnostics.isEmpty(), "${oracle.label} ${operators.diagnostics}\n${operators.debugDump}")
        assertEquals(7, operators.decisions.count { it.name == "TeXOperatorNoad" })
        val small = operators.decisions.single {
            it.name == "TeXOperatorNoad" && it.details["identity"] == "small-integral"
        }
        assertEquals("false", small.details["growsInDisplayStyle"])
        assertEquals("0.0", small.details["variantSelectionTargetPx"])
    }

    @Test
    fun smallmatrixAndGatheredMatchSameFontTectonicBoxes() = withFaces { oracle, face ->
        val engine = MathLayoutEngine(face)
        listOf(
            "smallmatrix" to "\\begin{smallmatrix}a&b\\\\c&d\\end{smallmatrix}",
            "gathered" to "\\begin{gathered}a=b\\\\c=d\\end{gathered}",
        ).forEach { (label, source) ->
            val result = engine.layout(source, options())
            val expected = if (label == "smallmatrix") oracle.smallMatrix else oracle.gathered
            assertTrue(result.diagnostics.isEmpty(), "${oracle.label}/$source ${result.diagnostics}\n${result.debugDump}")
            if (label != "smallmatrix" || !oracle.xeTeXSkipsSsty) {
                assertNear(
                    expected.widthPt * PT_TO_PX,
                    result.box.width,
                    "${oracle.label}/$label width\n${result.debugDump}",
                    0.25f,
                )
                assertNear(expected.ascentPt * PT_TO_PX, result.box.ascent, "${oracle.label}/$label ascent", 0.25f)
                assertNear(expected.descentPt * PT_TO_PX, result.box.descent, "${oracle.label}/$label descent", 0.25f)
            }
            assertNear(result.box.ascent, result.box.texCleanBoxMetrics.ascent, "${oracle.label}/$label clean ascent")
            assertNear(result.box.descent, result.box.texCleanBoxMetrics.descent, "${oracle.label}/$label clean descent")
        }
        val small = engine.layout("\\begin{smallmatrix}a&b\\\\c&d\\end{smallmatrix}", options())
            .decisions.single { it.name == "TeXMathTable" }
        assertEquals("Script", small.details["cellStyle"])
        assertEquals("3.0", small.details["smallMatrixOuterPaddingMu"])
        assertEquals("5.0", small.details["smallMatrixInterColumnMu"])
        val gathered = engine.layout("\\begin{gathered}a=b\\\\c=d\\end{gathered}", options())
            .decisions.single { it.name == "TeXMathTable" }
        assertEquals("Display", gathered.details["cellStyle"])
        assertEquals("Center", gathered.details["columnAlignments"])

        val displayGather = engine.layout("\\begin{gather*}a=b\\\\c=d\\end{gather*}", options())
        assertTrue(displayGather.diagnostics.isEmpty(), "${oracle.label} ${displayGather.diagnostics}")
        assertNear(oracle.gathered.widthPt * PT_TO_PX, displayGather.box.width, "${oracle.label}/gather* width", 0.07f)
    }

    private fun withFaces(block: (Oracle, SkiaMathFontFace) -> Unit) {
        oracles.forEach { oracle ->
            oracle.face().use { face -> block(oracle, face) }
        }
    }

    private fun options() = MathLayoutOptions(fontSizePx = 32f)

    private fun assertNear(expected: Float, actual: Float, label: String, tolerance: Float = 0.25f) {
        assertTrue(abs(expected - actual) <= tolerance, "$label expected=$expected actual=$actual")
    }

    private data class Box(val widthPt: Float, val ascentPt: Float, val descentPt: Float)
    private data class Oracle(
        val label: String,
        val face: () -> SkiaMathFontFace,
        val moduloWidthsPt: List<Float>,
        val substack: Box,
        val smallMatrix: Box,
        val gathered: Box,
        val xeTeXSkipsSsty: Boolean,
    )

    private companion object {
        const val PT_TO_PX = 96f / 72.27f
        val oracles = listOf(
            Oracle(
                "Lete",
                { SkiaMathFontFace(LeteSansMath.load()) },
                listOf(87.48553f, 98.1933f, 108.01611f),
                Box(49.51578f, 20.5391f, 23.28691f),
                Box(34.23087f, 20.40005f, 6.90823f),
                Box(57.03578f, 33.70322f, 20.2114f),
                false,
            ),
            Oracle(
                "STIX",
                { SkiaMathFontFace(StixTwoMath.load()) },
                listOf(85.24518f, 95.95294f, 107.79932f),
                Box(51.14185f, 22.82066f, 19.2514f),
                Box(33.79243f, 19.4316f, 6.99983f),
                Box(56.69853f, 32.42639f, 19.99463f),
                true,
            ),
        )
    }
}
