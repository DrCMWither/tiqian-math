package org.tiqian.math.core

enum class MathAtomClass {
    Ordinary,
    Operator,
    Binary,
    Relation,
    Opening,
    Closing,
    Punctuation,
    Inner,
}

sealed interface MathNode {
    val range: SourceRange
}

data class MathList(
    val children: List<MathNode>,
    override val range: SourceRange,
) : MathNode

data class MathGroup(
    val body: MathList,
    override val range: SourceRange,
) : MathNode

/**
 * Semantic math alphabet selection. This is source semantics, not a drawing effect:
 * adapters must resolve it to a real cmap glyph and must never synthesize a shear.
 */
enum class MathVariant {
    /** TeX/MathML variable semantics; the standard mathematical italic scalar is selected. */
    DefaultVariableItalic,

    /** Keep the semantic scalar upright (numbers, operators, delimiters, and explicit roman text). */
    Upright,

    /** The source already contains a Mathematical Alphanumeric Symbol; never remap it. */
    ExplicitUnicode,
}

data class MathSymbol(
    val sourceText: String,
    val displayText: String,
    val atomClass: MathAtomClass,
    val variant: MathVariant,
    override val range: SourceRange,
) : MathNode

data class MathScripts(
    val base: MathNode,
    val superscript: MathNode?,
    val subscript: MathNode?,
    override val range: SourceRange,
) : MathNode

enum class FractionKind {
    Barred,
    Ruleless,
}

data class MathFraction(
    val numerator: MathNode,
    val denominator: MathNode,
    val kind: FractionKind,
    val hasParentheses: Boolean,
    override val range: SourceRange,
) : MathNode

data class MathStyleScope(
    val requestedLevel: MathStyleLevel,
    val body: MathNode,
    override val range: SourceRange,
) : MathNode

data class MathVariantScope(
    val variant: MathVariant,
    val body: MathNode,
    override val range: SourceRange,
) : MathNode

/** Preserves unsupported or malformed source in the AST while parsing continues. */
data class MathErrorNode(
    val sourceText: String,
    override val range: SourceRange,
) : MathNode

data class MathParseResult(
    val source: String,
    val root: MathList,
    val diagnostics: List<MathDiagnostic>,
)
