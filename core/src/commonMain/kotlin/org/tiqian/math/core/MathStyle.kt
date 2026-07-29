package org.tiqian.math.core

/**
 * TeX's eight math styles. Cramped is state, not an incidental boolean passed
 * by individual layout functions, so nested fractions and scripts cannot lose it.
 */
enum class MathStyle(
    val level: MathStyleLevel,
    val cramped: Boolean,
) {
    Display(MathStyleLevel.Display, false),
    DisplayCramped(MathStyleLevel.Display, true),
    Text(MathStyleLevel.Text, false),
    TextCramped(MathStyleLevel.Text, true),
    Script(MathStyleLevel.Script, false),
    ScriptCramped(MathStyleLevel.Script, true),
    ScriptScript(MathStyleLevel.ScriptScript, false),
    ScriptScriptCramped(MathStyleLevel.ScriptScript, true),
    ;

    fun cramped(): MathStyle = when (this) {
        Display, DisplayCramped -> DisplayCramped
        Text, TextCramped -> TextCramped
        Script, ScriptCramped -> ScriptCramped
        ScriptScript, ScriptScriptCramped -> ScriptScriptCramped
    }

    fun superscript(): MathStyle = when (level) {
        MathStyleLevel.Display, MathStyleLevel.Text -> if (cramped) ScriptCramped else Script
        MathStyleLevel.Script, MathStyleLevel.ScriptScript -> if (cramped) ScriptScriptCramped else ScriptScript
    }

    fun subscript(): MathStyle = when (level) {
        MathStyleLevel.Display, MathStyleLevel.Text -> ScriptCramped
        MathStyleLevel.Script, MathStyleLevel.ScriptScript -> ScriptScriptCramped
    }

    fun fractionNumerator(): MathStyle = when (this) {
        Display -> Text
        DisplayCramped -> TextCramped
        Text -> Script
        TextCramped -> ScriptCramped
        Script -> ScriptScript
        ScriptCramped -> ScriptScriptCramped
        ScriptScript -> ScriptScript
        ScriptScriptCramped -> ScriptScriptCramped
    }

    fun fractionDenominator(): MathStyle = when (level) {
        MathStyleLevel.Display -> TextCramped
        MathStyleLevel.Text -> ScriptCramped
        MathStyleLevel.Script, MathStyleLevel.ScriptScript -> ScriptScriptCramped
    }

    fun withLevel(newLevel: MathStyleLevel): MathStyle = when (newLevel) {
        MathStyleLevel.Display -> if (cramped) DisplayCramped else Display
        MathStyleLevel.Text -> if (cramped) TextCramped else Text
        MathStyleLevel.Script -> if (cramped) ScriptCramped else Script
        MathStyleLevel.ScriptScript -> if (cramped) ScriptScriptCramped else ScriptScript
    }

    companion object {
        fun initial(mode: MathMode): MathStyle = when (mode) {
            MathMode.Inline -> Text
            MathMode.Display -> Display
        }
    }
}

enum class MathStyleLevel {
    Display,
    Text,
    Script,
    ScriptScript,
}
