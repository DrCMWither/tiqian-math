package org.tiqian.math.font.opentype

object LeteSansMath {
    const val ResourcePath: String = "/org/tiqian/math/fonts/${LeteSansMathPrebakedData.RegularFileStem}.otf"
    const val BoldResourcePath: String = "/org/tiqian/math/fonts/${LeteSansMathPrebakedData.BoldFileStem}.otf"
    const val SnapshotResourcePath: String = "/org/tiqian/math/fonts/${LeteSansMathPrebakedData.RegularFileStem}.tqmath"
    const val BoldSnapshotResourcePath: String = "/org/tiqian/math/fonts/${LeteSansMathPrebakedData.BoldFileStem}.tqmath"
    private val regularSnapshot: VerifiedOpenTypeMathSnapshot by lazy {
        VerifiedOpenTypeMathSnapshotLoader.prepare(readResource(SnapshotResourcePath))
    }
    private val boldSnapshot: VerifiedOpenTypeMathSnapshot by lazy {
        VerifiedOpenTypeMathSnapshotLoader.prepare(readResource(BoldSnapshotResourcePath))
    }

    fun loadBytes(): ByteArray = readResource(ResourcePath)

    /** The returned font owns its bytes while sharing immutable, detached prebaked metadata. */
    fun load(): OpenTypeMathFont = regularSnapshot.attach(loadBytes())

    fun loadBoldBytes(): ByteArray = readResource(BoldResourcePath)

    fun loadBold(): OpenTypeMathFont = boldSnapshot.attach(loadBoldBytes())

    private fun readResource(path: String): ByteArray =
        checkNotNull(LeteSansMath::class.java.getResourceAsStream(path)) {
            "Bundled Lete Sans Math resource is missing at $path"
        }.use { it.readBytes() }

}
