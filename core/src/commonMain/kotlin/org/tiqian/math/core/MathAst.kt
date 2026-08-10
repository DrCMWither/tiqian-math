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

/** TeX/xcolor declaration: affects subsequent atoms in the current math list only. */
data class MathColorDeclaration(
    val sourceName: String,
    val color: MathPaintColor,
    val commandRange: SourceRange,
    val nameRange: SourceRange,
    override val range: SourceRange,
) : MathNode

/** amsmath `\\boxed`: a display-style math field inside a TeX `\\fbox` frame. */
data class MathBoxed(
    val body: MathNode,
    val commandRange: SourceRange,
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
    Script,
    Fraktur,
    DoubleStruck,
    Monospace,
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
    Zeta("zeta", 0x03B6),
    Eta("eta", 0x03B7),
    Theta("theta", 0x03B8),
    Vartheta("vartheta", 0x03D1),
    Iota("iota", 0x03B9),
    Kappa("kappa", 0x03BA),
    Lambda("lambda", 0x03BB),
    Mu("mu", 0x03BC),
    Nu("nu", 0x03BD),
    Xi("xi", 0x03BE),
    Omicron("omicron", 0x03BF),
    Pi("pi", 0x03C0),
    Varpi("varpi", 0x03D6),
    Rho("rho", 0x03C1),
    Varrho("varrho", 0x03F1),
    Sigma("sigma", 0x03C3),
    Varsigma("varsigma", 0x03C2),
    Tau("tau", 0x03C4),
    Upsilon("upsilon", 0x03C5),
    Phi("phi", 0x03D5),
    Varphi("varphi", 0x03C6),
    Chi("chi", 0x03C7),
    Psi("psi", 0x03C8),
    Omega("omega", 0x03C9),
    CapitalGamma("capital-gamma", 0x0393),
    CapitalDelta("capital-delta", 0x0394),
    CapitalTheta("capital-theta", 0x0398),
    CapitalLambda("capital-lambda", 0x039B),
    CapitalXi("capital-xi", 0x039E),
    CapitalPi("capital-pi", 0x03A0),
    CapitalSigma("capital-sigma", 0x03A3),
    CapitalUpsilon("capital-upsilon", 0x03A5),
    CapitalPhi("capital-phi", 0x03A6),
    CapitalPsi("capital-psi", 0x03A8),
    CapitalOmega("capital-omega", 0x03A9),
    Aleph("aleph", 0x2135),
    ForAll("for-all", 0x2200),
    Exists("exists", 0x2203),
    EmptySet("empty-set", 0x2205),
    Nabla("nabla", 0x2207),
    LogicalNot("logical-not", 0x00AC),
    Top("top", 0x22A4),
    Bottom("bottom", 0x22A5),
    HBar("h-bar", 0x210F),
    ScriptSmallL("script-small-l", 0x2113),
    WeierstrassP("weierstrass-p", 0x2118),
    RealPart("real-part", 0x211C),
    ImaginaryPart("imaginary-part", 0x2111),
    Infinity("infinity", 0x221E),
    PartialDifferential("partial-differential", 0x2202),
    DotOperator("dot-operator", 0x22C5),
    MinusPlus("minus-plus", 0x2213),
    CircleOperator("circle-operator", 0x2218),
    Intersection("intersection", 0x2229),
    Union("union", 0x222A),
    SetMinus("set-minus", 0x2216),
    LogicalAnd("logical-and", 0x2227),
    LogicalOr("logical-or", 0x2228),
    BulletOperator("bullet-operator", 0x2219),
    CircledPlus("circled-plus", 0x2295),
    CircledTimes("circled-times", 0x2297),
    CircledDot("circled-dot", 0x2299),
    DiamondOperator("diamond-operator", 0x22C4),
    StarOperator("star-operator", 0x22C6),
    MultiplicationSign("multiplication-sign", 0x00D7),
    PlusMinus("plus-minus", 0x00B1),
    DivisionSign("division-sign", 0x00F7),
    LessThanOrEqual("less-than-or-equal", 0x2264),
    GreaterThanOrEqual("greater-than-or-equal", 0x2265),
    NotEqual("not-equal", 0x2260),
    ElementOf("element-of", 0x2208),
    NotElementOf("not-element-of", 0x2209),
    ContainsAsMember("contains-as-member", 0x220B),
    Subset("subset", 0x2282),
    Superset("superset", 0x2283),
    SubsetOrEqual("subset-or-equal", 0x2286),
    SupersetOrEqual("superset-or-equal", 0x2287),
    Equivalent("equivalent", 0x2261),
    Precedes("precedes", 0x227A),
    Succeeds("succeeds", 0x227B),
    Similar("similar", 0x223C),
    SimilarOrEqual("similar-or-equal", 0x2243),
    Congruent("congruent", 0x2245),
    ProportionalTo("proportional-to", 0x221D),
    Perpendicular("perpendicular", 0x22A5),
    Parallel("parallel", 0x2225),
    Mid("mid", 0x2223),
    MuchLessThan("much-less-than", 0x226A),
    MuchGreaterThan("much-greater-than", 0x226B),
    AsymptoticallyEqual("asymptotically-equal", 0x224D),
    RightTack("right-tack", 0x22A2),
    LeftTack("left-tack", 0x22A3),
    Models("models", 0x22A8),
    LeftArrow("left-arrow", 0x2190),
    RightArrow("right-arrow", 0x2192),
    LeftRightArrow("left-right-arrow", 0x2194),
    DoubleLeftArrow("double-left-arrow", 0x21D0),
    DoubleRightArrow("double-right-arrow", 0x21D2),
    DoubleLeftRightArrow("double-left-right-arrow", 0x21D4),
    MapsTo("maps-to", 0x21A6),
    ApproximatelyEqual("approximately-equal", 0x2248),
    LessThanOrSlantedEqual("less-than-or-slanted-equal", 0x2A7D),
    GreaterThanOrSlantedEqual("greater-than-or-slanted-equal", 0x2A7E),
    HorizontalEllipsis("horizontal-ellipsis", 0x2026),
    CenteredEllipsis("centered-ellipsis", 0x22EF),
    VerticalEllipsis("vertical-ellipsis", 0x22EE),
    DiagonalEllipsis("diagonal-ellipsis", 0x22F1),
    Prime("prime", 0x2032),
    LeftAngleBracket("left-angle-bracket", 0x27E8),
    RightAngleBracket("right-angle-bracket", 0x27E9),
    UpArrow("up-arrow", 0x2191),
    DownArrow("down-arrow", 0x2193),
    UpDownArrow("up-down-arrow", 0x2195),
    DoubleUpDownArrow("double-up-down-arrow", 0x21D5),
    LongLeftArrow("long-left-arrow", 0x27F5),
    LongRightArrow("long-right-arrow", 0x27F6),
    LongLeftRightArrow("long-left-right-arrow", 0x27F7),
    LongDoubleLeftArrow("long-double-left-arrow", 0x27F8),
    LongDoubleRightArrow("long-double-right-arrow", 0x27F9),
    LongDoubleLeftRightArrow("long-double-left-right-arrow", 0x27FA),
    DoubleVerticalBar("double-vertical-bar", 0x2016),
    LeftCeiling("left-ceiling", 0x2308),
    RightCeiling("right-ceiling", 0x2309),
    LeftFloor("left-floor", 0x230A),
    RightFloor("right-floor", 0x230B),
    Angle("angle", 0x2220),
    Therefore("therefore", 0x2234),
    TriangleEqual("triangle-equal", 0x225C),
    RightTriangle("right-triangle", 0x25B9),
    Dagger("dagger", 0x2020),
    BlackStar("black-star", 0x2605),
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

/** TeX operator-noad policy. Auto is display limits for ordinary large operators. */
enum class MathLimitsPolicy {
    Auto,
    Limits,
    NoLimits,
}

/**
 * Large-operator identities supported by the first operator-noad slice. The default policy follows
 * plain TeX: sums and products use display limits, while integrals keep side scripts.
 */
enum class MathLargeOperatorIdentity(
    val debugName: String,
    val baseScalar: Int,
    val defaultLimitsPolicy: MathLimitsPolicy,
) {
    Sum("sum", 0x2211, MathLimitsPolicy.Auto),
    Product("product", 0x220F, MathLimitsPolicy.Auto),
    Integral("integral", 0x222B, MathLimitsPolicy.NoLimits),
    ContourIntegral("contour-integral", 0x222E, MathLimitsPolicy.NoLimits),
    BigIntersection("big-intersection", 0x22C2, MathLimitsPolicy.Auto),
    BigUnion("big-union", 0x22C3, MathLimitsPolicy.Auto),
    BigCircledTimes("big-circled-times", 0x2A02, MathLimitsPolicy.Auto),
}

/** A real TeX operator noad, kept distinct from a Unicode symbol with Operator spacing. */
data class MathOperator(
    val sourceText: String,
    val identity: MathLargeOperatorIdentity,
    val limitsPolicy: MathLimitsPolicy,
    /** Exact command-token range used by glyph placements, never expanded across scripts. */
    val commandRange: SourceRange,
    /** The exact final postfix modifier, if the plain-TeX default was overridden. */
    val limitsModifierRange: SourceRange? = null,
    override val range: SourceRange,
) : MathNode {
    val atomClass: MathAtomClass get() = MathAtomClass.Operator
    val family: MathFamily get() = MathFamily.LargeSymbols
    val hasExplicitLimitsPolicy: Boolean get() = limitsModifierRange != null
}

/** A primitive-style `\mathop{...}` whose nucleus is an arbitrary completed math list. */
data class MathOperatorNoad(
    val nucleus: MathNode,
    val limitsPolicy: MathLimitsPolicy = MathLimitsPolicy.Auto,
    val commandRange: SourceRange,
    val limitsModifierRange: SourceRange? = null,
    override val range: SourceRange,
) : MathNode {
    val atomClass: MathAtomClass get() = MathAtomClass.Operator
    val hasExplicitLimitsPolicy: Boolean get() = limitsModifierRange != null
}

/**
 * A log-like function name (`\sin`, `\log`, `\lim`, …). It is an Operator-class atom rendered as
 * upright roman text; [limitsPolicy] decides whether attached scripts stack over/under in display
 * style (`\lim`) or stay to the side (`\sin`). In text style every function keeps side scripts.
 */
enum class MathOperatorNameOrigin { BuiltInCommand, OperatorNameCommand }

/** One shaped text-mode segment; grouping braces are omitted while its source remains untouched. */
data class MathTextSegment(
    val text: String,
    val range: SourceRange,
)

/** Text mode embedded in math. Spaces and Unicode text are shaped as text, never as math noads. */
data class MathText(
    val segments: List<MathTextSegment>,
    val commandRange: SourceRange,
    val contentRange: SourceRange,
    override val range: SourceRange,
    val origin: MathTextOrigin = MathTextOrigin.TextCommand,
) : MathNode {
    val text: String get() = segments.joinToString("") { it.text }
}

enum class MathTextOrigin { TextCommand, ImplicitCjk }

data class MathOperatorName(
    val name: String,
    val limitsPolicy: MathLimitsPolicy,
    val commandRange: SourceRange,
    val nameSegments: List<MathTextSegment>? = null,
    val nameRange: SourceRange = commandRange,
    val origin: MathOperatorNameOrigin = MathOperatorNameOrigin.BuiltInCommand,
    val limitsModifierRange: SourceRange? = null,
    override val range: SourceRange,
) : MathNode {
    val atomClass: MathAtomClass get() = MathAtomClass.Operator
    val hasExplicitLimitsPolicy: Boolean get() = limitsModifierRange != null
}


enum class MathAccentIdentity(
    val debugName: String,
    val scalar: Int,
    val wide: Boolean,
    val placement: MathAccentPlacement = MathAccentPlacement.Top,
) {
    Hat("hat", 0x0302, false),
    Bar("bar", 0x0304, false),
    Tilde("tilde", 0x0303, false),
    Dot("dot", 0x0307, false),
    DoubleDot("double-dot", 0x0308, false),
    Vec("vec", 0x20D7, false),
    WideHat("widehat", 0x0302, true),
    WideTilde("widetilde", 0x0303, true),
    OverBrace("overbrace", 0x23DE, true, MathAccentPlacement.Top),
    UnderBrace("underbrace", 0x23DF, true, MathAccentPlacement.Bottom),
}

enum class MathAccentPlacement { Top, Bottom }

/** A TeX math accent noad. The nucleus is laid out cramped; the source is never rewritten. */
data class MathAccent(
    val identity: MathAccentIdentity,
    val commandRange: SourceRange,
    val base: MathNode,
    override val range: SourceRange,
) : MathNode {
    val atomClass: MathAtomClass get() = MathAtomClass.Ordinary
}

enum class MathBraceKind { Over, Under }

/** TeX `\overbrace`/`\underbrace`: a growing accent nucleus wrapped in a limits op noad. */
data class MathBraceNoad(
    val kind: MathBraceKind,
    val base: MathNode,
    val limitsPolicy: MathLimitsPolicy = MathLimitsPolicy.Limits,
    val commandRange: SourceRange,
    val limitsModifierRange: SourceRange? = null,
    override val range: SourceRange,
) : MathNode {
    val atomClass: MathAtomClass get() = MathAtomClass.Operator
    val hasExplicitLimitsPolicy: Boolean get() = limitsModifierRange != null
}

enum class MathRuleDecorationKind { Overline, Underline }

/** Rule-based overline/underline, distinct from glyph accents such as `\bar`. */
data class MathRuleDecoration(
    val kind: MathRuleDecorationKind,
    val commandRange: SourceRange,
    val base: MathNode,
    override val range: SourceRange,
) : MathNode {
    val atomClass: MathAtomClass get() = MathAtomClass.Ordinary
}

/** TeX/amsmath over-under constructions whose annotation is a real math list. */
enum class MathOverUnderKind {
    Overset,
    Underset,
    StackRel,
}

data class MathOverUnder(
    val kind: MathOverUnderKind,
    val annotation: MathNode,
    val base: MathNode,
    /** `\stackrel` is Rel; `\overset`/`\underset` retain Bin/Rel bases and otherwise become Ord. */
    val atomClass: MathAtomClass,
    val commandRange: SourceRange,
    override val range: SourceRange,
) : MathNode

enum class MathExtensibleArrowIdentity(
    val debugName: String,
    val arrowHeadScalar: Int,
) {
    Left("xleftarrow", 0x2190),
    Right("xrightarrow", 0x2192),
}

/** An amsmath extensible relation arrow with script-style labels above and optionally below. */
data class MathExtensibleArrow(
    val identity: MathExtensibleArrowIdentity,
    val above: MathNode,
    val below: MathNode?,
    val commandRange: SourceRange,
    /** Includes the square brackets when an optional lower label was present. */
    val belowRange: SourceRange?,
    override val range: SourceRange,
) : MathNode {
    val atomClass: MathAtomClass get() = MathAtomClass.Relation
}

/** Explicit TeX math space or kern, resolved in mu units at the active math style's font size. */
data class MathExplicitSpace(
    val command: String,
    val mu: Float,
    override val range: SourceRange,
) : MathNode {
    init {
        require(mu.isFinite()) { "math space must be finite" }
    }
}

enum class MathTableEnvironment(
    val sourceName: String,
    val leftDelimiter: MathDelimiterIdentity? = null,
    val rightDelimiter: MathDelimiterIdentity? = null,
) {
    Matrix("matrix"),
    ParenthesizedMatrix("pmatrix", MathDelimiterIdentity.LeftParenthesis, MathDelimiterIdentity.RightParenthesis),
    BracketedMatrix("bmatrix", MathDelimiterIdentity.LeftBracket, MathDelimiterIdentity.RightBracket),
    Determinant("vmatrix", MathDelimiterIdentity.VerticalBar, MathDelimiterIdentity.VerticalBar),
    Array("array"),
    Aligned("aligned"),
    Cases("cases", MathDelimiterIdentity.LeftBrace, MathDelimiterIdentity.Invisible),
    Split("split"),
}

enum class MathTableColumnAlignment { Left, Center, Right }

data class MathTableCell(
    val body: MathList,
    val columnSeparatorRange: SourceRange? = null,
    val range: SourceRange,
)

data class MathTableRow(
    val cells: List<MathTableCell>,
    val rowSeparatorRange: SourceRange? = null,
    val additionalSpacing: MathTeXDimension? = null,
    val range: SourceRange,
)

enum class MathTeXDimensionUnit(val sourceName: String) {
    Point("pt"),
    BigPoint("bp"),
    Em("em"),
    Centimeter("cm"),
    Millimeter("mm"),
    Inch("in"),
}

/** A source-retaining TeX dimension accepted after a table row separator. */
data class MathTeXDimension(
    val value: Float,
    val unit: MathTeXDimensionUnit,
    val sourceText: String,
    val contentRange: SourceRange,
    val range: SourceRange,
)

/** Structured TeX/LaTeX alignment environment; row and column separators retain source ranges. */
data class MathTable(
    val environmentName: String,
    val environment: MathTableEnvironment?,
    val rows: List<MathTableRow>,
    val columnAlignments: List<MathTableColumnAlignment>,
    val beginCommandRange: SourceRange,
    val beginNameRange: SourceRange,
    val endCommandRange: SourceRange?,
    val endNameRange: SourceRange?,
    override val range: SourceRange,
) : MathNode {
    val atomClass: MathAtomClass get() = MathAtomClass.Inner
}

enum class MathDisplayEnvironmentKind(
    val sourceName: String,
    val alignment: Boolean,
    val sourceRequestsNumbering: Boolean,
) {
    Equation("equation", false, true),
    EquationStar("equation*", false, false),
    Align("align", true, true),
    AlignStar("align*", true, false),
}

/**
 * A document-level display wrapper retained by the Markdown-math frontend.
 *
 * It is deliberately distinct from [MathTable]: `align`/`equation` are display structures,
 * not Inner noads. Markdown hosts suppress document equation counters while preserving the
 * source request in decisions.
 */
data class MathDisplayEnvironment(
    val kind: MathDisplayEnvironmentKind,
    val body: MathNode,
    val beginCommandRange: SourceRange,
    val beginNameRange: SourceRange,
    val endCommandRange: SourceRange?,
    val endNameRange: SourceRange?,
    override val range: SourceRange,
) : MathNode

/**
 * One top-level Markdown display row. The separator belongs to the row before it, matching
 * TeX/amsmath's source model; a trailing separator therefore does not invent a visible empty row.
 */
data class MathDisplayRow(
    val body: MathList,
    val rowSeparatorRange: SourceRange?,
    val additionalSpacing: MathTeXDimension? = null,
    val range: SourceRange,
)

/**
 * Host-display extension for top-level `\\` rows outside a TeX alignment environment.
 * It is a display structure, not an Inner noad and not an automatic line-breaking request.
 */
data class MathDisplayRows(
    val rows: List<MathDisplayRow>,
    override val range: SourceRange,
) : MathNode

/** Parser-only source marker folded into [MathDisplayRows] before a parse result is returned. */
data class MathExplicitRowBreak(
    val separatorRange: SourceRange,
    val additionalSpacing: MathTeXDimension?,
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

enum class MathFractionOrigin {
    Fraction,
    Binomial,
    DisplayFraction,
    ContinuedFraction,
}

enum class MathFractionAlignment { Center, Left, Right }

data class MathFraction(
    val numerator: MathNode,
    val denominator: MathNode,
    val kind: FractionKind,
    val hasParentheses: Boolean,
    override val range: SourceRange,
    val origin: MathFractionOrigin = if (hasParentheses) MathFractionOrigin.Binomial else MathFractionOrigin.Fraction,
    /** LaTeX `\dfrac` and amsmath `\cfrac` select display style inside their local group. */
    val styleOverride: MathStyleLevel? = null,
    /** `\cfrac[l]`/`\cfrac[r]` align only the numerator inside the common fraction width. */
    val numeratorAlignment: MathFractionAlignment = MathFractionAlignment.Center,
    /** amsmath inserts a text-size strut at the start of every continued-fraction numerator. */
    val numeratorStrut: Boolean = false,
    /** amsmath `\cfrac` cancels the fraction noad's trailing `\nulldelimiterspace`. */
    val retainRightNullDelimiterSpace: Boolean = true,
    val commandRange: SourceRange = range,
    val alignmentRange: SourceRange? = null,
) : MathNode

/** A TeX radical noad, with LaTeX's optional root degree retained as its own math list. */
data class MathRadical(
    val sourceText: String,
    val commandRange: SourceRange,
    val degree: MathNode?,
    /** Includes the optional square brackets when they were present. */
    val degreeRange: SourceRange?,
    val radicand: MathNode,
    override val range: SourceRange,
) : MathNode {
    val atomClass: MathAtomClass get() = MathAtomClass.Ordinary
}

/** Semantic delimiter identities accepted by TeX's `\left`, `\middle`, and `\right`. */
enum class MathDelimiterIdentity(
    val debugName: String,
    val scalar: Int?,
) {
    Invisible("invisible", null),
    LeftParenthesis("left-parenthesis", 0x0028),
    RightParenthesis("right-parenthesis", 0x0029),
    LeftBracket("left-bracket", 0x005B),
    RightBracket("right-bracket", 0x005D),
    LeftBrace("left-brace", 0x007B),
    RightBrace("right-brace", 0x007D),
    VerticalBar("vertical-bar", 0x007C),
    DoubleVerticalBar("double-vertical-bar", 0x2016),
    Solidus("solidus", 0x002F),
    ReverseSolidus("reverse-solidus", 0x005C),
    LeftAngleBracket("left-angle-bracket", 0x27E8),
    RightAngleBracket("right-angle-bracket", 0x27E9),
    LeftFloor("left-floor", 0x230A),
    RightFloor("right-floor", 0x230B),
    LeftCeiling("left-ceiling", 0x2308),
    RightCeiling("right-ceiling", 0x2309),
    UpArrow("up-arrow", 0x2191),
    DownArrow("down-arrow", 0x2193),
    UpDownArrow("up-down-arrow", 0x2195),
    DoubleUpArrow("double-up-arrow", 0x21D1),
    DoubleDownArrow("double-down-arrow", 0x21D3),
    DoubleUpDownArrow("double-up-down-arrow", 0x21D5),
}

enum class MathDelimiterSide {
    Left,
    Middle,
    Right,
}

/**
 * One source delimiter token and its introducing command. Unsupported recovery retains both
 * ranges while leaving [identity] null; a literal `.` is the supported zero-advance delimiter.
 */
data class MathDelimiterSpec(
    val sourceText: String,
    val identity: MathDelimiterIdentity?,
    val side: MathDelimiterSide,
    val commandRange: SourceRange,
    val delimiterRange: SourceRange,
    val range: SourceRange,
) {
    val scalar: Int? get() = identity?.scalar
    val visible: Boolean get() = identity != null && identity != MathDelimiterIdentity.Invisible
}

/** The four amsmath fixed delimiter requests, expressed as multiples of `\big@size`. */
enum class MathFixedDelimiterSize(
    val commandStem: String,
    val amsmathFactor: Float,
) {
    Big("big", 1f),
    BigCapital("Big", 1.5f),
    Bigg("bigg", 2f),
    BiggCapital("Bigg", 2.5f),
}

/** The wrapper noad applied by `\big`, `\bigl`, `\bigm`, and `\bigr`. */
enum class MathFixedDelimiterRole(val atomClass: MathAtomClass) {
    Ordinary(MathAtomClass.Ordinary),
    Opening(MathAtomClass.Opening),
    Relation(MathAtomClass.Relation),
    Closing(MathAtomClass.Closing),
}

/**
 * One amsmath fixed-size delimiter macro invocation. The requested size and noad role are
 * independent of the delimiter identity and of the surrounding math style.
 */
data class MathFixedDelimiter(
    val delimiter: MathDelimiterSpec,
    val size: MathFixedDelimiterSize,
    val role: MathFixedDelimiterRole,
    override val range: SourceRange = delimiter.range,
) : MathNode {
    val atomClass: MathAtomClass get() = role.atomClass
}

/** Marker inside [MathDelimited.body]; it is sized together with both outer delimiters. */
data class MathMiddleDelimiter(
    val delimiter: MathDelimiterSpec,
    override val range: SourceRange = delimiter.range,
) : MathNode

enum class MathDelimiterSizePolicy {
    /** TeX `make_left_right`: content clean box, axis, factor, and shortfall. */
    ContentDrivenTeX,
}

/** A TeX inner noad produced by one matched `\left ... \right` group. */
data class MathDelimited(
    val left: MathDelimiterSpec,
    val body: MathList,
    val right: MathDelimiterSpec,
    val sizePolicy: MathDelimiterSizePolicy = MathDelimiterSizePolicy.ContentDrivenTeX,
    override val range: SourceRange,
) : MathNode {
    val atomClass: MathAtomClass get() = MathAtomClass.Inner
}

/** A TeX style declaration. It changes the remainder of the containing mlist. */
data class MathStyleDeclaration(
    val requestedLevel: MathStyleLevel,
    override val range: SourceRange,
) : MathNode

/** Legacy TeX list declaration such as `\rm`; affects later variable-family atoms in this mlist. */
data class MathAlphabetDeclaration(
    val family: MathFamily,
    val alphabet: MathAlphabet,
    override val range: SourceRange,
) : MathNode

/** A scoped math alphabet command such as LaTeX's `\mathrm`. */
data class MathAlphabetScope(
    val family: MathFamily,
    val alphabet: MathAlphabet,
    val body: MathNode,
    override val range: SourceRange,
) : MathNode

/** A TeX math-version selection, orthogonal to math alphabet and math style. */
enum class MathVersion {
    Bold,
}

/**
 * A scoped math version such as LaTeX's `\boldsymbol`. Unlike a math alphabet, it applies to
 * fixed-family symbols and operator noads as well as variable-family characters.
 */
data class MathVersionScope(
    val version: MathVersion,
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
