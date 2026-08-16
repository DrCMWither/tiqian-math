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

@Composable
internal fun PreviewScreen() {
    val lete = remember { SkiaMathFontFace(LeteSansMath.load()) }
    val stix = remember { SkiaMathFontFace(StixTwoMath.load()) }
    val textProvider = remember { loadPreviewHostTextProvider() }
    DisposableEffect(lete, stix, textProvider) {
        onDispose {
            lete.close()
            stix.close()
            textProvider.close()
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F5F1)) {
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("Tiqian Math — real OpenType MATH pipeline", fontSize = 20.sp)
                Text("Variable-family italic vs \\mathrm Latin control", fontSize = 13.sp)
                VariantControlSample("Lete Sans Math", lete)
                VariantControlSample("STIX Two Math", stix)
                FontSample("Lete Sans Math · display", lete, MathMode.Display)
                FontSample("STIX Two Math · display", stix, MathMode.Display)
                Text("STIX Two Math · XeTeX 0.17 operator ladder and zero-ppem accent evidence", fontSize = 13.sp)
                XeTeXStixAlignmentSample(stix)
                Text("Embedded text, declared operators, accents and rule decorations", fontSize = 13.sp)
                ExtendedStructureSample("Lete Sans Math", lete, textProvider)
                ExtendedStructureSample("STIX Two Math", stix, textProvider)
                Text("Embedded text baseline · Row alignByBaseline · same nominal 24 px", fontSize = 13.sp)
                EmbeddedTextBaselineSample("Lete Sans Math", lete, textProvider)
                EmbeddedTextBaselineSample("STIX Two Math", stix, textProvider)
                Text("Indexed, nested, fraction and stretched radicals", fontSize = 13.sp)
                RadicalSample("Lete Sans Math", lete)
                RadicalSample("STIX Two Math", stix)
                Text("Radical degree horizontal placement · TeX source for Tectonic comparison", fontSize = 13.sp)
                RadicalDegreeHorizontalComparison(lete, stix)
                Text("Ordinary side scripts · ink-constrained placement", fontSize = 13.sp)
                SideScriptSample("Lete Sans Math", lete)
                SideScriptSample("STIX Two Math", stix)
                Text("Operator side scripts · XeTeX make_op width/delta", fontSize = 13.sp)
                OperatorSideScriptSample("Lete Sans Math", lete)
                OperatorSideScriptSample("STIX Two Math", stix)
                Text("TeX fraction noad · fixed-style binomial delimiters", fontSize = 13.sp)
                ScriptBinomialSample("Lete Sans Math", lete)
                ScriptBinomialSample("STIX Two Math", stix)
                Text("TeX content-driven delimiters · shared left/middle/right target", fontSize = 13.sp)
                DelimiterNoadSample("Lete Sans Math", lete)
                DelimiterNoadSample("STIX Two Math", stix)
                Text("Operator-trailing line breaks · 260 px host width", fontSize = 13.sp)
                TiqianMath(
                    source = "E_k=(n-1)E_{k-1}+E_{k-2}+\\frac{a+b}{\\binom{n}{k}}=y_2^3",
                    modifier = Modifier.width(260.dp).background(Color.White).padding(8.dp),
                    fontFace = lete,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 25.sp, lineHeight = 34.sp),
                    softWrap = true,
                )
            }
        }
    }
}

@Composable
private fun EmbeddedTextBaselineSample(
    label: String,
    face: SkiaMathFontFace,
    textProvider: MathTextRunProvider,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label · ABCxyz —", modifier = Modifier.alignByBaseline(), fontSize = 24.sp)
        TiqianMath(
            source = "x+\\text{rank}+x",
            modifier = Modifier.alignByBaseline().background(Color.White).padding(horizontal = 5.dp),
            mode = MathMode.Inline,
            fontFace = face,
            textRunProvider = textProvider,
            style = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
            softWrap = false,
        )
    }
}

@Composable
private fun XeTeXStixAlignmentSample(face: SkiaMathFontFace) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OperatorSideScriptTier("display · glyph 1647 / 1641", "\\sum_0^1+\\prod_0^1", MathMode.Display, face)
        OperatorSideScriptTier("inline · glyph 732 / 744", "\\hat{a}+\\bar{a}", MathMode.Inline, face)
    }
}

