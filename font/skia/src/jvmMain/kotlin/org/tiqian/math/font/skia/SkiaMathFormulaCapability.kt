package org.tiqian.math.font.skia

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathDiagnostic
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathFormulaRenderPreflight
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.constructionPaintOwnershipDiagnostics

/** Closes every construction path needed by unbroken and broken replay before Compose drawing. */
class SkiaMathFormulaRenderPreflight(
    private val face: SkiaMathFontFace,
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

        return box.constructionPaintGroups.mapNotNull { group ->
            when (val outline = face.constructionOutline(box, group)) {
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

fun SkiaMathFontFace.formulaCapabilityEngine(): MathFormulaCapabilityEngine =
    MathFormulaCapabilityEngine(
        pipeline = MathLayoutEngine(this),
        renderPreflight = SkiaMathFormulaRenderPreflight(this),
    )
