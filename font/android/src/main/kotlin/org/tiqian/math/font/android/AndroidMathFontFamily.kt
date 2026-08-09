package org.tiqian.math.font.android

import android.content.Context
import android.graphics.Path
import org.tiqian.math.core.*
import org.tiqian.math.layout.*
import kotlin.math.abs
import kotlin.math.max
import org.tiqian.math.font.opentype.MathVerticalAssemblyPolicy
import org.tiqian.math.font.opentype.MathVerticalConstructionRequest

/** API 23+ HarfBuzz/FreeType family: every placement keeps the native face that produced it. */
class AndroidMathFontFamily private constructor(
    private val owner: Owner,
    override val requestedWeight: MathFontWeight,
) : MathComposeFontFace, AndroidReplayCatalog, AutoCloseable {
    private val primary get() = owner.mathFace(requestedWeight)
    override val mathFont get() = primary.mathFont
    override val faceId get() = primary.faceId
    override val fontClass get() = owner.fontClass
    override val resolvedWeight get() = primary.resolvedWeight

    override fun selectWeight(weight: MathFontWeight): MathFontFace =
        if (weight == requestedWeight) this else AndroidMathFontFamily(owner, weight)

    override fun mathFontFor(faceId: MathFaceId) = owner.mathFaces.getValue(faceId).mathFont
    override fun mathFontForOrNull(faceId: MathFaceId) = owner.mathFaces[faceId]?.mathFont

    override fun resolveSymbol(request: MathSymbolGlyphRequest, fontSizePx: Float): ResolvedMathSymbol {
        val selected = owner.firstSuccessful(requestedWeight) { it.resolveSymbol(request, fontSizePx).run }
        val resolved = selected.face.resolveSymbol(request, fontSizePx)
        return resolved.copy(run = resolved.run.withFaceDecision(requestedWeight, selected.reason))
    }

    override fun resolveOperator(request: MathOperatorGlyphRequest, fontSizePx: Float): ResolvedMathOperator {
        val face = owner.ordered(requestedWeight).firstOrNull {
            it.operatorConstructionAvailable(request, fontSizePx)
        } ?: owner.mathFace(requestedWeight)
        val reason = if (face.resolvedWeight == requestedWeight) MathFontFallbackReason.RequestedFace
            else MathFontFallbackReason.MissingMathConstructionInRequestedWeight
        val resolved = face.resolveOperator(request, fontSizePx)
        return resolved.copy(run = resolved.run.withFaceDecision(requestedWeight, reason))
    }

    override fun resolveSymbols(requests: List<MathSymbolGlyphRequest>, fontSizePx: Float): ResolvedMathSymbolRun {
        val selected = owner.firstSuccessful(requestedWeight) { it.resolveSymbols(requests, fontSizePx).run }
        val resolved = selected.face.resolveSymbols(requests, fontSizePx)
        return resolved.copy(run = resolved.run.withFaceDecision(requestedWeight, selected.reason))
    }

    override fun shape(text: String, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange): MeasuredMathRun {
        val selected = owner.firstSuccessful(requestedWeight) { it.shape(text, fontSizePx, style, sourceRange) }
        return selected.face.shape(text, fontSizePx, style, sourceRange)
            .withFaceDecision(requestedWeight, selected.reason)
    }

    override fun measureGlyph(glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange) =
        primary.measureGlyph(glyphId, fontSizePx, style, sourceRange)
            .withFaceDecision(requestedWeight, owner.reason(primary, requestedWeight))

    override fun measureGlyphForFace(faceId: MathFaceId, glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange) =
        owner.mathFaces.getValue(faceId).measureGlyph(glyphId, fontSizePx, style, sourceRange)
            .withFaceDecision(requestedWeight, owner.reason(owner.mathFaces.getValue(faceId), requestedWeight))

    override fun measureGlyphOutlineBounds(glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange) =
        primary.measureGlyphOutlineBounds(glyphId, fontSizePx, style, sourceRange)
            .withFaceDecision(requestedWeight, owner.reason(primary, requestedWeight))

    override fun measureGlyphOutlineBoundsForFace(faceId: MathFaceId, glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange) =
        owner.mathFaces.getValue(faceId).measureGlyphOutlineBounds(glyphId, fontSizePx, style, sourceRange)
            .withFaceDecision(requestedWeight, owner.reason(owner.mathFaces.getValue(faceId), requestedWeight))

    override fun measureOutlineConstructionGlyph(glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange) =
        primary.measureOutlineConstructionGlyph(glyphId, fontSizePx, style, sourceRange)
            .withFaceDecision(requestedWeight, owner.reason(primary, requestedWeight))

    override fun measureOutlineConstructionGlyphForFace(faceId: MathFaceId, glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange) =
        owner.mathFaces.getValue(faceId).measureOutlineConstructionGlyph(glyphId, fontSizePx, style, sourceRange)
            .withFaceDecision(requestedWeight, owner.reason(owner.mathFaces.getValue(faceId), requestedWeight))

    override fun shapeConstructionBase(text: String, fontSizePx: Float, sourceRange: SourceRange): MeasuredMathRun {
        val selected = owner.firstSuccessful(requestedWeight) { it.shapeConstructionBase(text, fontSizePx, sourceRange) }
        return selected.face.shapeConstructionBase(text, fontSizePx, sourceRange)
            .withFaceDecision(requestedWeight, selected.reason)
    }

    override fun shapeOutlineConstructionBase(text: String, fontSizePx: Float, sourceRange: SourceRange): MeasuredOutlineConstructionRun {
        val selected = owner.firstSuccessful(requestedWeight) {
            it.shapeOutlineConstructionBase(text, fontSizePx, sourceRange).run
        }
        return selected.face.shapeOutlineConstructionBase(text, fontSizePx, sourceRange)
            .withFaceDecision(requestedWeight, selected.reason)
    }

    override fun shapeOutlineConstructionBaseCandidates(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): List<MeasuredOutlineConstructionRun> = owner.ordered(requestedWeight).mapIndexed { index, face ->
        face.shapeOutlineConstructionBase(text, fontSizePx, sourceRange).withFaceDecision(
            requestedWeight,
            when {
                index == 0 && face.resolvedWeight == requestedWeight -> MathFontFallbackReason.RequestedFace
                index == 0 -> MathFontFallbackReason.RequestedWeightUnavailable
                else -> MathFontFallbackReason.MissingMathConstructionInRequestedWeight
            },
        )
    }

    override fun replayFace(faceId: MathFaceId): AndroidReplayFace? = owner.mathFaces[faceId]
    override fun constructionFace(faceId: MathFaceId): AndroidMathFontFace? = owner.mathFaces[faceId]
    override fun close() = owner.close()

    private class Owner(
        val fontClass: MathFontClass,
        val mathFaces: Map<MathFaceId, AndroidMathFontFace>,
    ) : AutoCloseable {
        fun mathFace(weight: MathFontWeight) = mathFaces.values.minBy { abs(it.resolvedWeight.cssWeight - weight.cssWeight) }
        fun hasWeight(weight: MathFontWeight) = mathFaces.values.any { it.resolvedWeight == weight }
        fun ordered(weight: MathFontWeight) = mathFaces.values.sortedBy { abs(it.resolvedWeight.cssWeight - weight.cssWeight) }
        fun reason(face: AndroidMathFontFace, requested: MathFontWeight) = when {
            face.resolvedWeight == requested -> MathFontFallbackReason.RequestedFace
            hasWeight(requested) -> MathFontFallbackReason.MissingGlyphInRequestedWeight
            else -> MathFontFallbackReason.RequestedWeightUnavailable
        }
        fun firstSuccessful(weight: MathFontWeight, measure: (AndroidMathFontFace) -> MeasuredMathRun): Selected {
            val ordered = ordered(weight)
            ordered.forEach { if (!measure(it).missingGlyph) return Selected(it, reason(it, weight)) }
            return Selected(ordered.first(), reason(ordered.first(), weight))
        }
        override fun close() {
            mathFaces.values.forEach(AndroidMathFontFace::close)
        }
    }

    private data class Selected(val face: AndroidMathFontFace, val reason: MathFontFallbackReason)

    companion object {
        const val LeteRegularAsset = "org/tiqian/math/fonts/LeteSansMath-Regular.otf"
        const val LeteBoldAsset = "org/tiqian/math/fonts/LeteSansMath-Bold.otf"
        fun fromSpec(spec: MathFontFamilySpec): AndroidMathFontFamily {
            val mathFaces = spec.faces.associate { face ->
                face.faceId to AndroidMathFontFace.fromBytes(
                    face.fontBytes, face.faceId, face.fontClass, face.weight, face.weight,
                )
            }
            return AndroidMathFontFamily(Owner(spec.fontClass, mathFaces), MathFontWeight.Regular)
        }

        fun loadBundledLete(context: Context): AndroidMathFontFamily {
            val assets = context.applicationContext.assets
            fun bytes(path: String) = assets.open(path).use { it.readBytes() }
            return fromSpec(
                MathFontFamilySpec(
                    "lete-sans-math",
                    MathFontClass.SansSerif,
                    listOf(
                        MathFontFaceSpec(MathFaceId("lete-sans-math-regular"), bytes(LeteRegularAsset), MathFontClass.SansSerif, MathFontWeight.Regular),
                        MathFontFaceSpec(MathFaceId("lete-sans-math-bold"), bytes(LeteBoldAsset), MathFontClass.SansSerif, MathFontWeight.Bold),
                    ),
                ),
            )
        }
    }
}

private fun MeasuredMathRun.withFaceDecision(requested: MathFontWeight, reason: MathFontFallbackReason) =
    copy(glyphs = glyphs.map { it.copy(requestedWeight = requested, fallbackReason = reason) })

private fun MeasuredOutlineConstructionRun.withFaceDecision(requested: MathFontWeight, reason: MathFontFallbackReason) =
    copy(run = run.withFaceDecision(requested, reason))

private fun AndroidMathFontFace.operatorConstructionAvailable(
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
