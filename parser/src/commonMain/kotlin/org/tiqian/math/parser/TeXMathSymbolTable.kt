package org.tiqian.math.parser

import org.tiqian.math.core.*

internal data class TeXMathSymbolSpec(
    val identity: MathSymbolIdentity,
    val atomClass: MathAtomClass,
    val family: MathFamily,
    val familyBinding: MathFamilyBinding,
    val alphabet: MathAlphabet = MathAlphabet.MathNormal,
)

/**
 * The supported mathcode/symbol-font slice. Every accepted variable or command is listed here;
 * callers never infer TeX family semantics from Unicode letter categories or blocks.
 */
internal object TeXMathSymbolTable {
    fun literal(text: String): TeXMathSymbolSpec {
        val scalar = text.singleUnicodeScalarOrNull()
            ?: return fixedLiteral(0xFFFD)
        decodeMathAlphabetScalar(scalar)?.let { return explicitAlphabetSymbol(it) }
        if (scalar in 'A'.code..'Z'.code || scalar in 'a'.code..'z'.code) {
            return variableLatin(scalar.toChar())
        }
        if (scalar in '0'.code..'9'.code) {
            return TeXMathSymbolSpec(
                MathSymbolIdentity.Digit(scalar.toChar()),
                MathAtomClass.Ordinary,
                MathFamily.Operators,
                MathFamilyBinding.Variable,
            )
        }
        literalSymbols[scalar]?.let { return it }
        literalGreek[scalar]?.let { return named(it, MathAtomClass.Ordinary, MathFamily.Letters) }
        return fixedLiteral(scalar)
    }

    fun command(name: String): TeXMathSymbolSpec? = commands[name]

    fun largeOperator(name: String): MathLargeOperatorIdentity? = largeOperators[name]

    fun controlSymbol(text: String): TeXMathSymbolSpec? = controlSymbols[text]

    private fun variableLatin(
        letter: Char,
        alphabet: MathAlphabet = MathAlphabet.MathNormal,
    ) = TeXMathSymbolSpec(
        MathSymbolIdentity.LatinLetter(letter),
        MathAtomClass.Ordinary,
        MathFamily.Letters,
        MathFamilyBinding.Variable,
        alphabet,
    )

    private fun fixedLiteral(scalar: Int) = TeXMathSymbolSpec(
        MathSymbolIdentity.Literal(scalar),
        MathAtomClass.Ordinary,
        MathFamily.Operators,
        MathFamilyBinding.Fixed,
    )

    private fun named(
        symbol: MathNamedSymbol,
        atomClass: MathAtomClass,
        family: MathFamily,
        binding: MathFamilyBinding = MathFamilyBinding.Fixed,
    ) = TeXMathSymbolSpec(MathSymbolIdentity.Named(symbol), atomClass, family, binding)

