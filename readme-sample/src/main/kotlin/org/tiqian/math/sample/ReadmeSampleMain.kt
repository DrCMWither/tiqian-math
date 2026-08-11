package org.tiqian.math.sample

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.DynamicMemoryWStream
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Point
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.shaper.HbIcuScriptRunIterator
import org.jetbrains.skia.shaper.RunHandler
import org.jetbrains.skia.shaper.RunInfo
import org.jetbrains.skia.shaper.Shaper
import org.jetbrains.skia.shaper.ShapingOptions
import org.jetbrains.skia.shaper.TrivialBidiRunIterator
import org.jetbrains.skia.shaper.TrivialFontRunIterator
import org.jetbrains.skia.shaper.TrivialLanguageRunIterator
import org.jetbrains.skia.svg.SVGCanvas
import org.tiqian.clreq.ClreqProfile
import org.tiqian.core.InlineObjectBoundaryAdjustment
import org.tiqian.core.InlineObjectPreferredStretch
import org.tiqian.core.InlineObjectPreferredStretchKind
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.PositionedCluster
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.EastAsianSpacingValue
import org.tiqian.core.UnicodeEastAsianSpacing
import org.tiqian.core.ic
import org.tiqian.core.positionedClusters
import org.tiqian.layout.ExplainableStubParagraphLayoutEngine
import org.tiqian.layout.LookaheadLineBreaker
import org.tiqian.math.core.MathAdjustmentPriority
import org.tiqian.math.core.MathAtomClass
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathBreakKind
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.core.MathHostTextFaceDecision
import org.tiqian.math.core.MathInlineFragment
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathPaintColor
import org.tiqian.math.core.MathPaintLayer
import org.tiqian.math.core.MathReplayFaceOwnership
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathRulePaintRole
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.skia.MathConstructionOutlineResult
import org.tiqian.math.font.skia.SkiaMathFontFamily
import org.tiqian.math.font.skia.SkiaMathFontFace
import org.tiqian.math.font.skia.SkiaReplayCatalog
import org.tiqian.math.font.skia.SkiaReplayFace
import org.tiqian.math.font.skia.combineSkiaReplayCatalogs
import org.tiqian.math.font.skia.formulaCapabilityEngine
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathGlyphBoundsSource
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.MathTextRunProviderResult
import org.tiqian.math.layout.MathTextRunRequest
import org.tiqian.math.layout.MeasuredMathGlyph
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.shaping.skia.SkiaFontMetricsResolver
import org.tiqian.shaping.skia.SkiaSystemTypefaces
import org.tiqian.shaping.skia.SkiaTextShaper
import org.tiqian.shaping.skia.drawTiqianGlyphs
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.abs

private const val BODY_FONT_SIZE = 28f
private const val BODY_LINE_HEIGHT = BODY_FONT_SIZE * 1.65f
private const val INLINE_INK_LEADING = BODY_FONT_SIZE * 0.05f
private const val INLINE_STRETCH_TARGET = BODY_FONT_SIZE * 0.5f
private val FORMULA_BOUNDARY_GAP = BODY_FONT_SIZE * ClreqProfile.MainlandHorizontal.autoSpace.gapEm
private const val MEASURE = BODY_FONT_SIZE * 30f
private const val CANVAS_PADDING = 42f
private const val DISPLAY_MARGIN = BODY_LINE_HEIGHT * 0.5f
private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
private const val BLACK_INK = -0x1000000
private const val WHITE_INK = -0x1

private val FIRST_PARAGRAPH = listOf(
    TextChunk("二项式展开把一个乘方写成若干同次项的和。对于非负整数"),
    FormulaChunk("n"),
    TextChunk("，"),
    FormulaChunk("(x+y)^n"),
    TextChunk("的第"),
    FormulaChunk("k"),
    TextChunk("项由"),
    FormulaChunk("\\binom{n}{k}x^{n-k}y^k"),
    TextChunk("给出，其中"),
    FormulaChunk("k"),
    TextChunk("从"),
    FormulaChunk("0"),
    TextChunk("取到"),
    FormulaChunk("n"),
    TextChunk("。每一项中"),
    FormulaChunk("x"),
    TextChunk("与"),
    FormulaChunk("y"),
    TextChunk("的指数之和都等于"),
    FormulaChunk("n"),
    TextChunk("，而系数决定该项出现的次数。把各项依次相加，就得到："),
)

