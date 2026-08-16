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

internal fun loadPreviewHostTextProvider(): PreviewHostTextRunProvider {
    fun font(env: String, vararg defaults: String): File =
        listOfNotNull(System.getenv(env)?.let(::File), *defaults.map(::File).toTypedArray())
            .firstOrNull(File::isFile)
            ?: error("Preview host text requires $env or one of ${defaults.joinToString()}")

    val regular = font(
        "MATH_COMPOSE_PREVIEW_TEXT_FONT_REGULAR",
        "/System/Library/Fonts/STHeiti Light.ttc",
        "/System/Library/Fonts/Hiragino Sans GB.ttc",
    )
    val bold = font(
        "MATH_COMPOSE_PREVIEW_TEXT_FONT_BOLD",
        "/System/Library/Fonts/STHeiti Medium.ttc",
        regular.absolutePath,
    )
    val extension = font(
        "MATH_COMPOSE_PREVIEW_TEXT_FONT_EXTENSION",
        "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
        "/System/Library/Fonts/Hiragino Sans GB.ttc",
    )
    return PreviewHostTextRunProvider(regular, bold, extension)
}

internal class PreviewHostTextRunProvider(
    regularFile: File,
    boldFile: File,
    extensionFile: File,
) : MathTextRunProvider, SkiaReplayCatalog, AutoCloseable {
    private data class Owned(
        val provider: SkiaMathTextRunProvider,
        val source: File,
        val selectionReason: String,
    )

    private val regular = Owned(
        SkiaMathTextRunProvider.fromBytes(MathFaceId("preview-host-regular-primary"), regularFile.readBytes(), MathFontWeight.Regular),
        regularFile,
        "PreviewPrimaryCoverage",
    )
    private val bold = Owned(
        SkiaMathTextRunProvider.fromBytes(MathFaceId("preview-host-bold-primary"), boldFile.readBytes(), MathFontWeight.Bold),
        boldFile,
        "PreviewRequestedBoldOrNearest",
    )
    private val regularExtension = Owned(
        SkiaMathTextRunProvider.fromBytes(MathFaceId("preview-host-regular-extension"), extensionFile.readBytes(), MathFontWeight.Regular),
        extensionFile,
        "PreviewExtensionCoverage",
    )
    private val boldExtension = Owned(
        SkiaMathTextRunProvider.fromBytes(MathFaceId("preview-host-bold-extension"), extensionFile.readBytes(), MathFontWeight.Regular),
        extensionFile,
        "PreviewExtensionCoverageWeightFallback",
    )
    private val all = listOf(regular, bold, regularExtension, boldExtension)
    val description: String = buildString {
        append("Regular=").append(regular.source.absolutePath)
        append(" · Bold/nearest=").append(bold.source.absolutePath)
        append(" · Extension=").append(regularExtension.source.absolutePath)
    }
    fun auditLabel(requested: MathFontWeight): String {
        val primary = if (requested == MathFontWeight.Bold) bold else regular
        val extension = if (requested == MathFontWeight.Bold) boldExtension else regularExtension
        return "requested=$requested · primary=${primary.provider.faceId} resolved=${if (requested == MathFontWeight.Bold) MathFontWeight.Bold else MathFontWeight.Regular} reason=${primary.selectionReason} · " +
            "extension=${extension.provider.faceId} resolved=${MathFontWeight.Regular} reason=${extension.selectionReason}"
    }

    override fun shapeTextAtom(request: MathTextRunRequest): MathTextRunProviderResult {
        var inputOffset = 0
        var x = 0f
        var ascent = 0f
        var descent = 0f
        var missing = false
        val glyphs = mutableListOf<org.tiqian.math.layout.MeasuredMathGlyph>()
        while (inputOffset < request.text.length) {
            val scalar = Character.codePointAt(request.text, inputOffset)
            val count = Character.charCount(scalar)
            val extension = scalar in 0x30A0..0x30FF || scalar in 0xAC00..0xD7AF ||
                (scalar in 0x4E00..0x9FFF && inputOffset % 2 == 1)
            val owned = when {
                request.requestedWeight == MathFontWeight.Bold && extension -> boldExtension
                request.requestedWeight == MathFontWeight.Bold -> bold
                extension -> regularExtension
                else -> regular
            }
            val childRequest = request.copy(
                text = request.text.substring(inputOffset, inputOffset + count),
                sourceRange = SourceRange(
                    request.sourceRange.start + inputOffset,
                    request.sourceRange.start + inputOffset + count,
                ),
            )
            when (val shaped = owned.provider.shapeTextAtom(childRequest)) {
                is MathTextRunProviderResult.CapabilityIssue -> return shaped
                is MathTextRunProviderResult.ReadyBox -> return shaped
                is MathTextRunProviderResult.Ready -> {
                    val run = shaped.run
                    run.glyphs.forEach { glyph ->
                        val localStart = inputOffset + glyph.textCluster
                        val localEnd = inputOffset + count
                        glyphs += glyph.copy(
                            x = x + glyph.x,
                            textCluster = localStart,
                            hostTextDecision = glyph.hostTextDecision?.copy(
                                sourceRange = SourceRange(
                                    request.sourceRange.start + localStart,
                                    request.sourceRange.start + localEnd,
                                ),
                                clusterRangeUtf16 = SourceRange(localStart, localEnd),
                                hostRole = request.origin.name,
                                fontKey = owned.source.absolutePath,
                                selectionReason = owned.selectionReason,
                                substitutionReason = when {
                                    extension && request.requestedWeight == MathFontWeight.Bold ->
                                        "ExtensionFaceAndRequestedWeightFallback"
                                    extension -> "PrimaryFaceMissingPreviewCluster"
                                    request.requestedWeight != glyph.resolvedWeight -> "RequestedWeightUnavailable"
                                    else -> null
                                },
                            ),
                        )
                    }
                    x += run.width
                    ascent = maxOf(ascent, run.ascent)
                    descent = maxOf(descent, run.descent)
                    missing = missing || run.missingGlyph
                }
            }
            inputOffset += count
        }
        return MathTextRunProviderResult.Ready(MeasuredMathRun(
            glyphs = glyphs,
            width = x,
            ascent = ascent,
            descent = descent,
            missingGlyph = missing,
            boundsSource = org.tiqian.math.layout.MathGlyphBoundsSource.Outline,
        ))
    }

    override fun replayFace(faceId: MathFaceId): SkiaReplayFace? =
        all.firstNotNullOfOrNull { it.provider.replayFace(faceId) }
    override fun constructionFace(faceId: MathFaceId): SkiaMathFontFace? = null
    override fun close() = all.forEach { it.provider.close() }
}
