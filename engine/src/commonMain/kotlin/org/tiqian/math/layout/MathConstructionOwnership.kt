package org.tiqian.math.layout

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathDiagnostic
import org.tiqian.math.core.SourceRange

/** Renderer-independent exact ownership rule for semantic construction paint groups. */
fun MathBox.constructionPaintOwnershipDiagnostics(): List<MathDiagnostic> {
    val declarations = constructionPaintGroups.groupBy { it.id }
    val references = mutableMapOf<Int, MutableList<SourceRange>>()
    glyphs.forEach { glyph ->
        glyph.constructionGroupId?.let { references.getOrPut(it) { mutableListOf() } += glyph.sourceRange }
    }
    rules.forEach { rule ->
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
        val declaredFace = groups.first().faceId
        val foreignGlyphs = glyphs.filter {
            it.constructionGroupId == groupId && it.faceId != declaredFace
        }
        if (foreignGlyphs.isNotEmpty()) {
            diagnostics += ownershipDiagnostic(
                "group $groupId owns face $declaredFace but references glyphs from " +
                    foreignGlyphs.map { it.faceId }.distinct().joinToString(),
                foreignGlyphs.map { it.sourceRange }.coveringRange(),
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

private fun ownershipDiagnostic(detail: String, range: SourceRange): MathDiagnostic = MathDiagnostic(
    code = DiagnosticCode.InvalidConstructionPaintOwnership,
    message = "$ConstructionOwnershipPolicy: $detail",
    range = range,
)

private fun List<SourceRange>.coveringRange(): SourceRange =
    reduce { covered, range -> covered.cover(range) }

const val ConstructionOwnershipPolicy = "ConstructionPaintOwnershipExactIdSet"
