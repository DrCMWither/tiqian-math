package org.tiqian.math.layout

import org.tiqian.math.core.MathAtomClass
import org.tiqian.math.core.MathGlueKind

/** XeTeX `mlist_to_hlist` 8x8 noad-spacing matrix. Missing entries are explicit zero glue. */
internal object TeXMathSpacing {
    fun kind(left: MathAtomClass, right: MathAtomClass, tight: Boolean): MathGlueKind =
        (if (tight) tightTable else normalTable)[left to right] ?: MathGlueKind.None

    private val normalTable: Map<Pair<MathAtomClass, MathAtomClass>, MathGlueKind> = buildMap {
        fun row(left: MathAtomClass, kind: MathGlueKind, vararg rights: MathAtomClass) {
            rights.forEach { put(left to it, kind) }
        }
        row(MathAtomClass.Ordinary, MathGlueKind.Thin, MathAtomClass.Operator, MathAtomClass.Inner)
        row(MathAtomClass.Ordinary, MathGlueKind.Medium, MathAtomClass.Binary)
        row(MathAtomClass.Ordinary, MathGlueKind.Thick, MathAtomClass.Relation)
        row(MathAtomClass.Operator, MathGlueKind.Thin, MathAtomClass.Ordinary, MathAtomClass.Operator, MathAtomClass.Inner)
        row(MathAtomClass.Operator, MathGlueKind.Thick, MathAtomClass.Relation)
        row(MathAtomClass.Binary, MathGlueKind.Medium, MathAtomClass.Ordinary, MathAtomClass.Operator, MathAtomClass.Opening, MathAtomClass.Inner)
        row(MathAtomClass.Relation, MathGlueKind.Thick, MathAtomClass.Ordinary, MathAtomClass.Operator, MathAtomClass.Opening, MathAtomClass.Inner)
        row(MathAtomClass.Closing, MathGlueKind.Thin, MathAtomClass.Operator, MathAtomClass.Inner)
        row(MathAtomClass.Closing, MathGlueKind.Medium, MathAtomClass.Binary)
        row(MathAtomClass.Closing, MathGlueKind.Thick, MathAtomClass.Relation)
        row(
            MathAtomClass.Punctuation,
            MathGlueKind.Thin,
            MathAtomClass.Ordinary,
            MathAtomClass.Operator,
            MathAtomClass.Relation,
            MathAtomClass.Opening,
            MathAtomClass.Closing,
            MathAtomClass.Punctuation,
            MathAtomClass.Inner,
        )
        row(
            MathAtomClass.Inner,
            MathGlueKind.Thin,
            MathAtomClass.Ordinary,
            MathAtomClass.Operator,
            MathAtomClass.Opening,
            MathAtomClass.Punctuation,
            MathAtomClass.Inner,
        )
        row(MathAtomClass.Inner, MathGlueKind.Medium, MathAtomClass.Binary)
        row(MathAtomClass.Inner, MathGlueKind.Thick, MathAtomClass.Relation)
    }

    private val tightTable: Map<Pair<MathAtomClass, MathAtomClass>, MathGlueKind> = buildMap {
        put(MathAtomClass.Ordinary to MathAtomClass.Operator, MathGlueKind.Thin)
        put(MathAtomClass.Operator to MathAtomClass.Ordinary, MathGlueKind.Thin)
        put(MathAtomClass.Operator to MathAtomClass.Operator, MathGlueKind.Thin)
        put(MathAtomClass.Closing to MathAtomClass.Operator, MathGlueKind.Thin)
        put(MathAtomClass.Inner to MathAtomClass.Operator, MathGlueKind.Thin)
    }
}
