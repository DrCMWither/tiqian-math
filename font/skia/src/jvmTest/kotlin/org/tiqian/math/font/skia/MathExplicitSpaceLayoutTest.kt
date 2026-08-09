package org.tiqian.math.font.skia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.MathAtomClass
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

class MathExplicitSpaceLayoutTest {
    @Test
    fun explicitGlueDoesNotBecomeANoadOrPreventLeadingBinRepair() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val result = MathLayoutEngine(face).layout("\\quad+x", MathLayoutOptions(fontSizePx = 36f))
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            val repair = result.decisions.single { it.name == "TeXBinaryAtomReclassification" }
            assertEquals("Binary", repair.details["from"])
            assertEquals("Ordinary", repair.details["to"])
            assertEquals(MathAtomClass.Ordinary, result.fragments[1].atomClass)
            assertTrue(result.decisions.single { it.name == "TeXExplicitMathSpace" }
                .details["advancePx"]!!.toFloat() > 0f)
        }
    }

    @Test
    fun explicitGlueBetweenOrdAndBinPreservesTheRealNoadAdjacency() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val result = MathLayoutEngine(face).layout("a\\quad+b", MathLayoutOptions(fontSizePx = 36f))
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            assertEquals(MathAtomClass.Binary, result.fragments[2].atomClass)
            assertTrue(result.fragments[2].trailingGlue.naturalPx > 0f)
        }
    }
}
