package org.tiqian.math.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.core.MathHostTextRunId
import org.tiqian.math.core.MathRect
import org.tiqian.math.layout.MathHostTextBox
import org.tiqian.math.layout.MathHostTextBoxReplayCatalog
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.MathTextRunProviderResult
import org.tiqian.math.layout.MathTextRunRequest

/** Compose-owned replay catalog for opaque host text boxes. */
internal interface ComposeMathTextReplayCatalog : MathHostTextBoxReplayCatalog {
    fun textLayout(runId: MathHostTextRunId): TextLayoutResult?

    override fun canReplayHostTextBox(runId: MathHostTextRunId): Boolean = textLayout(runId) != null
}

internal fun Canvas.drawComposeMathTextRuns(
    provider: MathTextRunProvider?,
    boxes: List<PositionedBox>,
    formulaColor: Color,
) {
    val catalog = provider as? ComposeMathTextReplayCatalog ?: return
    val formulaArgb = formulaColor.toArgb()
    boxes.forEach { positioned ->
        positioned.box.hostTextRuns.forEach { run ->
            val layout = checkNotNull(catalog.textLayout(run.runId)) {
                "No Compose text replay for ${run.runId}"
            }
            val color = Color(run.paintColor?.modulatedArgb(formulaArgb) ?: formulaArgb)
            save()
            try {
                translate(
                    positioned.x + run.x,
                    positioned.baselineFromTop + run.baselineY - run.ascent,
                )
                layout.multiParagraph.paint(this, color = color)
            } finally {
                restore()
            }
        }
    }
}

@Composable
internal fun rememberComposeMathTextRunProvider(
    hostStyle: TextStyle,
    density: Density,
): MathTextRunProvider {
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    return remember(textMeasurer, hostStyle, density) {
        ComposeMathTextRunProvider(textMeasurer, hostStyle, density)
    }
}

private class ComposeMathTextRunProvider(
    private val textMeasurer: androidx.compose.ui.text.TextMeasurer,
    private val hostStyle: TextStyle,
    private val density: Density,
) : MathTextRunProvider, ComposeMathTextReplayCatalog {
    private val entries = LinkedHashMap<MathTextRunRequest, Entry>()
    private val layouts = mutableMapOf<MathHostTextRunId, TextLayoutResult>()
    private var nextId = 0L

    override fun shapeTextAtom(request: MathTextRunRequest): MathTextRunProviderResult {
        entries[request]?.let { return MathTextRunProviderResult.ReadyBox(it.box) }
        val requestedWeight = when (request.requestedWeight) {
            MathFontWeight.Regular -> FontWeight.Normal
            MathFontWeight.Bold -> FontWeight.Bold
        }
        val measuredStyle = hostStyle.copy(
            color = Color.Unspecified,
            fontSize = with(density) { request.fontSizePx.toSp() },
            fontWeight = requestedWeight,
            lineHeight = TextUnit.Unspecified,
        )
        val layout = textMeasurer.measure(
            text = request.text,
            style = measuredStyle,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(),
        )
        val width = layout.multiParagraph.width
        val ascent = layout.firstBaseline
        val descent = (layout.multiParagraph.height - ascent).coerceAtLeast(0f)
        val runId = MathHostTextRunId("compose-text-${nextId++}")
        val box = MathHostTextBox(
            runId = runId,
            width = width,
            ascent = ascent,
            descent = descent,
            inkBounds = MathRect(0f, -ascent, width, descent),
        )
        val entry = Entry(box, layout)
        entries[request] = entry
        layouts[runId] = layout
        trimCache()
        return MathTextRunProviderResult.ReadyBox(box)
    }

    override fun textLayout(runId: MathHostTextRunId): TextLayoutResult? = layouts[runId]

    private fun trimCache() {
        while (entries.size > MaximumEntries) {
            val eldest = entries.entries.first()
            entries.remove(eldest.key)
            layouts.remove(eldest.value.box.runId)
        }
    }

    private data class Entry(
        val box: MathHostTextBox,
        val layout: TextLayoutResult,
    )

    private companion object {
        const val MaximumEntries = 128
    }
}
