package org.tiqian.math.font.stix

import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.opentype.PackagedOpenTypeMathManifestCodec
import org.tiqian.math.font.opentype.VerifiedOpenTypeMathSnapshotLoader

/** Optional prebaked STIX Two Math family. It is not a dependency of the default Compose artifact. */
object StixTwoMath {
    const val FamilyId: String = "stix"
    const val SourceSha256: String = "95bc2729e41faf93b0bcae9e96c4dc4da45855067fd0581e621e30734fe8d90b"
    const val ResourcePath: String = "/org/tiqian/math/host-fonts/stix/regular.otf"
    const val SnapshotResourcePath: String = "/org/tiqian/math/host-fonts/stix/regular.tqmath"
    const val ManifestResourcePath: String = "/org/tiqian/math/host-fonts/stix/manifest.tqfont"

    fun loadBytes(): ByteArray = checkNotNull(StixTwoMath::class.java.getResourceAsStream(ResourcePath)) {
        "STIX Two Math resource is missing at $ResourcePath"
    }.use { it.readBytes() }

    fun load(): OpenTypeMathFont {
        val manifest = readResource(ManifestResourcePath).let(PackagedOpenTypeMathManifestCodec::decode)
        val face = manifest.faces.single()
        check(manifest.familyId == FamilyId) { "Bundled STIX manifest has family id ${manifest.familyId}" }
        return VerifiedOpenTypeMathSnapshotLoader.load(
            loadBytes(),
            readResource(SnapshotResourcePath),
            face.fontSha256,
        )
    }

    private fun readResource(path: String): ByteArray =
        checkNotNull(StixTwoMath::class.java.getResourceAsStream(path)) {
            "STIX Two Math resource is missing at $path"
        }.use { it.readBytes() }
}