private const val DISPLAY_FORMULA =
    "(x+y)^n=\\sum_{k=0}^{n}\\binom{n}{k}x^{n-k}y^k\\tag{1}"

private val SECOND_PARAGRAPH = listOf(
    TextChunk("其中，组合数给出各项系数；首尾系数为一，其余系数关于展开式的中项对称。把前几项写开，可以看到"),
    FormulaChunk(
        "(x+y)^n=x^n+nx^{n-1}y+\\frac{n(n-1)}{2}x^{n-2}y^2+" +
            "\\frac{n(n-1)(n-2)}{6}x^{n-3}y^3+\\cdots+nxy^{n-1}+y^n",
    ),
    TextChunk("。令两个变量都为一，便有"),
    FormulaChunk("\\sum_{k=0}^{n}\\binom{n}{k}=2^n"),
    TextChunk("，这说明含有若干元素的集合，其子集总数等于二的相应次幂。"),
)

fun main(args: Array<String>) {
    require(args.size == 2) { "Expected black-text and white-text SVG paths" }
    val bodyLatinTypeface = requireNotNull(SkiaSystemTypefaces.latin) {
        "README sample requires the same Latin typeface selected for Tiqian body text"
    }
    val mathFace = SkiaMathFontFamily.loadBundledLete()
    val textProvider = ReadmeHostTextProvider(bodyLatinTypeface)
    val shaper = SkiaTextShaper()
    try {
        val replayCatalog = combineSkiaReplayCatalogs(mathFace, textProvider)
        val mathEngine = mathFace.formulaCapabilityEngine(textProvider)
        val paragraphEngine = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            textShaper = shaper,
            fontMetricsResolver = SkiaFontMetricsResolver(),
        )
        val first = layoutParagraph(FIRST_PARAGRAPH, mathEngine, paragraphEngine)
        val display = mathEngine.requireReady(
            DISPLAY_FORMULA,
            MathLayoutOptions(
                mode = MathMode.Display,
                fontSizePx = BODY_FONT_SIZE,
                displayWidthPx = MEASURE,
            ),
        )
        val second = layoutParagraph(SECOND_PARAGRAPH, mathEngine, paragraphEngine)
        verifySample(first, display, second)

        listOf(File(args[0]) to BLACK_INK, File(args[1]) to WHITE_INK).forEach { (output, color) ->
            output.parentFile.mkdirs()
            output.writeBytes(withAccessibilityMetadata(renderSvg(
                replayCatalog = replayCatalog,
                bodyLatinTypeface = bodyLatinTypeface,
                first = first,
                display = display,
                second = second,
                inkColor = color,
            )))
            println("README math sample: ${output.absolutePath}")
        }
        println("lines=${first.result.lines.size}+${second.result.lines.size}, internalBreak=${describeBreak(second)}")
    } finally {
        textProvider.close()
        mathFace.close()
    }
}

private fun layoutParagraph(
    chunks: List<SampleChunk>,
    mathEngine: MathFormulaCapabilityEngine,
    paragraphEngine: ExplainableStubParagraphLayoutEngine,
): ParagraphLayout {
    val source = StringBuilder()
    val formulas = mutableListOf<InlineFormula>()
    val formulaBoundaryGaps = mutableListOf<TextRange>()
    fun appendFormulaBoundaryGap() {
        val start = source.length
        source.append(' ')
        formulaBoundaryGaps += TextRange(start, source.length)
    }
    chunks.forEachIndexed { index, chunk ->
        when (chunk) {
            is TextChunk -> source.append(chunk.text)
            is FormulaChunk -> {
                val previousText = chunks.getOrNull(index - 1) as? TextChunk
                if (previousText?.text?.lastOrNull()?.needsFormulaBoundaryGap() == true) {
                    appendFormulaBoundaryGap()
                }
                val start = source.length
                source.append(chunk.source)
                val result = mathEngine.requireReady(
                    chunk.source,
                    MathLayoutOptions(mode = MathMode.Inline, fontSizePx = BODY_FONT_SIZE),
                )
                formulas += buildInlineFormula(
                    source = chunk.source,
                    globalRange = TextRange(start, source.length),
                    result = result,
                )
                val nextText = chunks.getOrNull(index + 1) as? TextChunk
                if (nextText?.text?.firstOrNull()?.needsFormulaBoundaryGap() == true) {
                    appendFormulaBoundaryGap()
                }
            }
        }
    }

    val baseInput = LayoutInput(
        content = TiqianTextContent(source.toString()),
        textStyle = TextStyle(fontSize = BODY_FONT_SIZE),
        paragraphStyle = ParagraphStyle(
            lineHeight = BODY_LINE_HEIGHT,
            firstLineIndent = 2.ic,
        ),
        constraints = LayoutConstraints(maxWidth = MEASURE),
        inlineObjects = formulas.flatMap { formula -> formula.pieces.map { it.inlineObject } },
    )
    val probe = paragraphEngine.layout(baseInput)
    val gapCorrections = formulaBoundaryGaps.map { range ->
        val measuredAdvance = probe.clusters.single { it.range == range }.advance
        InlineBoxSpan(range = range, inlineEnd = FORMULA_BOUNDARY_GAP - measuredAdvance)
    }
    val input = baseInput.copy(inlineBoxes = gapCorrections)
    return ParagraphLayout(paragraphEngine.layout(input), formulas, formulaBoundaryGaps)
}

