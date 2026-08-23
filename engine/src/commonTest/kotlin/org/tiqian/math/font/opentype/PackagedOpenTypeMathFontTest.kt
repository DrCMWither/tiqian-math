package org.tiqian.math.font.opentype

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.tiqian.math.core.MathFontClass
import org.tiqian.math.core.MathFontWeight

class PackagedOpenTypeMathFontTest {
    @Test
    fun manifestRoundTripsWithoutHostPaths() {
        val original = PackagedOpenTypeMathFamilyManifest(
            familyId = "stix",
            fontClass = MathFontClass.Serif,
            faces = listOf(PackagedOpenTypeMathFaceManifest(
                faceId = "regular",
                weight = MathFontWeight.Regular,
                fontSha256 = "a".repeat(64),
                fontFileName = "regular.otf",
                snapshotFileName = "regular.tqmath",
            )),
        )

        assertEquals(original, PackagedOpenTypeMathManifestCodec.decode(
            PackagedOpenTypeMathManifestCodec.encode(original),
        ))
    }

    @Test
    fun manifestRejectsResourceTraversal() {
        assertFailsWith<IllegalArgumentException> {
            packagedMathFontFamilyResourcePath("../stix")
        }
    }
}
