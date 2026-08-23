package org.tiqian.math.font.skia

import java.io.File
import kotlin.test.Test
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathMode
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/**
 * Corpus regression sweep over every display formula of the reference Zhihu answer at electronic
 * phone ratios. Writes a per-formula break report (line source slices, break kinds/depths,
 * overfull counts, indent tiers) to `build/reports/zhihu-sweep.txt`, and asserts that the set of
 * flagged pathologies exactly matches the adjudicated baseline — any new overflow, fenced break,
 * or diagnostic across the corpus turns the suite red and the report names the offender.
 */
class ZhihuCorpusSweepTest {
    @Test
    fun sweepCorpusAndWriteReport() {
        val corpus = checkNotNull(javaClass.classLoader.getResource("zhihu-cosx-corpus.txt"))
            .readText().trim().lines()
        val report = StringBuilder()
        var flagged = 0
        val flaggedSummaries = mutableListOf<String>()
        SkiaMathFontFamily.loadBundledLete().use { math ->
            TestHostTextProvider(
                SkiaMathFontFace(
                    org.tiqian.math.font.opentype.LeteSansMath.load(),
                    MathFaceId("sweep-host"),
                ),
            ).use { provider ->
                corpus.forEachIndexed { index, source ->
                    listOf(48f, 60f).forEach { fontSizePx ->
                        val result = MathLayoutEngine(math, textRunProvider = provider).layout(
                            source,
                            MathLayoutOptions(
                                mode = MathMode.Display,
                                fontSizePx = fontSizePx,
                                displayWidthPx = 1248f,
                                textLocale = "zh-Hans",
                                softWrapDisplay = true,
                            ),
                        )
                        val flags = mutableListOf<String>()
                        if (result.diagnostics.isNotEmpty()) {
                            flags += "DIAGNOSTICS:" + result.diagnostics.joinToString { it.code.name }
                        }
                        val wrapDecisions = result.decisions.filter {
                            it.name.endsWith("LineBreak") || it.name == "MarkdownExplicitDisplayRows"
                        }
                        val lines = mutableListOf<String>()
                        wrapDecisions.forEach { decision ->
                            when (decision.name) {
                                "MarkdownExplicitDisplayRows" -> {
                                    val joined = decision.details["rowJoinPolicy"]
                                    lines += "rows: author=${decision.details["authorRowCount"]} " +
                                        "layout=${decision.details["rowCount"]} join=$joined"
                                }
                                else -> {
                                    val ranges = decision.details["lineSourceRanges"].orEmpty()
                                    val kinds = decision.details["continuationBreakKinds"].orEmpty()
                                    val depths = decision.details["continuationBreakDepths"].orEmpty()
                                    val overfull = decision.details["overfullLineCount"] ?: "?"
                                    val tier = decision.details["continuationIndentTier"] ?: "-"
                                    if (overfull != "0") flags += "OVERFULL=$overfull"
                                    if (depths.split(",").any { it.trim().toIntOrNull()?.let { d -> d > 0 } == true }) {
                                        flags += "FENCED-BREAK depths=$depths"
                                    }
                                    lines += "${decision.name} tier=$tier kinds=[$kinds] depths=[$depths]"
                                    ranges.split(";").filter { it.isNotEmpty() }.forEachIndexed { li, r ->
                                        val bounds = r.split("..").mapNotNull { it.toIntOrNull() }
                                        if (bounds.size == 2) {
                                            val slice = source.substring(
                                                bounds[0].coerceIn(0, source.length),
                                                bounds[1].coerceIn(0, source.length),
                                            )
                                            lines += "  L$li |$slice|"
                                        }
                                    }
                                }
                            }
                        }
                        if (flags.isNotEmpty() || fontSizePx == 60f) {
                            if (flags.isNotEmpty()) {
                                flagged++
                                flags.forEach { flag ->
                                    flaggedSummaries +=
                                        "#$index@${fontSizePx.toInt()}:${flag.substringBefore(" depths")}"
                                }
                            }
                            report.append("== #$index @${fontSizePx.toInt()}px")
                            if (flags.isNotEmpty()) report.append("  ***  ${flags.joinToString("  ")}")
                            report.append('\n')
                            report.append("src: $source\n")
                            lines.forEach { report.append(it).append('\n') }
                            report.append('\n')
                        }
                    }
                }
            }
        }
        val out = File("build/reports/zhihu-sweep.txt")
        out.parentFile.mkdirs()
        out.writeText("flagged=$flagged\n\n$report")
        println("sweep report: ${out.absolutePath} flagged=$flagged")

        // Adjudicated corpus baseline: five unavoidable single-atom overflow lines and one
        // emergency fenced break, all at the zoomed 60px tier. Anything else is a regression;
        // the report file names the offender.
        kotlin.test.assertEquals(
            adjudicatedFlags,
            flaggedSummaries.toSet(),
            "corpus pathology set drifted; inspect ${out.absolutePath}",
        )
    }

    private val adjudicatedFlags = setOf(
        "#37@60:OVERFULL=1",
        "#40@60:OVERFULL=1",
        "#45@60:OVERFULL=1",
        "#46@60:FENCED-BREAK",
        "#56@60:OVERFULL=1",
        "#66@60:OVERFULL=1",
    )
}
