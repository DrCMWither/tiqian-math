package org.tiqian.math.font.android

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathDiagnostic
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathFormulaRenderPreflight
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.constructionPaintOwnershipDiagnostics

/** Closes every Android glyph and construction Path before the result can enter Compose draw. */
class AndroidMathFormulaRenderPreflight(
    private val face: AndroidMathFontFace,
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

        val glyphDiagnostics = box.glyphs
            .filter { it.constructionGroupId == null }
            .distinctBy { it.glyphId to it.fontSizePx }
            .mapNotNull { glyph ->
                if (face.glyphPath(glyph.glyphId, glyph.fontSizePx) != null) return@mapNotNull null
                MathDiagnostic(
                    code = DiagnosticCode.MissingGlyphOutlineEvidence,
                    message = "Android FreeType path replay is unavailable for glyph ${glyph.glyphId}",
                    range = glyph.sourceRange,
                )
            }
        if (glyphDiagnostics.isNotEmpty()) return glyphDiagnostics

        return box.constructionPaintGroups.mapNotNull { group ->
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

fun AndroidMathFontFace.formulaCapabilityEngine(): MathFormulaCapabilityEngine =
    MathFormulaCapabilityEngine(
        pipeline = MathLayoutEngine(this),
        renderPreflight = AndroidMathFormulaRenderPreflight(this),
    )
