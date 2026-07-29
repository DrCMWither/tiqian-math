package org.tiqian.math.core

/** A Unicode Mathematical Alphanumeric scalar decoded into its semantic base and alphabet. */
data class DecodedMathAlphabetScalar(
    val baseScalar: Int,
    val alphabet: MathAlphabet,
)

/**
 * Decodes the mathematical alphabets currently represented by [MathAlphabet]. The AST stores the
 * base identity and alphabet separately; it never uses a styled Unicode scalar as semantic truth.
 */
fun decodeMathAlphabetScalar(scalar: Int): DecodedMathAlphabetScalar? {
    decodeLatin(scalar, 0x1D400, 0x1D419, 'A', MathAlphabet.Bold)?.let { return it }
    decodeLatin(scalar, 0x1D41A, 0x1D433, 'a', MathAlphabet.Bold)?.let { return it }
    decodeLatin(scalar, 0x1D434, 0x1D44D, 'A', MathAlphabet.Italic)?.let { return it }
    decodeLatin(scalar, 0x1D44E, 0x1D467, 'a', MathAlphabet.Italic, excluded = 0x1D455)?.let { return it }
    if (scalar == 0x210E) return DecodedMathAlphabetScalar('h'.code, MathAlphabet.Italic)
    decodeLatin(scalar, 0x1D468, 0x1D481, 'A', MathAlphabet.BoldItalic)?.let { return it }
    decodeLatin(scalar, 0x1D482, 0x1D49B, 'a', MathAlphabet.BoldItalic)?.let { return it }
    decodeLatin(scalar, 0x1D5A0, 0x1D5B9, 'A', MathAlphabet.SansSerif)?.let { return it }
    decodeLatin(scalar, 0x1D5BA, 0x1D5D3, 'a', MathAlphabet.SansSerif)?.let { return it }
    decodeDigit(scalar, 0x1D7CE, MathAlphabet.Bold)?.let { return it }
    decodeDigit(scalar, 0x1D7E2, MathAlphabet.SansSerif)?.let { return it }
    decodeGreek(scalar, 0x1D6A8, MathAlphabet.Bold)?.let { return it }
    decodeGreek(scalar, 0x1D6E2, MathAlphabet.Italic)?.let { return it }
    decodeGreek(scalar, 0x1D71C, MathAlphabet.BoldItalic)?.let { return it }
    return null
}

/** Resolves a semantic base scalar to the matching Unicode glyph key for the selected alphabet. */
fun encodeMathAlphabetScalar(baseScalar: Int, alphabet: MathAlphabet): Int? = when (alphabet) {
    MathAlphabet.MathNormal, MathAlphabet.Roman -> baseScalar
    MathAlphabet.Italic ->
        encodeLatin(baseScalar, 0x1D434, 0x1D44E, italicH = true)
            ?: encodeGreek(baseScalar, 0x1D6E2)
    MathAlphabet.Bold ->
        encodeLatin(baseScalar, 0x1D400, 0x1D41A)
            ?: encodeDigit(baseScalar, 0x1D7CE)
            ?: encodeGreek(baseScalar, 0x1D6A8)
    MathAlphabet.BoldItalic ->
        encodeLatin(baseScalar, 0x1D468, 0x1D482)
            ?: encodeGreek(baseScalar, 0x1D71C)
    MathAlphabet.SansSerif ->
        encodeLatin(baseScalar, 0x1D5A0, 0x1D5BA)
            ?: encodeDigit(baseScalar, 0x1D7E2)
}

private fun decodeLatin(
    scalar: Int,
    start: Int,
    end: Int,
    base: Char,
    alphabet: MathAlphabet,
    excluded: Int? = null,
): DecodedMathAlphabetScalar? = if (scalar in start..end && scalar != excluded) {
    DecodedMathAlphabetScalar(base.code + scalar - start, alphabet)
} else {
    null
}

private fun decodeDigit(
    scalar: Int,
    start: Int,
    alphabet: MathAlphabet,
): DecodedMathAlphabetScalar? = if (scalar in start..start + 9) {
    DecodedMathAlphabetScalar('0'.code + scalar - start, alphabet)
} else {
    null
}

private fun decodeGreek(
    scalar: Int,
    start: Int,
    alphabet: MathAlphabet,
): DecodedMathAlphabetScalar? {
    val index = scalar - start
    val base = greekBaseForIndex(index) ?: return null
    return DecodedMathAlphabetScalar(base, alphabet)
}

private fun encodeLatin(
    scalar: Int,
    uppercaseStart: Int,
    lowercaseStart: Int,
    italicH: Boolean = false,
): Int? = when {
    scalar in 'A'.code..'Z'.code -> uppercaseStart + scalar - 'A'.code
    scalar in 'a'.code..'z'.code -> {
        if (italicH && scalar == 'h'.code) 0x210E else lowercaseStart + scalar - 'a'.code
    }
    scalar == 0x0131 && italicH -> 0x1D6A4
    scalar == 0x0237 && italicH -> 0x1D6A5
    else -> null
}

private fun encodeDigit(scalar: Int, start: Int): Int? =
    if (scalar in '0'.code..'9'.code) start + scalar - '0'.code else null

private fun encodeGreek(scalar: Int, start: Int): Int? =
    greekIndexForBase(scalar)?.let { start + it }

private fun greekBaseForIndex(index: Int): Int? = when (index) {
    in 0..16 -> 0x0391 + index
    17 -> 0x03F4
    in 18..24 -> 0x03A3 + index - 18
    25 -> 0x2207
    in 26..50 -> 0x03B1 + index - 26
    51 -> 0x2202
    in 52..57 -> greekVariantBases[index - 52]
    else -> null
}

private fun greekIndexForBase(scalar: Int): Int? = when (scalar) {
    in 0x0391..0x03A1 -> scalar - 0x0391
    0x03F4 -> 17
    in 0x03A3..0x03A9 -> 18 + scalar - 0x03A3
    0x2207 -> 25
    in 0x03B1..0x03C9 -> 26 + scalar - 0x03B1
    0x2202 -> 51
    else -> greekVariantBases.indexOf(scalar).takeIf { it >= 0 }?.plus(52)
}

private val greekVariantBases = intArrayOf(
    0x03F5,
    0x03D1,
    0x03F0,
    0x03D5,
    0x03F1,
    0x03D6,
)
