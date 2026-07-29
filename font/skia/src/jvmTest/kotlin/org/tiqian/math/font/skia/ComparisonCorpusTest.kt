package org.tiqian.math.font.skia

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComparisonCorpusTest {
    @Test
    fun texKatexAndOpenTypeComparisonCorpusRunsThroughBothFaces() {
        val corpus = checkNotNull(javaClass.getResourceAsStream("/corpus/tex-katex-geometry.tsv"))
            .bufferedReader()
            .useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
                    val columns = line.split('\t')
                    require(columns.size == 4) { "invalid comparison corpus row: $line" }
                    CorpusCase(
                        columns[0],
                        columns[1],
                        ComparisonOracle.from(columns[2]),
                        ComparisonInvariant.from(columns[3]),
                    )
                }.toList()
            }
        assertEquals(
            setOf(
                "ordinary-sub-mlist",
                "tight-spacing",
                "cramped-superscript",
                "script-binomial",
                "math-kern",
                "assembly",
                "operator-auto",
                "operator-integral",
            ),
            corpus.map { it.id }.toSet(),
        )

        listOf(LeteSansMath.load(), StixTwoMath.load()).forEach { font ->
            SkiaMathFontFace(font).use { face ->
                val engine = MathLayoutEngine(face)
                corpus.forEach { case ->
                    val result = engine.layout(case.source, MathLayoutOptions(fontSizePx = 44f))
                    when (case.invariant) {
                        ComparisonInvariant.OrdinarySubMlistBoundary -> {
                            assertEquals(ComparisonOracle.TeXOrdinarySubMlistAndKaTeXOrdGroup, case.oracle)
                            assertEquals(3, result.fragments.size, case.toString())
                            assertTrue(result.breakOpportunities.isEmpty(), case.toString())
                            assertTrue(result.decisions.any { it.name == "TeXOrdSubMlist" }, case.toString())
                            assertTrue(result.decisions.any {
                                it.name == "TeXBinaryAtomReclassification" &&
                                    it.details["from"] == "Binary" && it.details["to"] == "Ordinary"
                            }, case.toString())
                        }
                        ComparisonInvariant.TightBinaryGlueSuppressed -> {
                            assertEquals(ComparisonOracle.KaTeXTightSpacingTable, case.oracle)
                            val binary = result.decisions.filter { it.name == "TeXMathAtomSpacing" && it.range.start in 4..5 }
                            assertEquals(2, binary.size, case.toString())
                            assertTrue(binary.all { it.details["table"] == "tight" && it.details["kind"] == "None" }, case.toString())
                        }
                        ComparisonInvariant.CrampedNestedSuperscript -> {
                            assertEquals(ComparisonOracle.KaTeXSuperscriptStyleTable, case.oracle)
                            val z = result.box.glyphs.first { it.sourceRange == SourceRange(case.source.indexOf('z'), case.source.indexOf('z') + 1) }
                            assertEquals(MathStyle.ScriptScriptCramped, z.style, case.toString())
                        }
                        ComparisonInvariant.ScriptBinomialBaseCoverage -> {
                            assertEquals(ComparisonOracle.TeXStylesAndOpenTypeVariants, case.oracle)
                            assertFalse(result.diagnostics.any { it.code == DiagnosticCode.MathVariantTooShort }, case.toString())
                            assertTrue(result.decisions.filter { it.name == "BinomialDelimiter" }
                                .all { it.details["construction"] != "BaseGlyph" }, case.toString())
                        }
                        ComparisonInvariant.FinalMathKernParticipates -> {
                            assertEquals(ComparisonOracle.OpenTypeMathKern, case.oracle)
                            assertTrue(result.decisions.any {
                                it.name == "OpenTypeMathKern" && it.details["strategy"] == "two-correction-heights"
                            }, case.toString())
                        }
                        ComparisonInvariant.AssemblyCoversTarget -> {
                            assertEquals(ComparisonOracle.OpenTypeGlyphAssembly, case.oracle)
                            assertTrue(result.decisions.filter { it.name == "BinomialDelimiter" }
                                .all { it.details["construction"] == "Assembly" }, case.toString())
                        }
                        ComparisonInvariant.OperatorAutoDisplayLimits -> {
                            assertEquals(ComparisonOracle.TeXMakeOpAndOpenTypeNary, case.oracle)
                            assertEquals(
                                "NoLimits",
                                result.decisions.first { it.name == "TeXOperatorLimitsPolicy" }
                                    .details["effectivePolicy"],
                                case.toString(),
                            )
                            val display = engine.layout(case.source, MathLayoutOptions(MathMode.Display, 44f))
                            assertEquals(
                                "Limits",
                                display.decisions.first { it.name == "TeXOperatorLimitsPolicy" }
                                    .details["effectivePolicy"],
                                case.toString(),
                            )
                            val operator = display.decisions.first { it.name == "TeXOperatorNoad" }
                            assertTrue(operator.details["construction"] != "BaseGlyph", case.toString())
                            assertNear(
                                operator.details.getValue("axisY").toFloat(),
                                operator.details.getValue("inkCenterAfter").toFloat(),
                                case,
                            )
                            assertTrue(display.decisions.any { it.name == "OpenTypeMathOperatorLimits" })
                        }
                        ComparisonInvariant.IntegralDefaultNoLimits -> {
                            assertEquals(ComparisonOracle.PlainTeXIntegralNoLimits, case.oracle)
                            val display = engine.layout(case.source, MathLayoutOptions(MathMode.Display, 44f))
                            assertEquals(
                                "NoLimits",
                                display.decisions.first { it.name == "TeXOperatorLimitsPolicy" }
                                    .details["effectivePolicy"],
                                case.toString(),
                            )
                            assertTrue(display.decisions.any { it.name == "TeXOperatorSideScripts" })
                            val forced = engine.layout(
                                "\\int\\limits_i^n",
                                MathLayoutOptions(MathMode.Inline, 44f),
                            )
                            assertEquals(
                                "Limits",
                                forced.decisions.first { it.name == "TeXOperatorLimitsPolicy" }
                                    .details["effectivePolicy"],
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class CorpusCase(
    val id: String,
    val source: String,
    val oracle: ComparisonOracle,
    val invariant: ComparisonInvariant,
)

private enum class ComparisonOracle(val corpusText: String) {
    TeXOrdinarySubMlistAndKaTeXOrdGroup("TeX ordinary sub-mlist noad; KaTeX ord group"),
    KaTeXTightSpacingTable("KaTeX spacingData tight table"),
    KaTeXSuperscriptStyleTable("KaTeX Style.sup [S,Sc,S,Sc,SS,SSc,SS,SSc]"),
    TeXStylesAndOpenTypeVariants("TeX style transitions; OpenType MATH variants"),
    OpenTypeMathKern("OpenType MATH MathKern two-height algorithm"),
    OpenTypeGlyphAssembly("OpenType MATH GlyphAssembly"),
    TeXMakeOpAndOpenTypeNary("TeX make_op displaylimits; OpenType MATH n-ary variants"),
    PlainTeXIntegralNoLimits("plain.tex integral nolimits; TeX op noad"),
    ;

    companion object {
        fun from(text: String): ComparisonOracle = entries.singleOrNull { it.corpusText == text }
            ?: error("unreviewed comparison oracle: $text")
    }
}

private enum class ComparisonInvariant(val corpusText: String) {
    OrdinarySubMlistBoundary("group is one Ord atom and edge Bin becomes Ord internally"),
    TightBinaryGlueSuppressed("binary glue is suppressed in ScriptCramped"),
    CrampedNestedSuperscript("nested denominator superscripts remain ScriptScriptCramped"),
    ScriptBinomialBaseCoverage("ScriptCramped delimiters use base-glyph construction coverage"),
    FinalMathKernParticipates("parsed corner kerns participate in final script x"),
    AssemblyCoversTarget("extenders repeat and connector overlap covers the target"),
    OperatorAutoDisplayLimits("Auto uses stacked limits only in display style and the operator follows the math axis"),
    IntegralDefaultNoLimits("integrals default to side scripts while an explicit limits modifier overrides"),
    ;

    companion object {
        fun from(text: String): ComparisonInvariant = entries.singleOrNull { it.corpusText == text }
            ?: error("unreviewed comparison invariant: $text")
    }
}

private fun assertNear(expected: Float, actual: Float, case: CorpusCase) {
    assertTrue(abs(expected - actual) <= 0.03f, "$case expected=$expected actual=$actual")
}