private fun Char.needsFormulaBoundaryGap(): Boolean =
    UnicodeEastAsianSpacing.resolvedForGraphemeCluster(toString(), "zh-Hans") ==
        EastAsianSpacingValue.Wide

private fun buildInlineFormula(
    source: String,
    globalRange: TextRange,
    result: MathLayoutResult,
): InlineFormula {
    val sourceRanges = result.partitionSource(source)
        ?: error("Inline formula cannot be partitioned source-faithfully: $source")
    val groups = groupFragmentIndices(result.fragments)
    val pieces = groups.mapIndexed { groupIndex, group ->
        val localRange = sourceRanges[group.first].start until sourceRanges[group.last].end
        val globalPieceRange = TextRange(
            globalRange.start + localRange.first,
            globalRange.start + localRange.last + 1,
        )
        val geometry = fragmentRangeInkTight(result, group)
        val isFirst = groupIndex == 0
        val isLast = groupIndex == groups.lastIndex
        val trailingFragment = result.fragments[group.last]
        val outerBoundary = InlineObjectBoundaryAdjustment(participatesInUniformStretch = true)
        MathPiece(
            range = globalPieceRange,
            fragmentIndices = group,
            boxes = geometry.boxes,
            trailingFragment = trailingFragment,
            inlineObject = InlineObjectSpan(
                range = globalPieceRange,
                advance = geometry.width,
                ascent = geometry.ascent,
                descent = geometry.descent,
                leadingBoundary = if (isFirst) outerBoundary else InlineObjectBoundaryAdjustment.Fixed,
                trailingBoundary = if (isLast) outerBoundary else trailingFragment.toTiqianBoundary(),
            ),
        )
    }
    check(pieces.first().range.start == globalRange.start && pieces.last().range.end == globalRange.end)
    check(pieces.zipWithNext().all { (before, after) -> before.range.end == after.range.start })
    return InlineFormula(source, globalRange, result, pieces)
}

/** Same consumer grouping contract used by the Markdown integration. */
private fun groupFragmentIndices(fragments: List<MathInlineFragment>): List<IntRange> {
    val groups = mutableListOf<MutableList<Int>>()
    fragments.forEachIndexed { index, fragment ->
        val previous = fragments.getOrNull(index - 1)
        val bindsToPrevious = groups.isNotEmpty() && (
            fragment.atomClass == MathAtomClass.Closing ||
                fragment.atomClass == MathAtomClass.Punctuation ||
                previous?.atomClass == MathAtomClass.Opening
        )
        if (bindsToPrevious) groups.last().add(index) else groups.add(mutableListOf(index))
    }
    return groups.map { it.first()..it.last() }
}

private fun MathLayoutResult.partitionSource(expression: String): List<TextRange>? {
    if (fragments.isEmpty()) return null
    val starts = buildList {
        add(0)
        fragments.drop(1).forEach { fragment ->
            add(fragment.sourceRange.start.coerceIn(0, expression.length))
        }
        add(expression.length)
    }
    if (starts.zipWithNext().any { (start, end) -> start >= end }) return null
    return starts.zipWithNext { start, end -> TextRange(start, end) }
}

