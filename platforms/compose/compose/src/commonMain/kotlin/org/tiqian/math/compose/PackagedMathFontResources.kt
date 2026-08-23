package org.tiqian.math.compose

import org.tiqian.math.core.MathFaceId
import org.tiqian.math.font.opentype.PackagedMathFontManifestName
import org.tiqian.math.font.opentype.PackagedOpenTypeMathManifestCodec
import org.tiqian.math.font.opentype.PrebakedOpenTypeMathFaceSpec
import org.tiqian.math.font.opentype.PrebakedOpenTypeMathFamilySpec
import org.tiqian.math.font.opentype.packagedMathFontFamilyResourcePath

internal fun loadPackagedMathFontFamilySpec(
    requestedFamilyId: String,
    readResource: (String) -> ByteArray,
): PrebakedOpenTypeMathFamilySpec {
    val basePath = packagedMathFontFamilyResourcePath(requestedFamilyId)
    val manifest = PackagedOpenTypeMathManifestCodec.decode(
        readResource("$basePath/$PackagedMathFontManifestName"),
    )
    require(manifest.familyId == requestedFamilyId) {
        "packaged math font manifest belongs to ${manifest.familyId}, requested $requestedFamilyId"
    }
    return PrebakedOpenTypeMathFamilySpec(
        familyId = manifest.familyId,
        fontClass = manifest.fontClass,
        faces = manifest.faces.map { face ->
            PrebakedOpenTypeMathFaceSpec(
                faceId = MathFaceId("${manifest.familyId}-${face.faceId}"),
                fontBytes = readResource("$basePath/${face.fontFileName}"),
                snapshotBytes = readResource("$basePath/${face.snapshotFileName}"),
                expectedSha256 = face.fontSha256,
                fontClass = manifest.fontClass,
                weight = face.weight,
            )
        },
    )
}
