package org.tiqian.math.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathBrokenLayout
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathFontFamilySpec
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathFormulaStrictException
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.breakIntoLines
import kotlin.math.ceil

/** Loads Tiqian's platform-native product-default Lete face and owns its native lifetime. */
@Composable
fun rememberLeteMathFontFace(): MathComposeFontFace = rememberPlatformLeteMathFontFace()

/** Loads one host-provided OpenType MATH font and keeps measurement and drawing on the same face. */
@Composable
fun rememberMathFontFace(fontBytes: ByteArray): MathComposeFontFace =
    rememberPlatformMathFontFace(fontBytes)

/** Loads a class-safe, weighted OpenType MATH family whose selected face ids survive into replay. */
@Composable
fun rememberMathFontFamily(spec: MathFontFamilySpec): MathComposeFontFace =
    rememberPlatformMathFontFamily(spec)

/** Stable measured presentation shared by an embedding paragraph and the math canvas. */
class TiqianMathFormula internal constructor(
    internal val resolved: ResolvedFormulaCapability,
) {
    val layoutResult: MathLayoutResult?
        get() = (resolved.capability as? MathFormulaCapabilityResult.Ready)?.layoutResult

    val failure: MathFormulaCapabilityResult.FallbackRequired?
        get() = resolved.capability as? MathFormulaCapabilityResult.FallbackRequired

    /**
     * Exact canvas geometry for the whole formula or one engine-owned inline fragment, embedded in
     * a host paragraph. The host owns line height, so this reports the formula's true ink extent
     * plus a small breathing [InlineInkLeadingEm] leading — NOT the math font's full line box.
     * Reporting the font box here propped up every host line that carried a formula even when its
     * ink (e.g. a plain polynomial) fit the body line; see the standalone [ReadyTiqianMath] path,
     * which still fills its own requested line height.
     */
    fun presentationMetrics(fragmentIndex: Int? = null): TiqianMathPresentationMetrics? {
        val result = layoutResult ?: return null
        val leadingPx = InlineInkLeadingEm * resolved.resolvedFontSizePx
        val plan = if (fragmentIndex == null) {
            RenderPlan.unbrokenInkTight(result, leadingPx)
        } else {
            RenderPlan.fragmentInkTight(result, fragmentIndex, leadingPx)
        }
        return TiqianMathPresentationMetrics(plan.width, plan.height, plan.firstBaseline)
    }

    /** Presentation metrics for a contiguous fragment group drawn as one inline unit. */
    fun presentationMetrics(fragmentIndices: IntRange): TiqianMathPresentationMetrics? {
        val result = layoutResult ?: return null
        val leadingPx = InlineInkLeadingEm * resolved.resolvedFontSizePx
        val plan = RenderPlan.fragmentRangeInkTight(result, fragmentIndices, leadingPx)
        return TiqianMathPresentationMetrics(plan.width, plan.height, plan.firstBaseline)
    }
}

data class TiqianMathPresentationMetrics(
    val widthPx: Float,
    val heightPx: Float,
    val baselineFromTopPx: Float,
)

/**
 * Measures and preflights a formula once for hosts that need its fragments in their own paragraph
 * layout. Passing the returned object to [TiqianMathFormulaCanvas] replays that exact result.
 */
@Composable
fun rememberTiqianMathFormula(
    source: String,
    mode: MathMode = MathMode.Inline,
    style: TextStyle = LocalTextStyle.current,
    fontSizePx: Float? = null,
    nullDelimiterSpacePx: Float? = null,
    scriptSpacePx: Float? = null,
    delimiterFactor: Int = 901,
    delimiterShortfallPx: Float? = null,
    color: Color = Color.Unspecified,
    fontFace: MathComposeFontFace? = null,
    textRunProvider: MathTextRunProvider? = null,
    textLocale: String? = null,
    /** Required by explicit equation tags when measuring outside a constrained Compose layout. */
    displayWidthPx: Float? = null,
): TiqianMathFormula = TiqianMathFormula(
    rememberResolvedFormulaCapability(
        source = source,
        mode = mode,
        style = style,
        fontSizePx = fontSizePx,
        nullDelimiterSpacePx = nullDelimiterSpacePx,
        scriptSpacePx = scriptSpacePx,
        delimiterFactor = delimiterFactor,
        delimiterShortfallPx = delimiterShortfallPx,
        color = color,
        fontFace = fontFace,
        textRunProvider = textRunProvider,
        textLocale = textLocale,
        displayWidthPx = displayWidthPx,
    ),
)

