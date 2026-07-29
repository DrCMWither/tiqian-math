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
    ImageComposeScene(width = 900, height = 2400) { PreviewScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/math-preview.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("preview=${output.absolutePath} bytes=${output.length()}")
    }
    renderRadicalSeamReport()
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
                        "achievedAdvance=${construction.details["achievedAdvancePx"]} source=$source",
                )
            }
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

private const val RADICAL_BASE_SOURCE = "\\sqrt[3]{x}"
private const val RADICAL_VARIANT_SOURCE = "\\sqrt{\\frac{a}{b}}"
private val RADICAL_ASSEMBLY_SOURCE = "\\sqrt{" +
    (1..12).fold("x") { radicand, _ -> "\\frac{$radicand}{y}" } +
    "}"
