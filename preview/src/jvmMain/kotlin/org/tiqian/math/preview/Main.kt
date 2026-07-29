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
import org.jetbrains.skia.EncodedImageFormat
import org.tiqian.math.compose.TiqianMath
import org.tiqian.math.core.MathMode
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.skia.SkiaMathFontFace
import org.tiqian.math.font.stix.StixTwoMath
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
                Text("Script-style binomial coverage · real base glyph variants", fontSize = 13.sp)
                ScriptBinomialSample("Lete Sans Math", lete)
                ScriptBinomialSample("STIX Two Math", stix)
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
private fun RadicalSample(label: String, face: SkiaMathFontFace) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF55504A))
        TiqianMath(
            source = "\\sqrt[3]{x^2+1}+\\sqrt{\\frac{a+b}{\\sqrt{x}}}+" +
                "\\sqrt{\\frac{\\frac{\\frac{a}{b}}{c}}{d}}",
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
    ImageComposeScene(width = 900, height = 2400) { PreviewScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/math-preview.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("preview=${output.absolutePath} bytes=${output.length()}")
    }
}