/** Replays the whole formula or one premeasured fragment without reparsing or reclassification. */
@Composable
fun TiqianMathFormulaCanvas(
    formula: TiqianMathFormula,
    modifier: Modifier = Modifier,
    fragmentIndex: Int? = null,
) {
    val result = formula.layoutResult ?: return
    // Draw with the same ink-tight geometry presentationMetrics measured, so host embedding keeps
    // measure and paint identical.
    val leadingPx = InlineInkLeadingEm * formula.resolved.resolvedFontSizePx
    val plan = if (fragmentIndex == null) {
        RenderPlan.unbrokenInkTight(result, leadingPx)
    } else {
        RenderPlan.fragmentInkTight(result, fragmentIndex, leadingPx)
    }
    FixedTiqianMathPlan(
        plan = plan,
        modifier = modifier,
        color = formula.resolved.color,
        face = formula.resolved.face,
        textRunProvider = formula.resolved.textRunProvider,
    )
}

/** Replays a contiguous group of premeasured fragments as one inline unit. */
@Composable
fun TiqianMathFormulaCanvas(
    formula: TiqianMathFormula,
    fragmentIndices: IntRange,
    modifier: Modifier = Modifier,
) {
    val result = formula.layoutResult ?: return
    val leadingPx = InlineInkLeadingEm * formula.resolved.resolvedFontSizePx
    FixedTiqianMathPlan(
        plan = RenderPlan.fragmentRangeInkTight(result, fragmentIndices, leadingPx),
        modifier = modifier,
        color = formula.resolved.color,
        face = formula.resolved.face,
        textRunProvider = formula.resolved.textRunProvider,
    )
}

/**
 * Normal production entry point. Accepted formulas use Tiqian's platform renderer; unsupported or
 * malformed input uses Tiqian's visible diagnostic presentation instead of requiring a second
 * formula renderer from the host.
 */
@Composable
fun TiqianMath(
    source: String,
    modifier: Modifier = Modifier,
    mode: MathMode = MathMode.Inline,
    style: TextStyle = LocalTextStyle.current,
    /** Compatibility override; prefer [style].fontSize so density and fontScale are honored. */
    fontSizePx: Float? = null,
    /** Explicit TeX `\nulldelimiterspace` in layout pixels; null retains the engine policy. */
    nullDelimiterSpacePx: Float? = null,
    /** Explicit TeX `\scriptspace` in layout pixels; null retains the font MATH constant. */
    scriptSpacePx: Float? = null,
    /** TeX `\delimiterfactor` used by content-driven `\left ... \right`. */
    delimiterFactor: Int = 901,
    /** Explicit TeX `\delimitershortfall` in layout pixels; null retains the engine policy. */
    delimiterShortfallPx: Float? = null,
    color: Color = Color.Unspecified,
    softWrap: Boolean = true,
    fontFace: MathComposeFontFace? = null,
    textRunProvider: MathTextRunProvider? = null,
    textLocale: String? = null,
    onMathLayout: (MathLayoutResult) -> Unit = {},
    onMathError: (MathFormulaCapabilityResult.FallbackRequired) -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier) {
        val resolved = rememberResolvedFormulaCapability(
            source,
            mode,
            style,
            fontSizePx,
            nullDelimiterSpacePx,
            scriptSpacePx,
            delimiterFactor,
            delimiterShortfallPx,
            color,
            fontFace,
            textRunProvider,
            textLocale,
            displayWidthPx = constraints.takeIf { mode == MathMode.Display && it.hasBoundedWidth }
                ?.maxWidth?.toFloat(),
        )
        FormulaCapabilityContent(
            resolved = resolved,
            modifier = Modifier,
            softWrap = softWrap,
            onMathLayout = onMathLayout,
            fallback = { failure ->
                LaunchedEffect(failure) { onMathError(failure) }
                TiqianMathError(failure, Modifier, style)
            },
        )
    }
}

