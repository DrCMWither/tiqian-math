package org.tiqian.math.parser

import org.tiqian.math.core.*

class MathParser(
    private val macros: List<MathMacroDefinition> = emptyList(),
    private val expansionLimits: MacroExpansionLimits = MacroExpansionLimits(),
) {
    fun parse(source: String): MathParseResult {
        val tokenized = MathTokenizer().tokenize(source)
        val expanded = MathMacroExpander(macros, expansionLimits).expand(tokenized.tokens)
        return ParserState(
            source = source,
            tokens = expanded.tokens,
            diagnostics = (tokenized.diagnostics + expanded.diagnostics).toMutableList(),
        ).parse()
    }
}

private class ParserState(
    private val source: String,
    private val tokens: List<MathToken>,
    private val diagnostics: MutableList<MathDiagnostic>,
) {
    private var index = 0

    fun parse(): MathParseResult {
        val root = parseList(stopAtClosingGroup = false, opening = null)
        return MathParseResult(source, root, diagnostics.toList())
    }

    private fun parseList(stopAtClosingGroup: Boolean, opening: MathToken?): MathList {
        val children = mutableListOf<MathNode>()
        while (true) {
            skipIgnored()
            val token = peek()
            when (token.kind) {
                MathTokenKind.End -> {
                    if (stopAtClosingGroup && opening != null) {
                        diagnostics += MathDiagnostic(
                            DiagnosticCode.UnclosedGroup,
                            "Group opened here is not closed",
                            opening.range,
                        )
                    }
                    break
                }
                MathTokenKind.CloseGroup -> {
                    if (stopAtClosingGroup) break
                    advance()
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.UnexpectedClosingGroup,
                        "Unexpected closing brace",
                        token.range,
                    )
                    children += MathErrorNode(token.text, token.range)
                }
                else -> parseAtomWithScripts()?.let(children::add)
            }
        }

        val range = when {
            children.isNotEmpty() -> children.first().range.cover(children.last().range)
            opening != null -> SourceRange(opening.range.endExclusive, opening.range.endExclusive)
            else -> SourceRange(0, 0)
        }
        return MathList(children, range)
    }

    private fun parseAtomWithScripts(): MathNode? {
        val first = peek()
        if (first.kind == MathTokenKind.Superscript || first.kind == MathTokenKind.Subscript) {
            advance()
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingScriptBase,
                "Script marker has no base atom",
                first.range,
            )
            val argument = parseScriptArgument(first)
            return MathErrorNode(sourceSlice(first.range.cover(argument.range)), first.range.cover(argument.range))
        }

        var base = parsePrimary() ?: return null
        var superscript: MathNode? = null
        var subscript: MathNode? = null
        var totalRange = base.range
        while (true) {
            skipIgnored()
            val marker = peek()
            if (marker.kind != MathTokenKind.Superscript && marker.kind != MathTokenKind.Subscript) break
            advance()
            val argument = parseScriptArgument(marker)
            totalRange = totalRange.cover(marker.range).cover(argument.range)
            if (marker.kind == MathTokenKind.Superscript) {
                if (superscript == null) {
                    superscript = argument
                } else {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.DuplicateSuperscript,
                        "Base already has a superscript",
                        marker.range,
                    )
                }
            } else {
                if (subscript == null) {
                    subscript = argument
                } else {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.DuplicateSubscript,
                        "Base already has a subscript",
                        marker.range,
                    )
                }
            }
        }
        if (superscript != null || subscript != null) {
            base = MathScripts(base, superscript, subscript, totalRange)
        }
        return base
    }

    private fun parseScriptArgument(marker: MathToken): MathNode {
        skipIgnored()
        val next = peek()
        if (next.kind in setOf(MathTokenKind.End, MathTokenKind.CloseGroup, MathTokenKind.Superscript, MathTokenKind.Subscript)) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingScriptArgument,
                "Script marker requires an argument",
                marker.range,
            )
            return MathErrorNode("", SourceRange(marker.range.endExclusive, marker.range.endExclusive))
        }
        return parsePrimary() ?: MathErrorNode("", next.range)
    }

    private fun parsePrimary(): MathNode? {
        skipIgnored()
        val token = advance()
        return when (token.kind) {
            MathTokenKind.Symbol -> MathSymbol(
                sourceText = token.text,
                displayText = token.text,
                atomClass = classifyLiteral(token.text),
                variant = classifyVariant(token.text),
                range = token.range,
            )
            MathTokenKind.ControlSymbol -> parseControlSymbol(token)
            MathTokenKind.ControlWord -> parseControlWord(token)
            MathTokenKind.OpenGroup -> parseGroup(token)
            MathTokenKind.Parameter -> {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.InvalidParameterMarker,
                    "Parameter marker is only valid inside a registered macro replacement",
                    token.range,
                )
                MathErrorNode(token.text, token.range)
            }
            MathTokenKind.CloseGroup, MathTokenKind.End -> null
            MathTokenKind.Superscript, MathTokenKind.Subscript -> null
            MathTokenKind.Space, MathTokenKind.Comment -> null
        }
    }

    private fun parseGroup(opening: MathToken): MathGroup {
        val body = parseList(stopAtClosingGroup = true, opening = opening)
        val closing = if (peek().kind == MathTokenKind.CloseGroup) advance() else null
        val range = if (closing != null) opening.range.cover(closing.range) else opening.range.cover(body.range)
        return MathGroup(body, range)
    }

    private fun parseControlSymbol(token: MathToken): MathNode {
        val display = when (token.text) {
            "{", "}", "%", "#", "_", "^", "\\" -> token.text
            " " -> " "
            else -> null
        }
        if (display == null || display == " ") {
            diagnostics += MathDiagnostic(
                DiagnosticCode.UnknownCommand,
                "Unknown control symbol \\${token.text}",
                token.range,
            )
            return MathErrorNode(sourceSlice(token.range), token.range)
        }
        return MathSymbol(
            sourceSlice(token.range),
            display,
            classifyLiteral(display),
            classifyVariant(display),
            token.range,
        )
    }

    private fun parseControlWord(token: MathToken): MathNode {
        symbolCommands[token.text]?.let { mapped ->
            return MathSymbol(
                sourceSlice(token.range),
                mapped.display,
                mapped.atomClass,
                mapped.variant,
                token.range,
            )
        }
        styleCommands[token.text]?.let { level ->
            val argument = parseRequiredArgument(token, "style scope")
            return MathStyleScope(level, argument, token.range.cover(argument.range))
        }
        if (token.text == "mathrm") {
            val argument = parseRequiredArgument(token, "roman math scope")
            return MathVariantScope(MathVariant.Upright, argument, token.range.cover(argument.range))
        }
        if (token.text == "frac" || token.text == "binom") {
            val numerator = parseRequiredArgument(token, "numerator")
            val denominator = parseRequiredArgument(token, "denominator")
            return MathFraction(
                numerator = numerator,
                denominator = denominator,
                kind = if (token.text == "frac") FractionKind.Barred else FractionKind.Ruleless,
                hasParentheses = token.text == "binom",
                range = token.range.cover(numerator.range).cover(denominator.range),
            )
        }

        val code = if (token.text in explicitlyUnsupportedCommands) {
            DiagnosticCode.UnsupportedCommand
        } else {
            DiagnosticCode.UnknownCommand
        }
        diagnostics += MathDiagnostic(
            code,
            "Command \\${token.text} is not supported by the current Markdown-math slice",
            token.range,
        )
        return MathErrorNode(sourceSlice(token.range), token.range)
    }

    private fun parseRequiredArgument(command: MathToken, role: String): MathNode {
        skipIgnored()
        val next = peek()
        if (next.kind in setOf(MathTokenKind.End, MathTokenKind.CloseGroup, MathTokenKind.Superscript, MathTokenKind.Subscript)) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingCommandArgument,
                "Command \\${command.text} is missing its $role",
                command.range,
            )
            return MathErrorNode("", SourceRange(command.range.endExclusive, command.range.endExclusive))
        }
        return parsePrimary() ?: MathErrorNode("", next.range)
    }

    private fun skipIgnored() {
        while (peek().kind == MathTokenKind.Space || peek().kind == MathTokenKind.Comment) index++
    }

    private fun peek(): MathToken = tokens.getOrElse(index) { tokens.last() }
    private fun advance(): MathToken = peek().also { if (index < tokens.size) index++ }
    private fun sourceSlice(range: SourceRange): String =
        if (range.endExclusive <= source.length) source.substring(range.start, range.endExclusive) else ""

    private data class MappedSymbol(
        val display: String,
        val atomClass: MathAtomClass,
        val variant: MathVariant,
    )

    private companion object {
        val styleCommands = mapOf(
            "displaystyle" to MathStyleLevel.Display,
            "textstyle" to MathStyleLevel.Text,
            "scriptstyle" to MathStyleLevel.Script,
            "scriptscriptstyle" to MathStyleLevel.ScriptScript,
        )

        val lowercaseGreekVariables = (0x03B1..0x03C9).toSet()
        val greekVariantVariables = setOf(0x03F5, 0x03D1, 0x03F0, 0x03D5, 0x03F1, 0x03D6)
        val uprightTeXControlWords = setOf(
            "Gamma", "Delta", "Theta", "Lambda", "Pi", "Sigma", "Phi", "Omega", "infty",
        )

        val symbolCommands = buildMap {
            listOf(
                "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
                "epsilon" to "ε", "theta" to "θ", "lambda" to "λ", "mu" to "μ",
                "pi" to "π", "sigma" to "σ", "phi" to "φ", "omega" to "ω",
                "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
                "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Omega" to "Ω",
                "infty" to "∞", "partial" to "∂",
            ).forEach { (name, display) ->
                val variant = if (name in uprightTeXControlWords) {
                    MathVariant.Upright
                } else {
                    classifyVariant(display)
                }
                put(name, MappedSymbol(display, MathAtomClass.Ordinary, variant))
            }
            listOf(
                "cdot" to "⋅", "times" to "×", "pm" to "±", "div" to "÷",
            ).forEach { (name, display) ->
                put(name, MappedSymbol(display, MathAtomClass.Binary, MathVariant.Upright))
            }
            listOf(
                "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
                "neq" to "≠", "ne" to "≠", "in" to "∈", "to" to "→", "approx" to "≈",
            ).forEach { (name, display) ->
                put(name, MappedSymbol(display, MathAtomClass.Relation, MathVariant.Upright))
            }
        }

        val explicitlyUnsupportedCommands = setOf(
            "sqrt", "left", "right", "sum", "prod", "int", "oint", "overline", "underline",
            "hat", "bar", "vec", "begin", "end", "text", "operatorname", "limits", "nolimits",
            "matrix", "cases", "newcommand", "def", "color",
        )

        fun classifyLiteral(text: String): MathAtomClass = when (text) {
            "+", "-", "−", "*", "×", "⋅", "/", "±" -> MathAtomClass.Binary
            "=", "<", ">", "≤", "≥", "≠", "≈", "∈", "→" -> MathAtomClass.Relation
            "(", "[" -> MathAtomClass.Opening
            ")", "]" -> MathAtomClass.Closing
            ",", ";" -> MathAtomClass.Punctuation
            else -> MathAtomClass.Ordinary
        }

        /** MathML literal policy; TeX uppercase-Greek control words are overridden above. */
        fun classifyVariant(text: String): MathVariant {
            if (isExplicitMathematicalAlphanumeric(text)) return MathVariant.ExplicitUnicode
            val scalar = text.singleUnicodeScalarOrNull() ?: return MathVariant.Upright
            return if (
                scalar in 'A'.code..'Z'.code ||
                scalar in 'a'.code..'z'.code ||
                scalar in 0x0391..0x03A9 || scalar == 0x03F4 || scalar == 0x2207 ||
                scalar in lowercaseGreekVariables ||
                scalar in greekVariantVariables ||
                scalar == 0x2202 || scalar == 0x0131 || scalar == 0x0237
            ) {
                MathVariant.DefaultVariableItalic
            } else {
                MathVariant.Upright
            }
        }
    }
}
