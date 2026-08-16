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

internal class ParserState(
    internal val source: String,
    private val tokens: List<MathToken>,
    internal val diagnostics: MutableList<MathDiagnostic>,
) {
    internal data class ParsedBboxOptions(
        val options: MathBboxOptions,
        val totalRange: SourceRange?,
    )
    private var index = 0
    internal var structureDepth = 0
    internal val bboxDisplayContainerDepths = mutableListOf<Int>()

    fun parse(): MathParseResult {
        val parsedRoot = parseList(stopAtClosingGroup = false, opening = null)
        val root = foldTopLevelEquationTag(foldTopLevelDisplayRows(parsedRoot))
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
            val (bodyChildren, tag) = extractEquationTag(pending)
            val cleanBodyRange = bodyChildren.firstOrNull()?.range?.cover(bodyChildren.last().range)
                ?: SourceRange(bodyRange.start, bodyRange.start)
            rows += MathDisplayRow(
                body = MathList(bodyChildren, cleanBodyRange),
                rowSeparatorRange = separator?.separatorRange,
                additionalSpacing = separator?.additionalSpacing,
                range = bodyRange,
                tag = tag,
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

    private fun foldTopLevelEquationTag(root: MathList): MathList {
        if (root.children.none { it is MathEquationTag }) return root
        val (bodyChildren, tag) = extractEquationTag(root.children)
        val selected = tag ?: return root
        val bodyRange = bodyChildren.firstOrNull()?.range?.cover(bodyChildren.last().range)
            ?: SourceRange(root.range.start, root.range.start)
        return MathList(
            children = listOf(
                MathTaggedEquation(
                    body = MathList(bodyChildren, bodyRange),
                    tag = selected,
                    range = root.range,
                ),
            ),
            range = root.range,
        )
    }

    internal fun extractEquationTag(nodes: List<MathNode>): Pair<List<MathNode>, MathEquationTag?> {
        val tags = nodes.filterIsInstance<MathEquationTag>()
        tags.drop(1).forEach { duplicate ->
            diagnostics += MathDiagnostic(
                DiagnosticCode.MultipleEquationTags,
                "A display row may contain only one equation tag",
                duplicate.range,
            )
        }
        return nodes.filterNot { it is MathEquationTag } to tags.firstOrNull()
    }

    private fun parseList(
        stopAtClosingGroup: Boolean,
        opening: MathToken?,
        unclosedCode: DiagnosticCode = DiagnosticCode.UnclosedGroup,
        unclosedMessage: String = "Group opened here is not closed",
        generalizedFractionAllowed: Boolean = true,
    ): MathList {
        val children = mutableListOf<MathNode>()
        while (true) {
            skipIgnored()
            val token = peek()
            if (token.kind == MathTokenKind.ControlWord && token.text == "atop") {
                advance()
                if (!generalizedFractionAllowed) {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.AmbiguousGeneralizedFraction,
                        "Only one generalized fraction command is allowed in a math list",
                        token.range,
                    )
                    children += MathErrorNode(sourceSlice(token.range), token.range)
                    continue
                }
                val numerator = mathListFrom(children, token.range.start)
                if (children.isEmpty()) {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.MissingGeneralizedFractionNumerator,
                        "Command \\atop requires material before it in the containing math list",
                        token.range,
                    )
                }
                val denominator = parseList(
                    stopAtClosingGroup = stopAtClosingGroup,
                    opening = opening,
                    unclosedCode = unclosedCode,
                    unclosedMessage = unclosedMessage,
                    generalizedFractionAllowed = false,
                )
                if (denominator.children.isEmpty()) {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.MissingGeneralizedFractionDenominator,
                        "Command \\atop requires material after it in the containing math list",
                        token.range,
                    )
                }
                val fractionRange = numerator.range.cover(token.range).cover(denominator.range)
                children.clear()
                children += MathFraction(
                    numerator = numerator,
                    denominator = denominator,
                    kind = FractionKind.Ruleless,
                    hasParentheses = false,
                    range = fractionRange,
                    origin = MathFractionOrigin.GeneralizedAtop,
                    commandRange = token.range,
                )
                break
            }
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

    private fun mathListFrom(nodes: List<MathNode>, insertionOffset: Int): MathList {
        val range = nodes.firstOrNull()?.range?.cover(nodes.last().range)
            ?: SourceRange(insertionOffset, insertionOffset)
        return MathList(nodes.toList(), range)
    }

    internal fun parseAtomWithScripts(): MathNode? {
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
        if (
            base is MathStyleDeclaration || base is MathAlphabetDeclaration || base is MathVersionDeclaration ||
            base is MathExplicitRowBreak || base is MathEquationTag
        ) {
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

    internal fun parsePrimary(): MathNode? {
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

    internal fun parseGroup(
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

    internal fun MathFixedDelimiterRole.asDelimiterSide(): MathDelimiterSide = when (this) {
        MathFixedDelimiterRole.Opening -> MathDelimiterSide.Left
        MathFixedDelimiterRole.Closing -> MathDelimiterSide.Right
        MathFixedDelimiterRole.Ordinary,
        MathFixedDelimiterRole.Relation,
        -> MathDelimiterSide.Middle
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

    internal fun symbolNode(token: MathToken, spec: TeXMathSymbolSpec): MathSymbol = MathSymbol(
        sourceText = sourceSlice(token.range),
        identity = spec.identity,
        atomClass = spec.atomClass,
        family = spec.family,
        familyBinding = spec.familyBinding,
        alphabet = spec.alphabet,
        range = token.range,
    )

    internal fun skipIgnored() {
        while (peek().kind == MathTokenKind.Space || peek().kind == MathTokenKind.Comment) index++
    }

    internal fun peek(): MathToken = tokens.getOrElse(index) { tokens.last() }
    internal fun advance(): MathToken = peek().also { if (index < tokens.size) index++ }
    internal fun sourceSlice(range: SourceRange): String =
        if (range.endExclusive <= source.length) source.substring(range.start, range.endExclusive) else ""

    /**
     * Tokenization stays scalar based, but one implicit upright text atom must reach the host shaper
     * as one contiguous run. The provider, not the parser, owns grapheme and physical-face splits.
     */
    internal fun MutableList<MathNode>.appendParsedNode(node: MathNode) {
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

    internal companion object {
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
            "matrix", "cases", "newcommand", "def",
        )

        /** xcolor's always-available names plus the corpus-used dvips `RoyalBlue` alias. */
        val namedPaintColors = mapOf(
            "black" to MathPaintColor(0, 0, 0),
            "darkgray" to MathPaintColor(64, 64, 64),
            "gray" to MathPaintColor(128, 128, 128),
            "lightgray" to MathPaintColor(191, 191, 191),
            "white" to MathPaintColor(255, 255, 255),
            "red" to MathPaintColor(255, 0, 0),
            "green" to MathPaintColor(0, 255, 0),
            "blue" to MathPaintColor(0, 0, 255),
            "cyan" to MathPaintColor(0, 255, 255),
            "magenta" to MathPaintColor(255, 0, 255),
            "yellow" to MathPaintColor(255, 255, 0),
            "brown" to MathPaintColor(191, 128, 64),
            "lime" to MathPaintColor(191, 255, 0),
            "olive" to MathPaintColor(128, 128, 0),
            "orange" to MathPaintColor(255, 128, 0),
            "pink" to MathPaintColor(255, 191, 191),
            "purple" to MathPaintColor(191, 0, 64),
            "teal" to MathPaintColor(0, 128, 128),
            "violet" to MathPaintColor(128, 0, 128),
            "royalblue" to MathPaintColor(0, 128, 255),
        )

        val tableEnvironments = MathTableEnvironment.entries.associateBy { it.sourceName }
        val displayEnvironments = MathDisplayEnvironmentKind.entries.associateBy { it.sourceName }

        val rowSpacingPattern = Regex("([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*([A-Za-z]+)")
        val rowSpacingUnits = MathTeXDimensionUnit.entries.associateBy { it.sourceName }
        val bboxDimensionPattern = Regex(
            "(\\.\\d+|\\d+(?:\\.\\d*)?)(pt|em|ex|mu|px|in|cm|mm)",
            RegexOption.IGNORE_CASE,
        )
        val bboxDimensionUnits = MathBboxDimensionUnit.entries.associateBy { it.sourceName }
        val bboxBorderPattern = Regex(
            "border\\s*:\\s*((?:\\.\\d+|\\d+(?:\\.\\d*)?)(?:pt|em|ex|mu|px|in|cm|mm))" +
                "(?:\\s+(solid))?(?:\\s+([A-Za-z0-9]+|#[0-9A-Fa-f]{3}|#[0-9A-Fa-f]{6}))?\\s*",
            RegexOption.IGNORE_CASE,
        )
        val shortHexColorPattern = Regex("#[0-9A-Fa-f]{3}")
        val longHexColorPattern = Regex("#[0-9A-Fa-f]{6}")

        val explicitMathSpaces = mapOf(
            "quad" to 18f,
            "qquad" to 36f,
        )
        val explicitControlSpaces = mapOf(
            "!" to -3f,
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

    internal data class ParsedTextArgument(
        val segments: List<MathTextSegment>,
        val contentRange: SourceRange,
        val totalRange: SourceRange,
    )

    internal data class ParsedRadicalDegree(
        val node: MathNode?,
        val range: SourceRange,
    )

    internal data class ParsedOptionalMathList(
        val node: MathNode,
        val range: SourceRange,
    )

    internal data class ParsedFractionAlignment(
        val alignment: MathFractionAlignment,
        val range: SourceRange,
    )

    internal data class FixedDelimiterCommand(
        val size: MathFixedDelimiterSize,
        val role: MathFixedDelimiterRole,
    )

    internal data class ParsedEnvironmentName(
        val name: String,
        val contentRange: SourceRange,
        val totalRange: SourceRange,
    )
}