private fun fragmentRangeInkTight(result: MathLayoutResult, range: IntRange): MathPieceGeometry {
    var logicalX = 0f
    var visualLeft = 0f
    var visualRight = 0f
    var inkTop = 0f
    var inkBottom = 0f
    val boxes = ArrayList<PositionedMathBox>()
    for (index in range) {
        val fragment = result.fragments[index]
        logicalX += fragment.leadingKernPx
        boxes += PositionedMathBox(fragment.box, logicalX)
        visualLeft = minOf(visualLeft, logicalX + fragment.box.inkBounds.left)
        visualRight = maxOf(visualRight, logicalX + fragment.box.inkBounds.right)
        inkTop = minOf(inkTop, fragment.box.inkBounds.top)
        inkBottom = maxOf(inkBottom, fragment.box.inkBounds.bottom)
        logicalX += fragment.box.width + fragment.trailingAdvancePx
        visualRight = maxOf(visualRight, logicalX)
    }
    return MathPieceGeometry(
        boxes = boxes.map { it.copy(x = it.x - visualLeft) },
        width = visualRight - visualLeft,
        ascent = (-inkTop).coerceAtLeast(0f) + INLINE_INK_LEADING,
        descent = inkBottom.coerceAtLeast(0f) + INLINE_INK_LEADING,
    )
}

private fun MathInlineFragment.toTiqianBoundary(): InlineObjectBoundaryAdjustment {
    val opportunity = breakAfter
    val isPunctuationBreak = opportunity?.kind == MathBreakKind.PunctuationTrailing
    val preferred = trailingGlue.takeIf { it.stretchPx > 0f }?.let { glue ->
        val kind = when (glue.priority) {
            MathAdjustmentPriority.Punctuation -> InlineObjectPreferredStretchKind.PunctuationTrailing
            MathAdjustmentPriority.Relation -> InlineObjectPreferredStretchKind.Relation
            MathAdjustmentPriority.BinaryOperator -> InlineObjectPreferredStretchKind.BinaryOperator
            else -> null
        }
        kind?.let {
            InlineObjectPreferredStretch(
                kind = it,
                naturalWidth = glue.naturalPx,
                targetWidth = maxOf(glue.naturalPx + 0.01f, INLINE_STRETCH_TARGET),
            )
        }
    }
    return InlineObjectBoundaryAdjustment(
        participatesInUniformStretch = trailingGlue != org.tiqian.math.core.MathGlueAdjustment.Zero,
        preferredStretch = preferred,
        shrinkCapacity = trailingGlue.shrinkPx,
        lineEndDiscardableAdvance = if (opportunity != null && !isPunctuationBreak) {
            trailingGlue.naturalPx
        } else {
            0f
        },
        preventsLineBreak = opportunity == null || isPunctuationBreak,
    )
}

private fun verifySample(first: ParagraphLayout, display: MathLayoutResult, second: ParagraphLayout) {
    val allMath = first.formulas.map { it.result } + display + second.formulas.map { it.result }
    check(allMath.all { result -> result.diagnostics.none { it.severity.name == "Error" } })
    check(allMath.all { result -> result.box.glyphs.all { it.glyphId.toInt() != 0 } })
    check(first.result.debug.shapingDecisions.sumOf { it.missingGlyphs } == 0)
    check(second.result.debug.shapingDecisions.sumOf { it.missingGlyphs } == 0)
    check(first.result.lines.size >= 3 && second.result.lines.size >= 3) {
        "README prose must visibly demonstrate multi-line Tiqian paragraphs"
    }
    check(first.result.debug.inlineObjectDecisions.isNotEmpty())
    check(second.result.debug.justificationDecisions.isNotEmpty())
    check(listOf(first, second).all { paragraph ->
        paragraph.result.input.inlineBoxes.size == paragraph.formulaBoundaryGaps.size
    }) { "Every formula-to-Han boundary gap must be normalized to the CLREQ profile width" }
    val equationTag = display.decisions.single { it.name == "AmsmathEquationTag" }
    check(abs(equationTag.details.getValue("displayWidthPx").toFloat() - MEASURE) < 0.01f)
    val equationTagGlyphs = display.box.glyphs.filter {
        it.hostTextDecision?.hostRole == "EquationTag"
    }
    check(equationTagGlyphs.isNotEmpty())
    check(equationTagGlyphs.all {
        it.hostTextDecision?.selectionReason == "SameTypefaceAsReadmeBodyLatin"
    })

    val internalBreaks = listOf(first, second).flatMap(::legalInternalFormulaBreaks)
    check(internalBreaks.isNotEmpty()) {
        "README sample must visibly break at least one inline formula at an engine-provided operator boundary"
    }
    check(internalBreaks.all { it.piece.trailingFragment.breakAfter?.operatorStaysOnPreviousLine == true })
    check(internalBreaks.all { it.piece.trailingFragment.breakAfter?.kind != MathBreakKind.PunctuationTrailing })

    val inlineSum = second.formulas.firstOrNull { "\\sum" in it.source }
        ?: error("README sample must contain an inline sum")
    check(inlineSum.result.initialStyle != display.initialStyle)
    check(abs(inlineSum.result.box.ascent - display.box.ascent) > 0.01f)
}

