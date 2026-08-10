package org.tiqian.math.font.skia

import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Path
import org.jetbrains.skia.Point
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.shaper.RunHandler
import org.jetbrains.skia.shaper.RunInfo
import org.jetbrains.skia.shaper.Shaper
import org.jetbrains.skia.shaper.ShapingOptions
import org.jetbrains.skia.shaper.TrivialBidiRunIterator
import org.jetbrains.skia.shaper.TrivialFontRunIterator
import org.jetbrains.skia.shaper.TrivialLanguageRunIterator
import org.jetbrains.skia.shaper.TrivialScriptRunIterator
import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.OpenTypeMathReader
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.layout.*
import kotlin.math.max

/** Desktop implementation of one class-safe, replayable OpenType MATH family. */
class SkiaMathFontFamily private constructor(
    private val owner: Owner,
    override val requestedWeight: MathFontWeight,
) : MathComposeFontFace, SkiaReplayCatalog, AutoCloseable {
    private val primary: SkiaMathFontFace = owner.mathFace(requestedWeight)

    override val mathFont get() = primary.mathFont
    override val faceId get() = primary.faceId
    override val fontClass get() = owner.fontClass
    override val resolvedWeight get() = primary.resolvedWeight

    override fun selectWeight(weight: MathFontWeight): MathFontFace =
        if (weight == requestedWeight) this else SkiaMathFontFamily(owner, weight)

    override fun mathFontFor(faceId: MathFaceId) = owner.mathFaces.getValue(faceId).mathFont
    override fun mathFontForOrNull(faceId: MathFaceId) = owner.mathFaces[faceId]?.mathFont

    override fun resolveSymbol(request: MathSymbolGlyphRequest, fontSizePx: Float): ResolvedMathSymbol {
        val selected = owner.firstSuccessfulMathFace(
            requestedWeight,
            resolve = { it.resolveSymbol(request, fontSizePx) },
            run = { it.run },
        )
        return selected.value.copy(run = selected.value.run.tag(requestedWeight, selected.reason))
    }

    override fun resolveOperator(request: MathOperatorGlyphRequest, fontSizePx: Float): ResolvedMathOperator {
        val selected = owner.firstSuccessfulMathFace(
            requestedWeight,
            resolve = { it.resolveOperator(request, fontSizePx) },
            run = { it.run },
        )
        val reason = if (selected.face.resolvedWeight == requestedWeight) MathFontFallbackReason.RequestedFace
            else MathFontFallbackReason.MissingMathConstructionInRequestedWeight
        return selected.value.copy(run = selected.value.run.tag(requestedWeight, reason))
    }

    override fun resolveSymbols(
        requests: List<MathSymbolGlyphRequest>,
        fontSizePx: Float,
    ): ResolvedMathSymbolRun {
        val selected = owner.firstSuccessfulMathFace(
            requestedWeight,
            resolve = { it.resolveSymbols(requests, fontSizePx) },
            run = { it.run },
        )
        return selected.value.copy(run = selected.value.run.tag(requestedWeight, selected.reason))
    }

    override fun shape(
        text: String,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun {
        val selected = owner.firstSuccessfulMathFace(
            requestedWeight,
            resolve = { it.shape(text, fontSizePx, style, sourceRange) },
            run = { it },
        )
        return selected.value.tag(requestedWeight, selected.reason)
    }

    override fun measureGlyph(glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange) =
        primary.measureGlyph(glyphId, fontSizePx, style, sourceRange).tag(
            requestedWeight,
            if (resolvedWeight == requestedWeight) MathFontFallbackReason.RequestedFace
            else MathFontFallbackReason.RequestedWeightUnavailable,
        )

    override fun measureGlyphForFace(
        faceId: MathFaceId,
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ) = owner.mathFaces.getValue(faceId).measureGlyph(glyphId, fontSizePx, style, sourceRange)
        .tag(requestedWeight, fallbackReason(owner.mathFaces.getValue(faceId)))

    override fun measureGlyphOutlineBounds(
        glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange,
    ) = primary.measureGlyphOutlineBounds(glyphId, fontSizePx, style, sourceRange)
        .tag(requestedWeight, fallbackReason(primary))

    override fun measureGlyphOutlineBoundsForFace(
        faceId: MathFaceId,
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ) = owner.mathFaces.getValue(faceId).measureGlyphOutlineBounds(glyphId, fontSizePx, style, sourceRange)
        .tag(requestedWeight, fallbackReason(owner.mathFaces.getValue(faceId)))

    override fun measureOutlineConstructionGlyph(
        glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange,
    ) = primary.measureOutlineConstructionGlyph(glyphId, fontSizePx, style, sourceRange)
        .tag(requestedWeight, fallbackReason(primary))

    override fun measureOutlineConstructionGlyphForFace(
        faceId: MathFaceId,
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ) = owner.mathFaces.getValue(faceId).measureOutlineConstructionGlyph(glyphId, fontSizePx, style, sourceRange)
        .tag(requestedWeight, fallbackReason(owner.mathFaces.getValue(faceId)))

    override fun shapeConstructionBase(text: String, fontSizePx: Float, sourceRange: SourceRange): MeasuredMathRun {
        val selected = owner.firstSuccessfulMathFace(
            requestedWeight,
            resolve = { it.shapeConstructionBase(text, fontSizePx, sourceRange) },
            run = { it },
        )
        return selected.value.tag(requestedWeight, selected.reason)
    }

    override fun shapeOutlineConstructionBase(
        text: String, fontSizePx: Float, sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun {
        val selected = owner.firstSuccessfulMathFace(
            requestedWeight,
            resolve = { it.shapeOutlineConstructionBase(text, fontSizePx, sourceRange) },
            run = { it.run },
        )
        return selected.value.tag(requestedWeight, selected.reason)
    }

    override fun shapeOutlineConstructionBaseCandidates(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): List<MeasuredOutlineConstructionRun> = owner.mathFallbackOrder(requestedWeight).mapIndexed { index, face ->
        face.shapeOutlineConstructionBase(text, fontSizePx, sourceRange).tag(
            requestedWeight,
            when {
                index == 0 && face.resolvedWeight == requestedWeight -> MathFontFallbackReason.RequestedFace
                index == 0 -> MathFontFallbackReason.RequestedWeightUnavailable
                else -> MathFontFallbackReason.MissingMathConstructionInRequestedWeight
            },
        )
    }

    override fun replayFace(faceId: MathFaceId): SkiaReplayFace? = owner.mathFaces[faceId]

    override fun constructionFace(faceId: MathFaceId): SkiaMathFontFace? = owner.mathFaces[faceId]

    override fun close() = owner.close()

    private fun fallbackReason(face: SkiaMathFontFace): MathFontFallbackReason = when {
        face.resolvedWeight == requestedWeight -> MathFontFallbackReason.RequestedFace
        owner.hasMathWeight(requestedWeight) -> MathFontFallbackReason.MissingGlyphInRequestedWeight
        else -> MathFontFallbackReason.RequestedWeightUnavailable
    }

    private class Owner(
        val fontClass: MathFontClass,
        val mathFaces: Map<MathFaceId, SkiaMathFontFace>,
    ) : AutoCloseable {
        fun mathFace(weight: MathFontWeight): SkiaMathFontFace =
            mathFaces.values.minBy { kotlin.math.abs(it.resolvedWeight.cssWeight - weight.cssWeight) }

        fun hasMathWeight(weight: MathFontWeight) = mathFaces.values.any { it.resolvedWeight == weight }

        fun mathFallbackOrder(weight: MathFontWeight): List<SkiaMathFontFace> =
            mathFaces.values.sortedBy { kotlin.math.abs(it.resolvedWeight.cssWeight - weight.cssWeight) }

        fun <T> firstSuccessfulMathFace(
            weight: MathFontWeight,
            resolve: (SkiaMathFontFace) -> T,
            run: (T) -> MeasuredMathRun,
        ): SelectedMathFace<T> {
            val ordered = mathFallbackOrder(weight)
            var first: SelectedMathFace<T>? = null
            ordered.forEach { face ->
                val resolved = resolve(face)
                val selected = SelectedMathFace(face, resolved, reason(face, weight))
                if (first == null) first = selected
                if (!run(resolved).missingGlyph) return selected
            }
            return checkNotNull(first)
        }

        fun reason(face: SkiaMathFontFace, weight: MathFontWeight): MathFontFallbackReason = when {
            face.resolvedWeight == weight -> MathFontFallbackReason.RequestedFace
            hasMathWeight(weight) -> MathFontFallbackReason.MissingGlyphInRequestedWeight
            else -> MathFontFallbackReason.RequestedWeightUnavailable
        }

        override fun close() {
            mathFaces.values.forEach(SkiaMathFontFace::close)
        }
    }

    private data class SelectedMathFace<T>(
        val face: SkiaMathFontFace,
        val value: T,
        val reason: MathFontFallbackReason,
    )

    companion object {
        fun fromSpec(spec: MathFontFamilySpec): SkiaMathFontFamily {
            val mathFaces = spec.faces.associate { face ->
                face.faceId to SkiaMathFontFace(
                    OpenTypeMathReader().read(face.fontBytes),
                    face.faceId,
                    face.fontClass,
                    face.weight,
                    face.weight,
                )
            }
            return SkiaMathFontFamily(Owner(spec.fontClass, mathFaces), MathFontWeight.Regular)
        }

        fun loadBundledLete(): SkiaMathFontFamily {
            val faces = listOf(
                SkiaMathFontFace(
                    LeteSansMath.load(), MathFaceId("lete-sans-math-regular"),
                    MathFontClass.SansSerif, MathFontWeight.Regular, MathFontWeight.Regular,
                ),
                SkiaMathFontFace(
                    LeteSansMath.loadBold(), MathFaceId("lete-sans-math-bold"),
                    MathFontClass.SansSerif, MathFontWeight.Bold, MathFontWeight.Bold,
                ),
            ).associateBy { it.faceId }
            return SkiaMathFontFamily(Owner(MathFontClass.SansSerif, faces), MathFontWeight.Regular)
        }
    }
}

private fun MeasuredMathRun.tag(
    requestedWeight: MathFontWeight,
    reason: MathFontFallbackReason,
): MeasuredMathRun {
    if (glyphs.all { it.requestedWeight == requestedWeight && it.fallbackReason == reason }) return this
    return copy(glyphs = glyphs.map { it.copy(requestedWeight = requestedWeight, fallbackReason = reason) })
}

private fun MeasuredOutlineConstructionRun.tag(
    requestedWeight: MathFontWeight,
    reason: MathFontFallbackReason,
): MeasuredOutlineConstructionRun = copy(run = run.tag(requestedWeight, reason))
