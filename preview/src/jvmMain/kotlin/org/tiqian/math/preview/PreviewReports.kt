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

@OptIn(ExperimentalComposeUiApi::class)
internal fun renderSnapshot() {
    auditRadicalPreviewTiers()
    ImageComposeScene(width = 900, height = 5650) { PreviewScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/math-preview.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("preview=${output.absolutePath} bytes=${output.length()}")
    }
    ImageComposeScene(width = 1200, height = 1180) { ExtendedStructureOracleScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/tiqian-text-accent-decoration.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("text-accent-decoration=${output.absolutePath} bytes=${output.length()}")
    }
    ImageComposeScene(width = 1400, height = 980) { FontFamilyFallbackScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/tiqian-font-family-fallback.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("font-family-fallback=${output.absolutePath} bytes=${output.length()}")
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
    ImageComposeScene(width = 1400, height = 2300) { DelimiterNoadOracleScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/tiqian-delimiter-noad.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("delimiter-noad-oracle=${output.absolutePath} bytes=${output.length()}")
    }
    ImageComposeScene(width = 1400, height = 1900) { TableEnvironmentOracleScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/tiqian-table-environments.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("table-environment-oracle=${output.absolutePath} bytes=${output.length()}")
    }
    ImageComposeScene(width = 1400, height = 2200) { CommonExtensionsOracleScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/tiqian-common-extensions.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("common-extensions-oracle=${output.absolutePath} bytes=${output.length()}")
    }
    ImageComposeScene(width = 1400, height = 1420) { ColorBoxOracleScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/tiqian-color-box.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("color-box-oracle=${output.absolutePath} bytes=${output.length()}")
    }
    ImageComposeScene(width = 1000, height = 3200) { EquationTagOracleScreen() }.use { scene ->
        val data = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val output = File("build/reports/tiqian-equation-tags.png")
        output.parentFile.mkdirs()
        output.writeBytes(data.bytes)
        println("equation-tag-oracle=${output.absolutePath} bytes=${output.length()}")
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
internal const val TECTONIC_NULL_DELIMITER_SPACE_PX = 1.2f * 96f / 72.27f
internal const val TECTONIC_SCRIPT_SPACE_PX = 0.5f * 96f / 72.27f
internal const val TECTONIC_DELIMITER_SHORTFALL_PX = 5f * 96f / 72.27f
