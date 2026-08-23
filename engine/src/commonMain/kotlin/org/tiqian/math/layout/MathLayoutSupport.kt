package org.tiqian.math.layout

import org.tiqian.math.core.*

internal fun MathConstructionOutlineEvidence.evidenceLabel(): String = when (this) {
    is MathConstructionOutlineEvidence.Available -> "Available($source)"
    is MathConstructionOutlineEvidence.Unavailable -> "Unavailable($reason)"
}

internal fun unicodeLabel(scalar: Int): String = "U+${scalar.toString(16).uppercase().padStart(4, '0')}"

internal const val RADICAL_SIGN = "\u221A"
internal const val AMSMATH_RELBAR = "\u2212"

internal fun scalarString(scalar: Int): String = if (scalar <= 0xFFFF) {
    scalar.toChar().toString()
} else {
    val adjusted = scalar - 0x10000
    charArrayOf(
        ((adjusted ushr 10) + 0xD800).toChar(),
        ((adjusted and 0x3FF) + 0xDC00).toChar(),
    ).concatToString()
}

internal enum class ScriptBaseKind {
    Character,
    CompoundBox,
    ExtendedShape,
}

internal fun MathBox.singleGlyphOrNull(): MathGlyphPlacement? =
    if (rules.isEmpty() && hostTextRuns.isEmpty() && glyphs.size == 1) glyphs.single() else null

internal fun MathBox.sideScriptVerticalMetrics(): SideScriptBoxVerticalMetrics =
    SideScriptBoxVerticalMetrics(
        logicalAscentPx = ascent,
        logicalDescentPx = descent,
        inkTopPx = inkBounds.top,
        inkBottomPx = inkBounds.bottom,
    )

internal fun MathBox.texCleanSideScriptVerticalMetrics(): SideScriptBoxVerticalMetrics =
    SideScriptBoxVerticalMetrics(
        logicalAscentPx = texCleanBoxMetrics.ascent,
        logicalDescentPx = texCleanBoxMetrics.descent,
        inkTopPx = -texCleanBoxMetrics.ascent,
        inkBottomPx = texCleanBoxMetrics.descent,
    )

internal fun MathBox.translated(dx: Float, dy: Float): MathBox = copy(
    inkBounds = inkBounds.translated(dx, dy),
    glyphs = glyphs.map { glyph ->
        glyph.copy(
            x = glyph.x + dx,
            baselineY = glyph.baselineY + dy,
            inkBounds = glyph.inkBounds.translated(dx, dy),
        )
    },
    rules = rules.map { rule ->
        rule.copy(
            left = rule.left + dx,
            right = rule.right + dx,
            top = rule.top + dy,
            bottom = rule.bottom + dy,
            lineSegment = rule.lineSegment?.let { line ->
                line.copy(
                    startX = line.startX + dx,
                    startY = line.startY + dy,
                    endX = line.endX + dx,
                    endY = line.endY + dy,
                )
            },
        )
    },
    hostTextRuns = hostTextRuns.map { run ->
        run.copy(
            x = run.x + dx,
            baselineY = run.baselineY + dy,
            inkBounds = run.inkBounds.translated(dx, dy),
        )
    },
)

/** Applies a surrounding color declaration without overwriting a nested declaration. */
internal fun MathBox.withInheritedPaintColor(color: MathPaintColor): MathBox = copy(
    glyphs = glyphs.map { glyph ->
        if (glyph.paintColor == null) glyph.copy(paintColor = color) else glyph
    },
    rules = rules.map { rule ->
        if (rule.paintColor == null) rule.copy(paintColor = color) else rule
    },
    constructionPaintGroups = constructionPaintGroups.map { group ->
        if (group.paintColor == null) group.copy(paintColor = color) else group
    },
    hostTextRuns = hostTextRuns.map { run ->
        if (run.paintColor == null) run.copy(paintColor = color) else run
    },
)

internal fun MathBox.withHorizontalKerns(left: Float, right: Float): MathBox {
    require(left >= 0f && right >= 0f) { "math limit kerns must not be negative" }
    val shifted = translated(left, 0f)
    return shifted.copy(width = left + width + right)
}
