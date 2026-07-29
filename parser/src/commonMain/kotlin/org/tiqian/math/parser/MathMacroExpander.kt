package org.tiqian.math.parser

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathDiagnostic
import org.tiqian.math.core.SourceRange

data class MathMacroDefinition(
    val name: String,
    val parameterCount: Int,
    val replacement: String,
) {
    init {
        require(name.matches(Regex("[A-Za-z]+"))) { "macro name must be an ASCII control word" }
        require(parameterCount in 0..9) { "macro parameter count must be between 0 and 9" }
    }
}

data class MacroExpansionLimits(
    val maximumDepth: Int = 32,
    val maximumOutputTokens: Int = 20_000,
)

data class MacroExpansionResult(
    val tokens: List<MathToken>,
    val diagnostics: List<MathDiagnostic>,
)

/**
 * Expands only macros supplied explicitly by the host. Source-level `newcommand`
 * is intentionally outside the Markdown-math slice.
 */
class MathMacroExpander(
    definitions: List<MathMacroDefinition> = emptyList(),
    private val limits: MacroExpansionLimits = MacroExpansionLimits(),
) {
    private val tokenizer = MathTokenizer()
    private val definitions = definitions.associateBy { it.name }
    private val replacements = definitions.associate { definition ->
        definition.name to tokenizer.tokenize(definition.replacement).tokens.filterNot { it.kind == MathTokenKind.End }
    }

    fun expand(input: List<MathToken>): MacroExpansionResult {
        val diagnostics = mutableListOf<MathDiagnostic>()
        val output = expandTokens(
            input.filterNot { it.kind == MathTokenKind.End },
            diagnostics,
            depth = 0,
            stack = emptyList(),
        ).toMutableList()
        val endRange = input.lastOrNull()?.range ?: SourceRange.Empty
        output += MathToken(MathTokenKind.End, "", SourceRange(endRange.endExclusive, endRange.endExclusive))
        return MacroExpansionResult(output, diagnostics)
    }

    private fun expandTokens(
        input: List<MathToken>,
        diagnostics: MutableList<MathDiagnostic>,
        depth: Int,
        stack: List<String>,
    ): List<MathToken> {
        if (depth > limits.maximumDepth) {
            val range = input.firstOrNull()?.range ?: SourceRange.Empty
            diagnostics += MathDiagnostic(
                DiagnosticCode.MacroExpansionDepthExceeded,
                "Macro expansion exceeded depth ${limits.maximumDepth}",
                range,
            )
            return input
        }

        val output = mutableListOf<MathToken>()
        var index = 0
        while (index < input.size) {
            val token = input[index]
            val definition = if (token.kind == MathTokenKind.ControlWord) definitions[token.text] else null
            if (definition == null) {
                output += token
                index++
                continue
            }
            if (definition.name in stack) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.RecursiveMacro,
                    "Recursive macro expansion: ${(stack + definition.name).joinToString(" -> ")}",
                    token.range,
                )
                output += token
                index++
                continue
            }

            val arguments = mutableListOf<List<MathToken>>()
            var cursor = index + 1
            var invocationRange = token.range
            var missing = false
            repeat(definition.parameterCount) { argumentIndex ->
                while (cursor < input.size && input[cursor].kind in ignoredKinds) cursor++
                if (cursor >= input.size) {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.MissingMacroArgument,
                        "Macro \\${definition.name} is missing argument ${argumentIndex + 1}",
                        invocationRange,
                    )
                    arguments.add(emptyList())
                    missing = true
                } else {
                    val argument = readArgument(input, cursor)
                    arguments += argument.tokens
                    cursor = argument.nextIndex
                    invocationRange = invocationRange.cover(argument.range)
                }
            }
            if (missing) {
                output += token
                index++
                continue
            }

            val substituted = replacements.getValue(definition.name).flatMap { replacementToken ->
                if (replacementToken.kind == MathTokenKind.Parameter) {
                    val argumentIndex = (replacementToken.parameterIndex ?: 1) - 1
                    arguments.getOrElse(argumentIndex) {
                        diagnostics += MathDiagnostic(
                            DiagnosticCode.InvalidParameterMarker,
                            "Macro \\${definition.name} references absent parameter #${argumentIndex + 1}",
                            invocationRange,
                        )
                        emptyList()
                    }
                } else {
                    listOf(replacementToken.copy(range = invocationRange))
                }
            }
            output += expandTokens(
                substituted,
                diagnostics,
                depth + 1,
                stack + definition.name,
            )
            index = cursor
            if (output.size > limits.maximumOutputTokens) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MacroExpansionBudgetExceeded,
                    "Macro expansion exceeded ${limits.maximumOutputTokens} output tokens",
                    invocationRange,
                )
                return output.take(limits.maximumOutputTokens)
            }
        }
        return output
    }

    private fun readArgument(tokens: List<MathToken>, start: Int): MacroArgument {
        val first = tokens[start]
        if (first.kind != MathTokenKind.OpenGroup) {
            return MacroArgument(listOf(first), start + 1, first.range)
        }

        var depth = 1
        var cursor = start + 1
        val body = mutableListOf<MathToken>()
        var range = first.range
        while (cursor < tokens.size && depth > 0) {
            val token = tokens[cursor]
            range = range.cover(token.range)
            when (token.kind) {
                MathTokenKind.OpenGroup -> {
                    depth++
                    body += token
                }
                MathTokenKind.CloseGroup -> {
                    depth--
                    if (depth > 0) body += token
                }
                else -> body += token
            }
            cursor++
        }
        return MacroArgument(body, cursor, range)
    }

    private data class MacroArgument(
        val tokens: List<MathToken>,
        val nextIndex: Int,
        val range: SourceRange,
    )

    private companion object {
        val ignoredKinds = setOf(MathTokenKind.Space, MathTokenKind.Comment)
    }
}
