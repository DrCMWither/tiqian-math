package org.tiqian.math.font.stix

import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.opentype.OpenTypeMathReader

/** Optional comparison/CI font. It is not a dependency of the default Compose artifact. */
object StixTwoMath {
    const val ResourcePath: String = "/org/tiqian/math/fonts/STIXTwoMath-Regular.otf"

    fun loadBytes(): ByteArray = checkNotNull(StixTwoMath::class.java.getResourceAsStream(ResourcePath)) {
        "STIX Two Math resource is missing at $ResourcePath"
    }.use { it.readBytes() }

    fun load(): OpenTypeMathFont = OpenTypeMathReader().read(loadBytes())
}
