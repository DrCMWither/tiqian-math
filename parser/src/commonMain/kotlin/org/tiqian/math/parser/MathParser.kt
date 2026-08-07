package org.tiqian.math.parser

import org.tiqian.math.core.*

fun interface MathFormulaParser {
    fun parse(source: String): MathParseResult
}

class MathParser(
    private val macros: List<MathMacroDefinition> = emptyList(),
    private val expansionLimits: MacroExpansionLimits = MacroExpansionLimits(),
) : MathFormulaParser {
    override fun parse(source: String): MathParseResult {
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

    private fun parseList(
        stopAtClosingGroup: Boolean,
        opening: MathToken?,
        unclosedCode: DiagnosticCode = DiagnosticCode.UnclosedGroup,
        unclosedMessage: String = "Group opened here is not closed",
    ): MathList {
        val children = mutableListOf<MathNode>()
        while (true) {
            skipIgnored()
            val token = peek()
            when (token.kind) {
                MathTokenKind.End -> {
                    if (stopAtClosingGroup && opening != null) {
                        diagnostics += MathDiagnostic(
                            unclosedCode,
                            unclosedMessage,
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
            if (
                base is MathOperator &&
                marker.kind == MathTokenKind.ControlWord &&
                marker.text in limitsModifiers
            ) {
                advance()
                base = base.copy(
                    limitsPolicy = if (marker.text == "limits") {
                        MathLimitsPolicy.Limits
                    } else {
                        MathLimitsPolicy.NoLimits
                    },
                    limitsModifierRange = marker.range,
                    range = base.range.cover(marker.range),
                )
                totalRange = totalRange.cover(marker.range)
                continue
            }
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

    private fun parseGroup(
        opening: MathToken,
        unclosedCode: DiagnosticCode = DiagnosticCode.UnclosedGroup,
        unclosedMessage: String = "Group opened here is not closed",
    ): MathGroup {
        val body = parseList(
            stopAtClosingGroup = true,
            opening = opening,
            unclosedCode = unclosedCode,
            unclosedMessage = unclosedMessage,
        )
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
        if (token.text == "left") return parseDelimited(token)
        if (token.text == "right") return parseStrayDelimiterCommand(token, MathDelimiterSide.Right)
        if (token.text == "middle") return parseStrayDelimiterCommand(token, MathDelimiterSide.Middle)
        TeXMathSymbolTable.largeOperator(token.text)?.let { identity ->
            return MathOperator(
                sourceText = sourceSlice(token.range),
                identity = identity,
                limitsPolicy = identity.defaultLimitsPolicy,
                commandRange = token.range,
                limitsModifierRange = null,
                range = token.range,
            )
        }
        TeXMathSymbolTable.command(token.text)?.let { spec ->
            return symbolNode(token, spec)
        }
        styleCommands[token.text]?.let { level ->
            return MathStyleDeclaration(level, token.range)
        }
        alphabetCommands[token.text]?.let { (family, alphabet) ->
            val argument = parseRequiredArgument(token, "math alphabet scope")
            return MathAlphabetScope(
                family,
                alphabet,
                argument,
                token.range.cover(argument.range),
            )
        }
        functionNames[token.text]?.let { policy ->
            return MathOperatorName(
                name = token.text,
                limitsPolicy = policy,
                commandRange = token.range,
                range = token.range,
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
        if (token.text == "sqrt") {
            val degree = parseOptionalRadicalDegree()
            val radicand = parseRadicalRadicand(token)
            val range = token.range
                .let { degree?.range?.let(it::cover) ?: it }
                .cover(radicand.range)
            return MathRadical(
                sourceText = sourceSlice(token.range),
                commandRange = token.range,
                degree = degree?.node,
                degreeRange = degree?.range,
                radicand = radicand,
                range = range,
            )
        }

        if (token.text in limitsModifiers) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MisplacedLimitsModifier,
                "Command \\${token.text} must immediately follow a large operator",
                token.range,
            )
            return MathErrorNode(sourceSlice(token.range), token.range)
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

    private fun parseDelimited(leftCommand: MathToken): MathDelimited {
        val left = parseDelimiterSpec(leftCommand, MathDelimiterSide.Left)
        val children = mutableListOf<MathNode>()
        var right: MathDelimiterSpec? = null
        while (right == null) {
            skipIgnored()
            val token = peek()
            when {
                token.kind == MathTokenKind.End || token.kind == MathTokenKind.CloseGroup -> {
                    val insertion = SourceRange(token.range.start, token.range.start)
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.MissingRightDelimiter,
                        "Delimited group opened by \\left is missing its matching \\right",
                        insertion,
                    )
                    right = syntheticInvisibleDelimiter(MathDelimiterSide.Right, insertion)
                }
                token.kind == MathTokenKind.ControlWord && token.text == "right" -> {
                    val command = advance()
                    right = parseDelimiterSpec(command, MathDelimiterSide.Right)
                }
                token.kind == MathTokenKind.ControlWord && token.text == "middle" -> {
                    val command = advance()
                    children += MathMiddleDelimiter(parseDelimiterSpec(command, MathDelimiterSide.Middle))
                }
                else -> parseAtomWithScripts()?.let(children::add)
            }
        }
        val bodyRange = when {
            children.isNotEmpty() -> children.first().range.cover(children.last().range)
            else -> SourceRange(left.range.endExclusive, left.range.endExclusive)
        }
        val body = MathList(children, bodyRange)
        return MathDelimited(
            left = left,
            body = body,
            right = right,
            range = leftCommand.range.cover(right.range),
        )
    }

    private fun parseStrayDelimiterCommand(command: MathToken, side: MathDelimiterSide): MathNode {
        val delimiter = parseDelimiterSpec(command, side)
        diagnostics += MathDiagnostic(
            if (side == MathDelimiterSide.Right) {
                DiagnosticCode.UnexpectedRightDelimiter
            } else {
                DiagnosticCode.MiddleOutsideDelimitedGroup
            },
            if (side == MathDelimiterSide.Right) {
                "Command \\right has no matching \\left"
            } else {
                "Command \\middle has no enclosing \\left ... \\right group"
            },
            delimiter.range,
        )
        return MathErrorNode(sourceSlice(delimiter.range), delimiter.range)
    }

    private fun parseDelimiterSpec(
        command: MathToken,
        side: MathDelimiterSide,
    ): MathDelimiterSpec {
        skipIgnored()
        val token = peek()
        if (token.kind in delimiterMissingKinds ||
            (token.kind == MathTokenKind.ControlWord && token.text in delimiterBoundaryCommands)
        ) {
            diagnostics += MathDiagnostic(
                when (side) {
                    MathDelimiterSide.Left -> DiagnosticCode.MissingDelimiterAfterLeft
                    MathDelimiterSide.Middle -> DiagnosticCode.MissingDelimiterAfterMiddle
                    MathDelimiterSide.Right -> DiagnosticCode.MissingDelimiterAfterRight
                },
                "Command \\${command.text} must be followed by a delimiter token",
                command.range,
            )
            val insertion = SourceRange(command.range.endExclusive, command.range.endExclusive)
            return syntheticInvisibleDelimiter(side, insertion, command.range)
        }

        advance()
        val identity = delimiterIdentity(token)
        val totalRange = command.range.cover(token.range)
        if (identity == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.UnsupportedDelimiter,
                "${sourceSlice(token.range)} is not a supported delimiter after \\${command.text}",
                token.range,
            )
        }
        return MathDelimiterSpec(
            sourceText = sourceSlice(token.range),
            identity = identity,
            side = side,
            commandRange = command.range,
            delimiterRange = token.range,
            range = totalRange,
        )
    }

    private fun syntheticInvisibleDelimiter(
        side: MathDelimiterSide,
        insertion: SourceRange,
        commandRange: SourceRange = insertion,
    ): MathDelimiterSpec = MathDelimiterSpec(
        sourceText = "",
        identity = MathDelimiterIdentity.Invisible,
        side = side,
        commandRange = commandRange,
        delimiterRange = insertion,
        range = if (commandRange.isEmpty) insertion else commandRange,
    )

    private fun delimiterIdentity(token: MathToken): MathDelimiterIdentity? = when (token.kind) {
        MathTokenKind.Symbol -> literalDelimiters[token.text]
        MathTokenKind.ControlSymbol -> controlSymbolDelimiters[token.text]
        MathTokenKind.ControlWord -> commandDelimiters[token.text]
        else -> null
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

    private fun parseOptionalRadicalDegree(): ParsedRadicalDegree? {
        skipIgnored()
        val opening = peek()
        if (opening.kind != MathTokenKind.Symbol || opening.text != "[") return null
        advance()

        val children = mutableListOf<MathNode>()
        var closing: MathToken? = null
        while (true) {
            skipIgnored()
            val token = peek()
            when {
                token.kind == MathTokenKind.End -> {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.UnclosedRadicalDegree,
                        "Optional radical degree opened here is not closed with ]",
                        opening.range,
                    )
                    break
                }
                token.kind == MathTokenKind.Symbol && token.text == "]" -> {
                    closing = advance()
                    break
                }
                token.kind == MathTokenKind.CloseGroup -> {
                    advance()
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.UnexpectedClosingGroup,
                        "Unexpected closing brace inside radical degree",
                        token.range,
                    )
                    children += MathErrorNode(token.text, token.range)
                }
                else -> parseAtomWithScripts()?.let(children::add)
            }
        }
        val bodyRange = when {
            children.isNotEmpty() -> children.first().range.cover(children.last().range)
            else -> SourceRange(opening.range.endExclusive, opening.range.endExclusive)
        }
        val range = closing?.let { opening.range.cover(it.range) } ?: opening.range.cover(bodyRange)
        if (children.isEmpty()) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingRadicalDegree,
                "Optional radical degree is empty",
                range,
            )
        }
        return ParsedRadicalDegree(
            node = if (children.isEmpty()) null else MathList(children, bodyRange),
            range = range,
        )
    }

    private fun parseRadicalRadicand(command: MathToken): MathNode {
        skipIgnored()
        val next = peek()
        if (next.kind in setOf(MathTokenKind.End, MathTokenKind.CloseGroup, MathTokenKind.Superscript, MathTokenKind.Subscript)) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingRadicalRadicand,
                "Command \\sqrt is missing its radicand",
                command.range,
            )
            return MathErrorNode("", SourceRange(command.range.endExclusive, command.range.endExclusive))
        }
        return if (next.kind == MathTokenKind.OpenGroup) {
            advance()
            parseGroup(
                opening = next,
                unclosedCode = DiagnosticCode.UnclosedRadicalRadicand,
                unclosedMessage = "Radicand group opened here is not closed",
            )
        } else {
            parsePrimary() ?: MathErrorNode("", next.range)
        }
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

        // LaTeX math-alphabet commands. Family only matters for MathNormal (Letters italicises,
        // Operators stays upright); an explicit alphabet drives the glyph directly.
        val alphabetCommands = mapOf(
            "mathrm" to (MathFamily.Operators to MathAlphabet.Roman),
            "mathnormal" to (MathFamily.Letters to MathAlphabet.MathNormal),
            "mathbf" to (MathFamily.Operators to MathAlphabet.Bold),
            "mathit" to (MathFamily.Letters to MathAlphabet.Italic),
            "mathsf" to (MathFamily.Operators to MathAlphabet.SansSerif),
            "mathbb" to (MathFamily.Operators to MathAlphabet.DoubleStruck),
            "mathfrak" to (MathFamily.Operators to MathAlphabet.Fraktur),
            "mathcal" to (MathFamily.Operators to MathAlphabet.Script),
            "mathscr" to (MathFamily.Operators to MathAlphabet.Script),
            "mathtt" to (MathFamily.Operators to MathAlphabet.Monospace),
            "boldsymbol" to (MathFamily.Letters to MathAlphabet.BoldItalic),
        )

        // Log-like function names (TeX \mathop). The "limits" group stacks scripts over/under in
        // display style; the rest keep side scripts. Amsmath's canonical limit operators are
        // det gcd inf injlim lim liminf limsup max min Pr projlim sup.
        val functionNames: Map<String, MathLimitsPolicy> = buildMap {
            listOf(
                "sin", "cos", "tan", "cot", "sec", "csc",
                "sinh", "cosh", "tanh", "coth",
                "arcsin", "arccos", "arctan",
                "exp", "log", "ln", "lg",
                "arg", "deg", "dim", "hom", "ker",
            ).forEach { put(it, MathLimitsPolicy.NoLimits) }
            listOf(
                "lim", "limsup", "liminf", "max", "min", "sup", "inf",
                "det", "gcd", "Pr", "injlim", "projlim",
            ).forEach { put(it, MathLimitsPolicy.Auto) }
        }

        val explicitlyUnsupportedCommands = setOf(
            "overline", "underline",
            "hat", "bar", "vec", "begin", "end", "text", "operatorname", "limits", "nolimits",
            "matrix", "cases", "newcommand", "def", "color",
        )

        val limitsModifiers = setOf("limits", "nolimits")

        val delimiterMissingKinds = setOf(
            MathTokenKind.End,
            MathTokenKind.CloseGroup,
            MathTokenKind.Superscript,
            MathTokenKind.Subscript,
        )
        val delimiterBoundaryCommands = setOf("left", "middle", "right")
        val literalDelimiters = mapOf(
            "(" to MathDelimiterIdentity.LeftParenthesis,
            ")" to MathDelimiterIdentity.RightParenthesis,
            "[" to MathDelimiterIdentity.LeftBracket,
            "]" to MathDelimiterIdentity.RightBracket,
            "|" to MathDelimiterIdentity.VerticalBar,
            "/" to MathDelimiterIdentity.Solidus,
            "." to MathDelimiterIdentity.Invisible,
        )
        val controlSymbolDelimiters = mapOf(
            "{" to MathDelimiterIdentity.LeftBrace,
            "}" to MathDelimiterIdentity.RightBrace,
            "|" to MathDelimiterIdentity.DoubleVerticalBar,
        )
        val commandDelimiters = mapOf(
            "vert" to MathDelimiterIdentity.VerticalBar,
            "Vert" to MathDelimiterIdentity.DoubleVerticalBar,
            "lvert" to MathDelimiterIdentity.VerticalBar,
            "rvert" to MathDelimiterIdentity.VerticalBar,
            "langle" to MathDelimiterIdentity.LeftAngleBracket,
            "rangle" to MathDelimiterIdentity.RightAngleBracket,
            "lfloor" to MathDelimiterIdentity.LeftFloor,
            "rfloor" to MathDelimiterIdentity.RightFloor,
            "lceil" to MathDelimiterIdentity.LeftCeiling,
            "rceil" to MathDelimiterIdentity.RightCeiling,
            "backslash" to MathDelimiterIdentity.ReverseSolidus,
            "uparrow" to MathDelimiterIdentity.UpArrow,
            "downarrow" to MathDelimiterIdentity.DownArrow,
            "updownarrow" to MathDelimiterIdentity.UpDownArrow,
            "Uparrow" to MathDelimiterIdentity.DoubleUpArrow,
            "Downarrow" to MathDelimiterIdentity.DoubleDownArrow,
            "Updownarrow" to MathDelimiterIdentity.DoubleUpDownArrow,
        )

    }

    private data class ParsedRadicalDegree(
        val node: MathNode?,
        val range: SourceRange,
    )
}
