package org.tiqian.math.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
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
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathFormulaStrictException
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.breakIntoLines
import kotlin.math.ceil

/** Loads Tiqian's platform-native product-default Lete face and owns its native lifetime. */
@Composable
fun rememberLeteMathFontFace(): MathComposeFontFace = rememberPlatformLeteMathFontFace()

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
    onMathLayout: (MathLayoutResult) -> Unit = {},
    onMathError: (MathFormulaCapabilityResult.FallbackRequired) -> Unit = {},
) {
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
    )
    FormulaCapabilityContent(
        resolved = resolved,
        modifier = modifier,
        softWrap = softWrap,
        onMathLayout = onMathLayout,
        fallback = { failure ->
            LaunchedEffect(failure) { onMathError(failure) }
            TiqianMathError(failure, modifier, style)
        },
    )
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
    onMathLayout: (MathLayoutResult) -> Unit = {},
) {
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
    )
    FormulaCapabilityContent(resolved, modifier, softWrap, onMathLayout, fallback = null)
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
    onMathLayout: (MathLayoutResult) -> Unit = {},
    fallback: @Composable (MathFormulaCapabilityResult.FallbackRequired) -> Unit,
) {
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
    )
    FormulaCapabilityContent(resolved, modifier, softWrap, onMathLayout, fallback)
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
        capabilityEngine,
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

private data class ResolvedFormulaCapability(
    val face: MathComposeFontFace,
    val capability: MathFormulaCapabilityResult,
    val requestedLineHeightPx: Float?,
    val color: Color,
)

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
    capabilityEngineOverride: MathFormulaCapabilityEngine? = null,
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
    val resolvedColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> Color.Black
    }
    val defaultFace = if (fontFace == null) rememberPlatformLeteMathFontFace() else null
    val resolvedFace = fontFace ?: checkNotNull(defaultFace)
    val defaultCapabilityEngine = remember(resolvedFace) {
        platformFormulaCapabilityEngine(resolvedFace)
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
            ),
        )
    }
    return ResolvedFormulaCapability(
        resolvedFace,
        capability,
        requestedLineHeightPx,
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
    onMathLayout: (MathLayoutResult) -> Unit,
) {
    SideEffect { onMathLayout(result) }

    var renderPlan = RenderPlan.unbroken(result, requestedLineHeightPx)
    Layout(
        modifier = modifier,
        content = {
            Canvas(Modifier.fillMaxSize()) {
                drawPlatformMathPlan(face, renderPlan, color)
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