private fun legalInternalFormulaBreaks(paragraph: ParagraphLayout): List<InternalBreak> {
    val positions = paragraph.result.positionedClusters().associateBy { it.range }
    return paragraph.formulas.flatMap { formula ->
        formula.pieces.zipWithNext().mapNotNull { (piece, next) ->
            val beforePosition = positions.getValue(piece.range)
            val afterPosition = positions.getValue(next.range)
            if (beforePosition.lineIndex != afterPosition.lineIndex) {
                InternalBreak(formula, piece, beforePosition, afterPosition)
            } else {
                null
            }
        }
    }.filter { it.piece.trailingFragment.breakAfter != null }
}

private fun describeBreak(paragraph: ParagraphLayout): String =
    legalInternalFormulaBreaks(paragraph).joinToString { broken ->
        "${broken.formula.source}@${broken.piece.trailingFragment.breakAfter?.sourceOffset}:" +
            "${broken.before.lineIndex}->${broken.after.lineIndex}"
    }

private fun renderSvg(
    replayCatalog: SkiaReplayCatalog,
    bodyLatinTypeface: Typeface,
    first: ParagraphLayout,
    display: MathLayoutResult,
    second: ParagraphLayout,
    inkColor: Int,
): ByteArray {
    val displayHeight = display.lineMetrics.logicalHeightPx
    val contentHeight = first.result.size.height + DISPLAY_MARGIN + displayHeight +
        DISPLAY_MARGIN + second.result.size.height
    val width = MEASURE + CANVAS_PADDING * 2f
    val height = contentHeight + CANVAS_PADDING * 2f
    val stream = DynamicMemoryWStream()
    val canvas = SVGCanvas.make(
        Rect.makeWH(width, height),
        stream,
        convertTextToPaths = true,
        prettyXML = true,
    )
    val ink = Paint().apply { color = inkColor }
    val cjkFont = Font(SkiaSystemTypefaces.cjk, BODY_FONT_SIZE)
    val latinFont = Font(bodyLatinTypeface, BODY_FONT_SIZE)
    val paragraphShaper = Shaper.makeShaperDrivenWrapper()
    try {
        var top = CANVAS_PADDING
        drawParagraph(
            canvas, replayCatalog, paragraphShaper, cjkFont, latinFont,
            ink, first, CANVAS_PADDING, top, inkColor,
        )
        top += first.result.size.height + DISPLAY_MARGIN

        val displayX = (width - display.box.visualWidth) / 2f - display.box.visualLeft
        val displayBaseline = top + display.lineMetrics.logicalAscentPx
        drawMathBox(canvas, replayCatalog, display.box, displayX, displayBaseline, inkColor)
        top += displayHeight + DISPLAY_MARGIN

        drawParagraph(
            canvas, replayCatalog, paragraphShaper, cjkFont, latinFont,
            ink, second, CANVAS_PADDING, top, inkColor,
        )
    } finally {
        canvas.close()
        paragraphShaper.close()
        latinFont.close()
        cjkFont.close()
        ink.close()
    }
    return try {
        ByteArray(stream.bytesWritten()).also { bytes ->
            check(stream.read(bytes, 0, bytes.size)) { "Unable to read generated SVG bytes" }
        }
    } finally {
        stream.close()
    }
}