    private val literalSymbols = mapOf(
        '+'.code to named(MathNamedSymbol.Plus, MathAtomClass.Binary, MathFamily.Operators),
        '-'.code to named(MathNamedSymbol.Minus, MathAtomClass.Binary, MathFamily.Symbols),
        0x2212 to named(MathNamedSymbol.Minus, MathAtomClass.Binary, MathFamily.Symbols),
        '*'.code to named(MathNamedSymbol.AsteriskOperator, MathAtomClass.Binary, MathFamily.Symbols),
        '/'.code to named(MathNamedSymbol.Slash, MathAtomClass.Ordinary, MathFamily.Letters),
        ':'.code to named(MathNamedSymbol.Colon, MathAtomClass.Relation, MathFamily.Operators),
        '!'.code to named(MathNamedSymbol.ExclamationMark, MathAtomClass.Closing, MathFamily.Operators),
        '?'.code to named(MathNamedSymbol.QuestionMark, MathAtomClass.Closing, MathFamily.Operators),
        '('.code to named(MathNamedSymbol.LeftParenthesis, MathAtomClass.Opening, MathFamily.Operators),
        ')'.code to named(MathNamedSymbol.RightParenthesis, MathAtomClass.Closing, MathFamily.Operators),
        '['.code to named(MathNamedSymbol.LeftBracket, MathAtomClass.Opening, MathFamily.Operators),
        ']'.code to named(MathNamedSymbol.RightBracket, MathAtomClass.Closing, MathFamily.Operators),
        ','.code to named(MathNamedSymbol.Comma, MathAtomClass.Punctuation, MathFamily.Letters),
        ';'.code to named(MathNamedSymbol.Semicolon, MathAtomClass.Punctuation, MathFamily.Operators),
        '='.code to named(MathNamedSymbol.Equals, MathAtomClass.Relation, MathFamily.Operators),
        '<'.code to named(MathNamedSymbol.LessThan, MathAtomClass.Relation, MathFamily.Letters),
        '>'.code to named(MathNamedSymbol.GreaterThan, MathAtomClass.Relation, MathFamily.Letters),
        '|'.code to named(MathNamedSymbol.VerticalBar, MathAtomClass.Ordinary, MathFamily.Symbols),
        0x00D7 to named(MathNamedSymbol.MultiplicationSign, MathAtomClass.Binary, MathFamily.Symbols),
        0x22C5 to named(MathNamedSymbol.DotOperator, MathAtomClass.Binary, MathFamily.Symbols),
        0x00B1 to named(MathNamedSymbol.PlusMinus, MathAtomClass.Binary, MathFamily.Symbols),
        0x00F7 to named(MathNamedSymbol.DivisionSign, MathAtomClass.Binary, MathFamily.Symbols),
        0x2264 to named(MathNamedSymbol.LessThanOrEqual, MathAtomClass.Relation, MathFamily.Symbols),
        0x2265 to named(MathNamedSymbol.GreaterThanOrEqual, MathAtomClass.Relation, MathFamily.Symbols),
        0x2260 to named(MathNamedSymbol.NotEqual, MathAtomClass.Relation, MathFamily.Symbols),
        0x2248 to named(MathNamedSymbol.ApproximatelyEqual, MathAtomClass.Relation, MathFamily.Symbols),
        0x2208 to named(MathNamedSymbol.ElementOf, MathAtomClass.Relation, MathFamily.Symbols),
        0x2192 to named(MathNamedSymbol.RightArrow, MathAtomClass.Relation, MathFamily.Symbols),
    )

    private val literalGreek = mapOf(
        0x03B1 to MathNamedSymbol.Alpha,
        0x03B2 to MathNamedSymbol.Beta,
        0x03B3 to MathNamedSymbol.Gamma,
        0x03B4 to MathNamedSymbol.Delta,
        0x03F5 to MathNamedSymbol.Epsilon,
        0x03B5 to MathNamedSymbol.Varepsilon,
        0x03B8 to MathNamedSymbol.Theta,
        0x03BB to MathNamedSymbol.Lambda,
        0x03BC to MathNamedSymbol.Mu,
        0x03C0 to MathNamedSymbol.Pi,
        0x03C3 to MathNamedSymbol.Sigma,
        0x03D5 to MathNamedSymbol.Phi,
        0x03C6 to MathNamedSymbol.Varphi,
        0x03C9 to MathNamedSymbol.Omega,
    )

    private val commands = buildMap {
        literalGreek.forEach { (_, symbol) -> put(symbol.debugName, named(symbol, MathAtomClass.Ordinary, MathFamily.Letters)) }
        put("epsilon", named(MathNamedSymbol.Epsilon, MathAtomClass.Ordinary, MathFamily.Letters))
        put("varepsilon", named(MathNamedSymbol.Varepsilon, MathAtomClass.Ordinary, MathFamily.Letters))
        put("phi", named(MathNamedSymbol.Phi, MathAtomClass.Ordinary, MathFamily.Letters))
        put("varphi", named(MathNamedSymbol.Varphi, MathAtomClass.Ordinary, MathFamily.Letters))
        listOf(
            "Gamma" to MathNamedSymbol.CapitalGamma,
            "Delta" to MathNamedSymbol.CapitalDelta,
            "Theta" to MathNamedSymbol.CapitalTheta,
            "Lambda" to MathNamedSymbol.CapitalLambda,
            "Pi" to MathNamedSymbol.CapitalPi,
            "Sigma" to MathNamedSymbol.CapitalSigma,
            "Phi" to MathNamedSymbol.CapitalPhi,
            "Omega" to MathNamedSymbol.CapitalOmega,
        ).forEach { (name, symbol) ->
            put(name, named(symbol, MathAtomClass.Ordinary, MathFamily.Operators, MathFamilyBinding.Variable))
        }
        put("infty", named(MathNamedSymbol.Infinity, MathAtomClass.Ordinary, MathFamily.Symbols))
        put("partial", named(MathNamedSymbol.PartialDifferential, MathAtomClass.Ordinary, MathFamily.Letters))
        put("cdot", named(MathNamedSymbol.DotOperator, MathAtomClass.Binary, MathFamily.Symbols))
        put("times", named(MathNamedSymbol.MultiplicationSign, MathAtomClass.Binary, MathFamily.Symbols))
        put("pm", named(MathNamedSymbol.PlusMinus, MathAtomClass.Binary, MathFamily.Symbols))
        put("div", named(MathNamedSymbol.DivisionSign, MathAtomClass.Binary, MathFamily.Symbols))
        put("le", named(MathNamedSymbol.LessThanOrEqual, MathAtomClass.Relation, MathFamily.Symbols))
        put("leq", named(MathNamedSymbol.LessThanOrEqual, MathAtomClass.Relation, MathFamily.Symbols))
        put("ge", named(MathNamedSymbol.GreaterThanOrEqual, MathAtomClass.Relation, MathFamily.Symbols))
        put("geq", named(MathNamedSymbol.GreaterThanOrEqual, MathAtomClass.Relation, MathFamily.Symbols))
        put("neq", named(MathNamedSymbol.NotEqual, MathAtomClass.Relation, MathFamily.Symbols))
        put("ne", named(MathNamedSymbol.NotEqual, MathAtomClass.Relation, MathFamily.Symbols))
        put("in", named(MathNamedSymbol.ElementOf, MathAtomClass.Relation, MathFamily.Symbols))
        put("to", named(MathNamedSymbol.RightArrow, MathAtomClass.Relation, MathFamily.Symbols))
        put("approx", named(MathNamedSymbol.ApproximatelyEqual, MathAtomClass.Relation, MathFamily.Symbols))
    }

