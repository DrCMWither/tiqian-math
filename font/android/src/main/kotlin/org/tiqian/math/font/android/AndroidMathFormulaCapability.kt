package org.tiqian.math.font.android

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathDiagnostic
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathReplayFaceOwnership
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathFormulaRenderPreflight
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.constructionPaintOwnershipDiagnostics

/** Closes every Android glyph and construction Path before the result can enter Compose draw. */
class AndroidMathFormulaRenderPreflight(
    private val faces: AndroidReplayCatalog,
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

        val glyphDiagnostics = box.glyphs
            .filter { it.constructionGroupId == null }
            .distinctBy { Triple(it.faceId, it.glyphId, it.fontSizePx) }
            .mapNotNull { glyph ->
                if (faces.replayFaceOwnership(glyph.faceId) == MathReplayFaceOwnership.Missing) {
                    return@mapNotNull MathDiagnostic(
                        code = DiagnosticCode.MissingGlyphOutlineEvidence,
                        message = "Android has no replay owner for ${glyph.faceId} glyph ${glyph.glyphId}",
                        range = glyph.sourceRange,
                    )
                }
                val face = faces.replayFace(glyph.faceId)
                if (face?.glyphPath(glyph.glyphId, glyph.fontSizePx) != null) return@mapNotNull null
                MathDiagnostic(
                    code = DiagnosticCode.MissingGlyphOutlineEvidence,
                    message = "Android FreeType path replay is unavailable for ${glyph.faceId} glyph ${glyph.glyphId}",
                    range = glyph.sourceRange,
                )
            }
        if (glyphDiagnostics.isNotEmpty()) return glyphDiagnostics

        return box.constructionPaintGroups.mapNotNull { group ->
            val face = faces.constructionFace(group.faceId)
                ?: return@mapNotNull MathDiagnostic(
                    code = DiagnosticCode.MissingConstructionOutlineEvidence,
                    message = "Android has no MATH construction face ${group.faceId}",
                    range = group.sourceRange,
                )
            when (val path = face.constructionPath(box, group)) {
                is AndroidMathConstructionPathResult.Available -> null
                is AndroidMathConstructionPathResult.Unavailable -> MathDiagnostic(
                    code = DiagnosticCode.MissingConstructionOutlineEvidence,
                    message = buildString {
                        append("Construction paint group ")
                        append(group.id)
                        append(" cannot be replayed by the Android FreeType path backend: ")
                        append(path.reason)
                        path.glyphId?.let { append(" (glyph ").append(it).append(')') }
                    },
                    range = group.sourceRange,
                )
            }
        }
    }
}

fun combineAndroidReplayCatalogs(
    mathCatalog: AndroidReplayCatalog,
    textCatalog: AndroidReplayCatalog?,
): AndroidReplayCatalog = object : AndroidReplayCatalog {
    override fun replayFaceOwnership(faceId: MathFaceId): MathReplayFaceOwnership {
        val math = mathCatalog.replayFace(faceId)
        val text = textCatalog?.replayFace(faceId)
        return when {
            math != null && text != null -> MathReplayFaceOwnership.Conflict
            math != null || text != null -> MathReplayFaceOwnership.Unique
            else -> MathReplayFaceOwnership.Missing
        }
    }

    override fun replayFace(faceId: MathFaceId): AndroidReplayFace? = when (replayFaceOwnership(faceId)) {
        MathReplayFaceOwnership.Unique -> {
            val mathFace = mathCatalog.replayFace(faceId)
            if (mathFace != null) mathFace else textCatalog?.replayFace(faceId)
        }
        MathReplayFaceOwnership.Missing,
        MathReplayFaceOwnership.Conflict,
        -> null
    }

    override fun constructionFace(faceId: MathFaceId): AndroidMathFontFace? =
        mathCatalog.constructionFace(faceId)
}

fun MathComposeFontFace.androidFormulaCapabilityEngine(
    textRunProvider: MathTextRunProvider? = null,
): MathFormulaCapabilityEngine {
    val mathCatalog = this as? AndroidReplayCatalog
        ?: error("Android capability preflight requires a replayable face catalog")
    val textCatalog = textRunProvider as? AndroidReplayCatalog
    val catalog = combineAndroidReplayCatalogs(mathCatalog, textCatalog)
    return MathFormulaCapabilityEngine(
        pipeline = MathLayoutEngine(this, textRunProvider = textRunProvider),
        renderPreflight = AndroidMathFormulaRenderPreflight(catalog),
    )
}

fun AndroidMathFontFace.formulaCapabilityEngine(): MathFormulaCapabilityEngine = androidFormulaCapabilityEngine(null)

fun AndroidMathFontFamily.formulaCapabilityEngine(): MathFormulaCapabilityEngine = androidFormulaCapabilityEngine(null)
