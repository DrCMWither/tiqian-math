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
        0x2135 to named(MathNamedSymbol.Aleph, MathAtomClass.Ordinary, MathFamily.Symbols),
        0x2200 to named(MathNamedSymbol.ForAll, MathAtomClass.Ordinary, MathFamily.Symbols),
        0x2203 to named(MathNamedSymbol.Exists, MathAtomClass.Ordinary, MathFamily.Symbols),
        0x2205 to named(MathNamedSymbol.EmptySet, MathAtomClass.Ordinary, MathFamily.Symbols),
        0x2207 to named(MathNamedSymbol.Nabla, MathAtomClass.Ordinary, MathFamily.Symbols),
        0x00AC to named(MathNamedSymbol.LogicalNot, MathAtomClass.Ordinary, MathFamily.Symbols),
        0x22A4 to named(MathNamedSymbol.Top, MathAtomClass.Ordinary, MathFamily.Symbols),
        0x22A5 to named(MathNamedSymbol.Bottom, MathAtomClass.Ordinary, MathFamily.Symbols),
        0x210F to named(MathNamedSymbol.HBar, MathAtomClass.Ordinary, MathFamily.Letters),
        0x2113 to named(MathNamedSymbol.ScriptSmallL, MathAtomClass.Ordinary, MathFamily.Letters),
        0x2118 to named(MathNamedSymbol.WeierstrassP, MathAtomClass.Ordinary, MathFamily.Letters),
        0x211C to named(MathNamedSymbol.RealPart, MathAtomClass.Ordinary, MathFamily.Symbols),
        0x2111 to named(MathNamedSymbol.ImaginaryPart, MathAtomClass.Ordinary, MathFamily.Symbols),
        0x2209 to named(MathNamedSymbol.NotElementOf, MathAtomClass.Relation, MathFamily.Symbols),
        0x220B to named(MathNamedSymbol.ContainsAsMember, MathAtomClass.Relation, MathFamily.Symbols),
        0x2282 to named(MathNamedSymbol.Subset, MathAtomClass.Relation, MathFamily.Symbols),
        0x2283 to named(MathNamedSymbol.Superset, MathAtomClass.Relation, MathFamily.Symbols),
        0x2286 to named(MathNamedSymbol.SubsetOrEqual, MathAtomClass.Relation, MathFamily.Symbols),
        0x2287 to named(MathNamedSymbol.SupersetOrEqual, MathAtomClass.Relation, MathFamily.Symbols),
        0x2261 to named(MathNamedSymbol.Equivalent, MathAtomClass.Relation, MathFamily.Symbols),
        0x223C to named(MathNamedSymbol.Similar, MathAtomClass.Relation, MathFamily.Symbols),
        0x2243 to named(MathNamedSymbol.SimilarOrEqual, MathAtomClass.Relation, MathFamily.Symbols),
        0x2245 to named(MathNamedSymbol.Congruent, MathAtomClass.Relation, MathFamily.Symbols),
        0x221D to named(MathNamedSymbol.ProportionalTo, MathAtomClass.Relation, MathFamily.Symbols),
        0x2225 to named(MathNamedSymbol.Parallel, MathAtomClass.Relation, MathFamily.Symbols),
        0x2223 to named(MathNamedSymbol.Mid, MathAtomClass.Relation, MathFamily.Symbols),
        0x2190 to named(MathNamedSymbol.LeftArrow, MathAtomClass.Relation, MathFamily.Symbols),
        0x2194 to named(MathNamedSymbol.LeftRightArrow, MathAtomClass.Relation, MathFamily.Symbols),
        0x21D0 to named(MathNamedSymbol.DoubleLeftArrow, MathAtomClass.Relation, MathFamily.Symbols),
        0x21D2 to named(MathNamedSymbol.DoubleRightArrow, MathAtomClass.Relation, MathFamily.Symbols),
        0x21D4 to named(MathNamedSymbol.DoubleLeftRightArrow, MathAtomClass.Relation, MathFamily.Symbols),
        0x21A6 to named(MathNamedSymbol.MapsTo, MathAtomClass.Relation, MathFamily.Symbols),
    )

    private val literalGreek = mapOf(
        0x03B1 to MathNamedSymbol.Alpha,
        0x03B2 to MathNamedSymbol.Beta,
        0x03B3 to MathNamedSymbol.Gamma,
        0x03B4 to MathNamedSymbol.Delta,
        0x03F5 to MathNamedSymbol.Epsilon,
        0x03B5 to MathNamedSymbol.Varepsilon,
        0x03B6 to MathNamedSymbol.Zeta,
        0x03B7 to MathNamedSymbol.Eta,
        0x03B8 to MathNamedSymbol.Theta,
        0x03D1 to MathNamedSymbol.Vartheta,
        0x03B9 to MathNamedSymbol.Iota,
        0x03BA to MathNamedSymbol.Kappa,
        0x03BB to MathNamedSymbol.Lambda,
        0x03BC to MathNamedSymbol.Mu,
        0x03BD to MathNamedSymbol.Nu,
        0x03BE to MathNamedSymbol.Xi,
        0x03BF to MathNamedSymbol.Omicron,
        0x03C0 to MathNamedSymbol.Pi,
        0x03D6 to MathNamedSymbol.Varpi,
        0x03C1 to MathNamedSymbol.Rho,
        0x03F1 to MathNamedSymbol.Varrho,
        0x03C2 to MathNamedSymbol.Varsigma,
        0x03C3 to MathNamedSymbol.Sigma,
        0x03C4 to MathNamedSymbol.Tau,
        0x03C5 to MathNamedSymbol.Upsilon,
        0x03D5 to MathNamedSymbol.Phi,
        0x03C6 to MathNamedSymbol.Varphi,
        0x03C7 to MathNamedSymbol.Chi,
        0x03C8 to MathNamedSymbol.Psi,
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
            "Xi" to MathNamedSymbol.CapitalXi,
            "Pi" to MathNamedSymbol.CapitalPi,
            "Sigma" to MathNamedSymbol.CapitalSigma,
            "Upsilon" to MathNamedSymbol.CapitalUpsilon,
            "Phi" to MathNamedSymbol.CapitalPhi,
            "Psi" to MathNamedSymbol.CapitalPsi,
            "Omega" to MathNamedSymbol.CapitalOmega,
        ).forEach { (name, symbol) ->
            put(name, named(symbol, MathAtomClass.Ordinary, MathFamily.Operators, MathFamilyBinding.Variable))
        }
        put("infty", named(MathNamedSymbol.Infinity, MathAtomClass.Ordinary, MathFamily.Symbols))
        put("partial", named(MathNamedSymbol.PartialDifferential, MathAtomClass.Ordinary, MathFamily.Letters))
        listOf(
            "aleph" to MathNamedSymbol.Aleph,
            "forall" to MathNamedSymbol.ForAll,
            "exists" to MathNamedSymbol.Exists,
            "emptyset" to MathNamedSymbol.EmptySet,
            "nabla" to MathNamedSymbol.Nabla,
            "neg" to MathNamedSymbol.LogicalNot,
            "top" to MathNamedSymbol.Top,
            "bot" to MathNamedSymbol.Bottom,
            "Re" to MathNamedSymbol.RealPart,
            "Im" to MathNamedSymbol.ImaginaryPart,
        ).forEach { (name, symbol) ->
            put(name, named(symbol, MathAtomClass.Ordinary, MathFamily.Symbols))
        }
        put("lnot", getValue("neg"))
        listOf(
            "hbar" to MathNamedSymbol.HBar,
            "ell" to MathNamedSymbol.ScriptSmallL,
            "wp" to MathNamedSymbol.WeierstrassP,
        ).forEach { (name, symbol) ->
            put(name, named(symbol, MathAtomClass.Ordinary, MathFamily.Letters))
        }
        put("cdot", named(MathNamedSymbol.DotOperator, MathAtomClass.Binary, MathFamily.Symbols))
        put("times", named(MathNamedSymbol.MultiplicationSign, MathAtomClass.Binary, MathFamily.Symbols))
        put("pm", named(MathNamedSymbol.PlusMinus, MathAtomClass.Binary, MathFamily.Symbols))
        put("mp", named(MathNamedSymbol.MinusPlus, MathAtomClass.Binary, MathFamily.Symbols))
        put("div", named(MathNamedSymbol.DivisionSign, MathAtomClass.Binary, MathFamily.Symbols))
        listOf(
            "ast" to MathNamedSymbol.AsteriskOperator,
            "circ" to MathNamedSymbol.CircleOperator,
            "cap" to MathNamedSymbol.Intersection,
            "cup" to MathNamedSymbol.Union,
            "setminus" to MathNamedSymbol.SetMinus,
            "wedge" to MathNamedSymbol.LogicalAnd,
            "vee" to MathNamedSymbol.LogicalOr,
            "bullet" to MathNamedSymbol.BulletOperator,
            "oplus" to MathNamedSymbol.CircledPlus,
            "otimes" to MathNamedSymbol.CircledTimes,
            "odot" to MathNamedSymbol.CircledDot,
            "diamond" to MathNamedSymbol.DiamondOperator,
            "star" to MathNamedSymbol.StarOperator,
        ).forEach { (name, symbol) ->
            put(name, named(symbol, MathAtomClass.Binary, MathFamily.Symbols))
        }
        put("land", getValue("wedge"))
        put("lor", getValue("vee"))
        put("le", named(MathNamedSymbol.LessThanOrEqual, MathAtomClass.Relation, MathFamily.Symbols))
        put("leq", named(MathNamedSymbol.LessThanOrEqual, MathAtomClass.Relation, MathFamily.Symbols))
        put("ge", named(MathNamedSymbol.GreaterThanOrEqual, MathAtomClass.Relation, MathFamily.Symbols))
        put("geq", named(MathNamedSymbol.GreaterThanOrEqual, MathAtomClass.Relation, MathFamily.Symbols))
        put("neq", named(MathNamedSymbol.NotEqual, MathAtomClass.Relation, MathFamily.Symbols))
        put("ne", named(MathNamedSymbol.NotEqual, MathAtomClass.Relation, MathFamily.Symbols))
        put("in", named(MathNamedSymbol.ElementOf, MathAtomClass.Relation, MathFamily.Symbols))
        put("notin", named(MathNamedSymbol.NotElementOf, MathAtomClass.Relation, MathFamily.Symbols))
        put("ni", named(MathNamedSymbol.ContainsAsMember, MathAtomClass.Relation, MathFamily.Symbols))
        put("owns", getValue("ni"))
        put("subset", named(MathNamedSymbol.Subset, MathAtomClass.Relation, MathFamily.Symbols))
        put("supset", named(MathNamedSymbol.Superset, MathAtomClass.Relation, MathFamily.Symbols))
        put("subseteq", named(MathNamedSymbol.SubsetOrEqual, MathAtomClass.Relation, MathFamily.Symbols))
        put("supseteq", named(MathNamedSymbol.SupersetOrEqual, MathAtomClass.Relation, MathFamily.Symbols))
        listOf(
            "equiv" to MathNamedSymbol.Equivalent,
            "prec" to MathNamedSymbol.Precedes,
            "succ" to MathNamedSymbol.Succeeds,
            "sim" to MathNamedSymbol.Similar,
            "simeq" to MathNamedSymbol.SimilarOrEqual,
            "cong" to MathNamedSymbol.Congruent,
            "propto" to MathNamedSymbol.ProportionalTo,
            "perp" to MathNamedSymbol.Perpendicular,
            "parallel" to MathNamedSymbol.Parallel,
            "mid" to MathNamedSymbol.Mid,
            "ll" to MathNamedSymbol.MuchLessThan,
            "gg" to MathNamedSymbol.MuchGreaterThan,
            "asymp" to MathNamedSymbol.AsymptoticallyEqual,
            "vdash" to MathNamedSymbol.RightTack,
            "dashv" to MathNamedSymbol.LeftTack,
            "models" to MathNamedSymbol.Models,
        ).forEach { (name, symbol) ->
            put(name, named(symbol, MathAtomClass.Relation, MathFamily.Symbols))
        }
        put("to", named(MathNamedSymbol.RightArrow, MathAtomClass.Relation, MathFamily.Symbols))
        put("rightarrow", getValue("to"))
        put("leftarrow", named(MathNamedSymbol.LeftArrow, MathAtomClass.Relation, MathFamily.Symbols))
        put("gets", getValue("leftarrow"))
        put("leftrightarrow", named(MathNamedSymbol.LeftRightArrow, MathAtomClass.Relation, MathFamily.Symbols))
        put("Leftarrow", named(MathNamedSymbol.DoubleLeftArrow, MathAtomClass.Relation, MathFamily.Symbols))
        put("Rightarrow", named(MathNamedSymbol.DoubleRightArrow, MathAtomClass.Relation, MathFamily.Symbols))
        put("Leftrightarrow", named(MathNamedSymbol.DoubleLeftRightArrow, MathAtomClass.Relation, MathFamily.Symbols))
        put("mapsto", named(MathNamedSymbol.MapsTo, MathAtomClass.Relation, MathFamily.Symbols))
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
        0x039E to MathNamedSymbol.CapitalXi,
        0x03A0 to MathNamedSymbol.CapitalPi,
        0x03A3 to MathNamedSymbol.CapitalSigma,
        0x03A5 to MathNamedSymbol.CapitalUpsilon,
        0x03A6 to MathNamedSymbol.CapitalPhi,
        0x03A8 to MathNamedSymbol.CapitalPsi,
        0x03A9 to MathNamedSymbol.CapitalOmega,
    )

}
