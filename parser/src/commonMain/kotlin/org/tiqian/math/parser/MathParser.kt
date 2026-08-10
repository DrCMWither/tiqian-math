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
    private var structureDepth = 0

    fun parse(): MathParseResult {
        val parsedRoot = parseList(stopAtClosingGroup = false, opening = null)
        val root = foldTopLevelDisplayRows(parsedRoot)
        val completeDisplay = root.children.singleOrNull().let { only ->
            when (only) {
                is MathDisplayEnvironment -> only
                is MathDisplayRows -> only.rows.singleOrNull()
                    ?.body
                    ?.children
                    ?.singleOrNull() as? MathDisplayEnvironment
                else -> null
            }
        }
        root.children.flatMap { child ->
            when (child) {
                is MathDisplayEnvironment -> listOf(child)
                is MathDisplayRows -> child.rows.flatMap { row -> row.body.children.filterIsInstance<MathDisplayEnvironment>() }
                else -> emptyList()
            }
        }.filter { it !== completeDisplay }.forEach { display ->
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MisplacedDisplayEnvironment,
                    "Display environment ${display.kind.sourceName} must be the complete formula source",
                    display.range,
                )
        }
        return MathParseResult(source, root, diagnostics.toList())
    }

    private fun foldTopLevelDisplayRows(root: MathList): MathList {
        if (root.children.none { it is MathExplicitRowBreak }) return root
        val rows = mutableListOf<MathDisplayRow>()
        var pending = mutableListOf<MathNode>()
        var rowStart = root.range.start

        fun finishRow(separator: MathExplicitRowBreak?, allowEmpty: Boolean) {
            if (pending.isEmpty() && !allowEmpty) return
            val bodyRange = if (pending.isEmpty()) {
                SourceRange(rowStart, rowStart)
            } else {
                pending.first().range.cover(pending.last().range)
            }
            rows += MathDisplayRow(
                body = MathList(pending.toList(), bodyRange),
                rowSeparatorRange = separator?.separatorRange,
                additionalSpacing = separator?.additionalSpacing,
                range = bodyRange,
            )
            pending = mutableListOf()
            rowStart = separator?.additionalSpacing?.range?.endExclusive
                ?: separator?.separatorRange?.endExclusive
                ?: bodyRange.endExclusive
        }

        root.children.forEach { child ->
            if (child is MathExplicitRowBreak) {
                finishRow(child, allowEmpty = true)
            } else {
                pending += child
            }
        }
        finishRow(separator = null, allowEmpty = rows.isEmpty())
        return MathList(
            children = listOf(MathDisplayRows(rows, root.range)),
            range = root.range,
        )
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
                else -> parseAtomWithScripts()?.let { children.appendParsedNode(it) }
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
        if (base is MathStyleDeclaration || base is MathAlphabetDeclaration || base is MathExplicitRowBreak) {
            return base
        }
        var superscript: MathNode? = null
        var subscript: MathNode? = null
        var totalRange = base.range
        while (true) {
            skipIgnored()
            val marker = peek()
            val modifiedOperator = if (
                marker.kind == MathTokenKind.ControlWord && marker.text in limitsModifiers
            ) {
                val policy = if (marker.text == "limits") MathLimitsPolicy.Limits else MathLimitsPolicy.NoLimits
                when (base) {
                    is MathOperator -> base.copy(
                        limitsPolicy = policy,
                        limitsModifierRange = marker.range,
                        range = base.range.cover(marker.range),
                    )
                    is MathOperatorName -> base.copy(
                        limitsPolicy = policy,
                        limitsModifierRange = marker.range,
                        range = base.range.cover(marker.range),
                    )
                    is MathOperatorNoad -> base.copy(
                        limitsPolicy = policy,
                        limitsModifierRange = marker.range,
                        range = base.range.cover(marker.range),
                    )
                    is MathBraceNoad -> base.copy(
                        limitsPolicy = policy,
                        limitsModifierRange = marker.range,
                        range = base.range.cover(marker.range),
                    )
                    else -> null
                }
            } else {
                null
            }
            if (modifiedOperator != null) {
                advance()
                base = modifiedOperator
                totalRange = totalRange.cover(marker.range)
                continue
            }
            if (marker.kind != MathTokenKind.Superscript && marker.kind != MathTokenKind.Subscript) break
            advance()
            if (base is MathDisplayEnvironment) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MisplacedDisplayEnvironment,
                    "Display environment ${base.kind.sourceName} cannot be used as a scripted math atom",
                    marker.range,
                )
            }
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
            MathTokenKind.Symbol -> if (token.text == "&") {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.UnexpectedAlignmentTab,
                    "Alignment tab & is only valid inside a supported table environment",
                    token.range,
                )
                MathErrorNode(token.text, token.range)
            } else if (token.text.scalarValues().all { it.isCjkMathTextScalar() }) {
                MathText(
                    segments = listOf(MathTextSegment(token.text, token.range)),
                    commandRange = SourceRange(token.range.start, token.range.start),
                    contentRange = token.range,
                    range = token.range,
                    origin = MathTextOrigin.ImplicitCjk,
                )
            } else {
                symbolNode(token, TeXMathSymbolTable.literal(token.text))
            }
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
        structureDepth += 1
        val body = try {
            parseList(
                stopAtClosingGroup = true,
                opening = opening,
                unclosedCode = unclosedCode,
                unclosedMessage = unclosedMessage,
            )
        } finally {
            structureDepth -= 1
        }
        val closing = if (peek().kind == MathTokenKind.CloseGroup) advance() else null
        val range = if (closing != null) opening.range.cover(closing.range) else opening.range.cover(body.range)
        return MathGroup(body, range)
    }

    private fun parseControlSymbol(token: MathToken): MathNode {
        explicitControlSpaces[token.text]?.let { mu ->
            return MathExplicitSpace(sourceSlice(token.range), mu, token.range)
        }
        if (token.text == "\\") {
            if (structureDepth == 0) {
                val spacing = parseOptionalRowSpacing(token)
                return MathExplicitRowBreak(
                    separatorRange = token.range,
                    additionalSpacing = spacing,
                    range = spacing?.range?.let(token.range::cover) ?: token.range,
                )
            }
            diagnostics += MathDiagnostic(
                DiagnosticCode.UnexpectedRowSeparator,
                "Row separator \\\\ is only valid at formula top level or inside a supported table environment",
                token.range,
            )
            return MathErrorNode(sourceSlice(token.range), token.range)
        }
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
        if (token.text == "begin") return parseEnvironment(token)
        if (token.text == "end") {
            val name = parseEnvironmentName(token)
            val range = name?.totalRange?.let(token.range::cover) ?: token.range
            diagnostics += MathDiagnostic(
                DiagnosticCode.MismatchedEnvironmentEnd,
                "Command \\end has no matching \\begin",
                range,
            )
            return MathErrorNode(sourceSlice(range), range)
        }
        if (token.text == "left") return parseDelimited(token)
        if (token.text == "right") return parseStrayDelimiterCommand(token, MathDelimiterSide.Right)
        if (token.text == "middle") return parseStrayDelimiterCommand(token, MathDelimiterSide.Middle)
        fixedDelimiterCommands[token.text]?.let { command ->
            return parseFixedDelimiter(token, command)
        }
        explicitMathSpaces[token.text]?.let { mu ->
            return MathExplicitSpace(sourceSlice(token.range), mu, token.range)
        }
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
        if (token.text == "rm") {
            return MathAlphabetDeclaration(MathFamily.Operators, MathAlphabet.Roman, token.range)
        }
        if (token.text == "text") {
            val argument = parseTextArgument(token, "text content")
            return MathText(
                segments = argument.segments,
                commandRange = token.range,
                contentRange = argument.contentRange,
                range = token.range.cover(argument.totalRange),
            )
        }
        if (token.text == "operatorname") {
            skipIgnored()
            val starred = if (peek().kind == MathTokenKind.Symbol && peek().text == "*") advance() else null
            val argument = parseTextArgument(token, "operator name")
            return MathOperatorName(
                name = argument.segments.joinToString("") { it.text },
                limitsPolicy = if (starred == null) MathLimitsPolicy.NoLimits else MathLimitsPolicy.Auto,
                commandRange = token.range,
                nameSegments = argument.segments,
                nameRange = argument.contentRange,
                origin = MathOperatorNameOrigin.OperatorNameCommand,
                range = token.range.cover(starred?.range ?: token.range).cover(argument.totalRange),
            )
        }
        if (token.text == "mathop") {
            val nucleus = parseRequiredArgument(token, "operator nucleus")
            return MathOperatorNoad(
                nucleus = nucleus,
                limitsPolicy = MathLimitsPolicy.Auto,
                commandRange = token.range,
                range = token.range.cover(nucleus.range),
            )
        }
        accentCommands[token.text]?.let { identity ->
            val argument = parseRequiredArgument(token, "accent base")
            return MathAccent(identity, token.range, argument, token.range.cover(argument.range))
        }
        if (token.text == "overbrace" || token.text == "underbrace") {
            val base = parseRequiredArgument(token, "brace base")
            return MathBraceNoad(
                kind = if (token.text == "overbrace") MathBraceKind.Over else MathBraceKind.Under,
                base = base,
                commandRange = token.range,
                range = token.range.cover(base.range),
            )
        }
        ruleDecorationCommands[token.text]?.let { kind ->
            val argument = parseRequiredArgument(token, "rule decoration base")
            return MathRuleDecoration(kind, token.range, argument, token.range.cover(argument.range))
        }
        overUnderCommands[token.text]?.let { kind ->
            val annotation = parseRequiredArgument(token, "annotation")
            val base = parseRequiredArgument(token, "base")
            return MathOverUnder(
                kind = kind,
                annotation = annotation,
                base = base,
                atomClass = if (kind == MathOverUnderKind.StackRel) {
                    MathAtomClass.Relation
                } else {
                    binRelClass(base)
                },
                commandRange = token.range,
                range = token.range.cover(annotation.range).cover(base.range),
            )
        }
        extensibleArrowCommands[token.text]?.let { identity ->
            val below = parseOptionalExtensibleArrowBelow()
            val above = parseRequiredArgument(token, "upper label")
            if (above is MathErrorNode && above.range.isEmpty) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MissingExtensibleArrowLabel,
                    "Command \\${token.text} requires an upper label",
                    token.range,
                )
            }
            return MathExtensibleArrow(
                identity = identity,
                above = above,
                below = below?.node,
                commandRange = token.range,
                belowRange = below?.range,
                range = token.range
                    .let { below?.range?.let(it::cover) ?: it }
                    .cover(above.range),
            )
        }
        if (token.text == "boldsymbol") {
            val argument = parseRequiredArgument(token, "bold math version scope")
            return MathVersionScope(
                version = MathVersion.Bold,
                body = argument,
                range = token.range.cover(argument.range),
            )
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
        if (token.text == "frac" || token.text == "binom" || token.text == "dfrac" || token.text == "cfrac") {
            val continuedAlignment = if (token.text == "cfrac") parseContinuedFractionAlignment() else null
            val numerator = parseRequiredArgument(token, "numerator")
            val denominator = parseRequiredArgument(token, "denominator")
            val origin = when (token.text) {
                "binom" -> MathFractionOrigin.Binomial
                "dfrac" -> MathFractionOrigin.DisplayFraction
                "cfrac" -> MathFractionOrigin.ContinuedFraction
                else -> MathFractionOrigin.Fraction
            }
            return MathFraction(
                numerator = numerator,
                denominator = denominator,
                kind = if (origin == MathFractionOrigin.Binomial) FractionKind.Ruleless else FractionKind.Barred,
                hasParentheses = origin == MathFractionOrigin.Binomial,
                range = token.range.cover(numerator.range).cover(denominator.range),
                origin = origin,
                styleOverride = if (origin == MathFractionOrigin.DisplayFraction || origin == MathFractionOrigin.ContinuedFraction) {
                    MathStyleLevel.Display
                } else {
                    null
                },
                numeratorAlignment = continuedAlignment?.alignment ?: MathFractionAlignment.Center,
                numeratorStrut = origin == MathFractionOrigin.ContinuedFraction,
                retainRightNullDelimiterSpace = origin != MathFractionOrigin.ContinuedFraction,
                commandRange = token.range,
                alignmentRange = continuedAlignment?.range,
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
                "Command \\${token.text} must immediately follow an operator",
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

    private fun parseEnvironment(beginCommand: MathToken): MathNode {
        val parsedName = parseEnvironmentName(beginCommand)
        if (parsedName == null) {
            return MathErrorNode(sourceSlice(beginCommand.range), beginCommand.range)
        }
        val displayEnvironment = displayEnvironments[parsedName.name]
        if (displayEnvironment != null && structureDepth > 0) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MisplacedDisplayEnvironment,
                "Display environment ${parsedName.name} cannot be nested inside a math atom or another environment",
                beginCommand.range.cover(parsedName.totalRange),
            )
        }
        if (displayEnvironment != null && !displayEnvironment.alignment) {
            return parseSingleEquationEnvironment(beginCommand, parsedName, displayEnvironment)
        }
        val environment = tableEnvironments[parsedName.name]
            ?: if (displayEnvironment?.alignment == true) MathTableEnvironment.Aligned else null
        if (environment == null && displayEnvironment == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.UnsupportedEnvironment,
                "Environment ${parsedName.name} is not supported",
                parsedName.contentRange,
            )
        }
        val columnAlignments = if (environment == MathTableEnvironment.Array) {
            parseArrayColumnSpecification(beginCommand)
        } else {
            emptyList()
        }

        val rows = mutableListOf<MathTableRow>()
        var currentNodes = mutableListOf<MathNode>()
        var currentCells = mutableListOf<MathTableCell>()
        var currentCellStart = parsedName.totalRange.endExclusive
        var currentRowStart = currentCellStart
        var endCommand: MathToken? = null
        var endName: ParsedEnvironmentName? = null

        fun finishCell(separatorRange: SourceRange?) {
            val range = if (currentNodes.isEmpty()) {
                val insertion = separatorRange?.start ?: peek().range.start
                SourceRange(insertion, insertion)
            } else {
                currentNodes.first().range.cover(currentNodes.last().range)
            }
            currentCells += MathTableCell(
                body = MathList(currentNodes.toList(), range),
                columnSeparatorRange = separatorRange,
                range = range,
            )
            currentNodes = mutableListOf()
            currentCellStart = separatorRange?.endExclusive ?: range.endExclusive
        }

        fun finishRow(
            separatorRange: SourceRange?,
            additionalSpacing: MathTeXDimension?,
            allowEmpty: Boolean,
        ) {
            finishCell(null)
            val hasContent = currentCells.any { it.body.children.isNotEmpty() }
            if (hasContent || allowEmpty) {
                val range = currentCells.firstOrNull()?.range?.cover(currentCells.last().range)
                    ?: SourceRange(currentRowStart, currentRowStart)
                rows += MathTableRow(
                    cells = currentCells.toList(),
                    rowSeparatorRange = separatorRange,
                    additionalSpacing = additionalSpacing,
                    range = range,
                )
            }
            currentCells = mutableListOf()
            currentRowStart = additionalSpacing?.range?.endExclusive
                ?: separatorRange?.endExclusive
                ?: currentCellStart
            currentCellStart = currentRowStart
        }

        structureDepth += 1
        try {
            while (true) {
                skipIgnored()
                val token = peek()
                when {
                    token.kind == MathTokenKind.End -> {
                        diagnostics += MathDiagnostic(
                            DiagnosticCode.MissingEnvironmentEnd,
                            "Environment ${parsedName.name} is missing \\end{${parsedName.name}}",
                            beginCommand.range.cover(parsedName.totalRange),
                        )
                        finishRow(null, null, rows.isEmpty())
                        break
                    }
                    token.kind == MathTokenKind.ControlWord && token.text == "end" -> {
                        val closingCommand = advance()
                        val closingName = parseEnvironmentName(closingCommand)
                        endCommand = closingCommand
                        endName = closingName
                        if (closingName == null || closingName.name != parsedName.name) {
                            val range = closingName?.totalRange?.let(closingCommand.range::cover) ?: closingCommand.range
                            diagnostics += MathDiagnostic(
                                DiagnosticCode.MismatchedEnvironmentEnd,
                                "Expected \\end{${parsedName.name}} but found \\end{${closingName?.name.orEmpty()}}",
                                range,
                            )
                        }
                        finishRow(null, null, rows.isEmpty())
                        break
                    }
                    token.kind == MathTokenKind.Symbol && token.text == "&" -> {
                        val separator = advance()
                        finishCell(separator.range)
                    }
                    token.kind == MathTokenKind.ControlSymbol && token.text == "\\" -> {
                        val separator = advance()
                        val additionalSpacing = parseOptionalRowSpacing(separator)
                        finishRow(separator.range, additionalSpacing, true)
                    }
                    else -> parseAtomWithScripts()?.let { currentNodes.appendParsedNode(it) }
                }
            }
        } finally {
            structureDepth -= 1
        }

        val finalRange = endName?.totalRange?.let(beginCommand.range::cover)
            ?: rows.lastOrNull()?.range?.let(beginCommand.range::cover)
            ?: beginCommand.range.cover(parsedName.totalRange)
        val table = MathTable(
            environmentName = parsedName.name,
            environment = environment,
            rows = rows,
            columnAlignments = columnAlignments,
            beginCommandRange = beginCommand.range,
            beginNameRange = parsedName.contentRange,
            endCommandRange = endCommand?.range,
            endNameRange = endName?.contentRange,
            range = finalRange,
        )
        return if (displayEnvironment != null) {
            MathDisplayEnvironment(
                kind = displayEnvironment,
                body = table,
                beginCommandRange = beginCommand.range,
                beginNameRange = parsedName.contentRange,
                endCommandRange = endCommand?.range,
                endNameRange = endName?.contentRange,
                range = finalRange,
            )
        } else {
            table
        }
    }

    private fun parseSingleEquationEnvironment(
        beginCommand: MathToken,
        parsedName: ParsedEnvironmentName,
        kind: MathDisplayEnvironmentKind,
    ): MathDisplayEnvironment {
        val children = mutableListOf<MathNode>()
        var endCommand: MathToken? = null
        var endName: ParsedEnvironmentName? = null
        structureDepth += 1
        try {
            while (true) {
                skipIgnored()
                val token = peek()
                when {
                    token.kind == MathTokenKind.End -> {
                        diagnostics += MathDiagnostic(
                            DiagnosticCode.MissingEnvironmentEnd,
                            "Environment ${parsedName.name} is missing \\end{${parsedName.name}}",
                            beginCommand.range.cover(parsedName.totalRange),
                        )
                        break
                    }
                    token.kind == MathTokenKind.ControlWord && token.text == "end" -> {
                        val closingCommand = advance()
                        val closingName = parseEnvironmentName(closingCommand)
                        endCommand = closingCommand
                        endName = closingName
                        if (closingName == null || closingName.name != parsedName.name) {
                            val range = closingName?.totalRange?.let(closingCommand.range::cover) ?: closingCommand.range
                            diagnostics += MathDiagnostic(
                                DiagnosticCode.MismatchedEnvironmentEnd,
                                "Expected \\end{${parsedName.name}} but found \\end{${closingName?.name.orEmpty()}}",
                                range,
                            )
                        }
                        break
                    }
                    else -> parseAtomWithScripts()?.let { children.appendParsedNode(it) }
                }
            }
        } finally {
            structureDepth -= 1
        }
        val bodyRange = if (children.isEmpty()) {
            SourceRange(parsedName.totalRange.endExclusive, parsedName.totalRange.endExclusive)
        } else {
            children.first().range.cover(children.last().range)
        }
        val finalRange = endName?.totalRange?.let(beginCommand.range::cover)
            ?: children.lastOrNull()?.range?.let(beginCommand.range::cover)
            ?: beginCommand.range.cover(parsedName.totalRange)
        return MathDisplayEnvironment(
            kind = kind,
            body = MathList(children, bodyRange),
            beginCommandRange = beginCommand.range,
            beginNameRange = parsedName.contentRange,
            endCommandRange = endCommand?.range,
            endNameRange = endName?.contentRange,
            range = finalRange,
        )
    }

    private fun parseOptionalRowSpacing(separator: MathToken): MathTeXDimension? {
        skipIgnored()
        val opening = peek()
        if (opening.kind != MathTokenKind.Symbol || opening.text != "[") return null
        advance()
        val contentStart = opening.range.endExclusive
        var closing: MathToken? = null
        while (peek().kind != MathTokenKind.End) {
            val token = peek()
            if (token.kind == MathTokenKind.Symbol && token.text == "]") {
                closing = advance()
                break
            }
            if (
                token.kind == MathTokenKind.ControlSymbol && token.text == "\\" ||
                token.kind == MathTokenKind.ControlWord && token.text == "end"
            ) {
                break
            }
            advance()
        }
        val contentEnd = closing?.range?.start ?: peek().range.start
        val contentRange = SourceRange(contentStart, contentEnd.coerceAtLeast(contentStart))
        val totalRange = closing?.range?.let(opening.range::cover) ?: opening.range.cover(contentRange)
        if (closing == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.InvalidRowSpacing,
                "Optional row spacing after \\\\ is not closed",
                separator.range.cover(totalRange),
            )
            return null
        }
        val sourceText = sourceSlice(contentRange)
        val match = rowSpacingPattern.matchEntire(sourceText.trim())
        if (match == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.InvalidRowSpacing,
                "Optional row spacing must be a supported TeX dimension",
                contentRange,
            )
            return null
        }
        val value = match.groupValues[1].toFloatOrNull()
        val unitName = match.groupValues[2].lowercase()
        val unit = rowSpacingUnits[unitName]
        if (value == null || !value.isFinite() || unit == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.InvalidRowSpacing,
                "Optional row spacing ${sourceText.trim()} is not a finite supported TeX dimension",
                contentRange,
            )
            return null
        }
        return MathTeXDimension(
            value = value,
            unit = unit,
            sourceText = sourceText,
            contentRange = contentRange,
            range = totalRange,
        )
    }

    private fun parseEnvironmentName(command: MathToken): ParsedEnvironmentName? {
        skipIgnored()
        val opening = peek()
        if (opening.kind != MathTokenKind.OpenGroup) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingEnvironmentName,
                "Command \\${command.text} requires a braced environment name",
                command.range,
            )
            return null
        }
        advance()
        val contentStart = opening.range.endExclusive
        var closing: MathToken? = null
        while (peek().kind != MathTokenKind.End) {
            val token = advance()
            if (token.kind == MathTokenKind.CloseGroup) {
                closing = token
                break
            }
            if (token.kind == MathTokenKind.OpenGroup) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MissingEnvironmentName,
                    "Environment names cannot contain nested groups",
                    token.range,
                )
            }
        }
        val contentEnd = closing?.range?.start ?: peek().range.start
        val contentRange = SourceRange(contentStart, contentEnd.coerceAtLeast(contentStart))
        if (closing == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.UnclosedGroup,
                "Environment name opened here is not closed",
                opening.range,
            )
        }
        val name = sourceSlice(contentRange).trim()
        if (name.isEmpty()) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingEnvironmentName,
                "Environment name must not be empty",
                contentRange,
            )
        }
        return ParsedEnvironmentName(
            name = name,
            contentRange = contentRange,
            totalRange = closing?.let { opening.range.cover(it.range) } ?: opening.range.cover(contentRange),
        )
    }

    private fun parseArrayColumnSpecification(command: MathToken): List<MathTableColumnAlignment> {
        val parsed = parseEnvironmentName(command)
        if (parsed == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingArrayColumnSpecification,
                "Array environment requires a braced column specification",
                command.range,
            )
            return emptyList()
        }
        val alignments = parsed.name.mapNotNull { character ->
            when (character) {
                'l' -> MathTableColumnAlignment.Left
                'c' -> MathTableColumnAlignment.Center
                'r' -> MathTableColumnAlignment.Right
                else -> {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.InvalidArrayColumnSpecification,
                        "Array column specifier $character is not supported",
                        parsed.contentRange,
                    )
                    null
                }
            }
        }
        if (alignments.isEmpty()) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingArrayColumnSpecification,
                "Array environment needs at least one l, c, or r column",
                parsed.contentRange,
            )
        }
        return alignments
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
                else -> parseAtomWithScripts()?.let { children.appendParsedNode(it) }
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

    private fun parseFixedDelimiter(
        command: MathToken,
        fixed: FixedDelimiterCommand,
    ): MathFixedDelimiter {
        skipIgnored()
        val token = peek()
        if (token.kind in delimiterMissingKinds ||
            (token.kind == MathTokenKind.ControlWord && token.text in delimiterBoundaryCommands)
        ) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingDelimiterAfterFixedSizeCommand,
                "Command \\${command.text} must be followed by a delimiter token",
                command.range,
            )
            val insertion = SourceRange(command.range.endExclusive, command.range.endExclusive)
            return MathFixedDelimiter(
                delimiter = syntheticInvisibleDelimiter(
                    side = fixed.role.asDelimiterSide(),
                    insertion = insertion,
                    commandRange = command.range,
                ),
                size = fixed.size,
                role = fixed.role,
                range = command.range,
            )
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
        return MathFixedDelimiter(
            delimiter = MathDelimiterSpec(
                sourceText = sourceSlice(token.range),
                identity = identity,
                side = fixed.role.asDelimiterSide(),
                commandRange = command.range,
                delimiterRange = token.range,
                range = totalRange,
            ),
            size = fixed.size,
            role = fixed.role,
            range = totalRange,
        )
    }

    private fun MathFixedDelimiterRole.asDelimiterSide(): MathDelimiterSide = when (this) {
        MathFixedDelimiterRole.Opening -> MathDelimiterSide.Left
        MathFixedDelimiterRole.Closing -> MathDelimiterSide.Right
        MathFixedDelimiterRole.Ordinary,
        MathFixedDelimiterRole.Relation,
        -> MathDelimiterSide.Middle
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

    private fun parseTextArgument(command: MathToken, role: String): ParsedTextArgument {
        skipIgnored()
        val opening = peek()
        if (opening.kind != MathTokenKind.OpenGroup) {
            if (opening.kind !in setOf(
                    MathTokenKind.End,
                    MathTokenKind.CloseGroup,
                    MathTokenKind.Superscript,
                    MathTokenKind.Subscript,
                )
            ) {
                val token = advance()
                val text = when (token.kind) {
                    MathTokenKind.ControlSymbol -> textControlSymbols[token.text]
                    MathTokenKind.Symbol, MathTokenKind.Space -> sourceSlice(token.range)
                    else -> null
                }
                if (text != null) {
                    return ParsedTextArgument(
                        segments = listOf(MathTextSegment(text, token.range)),
                        contentRange = token.range,
                        totalRange = token.range,
                    )
                }
            }
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingCommandArgument,
                "Command \\${command.text} requires a text token or braced $role",
                command.range,
            )
            val insertion = SourceRange(command.range.endExclusive, command.range.endExclusive)
            return ParsedTextArgument(emptyList(), insertion, insertion)
        }
        advance()
        val segments = mutableListOf<MathTextSegment>()
        fun appendText(text: String, range: SourceRange) {
            if (text.isEmpty()) return
            val previous = segments.lastOrNull()
            if (previous != null && previous.range.endExclusive == range.start) {
                segments[segments.lastIndex] = MathTextSegment(
                    previous.text + text,
                    previous.range.cover(range),
                )
            } else {
                segments += MathTextSegment(text, range)
            }
        }
        var depth = 1
        var closing: MathToken? = null
        var lastContentEnd = opening.range.endExclusive
        while (depth > 0) {
            val token = peek()
            when (token.kind) {
                MathTokenKind.End -> {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.UnclosedGroup,
                        "$role opened here is not closed",
                        opening.range,
                    )
                    break
                }
                MathTokenKind.OpenGroup -> {
                    advance()
                    depth += 1
                    lastContentEnd = token.range.endExclusive
                }
                MathTokenKind.CloseGroup -> {
                    advance()
                    depth -= 1
                    if (depth == 0) {
                        closing = token
                    } else {
                        lastContentEnd = token.range.endExclusive
                    }
                }
                MathTokenKind.Comment -> {
                    advance()
                    lastContentEnd = token.range.endExclusive
                }
                MathTokenKind.ControlWord -> {
                    advance()
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.UnsupportedCommand,
                        "Text-mode command \\${token.text} is not supported",
                        token.range,
                    )
                    lastContentEnd = token.range.endExclusive
                }
                MathTokenKind.ControlSymbol -> {
                    advance()
                    val decoded = textControlSymbols[token.text]
                    if (decoded == null) {
                        diagnostics += MathDiagnostic(
                            DiagnosticCode.UnsupportedCommand,
                            "Text-mode control symbol \\${token.text} is not supported",
                            token.range,
                        )
                    } else {
                        appendText(decoded, token.range)
                    }
                    lastContentEnd = token.range.endExclusive
                }
                else -> {
                    advance()
                    val rendered = if (token.kind == MathTokenKind.Symbol && token.text == "~") {
                        "\u00A0"
                    } else {
                        sourceSlice(token.range)
                    }
                    appendText(rendered, token.range)
                    lastContentEnd = token.range.endExclusive
                }
            }
        }
        val contentEnd = closing?.range?.start ?: lastContentEnd
        val contentRange = SourceRange(opening.range.endExclusive, contentEnd.coerceAtLeast(opening.range.endExclusive))
        val totalRange = closing?.let { opening.range.cover(it.range) } ?: opening.range.cover(contentRange)
        return ParsedTextArgument(segments, contentRange, totalRange)
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

    private fun String.scalarValues(): List<Int> = buildList {
        var offset = 0
        while (offset < length) {
            val first = this@scalarValues[offset]
            val scalar = if (first.isHighSurrogate() && offset + 1 < length && this@scalarValues[offset + 1].isLowSurrogate()) {
                ((first.code - 0xD800) shl 10) + (this@scalarValues[offset + 1].code - 0xDC00) + 0x10000
            } else {
                first.code
            }
            add(scalar)
            offset += if (scalar > 0xFFFF) 2 else 1
        }
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
                else -> parseAtomWithScripts()?.let { children.appendParsedNode(it) }
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

    private fun parseContinuedFractionAlignment(): ParsedFractionAlignment? {
        skipIgnored()
        val opening = peek()
        if (opening.kind != MathTokenKind.Symbol || opening.text != "[") return null
        advance()

        skipIgnored()
        val valueToken = peek()
        val alignment = if (valueToken.kind == MathTokenKind.Symbol && valueToken.text in setOf("l", "c", "r")) {
            advance()
            when (valueToken.text) {
                "l" -> MathFractionAlignment.Left
                "r" -> MathFractionAlignment.Right
                else -> MathFractionAlignment.Center
            }
        } else {
            diagnostics += MathDiagnostic(
                DiagnosticCode.InvalidContinuedFractionAlignment,
                "Continued-fraction alignment must be l, c, or r",
                valueToken.range,
            )
            if (valueToken.kind != MathTokenKind.End) advance()
            MathFractionAlignment.Center
        }

        skipIgnored()
        val closing = peek()
        val range = if (closing.kind == MathTokenKind.Symbol && closing.text == "]") {
            opening.range.cover(advance().range)
        } else {
            diagnostics += MathDiagnostic(
                DiagnosticCode.UnclosedContinuedFractionAlignment,
                "Continued-fraction alignment is not closed with ]",
                opening.range.cover(valueToken.range),
            )
            opening.range.cover(valueToken.range)
        }
        return ParsedFractionAlignment(alignment, range)
    }

    private fun parseOptionalExtensibleArrowBelow(): ParsedOptionalMathList? {
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
                        DiagnosticCode.UnclosedExtensibleArrowBelow,
                        "Optional extensible-arrow lower label opened here is not closed with ]",
                        opening.range,
                    )
                    break
                }
                token.kind == MathTokenKind.Symbol && token.text == "]" -> {
                    closing = advance()
                    break
                }
                token.kind == MathTokenKind.CloseGroup -> {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.UnclosedExtensibleArrowBelow,
                        "Optional extensible-arrow lower label is not closed before this group ends",
                        opening.range.cover(token.range),
                    )
                    break
                }
                else -> parseAtomWithScripts()?.let { children.appendParsedNode(it) }
            }
        }
        val bodyRange = if (children.isEmpty()) {
            SourceRange(opening.range.endExclusive, opening.range.endExclusive)
        } else {
            children.first().range.cover(children.last().range)
        }
        val range = closing?.let { opening.range.cover(it.range) } ?: opening.range.cover(bodyRange)
        return ParsedOptionalMathList(
            node = MathList(children, bodyRange),
            range = range,
        )
    }

    private fun binRelClass(node: MathNode): MathAtomClass = when (node) {
        is MathSymbol -> node.atomClass.takeIf { it == MathAtomClass.Binary || it == MathAtomClass.Relation }
        is MathExtensibleArrow -> MathAtomClass.Relation
        is MathOverUnder -> node.atomClass.takeIf { it == MathAtomClass.Binary || it == MathAtomClass.Relation }
        is MathScripts -> binRelClass(node.base).takeIf { it == MathAtomClass.Binary || it == MathAtomClass.Relation }
        is MathGroup -> node.body.children.singleOrNull()?.let(::binRelClass)
            ?.takeIf { it == MathAtomClass.Binary || it == MathAtomClass.Relation }
        is MathList -> node.children.singleOrNull()?.let(::binRelClass)
            ?.takeIf { it == MathAtomClass.Binary || it == MathAtomClass.Relation }
        else -> null
    } ?: MathAtomClass.Ordinary

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

    /**
     * Tokenization stays scalar based, but one implicit upright text atom must reach the host shaper
     * as one contiguous run. The provider, not the parser, owns grapheme and physical-face splits.
     */
    private fun MutableList<MathNode>.appendParsedNode(node: MathNode) {
        val previous = lastOrNull() as? MathText
        if (previous?.origin == MathTextOrigin.ImplicitCjk &&
            node is MathText && node.origin == MathTextOrigin.ImplicitCjk &&
            previous.range.endExclusive == node.range.start
        ) {
            this[lastIndex] = previous.copy(
                segments = previous.segments + node.segments,
                contentRange = previous.contentRange.cover(node.contentRange),
                range = previous.range.cover(node.range),
            )
        } else {
            add(node)
        }
    }

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

        val accentCommands = mapOf(
            "hat" to MathAccentIdentity.Hat,
            "bar" to MathAccentIdentity.Bar,
            "tilde" to MathAccentIdentity.Tilde,
            "dot" to MathAccentIdentity.Dot,
            "ddot" to MathAccentIdentity.DoubleDot,
            "vec" to MathAccentIdentity.Vec,
            "widehat" to MathAccentIdentity.WideHat,
            "widetilde" to MathAccentIdentity.WideTilde,
        )

        val ruleDecorationCommands = mapOf(
            "overline" to MathRuleDecorationKind.Overline,
            "underline" to MathRuleDecorationKind.Underline,
        )

        val overUnderCommands = mapOf(
            "overset" to MathOverUnderKind.Overset,
            "underset" to MathOverUnderKind.Underset,
            "stackrel" to MathOverUnderKind.StackRel,
        )

        val extensibleArrowCommands = mapOf(
            "xleftarrow" to MathExtensibleArrowIdentity.Left,
            "xrightarrow" to MathExtensibleArrowIdentity.Right,
        )

        val textControlSymbols = mapOf(
            " " to " ",
            "," to "\u2009",
            "\\" to "\\",
            "{" to "{",
            "}" to "}",
            "%" to "%",
            "#" to "#",
            "_" to "_",
            "&" to "&",
            "$" to "$",
        )

        val explicitlyUnsupportedCommands = setOf(
            "limits", "nolimits",
            "matrix", "cases", "newcommand", "def", "color",
        )

        val tableEnvironments = MathTableEnvironment.entries.associateBy { it.sourceName }
        val displayEnvironments = MathDisplayEnvironmentKind.entries.associateBy { it.sourceName }

        val rowSpacingPattern = Regex("([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*([A-Za-z]+)")
        val rowSpacingUnits = MathTeXDimensionUnit.entries.associateBy { it.sourceName }

        val explicitMathSpaces = mapOf(
            "quad" to 18f,
            "qquad" to 36f,
        )
        val explicitControlSpaces = mapOf(
            "," to 3f,
            ":" to 4f,
            ">" to 4f,
            ";" to 5f,
            " " to 6f,
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
            "lVert" to MathDelimiterIdentity.DoubleVerticalBar,
            "rVert" to MathDelimiterIdentity.DoubleVerticalBar,
            "lbrack" to MathDelimiterIdentity.LeftBracket,
            "rbrack" to MathDelimiterIdentity.RightBracket,
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

        val fixedDelimiterCommands = buildMap {
            MathFixedDelimiterSize.entries.forEach { size ->
                put(size.commandStem, FixedDelimiterCommand(size, MathFixedDelimiterRole.Ordinary))
                put("${size.commandStem}l", FixedDelimiterCommand(size, MathFixedDelimiterRole.Opening))
                put("${size.commandStem}m", FixedDelimiterCommand(size, MathFixedDelimiterRole.Relation))
                put("${size.commandStem}r", FixedDelimiterCommand(size, MathFixedDelimiterRole.Closing))
            }
        }
    }

    private data class ParsedTextArgument(
        val segments: List<MathTextSegment>,
        val contentRange: SourceRange,
        val totalRange: SourceRange,
    )

    private data class ParsedRadicalDegree(
        val node: MathNode?,
        val range: SourceRange,
    )

    private data class ParsedOptionalMathList(
        val node: MathNode,
        val range: SourceRange,
    )

    private data class ParsedFractionAlignment(
        val alignment: MathFractionAlignment,
        val range: SourceRange,
    )

    private data class FixedDelimiterCommand(
        val size: MathFixedDelimiterSize,
        val role: MathFixedDelimiterRole,
    )

    private data class ParsedEnvironmentName(
        val name: String,
        val contentRange: SourceRange,
        val totalRange: SourceRange,
    )
}
