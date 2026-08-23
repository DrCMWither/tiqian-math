package org.tiqian.math.layout

import org.tiqian.math.core.*

internal data class MathLayoutDebugDumpMetadata(
    val unitsPerEm: Int,
    val axisHeight: Int,
    val fractionRuleThickness: Int,
    val scriptPercentScaleDown: Int,
    val scriptScriptPercentScaleDown: Int,
)

/**
 * Value-based so two independently produced, otherwise identical layout results retain the
 * equality semantics they had when [MathLayoutResult.debugDump] was an eager String.
 */
internal data class DefaultMathLayoutDebugDumpRenderer(
    val metadata: MathLayoutDebugDumpMetadata,
) : MathLayoutDebugDumpRenderer {
    override fun render(result: MathLayoutResult): String =
        buildMathLayoutDebugDump(result, metadata)
}

private fun buildMathLayoutDebugDump(
    result: MathLayoutResult,
    metadata: MathLayoutDebugDumpMetadata,
): String = buildString {
    appendLine("source=${result.source}")
    appendLine("mode=${result.mode} style=${result.initialStyle} upm=${metadata.unitsPerEm}")
    appendLine(
        "math axis=${metadata.axisHeight} rule=${metadata.fractionRuleThickness} " +
            "script=${metadata.scriptPercentScaleDown}/${metadata.scriptScriptPercentScaleDown}",
    )
    val box = result.box
    appendLine(
        "box advance=${box.width} ink=${box.inkBounds.left},${box.inkBounds.top}," +
            "${box.inkBounds.right},${box.inkBounds.bottom} visual=${box.visualLeft}..${box.visualRight}",
    )
    result.taggedDisplayReplay?.let { replay ->
        appendLine(
            "taggedDisplay viewport=${replay.viewportWidthPx} bodyX=${replay.bodyLogicalX} " +
                "bodyVisual=${replay.body.visualLeft}..${replay.body.visualRight}",
        )
        replay.tags.forEachIndexed { index, tag ->
            appendLine(
                "equationTagReplay[$index] range=${tag.sourceRange.start}..${tag.sourceRange.endExclusive} " +
                    "placement=${tag.placement} x=${tag.logicalX} baseline=${tag.baselineY} " +
                    "visual=${tag.box.visualLeft}..${tag.box.visualRight}",
            )
        }
    }
    val lineMetrics = result.lineMetrics
    appendLine(
        "line font=${lineMetrics.fontAscentPx}/${lineMetrics.fontDescentPx}/${lineMetrics.fontLineGapPx} " +
            "mathLeading=${lineMetrics.mathLeadingPx} ink=${lineMetrics.inkAscentPx}/${lineMetrics.inkDescentPx} " +
            "logical=${lineMetrics.logicalAscentPx}/${lineMetrics.logicalDescentPx}",
    )
    result.decisions.forEach { decision ->
        appendLine(
            "decision ${decision.name} range=${decision.range.start}..${decision.range.endExclusive} " +
                decision.details.entries.joinToString(" ") { "${it.key}=${it.value}" },
        )
    }
    box.glyphs.forEachIndexed { index, glyph ->
        appendLine(
            "glyph[$index] id=${glyph.glyphId} range=${glyph.sourceRange.start}..${glyph.sourceRange.endExclusive} " +
                "style=${glyph.style} size=${glyph.fontSizePx} x=${glyph.x} baseline=${glyph.baselineY} " +
                "face=${glyph.faceId} class=${glyph.fontClass} weight=${glyph.requestedWeight}->${glyph.resolvedWeight} " +
                "mathFallback=${glyph.fallbackReason} " +
                "hostDecision=${glyph.hostTextDecision} " +
                "ink=${glyph.inkBounds.left},${glyph.inkBounds.top},${glyph.inkBounds.right},${glyph.inkBounds.bottom} " +
                "constructionGroup=${glyph.constructionGroupId}",
        )
    }
    box.hostTextRuns.forEachIndexed { index, run ->
        appendLine(
            "hostText[$index] id=${run.runId} range=${run.sourceRange.start}..${run.sourceRange.endExclusive} " +
                "x=${run.x} baseline=${run.baselineY} advance=${run.width} " +
                "logical=${run.ascent}/${run.descent} weight=${run.requestedWeight} " +
                "ink=${run.inkBounds.left},${run.inkBounds.top},${run.inkBounds.right},${run.inkBounds.bottom}",
        )
    }
    box.rules.forEachIndexed { index, rule ->
        val line = rule.lineSegment?.let { " line=$it" }.orEmpty()
        appendLine(
            "rule[$index] ${rule.left},${rule.top},${rule.right},${rule.bottom} " +
                "layer=${rule.paintLayer} role=${rule.paintRole} color=${rule.paintColor} " +
                "constructionGroup=${rule.constructionGroupId}$line",
        )
    }
    box.constructionPaintGroups.forEach { group ->
        appendLine(
            "constructionPaintGroup[${group.id}] kind=${group.kind} shape=${group.shapeKind} " +
                "face=${group.faceId} " +
                "range=${group.sourceRange.start}..${group.sourceRange.endExclusive} " +
                "outlinePolicy=${group.outlinePolicy}",
        )
    }
    result.fragments.forEach { fragment ->
        appendLine(
            "fragment[${fragment.index}] range=${fragment.sourceRange.start}..${fragment.sourceRange.endExclusive} " +
                "advance=${fragment.box.width} ink=${fragment.box.inkBounds.left}..${fragment.box.inkBounds.right} " +
                "leadingKern=${fragment.leadingKernPx} " +
                "italicCorrection=${fragment.trailingItalicCorrectionPx} " +
                "glue=${fragment.trailingGlue.kind}/${fragment.trailingGlue.naturalPx}/" +
                "${fragment.trailingGlue.minimumPx}/${fragment.trailingGlue.maximumPx}/" +
                "${fragment.trailingGlue.priority}",
        )
    }
    result.breakOpportunities.forEach { opportunity ->
        appendLine(
            "break after=${opportunity.afterFragmentIndex} offset=${opportunity.sourceOffset} " +
                "kind=${opportunity.kind} priority=${opportunity.priority} " +
                "discard=${opportunity.discardedTrailingGlue.naturalPx}",
        )
    }
    result.diagnostics.forEach { diagnostic ->
        appendLine(
            "diagnostic ${diagnostic.severity}/${diagnostic.code} " +
                "range=${diagnostic.range.start}..${diagnostic.range.endExclusive}",
        )
    }
}
