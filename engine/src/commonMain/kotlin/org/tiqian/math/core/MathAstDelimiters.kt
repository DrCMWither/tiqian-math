package org.tiqian.math.core

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
