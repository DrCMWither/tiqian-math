package org.tiqian.math.layout

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.DiagnosticSeverity
import org.tiqian.math.core.MathDiagnostic
import org.tiqian.math.core.MathLayoutResult

/** Formula-wide production decision. A formula is never partially accepted. */
enum class MathFormulaCapabilityCategory {
    UnsupportedSyntax,
    MalformedSource,
    MissingGlyph,
    MissingMathConstruction,
    InsufficientMathConstruction,
    ConstructionOutlineUnavailable,
    ConstructionPaintOwnershipInvalid,
    UnsupportedFontCapability,
}

data class MathFormulaFallbackReason(
    val category: MathFormulaCapabilityCategory,
    val diagnostics: List<MathDiagnostic>,
)

sealed interface MathFormulaCapabilityResult {
    val source: String
    val diagnostics: List<MathDiagnostic>

    data class Ready(
        val layoutResult: MathLayoutResult,
        override val diagnostics: List<MathDiagnostic> = layoutResult.diagnostics,
    ) : MathFormulaCapabilityResult {
        override val source: String get() = layoutResult.source
    }

    data class FallbackRequired(
        override val source: String,
        override val diagnostics: List<MathDiagnostic>,
        val reasons: List<MathFormulaFallbackReason>,
    ) : MathFormulaCapabilityResult
}

class MathFormulaStrictException(
    val fallback: MathFormulaCapabilityResult.FallbackRequired,
) : IllegalStateException(
    "Formula requires host fallback: " + fallback.reasons.joinToString { it.category.name },
)

/** Renderer-specific inspection that must complete before a result can be declared paintable. */
fun interface MathFormulaRenderPreflight {
    fun inspect(layoutResult: MathLayoutResult): List<MathDiagnostic>
}

/**
 * Parses and lays out exactly once, then closes formula-wide layout and paint capabilities.
 * Existing blocking diagnostics short-circuit render preflight so rejected formulas build no paths.
 */
class MathFormulaCapabilityEngine(
    private val pipeline: MathFormulaProductionPipeline,
    private val renderPreflight: MathFormulaRenderPreflight,
) {
    fun evaluate(
        source: String,
        options: MathLayoutOptions = MathLayoutOptions(),
    ): MathFormulaCapabilityResult {
        val prepared = pipeline.prepare(source)
        check(prepared.source == source) { "Prepared formula source does not match its request" }
        MathFormulaCapabilityClassifier.classify(source, prepared.diagnostics)?.let { return it }

        val layoutResult = pipeline.layout(prepared, options)
        check(layoutResult.source == source) { "Layout result source does not match its prepared formula" }
        MathFormulaCapabilityClassifier.classify(layoutResult)?.let { return it }

        val renderDiagnostics = renderPreflight.inspect(layoutResult)
        val allDiagnostics = MathFormulaCapabilityClassifier.mergeDiagnostics(
            layoutResult.diagnostics,
            renderDiagnostics,
        )
        return MathFormulaCapabilityClassifier.classify(source, allDiagnostics)
            ?: MathFormulaCapabilityResult.Ready(layoutResult, allDiagnostics)
    }

    fun requireReady(
        source: String,
        options: MathLayoutOptions = MathLayoutOptions(),
    ): MathLayoutResult = when (val result = evaluate(source, options)) {
        is MathFormulaCapabilityResult.Ready -> result.layoutResult
        is MathFormulaCapabilityResult.FallbackRequired -> throw MathFormulaStrictException(result)
    }
}

object MathFormulaCapabilityClassifier {
    /** Warnings in these categories describe incomplete output and are never production-safe. */
    private val blockingWarningCodes = setOf(
        DiagnosticCode.MissingGlyph,
        DiagnosticCode.MissingMathTable,
        DiagnosticCode.MalformedFont,
        DiagnosticCode.UnsupportedMathDeviceAdjustment,
        DiagnosticCode.MissingMathConstruction,
        DiagnosticCode.MathVariantTooShort,
        DiagnosticCode.MissingConstructionOutlineEvidence,
        DiagnosticCode.InvalidConstructionPaintOwnership,
    )

    /** Returns null only when all supplied diagnostics are production-safe. */
    fun classify(
        layoutResult: MathLayoutResult,
        additionalDiagnostics: List<MathDiagnostic> = emptyList(),
    ): MathFormulaCapabilityResult.FallbackRequired? = classify(
        source = layoutResult.source,
        diagnostics = mergeDiagnostics(layoutResult.diagnostics, additionalDiagnostics),
    )

