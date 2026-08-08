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
        assertEquals(1800, font.constants.displayOperatorMinHeight)
        assertEquals(258, font.constants.axisHeight)
        assertEquals(480, font.constants.accentBaseHeight)
        assertEquals(656, font.constants.flattenedAccentBaseHeight)
        assertEquals(listOf(175, 68, 68), listOf(
            font.constants.overbarVerticalGap, font.constants.overbarRuleThickness, font.constants.overbarExtraAscender,
        ))
        assertEquals(listOf(175, 68, 68), listOf(
            font.constants.underbarVerticalGap, font.constants.underbarRuleThickness, font.constants.underbarExtraDescender,
        ))
        assertEquals(210, font.constants.subscriptShiftDown)
        assertEquals(360, font.constants.superscriptShiftUp)
        assertEquals(252, font.constants.superscriptShiftUpCramped)
        assertEquals(150, font.constants.subSuperscriptGapMin)
        assertEquals(
            listOf(135, 300, 135, 670),
            listOf(
                font.constants.upperLimitGapMin,
                font.constants.upperLimitBaselineRiseMin,
                font.constants.lowerLimitGapMin,
                font.constants.lowerLimitBaselineDropMin,
            ),
        )
        assertEquals(640, font.constants.fractionNumeratorDisplayStyleShiftUp)
        assertEquals(640, font.constants.fractionDenominatorDisplayStyleShiftDown)
        assertEquals(68, font.constants.fractionRuleThickness)
        assertEquals(150, font.constants.fractionNumDisplayStyleGapMin)
        assertEquals(150, font.constants.fractionDenomDisplayStyleGapMin)
        assertEquals(
            listOf(85, 170, 68, 78, 65, -335, 55),
            listOf(
                font.constants.radicalVerticalGap,
                font.constants.radicalDisplayStyleVerticalGap,
                font.constants.radicalRuleThickness,
                font.constants.radicalExtraAscender,
                font.constants.radicalKernBeforeDegree,
                font.constants.radicalKernAfterDegree,
                font.constants.radicalDegreeBottomRaisePercent,
            ),
        )
        assertTrue(font.verticalVariants.values.any { it.size > 1 })
        assertEquals(889, font.italicCorrections.size)
        assertEquals(setOf(4010.toUShort()), font.unsupportedItalicCorrectionAdjustments)
        val unsupported = assertFailsWith<OpenTypeMathException> {
            font.italicCorrection(4010.toUShort(), 40f)
        }
        assertEquals(DiagnosticCode.UnsupportedMathDeviceAdjustment, unsupported.diagnosticCode)
        assertEquals(118, font.verticalVariants.size)
        assertEquals(47, font.horizontalConstructions.size)
        assertEquals(2652, font.topAccentAttachments.size)
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