    private val largeOperators = mapOf(
        "sum" to MathLargeOperatorIdentity.Sum,
        "prod" to MathLargeOperatorIdentity.Product,
        "int" to MathLargeOperatorIdentity.Integral,
        "oint" to MathLargeOperatorIdentity.ContourIntegral,
    )

    private val controlSymbols = mapOf(
        "{" to named(MathNamedSymbol.LeftBrace, MathAtomClass.Opening, MathFamily.Symbols),
        "}" to named(MathNamedSymbol.RightBrace, MathAtomClass.Closing, MathFamily.Symbols),
        "%" to fixedLiteral('%'.code),
        "#" to fixedLiteral('#'.code),
        "_" to fixedLiteral('_'.code),
        "^" to fixedLiteral('^'.code),
        "\\" to fixedLiteral('\\'.code),
    )

    private fun explicitAlphabetSymbol(decoded: DecodedMathAlphabetScalar): TeXMathSymbolSpec {
        val base = decoded.baseScalar
        if (base in 'A'.code..'Z'.code || base in 'a'.code..'z'.code) {
            return variableLatin(base.toChar(), decoded.alphabet)
        }
        if (base in '0'.code..'9'.code) {
            return TeXMathSymbolSpec(
                MathSymbolIdentity.Digit(base.toChar()),
                MathAtomClass.Ordinary,
                MathFamily.Operators,
                MathFamilyBinding.Variable,
                decoded.alphabet,
            )
        }
        val identity = literalGreek[base]?.let { MathSymbolIdentity.Named(it) }
            ?: capitalGreekByScalar[base]?.let { MathSymbolIdentity.Named(it) }
            ?: if (base == MathNamedSymbol.PartialDifferential.baseScalar) {
                MathSymbolIdentity.Named(MathNamedSymbol.PartialDifferential)
            } else {
                MathSymbolIdentity.Literal(base)
            }
        val isCapitalGreek = base in 0x0391..0x03A9 || base == 0x03F4
        return TeXMathSymbolSpec(
            identity,
            MathAtomClass.Ordinary,
            if (isCapitalGreek) MathFamily.Operators else MathFamily.Letters,
            if (isCapitalGreek) MathFamilyBinding.Variable else MathFamilyBinding.Fixed,
            decoded.alphabet,
        )
    }

    private val capitalGreekByScalar = mapOf(
        0x0393 to MathNamedSymbol.CapitalGamma,
        0x0394 to MathNamedSymbol.CapitalDelta,
        0x0398 to MathNamedSymbol.CapitalTheta,
        0x039B to MathNamedSymbol.CapitalLambda,
        0x03A0 to MathNamedSymbol.CapitalPi,
        0x03A3 to MathNamedSymbol.CapitalSigma,
        0x03A6 to MathNamedSymbol.CapitalPhi,
        0x03A9 to MathNamedSymbol.CapitalOmega,
    )

}
