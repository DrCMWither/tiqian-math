package org.tiqian.math.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MathSymbolSemanticsTest {
    @Test
    fun symbolAxesRemainOrthogonalAndSourcePreserving() {
        val symbol = MathSymbol(
            sourceText = "x",
            identity = MathSymbolIdentity.LatinLetter('x'),
            atomClass = MathAtomClass.Ordinary,
            family = MathFamily.Letters,
            familyBinding = MathFamilyBinding.Variable,
            alphabet = MathAlphabet.MathNormal,
            range = SourceRange(4, 5),
        )

        assertEquals('x'.code, symbol.identity.baseScalar)
        assertEquals(MathAtomClass.Ordinary, symbol.atomClass)
        assertEquals(MathFamily.Letters, symbol.family)
        assertEquals(MathFamilyBinding.Variable, symbol.familyBinding)
        assertEquals(MathAlphabet.MathNormal, symbol.alphabet)
        assertEquals("x", symbol.sourceText)
        assertEquals(SourceRange(4, 5), symbol.range)
    }

    @Test
    fun mathAlphabetAndMathStyleAreDifferentAxes() {
        assertEquals(MathAlphabet.Roman, MathAlphabetScope(
            family = MathFamily.Operators,
            alphabet = MathAlphabet.Roman,
            body = MathErrorNode("", SourceRange.Empty),
            range = SourceRange.Empty,
        ).alphabet)
        assertEquals(
            MathStyleLevel.Script,
            MathStyleDeclaration(MathStyleLevel.Script, SourceRange.Empty).requestedLevel,
        )
    }

    @Test
    fun supportedMathematicalAlphanumericScalarsRoundTripThroughBaseIdentity() {
        listOf(
            0x1D465 to DecodedMathAlphabetScalar('x'.code, MathAlphabet.Italic),
            0x210E to DecodedMathAlphabetScalar('h'.code, MathAlphabet.Italic),
            0x1D6FC to DecodedMathAlphabetScalar(0x03B1, MathAlphabet.Italic),
            0x1D431 to DecodedMathAlphabetScalar('x'.code, MathAlphabet.Bold),
            0x1D6C2 to DecodedMathAlphabetScalar(0x03B1, MathAlphabet.Bold),
            0x1D499 to DecodedMathAlphabetScalar('x'.code, MathAlphabet.BoldItalic),
            0x1D736 to DecodedMathAlphabetScalar(0x03B1, MathAlphabet.BoldItalic),
            0x1D5D1 to DecodedMathAlphabetScalar('x'.code, MathAlphabet.SansSerif),
            0x1D7E4 to DecodedMathAlphabetScalar('2'.code, MathAlphabet.SansSerif),
        ).forEach { (styledScalar, expected) ->
            val decoded = assertNotNull(decodeMathAlphabetScalar(styledScalar), "U+${styledScalar.toString(16)}")
            assertEquals(expected, decoded)
            assertEquals(styledScalar, encodeMathAlphabetScalar(decoded.baseScalar, decoded.alphabet))
        }

        val contiguousRanges = listOf(
            0x1D400..0x1D433,
            0x1D434..0x1D44D,
            0x1D44E..0x1D467,
            0x1D468..0x1D49B,
            0x1D5A0..0x1D5D3,
            0x1D6A8..0x1D6E1,
            0x1D6E2..0x1D71B,
            0x1D71C..0x1D755,
            0x1D7CE..0x1D7D7,
            0x1D7E2..0x1D7EB,
        )
        contiguousRanges.flatten().filterNot { it == 0x1D455 }.forEach { styledScalar ->
            val decoded = assertNotNull(decodeMathAlphabetScalar(styledScalar), "U+${styledScalar.toString(16)}")
            assertEquals(
                styledScalar,
                encodeMathAlphabetScalar(decoded.baseScalar, decoded.alphabet),
                "U+${styledScalar.toString(16)}",
            )
        }
    }

    @Test
    fun extendedAlphabetsEncodeContiguousLettersAndLetterlikeHoles() {
        // Contiguous Plane-1 letters.
        assertEquals(0x1D538, encodeMathAlphabetScalar('A'.code, MathAlphabet.DoubleStruck))
        assertEquals(0x1D504, encodeMathAlphabetScalar('A'.code, MathAlphabet.Fraktur))
        assertEquals(0x1D49C, encodeMathAlphabetScalar('A'.code, MathAlphabet.Script))
        assertEquals(0x1D670, encodeMathAlphabetScalar('A'.code, MathAlphabet.Monospace))
        // Letterlike-block holes must redirect, not land on unassigned Plane-1 slots.
        assertEquals(0x211D, encodeMathAlphabetScalar('R'.code, MathAlphabet.DoubleStruck)) // ℝ
        assertEquals(0x2102, encodeMathAlphabetScalar('C'.code, MathAlphabet.DoubleStruck)) // ℂ
        assertEquals(0x212D, encodeMathAlphabetScalar('C'.code, MathAlphabet.Fraktur)) // ℭ
        assertEquals(0x211C, encodeMathAlphabetScalar('R'.code, MathAlphabet.Fraktur)) // ℜ
        assertEquals(0x211B, encodeMathAlphabetScalar('R'.code, MathAlphabet.Script)) // ℛ
        assertEquals(0x212F, encodeMathAlphabetScalar('e'.code, MathAlphabet.Script)) // ℯ
        // Digits: double-struck and monospace carry their own digit runs; script/fraktur do not.
        assertEquals(0x1D7D8, encodeMathAlphabetScalar('0'.code, MathAlphabet.DoubleStruck))
        assertEquals(0x1D7F6, encodeMathAlphabetScalar('0'.code, MathAlphabet.Monospace))
        assertEquals(null, encodeMathAlphabetScalar('0'.code, MathAlphabet.Script))
    }

    @Test
    fun extendedAlphabetPlaneOneAndLetterlikeScalarsRoundTripWithoutDecodingHoles() {
        listOf(
            MathAlphabet.Script,
            MathAlphabet.Fraktur,
            MathAlphabet.DoubleStruck,
            MathAlphabet.Monospace,
        ).forEach { alphabet ->
            val bases = ('A'..'Z') + ('a'..'z') + ('0'..'9')
            bases.forEach { base ->
                val encoded = encodeMathAlphabetScalar(base.code, alphabet) ?: return@forEach
                assertEquals(
                    DecodedMathAlphabetScalar(base.code, alphabet),
                    decodeMathAlphabetScalar(encoded),
                    "$alphabet/$base/U+${encoded.toString(16)}",
                )
            }
        }

        listOf(
            0x211B to DecodedMathAlphabetScalar('R'.code, MathAlphabet.Script),
            0x212F to DecodedMathAlphabetScalar('e'.code, MathAlphabet.Script),
            0x212D to DecodedMathAlphabetScalar('C'.code, MathAlphabet.Fraktur),
            0x211C to DecodedMathAlphabetScalar('R'.code, MathAlphabet.Fraktur),
            0x2102 to DecodedMathAlphabetScalar('C'.code, MathAlphabet.DoubleStruck),
            0x211D to DecodedMathAlphabetScalar('R'.code, MathAlphabet.DoubleStruck),
        ).forEach { (styled, expected) ->
            assertEquals(expected, decodeMathAlphabetScalar(styled), "U+${styled.toString(16)}")
        }

        // These are the unassigned Plane-1 slots replaced by Letterlike Symbols, not alternate glyphs.
        assertNull(decodeMathAlphabetScalar(0x1D49D)) // script B -> ℬ
        assertNull(decodeMathAlphabetScalar(0x1D506)) // fraktur C -> ℭ
        assertNull(decodeMathAlphabetScalar(0x1D53A)) // double-struck C -> ℂ
    }
}
