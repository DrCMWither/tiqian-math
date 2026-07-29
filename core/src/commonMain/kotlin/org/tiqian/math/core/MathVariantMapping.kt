package org.tiqian.math.core

/** The auditable Unicode scalar selected for a semantic math symbol. */
data class MathVariantSelection(
    val semanticText: String,
    val glyphText: String,
    val variant: MathVariant,
    val remapped: Boolean,
)

/**
 * Resolves the currently supported math alphabets through Unicode Mathematical Alphanumeric
 * Symbols. Source text and UTF-16 ranges remain on [MathSymbol]; only the glyph request changes.
 */
fun MathSymbol.selectMathVariant(override: MathVariant? = null): MathVariantSelection {
    val effective = override ?: variant
    val glyphText = when (effective) {
        MathVariant.Upright, MathVariant.ExplicitUnicode -> displayText
        MathVariant.DefaultVariableItalic -> displayText.singleUnicodeScalarOrNull()
            ?.let(::mathematicalItalicScalar)
            ?.let(::unicodeScalarString)
            ?: displayText
    }
    return MathVariantSelection(displayText, glyphText, effective, glyphText != displayText)
}

private fun mathematicalItalicScalar(scalar: Int): Int? = when {
    scalar in 'A'.code..'Z'.code -> 0x1D434 + (scalar - 'A'.code)
    scalar in 'a'.code..'z'.code -> if (scalar == 'h'.code) 0x210E else 0x1D44E + (scalar - 'a'.code)
    scalar == 0x0131 -> 0x1D6A4 // dotless i
    scalar == 0x0237 -> 0x1D6A5 // dotless j
    else -> italicGreek[scalar]
}

private val italicGreek = mapOf(
    0x0391 to 0x1D6E2, 0x0392 to 0x1D6E3, 0x0393 to 0x1D6E4, 0x0394 to 0x1D6E5,
    0x0395 to 0x1D6E6, 0x0396 to 0x1D6E7, 0x0397 to 0x1D6E8, 0x0398 to 0x1D6E9,
    0x0399 to 0x1D6EA, 0x039A to 0x1D6EB, 0x039B to 0x1D6EC, 0x039C to 0x1D6ED,
    0x039D to 0x1D6EE, 0x039E to 0x1D6EF, 0x039F to 0x1D6F0, 0x03A0 to 0x1D6F1,
    0x03A1 to 0x1D6F2, 0x03F4 to 0x1D6F3, 0x03A3 to 0x1D6F4, 0x03A4 to 0x1D6F5,
    0x03A5 to 0x1D6F6, 0x03A6 to 0x1D6F7, 0x03A7 to 0x1D6F8, 0x03A8 to 0x1D6F9,
    0x03A9 to 0x1D6FA, 0x2207 to 0x1D6FB,
    0x03B1 to 0x1D6FC, 0x03B2 to 0x1D6FD, 0x03B3 to 0x1D6FE, 0x03B4 to 0x1D6FF,
    0x03B5 to 0x1D700, 0x03B6 to 0x1D701, 0x03B7 to 0x1D702, 0x03B8 to 0x1D703,
    0x03B9 to 0x1D704, 0x03BA to 0x1D705, 0x03BB to 0x1D706, 0x03BC to 0x1D707,
    0x03BD to 0x1D708, 0x03BE to 0x1D709, 0x03BF to 0x1D70A, 0x03C0 to 0x1D70B,
    0x03C1 to 0x1D70C, 0x03C2 to 0x1D70D, 0x03C3 to 0x1D70E, 0x03C4 to 0x1D70F,
    0x03C5 to 0x1D710, 0x03C6 to 0x1D711, 0x03C7 to 0x1D712, 0x03C8 to 0x1D713,
    0x03C9 to 0x1D714,
    0x2202 to 0x1D715,
    0x03F5 to 0x1D716, 0x03D1 to 0x1D717, 0x03F0 to 0x1D718,
    0x03D5 to 0x1D719, 0x03F1 to 0x1D71A, 0x03D6 to 0x1D71B,
)

fun String.singleUnicodeScalarOrNull(): Int? = when {
    length == 1 && !this[0].isSurrogate() -> this[0].code
    length == 2 && this[0].isHighSurrogate() && this[1].isLowSurrogate() ->
        0x10000 + ((this[0].code - 0xD800) shl 10) + (this[1].code - 0xDC00)
    else -> null
}

fun unicodeScalarString(scalar: Int): String {
    require(scalar in 0..0x10FFFF && scalar !in 0xD800..0xDFFF)
    if (scalar <= 0xFFFF) return scalar.toChar().toString()
    val adjusted = scalar - 0x10000
    return charArrayOf(
        (0xD800 + (adjusted shr 10)).toChar(),
        (0xDC00 + (adjusted and 0x3FF)).toChar(),
    ).concatToString()
}

fun isExplicitMathematicalAlphanumeric(text: String): Boolean =
    text.singleUnicodeScalarOrNull()?.let { it in 0x1D400..0x1D7FF || it == 0x210E } == true
