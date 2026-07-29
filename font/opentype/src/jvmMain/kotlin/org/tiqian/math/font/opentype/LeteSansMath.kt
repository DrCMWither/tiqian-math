package org.tiqian.math.font.opentype

object LeteSansMath {
    const val ResourcePath: String = "/org/tiqian/math/fonts/LeteSansMath-Regular.otf"

    fun loadBytes(): ByteArray = checkNotNull(LeteSansMath::class.java.getResourceAsStream(ResourcePath)) {
        "Bundled Lete Sans Math resource is missing at $ResourcePath"
    }.use { it.readBytes() }

    fun load(): OpenTypeMathFont = OpenTypeMathReader().read(loadBytes())
}
