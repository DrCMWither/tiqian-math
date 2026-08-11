package org.tiqian.math.font.skia

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathDiagnostic
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathReplayFaceOwnership
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathFormulaRenderPreflight
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.MathHostTextBoxReplayCatalog
import org.tiqian.math.layout.constructionPaintOwnershipDiagnostics

/** Closes every construction path needed by unbroken and broken replay before Compose drawing. */
class SkiaMathFormulaRenderPreflight(
    private val faces: SkiaReplayCatalog,
    private val hostTextBoxes: MathHostTextBoxReplayCatalog? = null,
) : MathFormulaRenderPreflight {
    override fun inspect(layoutResult: MathLayoutResult): List<MathDiagnostic> {
        val boxes = buildList {
            add(layoutResult.box)
            layoutResult.fragments.forEach { add(it.box) }
        }
        return boxes.flatMap(::inspectBox)
            .distinctBy { listOf(it.code, it.message, it.range, it.severity) }
            .sortedWith(compareBy(
                { it.range.start },
                { it.range.endExclusive },
                { it.code.name },
                { it.message },
            ))
    }

    private fun inspectBox(box: MathBox): List<MathDiagnostic> {
        val ownershipDiagnostics = box.constructionPaintOwnershipDiagnostics()
        if (ownershipDiagnostics.isNotEmpty()) return ownershipDiagnostics

        val missingHostText = box.hostTextRuns.firstOrNull {
            hostTextBoxes?.canReplayHostTextBox(it.runId) != true
        }
        if (missingHostText != null) return listOf(MathDiagnostic(
            DiagnosticCode.NonReplayableHostTextRun,
            "No host text replay catalog owns host text box ${missingHostText.runId}",
            missingHostText.sourceRange,
        ))

        val conflictingReplay = box.glyphs.firstOrNull {
            faces.replayFaceOwnership(it.faceId) == MathReplayFaceOwnership.Conflict
        }
        val conflictingGroup = box.constructionPaintGroups.firstOrNull {
            faces.replayFaceOwnership(it.faceId) == MathReplayFaceOwnership.Conflict
        }
        if (conflictingReplay != null || conflictingGroup != null) return listOf(MathDiagnostic(
            DiagnosticCode.ReplayFaceOwnershipConflict,
            "Both the MATH catalog and host text catalog claim replay face " +
                (conflictingReplay?.faceId ?: conflictingGroup?.faceId),
            conflictingReplay?.sourceRange ?: checkNotNull(conflictingGroup).sourceRange,
        ))
        val missingReplay = box.glyphs.firstOrNull {
            faces.replayFaceOwnership(it.faceId) == MathReplayFaceOwnership.Missing
        }
        if (missingReplay != null) return listOf(MathDiagnostic(
            DiagnosticCode.MissingGlyphOutlineEvidence,
            "No Skia replay catalog owns text/math face ${missingReplay.faceId}",
            missingReplay.sourceRange,
        ))
        val invalidReplay = box.glyphs.firstOrNull { glyph ->
            glyph.constructionGroupId == null &&
                faces.replayFace(glyph.faceId)?.canReplayGlyph(glyph.glyphId) != true
        }
        if (invalidReplay != null) return listOf(MathDiagnostic(
            DiagnosticCode.MissingGlyphOutlineEvidence,
            "Skia replay face ${invalidReplay.faceId} cannot replay glyph ${invalidReplay.glyphId}",
            invalidReplay.sourceRange,
        ))
        return box.constructionPaintGroups.mapNotNull { group ->
            val constructionFace = faces.constructionFace(group.faceId)
                ?: return@mapNotNull MathDiagnostic(
                    DiagnosticCode.MissingConstructionOutlineEvidence,
                    "No replayable Skia MATH face owns construction group ${group.id} (${group.faceId})",
                    group.sourceRange,
                )
            when (val outline = constructionFace.constructionOutline(box, group)) {
                is MathConstructionOutlineResult.Available -> null
                is MathConstructionOutlineResult.Unavailable -> MathDiagnostic(
                    code = DiagnosticCode.MissingConstructionOutlineEvidence,
                    message = buildString {
                        append("Construction paint group ")
                        append(group.id)
                        append(" cannot be replayed by the selected Skia math face: ")
                        append(outline.reason)
                        outline.glyphId?.let { append(" (glyph ").append(it).append(')') }
                    },
                    range = group.sourceRange,
                )
            }
        }
    }

}

fun combineSkiaReplayCatalogs(
    mathCatalog: SkiaReplayCatalog,
    textCatalog: SkiaReplayCatalog?,
): SkiaReplayCatalog = object : SkiaReplayCatalog {
    override fun replayFaceOwnership(faceId: MathFaceId): MathReplayFaceOwnership {
        val math = mathCatalog.replayFace(faceId)
        val text = textCatalog?.replayFace(faceId)
        return when {
            math != null && text != null -> MathReplayFaceOwnership.Conflict
            math != null || text != null -> MathReplayFaceOwnership.Unique
            else -> MathReplayFaceOwnership.Missing
        }
    }

    override fun replayFace(faceId: MathFaceId): SkiaReplayFace? = when (replayFaceOwnership(faceId)) {
        MathReplayFaceOwnership.Unique -> {
            val mathFace = mathCatalog.replayFace(faceId)
            if (mathFace != null) mathFace else textCatalog?.replayFace(faceId)
        }
        MathReplayFaceOwnership.Missing,
        MathReplayFaceOwnership.Conflict,
        -> null
    }

    override fun constructionFace(faceId: MathFaceId): SkiaMathFontFace? =
        mathCatalog.constructionFace(faceId)
}

fun MathComposeFontFace.formulaCapabilityEngine(
    textRunProvider: MathTextRunProvider? = null,
): MathFormulaCapabilityEngine {
    val mathCatalog = this as? SkiaReplayCatalog ?: error("Skia math face is not replayable")
    val textCatalog = textRunProvider as? SkiaReplayCatalog
    val catalog = combineSkiaReplayCatalogs(mathCatalog, textCatalog)
    return MathFormulaCapabilityEngine(
        pipeline = MathLayoutEngine(this, textRunProvider = textRunProvider),
        renderPreflight = SkiaMathFormulaRenderPreflight(
            catalog,
            textRunProvider as? MathHostTextBoxReplayCatalog,
        ),
    )
}