private fun drawParagraph(
    canvas: Canvas,
    mathFace: SkiaReplayCatalog,
    paragraphShaper: Shaper,
    cjkFont: Font,
    latinFont: Font,
    ink: Paint,
    paragraph: ParagraphLayout,
    left: Float,
    top: Float,
    inkColor: Int,
) {
    val saveCount = canvas.save()
    try {
        canvas.translate(left, top)
        drawTiqianGlyphs(
            canvas = canvas,
            result = paragraph.result,
            cjkFont = cjkFont,
            latinFont = latinFont,
            paint = ink,
            shaper = paragraphShaper,
        )
        val positioned = paragraph.result.positionedClusters().associateBy { it.range }
        paragraph.formulas.flatMap { it.pieces }.forEach { piece ->
            val placement = positioned.getValue(piece.range)
            piece.boxes.forEach { box ->
                drawMathBox(
                    canvas = canvas,
                    catalog = mathFace,
                    box = box.box,
                    originX = placement.drawX + box.x,
                    baseline = placement.baseline,
                    formulaColor = inkColor,
                )
            }
        }
    } finally {
        canvas.restoreToCount(saveCount)
    }
}

private fun drawMathBox(
    canvas: Canvas,
    catalog: SkiaReplayCatalog,
    box: MathBox,
    originX: Float,
    baseline: Float,
    formulaColor: Int,
) {
    check(box.hostTextRuns.isEmpty()) { "README sample formulas must not contain host text runs" }
    val paint = Paint().apply {
        color = formulaColor
        isAntiAlias = true
    }
    try {
        box.rules.filter { it.constructionGroupId == null && it.paintLayer == MathPaintLayer.Background }
            .forEach { rule ->
                paint.color = resolvedColor(rule.paintColor, formulaColor)
                canvas.drawRect(Rect.makeLTRB(
                    originX + rule.left,
                    baseline + rule.top,
                    originX + rule.right,
                    baseline + rule.bottom,
                ), paint)
            }

        box.glyphs.filter { it.constructionGroupId == null }.forEach { glyph ->
            check(catalog.replayFaceOwnership(glyph.faceId) == MathReplayFaceOwnership.Unique)
            val replayFace = checkNotNull(catalog.replayFace(glyph.faceId))
            val path = checkNotNull(replayFace.glyphPath(glyph.glyphId, glyph.fontSizePx)) {
                "No outline for glyph ${glyph.glyphId} in ${glyph.faceId}"
            }
            path.use {
                val saveCount = canvas.save()
                try {
                    canvas.translate(originX + glyph.x, baseline + glyph.baselineY)
                    paint.color = resolvedColor(glyph.paintColor, formulaColor)
                    canvas.drawPath(path, paint)
                } finally {
                    canvas.restoreToCount(saveCount)
                }
            }
        }

        box.rules.filter {
            it.constructionGroupId == null && it.paintLayer == MathPaintLayer.Foreground &&
                it.paintRole != MathRulePaintRole.Border && it.lineSegment == null
        }.forEach { rule ->
            paint.color = resolvedColor(rule.paintColor, formulaColor)
            canvas.drawRect(Rect.makeLTRB(
                originX + rule.left,
                baseline + rule.top,
                originX + rule.right,
                baseline + rule.bottom,
            ), paint)
        }

        val knownGroupIds = box.constructionPaintGroups.mapTo(mutableSetOf()) { it.id }
        val referencedGroupIds = buildSet {
            box.glyphs.mapNotNullTo(this) { it.constructionGroupId }
            box.rules.mapNotNullTo(this) { it.constructionGroupId }
        }
        check(knownGroupIds == referencedGroupIds)
        box.constructionPaintGroups.forEach { group ->
            val constructionFace = checkNotNull(catalog.constructionFace(group.faceId))
            val outline = constructionFace.constructionOutline(box, group)
            val path = when (outline) {
                is MathConstructionOutlineResult.Available -> outline.path
                is MathConstructionOutlineResult.Unavailable -> error(
                    "Construction ${group.id} cannot be replayed: ${outline.reason}",
                )
            }
            val saveCount = canvas.save()
            try {
                canvas.translate(originX, baseline)
                paint.color = resolvedColor(group.paintColor, formulaColor)
                canvas.drawPath(path, paint)
            } finally {
                canvas.restoreToCount(saveCount)
            }
        }

        box.rules.filter {
            it.constructionGroupId == null && it.paintLayer == MathPaintLayer.Foreground &&
                it.lineSegment != null
        }.forEach { rule ->
            val line = checkNotNull(rule.lineSegment)
            paint.color = resolvedColor(rule.paintColor, formulaColor)
            paint.mode = PaintMode.STROKE
            paint.strokeWidth = line.thickness
            canvas.drawLine(
                originX + line.startX,
                baseline + line.startY,
                originX + line.endX,
                baseline + line.endY,
                paint,
            )
            paint.mode = PaintMode.FILL
        }

        box.rules.filter {
            it.constructionGroupId == null && it.paintLayer == MathPaintLayer.Foreground &&
                it.paintRole == MathRulePaintRole.Border
        }.forEach { rule ->
            paint.color = resolvedColor(rule.paintColor, formulaColor)
            canvas.drawRect(Rect.makeLTRB(
                originX + rule.left,
                baseline + rule.top,
                originX + rule.right,
                baseline + rule.bottom,
            ), paint)
        }
    } finally {
        paint.close()
    }
}

