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
        if (base is MathStyleDeclaration) return base
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
            MathTokenKind.Symbol -> symbolNode(token, TeXMathSymbolTable.literal(token.text))
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
        val spec = TeXMathSymbolTable.controlSymbol(token.text)
        if (spec == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.UnknownCommand,
                "Unknown control symbol \\${token.text}",
                token.range,
            )
            return MathErrorNode(sourceSlice(token.range), token.range)
        }
        return symbolNode(token, spec)
    }

    private fun parseControlWord(token: MathToken): MathNode {
        TeXMathSymbolTable.command(token.text)?.let { spec ->
            return symbolNode(token, spec)
        }
        styleCommands[token.text]?.let { level ->
            return MathStyleDeclaration(level, token.range)
        }
        if (token.text == "mathrm") {
            val argument = parseRequiredArgument(token, "roman math scope")
            return MathAlphabetScope(
                MathFamily.Operators,
                MathAlphabet.Roman,
                argument,
                token.range.cover(argument.range),
            )
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

    private fun symbolNode(token: MathToken, spec: TeXMathSymbolSpec): MathSymbol = MathSymbol(
        sourceText = sourceSlice(token.range),
        identity = spec.identity,
        atomClass = spec.atomClass,
        family = spec.family,
        familyBinding = spec.familyBinding,
        alphabet = spec.alphabet,
        range = token.range,
    )

    private fun skipIgnored() {
        while (peek().kind == MathTokenKind.Space || peek().kind == MathTokenKind.Comment) index++
    }

    private fun peek(): MathToken = tokens.getOrElse(index) { tokens.last() }
    private fun advance(): MathToken = peek().also { if (index < tokens.size) index++ }
    private fun sourceSlice(range: SourceRange): String =
        if (range.endExclusive <= source.length) source.substring(range.start, range.endExclusive) else ""

    private companion object {
        val styleCommands = mapOf(
            "displaystyle" to MathStyleLevel.Display,
            "textstyle" to MathStyleLevel.Text,
            "scriptstyle" to MathStyleLevel.Script,
            "scriptscriptstyle" to MathStyleLevel.ScriptScript,
        )

        val explicitlyUnsupportedCommands = setOf(
            "sqrt", "left", "right", "sum", "prod", "int", "oint", "overline", "underline",
            "hat", "bar", "vec", "begin", "end", "text", "operatorname", "limits", "nolimits",
            "matrix", "cases", "newcommand", "def", "color", "mathnormal", "mathit", "mathbf",
            "boldsymbol", "mathsf",
        )

    }
}