/** Strict dogfood/CI entry: capability failures are deterministic before measure or draw. */
@Composable
fun StrictTiqianMath(
    source: String,
    modifier: Modifier = Modifier,
    mode: MathMode = MathMode.Inline,
    style: TextStyle = LocalTextStyle.current,
    fontSizePx: Float? = null,
    color: Color = Color.Unspecified,
    softWrap: Boolean = true,
    fontFace: MathComposeFontFace? = null,
    textRunProvider: MathTextRunProvider? = null,
    textLocale: String? = null,
    onMathLayout: (MathLayoutResult) -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier) {
        val resolved = rememberResolvedFormulaCapability(
            source,
            mode,
            style,
            fontSizePx,
            null,
            null,
            901,
            null,
            color,
            fontFace,
            textRunProvider,
            textLocale,
            displayWidthPx = constraints.takeIf { mode == MathMode.Display && it.hasBoundedWidth }
                ?.maxWidth?.toFloat(),
        )
        FormulaCapabilityContent(resolved, Modifier, softWrap, onMathLayout, fallback = null)
    }
}

/**
 * Optional migration boundary. A host may temporarily own [fallback], but this is not required by
 * Tiqian's steady-state renderer contract.
 */
@Composable
fun TiqianMathOrFallback(
    source: String,
    modifier: Modifier = Modifier,
    mode: MathMode = MathMode.Inline,
    style: TextStyle = LocalTextStyle.current,
    fontSizePx: Float? = null,
    nullDelimiterSpacePx: Float? = null,
    scriptSpacePx: Float? = null,
    delimiterFactor: Int = 901,
    delimiterShortfallPx: Float? = null,
    color: Color = Color.Unspecified,
    softWrap: Boolean = true,
    fontFace: MathComposeFontFace? = null,
    textRunProvider: MathTextRunProvider? = null,
    textLocale: String? = null,
    onMathLayout: (MathLayoutResult) -> Unit = {},
    fallback: @Composable (MathFormulaCapabilityResult.FallbackRequired) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val resolved = rememberResolvedFormulaCapability(
            source,
            mode,
            style,
            fontSizePx,
            nullDelimiterSpacePx,
            scriptSpacePx,
            delimiterFactor,
            delimiterShortfallPx,
            color,
            fontFace,
            textRunProvider,
            textLocale,
            displayWidthPx = constraints.takeIf { mode == MathMode.Display && it.hasBoundedWidth }
                ?.maxWidth?.toFloat(),
        )
        FormulaCapabilityContent(resolved, Modifier, softWrap, onMathLayout, fallback)
    }
}

/** Test seam: injects layout corruption before the real production preflight and branch. */
@Composable
internal fun TiqianMathCapabilityBoundaryForTest(
    source: String,
    fontFace: MathComposeFontFace,
    capabilityEngine: MathFormulaCapabilityEngine,
    strict: Boolean,
    modifier: Modifier = Modifier,
    fontSizePx: Float = 32f,
    onMathLayout: (MathLayoutResult) -> Unit = {},
    fallback: @Composable (MathFormulaCapabilityResult.FallbackRequired) -> Unit,
) {
    val resolved = rememberResolvedFormulaCapability(
        source,
        MathMode.Inline,
        LocalTextStyle.current,
        fontSizePx,
        null,
        null,
        901,
        null,
        Color.Black,
        fontFace,
        null,
        null,
        capabilityEngine,
        null,
    )
    FormulaCapabilityContent(
        resolved,
        modifier,
        softWrap = true,
        onMathLayout,
        fallback = if (strict) null else fallback,
    )
}

@Composable
private fun FormulaCapabilityContent(
    resolved: ResolvedFormulaCapability,
    modifier: Modifier,
    softWrap: Boolean,
    onMathLayout: (MathLayoutResult) -> Unit,
    fallback: (@Composable (MathFormulaCapabilityResult.FallbackRequired) -> Unit)?,
) {
    when (val capability = resolved.capability) {
        is MathFormulaCapabilityResult.Ready -> ReadyTiqianMath(
            capability.layoutResult,
            modifier,
            resolved.requestedLineHeightPx,
            resolved.color,
            softWrap,
            resolved.face,
            resolved.textRunProvider,
            onMathLayout,
        )
        is MathFormulaCapabilityResult.FallbackRequired -> {
            val errorPresentation = fallback ?: throw MathFormulaStrictException(capability)
            errorPresentation(capability)
        }
    }
}

