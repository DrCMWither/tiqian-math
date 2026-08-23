package org.tiqian.math.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface as SkiaSurface
import org.jetbrains.skia.Color as SkiaColor
import org.tiqian.math.compose.TiqianMath
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.core.MathHostTextFaceDecision
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.SourceRange
import org.tiqian.math.core.MathConstructionPaintKind
import org.tiqian.math.core.MathMode
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.skia.MathConstructionOutlineResult
import org.tiqian.math.font.skia.SkiaMathFontFace
import org.tiqian.math.font.skia.SkiaMathFontFamily
import org.tiqian.math.font.skia.SkiaMathTextRunProvider
import org.tiqian.math.font.skia.SkiaReplayCatalog
import org.tiqian.math.font.skia.SkiaReplayFace
import org.tiqian.math.font.skia.radicalSeamGeometry
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.MathTextRunProviderResult
import org.tiqian.math.layout.MathTextRunRequest
import org.tiqian.math.layout.MeasuredMathRun
import java.io.File
import kotlin.system.exitProcess

internal data class DelimiterNoadPreviewCase(
    val label: String,
    val source: String,
    val mode: MathMode = MathMode.Inline,
    val fontSizeSp: Int = 32,
    val lineHeightSp: Int = 58,
)

internal data class CommonExtensionPreviewCase(
    val label: String,
    val source: String,
    val mode: MathMode = MathMode.Inline,
    val lineHeightSp: Int = 100,
)

internal val commonExtensionPreviewCases = listOf(
    CommonExtensionPreviewCase("plain TeX generalized fraction", "E_{a\\atop b}+{a\\atop b}", lineHeightSp = 145),
    CommonExtensionPreviewCase("precomposed negated relation", "\\mu\\not\\equiv\\mu"),
    CommonExtensionPreviewCase("OpenType negation overlay / article kern bridge", "\\not p+\\not\\!p+\\not\\!k"),
    CommonExtensionPreviewCase("cancel.sty stroked overlay", "\\cancel{x+1}+\\frac{\\cancel{a}}{b}"),
    CommonExtensionPreviewCase("display fraction / centered continued fraction", "\\dfrac{a}{b}+\\cfrac{a}{b}"),
    CommonExtensionPreviewCase("continued fraction numerator alignment", "\\cfrac[l]{a}{bbbb}+\\cfrac[r]{a}{bbbb}"),
    CommonExtensionPreviewCase("nested continued fraction", "1+\\cfrac{a}{b+\\cfrac{c}{d}}", lineHeightSp = 170),
    CommonExtensionPreviewCase("mathop inline side scripts", "\\mathop{abc}_0^1"),
    CommonExtensionPreviewCase("mathop display / explicit limits", "\\mathop{abc}_0^1+\\mathop{x+y}\\limits_0^1", MathMode.Display, 130),
    CommonExtensionPreviewCase("top / bottom growing braces", "\\overbrace{a+b}^{n}+\\underbrace{a+b}_{n}", lineHeightSp = 135),
    CommonExtensionPreviewCase(
        "assembly braces",
        "\\overbrace{a+b+c+d+e}^{n}+\\underbrace{a+b+c+d+e}_{n}",
        lineHeightSp = 150,
    ),
)

internal val EQUATION_TAG_PREVIEW_CASES = listOf(
    "centered body / right tag" to "x+y\\tag{1}",
    "unwrapped tag*" to "x+y\\tag*{A}",
    "fraction body" to "\\frac{a+b}{c+d}=x\\tag{2}",
    "two tagged align rows" to "\\begin{align*}a&=b\\tag{3}\\\\c&=\\frac{d}{e}\\tag{4}\\end{align*}",
    "split body / vertically centered tag" to
        "\\begin{equation*}\\begin{split}a&=b\\\\c&=\\frac{d}{e}\\end{split}\\tag{7}\\end{equation*}",
)

internal const val EQUATION_TAG_OVERFLOW_SOURCE =
    "E=M+e(2-k)\\left[S_{1k}+S_{2k}-\\frac{1}{3}A_{2k}\\right]^{-1/2}, \\{e,M\\}\\in R_k,k=1 \\operatorname{or} 3\\tag{49}"

internal const val RESPONSIVE_DISPLAY_PREVIEW_SOURCE =
    "E_k=(n-1)E_{k-1}+E_{k-2}+\\frac{\\sum_{i=1}^{n}i^2}{\\binom{2n}{n}}+" +
        "\\sqrt{\\frac{a+b}{c+d}}=y_2^3\\tag{50}"

