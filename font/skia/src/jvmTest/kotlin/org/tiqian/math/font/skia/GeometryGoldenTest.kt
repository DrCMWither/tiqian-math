package org.tiqian.math.font.skia

import org.tiqian.math.core.MathMode
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reviewed structural snapshot; invariant tests remain the authority for correctness. */
class GeometryGoldenTest {
    @Test
    fun twoFontGeometryAndDecisionSnapshotMatchesReviewedGolden() {
        val actual = buildString {
            appendLine("geometry-v1")
            listOf(
                "Lete Sans Math" to LeteSansMath.load(),
                "STIX Two Math" to StixTwoMath.load(),
            ).forEach { (label, font) -> appendFace(label, font) }
        }.trimEnd()
        val expected = checkNotNull(javaClass.getResourceAsStream("/goldens/geometry-v1.txt"))
            .bufferedReader().use { it.readText() }.trimEnd()
        assertEquals(expected, actual)
    }

    private fun StringBuilder.appendFace(label: String, font: OpenTypeMathFont) {
        appendLine("face=$label upm=${font.unitsPerEm} line=${font.lineMetrics.typoAscender}/${font.lineMetrics.typoDescender}/${font.lineMetrics.typoLineGap}")
        SkiaMathFontFace(font).use { face ->
            val engine = MathLayoutEngine(face)
            listOf(
                GoldenCase("variants", "x+\\mathrm{x}+\\alpha+2", MathMode.Inline, 40f),
                GoldenCase("group", "a{b}c", MathMode.Inline, 40f),
                GoldenCase("tight", "x_{k-1}", MathMode.Inline, 40f),
                GoldenCase("cramped", "\\frac{a}{x^{y^z}}", MathMode.Inline, 40f),
                GoldenCase("script-binomial", "\\frac{a}{\\binom{n}{k}}", MathMode.Inline, 40f),
                GoldenCase("adjustment", "a,b=c+d", MathMode.Inline, 40f),
            ).forEach { case ->
                val result = engine.layout(case.source, MathLayoutOptions(case.mode, case.size))
                appendLine(
                    "case=${case.id} logical=${result.box.width.fmt()} visual=${result.box.visualLeft.fmt()}..${result.box.visualRight.fmt()} " +
                        "ink=${result.box.inkBounds.left.fmt()},${result.box.inkBounds.top.fmt()},${result.box.inkBounds.right.fmt()},${result.box.inkBounds.bottom.fmt()} " +
                        "safe=${result.lineMetrics.logicalAscentPx.fmt()}/${result.lineMetrics.logicalDescentPx.fmt()} " +
                        "fragments=${result.fragments.size} breaks=${result.breakOpportunities.size} diagnostics=${result.diagnostics.map { it.code }}",
                )
                when (case.id) {
                    "variants" -> appendLine(
                        "  evidence=" + result.decisions.filter { it.name == "MathVariantGlyphSelection" }
                            .joinToString(",") {
                                "${it.details["semantic"]}:${it.details["variant"]}/${it.details["glyphIds"]}"
                            },
                    )
                    "group" -> appendLine("  evidence=${result.decisions.filter { it.name == "TransparentMathGroup" }.map { "${it.range.start}..${it.range.endExclusive}" }}")
                    "tight" -> appendLine(
                        "  evidence=" + result.decisions.filter { it.name == "TeXMathAtomSpacing" }
                            .joinToString(",") { "${it.range.start}..${it.range.endExclusive}:${it.details["table"]}/${it.details["kind"]}" },
                    )
                    "cramped" -> appendLine(
                        "  evidence=" + listOf('x', 'y', 'z').joinToString(",") { character ->
                            val offset = case.source.indexOf(character)
                            val glyph = result.box.glyphs.first { it.sourceRange == SourceRange(offset, offset + 1) }
                            "$character:${glyph.style}@${glyph.baselineY.fmt()}"
                        },
                    )
                    "script-binomial" -> appendLine(
                        "  evidence=" + result.decisions.filter { it.name == "BinomialDelimiter" }.joinToString(",") {
                            "${it.details["side"]}:${it.details["baseGlyphId"]}/${it.details["construction"]}/" +
                                "${it.details.getValue("targetPx").toFloat().fmt()}/${it.details.getValue("achievedAdvancePx").toFloat().fmt()}"
                        },
                    )
                    "adjustment" -> appendLine(
                        "  evidence=" + result.fragments.joinToString(",") {
                            "${it.sourceRange.start}:ic=${it.trailingItalicCorrectionPx.fmt()}/" +
                                "${it.trailingGlue.kind}/${it.trailingGlue.naturalPx.fmt()}/" +
                                "${it.trailingGlue.minimumPx.fmt()}/${it.trailingGlue.maximumPx.fmt()}/${it.trailingGlue.priority}"
                        },
                    )
                }
            }
        }
    }

    private fun Float.fmt(): String = String.format(Locale.ROOT, "%.3f", this)

    private data class GoldenCase(val id: String, val source: String, val mode: MathMode, val size: Float)

}