@Composable
private fun TiqianMathError(
    failure: MathFormulaCapabilityResult.FallbackRequired,
    modifier: Modifier,
    style: TextStyle,
) {
    val label = failure.reasons.joinToString { it.category.name }
    BasicText(
        text = failure.source.ifEmpty { "∅" },
        modifier = modifier.semantics {
            contentDescription = "Math formula error: $label"
        },
        style = style.copy(
            color = if (style.color != Color.Unspecified) style.color else Color.Red,
        ),
    )
}

internal data class ResolvedFormulaCapability(
    val face: MathComposeFontFace,
    val textRunProvider: MathTextRunProvider?,
    val capability: MathFormulaCapabilityResult,
    val requestedLineHeightPx: Float?,
    val resolvedFontSizePx: Float,
    val color: Color,
)

/**
 * Breathing leading (per edge, in em) added to a formula's ink extent when it is embedded inline in
 * a host paragraph, so a superscript's ink top does not touch the line above without paying for the
 * math font's full line box on every formula-bearing line.
 */
private const val InlineInkLeadingEm = 0.05f

@Composable
private fun rememberResolvedFormulaCapability(
    source: String,
    mode: MathMode,
    style: TextStyle,
    fontSizePx: Float?,
    nullDelimiterSpacePx: Float?,
    scriptSpacePx: Float?,
    delimiterFactor: Int,
    delimiterShortfallPx: Float?,
    color: Color,
    fontFace: MathComposeFontFace?,
    textRunProvider: MathTextRunProvider?,
    textLocale: String?,
    capabilityEngineOverride: MathFormulaCapabilityEngine? = null,
    displayWidthPx: Float? = null,
): ResolvedFormulaCapability {
    val density = LocalDensity.current
    val resolvedFontSizePx = fontSizePx ?: with(density) {
        (if (style.fontSize.isSpecified) style.fontSize else DefaultMathFontSize).toPx()
    }
    val requestedLineHeightPx = if (style.lineHeight.isSpecified) {
        with(density) { style.lineHeight.toPx() }
    } else {
        null
    }
    val composeTextRunProvider = rememberComposeMathTextRunProvider(style, density)
    val resolvedTextRunProvider = textRunProvider ?: composeTextRunProvider
    val resolvedColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> Color.Black
    }
    val defaultFace = if (fontFace == null) rememberPlatformLeteMathFontFace() else null
    val familyFace = fontFace ?: checkNotNull(defaultFace)
    val requestedWeight = MathFontWeight.nearest(style.fontWeight?.weight ?: MathFontWeight.Regular.cssWeight)
    val resolvedFace = remember(familyFace, requestedWeight) {
        familyFace.selectWeight(requestedWeight) as? MathComposeFontFace
            ?: error("Selected math weight is not Compose-replayable")
    }
    val defaultCapabilityEngine = remember(resolvedFace, resolvedTextRunProvider) {
        platformFormulaCapabilityEngine(resolvedFace, resolvedTextRunProvider)
    }
    val capabilityEngine = capabilityEngineOverride ?: defaultCapabilityEngine
    val capability = remember(
        source,
        mode,
        resolvedFontSizePx,
        nullDelimiterSpacePx,
        scriptSpacePx,
        delimiterFactor,
        delimiterShortfallPx,
        textLocale,
        displayWidthPx,
        capabilityEngine,
    ) {
        capabilityEngine.evaluate(
            source,
            MathLayoutOptions(
                mode = mode,
                fontSizePx = resolvedFontSizePx,
                nullDelimiterSpacePx = nullDelimiterSpacePx,
                scriptSpacePx = scriptSpacePx,
                delimiterFactor = delimiterFactor,
                delimiterShortfallPx = delimiterShortfallPx,
                textLocale = textLocale,
                displayWidthPx = displayWidthPx,
            ),
        )
    }
    return ResolvedFormulaCapability(
        resolvedFace,
        resolvedTextRunProvider,
        capability,
        requestedLineHeightPx,
        resolvedFontSizePx,
        resolvedColor,
    )
}

