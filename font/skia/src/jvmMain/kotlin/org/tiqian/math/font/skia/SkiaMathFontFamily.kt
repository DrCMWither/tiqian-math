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
import org.tiqian.math.font.opentype.MathVerticalAssemblyPolicy
import org.tiqian.math.font.opentype.MathVerticalConstructionRequest
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
        val (face, reason) = owner.firstSuccessfulMathFace(requestedWeight) {
            it.resolveSymbol(request, fontSizePx).run
        }
        val resolved = face.resolveSymbol(request, fontSizePx)
        return resolved.copy(run = resolved.run.tag(requestedWeight, reason))
    }

    override fun resolveOperator(request: MathOperatorGlyphRequest, fontSizePx: Float): ResolvedMathOperator {
        val face = owner.mathFallbackOrder(requestedWeight).firstOrNull {
            it.operatorConstructionAvailable(request, fontSizePx)
        } ?: owner.mathFace(requestedWeight)
        val reason = if (face.resolvedWeight == requestedWeight) MathFontFallbackReason.RequestedFace
            else MathFontFallbackReason.MissingMathConstructionInRequestedWeight
        val resolved = face.resolveOperator(request, fontSizePx)
        return resolved.copy(run = resolved.run.tag(requestedWeight, reason))
    }

    override fun resolveSymbols(
        requests: List<MathSymbolGlyphRequest>,
        fontSizePx: Float,
    ): ResolvedMathSymbolRun {
        val (face, reason) = owner.firstSuccessfulMathFace(requestedWeight) {
            it.resolveSymbols(requests, fontSizePx).run
        }
        val resolved = face.resolveSymbols(requests, fontSizePx)
        return resolved.copy(run = resolved.run.tag(requestedWeight, reason))
    }

    override fun shape(
        text: String,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun {
        val (face, reason) = owner.firstSuccessfulMathFace(requestedWeight) {
            it.shape(text, fontSizePx, style, sourceRange)
        }
        return face.shape(text, fontSizePx, style, sourceRange).tag(requestedWeight, reason)
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
        val (face, reason) = owner.firstSuccessfulMathFace(requestedWeight) {
            it.shapeConstructionBase(text, fontSizePx, sourceRange)
        }
        return face.shapeConstructionBase(text, fontSizePx, sourceRange).tag(requestedWeight, reason)
    }

    override fun shapeOutlineConstructionBase(
        text: String, fontSizePx: Float, sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun {
        val (face, reason) = owner.firstSuccessfulMathFace(requestedWeight) {
            it.shapeOutlineConstructionBase(text, fontSizePx, sourceRange).run
        }
        return face.shapeOutlineConstructionBase(text, fontSizePx, sourceRange)
            .tag(requestedWeight, reason)
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

        fun firstSuccessfulMathFace(
            weight: MathFontWeight,
            measure: (SkiaMathFontFace) -> MeasuredMathRun,
        ): Pair<SkiaMathFontFace, MathFontFallbackReason> {
            val ordered = mathFallbackOrder(weight)
            ordered.forEach { face ->
                if (!measure(face).missingGlyph) {
                    return face to when {
                        face.resolvedWeight == weight -> MathFontFallbackReason.RequestedFace
                        hasMathWeight(weight) -> MathFontFallbackReason.MissingGlyphInRequestedWeight
                        else -> MathFontFallbackReason.RequestedWeightUnavailable
                    }
                }
            }
            return ordered.first() to if (hasMathWeight(weight)) {
                MathFontFallbackReason.RequestedFace
            } else {
                MathFontFallbackReason.RequestedWeightUnavailable
            }
        }

        override fun close() {
            mathFaces.values.forEach(SkiaMathFontFace::close)
        }
    }

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

        fun loadBundledLete(): SkiaMathFontFamily = fromSpec(
            MathFontFamilySpec(
                familyId = "lete-sans-math",
                fontClass = MathFontClass.SansSerif,
                faces = listOf(
                    MathFontFaceSpec(
                        MathFaceId("lete-sans-math-regular"),
                        LeteSansMath.loadBytes(),
                        MathFontClass.SansSerif,
                        MathFontWeight.Regular,
                    ),
                    MathFontFaceSpec(
                        MathFaceId("lete-sans-math-bold"),
                        LeteSansMath.loadBoldBytes(),
                        MathFontClass.SansSerif,
                        MathFontWeight.Bold,
                    ),
                ),
            ),
        )
    }
}

private fun MeasuredMathRun.tag(
    requestedWeight: MathFontWeight,
    reason: MathFontFallbackReason,
): MeasuredMathRun = copy(glyphs = glyphs.map { it.copy(requestedWeight = requestedWeight, fallbackReason = reason) })

private fun MeasuredOutlineConstructionRun.tag(
    requestedWeight: MathFontWeight,
    reason: MathFontFallbackReason,
): MeasuredOutlineConstructionRun = copy(run = run.tag(requestedWeight, reason))

private fun SkiaMathFontFace.operatorConstructionAvailable(
    request: MathOperatorGlyphRequest,
    fontSizePx: Float,
): Boolean {
    val resolved = resolveOperator(request, fontSizePx)
    if (resolved.run.missingGlyph) return false
    if (request.style.level != MathStyleLevel.Display) return true
    val glyphId = resolved.constructionBaseGlyphId ?: return false
    val target = mathFont.scaleDesignUnits(mathFont.constants.displayOperatorMinHeight, fontSizePx)
    val normal = measureGlyphOutlineBounds(glyphId, fontSizePx, request.style, request.sourceRange)
    return mathFont.verticalConstruction(
        MathVerticalConstructionRequest(
            glyphId,
            target,
            fontSizePx,
            normal.glyphs.maxOfOrNull { it.inkBounds.height } ?: normal.ascent + normal.descent,
            normal.width,
            MathVerticalAssemblyPolicy.MathMLCoreUniformOverlap,
        ),
        glyphVerticalExtentPx = { id ->
            measureGlyphOutlineBounds(id, fontSizePx, request.style, request.sourceRange)
                .glyphs.singleOrNull()?.inkBounds?.height ?: 0f
        },
    ) { id -> measureGlyph(id, fontSizePx, request.style, request.sourceRange).width }?.reachesTarget == true
}