internal val CURRENT_ZHIHU_RESPONSIVE_CASES = listOf(
    "trailing row separator · equation 35" to
        "\\arg \\Omega_k^+(t)=\\tan^{-1}\\left[\\frac{2(-1)^{k+1}tC(t)\\left[e+(-1)^k\\frac{\\pi}{2}\\left|t\\right|\\right]}{\\left[e+(-1)^k\\frac{\\pi}{2}\\left|t\\right|\\right]^2-t^2[M-\\pi\\Delta(k)]^2-t^2C^2(t)}\\right]\\tag{35}\\\\",
    "responsive shifted tag gap · equation 36" to
        "E=M-e(M-\\pi)[e^2\\Omega_0(iy)E_0^2(iy)-y^2(M-\\pi)^2]^{-1/2},\\{e,M\\}\\in R_2 \\tag{36}\\\\",
    "right-aligned clause break · equation 38" to
        "\\boxed{E=M-e(M-\\pi)\\left[(e+1)^2-(M-\\pi)^2\\frac{2}{\\pi}\\int_0^1t\\arg \\Omega_0^+(t)\\mathrm{d}t\\right]^{-1/2},\\{e,M\\}\\in R_2 \\tag {38}}",
    "trailing row separator · equation 43" to
        "D_k(\\alpha,\\beta,\\gamma)=\\frac{1}{6}\\left[A_{1k}(\\alpha,\\beta,\\gamma)A_{2k}(\\alpha,\\beta,\\gamma)-3A_{0k}(\\alpha,\\beta,\\gamma)\\right]-[\\frac{1}{3}A_{2k}(\\alpha,\\beta,\\gamma)]^3\\tag{43}\\\\",
    "boxed terminal row separator · equation 49" to
        "\\boxed{E=M+e(2-k)\\left[S_{1k}+S_{2k}-\\frac{1}{3}A_{2k}\\right]^{-1/2},\\{e,M\\}\\in R_k,k=1\\ or\\ 3\\tag{49}\\\\}",
)

internal val COLOR_BOX_PREVIEW_CASES = listOf(
    "nested declaration scope" to "{\\color{red}a+{\\color{blue}b}+c}+d",
    "XeTeX middle/right color state" to "\\left(\\color{red}a\\middle|b\\right)+\\left(a\\middle|\\color{blue}b\\right)",
    "colored construction ownership" to "{\\color{royalblue}\\sqrt{\\frac{a}{b}}}",
    "boxed x / display fraction" to "\\boxed{x}+\\boxed{\\frac{a}{b}}",
    "boxed ordinary spacing" to "a\\boxed{b}c",
    "color owns box rules" to "{\\color{violet}\\boxed{\\frac{a}{b}}}+x",
    "MathJax padding + solid border" to "a+\\bbox[5px,border:1px solid red]{\\frac{x^2+1}{y}}+b",
    "MathJax background + padding" to "\\bbox[#CAF,12px]{x+\\sqrt{y}}",
    "nested bbox + construction" to "\\bbox[4px,border:2px solid blue]{\\sqrt{\\bbox[3px,yellow]{\\frac{a}{b}}}}",
)

private val DELIMITER_TALL_CONTENT =
    (1..8).fold("x") { content, _ -> "\\frac{$content}{y}" }

internal val DELIMITER_NOAD_PREVIEW_CASES = listOf(
    DelimiterNoadPreviewCase("fixed tiers", "\\bigl(x\\bigr)+\\Bigl[x\\Bigr]+\\biggl\\{x\\biggr\\}+\\Bigg\\langle x\\Bigg\\rangle", lineHeightSp = 96),
    DelimiterNoadPreviewCase("fixed content independence", "\\bigl(x\\bigr)\\quad\\bigl(\\frac{a}{b}\\bigr)", lineHeightSp = 76),
    DelimiterNoadPreviewCase("fixed fresh textstyle in script", "A_{\\bigl(x\\bigr)}+{\\scriptstyle\\Bigl[x\\Bigr]}", lineHeightSp = 90),
    DelimiterNoadPreviewCase("fixed assembly + invisible", "a\\big.b+\\Bigg\\uparrow x\\Bigg\\Downarrow", lineHeightSp = 120),
    DelimiterNoadPreviewCase("normal", "\\left(x\\right)"),
    DelimiterNoadPreviewCase("fraction", "\\left(\\frac{a}{b}\\right)"),
    DelimiterNoadPreviewCase("tall assembly", "\\left($DELIMITER_TALL_CONTENT\\right)", MathMode.Inline, 32, 180),
    DelimiterNoadPreviewCase("invisible", "\\left.\\frac{a}{b}\\right|"),
    DelimiterNoadPreviewCase("middle", "\\left\\langle a\\middle|\\frac{b}{c}\\right\\rangle"),
    DelimiterNoadPreviewCase("nested", "\\left[\\sqrt{\\frac{a}{\\left(b+c\\right)}}\\right]", MathMode.Display, 32, 100),
    DelimiterNoadPreviewCase("scripts", "\\left(\\frac{a}{b}\\right)_0^1"),
    DelimiterNoadPreviewCase(
        "complex",
        "\\left\\langle\\sqrt{\\frac{a+b}{c+d}}\\middle|\\frac{\\sum\\limits_{i=1}^{n}i^2}{\\binom{2n}{n}}\\right\\rangle",
        MathMode.Display,
        32,
        130,
    ),
)

