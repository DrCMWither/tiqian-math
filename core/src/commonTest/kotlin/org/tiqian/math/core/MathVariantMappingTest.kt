package org.tiqian.math.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MathVariantMappingTest {
    @Test
    fun latinAndApplicableGreekVariablesResolveToStandardItalicScalars() {
        assertEquals("𝑥", symbol("x", MathVariant.DefaultVariableItalic).selectMathVariant().glyphText)
        assertEquals("ℎ", symbol("h", MathVariant.DefaultVariableItalic).selectMathVariant().glyphText)
        assertEquals("𝛼", symbol("α", MathVariant.DefaultVariableItalic).selectMathVariant().glyphText)
        assertEquals("𝜕", symbol("∂", MathVariant.DefaultVariableItalic).selectMathVariant().glyphText)
    }

    @Test
    fun uprightAndExplicitMathematicalScalarsAreNeverRemapped() {
        assertFalse(symbol("Γ", MathVariant.Upright).selectMathVariant().remapped)
        assertFalse(symbol("2", MathVariant.Upright).selectMathVariant().remapped)
        val explicit = symbol("𝑥", MathVariant.ExplicitUnicode).selectMathVariant()
        assertEquals("𝑥", explicit.glyphText)
        assertFalse(explicit.remapped)
        assertTrue(isExplicitMathematicalAlphanumeric("𝑥"))
    }

    private fun symbol(text: String, variant: MathVariant) = MathSymbol(
        sourceText = text,
        displayText = text,
        atomClass = MathAtomClass.Ordinary,
        variant = variant,
        range = SourceRange(0, text.length),
    )
}