@Composable
private fun ExtendedStructureSample(
    label: String,
    face: SkiaMathFontFace,
    textProvider: MathTextRunProvider,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF55504A))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            ExtendedStructureTier("text / operator", "\\text{rank and rate}+\\operatorname{rank}_A", face, textProvider)
            ExtendedStructureTier("fixed / wide accents", "\\hat{x}+\\bar{x}+\\vec{v}+\\widehat{x+y+z}+\\widetilde{abc}", face, textProvider)
            ExtendedStructureTier("nested rules", "\\overline{x+\\underline{\\frac{a}{b}}}+\\underline{\\sqrt{x}}", face, textProvider)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            ExtendedStructureTier("extensible arrows", "X\\xrightarrow[k-1]{p_k}Y+Z\\xleftarrow{f}W", face, textProvider)
            ExtendedStructureTier("over / under / stackrel", "a\\overset{u}{=}b+\\underset{d}{x}+\\stackrel{def}{=}", face, textProvider)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            ExtendedStructureTier("growing braces", "\\overbrace{a+b+c+d+e}^{n}+\\underbrace{x+y+z}_{k}", face, textProvider)
            ExtendedStructureTier("dfrac / cfrac / mathop", "\\dfrac{a}{b}+\\cfrac[l]{x}{bbbb}+\\mathop{rank}_0^1", face, textProvider)
        }
    }
}

@Composable
private fun ExtendedStructureTier(
    label: String,
    source: String,
    face: SkiaMathFontFace,
    textProvider: MathTextRunProvider,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 9.sp, color = Color(0xFF6B655E))
        TiqianMath(
            source = source,
            modifier = Modifier.background(Color.White).padding(5.dp),
            mode = MathMode.Display,
            fontFace = face,
            textRunProvider = textProvider,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 24.sp, lineHeight = 38.sp),
            softWrap = false,
        )
    }
}

@Composable
private fun DelimiterNoadSample(label: String, face: SkiaMathFontFace) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF55504A))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DelimiterPreviewTier("normal", "\\left(x\\right)", MathMode.Inline, face, 24)
            DelimiterPreviewTier("middle", "\\left\\langle a\\middle|b\\right\\rangle", MathMode.Inline, face, 24)
            DelimiterPreviewTier("invisible", "\\left.\\frac{a}{b}\\right|", MathMode.Inline, face, 22)
        }
    }
}

@Composable
private fun DelimiterPreviewTier(
    label: String,
    source: String,
    mode: MathMode,
    face: SkiaMathFontFace,
    sizeSp: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 9.sp, color = Color(0xFF6B655E))
        TiqianMath(
            source = source,
            modifier = Modifier.background(Color.White).padding(5.dp),
            mode = mode,
            fontFace = face,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = sizeSp.sp, lineHeight = (sizeSp + 14).sp),
            nullDelimiterSpacePx = TECTONIC_NULL_DELIMITER_SPACE_PX,
            delimiterShortfallPx = TECTONIC_DELIMITER_SHORTFALL_PX,
            softWrap = false,
        )
    }
}

@Composable
private fun RadicalDegreeHorizontalComparison(
    lete: SkiaMathFontFace,
    stix: SkiaMathFontFace,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf(
            "Lete Sans Math" to lete,
            "STIX Two Math" to stix,
        ).forEach { (label, face) ->
            Column(Modifier.width(390.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, fontSize = 12.sp, color = Color(0xFF55504A))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadicalDegreeTier("plain inline", "\\sqrt{x}", face)
                    RadicalDegreeTier("oracle · ³√X", "\\sqrt[3]{X}", face)
                    RadicalDegreeTier("wide degree", "\\sqrt[g_j+abc]{x}", face)
                }
            }
        }
    }
}

@Composable
internal fun RadicalDegreeTier(label: String, source: String, face: SkiaMathFontFace) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 10.sp, color = Color(0xFF6B655E))
        TiqianMath(
            source = source,
            modifier = Modifier.background(Color.White).padding(7.dp),
            mode = MathMode.Inline,
            fontFace = face,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 32.sp, lineHeight = 44.sp),
            softWrap = false,
        )
    }
}

@Composable
private fun SideScriptSample(label: String, face: SkiaMathFontFace) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF55504A))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SideScriptTier("character base", "x_1^2", face)
            SideScriptTier("radical base", "\\sqrt{x}_1^2", face)
            SideScriptTier("radical superscript", "x^{\\sqrt{y_j}}", face)
            SideScriptTier("paired radicals", "x_{\\sqrt{X}}^{\\sqrt{y_j}}", face)
        }
    }
}