private fun resolvedColor(explicit: MathPaintColor?, formulaColor: Int): Int =
    explicit?.modulatedArgb(formulaColor) ?: formulaColor

private fun withAccessibilityMetadata(svg: ByteArray): ByteArray {
    val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val document = documentBuilderFactory.newDocumentBuilder().parse(ByteArrayInputStream(svg))
    val root = document.documentElement
    removeWhitespaceTextNodes(root)
    val title = document.createElementNS(SVG_NAMESPACE, "title").apply {
        setAttribute("id", "sample-title")
        textContent = "提椠 Math 正文公式样张"
    }
    val description = document.createElementNS(SVG_NAMESPACE, "desc").apply {
        setAttribute("id", "sample-description")
        textContent = "两段由提椠排版的二项式定理正文，中间是一条展示公式；行内公式参与正文断行与两端对齐。"
    }
    root.insertBefore(description, root.firstChild)
    root.insertBefore(title, description)
    root.setAttribute("role", "img")
    root.setAttribute("aria-labelledby", "sample-title sample-description")

    val output = ByteArrayOutputStream()
    val transformerFactory = TransformerFactory.newInstance().apply {
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
    }
    transformerFactory.newTransformer().apply {
        setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        setOutputProperty(OutputKeys.INDENT, "yes")
        setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        transform(DOMSource(document), StreamResult(output))
    }
    return output.toByteArray()
}

private fun removeWhitespaceTextNodes(node: Node) {
    var child = node.firstChild
    while (child != null) {
        val next = child.nextSibling
        if (child.nodeType == Node.TEXT_NODE && child.nodeValue.isNullOrBlank()) {
            node.removeChild(child)
        } else {
            removeWhitespaceTextNodes(child)
        }
        child = next
    }
}