@Composable
private fun ReadyTiqianMath(
    result: MathLayoutResult,
    modifier: Modifier,
    requestedLineHeightPx: Float?,
    color: Color,
    softWrap: Boolean,
    face: MathComposeFontFace,
    textRunProvider: MathTextRunProvider?,
    onMathLayout: (MathLayoutResult) -> Unit,
) {
    SideEffect { onMathLayout(result) }

    var renderPlan = RenderPlan.unbroken(result, requestedLineHeightPx)
    Layout(
        modifier = modifier,
        content = {
            Canvas(Modifier.fillMaxSize()) {
                drawPlatformMathPlan(face, textRunProvider, renderPlan, color)
            }
        },
    ) { measurables, constraints ->
        val broken = if (softWrap && constraints.hasBoundedWidth && result.fragments.size > 1) {
            result.breakIntoLines(constraints.maxWidth.toFloat().coerceAtLeast(1f))
        } else {
            null
        }
        renderPlan = if (broken != null) {
            RenderPlan.broken(result, broken, requestedLineHeightPx)
        } else {
            RenderPlan.unbroken(result, requestedLineHeightPx)
        }
        val width = ceil(renderPlan.width).toInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = ceil(renderPlan.height).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val child = measurables.single().measure(Constraints.fixed(width, height))
        layout(
            width,
            height,
            alignmentLines = mapOf(FirstBaseline to renderPlan.firstBaseline.toInt()),
        ) {
            child.place(0, 0)
        }
    }
}

@Composable
private fun FixedTiqianMathPlan(
    plan: RenderPlan,
    modifier: Modifier,
    color: Color,
    face: MathComposeFontFace,
    textRunProvider: MathTextRunProvider?,
) {
    Layout(
        modifier = modifier,
        content = {
            Canvas(Modifier.fillMaxSize()) {
                drawPlatformMathPlan(face, textRunProvider, plan, color)
            }
        },
    ) { measurables, constraints ->
        val width = ceil(plan.width).toInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = ceil(plan.height).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val child = measurables.single().measure(Constraints.fixed(width, height))
        layout(
            width,
            height,
            alignmentLines = mapOf(FirstBaseline to plan.firstBaseline.toInt()),
        ) {
            child.place(0, 0)
        }
    }
}

internal data class PositionedBox(
    val box: MathBox,
    val x: Float,
    val baselineFromTop: Float,
)