@Composable
private fun SideScriptTier(label: String, source: String, face: SkiaMathFontFace) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 10.sp, color = Color(0xFF6B655E))
        TiqianMath(
            source = source,
            modifier = Modifier.background(Color.White).padding(7.dp),
            mode = MathMode.Inline,
            fontFace = face,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 30.sp, lineHeight = 42.sp),
            softWrap = false,
        )
    }
}

@Composable
private fun OperatorSideScriptSample(label: String, face: SkiaMathFontFace) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF55504A))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OperatorSideScriptTier("inline", "\\int_0^1+\\oint_0^1", MathMode.Inline, face)
            OperatorSideScriptTier("display", "\\int_0^1+\\oint_0^1", MathMode.Display, face)
            OperatorSideScriptTier("forced nolimits", "\\sum\\nolimits_0^1", MathMode.Display, face)
        }
    }
}

@Composable
private fun OperatorSideScriptTier(
    label: String,
    source: String,
    mode: MathMode,
    face: SkiaMathFontFace,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 10.sp, color = Color(0xFF6B655E))
        TiqianMath(
            source = source,
            modifier = Modifier.background(Color.White).padding(7.dp),
            mode = mode,
            fontFace = face,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 28.sp, lineHeight = 52.sp),
            scriptSpacePx = TECTONIC_SCRIPT_SPACE_PX,
            softWrap = false,
        )
    }
}

@Composable
private fun RadicalSample(label: String, face: SkiaMathFontFace) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF55504A))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadicalTier("base · x", "\\sqrt{x}", face)
            RadicalTier("base · X", "\\sqrt{X}", face)
            RadicalTier("ascender / descender", "\\sqrt{x_j^2}", face)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadicalTier("variant · fraction", RADICAL_VARIANT_SOURCE, face)
            RadicalTier("nested · linked groups", "\\sqrt{1+\\sqrt{x}}", face)
            RadicalTier("assembly · deep fraction", RADICAL_ASSEMBLY_SOURCE, face)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadicalTier("indexed base · ²√x", "\\sqrt[2]{x}", face)
            RadicalTier("indexed base · ³√X", "\\sqrt[3]{X}", face)
            RadicalTier("indexed high / deep", "\\sqrt[g_j]{x_j^2}", face)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadicalTier("indexed variant", "\\sqrt[3]{\\frac{a}{b}}", face)
            RadicalTier("indexed assembly", RADICAL_ASSEMBLY_INDEXED_SOURCE, face)
        }
    }
}

@Composable
private fun RadicalTier(label: String, source: String, face: SkiaMathFontFace) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 10.sp, color = Color(0xFF6B655E))
        TiqianMath(
            source = source,
            modifier = Modifier.background(Color.White).padding(7.dp),
            mode = MathMode.Display,
            fontFace = face,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 32.sp, lineHeight = 44.sp),
            softWrap = false,
        )
    }
}

@Composable
private fun VariantControlSample(label: String, face: SkiaMathFontFace) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF55504A))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("default variable italic", fontSize = 11.sp)
                TiqianMath(
                    source = "x+y+abc+\\alpha+\\beta",
                    modifier = Modifier.background(Color.White).padding(6.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 28.sp, lineHeight = 38.sp),
                    fontFace = face,
                    softWrap = false,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("\\mathrm Latin control", fontSize = 11.sp)
                TiqianMath(
                    source = "\\mathrm{x+y+abc+2}",
                    modifier = Modifier.background(Color.White).padding(6.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 28.sp, lineHeight = 38.sp),
                    fontFace = face,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun ScriptBinomialSample(label: String, face: SkiaMathFontFace) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF55504A))
        TiqianMath(
            source = "x_{k-1}+\\frac{a}{\\binom{n}{k}}+\\scriptscriptstyle{\\binom{p}{q}}",
            modifier = Modifier.background(Color.White).padding(6.dp),
            mode = MathMode.Inline,
            fontFace = face,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 30.sp, lineHeight = 40.sp),
            softWrap = false,
        )
    }
}

@Composable
private fun FontSample(label: String, face: SkiaMathFontFace, mode: MathMode) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 13.sp, color = Color(0xFF55504A))
        TiqianMath(
            source = "\\sum_{i=1}^n+\\int\\limits_0^1+x_1^2+\\frac{a+b}{\\binom{n}{k}}=y_2^3",
            modifier = Modifier.background(Color.White).padding(8.dp),
            mode = mode,
            fontFace = face,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 34.sp, lineHeight = 46.sp),
            softWrap = false,
        )
    }
}