/** Uses the exact Latin typeface selected for the surrounding Tiqian paragraph. */
private class ReadmeHostTextProvider(
    private val typeface: Typeface,
) : MathTextRunProvider, SkiaReplayCatalog, SkiaReplayFace, AutoCloseable {
    override val faceId = MathFaceId("readme-body-latin")
    override val resolvedWeight = MathFontWeight.Regular
    private val shaper = Shaper.makeShaperDrivenWrapper()

    override fun shapeTextAtom(request: MathTextRunRequest): MathTextRunProviderResult =
        MathTextRunProviderResult.Ready(shape(request))

    private fun shape(request: MathTextRunRequest): MeasuredMathRun {
        if (request.text.isEmpty()) return MeasuredMathRun(emptyList(), 0f, 0f, 0f, false)
        val font = font(request.fontSizePx)
        return try {
            val collector = TextRunCollector()
            shaper.shape(
                request.text,
                TrivialFontRunIterator(request.text, font),
                TrivialBidiRunIterator(request.text, 0),
                HbIcuScriptRunIterator(request.text),
                TrivialLanguageRunIterator(request.text, request.locale ?: "und"),
                ShapingOptions.DEFAULT,
                Float.MAX_VALUE,
                collector,
            )
            val glyphIds = collector.glyphIds.toShortArray()
            val widths = font.getWidths(glyphIds)
            val fallbackBounds = font.getBounds(glyphIds)
            val clusterBoundaries = (collector.clusters + request.text.length).distinct().sorted()
            val glyphs = glyphIds.indices.map { index ->
                val bounds = font.getPath(glyphIds[index])?.use { path ->
                    if (path.isEmpty) null else path.computeTightBounds()
                } ?: fallbackBounds[index]
                val cluster = collector.clusters[index]
                val nextCluster = clusterBoundaries.firstOrNull { it > cluster } ?: request.text.length
                val clusterRange = SourceRange(cluster, nextCluster)
                MeasuredMathGlyph(
                    glyphId = glyphIds[index].toUShort(),
                    x = collector.x[index],
                    baselineOffsetPx = collector.y[index],
                    advance = widths[index],
                    inkBounds = MathRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    textCluster = cluster,
                    faceId = faceId,
                    fontClass = null,
                    requestedWeight = request.requestedWeight,
                    resolvedWeight = resolvedWeight,
                    fallbackReason = null,
                    hostTextDecision = MathHostTextFaceDecision(
                        sourceRange = SourceRange(
                            request.sourceRange.start + cluster,
                            request.sourceRange.start + nextCluster,
                        ),
                        clusterRangeUtf16 = clusterRange,
                        hostRole = request.origin.name,
                        faceId = faceId,
                        fontKey = typeface.familyName,
                        requestedWeight = request.requestedWeight,
                        resolvedWeight = resolvedWeight,
                        selectionReason = "SameTypefaceAsReadmeBodyLatin",
                        substitutionReason = null,
                    ),
                )
            }
            MeasuredMathRun(
                glyphs = glyphs,
                width = maxOf(collector.advance, glyphs.maxOfOrNull { it.x + it.advance } ?: 0f),
                ascent = glyphs.maxOfOrNull {
                    -(it.inkBounds.top + it.baselineOffsetPx)
                }?.coerceAtLeast(0f) ?: 0f,
                descent = glyphs.maxOfOrNull {
                    it.inkBounds.bottom + it.baselineOffsetPx
                }?.coerceAtLeast(0f) ?: 0f,
                missingGlyph = glyphIds.any { it.toInt() == 0 },
                boundsSource = MathGlyphBoundsSource.Outline,
            )
        } finally {
            font.close()
        }
    }

    override fun replayFace(faceId: MathFaceId): SkiaReplayFace? =
        takeIf { faceId == this.faceId }

    override fun constructionFace(faceId: MathFaceId): SkiaMathFontFace? = null

    override fun font(fontSizePx: Float): Font = Font(typeface, fontSizePx).apply { isSubpixel = true }

    override fun glyphPath(glyphId: UShort, fontSizePx: Float) =
        font(fontSizePx).use { it.getPath(glyphId.toShort()) }

    override fun canReplayGlyph(glyphId: UShort): Boolean =
        glyphId.toInt() != 0 && glyphId.toInt() < typeface.glyphsCount

    override fun close() = shaper.close()

    private class TextRunCollector : RunHandler {
        val glyphIds = mutableListOf<Short>()
        val x = mutableListOf<Float>()
        val y = mutableListOf<Float>()
        val clusters = mutableListOf<Int>()
        var advance = 0f
        private var pen = 0f

        override fun beginLine() = Unit
        override fun runInfo(info: RunInfo?) = Unit
        override fun commitRunInfo() = Unit
        override fun runOffset(info: RunInfo?) = Point(pen, 0f)

        override fun commitRun(
            info: RunInfo?,
            glyphs: ShortArray?,
            positions: Array<Point?>?,
            clusters: IntArray?,
        ) {
            if (info == null || glyphs == null || positions == null) return
            glyphs.forEachIndexed { index, glyph ->
                glyphIds += glyph
                x += positions[index]?.x ?: pen
                y += positions[index]?.y ?: 0f
                this.clusters += clusters?.getOrElse(index) { 0 } ?: 0
            }
            pen += info.advanceX
            advance = pen
        }

        override fun commitLine() = Unit
    }
}

private sealed interface SampleChunk
private data class TextChunk(val text: String) : SampleChunk
private data class FormulaChunk(val source: String) : SampleChunk

private data class ParagraphLayout(
    val result: LayoutResult,
    val formulas: List<InlineFormula>,
    val formulaBoundaryGaps: List<TextRange>,
)

private data class InlineFormula(
    val source: String,
    val range: TextRange,
    val result: MathLayoutResult,
    val pieces: List<MathPiece>,
)

private data class MathPiece(
    val range: TextRange,
    val fragmentIndices: IntRange,
    val boxes: List<PositionedMathBox>,
    val trailingFragment: MathInlineFragment,
    val inlineObject: InlineObjectSpan,
)

private data class MathPieceGeometry(
    val boxes: List<PositionedMathBox>,
    val width: Float,
    val ascent: Float,
    val descent: Float,
)

private data class PositionedMathBox(val box: MathBox, val x: Float)

private data class InternalBreak(
    val formula: InlineFormula,
    val piece: MathPiece,
    val before: PositionedCluster,
    val after: PositionedCluster,
)
