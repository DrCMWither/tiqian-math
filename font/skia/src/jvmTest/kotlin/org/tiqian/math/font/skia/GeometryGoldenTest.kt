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
            appendLine("geometry-v2")
            listOf(
                "Lete Sans Math" to LeteSansMath.load(),
                "STIX Two Math" to StixTwoMath.load(),
            ).forEach { (label, font) -> appendFace(label, font) }
        }.trimEnd()
        val expected = checkNotNull(javaClass.getResourceAsStream("/goldens/geometry-v2.txt"))
            .bufferedReader().use { it.readText() }.trimEnd()
        assertEquals(expected, actual)
    }

    private fun StringBuilder.appendFace(label: String, font: OpenTypeMathFont) {
        appendLine("face=$label upm=${font.unitsPerEm} line=${font.lineMetrics.typoAscender}/${font.lineMetrics.typoDescender}/${font.lineMetrics.typoLineGap}")
        SkiaMathFontFace(font).use { face ->
            val engine = MathLayoutEngine(face)
            listOf(
                GoldenCase("symbols", "x+\\mathrm{x}+\\alpha+\\Gamma+2", MathMode.Inline, 40f),
                GoldenCase("group", "a{+}b", MathMode.Inline, 40f),
                GoldenCase("style", "{\\scriptstyle x+y}z", MathMode.Inline, 40f),
                GoldenCase("tight", "x_{k-1}", MathMode.Inline, 40f),
                GoldenCase("cramped", "\\frac{a}{x^{y^z}}", MathMode.Inline, 40f),
                GoldenCase("script-binomial", "\\frac{a}{\\binom{n}{k}}", MathMode.Inline, 40f),
                GoldenCase("operator-inline", "\\sum_i^n+\\int_0^1", MathMode.Inline, 40f),
                GoldenCase("operator-display", "\\sum_i^n+\\int\\limits_0^1", MathMode.Display, 40f),
                GoldenCase(
                    "operator-limit-skew",
                    "\\int\\limits_{abcdefgh}^{abcdefgh}",
                    MathMode.Display,
                    40f,
                ),
                GoldenCase("radical-inline", "\\sqrt[3]{x^2+1}", MathMode.Inline, 40f),
                GoldenCase(
                    "radical-display",
                    "\\sqrt{\\frac{a+b}{\\sqrt{x}}}",
                    MathMode.Display,
                    40f,
                ),
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
                    "symbols" -> appendLine(
                        "  evidence=" + result.decisions.filter { it.name == "TeXMathSymbolResolution" }
                            .joinToString(",") {
                                "${it.details["identity"]}:${it.details["declaredFamily"]}/${it.details["familyBinding"]}/" +
                                    "${it.details["declaredAlphabet"]}->${it.details["resolvedFamily"]}/" +
                                    "${it.details["resolvedAlphabet"]}/${it.details["backendScalar"]}/${it.details["glyphIds"]}"
                            },
                    )
                    "group" -> appendLine(
                        "  evidence=" + result.decisions.filter {
                            it.name == "TeXOrdSubMlist" || it.name == "TeXBinaryAtomReclassification"
                        }.joinToString(",") { "${it.name}:${it.range.start}..${it.range.endExclusive}:${it.details}" },
                    )
                    "style" -> appendLine(
                        "  evidence=" + result.decisions.filter {
                            it.name == "TeXMathStyleDeclaration" || it.name == "TeXOrdSubMlist"
                        }.joinToString(",") { "${it.name}:${it.range.start}..${it.range.endExclusive}:${it.details}" },
                    )
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
                    "operator-inline", "operator-display" -> appendLine(
                        "  evidence=" + result.decisions.filter {
                            it.name == "TeXOperatorNoad" ||
                                it.name == "TeXOperatorLimitsPolicy" ||
                                it.name == "OpenTypeMathOperatorLimits" ||
                                it.name == "TeXOperatorSideScripts"
                        }.joinToString(",") { decision ->
                            when (decision.name) {
                                "TeXOperatorNoad" ->
                                    "op:${decision.details["identity"]}/${decision.details["style"]}/" +
                                        "${decision.details["construction"]}/" +
                                        "${decision.details.getValue("displayOperatorMinHeightPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("achievedAdvancePx").toFloat().fmt()}/" +
                                        "axis=${decision.details.getValue("inkCenterAfter").toFloat().fmt()}/" +
                                        "ic=${decision.details.getValue("italicCorrectionPx").toFloat().fmt()}/" +
                                        decision.details["italicCorrectionSource"]
                                "TeXOperatorLimitsPolicy" ->
                                    "policy:${decision.details["identity"]}/${decision.details["declaredPolicy"]}->" +
                                        "${decision.details["effectivePolicy"]}/${decision.details["reason"]}"
                                "OpenTypeMathOperatorLimits" ->
                                    "limits:${decision.details.getValue("actualUpperGapPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("actualUpperBaselineRisePx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("actualLowerGapPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("actualLowerBaselineDropPx").toFloat().fmt()}"
                                else -> "side:${decision.details["identity"]}/${decision.details["style"]}"
                            }
                        },
                    )
                    "operator-limit-skew" -> {
                        val limits = result.decisions.first { it.name == "OpenTypeMathOperatorLimits" }
                        appendLine(
                            "  evidence=policy=${limits.details["logicalWidthPolicy"]} " +
                                "widths=${limits.details.getValue("operatorWidthPx").toFloat().fmt()}/" +
                                "${limits.details.getValue("upperWidthPx").toFloat().fmt()}/" +
                                "${limits.details.getValue("lowerWidthPx").toFloat().fmt()} " +
                                "x=${limits.details.getValue("operatorX").toFloat().fmt()}/" +
                                "${limits.details.getValue("upperX").toFloat().fmt()}/" +
                                "${limits.details.getValue("lowerX").toFloat().fmt()} " +
                                "ic=${limits.details.getValue("operatorItalicCorrectionPx").toFloat().fmt()}",
                        )
                    }
                    "radical-inline", "radical-display" -> appendLine(
                        "  evidence=" + result.decisions.filter {
                            it.name == "TeXRadicalNoad" ||
                                it.name == "OpenTypeRadicalConstruction" ||
                                it.name == "OpenTypeMathRadical"
                        }.joinToString(",") { decision ->
                            when (decision.name) {
                                "TeXRadicalNoad" ->
                                    "noad:${decision.range.start}..${decision.range.endExclusive}/" +
                                        "${decision.details["style"]}/${decision.details["radicandStyle"]}/" +
                                        "${decision.details["degreeStyle"]}/${decision.details["scriptBaseKind"]}"
                                "OpenTypeRadicalConstruction" ->
                                    "construction:${decision.details["construction"]}/" +
                                        "${decision.details["selectionStep"]}/" +
                                        "${decision.details["targetMetric"]}/" +
                                        "base=${decision.details["baseGlyphCoversTarget"]}/" +
                                        "${decision.details.getValue("targetHeightPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("achievedAdvancePx").toFloat().fmt()}/" +
                                        "${decision.details["componentGlyphIds"]}/${decision.details["componentOffsetsDesignUnits"]}"
                                else ->
                                    "geometry:${decision.details.getValue("radicalVerticalGapPx").toFloat().fmt()}/" +
                                        "excess=${decision.details.getValue("constructionExcessPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("actualRadicalGapPx").toFloat().fmt()}/" +
                                        "${decision.details["clearancePolicy"]}/" +
                                        "${decision.details.getValue("radicalRuleThicknessPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("radicalExtraAscenderPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("radicalKernBeforeDegreePx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("radicalKernAfterDegreePx").toFloat().fmt()}/" +
                                        "adjusted=${decision.details.getValue("adjustedRadicalKernBeforeDegreePx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("adjustedRadicalKernAfterDegreePx").toFloat().fmt()}/" +
                                        "${decision.details["radicalDegreeBottomRaisePercent"]}/" +
                                        "raise=${decision.details["degreeRaiseReferencePx"]}/" +
                                        "${decision.details["degreeRaiseReferenceMetric"]}/" +
                                        "${decision.details["degreeRaisePx"]}/" +
                                        "bottom=${decision.details["degreeLogicalBottomY"]}/" +
                                        "${decision.details["degreeInkBottomY"]}/" +
                                        "B=${decision.details.getValue("unindexedAscentPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("unindexedDescentPx").toFloat().fmt()}/" +
                                        "degreeBaseline=${decision.details["degreeBaselineY"]}/" +
                                        "${decision.details.getValue("logicalWidthPx").toFloat().fmt()}/" +
                                        "seam=${decision.details["radicalTopStrokeEvidence"]}/" +
                                        "${decision.details["radicalTopStrokeEvidenceSource"]}/" +
                                        "${decision.details.getValue("radicalTopStrokeTopPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("radicalTopStrokeBottomPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("radicalTopStrokeRightPx").toFloat().fmt()}->" +
                                        "${decision.details.getValue("ruleLeft").toFloat().fmt()}/" +
                                        "advance=${decision.details.getValue("radicalBoxAdvancePx").toFloat().fmt()}/" +
                                        "bounds=${decision.details["radicalGlyphBoundsSources"]}/" +
                                        "logical=${decision.details["radicalLogicalAdvancePolicy"]}"
                            }
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
