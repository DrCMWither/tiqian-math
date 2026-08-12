package org.tiqian.math.font.opentype

import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathFontClass
import org.tiqian.math.core.MathFontWeight

const val PackagedMathFontResourceRoot: String = "org/tiqian/math/host-fonts"
const val PackagedMathFontManifestName: String = "manifest.tqfont"

data class PackagedOpenTypeMathFaceManifest(
    val faceId: String,
    val weight: MathFontWeight,
    val fontSha256: String,
    val fontFileName: String,
    val snapshotFileName: String,
)

data class PackagedOpenTypeMathFamilyManifest(
    val familyId: String,
    val fontClass: MathFontClass,
    val faces: List<PackagedOpenTypeMathFaceManifest>,
)

class PrebakedOpenTypeMathFaceSpec(
    val faceId: MathFaceId,
    fontBytes: ByteArray,
    snapshotBytes: ByteArray,
    val expectedSha256: String,
    val fontClass: MathFontClass,
    val weight: MathFontWeight,
) {
    val fontBytes: ByteArray = fontBytes.copyOf()
    val snapshotBytes: ByteArray = snapshotBytes.copyOf()
}
class PrebakedOpenTypeMathFamilySpec(
    val familyId: String,
    val fontClass: MathFontClass,
    val faces: List<PrebakedOpenTypeMathFaceSpec>,
) {
    init {
        requirePackagedMathFontId(familyId, "family id")
        require(faces.isNotEmpty()) { "a prebaked math family needs at least one face" }
        require(faces.map { it.faceId }.toSet().size == faces.size) { "face ids must be unique" }
        require(faces.all { it.fontClass == fontClass }) {
            "font fallback cannot silently cross Serif/SansSerif class"
        }
    }
}

object PackagedOpenTypeMathManifestCodec {
    private const val Header = "TQMF\t1"

    fun encode(manifest: PackagedOpenTypeMathFamilyManifest): ByteArray {
        validate(manifest)
        return buildString {
            appendLine(Header)
            append("family\t")
            append(manifest.familyId)
            append('\t')
            append(manifest.fontClass.serializedName())
            append('\n')
            manifest.faces.sortedBy { it.faceId }.forEach { face ->
                append("face\t")
                append(face.faceId)
                append('\t')
                append(face.weight.cssWeight)
                append('\t')
                append(face.fontSha256)
                append('\t')
                append(face.fontFileName)
                append('\t')
                append(face.snapshotFileName)
                append('\n')
            }
        }.encodeToByteArray()
    }

    fun decode(bytes: ByteArray): PackagedOpenTypeMathFamilyManifest {
        val lines = bytes.decodeToString().lineSequence().filter(String::isNotBlank).toList()
        require(lines.firstOrNull() == Header) { "invalid packaged math font manifest header" }
        val family = lines.getOrNull(1)?.split('\t')
        require(family?.size == 3 && family[0] == "family") { "invalid packaged math font family record" }
        val manifest = PackagedOpenTypeMathFamilyManifest(
            familyId = family[1],
            fontClass = when (family[2]) {
                "serif" -> MathFontClass.Serif
                "sans-serif" -> MathFontClass.SansSerif
                else -> error("invalid packaged math font class ${family[2]}")
            },
            faces = lines.drop(2).map { line ->
                val face = line.split('\t')
                require(face.size == 6 && face[0] == "face") { "invalid packaged math font face record" }
                PackagedOpenTypeMathFaceManifest(
                    faceId = face[1],
                    weight = when (face[2].toIntOrNull()) {
                        400 -> MathFontWeight.Regular
                        700 -> MathFontWeight.Bold
                        else -> error("unsupported packaged math font weight ${face[2]}")
                    },
                    fontSha256 = face[3],
                    fontFileName = face[4],
                    snapshotFileName = face[5],
                )
            },
        )
        validate(manifest)
        return manifest
    }

    private fun validate(manifest: PackagedOpenTypeMathFamilyManifest) {
        requirePackagedMathFontId(manifest.familyId, "family id")
        require(manifest.faces.isNotEmpty()) { "a packaged math family needs at least one face" }
        require(manifest.faces.map { it.faceId }.toSet().size == manifest.faces.size) {
            "packaged math face ids must be unique"
        }
        manifest.faces.forEach { face ->
            requirePackagedMathFontId(face.faceId, "face id")
            require(face.fontSha256.length == 64 && face.fontSha256.all { it in '0'..'9' || it in 'a'..'f' }) {
                "font SHA-256 must be lowercase hexadecimal"
            }
            requireResourceFileName(face.fontFileName, ".otf")
            requireResourceFileName(face.snapshotFileName, ".tqmath")
        }
    }
}

fun packagedMathFontFamilyResourcePath(familyId: String): String {
    requirePackagedMathFontId(familyId, "family id")
    return "$PackagedMathFontResourceRoot/$familyId"
}

fun requirePackagedMathFontId(value: String, label: String) {
    require(value.isNotEmpty() && value.first() in 'a'..'z' && value.all {
        it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
    }) {
        "$label must start with a lowercase ASCII letter and contain only lowercase letters, digits, '-' or '_'"
    }
}

private fun requireResourceFileName(value: String, suffix: String) {
    require(value.endsWith(suffix)) { "packaged math font resource must end with $suffix" }
    requirePackagedMathFontId(value.removeSuffix(suffix), "resource file name")
}

private fun MathFontClass.serializedName(): String = when (this) {
    MathFontClass.Serif -> "serif"
    MathFontClass.SansSerif -> "sans-serif"
}