internal data class RenderPlan(
    val boxes: List<PositionedBox>,
    val width: Float,
    val height: Float,
    val firstBaseline: Float,
) {
    companion object {
        fun unbroken(result: MathLayoutResult, minimumLineHeightPx: Float? = null): RenderPlan {
            val metrics = result.lineMetrics.forInk(result.box.ascent, result.box.descent)
            val extraLeading = ((minimumLineHeightPx ?: 0f) - metrics.logicalHeightPx).coerceAtLeast(0f)
            val topLeading = extraLeading / 2f
            return RenderPlan(
                boxes = listOf(
                    PositionedBox(
                        result.box,
                        -result.box.visualLeft,
                        metrics.logicalAscentPx + topLeading,
                    ),
                ),
                width = result.box.visualWidth,
                height = metrics.logicalHeightPx + extraLeading,
                firstBaseline = metrics.logicalAscentPx + topLeading,
            )
        }

        fun fragment(
            result: MathLayoutResult,
            fragmentIndex: Int,
            minimumLineHeightPx: Float? = null,
        ): RenderPlan {
            val fragment = result.fragments[fragmentIndex]
            val metrics = result.lineMetrics.forInk(fragment.box.ascent, fragment.box.descent)
            val extraLeading = ((minimumLineHeightPx ?: 0f) - metrics.logicalHeightPx).coerceAtLeast(0f)
            val topLeading = extraLeading / 2f
            val logicalLeft = fragment.leadingKernPx
            val visualLeft = minOf(0f, logicalLeft + fragment.box.inkBounds.left)
            val logicalRight = logicalLeft + fragment.box.width + fragment.trailingAdvancePx
            val visualRight = maxOf(logicalRight, logicalLeft + fragment.box.inkBounds.right)
            return RenderPlan(
                boxes = listOf(
                    PositionedBox(
                        fragment.box,
                        logicalLeft - visualLeft,
                        metrics.logicalAscentPx + topLeading,
                    ),
                ),
                width = visualRight - visualLeft,
                height = metrics.logicalHeightPx + extraLeading,
                firstBaseline = metrics.logicalAscentPx + topLeading,
            )
        }

        /**
         * Host-embedding geometry for the whole formula: vertical extent is the box's true ink plus
         * [leadingPx] on each edge, so the host line grows only for real ink (tall superscripts,
         * fractions, big operators), never for the math font's declared line box.
         */
        fun unbrokenInkTight(result: MathLayoutResult, leadingPx: Float): RenderPlan =
            inkTight(result.box, result.box.visualWidth, leadingPx)

        /** Host-embedding geometry for one inline fragment; see [unbrokenInkTight]. */
        fun fragmentInkTight(result: MathLayoutResult, fragmentIndex: Int, leadingPx: Float): RenderPlan {
            val fragment = result.fragments[fragmentIndex]
            val logicalLeft = fragment.leadingKernPx
            val visualLeft = minOf(0f, logicalLeft + fragment.box.inkBounds.left)
            val logicalRight = logicalLeft + fragment.box.width + fragment.trailingAdvancePx
            val visualRight = maxOf(logicalRight, logicalLeft + fragment.box.inkBounds.right)
            return inkTight(
                fragment.box,
                visualRight - visualLeft,
                leadingPx,
                x = logicalLeft - visualLeft,
            )
        }

        /**
         * Host-embedding geometry for a contiguous group of fragments drawn as one inline unit. The
         * per-fragment boxes keep their own geometry (the engine's fragment model is unchanged); this
         * only assembles them side by side with their interior glue for a consumer that binds
         * delimiters/punctuation into one object. See [unbrokenInkTight].
         */
        fun fragmentRangeInkTight(result: MathLayoutResult, range: IntRange, leadingPx: Float): RenderPlan {
            if (range.first == range.last) return fragmentInkTight(result, range.first, leadingPx)
            var logicalX = 0f
            var visualLeft = 0f
            var visualRight = 0f
            var inkTop = 0f
            var inkBottom = 0f
            val placed = ArrayList<Pair<MathBox, Float>>()
            for (index in range) {
                val fragment = result.fragments[index]
                logicalX += fragment.leadingKernPx
                placed += fragment.box to logicalX
                visualLeft = minOf(visualLeft, logicalX + fragment.box.inkBounds.left)
                visualRight = maxOf(visualRight, logicalX + fragment.box.inkBounds.right)
                inkTop = minOf(inkTop, fragment.box.inkBounds.top)
                inkBottom = maxOf(inkBottom, fragment.box.inkBounds.bottom)
                logicalX += fragment.box.width + fragment.trailingAdvancePx
                visualRight = maxOf(visualRight, logicalX)
            }
            val ascent = (-inkTop).coerceAtLeast(0f) + leadingPx
            val descent = inkBottom.coerceAtLeast(0f) + leadingPx
            return RenderPlan(
                boxes = placed.map { (box, x) -> PositionedBox(box, x - visualLeft, ascent) },
                width = visualRight - visualLeft,
                height = ascent + descent,
                firstBaseline = ascent,
            )
        }

        private fun inkTight(box: MathBox, width: Float, leadingPx: Float, x: Float = -box.visualLeft): RenderPlan {
            val ascent = (-box.inkBounds.top).coerceAtLeast(0f) + leadingPx
            val descent = box.inkBounds.bottom.coerceAtLeast(0f) + leadingPx
            return RenderPlan(
                boxes = listOf(PositionedBox(box, x, ascent)),
                width = width,
                height = ascent + descent,
                firstBaseline = ascent,
            )
        }

        fun broken(
            result: MathLayoutResult,
            broken: MathBrokenLayout,
            minimumLineHeightPx: Float? = null,
        ): RenderPlan {
            var top = 0f
            var firstBaseline = 0f
            val boxes = broken.lines.flatMapIndexed { lineIndex, line ->
                val intrinsicHeight = line.ascent + line.descent
                val extraLeading = ((minimumLineHeightPx ?: 0f) - intrinsicHeight).coerceAtLeast(0f)
                val baseline = top + line.ascent + extraLeading / 2f
                if (lineIndex == 0) firstBaseline = baseline
                top += intrinsicHeight + extraLeading
                line.fragments.map { placement ->
                    PositionedBox(
                        box = result.fragments[placement.fragmentIndex].box,
                        x = -line.visualLeft + placement.x,
                        baselineFromTop = baseline,
                    )
                }
            }
            return RenderPlan(boxes, broken.width, top, firstBaseline)
        }
    }
}

private val DefaultMathFontSize = 24.sp
