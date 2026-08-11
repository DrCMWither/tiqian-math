package org.tiqian.math.layout

import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.core.MathHostTextRunId
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathTextOrigin
import org.tiqian.math.core.SourceRange

data class MathTextRunRequest(
    val text: String,
    val sourceRange: SourceRange,
    val fontSizePx: Float,
    val requestedWeight: MathFontWeight,
    val locale: String? = null,
    val origin: MathTextOrigin,
)

/**
 * Advanced host-text boundary for one already-classified upright text atom. A provider may return
 * either a glyph-level [MathTextRunProviderResult.Ready] result or an opaque replayable
 * [MathTextRunProviderResult.ReadyBox]. TeX style selection and placement stay in layout.
 * Compose applications normally use the frontend's automatic text-box provider.
 */
fun interface MathTextRunProvider {
    fun shapeTextAtom(request: MathTextRunRequest): MathTextRunProviderResult
}

/** Replay availability for providers that return opaque [MathHostTextBox] results. */
interface MathHostTextBoxReplayCatalog {
    fun canReplayHostTextBox(runId: MathHostTextRunId): Boolean
}

sealed interface MathTextRunProviderResult {
    data class Ready(val run: MeasuredMathRun) : MathTextRunProviderResult
    data class ReadyBox(val box: MathHostTextBox) : MathTextRunProviderResult
    data class CapabilityIssue(
        val issue: org.tiqian.math.core.MathHostTextCapabilityIssue,
    ) : MathTextRunProviderResult
}

/**
 * Host-owned single-line text layout retained by an opaque replay id. The same host backend that
 * measured this box must replay that id; the math core never needs its internal glyph list.
 */
data class MathHostTextBox(
    val runId: MathHostTextRunId,
    val width: Float,
    val ascent: Float,
    val descent: Float,
    /** Bounds relative to the run baseline. */
    val inkBounds: MathRect,
)

/**
 * Conservative capability gate shared by the explicit standalone adapters. A real host adapter
 * is not subject to this gate: it may return fully bidi-resolved, multi-face replayable glyphs.
 */
fun restrictedStandaloneTextCapabilityIssue(
    request: MathTextRunRequest,
): org.tiqian.math.core.MathHostTextCapabilityIssue? {
    var index = 0
    while (index < request.text.length) {
        val first = request.text[index].code
        val scalar = if (first in 0xD800..0xDBFF && index + 1 < request.text.length) {
            val second = request.text[index + 1].code
            if (second in 0xDC00..0xDFFF) {
                0x10000 + ((first - 0xD800) shl 10) + (second - 0xDC00)
            } else first
        } else first
        val width = if (scalar > 0xFFFF) 2 else 1
        val issueCode = when {
            scalar in 0x0590..0x08FF || scalar in 0xFB1D..0xFDFF ||
                scalar in 0xFE70..0xFEFF || scalar in 0x10800..0x10FFF ||
                scalar in 0x200E..0x200F || scalar in 0x202A..0x202E ||
                scalar in 0x2066..0x2069 ->
                org.tiqian.math.core.MathHostTextCapabilityIssueCode.UnsupportedBidirectionalText

            scalar in 0x0900..0x109F || scalar in 0x1780..0x17FF ||
                scalar in 0x1A00..0x1CFF || scalar in 0xA800..0xABFF ->
                org.tiqian.math.core.MathHostTextCapabilityIssueCode.UnsupportedComplexScript

            else -> null
        }
        if (issueCode != null) {
            val kind = if (issueCode == org.tiqian.math.core.MathHostTextCapabilityIssueCode.UnsupportedBidirectionalText) {
                "bidirectional/RTL"
            } else {
                "complex-script"
            }
            return org.tiqian.math.core.MathHostTextCapabilityIssue(
                code = issueCode,
                message = "The explicit single-face standalone text provider cannot safely shape $kind text; inject a host MathTextRunProvider",
                sourceRange = SourceRange(
                    request.sourceRange.start + index,
                    request.sourceRange.start + index + width,
                ),
            )
        }
        index += width
    }
    return null
}
