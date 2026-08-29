package org.tiqian.math.font.skia

import kotlin.test.Test
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathMode
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/**
 * Corpus regression over the Zhihu fancy-usage answers (「回答字体怎样变大?」): every formula must
 * lay out clean except the adjudicated remainder — #14 needs `\textsf`, which requires a text
 * font-family request the host text contract does not carry yet.
 */
class FancyUsageProbeTest {
    @Test
    fun tabulateDiagnostics() {
        val failing = mutableMapOf<Int, String>()
        val corpus = checkNotNull(javaClass.classLoader.getResource("zhihu-fancy-usage-corpus.txt"))
            .readText().trim().lines()
        SkiaMathFontFamily.loadBundledLete().use { math ->
            TestHostTextProvider(
                SkiaMathFontFace(
                    org.tiqian.math.font.opentype.LeteSansMath.load(),
                    MathFaceId("fancy-host"),
                ),
            ).use { provider ->
                corpus.forEachIndexed { index, source ->
                    val result = MathLayoutEngine(math, textRunProvider = provider).layout(
                        source,
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = 48f,
                            displayWidthPx = 1248f,
                            textLocale = "zh-Hans",
                            softWrapDisplay = true,
                        ),
                    )
                    if (result.diagnostics.isNotEmpty()) {
                        failing[index] = result.diagnostics.joinToString("; ") { "${it.code}:${it.message.take(60)}" }
                    }
                }
            }
        }
        kotlin.test.assertEquals(setOf(14), failing.keys, "fancy-usage support drifted: $failing")
    }
}
