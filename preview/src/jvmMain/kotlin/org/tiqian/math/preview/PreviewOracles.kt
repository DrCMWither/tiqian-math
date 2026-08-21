package org.tiqian.math.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
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

@Composable
internal fun EquationTagOracleScreen() {
    val lete = remember { SkiaMathFontFace(LeteSansMath.load()) }
    val stix = remember { SkiaMathFontFace(StixTwoMath.load()) }
    val leteText = remember {
        SkiaMathTextRunProvider.fromBytes(MathFaceId("preview-tag-text-lete"), LeteSansMath.loadBytes())
    }
    val stixText = remember {
        SkiaMathTextRunProvider.fromBytes(MathFaceId("preview-tag-text-stix"), StixTwoMath.loadBytes())
    }
    DisposableEffect(lete, stix, leteText, stixText) {
        onDispose {
            lete.close(); stix.close(); leteText.close(); stixText.close()
        }
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F5F1)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Tiqian · TeX equation tags + responsive electronic display · 32 px", fontSize = 17.sp)
                Text(
                    "Static tag geometry uses same-font Tectonic oracles; responsive wrapping is a named electronic-reading extension",
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    EquationTagFontColumn("Lete Sans Math", lete, leteText)
                    EquationTagFontColumn("STIX Two Math", stix, stixText)
                }
                EquationTagOverflowSample("Lete Sans Math", lete, leteText)
                EquationTagOverflowSample("STIX Two Math", stix, stixText)
                EquationTagResponsiveSample("Lete Sans Math", lete, leteText)
                EquationTagResponsiveSample("STIX Two Math", stix, stixText)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    CurrentZhihuResponsiveColumn("Lete Sans Math", lete, leteText)
                    CurrentZhihuResponsiveColumn("STIX Two Math", stix, stixText)
                }
            }
        }
    }
}

