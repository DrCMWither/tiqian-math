package org.tiqian.math.parser

import org.tiqian.math.core.*
import org.tiqian.math.parser.ParserState.Companion.accentCommands
import org.tiqian.math.parser.ParserState.Companion.alphabetCommands
import org.tiqian.math.parser.ParserState.Companion.explicitControlSpaces
import org.tiqian.math.parser.ParserState.Companion.explicitMathSpaces
import org.tiqian.math.parser.ParserState.Companion.explicitlyUnsupportedCommands
import org.tiqian.math.parser.ParserState.Companion.extensibleArrowCommands
import org.tiqian.math.parser.ParserState.Companion.fixedDelimiterCommands
import org.tiqian.math.parser.ParserState.Companion.functionNames
import org.tiqian.math.parser.ParserState.Companion.limitsModifiers
import org.tiqian.math.parser.ParserState.Companion.namedPaintColors
import org.tiqian.math.parser.ParserState.Companion.overUnderCommands
import org.tiqian.math.parser.ParserState.Companion.ruleDecorationCommands
import org.tiqian.math.parser.ParserState.Companion.styleCommands

internal fun ParserState.parseControlSymbol(token: MathToken): MathNode {
    explicitControlSpaces[token.text]?.let { mu ->
        return MathExplicitSpace(sourceSlice(token.range), mu, token.range)
    }
    if (token.text == "\\") {
        if (structureDepth == 0 || structureDepth in rowSeparatorContainerDepths) {
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

internal fun ParserState.parseControlWord(token: MathToken): MathNode {
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
    if (token.text == "tag") return parseEquationTag(token)
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
    if (token.text == "bf") {
        return MathVersionDeclaration(MathVersion.Bold, token.range)
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
    if (token.text == "textbf") {
        val argument = parseTextArgument(token, "bold text content", MathFontWeight.Bold)
        return MathText(
            segments = argument.segments,
            commandRange = token.range,
            contentRange = argument.contentRange,
            range = token.range.cover(argument.totalRange),
        )
    }
    if (token.text == "not") {
        val interveningSpaces = buildList {
            while (true) {
                skipIgnored()
                val next = peek()
                if (next.kind != MathTokenKind.ControlSymbol || next.text != "!") break
                val parsed = parsePrimary()
                if (parsed is MathExplicitSpace) add(parsed) else break
            }
        }
        skipIgnored()
        val next = peek()
        if (next.kind in setOf(
                MathTokenKind.End,
                MathTokenKind.CloseGroup,
                MathTokenKind.Superscript,
                MathTokenKind.Subscript,
            )
        ) {
            val errorRange = interveningSpaces.lastOrNull()?.let { token.range.cover(it.range) } ?: token.range
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingNegatedAtom,
                "Command \\not requires a following math atom",
                errorRange,
            )
            return MathErrorNode(sourceSlice(errorRange), errorRange)
        }
        val base = parsePrimary() ?: MathErrorNode("", next.range)
        return MathNegation(
            base = base,
            interveningSpaces = interveningSpaces,
            commandRange = token.range,
            range = token.range.cover(base.range),
        )
    }
    if (token.text == "cancel") {
        val body = parseRequiredArgument(token, "cancellation body")
        return MathCancel(body, token.range, token.range.cover(body.range))
    }
    if (token.text == "rule") {
        val raise = parseOptionalRuleRaise(token)
        val width = parseRuleDimensionArgument(token, "width", allowNegative = false)
        val height = parseRuleDimensionArgument(token, "height", allowNegative = false)
        if (width == null || height == null) {
            val range = token.range.cover((height ?: width ?: raise)?.range ?: token.range)
            return MathErrorNode(sourceSlice(range), range)
        }
        val range = token.range.cover(height.range)
        return MathRuleBox(width, height, raise, token.range, range)
    }
    if (token.text == "rlap" || token.text == "llap") {
        val body = parseRequiredArgument(token, "lap content")
        return MathLap(
            kind = if (token.text == "rlap") MathLapKind.Right else MathLapKind.Left,
            body = body,
            commandRange = token.range,
            range = token.range.cover(body.range),
        )
    }
    if (token.text == "hline") {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MisplacedHorizontalRule,
            "Command \\hline is only valid between rows of an array-like environment",
            token.range,
        )
        return MathErrorNode(sourceSlice(token.range), token.range)
    }
    if (token.text == "TeX" || token.text == "LaTeX") {
        return MathTexLogo(
            kind = if (token.text == "TeX") MathTexLogoKind.Tex else MathTexLogoKind.Latex,
            commandRange = token.range,
            range = token.range,
        )
    }
    ParserState.latexSizeScales[token.text]?.let { scale ->
        return MathSizeDeclaration(
            sourceName = token.text,
            scale = scale,
            commandRange = token.range,
            range = token.range,
        )
    }
    if (token.text == "color") {
        val argument = parseTextArgument(token, "color name")
        val sourceName = argument.segments.joinToString("") { it.text }.trim()
        val color = resolvePaintColorText(sourceName)
        val range = token.range.cover(argument.totalRange)
        if (color == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.UnknownColorName,
                "Unknown xcolor name '$sourceName'",
                argument.contentRange,
            )
            return MathErrorNode(sourceSlice(range), range)
        }
        return MathColorDeclaration(
            sourceName = sourceName,
            color = color,
            commandRange = token.range,
            nameRange = argument.contentRange,
            range = range,
        )
    }
    if (token.text == "boxed") {
        skipIgnored()
        val rowDepth = structureDepth + if (peek().kind == MathTokenKind.OpenGroup) 1 else 0
        rowSeparatorContainerDepths += rowDepth
        // A boxed field hosts display environments through the shared depth whitelist.
        boxDisplayContainerDepths += rowDepth
        val parsedBody = try {
            parseRequiredArgument(token, "boxed math field")
        } finally {
            rowSeparatorContainerDepths.removeAt(rowSeparatorContainerDepths.lastIndex)
            boxDisplayContainerDepths.removeAt(boxDisplayContainerDepths.lastIndex)
        }
        val group = parsedBody as? MathGroup
        val directChildren = group?.body?.children.orEmpty()
        val terminalCandidate = directChildren.lastOrNull() as? MathExplicitRowBreak
        val terminalRowSeparator = terminalCandidate?.takeIf {
            structureDepth == 0 && directChildren.dropLast(1).any { child -> child is MathEquationTag }
        }
        val normalizedChildren = directChildren.mapNotNull { child ->
            when {
                child === terminalRowSeparator -> null
                child is MathExplicitRowBreak -> {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.UnexpectedRowSeparator,
                        "Only an empty final row separator is accepted inside a boxed display field",
                        child.range,
                    )
                    MathErrorNode(sourceSlice(child.range), child.range)
                }
                else -> child
            }
        }
        val body = if (group == null) {
            parsedBody
        } else {
            group.copy(body = group.body.copy(children = normalizedChildren))
        }
        return MathBoxed(
            body = body,
            commandRange = token.range,
            terminalRowSeparator = terminalRowSeparator,
            range = token.range.cover(body.range),
        )
    }
    if (token.text == "bbox") {
        val parsedOptions = parseOptionalBboxOptions(token)
        val body = parseBboxRequiredArgument(token)
        return MathBbox(
            body = body,
            options = parsedOptions.options,
            commandRange = token.range,
            optionsRange = parsedOptions.totalRange,
            range = token.range.cover(body.range),
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
    if (token.text == "bmod" || token.text == "mod" || token.text == "pmod") {
        val argument = if (token.text == "pmod") parseRequiredArgument(token, "modulus") else null
        val kind = when (token.text) {
            "bmod" -> MathModuloKind.Binary
            "pmod" -> MathModuloKind.Parenthesized
            else -> MathModuloKind.Plain
        }
        return MathModulo(
            kind = kind,
            argument = argument,
            commandRange = token.range,
            range = argument?.range?.let(token.range::cover) ?: token.range,
        )
    }
    if (token.text == "substack") {
        skipIgnored()
        val rowDepth = structureDepth + if (peek().kind == MathTokenKind.OpenGroup) 1 else 0
        rowSeparatorContainerDepths += rowDepth
        val argument = try {
            parseRequiredArgument(token, "substack rows")
        } finally {
            rowSeparatorContainerDepths.removeAt(rowSeparatorContainerDepths.lastIndex)
        }
        val body = when (argument) {
            is MathGroup -> argument.body
            is MathList -> argument
            else -> MathList(listOf(argument), argument.range)
        }
        val rowNodes = mutableListOf<MathNode>()
        val rows = mutableListOf<MathTableRow>()
        fun finishRow(separator: MathExplicitRowBreak?) {
            val rowRange = rowNodes.firstOrNull()?.range?.cover(rowNodes.last().range)
                ?: SourceRange(separator?.range?.start ?: body.range.endExclusive, separator?.range?.start ?: body.range.endExclusive)
            val cell = MathTableCell(MathList(rowNodes.toList(), rowRange), range = rowRange)
            rows += MathTableRow(
                cells = listOf(cell),
                rowSeparatorRange = separator?.separatorRange,
                additionalSpacing = separator?.additionalSpacing,
                range = rowRange,
            )
            rowNodes.clear()
        }
        body.children.forEach { child ->
            if (child is MathExplicitRowBreak) finishRow(child) else rowNodes += child
        }
        finishRow(null)
        return MathTable(
            environmentName = "substack",
            environment = MathTableEnvironment.Substack,
            rows = rows,
            columnAlignments = listOf(MathTableColumnAlignment.Center),
            beginCommandRange = token.range,
            beginNameRange = token.range,
            endCommandRange = null,
            endNameRange = null,
            range = token.range.cover(argument.range),
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
    if (
        token.text == "frac" ||
        token.text == "binom" ||
        token.text == "tfrac" ||
        token.text == "dfrac" ||
        token.text == "cfrac"
    ) {
        val continuedAlignment = if (token.text == "cfrac") parseContinuedFractionAlignment() else null
        val numerator = parseRequiredArgument(token, "numerator")
        val denominator = parseRequiredArgument(token, "denominator")
        val origin = when (token.text) {
            "binom" -> MathFractionOrigin.Binomial
            "tfrac" -> MathFractionOrigin.TextFraction
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
            styleOverride = when (origin) {
                MathFractionOrigin.TextFraction -> MathStyleLevel.Text
                MathFractionOrigin.DisplayFraction,
                MathFractionOrigin.ContinuedFraction,
                -> MathStyleLevel.Display
                else -> null
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
