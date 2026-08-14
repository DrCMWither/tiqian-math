package org.tiqian.math.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import org.tiqian.math.core.MathFontClass
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.opentype.PackagedMathFontManifestName
import org.tiqian.math.font.opentype.PackagedOpenTypeMathFaceManifest
import org.tiqian.math.font.opentype.PackagedOpenTypeMathFamilyManifest
import org.tiqian.math.font.opentype.PackagedOpenTypeMathManifestCodec
import org.tiqian.math.font.opentype.VerifiedOpenTypeMathSnapshotLoader
import org.tiqian.math.font.opentype.packagedMathFontFamilyResourcePath
import org.tiqian.math.font.skia.SkiaMathFontFamily

class PackagedMathFontResourcesTest {
    @Test
    fun restoresVerifiedFamilyFromHostResourceBundle() {
        val base = packagedMathFontFamilyResourcePath("sample")
        val snapshot = checkNotNull(javaClass.getResourceAsStream(LeteSansMath.SnapshotResourcePath))
            .use { it.readBytes() }
        val manifest = PackagedOpenTypeMathManifestCodec.encode(
            PackagedOpenTypeMathFamilyManifest(
                familyId = "sample",
                fontClass = MathFontClass.SansSerif,
                faces = listOf(PackagedOpenTypeMathFaceManifest(
                    faceId = "regular",
                    weight = MathFontWeight.Regular,
                    fontSha256 = VerifiedOpenTypeMathSnapshotLoader.prepare(snapshot).fontSha256,
                    fontFileName = "regular.otf",
                    snapshotFileName = "regular.tqmath",
                )),
            ),
        )
        val resources = mapOf(
            "$base/$PackagedMathFontManifestName" to manifest,
            "$base/regular.otf" to LeteSansMath.loadBytes(),
            "$base/regular.tqmath" to snapshot,
        )

        val spec = loadPackagedMathFontFamilySpec("sample") { path ->
            checkNotNull(resources[path]) { "missing fixture resource $path" }
        }
        SkiaMathFontFamily.fromPrebakedSpec(spec).use { family ->
            assertEquals("sample-regular", family.faceId.value)
            assertEquals(MathFontClass.SansSerif, family.fontClass)
            assertEquals(280, family.mathFont.constants.axisHeight)
        }
    }
}
