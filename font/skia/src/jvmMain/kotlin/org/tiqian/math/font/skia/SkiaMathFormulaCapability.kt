package org.tiqian.math.font.skia

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathDiagnostic
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.SourceRange
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathFormulaRenderPreflight
import org.tiqian.math.layout.MathLayoutEngine

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
        val ownershipDiagnostics = constructionOwnershipDiagnostics(box)
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

    private fun constructionOwnershipDiagnostics(box: MathBox): List<MathDiagnostic> {
        val declarations = box.constructionPaintGroups.groupBy { it.id }
        val references = mutableMapOf<Int, MutableList<SourceRange>>()
        box.glyphs.forEach { glyph ->
            glyph.constructionGroupId?.let { references.getOrPut(it) { mutableListOf() } += glyph.sourceRange }
        }
        box.rules.forEach { rule ->
            rule.constructionGroupId?.let { references.getOrPut(it) { mutableListOf() } += rule.sourceRange }
        }

        val diagnostics = mutableListOf<MathDiagnostic>()
        declarations.entries.sortedBy { it.key }.forEach { (groupId, groups) ->
            if (groups.size > 1) {
                diagnostics += ownershipDiagnostic(
                    "group $groupId is declared ${groups.size} times",
                    groups.map { it.sourceRange }.coveringRange(),
                )
            }
            if (groupId !in references) {
                diagnostics += ownershipDiagnostic(
                    "group $groupId is declared but has no glyph or rule placements",
                    groups.map { it.sourceRange }.coveringRange(),
                )
            }
        }
        references.entries.sortedBy { it.key }.forEach { (groupId, ranges) ->
            if (groupId !in declarations) {
                diagnostics += ownershipDiagnostic(
                    "group $groupId is referenced by glyph or rule placements but is not declared",
                    ranges.coveringRange(),
                )
            }
        }
        return diagnostics
    }

    private fun ownershipDiagnostic(
        detail: String,
        range: SourceRange,
    ): MathDiagnostic = MathDiagnostic(
        code = DiagnosticCode.InvalidConstructionPaintOwnership,
        message = "$CONSTRUCTION_OWNERSHIP_POLICY: $detail",
        range = range,
    )

    private fun List<SourceRange>.coveringRange(): SourceRange =
        reduce { covered, range -> covered.cover(range) }

    private companion object {
        const val CONSTRUCTION_OWNERSHIP_POLICY = "ConstructionPaintOwnershipExactIdSet"
    }
}

fun SkiaMathFontFace.formulaCapabilityEngine(): MathFormulaCapabilityEngine =
    MathFormulaCapabilityEngine(
        pipeline = MathLayoutEngine(this),
        renderPreflight = SkiaMathFormulaRenderPreflight(this),
    )
