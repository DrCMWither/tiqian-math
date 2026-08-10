package org.tiqian.math.layout

import org.tiqian.math.core.MathAtomClass
import org.tiqian.math.core.MathGlueKind
import kotlin.test.Test
import kotlin.test.assertEquals

class TeXMathSpacingTest {
    @Test
    fun normalAndTightMatricesMatchXeTeXMlistToHlistOffsetTable() {
        // Tectonic 0.17.0 engine_xetex/xetex/xetex-math.c `offset_table`.
        val classes = listOf(
            MathAtomClass.Ordinary,
            MathAtomClass.Operator,
            MathAtomClass.Binary,
            MathAtomClass.Relation,
            MathAtomClass.Opening,
            MathAtomClass.Closing,
            MathAtomClass.Punctuation,
            MathAtomClass.Inner,
        )
        val normal = listOf(
            "ntmTnnnt",
            "ttnTnnnt",
            "mmnnmnnm",
            "TTnnTnnT",
            "nnnnnnnn",
            "ntmTnnnt",
            "ttnttttt",
            "ttmTtntt",
        )
        val tight = listOf(
            "ntnnnnnn",
            "ttnnnnnn",
            "nnnnnnnn",
            "nnnnnnnn",
            "nnnnnnnn",
            "ntnnnnnn",
            "nnnnnnnn",
            "ntnnnnnn",
        )
        listOf(false to normal, true to tight).forEach { (isTight, expectedRows) ->
            classes.forEachIndexed { leftIndex, left ->
                classes.forEachIndexed { rightIndex, right ->
                    assertEquals(
                        expectedRows[leftIndex][rightIndex].asGlueKind(),
                        TeXMathSpacing.kind(left, right, isTight),
                        "tight=$isTight left=$left right=$right",
                    )
                }
            }
        }
    }
}

private fun Char.asGlueKind(): MathGlueKind = when (this) {
    'n' -> MathGlueKind.None
    't' -> MathGlueKind.Thin
    'm' -> MathGlueKind.Medium
    'T' -> MathGlueKind.Thick
    else -> error("unknown spacing code $this")
}