@Composable
private fun CurrentZhihuResponsiveColumn(
    label: String,
    face: SkiaMathFontFace,
    textProvider: SkiaMathTextRunProvider,
) {
    Column(Modifier.width(430.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label · current Zhihu answer · 416 px / 16 px = phone 1248 px / 48 px", fontSize = 9.sp)
        CURRENT_ZHIHU_RESPONSIVE_CASES.forEach { (caseLabel, source) ->
            Column(Modifier.background(Color.White).padding(7.dp)) {
                Text(caseLabel, fontSize = 8.sp, color = Color(0xFF6B655E))
                TiqianMath(
                    source = source,
                    modifier = Modifier.width(416.dp),
                    mode = MathMode.Display,
                    fontFace = face,
                    textRunProvider = textProvider,
                    style = TextStyle(fontSize = 16.sp, lineHeight = 36.sp),
                    softWrap = true,
                )
            }
        }
    }
}

@Composable
private fun EquationTagResponsiveSample(
    label: String,
    face: SkiaMathFontFace,
    textProvider: SkiaMathTextRunProvider,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            "$label · responsive display · full-width body · tag moves below right",
            fontSize = 9.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf(416 to "wide 416 px", 300 to "narrow 300 px").forEach { (width, caption) ->
                Column(Modifier.width(430.dp).background(Color.White).padding(7.dp)) {
                    Text(caption, fontSize = 8.sp, color = Color(0xFF6B655E))
                    TiqianMath(
                        source = RESPONSIVE_DISPLAY_PREVIEW_SOURCE,
                        modifier = Modifier.width(width.dp),
                        mode = MathMode.Display,
                        fontFace = face,
                        textRunProvider = textProvider,
                        style = TextStyle(fontSize = 32.sp, lineHeight = 72.sp),
                        softWrap = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun EquationTagOverflowSample(
    label: String,
    face: SkiaMathFontFace,
    textProvider: SkiaMathTextRunProvider,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$label · same 416 px viewport · body scrolls, tag stays right", fontSize = 9.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            EquationTagScrollViewport("scroll = 0 px", face, textProvider, remember { ScrollState(0) })
            EquationTagScrollViewport("scroll = 220 px", face, textProvider, remember { ScrollState(220) })
        }
    }
}

@Composable
private fun EquationTagScrollViewport(
    label: String,
    face: SkiaMathFontFace,
    textProvider: SkiaMathTextRunProvider,
    scrollState: ScrollState,
) {
    Column(Modifier.width(430.dp).background(Color.White).padding(7.dp)) {
        Text(label, fontSize = 8.sp, color = Color(0xFF6B655E))
        TiqianMath(
            source = EQUATION_TAG_OVERFLOW_SOURCE,
            modifier = Modifier.width(416.dp),
            mode = MathMode.Display,
            fontFace = face,
            textRunProvider = textProvider,
            style = TextStyle(fontSize = 32.sp, lineHeight = 72.sp),
            displayScrollState = scrollState,
            softWrap = false,
        )
    }
}

@Composable
private fun EquationTagFontColumn(
    label: String,
    face: SkiaMathFontFace,
    textProvider: SkiaMathTextRunProvider,
) {
    Column(Modifier.width(430.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$label · tag text face=${textProvider.faceId.value}", fontSize = 11.sp)
        EQUATION_TAG_PREVIEW_CASES.forEach { (caseLabel, source) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(caseLabel, fontSize = 9.sp, color = Color(0xFF6B655E))
                TiqianMath(
                    source = source,
                    modifier = Modifier.width(430.dp).background(Color.White).padding(7.dp),
                    mode = MathMode.Display,
                    fontFace = face,
                    textRunProvider = textProvider,
                    style = TextStyle(fontSize = 32.sp, lineHeight = 72.sp),
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
internal fun ColorBoxOracleScreen() {
    val lete = remember { SkiaMathFontFace(LeteSansMath.load()) }
    val stix = remember { SkiaMathFontFace(StixTwoMath.load()) }
    DisposableEffect(lete, stix) {
        onDispose { lete.close(); stix.close() }
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F5F1)) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text("Tiqian · xcolor / amsmath boxed / MathJax bbox · repository OTF · 32 px", fontSize = 17.sp)
                Text(
                    "Tectonic boxed + MathJax bbox: padding/background/border are explicit LayoutResult paint layers",
                    fontSize = 11.sp,
                    color = Color(0xFF55504A),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ColorBoxFontColumn("Lete Sans Math", lete)
                    ColorBoxFontColumn("STIX Two Math", stix)
                }
            }
        }
    }
}

@Composable
private fun ColorBoxFontColumn(label: String, face: SkiaMathFontFace) {
    Column(Modifier.width(650.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(label, fontSize = 14.sp)
        COLOR_BOX_PREVIEW_CASES.forEach { (caseLabel, source) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$caseLabel · $source", fontSize = 9.sp, color = Color(0xFF6B655E))
                TiqianMath(
                    source = source,
                    modifier = Modifier.background(Color.White).padding(8.dp),
                    mode = MathMode.Inline,
                    fontFace = face,
                    style = TextStyle(fontSize = 32.sp, lineHeight = 58.sp),
                    nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
                    delimiterShortfallPx = TECTONIC_DELIMITER_SHORTFALL_PX,
                    scriptSpacePx = TECTONIC_SCRIPT_SPACE_PX,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
internal fun CommonExtensionsOracleScreen() {
    val lete = remember { SkiaMathFontFace(LeteSansMath.load()) }
    val stix = remember { SkiaMathFontFace(StixTwoMath.load()) }
    DisposableEffect(lete, stix) {
        onDispose { lete.close(); stix.close() }
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F5F1)) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text("Tiqian · XeTeX common extensions · same repository OTF · 32 px", fontSize = 17.sp)
                Text("Reproducer: preview/tectonic/common-extension-oracle-{lete,stix}.tex", fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    CommonExtensionsFontColumn("Lete Sans Math", lete)
                    CommonExtensionsFontColumn("STIX Two Math", stix)
                }
            }
        }
    }
}

@Composable
private fun CommonExtensionsFontColumn(label: String, face: SkiaMathFontFace) {
    Column(Modifier.width(650.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 14.sp)
        commonExtensionPreviewCases.forEach { case ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(case.label, fontSize = 9.sp, color = Color(0xFF6B655E))
                TiqianMath(
                    source = case.source,
                    modifier = Modifier.background(Color.White).padding(7.dp),
                    mode = case.mode,
                    fontFace = face,
                    style = TextStyle(fontSize = 32.sp, lineHeight = case.lineHeightSp.sp),
                    nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
                    delimiterShortfallPx = TECTONIC_DELIMITER_SHORTFALL_PX,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
internal fun TableEnvironmentOracleScreen() {
    val lete = remember { SkiaMathFontFace(LeteSansMath.load()) }
    val stix = remember { SkiaMathFontFace(StixTwoMath.load()) }
    DisposableEffect(lete, stix) {
        onDispose {
            lete.close()
            stix.close()
        }
    }
    MaterialTheme {
        Surface(color = Color.White) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Structured TeX tables and display wrappers · source-aware rows", fontSize = 18.sp)
                Text(
                    "32 px display style · identical sources · Lete Sans Math / STIX Two Math",
                    fontSize = 11.sp,
                    color = Color(0xFF55504A),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                    TableEnvironmentFontColumn("Lete Sans Math", lete)
                    TableEnvironmentFontColumn("STIX Two Math", stix)
                }
            }
        }
    }
}

@Composable
private fun TableEnvironmentFontColumn(label: String, face: SkiaMathFontFace) {
    Column(Modifier.width(655.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 13.sp, color = Color(0xFF55504A))
        TABLE_ENVIRONMENT_PREVIEW_CASES.forEach { (caseLabel, source) ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("$caseLabel · $source", fontSize = 8.sp, color = Color(0xFF6B655E))
                TiqianMath(
                    source = source,
                    modifier = Modifier.background(Color(0xFFF7F5F1)).padding(6.dp),
                    mode = MathMode.Display,
                    fontFace = face,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 32.sp, lineHeight = 48.sp),
                    softWrap = false,
                )
            }
        }
    }
}

private val TABLE_ENVIRONMENT_PREVIEW_CASES = listOf(
    "Markdown display rows" to """a=b\\c=\frac{d}{e}""",
    "Markdown rows + explicit .2 cm gap" to """1\\[.2cm]22""",
    "display align wrapper" to """\begin{align*}a&=b\\c&=\frac{d}{e}\end{align*}""",
    "display equation wrapper" to """\begin{equation}a=\frac{b}{c}\end{equation}""",
    "aligned equations" to """\begin{aligned}a&=b\\c&=\frac{d}{e}\end{aligned}""",
    "aligned + explicit .2 cm row spacing" to """\begin{aligned}a&=b\\[.2cm]c&=d\end{aligned}""",
    "array + explicit .2 cm row strut" to """\begin{array}{cc}a&b\\[.2cm]c&d\end{array}""",
    "array horizontal rules" to """\begin{array}{c}a\\\hline b\\\hline c\end{array}""",
    "parenthesized matrix + scripts" to """\begin{pmatrix}a&b\\\frac{c}{d}&\sqrt{x}\end{pmatrix}_0^1""",
    "cases" to """\begin{cases}x&x>0\\\frac{a}{b}&x\le0\end{cases}""",
    "nested table structures" to
        """\begin{bmatrix}\sqrt{x}&\binom{n}{k}\\\sum_{i=1}^{n}i&\begin{matrix}a&b\\c&d\end{matrix}\end{bmatrix}""",
)

@Composable
internal fun FontFamilyFallbackScreen() {
    val family = remember { SkiaMathFontFamily.loadBundledLete() }
    val textProvider = remember { loadPreviewHostTextProvider() }
    DisposableEffect(family, textProvider) { onDispose { family.close(); textProvider.close() } }
    MaterialTheme {
        Surface(color = Color(0xFFF7F5F1)) {
            Column(Modifier.fillMaxSize().padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Math family weight + explicit host text provider", fontSize = 22.sp)
                Text(
                    "provider=${textProvider.description} · requested/resolved weight and selection reason are recorded per glyph",
                    fontSize = 11.sp,
                    color = Color(0xFF55504A),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    FontFamilyColumn("Regular heading", FontWeight.Normal, family, textProvider)
                    FontFamilyColumn("Bold heading", FontWeight.Bold, family, textProvider)
                }
                Text("Construction ownership · Bold request falls back as one complete MATH atom when needed", fontSize = 13.sp)
                TiqianMath(
                    source = "\\sqrt{\\frac{\\frac{a+中文}{b}}{\\frac{c}{d}}}+\\left(\\frac{中文+x}{b}\\right)+\\binom{2n}{n}",
                    fontFace = family,
                    textRunProvider = textProvider,
                    mode = MathMode.Display,
                    style = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.background(Color.White).padding(12.dp),
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun FontFamilyColumn(
    label: String,
    weight: FontWeight,
    family: SkiaMathFontFamily,
    textProvider: PreviewHostTextRunProvider,
) {
    Column(Modifier.width(650.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 15.sp, fontWeight = weight)
        Text(
            textProvider.auditLabel(if (weight == FontWeight.Bold) MathFontWeight.Bold else MathFontWeight.Regular),
            fontSize = 8.sp,
            color = Color(0xFF6B655E),
        )
        listOf(
            "math + official missing-glyph fallback" to "x+y+\\alpha+\\aleph_0+\\int_0^1",
            "grouped legacy math bold + host bold text" to "{\\bf 0}+\\textbf{1}",
            "embedded and raw CJK" to "\\text{速率 rate 2}+原始中文+x",
            "mixed scripts at MATH script size" to "x^{中文2}+y_{片仮名3}+z^{한글4}",
        ).forEach { (caseLabel, source) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(caseLabel, fontSize = 10.sp, color = Color(0xFF69635C))
                TiqianMath(
                    source = source,
                    fontFace = family,
                    textRunProvider = textProvider,
                    style = TextStyle(fontSize = 34.sp, fontWeight = weight),
                    modifier = Modifier.background(Color.White).padding(10.dp),
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
internal fun ExtendedStructureOracleScreen() {
    val lete = remember { SkiaMathFontFace(LeteSansMath.load()) }
    val stix = remember { SkiaMathFontFace(StixTwoMath.load()) }
    val textProvider = remember { loadPreviewHostTextProvider() }
    DisposableEffect(lete, stix, textProvider) {
        onDispose { lete.close(); stix.close(); textProvider.close() }
    }
    MaterialTheme {
        Surface(color = Color.White) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tiqian · embedded text / operatorname / OpenType accents / rule decorations", fontSize = 16.sp)
                Text("same source and nominal 32 px · exact repository OTF · no visual offsets", fontSize = 10.sp, color = Color(0xFF55504A))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    listOf("Lete Sans Math" to lete, "STIX Two Math" to stix).forEach { (label, face) ->
                        Column(Modifier.width(570.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(label, fontSize = 13.sp, color = Color(0xFF55504A))
                            listOf(
                                "\\text{rank and rate}+\\operatorname{rank}_A",
                                "\\hat{x}+\\bar{x}+\\vec{v}+\\widehat{x+y+z}+\\widetilde{abc}",
                                "\\overline{x+\\underline{\\frac{a}{b}}}+\\underline{\\sqrt{x}}",
                                "\\operatorname*{argmax}_{x\\to\\infty}\\overline{\\widehat{x+y}}+\\text{ subject to }\\underline{\\frac{a}{b}}",
                                "X\\xrightarrow[k-1]{p_k}Y+Z\\xleftarrow{f}W",
                                "a\\overset{u}{=}b+\\underset{d}{x}+\\stackrel{def}{=}",
                            ).forEach { source ->
                                Text(source, fontSize = 8.sp, color = Color(0xFF6B655E))
                                TiqianMath(
                                    source = source,
                                    modifier = Modifier.background(Color(0xFFF7F5F1)).padding(7.dp),
                                    mode = MathMode.Display,
                                    fontFace = face,
                                    textRunProvider = textProvider,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 32.sp, lineHeight = 52.sp),
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DelimiterNoadOracleScreen() {
    val lete = remember { SkiaMathFontFace(LeteSansMath.load()) }
    val stix = remember { SkiaMathFontFace(StixTwoMath.load()) }
    DisposableEffect(lete, stix) {
        onDispose {
            lete.close()
            stix.close()
        }
    }
    MaterialTheme {
        Surface(color = Color.White) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tiqian · exact repository OTF · Tectonic 0.17.0 comparison settings", fontSize = 13.sp)
                Text(
                    "nominal 32 px · delimiterfactor=901 · delimitershortfall=5pt · nulldelimiterspace=1.2pt",
                    fontSize = 10.sp,
                    color = Color(0xFF55504A),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    DelimiterNoadFontColumn("Lete Sans Math", lete)
                    DelimiterNoadFontColumn("STIX Two Math", stix)
                }
            }
        }
    }
}

@Composable
private fun DelimiterNoadFontColumn(label: String, face: SkiaMathFontFace) {
    Column(Modifier.width(660.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF55504A))
        DELIMITER_NOAD_PREVIEW_CASES.forEach { case ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${case.label} · ${case.source}", fontSize = 8.sp, color = Color(0xFF6B655E))
                TiqianMath(
                    source = case.source,
                    modifier = Modifier.background(Color(0xFFF7F5F1)).padding(4.dp),
                    mode = case.mode,
                    fontFace = face,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = case.fontSizeSp.sp,
                        lineHeight = case.lineHeightSp.sp,
                    ),
                    nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
                    delimiterShortfallPx = TECTONIC_DELIMITER_SHORTFALL_PX,
                    scriptSpacePx = TECTONIC_SCRIPT_SPACE_PX,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
internal fun OperatorSideScriptOracleScreen() {
    val lete = remember { SkiaMathFontFace(LeteSansMath.load()) }
    val stix = remember { SkiaMathFontFace(StixTwoMath.load()) }
    DisposableEffect(lete, stix) {
        onDispose {
            lete.close()
            stix.close()
        }
    }
    MaterialTheme {
        Surface(color = Color.White) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tiqian · exact repository OTF · Tectonic 0.17.0 comparison settings", fontSize = 13.sp)
                Text(
                    "nominal 32 px · inline/display · TeX scriptspace 0.5pt · no visual offsets",
                    fontSize = 10.sp,
                    color = Color(0xFF55504A),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    OperatorSideScriptFontColumn("Lete Sans Math", lete)
                    OperatorSideScriptFontColumn("STIX Two Math", stix)
                }
            }
        }
    }
}

@Composable
private fun OperatorSideScriptFontColumn(label: String, face: SkiaMathFontFace) {
    Column(Modifier.width(410.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF55504A))
        OPERATOR_SIDE_SCRIPT_PREVIEW_CASES.forEach { case ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${case.label} · ${case.source}", fontSize = 9.sp, color = Color(0xFF6B655E))
                TiqianMath(
                    source = case.source,
                    modifier = Modifier.background(Color(0xFFF7F5F1)).padding(4.dp),
                    mode = case.mode,
                    fontFace = face,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = case.fontSizeSp.sp,
                        lineHeight = case.lineHeightSp.sp,
                    ),
                    nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
                    scriptSpacePx = TECTONIC_SCRIPT_SPACE_PX,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
internal fun RadicalVerticalOracleScreen() {
    val lete = remember { SkiaMathFontFace(LeteSansMath.load()) }
    val stix = remember { SkiaMathFontFace(StixTwoMath.load()) }
    DisposableEffect(lete, stix) {
        onDispose {
            lete.close()
            stix.close()
        }
    }
    MaterialTheme {
        Surface(color = Color.White) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tiqian · nominal 32 px · exact repository OTF · Tectonic 0.17.0 trace sources", fontSize = 13.sp)
                Text(
                    "clean_box h+d → construction advance → TeX clearance/rule/outer box · null delimiter 1.2pt",
                    fontSize = 10.sp,
                    color = Color(0xFF55504A),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    RadicalVerticalFontColumn("Lete Sans Math", lete)
                    RadicalVerticalFontColumn("STIX Two Math", stix)
                }
            }
        }
    }
}

@Composable
private fun RadicalVerticalFontColumn(label: String, face: SkiaMathFontFace) {
    Column(Modifier.width(410.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF55504A))
        RADICAL_VERTICAL_PREVIEW_CASES.forEach { case ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${case.label} · ${case.source}", fontSize = 9.sp, color = Color(0xFF6B655E))
                TiqianMath(
                    source = case.source,
                    modifier = Modifier.background(Color(0xFFF7F5F1)).padding(4.dp),
                    mode = case.mode,
                    fontFace = face,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 32.sp, lineHeight = 44.sp),
                    nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
internal fun FractionNoadOracleScreen() {
    val lete = remember { SkiaMathFontFace(LeteSansMath.load()) }
    val stix = remember { SkiaMathFontFace(StixTwoMath.load()) }
    DisposableEffect(lete, stix) {
        onDispose {
            lete.close()
            stix.close()
        }
    }
    MaterialTheme {
        Surface(color = Color.White) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tiqian · inline host · nominal 32 px · exact repository OTF", fontSize = 13.sp)
                Text(
                    "genfrac targets: D 2.39em · T 1em · S 1.45em · SS 1.35em · null delimiter 1.2pt",
                    fontSize = 10.sp,
                    color = Color(0xFF55504A),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    FractionNoadFontColumn("Lete Sans Math", lete)
                    FractionNoadFontColumn("STIX Two Math", stix)
                }
            }
        }
    }
}

@Composable
private fun FractionNoadFontColumn(label: String, face: SkiaMathFontFace) {
    Column(Modifier.width(410.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF55504A))
        FRACTION_NOAD_PREVIEW_CASES.forEach { (caseLabel, source) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$caseLabel · $source", fontSize = 9.sp, color = Color(0xFF6B655E))
                TiqianMath(
                    source = source,
                    modifier = Modifier.background(Color(0xFFF7F5F1)).padding(4.dp),
                    mode = MathMode.Inline,
                    fontFace = face,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 32.sp, lineHeight = 44.sp),
                    nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
internal fun RadicalDegreeOracleScreen() {
    val lete = remember { SkiaMathFontFace(LeteSansMath.load()) }
    val stix = remember { SkiaMathFontFace(StixTwoMath.load()) }
    DisposableEffect(lete, stix) {
        onDispose {
            lete.close()
            stix.close()
        }
    }
    MaterialTheme {
        Surface(color = Color.White) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tiqian · inline · 32 px · \\sqrt[3]{X}", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    RadicalDegreeTier("Lete Sans Math", "\\sqrt[3]{X}", lete)
                    RadicalDegreeTier("STIX Two Math", "\\sqrt[3]{X}", stix)
                }
            }
        }
    }
}
