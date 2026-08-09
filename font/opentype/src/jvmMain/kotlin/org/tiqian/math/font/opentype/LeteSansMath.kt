package org.tiqian.math.font.opentype

object LeteSansMath {
    const val ResourcePath: String = "/org/tiqian/math/fonts/LeteSansMath-Regular.otf"
    const val BoldResourcePath: String = "/org/tiqian/math/fonts/LeteSansMath-Bold.otf"

    fun loadBytes(): ByteArray = checkNotNull(LeteSansMath::class.java.getResourceAsStream(ResourcePath)) {
        "Bundled Lete Sans Math resource is missing at $ResourcePath"
    }.use { it.readBytes() }

    fun load(): OpenTypeMathFont = OpenTypeMathReader().read(loadBytes())

    fun loadBoldBytes(): ByteArray = checkNotNull(LeteSansMath::class.java.getResourceAsStream(BoldResourcePath)) {
        "Bundled Lete Sans Math Bold resource is missing at $BoldResourcePath"
    }.use { it.readBytes() }

    fun loadBold(): OpenTypeMathFont = OpenTypeMathReader().read(loadBoldBytes())
}
