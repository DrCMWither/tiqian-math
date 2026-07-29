package org.tiqian.math.font.stix

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.font.opentype.OpenTypeLineMetrics
import org.tiqian.math.font.opentype.OpenTypeMathException
import org.tiqian.math.core.DiagnosticCode
import kotlin.test.assertFailsWith

class StixTwoMathTest {
    @Test
    fun bundledComparisonFontHasRealMathData() {
        val font = StixTwoMath.load()

        assertEquals(1000, font.unitsPerEm)
        assertEquals(OpenTypeLineMetrics(762, -238, 250), font.lineMetrics)
        assertEquals(70, font.constants.scriptPercentScaleDown)
        assertEquals(55, font.constants.scriptScriptPercentScaleDown)
        assertEquals(258, font.constants.axisHeight)
        assertEquals(210, font.constants.subscriptShiftDown)
        assertEquals(360, font.constants.superscriptShiftUp)
        assertEquals(252, font.constants.superscriptShiftUpCramped)
        assertEquals(150, font.constants.subSuperscriptGapMin)
        assertEquals(640, font.constants.fractionNumeratorDisplayStyleShiftUp)
        assertEquals(640, font.constants.fractionDenominatorDisplayStyleShiftDown)
        assertEquals(68, font.constants.fractionRuleThickness)
        assertEquals(150, font.constants.fractionNumDisplayStyleGapMin)
        assertEquals(150, font.constants.fractionDenomDisplayStyleGapMin)
        assertTrue(font.verticalVariants.values.any { it.size > 1 })
        assertEquals(889, font.italicCorrections.size)
        assertEquals(setOf(4010.toUShort()), font.unsupportedItalicCorrectionAdjustments)
        val unsupported = assertFailsWith<OpenTypeMathException> {
            font.italicCorrection(4010.toUShort(), 40f)
        }
        assertEquals(DiagnosticCode.UnsupportedMathDeviceAdjustment, unsupported.diagnosticCode)
        assertEquals(118, font.verticalVariants.size)
        assertTrue(font.extendedShapeGlyphs.isNotEmpty())
        assertTrue(font.mathKernInfo.isNotEmpty())
        assertEquals(
            "95bc2729e41faf93b0bcae9e96c4dc4da45855067fd0581e621e30734fe8d90b",
            MessageDigest.getInstance("SHA-256").digest(StixTwoMath.loadBytes()).toHex(),
        )
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
}
