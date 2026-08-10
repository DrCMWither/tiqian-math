package org.tiqian.math.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MathStyleTest {
    @Test
    fun exposesAllEightStatesAndTeXTransitions() {
        assertEquals(8, MathStyle.entries.size)
        assertEquals(MathStyle.Text, MathStyle.Display.fractionNumerator())
        assertEquals(MathStyle.TextCramped, MathStyle.DisplayCramped.fractionNumerator())
        assertEquals(MathStyle.Script, MathStyle.Text.fractionNumerator())
        assertEquals(MathStyle.ScriptCramped, MathStyle.Text.fractionDenominator())
        assertEquals(MathStyle.ScriptCramped, MathStyle.DisplayCramped.superscript())
        assertEquals(MathStyle.ScriptCramped, MathStyle.Display.subscript())
        assertEquals(MathStyle.ScriptScriptCramped, MathStyle.ScriptCramped.superscript())
        assertEquals(MathStyle.ScriptScriptCramped, MathStyle.Script.subscript())
        assertEquals(MathStyle.ScriptScriptCramped, MathStyle.ScriptScriptCramped.fractionDenominator())
        assertEquals(
            listOf(
                MathStyle.Script,
                MathStyle.ScriptCramped,
                MathStyle.Script,
                MathStyle.ScriptCramped,
                MathStyle.ScriptScript,
                MathStyle.ScriptScriptCramped,
                MathStyle.ScriptScript,
                MathStyle.ScriptScriptCramped,
            ),
            MathStyle.entries.map { it.superscript() },
            "XeTeX clean_box sup transition 2*(style/4)+4+(style%2)",
        )
    }
}
