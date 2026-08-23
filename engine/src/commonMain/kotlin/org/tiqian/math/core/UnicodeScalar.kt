package org.tiqian.math.core

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
