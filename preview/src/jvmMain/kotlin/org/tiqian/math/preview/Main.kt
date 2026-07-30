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
import org.tiqian.math.core.MathConstructionPaintKind
import org.tiqian.math.core.MathMode
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.skia.MathConstructionOutlineResult
import org.tiqian.math.font.skia.SkiaMathFontFace
import org.tiqian.math.font.skia.radicalSeamGeometry
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.contains("--snapshot")) {
        renderSnapshot()
        // Skiko may leave its event-dispatch thread alive after an offscreen scene.
        exitProcess(0)
    } else {
        application {
            Window(onCloseRequest = ::exitApplication, title = "Tiqian Math Compose") {
                PreviewScreen()
            }
        }
    }
}

@Composable
private fun PreviewScreen() {
    val lete = remember { SkiaMathFontFace(LeteSansMath.load()) }
    val stix = remember { SkiaMathFontFace(StixTwoMath.load()) }
    DisposableEffect(lete, stix) {
        onDispose {
            lete.close()
            stix.close()
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
private fun RadicalDegreeTier(label: String, source: String, face: SkiaMathFontFace) {
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

@OptIn(ExperimentalComposeUiApi::class)
private fun renderSnapshot() {
    auditRadicalPreviewTiers()
    ImageComposeScene(width = 900, height = 5200) { PreviewScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/math-preview.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("preview=${output.absolutePath} bytes=${output.length()}")
    }
    ImageComposeScene(width = 900, height = 190) { RadicalDegreeOracleScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/tiqian-radical-degree-inline.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("radical-degree-oracle=${output.absolutePath} bytes=${output.length()}")
    }
    ImageComposeScene(width = 900, height = 960) { FractionNoadOracleScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/tiqian-fraction-noad.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("fraction-noad-oracle=${output.absolutePath} bytes=${output.length()}")
    }
    ImageComposeScene(width = 900, height = 1320) { RadicalVerticalOracleScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/tiqian-radical-vertical.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("radical-vertical-oracle=${output.absolutePath} bytes=${output.length()}")
    }
    ImageComposeScene(width = 900, height = 760) { OperatorSideScriptOracleScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/tiqian-operator-side-scripts.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("operator-side-script-oracle=${output.absolutePath} bytes=${output.length()}")
    }
    ImageComposeScene(width = 1400, height = 1900) { DelimiterNoadOracleScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/tiqian-delimiter-noad.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("delimiter-noad-oracle=${output.absolutePath} bytes=${output.length()}")
    }
    renderRadicalSeamReport()
}

@Composable
private fun DelimiterNoadOracleScreen() {
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
private fun OperatorSideScriptOracleScreen() {
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
private fun RadicalVerticalOracleScreen() {
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
private fun FractionNoadOracleScreen() {
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
private fun RadicalDegreeOracleScreen() {
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

private fun auditRadicalPreviewTiers() {
    listOf(
        "Lete Sans Math" to LeteSansMath.load(),
        "STIX Two Math" to StixTwoMath.load(),
    ).forEach { (label, font) ->
        SkiaMathFontFace(font).use { face ->
            listOf(
                "BaseGlyph" to RADICAL_BASE_SOURCE,
                "Variant" to RADICAL_VARIANT_SOURCE,
                "Assembly" to RADICAL_ASSEMBLY_SOURCE,
            ).forEach { (expected, source) ->
                val result = MathLayoutEngine(face).layout(
                    source,
                    MathLayoutOptions(MathMode.Display, 32f),
                )
                val actual = result.decisions.first {
                    it.name == "OpenTypeRadicalConstruction" && it.range.start == 0
                }.details["construction"]
                val construction = result.decisions.first {
                    it.name == "OpenTypeRadicalConstruction" && it.range.start == 0
                }
                val geometry = result.decisions.first {
                    it.name == "OpenTypeMathRadical" && it.range.start == 0
                }
                val group = result.box.constructionPaintGroups.first {
                    it.kind == MathConstructionPaintKind.Radical && it.sourceRange.start == 0
                }
                check(actual == expected) {
                    "$label preview tier expected $expected but selected $actual for $source"
                }
                val seam = face.radicalSeamGeometry(result.box, group)
                check(seam.edgesAndThicknessMatch) {
                    "$label/$expected outline seam mismatch: $seam"
                }
                println(
                    "preview-radical=$label/$expected " +
                        "top=${seam.topEdgeErrorPx} bottom=${seam.bottomEdgeErrorPx} " +
                        "center=${seam.centerlineErrorPx} thickness=${seam.thicknessErrorPx} " +
                        "overlap=${seam.horizontalOverlapPx} " +
                        "coordinateTolerance=${seam.coordinateAlignmentTolerancePx} " +
                        "thicknessTolerance=${seam.strokeThicknessTolerancePx} " +
                        "evidence=${geometry.details["radicalTopStrokeEvidence"]} " +
                        "anchor=${geometry.details["radicalTopStrokeTopPx"]}.." +
                        "${geometry.details["radicalTopStrokeBottomPx"]}/" +
                        "${geometry.details["radicalTopStrokeRightPx"]}->" +
                        "${geometry.details["ruleLeft"]} logicalAdvance=" +
                        "${geometry.details["radicalBoxAdvancePx"]} " +
                        "clearance=${geometry.details["minimumRadicalGapPx"]}+" +
                        "${geometry.details["constructionExcessPx"]}/2=" +
                        "${geometry.details["actualRadicalGapPx"]} " +
                        "bounds=${geometry.details["radicalGlyphBoundsSources"]} " +
                        "advancePolicy=${geometry.details["radicalLogicalAdvancePolicy"]} " +
                        "clean=${geometry.details["radicandAscentPx"]}+" +
                        "${geometry.details["radicandDescentPx"]} " +
                        "target=${construction.details["targetHeightPx"]} " +
                        "achievedAdvance=${construction.details["achievedAdvancePx"]} " +
                        "B=${geometry.details["unindexedAscentPx"]}+" +
                        "${geometry.details["unindexedDescentPx"]} source=$source",
                )
            }
            listOf(
                Triple("BaseGlyph", "²√x", "\\sqrt[2]{x}"),
                Triple("Variant", "³√fraction", "\\sqrt[3]{\\frac{a}{b}}"),
                Triple("Assembly", "⁵√assembly", RADICAL_ASSEMBLY_INDEXED_SOURCE),
            ).forEach { (expected, caseLabel, source) ->
                val result = MathLayoutEngine(face).layout(
                    source,
                    MathLayoutOptions(MathMode.Display, 32f),
                )
                val construction = result.decisions.first {
                    it.name == "OpenTypeRadicalConstruction" && it.range.start == 0
                }
                val geometry = result.decisions.first {
                    it.name == "OpenTypeMathRadical" && it.range.start == 0
                }
                check(construction.details["construction"] == expected) {
                    "$label indexed preview expected $expected for $source: $construction"
                }
                println(
                    "preview-degree=$label/$caseLabel construction=$expected " +
                        "reference=${geometry.details["degreeRaiseReferencePx"]}/" +
                        "${geometry.details["degreeRaiseReferenceMetric"]} " +
                        "ascent=${geometry.details["degreeRaiseReferenceAscentPx"]} " +
                        "descent=${geometry.details["degreeRaiseReferenceDescentPx"]} " +
                        "percent=${geometry.details["radicalDegreeBottomRaisePercent"]} " +
                        "raise=${geometry.details["degreeRaisePx"]} " +
                        "B=${geometry.details["unindexedBlockSizePx"]} " +
                        "logicalBottom=${geometry.details["degreeLogicalBottomY"]} " +
                        "inkBottom=${geometry.details["degreeInkBottomY"]} " +
                        "horizontal=${geometry.details["radicalKernBeforeDegreePx"]}/" +
                        "${geometry.details["radicalKernAfterDegreePx"]} " +
                        "lower=${geometry.details["radicalDegreeAfterKernClampLowerBoundPx"]} " +
                        "adjustedAfter=${geometry.details["adjustedRadicalKernAfterDegreePx"]} " +
                        "x=${geometry.details["degreeX"]}/${geometry.details["radicalX"]} " +
                        "horizontalPolicy=${geometry.details["degreeHorizontalPlacementPolicy"]} " +
                        "verticalPolicy=${geometry.details["degreePlacementPolicy"]}",
                )
            }
            val inlineOracle = MathLayoutEngine(face).layout(
                "\\sqrt[3]{X}",
                MathLayoutOptions(MathMode.Inline, 32f),
            )
            val inlineGeometry = inlineOracle.decisions.single { it.name == "OpenTypeMathRadical" }
            val degreeGlyph = inlineOracle.box.glyphs.single { it.sourceRange.start == 6 }
            val radicalGlyph = inlineOracle.box.glyphs.single { it.sourceRange.start == 0 }
            val radicalOrigin = inlineGeometry.details.getValue("radicalX").toFloat()
            val topStrokeRight = radicalOrigin +
                inlineGeometry.details.getValue("radicalTopStrokeRightPx").toFloat()
            println(
                "preview-degree-horizontal-oracle=$label mode=Inline size=32 " +
                    "source=\\sqrt[3]{X} degreeInk=${degreeGlyph.inkBounds} " +
                    "degreeAdvance=${inlineGeometry.details["degreeWidthPx"]} " +
                    "radicalOrigin=$radicalOrigin topStrokeRight=$topStrokeRight " +
                    "degreeToRadicalInk=${radicalGlyph.inkBounds.left - degreeGlyph.inkBounds.right}",
            )
        }
    }
}

/** One-device-pixel Skia union crops, enlarged with nearest-neighbor rectangles for review. */
private fun renderRadicalSeamReport() {
    val report = SkiaSurface.makeRasterN32Premul(1380, 790)
    val text = Paint().apply { color = SkiaColor.makeRGB(28, 28, 28) }
    val guide = Paint().apply { color = SkiaColor.makeRGB(205, 45, 45) }
    try {
        report.canvas.clear(SkiaColor.makeRGB(248, 247, 244))
        val fonts = listOf(
            "Lete Sans Math" to LeteSansMath.load(),
            "STIX Two Math" to StixTwoMath.load(),
        )
        val cases = listOf(
            "base" to RADICAL_BASE_SOURCE,
            "variant" to RADICAL_VARIANT_SOURCE,
            "assembly" to RADICAL_ASSEMBLY_SOURCE,
        )
        fonts.flatMap { (fontLabel, font) ->
            cases.map { (kind, source) -> Triple("$fontLabel · $kind", font, source) }
        }.forEachIndexed { index, (label, font, source) ->
            SkiaMathFontFace(font).use { face ->
                val titleFont = face.font(17f)
                val detailFont = face.font(12f)
                try {
                    val result = MathLayoutEngine(face).layout(
                        source,
                        MathLayoutOptions(MathMode.Display, 32f),
                    )
                    val group = result.box.constructionPaintGroups.first {
                        it.kind == MathConstructionPaintKind.Radical && it.sourceRange.start == 0
                    }
                    val seam = face.radicalSeamGeometry(result.box, group)
                    val geometry = result.decisions.first {
                        it.name == "OpenTypeMathRadical" && it.range.start == 0
                    }
                    val outline = (face.constructionOutline(
                        result.box,
                        group,
                    ) as MathConstructionOutlineResult.Available).path
                    val crop = rasterizeSeamCrop(
                        outline,
                        seam.overbarLeftPx,
                        minOf(seam.glyphStroke.topPx, seam.overbar.topPx),
                    )
                    crop.use { bitmap ->
                        val column = index % 2
                        val row = index / 2
                        val panelX = 22f + column * 680f
                        val panelY = 24f + row * 250f
                        report.canvas.drawString(label, panelX, panelY + 18f, titleFont, text)
                        report.canvas.drawString(
                            "top=${seam.topEdgeErrorPx.fmt()} bottom=${seam.bottomEdgeErrorPx.fmt()} " +
                                "center=${seam.centerlineErrorPx.fmt()} thickness=${seam.thicknessErrorPx.fmt()}",
                            panelX,
                            panelY + 38f,
                            detailFont,
                            text,
                        )
                        report.canvas.drawString(
                            "overlap=${seam.horizontalOverlapPx.fmt()} " +
                                "coordTol=${seam.coordinateAlignmentTolerancePx.fmt()} " +
                                "strokeTol=${seam.strokeThicknessTolerancePx.fmt()} · 1px raster ×12",
                            panelX,
                            panelY + 54f,
                            detailFont,
                            text,
                        )
                        report.canvas.drawString(
                            "evidence=${geometry.details["radicalTopStrokeEvidenceSource"]} " +
                                "anchor=${geometry.details.getValue("radicalTopStrokeTopPx").toFloat().fmt()}.." +
                                "${geometry.details.getValue("radicalTopStrokeBottomPx").toFloat().fmt()}/" +
                                "${geometry.details.getValue("radicalTopStrokeRightPx").toFloat().fmt()} -> " +
                                "rule=${geometry.details.getValue("ruleLeft").toFloat().fmt()} " +
                                "logical=${geometry.details.getValue("radicalBoxAdvancePx").toFloat().fmt()}",
                            panelX,
                            panelY + 70f,
                            detailFont,
                            text,
                        )
                        report.canvas.drawString(
                            "bounds=${geometry.details["radicalGlyphBoundsSources"]} · " +
                                "advance=${geometry.details["radicalLogicalAdvancePolicy"]}",
                            panelX,
                            panelY + 86f,
                            detailFont,
                            text,
                        )
                        val imageX = panelX
                        val imageY = panelY + 100f
                        drawNearestNeighbor(report, bitmap, imageX, imageY, 12f)
                        val seamPixelX = (SEAM_CROP_LEFT_OF_SEAM_PX * 12f)
                        report.canvas.drawRect(
                            Rect.makeLTRB(imageX + seamPixelX, imageY - 7f, imageX + seamPixelX + 2f, imageY),
                            guide,
                        )
                    }
                } finally {
                    detailFont.close()
                    titleFont.close()
                }
            }
        }
        val output = File("build/reports/math-radical-seams.png")
        output.parentFile.mkdirs()
        report.makeImageSnapshot().use { image ->
            val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG))
            output.writeBytes(data.bytes)
            data.close()
        }
        println("preview-seams=${output.absolutePath} bytes=${output.length()}")
    } finally {
        guide.close()
        text.close()
        report.close()
    }
}

private fun rasterizeSeamCrop(
    outline: org.jetbrains.skia.Path,
    seamX: Float,
    strokeTop: Float,
): Bitmap {
    val cropLeft = kotlin.math.floor(seamX).toInt() - SEAM_CROP_LEFT_OF_SEAM_PX
    val cropTop = kotlin.math.floor(strokeTop).toInt() - 4
    val surface = SkiaSurface.makeRasterN32Premul(SEAM_CROP_WIDTH_PX, SEAM_CROP_HEIGHT_PX)
    val paint = Paint().apply { color = SkiaColor.BLACK }
    val bitmap = Bitmap().apply { allocN32Pixels(SEAM_CROP_WIDTH_PX, SEAM_CROP_HEIGHT_PX) }
    try {
        surface.canvas.clear(SkiaColor.WHITE)
        val save = surface.canvas.save()
        surface.canvas.translate(-cropLeft.toFloat(), -cropTop.toFloat())
        surface.canvas.drawPath(outline, paint)
        surface.canvas.restoreToCount(save)
        check(surface.readPixels(bitmap, 0, 0))
        return bitmap
    } finally {
        paint.close()
        surface.close()
    }
}

private fun drawNearestNeighbor(
    target: SkiaSurface,
    source: Bitmap,
    left: Float,
    top: Float,
    scale: Float,
) {
    val pixel = Paint()
    try {
        for (y in 0 until source.height) for (x in 0 until source.width) {
            pixel.color = source.getColor(x, y)
            target.canvas.drawRect(
                Rect.makeXYWH(left + x * scale, top + y * scale, scale, scale),
                pixel,
            )
        }
    } finally {
        pixel.close()
    }
}

private fun Float.fmt(): String = "%.4f".format(this)

private const val SEAM_CROP_LEFT_OF_SEAM_PX = 12
private const val SEAM_CROP_WIDTH_PX = 30
private const val SEAM_CROP_HEIGHT_PX = 12
private const val TECTONIC_NULL_DELIMITER_SPACE_PX = 1.2f * 96f / 72.27f
private const val TECTONIC_SCRIPT_SPACE_PX = 0.5f * 96f / 72.27f
private const val TECTONIC_DELIMITER_SHORTFALL_PX = 5f * 96f / 72.27f

private data class DelimiterNoadPreviewCase(
    val label: String,
    val source: String,
    val mode: MathMode = MathMode.Inline,
    val fontSizeSp: Int = 32,
    val lineHeightSp: Int = 58,
)

private val DELIMITER_TALL_CONTENT =
    (1..8).fold("x") { content, _ -> "\\frac{$content}{y}" }

private val DELIMITER_NOAD_PREVIEW_CASES = listOf(
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

private val FRACTION_NOAD_PREVIEW_CASES = listOf(
    "inline fraction" to "\\frac{a}{b}",
    "display fraction" to "\\displaystyle\\frac{a}{b}",
    "inline binomial" to "\\binom{n}{k}",
    "display binomial" to "\\displaystyle\\binom{n}{k}",
    "script binomial" to "\\scriptstyle\\binom{n}{k}",
    "tall binomial" to "\\binom{\\frac{\\frac{a}{b}}{c}}{\\frac{d}{\\frac{e}{f}}}",
    "nested fraction/binomial" to "\\frac{\\binom{n}{k}}{\\binom{2n}{n-k}}",
)

private const val RADICAL_BASE_SOURCE = "\\sqrt[3]{x}"
private const val RADICAL_VARIANT_SOURCE = "\\sqrt{\\frac{a}{b}}"
private val RADICAL_ASSEMBLY_RADICAND =
    (1..12).fold("x") { radicand, _ -> "\\frac{$radicand}{y}" }
private val RADICAL_ASSEMBLY_SOURCE = "\\sqrt{$RADICAL_ASSEMBLY_RADICAND}"
private val RADICAL_ASSEMBLY_INDEXED_SOURCE = "\\sqrt[5]{$RADICAL_ASSEMBLY_RADICAND}"

private data class RadicalVerticalPreviewCase(
    val label: String,
    val source: String,
    val mode: MathMode,
)

private val RADICAL_VERTICAL_PREVIEW_CASES = listOf(
    RadicalVerticalPreviewCase("inline base x / X", "\\sqrt{x}+\\sqrt{X}", MathMode.Inline),
    RadicalVerticalPreviewCase("inline scripts", "\\sqrt{x_j^2}", MathMode.Inline),
    RadicalVerticalPreviewCase("inline fraction", "\\sqrt{\\frac{a}{b}}", MathMode.Inline),
    RadicalVerticalPreviewCase("inline nested", "\\sqrt{1+\\sqrt{x}}", MathMode.Inline),
    RadicalVerticalPreviewCase("inline indexed base", "\\sqrt[3]{X}", MathMode.Inline),
    RadicalVerticalPreviewCase("inline indexed fraction", "\\sqrt[3]{\\frac{a}{b}}", MathMode.Inline),
    RadicalVerticalPreviewCase("display fraction", "\\sqrt{\\frac{a}{b}}", MathMode.Display),
    RadicalVerticalPreviewCase("display assembly", RADICAL_ASSEMBLY_SOURCE, MathMode.Display),
)

private data class OperatorSideScriptPreviewCase(
    val label: String,
    val source: String,
    val mode: MathMode,
    val fontSizeSp: Int = 32,
    val lineHeightSp: Int = 58,
)

private const val OPERATOR_COMPLEX_SOURCE =
    "\\sqrt[3]{\\frac{\\sum\\limits_{i=1}^{n}\\frac{i^2+\\alpha_i}{1+\\beta_i^2}+" +
        "\\int_0^1\\frac{x^2+1}{x^4+1}}{\\sqrt{\\frac{a+b}{c+d}}+\\binom{2n}{n}}}"

private val OPERATOR_SIDE_SCRIPT_PREVIEW_CASES = listOf(
    OperatorSideScriptPreviewCase("inline upper/lower/both", "\\int^1+\\int_0+\\int_0^1", MathMode.Inline),
    OperatorSideScriptPreviewCase("inline int / oint", "\\int_0^1+\\oint_0^1", MathMode.Inline),
    OperatorSideScriptPreviewCase("display int / oint", "\\int_0^1+\\oint_0^1", MathMode.Display),
    OperatorSideScriptPreviewCase("display sum nolimits", "\\sum\\nolimits_0^1", MathMode.Display),
    OperatorSideScriptPreviewCase("complex integral", OPERATOR_COMPLEX_SOURCE, MathMode.Inline, 18, 46),
)