internal val FRACTION_NOAD_PREVIEW_CASES = listOf(
    "inline fraction" to "\\frac{a}{b}",
    "display fraction" to "\\displaystyle\\frac{a}{b}",
    "inline binomial" to "\\binom{n}{k}",
    "display binomial" to "\\displaystyle\\binom{n}{k}",
    "script binomial" to "\\scriptstyle\\binom{n}{k}",
    "tall binomial" to "\\binom{\\frac{\\frac{a}{b}}{c}}{\\frac{d}{\\frac{e}{f}}}",
    "nested fraction/binomial" to "\\frac{\\binom{n}{k}}{\\binom{2n}{n-k}}",
)

internal const val RADICAL_BASE_SOURCE = "\\sqrt[3]{x}"
internal const val RADICAL_VARIANT_SOURCE = "\\sqrt{\\frac{a}{b}}"
private val RADICAL_ASSEMBLY_RADICAND =
    (1..12).fold("x") { radicand, _ -> "\\frac{$radicand}{y}" }
internal val RADICAL_ASSEMBLY_SOURCE = "\\sqrt{$RADICAL_ASSEMBLY_RADICAND}"
internal val RADICAL_ASSEMBLY_INDEXED_SOURCE = "\\sqrt[5]{$RADICAL_ASSEMBLY_RADICAND}"

internal data class RadicalVerticalPreviewCase(
    val label: String,
    val source: String,
    val mode: MathMode,
)

internal val RADICAL_VERTICAL_PREVIEW_CASES = listOf(
    RadicalVerticalPreviewCase("inline base x / X", "\\sqrt{x}+\\sqrt{X}", MathMode.Inline),
    RadicalVerticalPreviewCase("inline scripts", "\\sqrt{x_j^2}", MathMode.Inline),
    RadicalVerticalPreviewCase("inline fraction", "\\sqrt{\\frac{a}{b}}", MathMode.Inline),
    RadicalVerticalPreviewCase("inline nested", "\\sqrt{1+\\sqrt{x}}", MathMode.Inline),
    RadicalVerticalPreviewCase("inline indexed base", "\\sqrt[3]{X}", MathMode.Inline),
    RadicalVerticalPreviewCase("inline indexed fraction", "\\sqrt[3]{\\frac{a}{b}}", MathMode.Inline),
    RadicalVerticalPreviewCase("display fraction", "\\sqrt{\\frac{a}{b}}", MathMode.Display),
    RadicalVerticalPreviewCase("display assembly", RADICAL_ASSEMBLY_SOURCE, MathMode.Display),
)

internal data class OperatorSideScriptPreviewCase(
    val label: String,
    val source: String,
    val mode: MathMode,
    val fontSizeSp: Int = 32,
    val lineHeightSp: Int = 58,
)

private const val OPERATOR_COMPLEX_SOURCE =
    "\\sqrt[3]{\\frac{\\sum\\limits_{i=1}^{n}\\frac{i^2+\\alpha_i}{1+\\beta_i^2}+" +
        "\\int_0^1\\frac{x^2+1}{x^4+1}}{\\sqrt{\\frac{a+b}{c+d}}+\\binom{2n}{n}}}"

internal val OPERATOR_SIDE_SCRIPT_PREVIEW_CASES = listOf(
    OperatorSideScriptPreviewCase("inline upper/lower/both", "\\int^1+\\int_0+\\int_0^1", MathMode.Inline),
    OperatorSideScriptPreviewCase("inline int / oint", "\\int_0^1+\\oint_0^1", MathMode.Inline),
    OperatorSideScriptPreviewCase("display int / oint", "\\int_0^1+\\oint_0^1", MathMode.Display),
    OperatorSideScriptPreviewCase("display sum nolimits", "\\sum\\nolimits_0^1", MathMode.Display),
    OperatorSideScriptPreviewCase("complex integral", OPERATOR_COMPLEX_SOURCE, MathMode.Inline, 18, 46),
)
