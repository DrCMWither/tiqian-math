package org.tiqian.math.font.skia

import org.tiqian.math.core.DiagnosticCode
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
            setOf("group-transparency", "tight-spacing", "cramped-superscript", "script-binomial", "math-kern", "assembly"),
            corpus.map { it.id }.toSet(),
        )

        listOf(LeteSansMath.load(), StixTwoMath.load()).forEach { font ->
            SkiaMathFontFace(font).use { face ->
                val engine = MathLayoutEngine(face)
                corpus.forEach { case ->
                    val result = engine.layout(case.source, MathLayoutOptions(fontSizePx = 44f))
                    when (case.invariant) {
                        ComparisonInvariant.TransparentOrdinaryGroup -> {
                            assertEquals(ComparisonOracle.TeXOrdinaryGroupAndKaTeXAtomList, case.oracle)
                            val plain = engine.layout(case.source.replace("{", "").replace("}", ""), MathLayoutOptions(fontSizePx = 44f))
                            assertNear(plain.box.width, result.box.width, case)
                            assertTrue(result.decisions.any { it.name == "TransparentMathGroup" }, case.toString())
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
    TeXOrdinaryGroupAndKaTeXAtomList("TeX ordinary group; KaTeX atom list"),
    KaTeXTightSpacingTable("KaTeX spacingData tight table"),
    KaTeXSuperscriptStyleTable("KaTeX Style.sup [S,Sc,S,Sc,SS,SSc,SS,SSc]"),
    TeXStylesAndOpenTypeVariants("TeX style transitions; OpenType MATH variants"),
    OpenTypeMathKern("OpenType MATH MathKern two-height algorithm"),
    OpenTypeGlyphAssembly("OpenType MATH GlyphAssembly"),
    ;

    companion object {
        fun from(text: String): ComparisonOracle = entries.singleOrNull { it.corpusText == text }
            ?: error("unreviewed comparison oracle: $text")
    }
}

private enum class ComparisonInvariant(val corpusText: String) {
    TransparentOrdinaryGroup("braces do not create an Inner atom or extra glue"),
    TightBinaryGlueSuppressed("binary glue is suppressed in ScriptCramped"),
    CrampedNestedSuperscript("nested denominator superscripts remain ScriptScriptCramped"),
    ScriptBinomialBaseCoverage("ScriptCramped delimiters use base-glyph construction coverage"),
    FinalMathKernParticipates("parsed corner kerns participate in final script x"),
    AssemblyCoversTarget("extenders repeat and connector overlap covers the target"),
    ;

    companion object {
        fun from(text: String): ComparisonInvariant = entries.singleOrNull { it.corpusText == text }
            ?: error("unreviewed comparison invariant: $text")
    }
}

private fun assertNear(expected: Float, actual: Float, case: CorpusCase) {
    assertTrue(abs(expected - actual) <= 0.03f, "$case expected=$expected actual=$actual")
}
