package org.tiqian.math.core

/** UTF-16 offsets into the original Markdown math source. */
data class SourceRange(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0) { "source range start must not be negative" }
        require(endExclusive >= start) { "source range end must not precede start" }
    }

    val length: Int get() = endExclusive - start
    val isEmpty: Boolean get() = length == 0

    fun cover(other: SourceRange): SourceRange = SourceRange(
        start = minOf(start, other.start),
        endExclusive = maxOf(endExclusive, other.endExclusive),
    )

    companion object {
        val Empty = SourceRange(0, 0)
    }
}

enum class DiagnosticSeverity {
    Warning,
    Error,
}

enum class DiagnosticCode {
    TrailingEscape,
    InvalidParameterMarker,
    MissingMacroArgument,
    MacroExpansionDepthExceeded,
    MacroExpansionBudgetExceeded,
    RecursiveMacro,
    UnexpectedClosingGroup,
    UnclosedGroup,
    MissingScriptBase,
    MissingScriptArgument,
    DuplicateSubscript,
    DuplicateSuperscript,
    MissingCommandArgument,
    MissingRadicalDegree,
    UnclosedRadicalDegree,
    MissingRadicalRadicand,
    UnclosedRadicalRadicand,
    MisplacedLimitsModifier,
    UnknownCommand,
    UnsupportedCommand,
    UnsupportedMathAlphabet,
    MissingGlyph,
    MissingMathTable,
    MalformedFont,
    UnsupportedMathDeviceAdjustment,
    MissingMathConstruction,
    MathVariantTooShort,
    MissingConstructionOutlineEvidence,
}

data class MathDiagnostic(
    val code: DiagnosticCode,
    val message: String,
    val range: SourceRange,
    val severity: DiagnosticSeverity = DiagnosticSeverity.Error,
)

enum class MathMode {
    Inline,
    Display,
}