    fun classify(
        source: String,
        diagnostics: List<MathDiagnostic>,
    ): MathFormulaCapabilityResult.FallbackRequired? {
        val normalizedDiagnostics = mergeDiagnostics(diagnostics)
        val blocking = normalizedDiagnostics.filter(::requiresFallback)
        if (blocking.isEmpty()) return null

        val reasons = blocking
            .groupBy { category(it.code) }
            .entries
            .sortedBy { it.key.name }
            .map { (category, categoryDiagnostics) ->
                MathFormulaFallbackReason(category, categoryDiagnostics)
            }
        return MathFormulaCapabilityResult.FallbackRequired(
            source = source,
            diagnostics = normalizedDiagnostics,
            reasons = reasons,
        )
    }

    fun mergeDiagnostics(vararg groups: List<MathDiagnostic>): List<MathDiagnostic> = groups
        .flatMap { it }
        .distinctBy { listOf(it.code, it.message, it.range, it.severity) }
        .sortedWith(diagnosticComparator)

    fun requiresFallback(diagnostic: MathDiagnostic): Boolean =
        diagnostic.severity == DiagnosticSeverity.Error || diagnostic.code in blockingWarningCodes

    fun category(code: DiagnosticCode): MathFormulaCapabilityCategory = when (code) {
        DiagnosticCode.UnknownCommand,
        DiagnosticCode.UnsupportedCommand,
        DiagnosticCode.UnsupportedMathAlphabet,
        DiagnosticCode.UnsupportedDelimiter,
        -> MathFormulaCapabilityCategory.UnsupportedSyntax

        DiagnosticCode.TrailingEscape,
        DiagnosticCode.InvalidParameterMarker,
        DiagnosticCode.MissingMacroArgument,
        DiagnosticCode.MacroExpansionDepthExceeded,
        DiagnosticCode.MacroExpansionBudgetExceeded,
        DiagnosticCode.RecursiveMacro,
        DiagnosticCode.UnexpectedClosingGroup,
        DiagnosticCode.UnclosedGroup,
        DiagnosticCode.MissingScriptBase,
        DiagnosticCode.MissingScriptArgument,
        DiagnosticCode.DuplicateSubscript,
        DiagnosticCode.DuplicateSuperscript,
        DiagnosticCode.MissingCommandArgument,
        DiagnosticCode.MissingRadicalDegree,
        DiagnosticCode.UnclosedRadicalDegree,
        DiagnosticCode.MissingRadicalRadicand,
        DiagnosticCode.UnclosedRadicalRadicand,
        DiagnosticCode.MisplacedLimitsModifier,
        DiagnosticCode.MissingDelimiterAfterLeft,
        DiagnosticCode.MissingDelimiterAfterMiddle,
        DiagnosticCode.MissingDelimiterAfterRight,
        DiagnosticCode.UnexpectedRightDelimiter,
        DiagnosticCode.MiddleOutsideDelimitedGroup,
        DiagnosticCode.MissingRightDelimiter,
        -> MathFormulaCapabilityCategory.MalformedSource

        DiagnosticCode.MissingGlyph -> MathFormulaCapabilityCategory.MissingGlyph
        DiagnosticCode.MissingMathConstruction -> MathFormulaCapabilityCategory.MissingMathConstruction
        DiagnosticCode.MathVariantTooShort -> MathFormulaCapabilityCategory.InsufficientMathConstruction
        DiagnosticCode.MissingConstructionOutlineEvidence ->
            MathFormulaCapabilityCategory.ConstructionOutlineUnavailable
        DiagnosticCode.InvalidConstructionPaintOwnership ->
            MathFormulaCapabilityCategory.ConstructionPaintOwnershipInvalid

        DiagnosticCode.MissingMathTable,
        DiagnosticCode.MalformedFont,
        DiagnosticCode.UnsupportedMathDeviceAdjustment,
        -> MathFormulaCapabilityCategory.UnsupportedFontCapability
    }

    private val diagnosticComparator = compareBy<MathDiagnostic>(
        { it.range.start },
        { it.range.endExclusive },
        { it.code.name },
        { it.severity.name },
        { it.message },
    )
}
