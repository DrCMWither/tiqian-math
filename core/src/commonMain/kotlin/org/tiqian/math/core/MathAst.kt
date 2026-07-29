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

/** TeX math symbol families. These are semantic families, not fallback font names. */
enum class MathFamily {
    Operators,
    Letters,
    Symbols,
    LargeSymbols,
}

/**
 * A variable-family mathcode follows the active math alphabet (for example `\mathrm`). A fixed
 * family symbol keeps its declared family. This is the modern equivalent of TeX's class-7
 * variable-family distinction.
 */
enum class MathFamilyBinding {
    Variable,
    Fixed,
}

/** Math alphabet selection is orthogonal to atom class, symbol identity, and math style. */
enum class MathAlphabet {
    MathNormal,
    Roman,
    Italic,
    Bold,
    BoldItalic,
    SansSerif,
}

enum class MathNamedSymbol(val debugName: String, val baseScalar: Int) {
    Minus("minus", 0x2212),
    AsteriskOperator("asterisk-operator", 0x2217),
    Plus("plus", 0x002B),
    Slash("solidus", 0x002F),
    Colon("colon", 0x003A),
    ExclamationMark("exclamation-mark", 0x0021),
    QuestionMark("question-mark", 0x003F),
    LeftParenthesis("left-parenthesis", 0x0028),
    RightParenthesis("right-parenthesis", 0x0029),
    LeftBracket("left-bracket", 0x005B),
    RightBracket("right-bracket", 0x005D),
    LeftBrace("left-brace", 0x007B),
    RightBrace("right-brace", 0x007D),
    Comma("comma", 0x002C),
    Semicolon("semicolon", 0x003B),
    Equals("equals", 0x003D),
    LessThan("less-than", 0x003C),
    GreaterThan("greater-than", 0x003E),
    Alpha("alpha", 0x03B1),
    Beta("beta", 0x03B2),
    Gamma("gamma", 0x03B3),
    Delta("delta", 0x03B4),
    Epsilon("epsilon", 0x03F5),
    Varepsilon("varepsilon", 0x03B5),
    Theta("theta", 0x03B8),
    Lambda("lambda", 0x03BB),
    Mu("mu", 0x03BC),
    Pi("pi", 0x03C0),
    Sigma("sigma", 0x03C3),
    Phi("phi", 0x03D5),
    Varphi("varphi", 0x03C6),
    Omega("omega", 0x03C9),
    CapitalGamma("capital-gamma", 0x0393),
    CapitalDelta("capital-delta", 0x0394),
    CapitalTheta("capital-theta", 0x0398),
    CapitalLambda("capital-lambda", 0x039B),
    CapitalPi("capital-pi", 0x03A0),
    CapitalSigma("capital-sigma", 0x03A3),
    CapitalPhi("capital-phi", 0x03A6),
    CapitalOmega("capital-omega", 0x03A9),
    Infinity("infinity", 0x221E),
    PartialDifferential("partial-differential", 0x2202),
    DotOperator("dot-operator", 0x22C5),
    MultiplicationSign("multiplication-sign", 0x00D7),
    PlusMinus("plus-minus", 0x00B1),
    DivisionSign("division-sign", 0x00F7),
    LessThanOrEqual("less-than-or-equal", 0x2264),
    GreaterThanOrEqual("greater-than-or-equal", 0x2265),
    NotEqual("not-equal", 0x2260),
    ElementOf("element-of", 0x2208),
    RightArrow("right-arrow", 0x2192),
    ApproximatelyEqual("approximately-equal", 0x2248),
    VerticalBar("vertical-bar", 0x007C),
}

sealed interface MathSymbolIdentity {
    val baseScalar: Int
    val debugName: String

    data class LatinLetter(val letter: Char) : MathSymbolIdentity {
        init {
            require(letter in 'A'..'Z' || letter in 'a'..'z')
        }
        override val baseScalar: Int = letter.code
        override val debugName: String = "latin-$letter"
    }

    data class Digit(val digit: Char) : MathSymbolIdentity {
        init {
            require(digit in '0'..'9')
        }
        override val baseScalar: Int = digit.code
        override val debugName: String = "digit-$digit"
    }

    data class Named(val symbol: MathNamedSymbol) : MathSymbolIdentity {
        override val baseScalar: Int = symbol.baseScalar
        override val debugName: String = symbol.debugName
    }

    /** A literal supported by the tokenizer but not assigned a named TeX symbol identity yet. */
    data class Literal(override val baseScalar: Int) : MathSymbolIdentity {
        override val debugName: String = "literal-U+${baseScalar.toString(16).uppercase()}"
    }
}

data class MathSymbol(
    val sourceText: String,
    val identity: MathSymbolIdentity,
    val atomClass: MathAtomClass,
    val family: MathFamily,
    val familyBinding: MathFamilyBinding,
    val alphabet: MathAlphabet = MathAlphabet.MathNormal,
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

/** A TeX style declaration. It changes the remainder of the containing mlist. */
data class MathStyleDeclaration(
    val requestedLevel: MathStyleLevel,
    override val range: SourceRange,
) : MathNode

/** A scoped math alphabet command such as LaTeX's `\mathrm`. */
data class MathAlphabetScope(
    val family: MathFamily,
    val alphabet: MathAlphabet,
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
