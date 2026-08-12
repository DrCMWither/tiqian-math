package org.tiqian.math.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import org.tiqian.math.font.android.AndroidMathFontFamily
import org.tiqian.math.font.android.AndroidReplayCatalog
import org.tiqian.math.font.android.AndroidMathRenderer
import org.tiqian.math.font.android.androidFormulaCapabilityEngine
import org.tiqian.math.font.android.combineAndroidReplayCatalogs
import org.tiqian.math.core.MathFontFamilySpec
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathFontClass
import org.tiqian.math.core.MathFontFaceSpec
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathTextRunProvider

@Composable
internal actual fun rememberPlatformLeteMathFontFace(): MathComposeFontFace {
    val context = LocalContext.current.applicationContext
    val face = remember(context) { AndroidMathFontFamily.loadBundledLete(context) }
    DisposableEffect(face) { onDispose(face::close) }
    return face
}

@Composable
internal actual fun rememberPlatformPackagedMathFontFamily(familyId: String): MathComposeFontFace {
    val context = LocalContext.current.applicationContext
    val face = remember(context, familyId) {
        AndroidMathFontFamily.fromPrebakedSpec(
            loadPackagedMathFontFamilySpec(familyId) { path ->
                runCatching { context.assets.open(path) }
                    .getOrElse {
                        checkNotNull(context.classLoader.getResourceAsStream(path)) {
                            "Packaged math font resource is missing at $path; " +
                                "apply org.tiqian.math.fonts to the host"
                        }
                    }.use { it.readBytes() }
            },
        )
    }
    DisposableEffect(face) { onDispose(face::close) }
    return face
}

@Composable
internal actual fun rememberPlatformMathFontFamily(spec: MathFontFamilySpec): MathComposeFontFace {
    val face = remember(spec) { AndroidMathFontFamily.fromSpec(spec) }
    DisposableEffect(face) { onDispose(face::close) }
    return face
}

@Composable
internal actual fun rememberPlatformMathFontFace(fontBytes: ByteArray): MathComposeFontFace {
    val face = remember(fontBytes) {
        AndroidMathFontFamily.fromSpec(
            MathFontFamilySpec(
                familyId = "legacy-single-math-face",
                fontClass = MathFontClass.Serif,
                faces = listOf(MathFontFaceSpec(
                    MathFaceId.LegacySingleFace,
                    fontBytes,
                    MathFontClass.Serif,
                    MathFontWeight.Regular,
                )),
            ),
        )
    }
    DisposableEffect(face) { onDispose(face::close) }
    return face
}

internal actual fun platformFormulaCapabilityEngine(
    face: MathComposeFontFace,
    textRunProvider: MathTextRunProvider?,
): MathFormulaCapabilityEngine = face.androidFormulaCapabilityEngine(textRunProvider)

internal actual fun DrawScope.drawPlatformMathPlan(
    face: MathComposeFontFace,
    textRunProvider: MathTextRunProvider?,
    plan: RenderPlan,
    color: Color,
) {
    val mathCatalog = face as? AndroidReplayCatalog ?: error("Android Compose requires a replayable face catalog")
    val textCatalog = textRunProvider as? AndroidReplayCatalog
    val androidFaces = combineAndroidReplayCatalogs(mathCatalog, textCatalog)
    val renderer = AndroidMathRenderer(androidFaces)
    drawIntoCanvas { canvas ->
        plan.boxes.forEach { positioned ->
            renderer.drawBox(
                canvas.nativeCanvas,
                positioned.box,
                positioned.x,
                positioned.baselineFromTop,
                color.toArgb(),
                drawHostText = {
                    canvas.drawComposeMathTextRuns(
                        textRunProvider,
                        listOf(positioned),
                        color,
                    )
                },
            )
        }
    }
}
