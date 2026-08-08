package org.tiqian.math.font.opentype

import org.tiqian.math.core.DiagnosticCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.security.MessageDigest

class LeteSansMathTest {
    @Test
    fun bundledFontHasRealMathConstantsAndParenthesisVariants() {
        val font = LeteSansMath.load()

        assertEquals(1000, font.unitsPerEm)
        assertEquals(OpenTypeLineMetrics(750, -250, 0), font.lineMetrics)
        assertEquals(70, font.constants.scriptPercentScaleDown)
        assertEquals(55, font.constants.scriptScriptPercentScaleDown)
        assertEquals(1400, font.constants.displayOperatorMinHeight)
        assertEquals(280, font.constants.axisHeight)
        assertEquals(527, font.constants.accentBaseHeight)
        assertEquals(689, font.constants.flattenedAccentBaseHeight)
        assertEquals(listOf(150, 66, 50), listOf(
            font.constants.overbarVerticalGap, font.constants.overbarRuleThickness, font.constants.overbarExtraAscender,
        ))
        assertEquals(listOf(150, 66, 50), listOf(
            font.constants.underbarVerticalGap, font.constants.underbarRuleThickness, font.constants.underbarExtraDescender,
        ))
        assertEquals(250, font.constants.subscriptShiftDown)
        assertEquals(420, font.constants.superscriptShiftUp)
        assertEquals(370, font.constants.superscriptShiftUpCramped)
        assertEquals(170, font.constants.subSuperscriptGapMin)
        assertEquals(
            listOf(150, 150, 150, 600),
            listOf(
                font.constants.upperLimitGapMin,
                font.constants.upperLimitBaselineRiseMin,
                font.constants.lowerLimitGapMin,
                font.constants.lowerLimitBaselineDropMin,
            ),
        )
        assertEquals(580, font.constants.fractionNumeratorDisplayStyleShiftUp)
        assertEquals(700, font.constants.fractionDenominatorDisplayStyleShiftDown)
        assertEquals(66, font.constants.fractionRuleThickness)
        assertEquals(200, font.constants.fractionNumDisplayStyleGapMin)
        assertEquals(200, font.constants.fractionDenomDisplayStyleGapMin)
        assertEquals(
            listOf(96, 142, 76, 76, 276, -400, 64),
            font.constants.radicalValues(),
        )
        assertTrue(font.verticalVariants.values.any { it.size > 1 })
        assertEquals(828, font.italicCorrections.size)
        assertTrue(font.unsupportedItalicCorrectionAdjustments.isEmpty())
        assertEquals(89, font.verticalVariants.size)
        assertEquals(44, font.horizontalConstructions.size)
        assertEquals(1519, font.topAccentAttachments.size)
        assertTrue(font.extendedShapeGlyphs.isNotEmpty())
        assertTrue(font.mathKernInfo.isNotEmpty())
        assertEquals(
            "ead643895be03f42f6fa201fb1176323f60dd330d4109387bac90bdf980fcf3e",
            MessageDigest.getInstance("SHA-256").digest(LeteSansMath.loadBytes()).toHex(),
        )
    }

    @Test
    fun designUnitScalingIsExact() {
        val font = LeteSansMath.load()
        assertEquals(20f, font.scaleDesignUnits(font.unitsPerEm, 20f))
    }

    @Test
    fun radicalMathValueDeviceAdjustmentIsRejectedExplicitly() {
        val bytes = LeteSansMath.loadBytes().copyOf()
        val constants = bytes.mathConstantsOffset()
        val deviceOffset = constants + 8 + 45 * 4 + 2
        bytes[deviceOffset] = 0
        bytes[deviceOffset + 1] = 1

        val failure = assertFailsWith<OpenTypeMathException> { OpenTypeMathReader().read(bytes) }
        assertEquals(DiagnosticCode.UnsupportedMathDeviceAdjustment, failure.diagnosticCode)
        assertTrue(failure.message.orEmpty().contains("MathConstants[45]"))
    }
}

private fun OpenTypeMathConstants.radicalValues(): List<Int> = listOf(
    radicalVerticalGap,
    radicalDisplayStyleVerticalGap,
    radicalRuleThickness,
    radicalExtraAscender,
    radicalKernBeforeDegree,
    radicalKernAfterDegree,
    radicalDegreeBottomRaisePercent,
)

private fun ByteArray.mathConstantsOffset(): Int {
    val tableCount = u16(4)
    val mathRecord = (0 until tableCount).firstNotNullOf { tableIndex ->
        val record = 12 + tableIndex * 16
        val tag = String(this, record, 4, Charsets.ISO_8859_1)
        if (tag == "MATH") record else null
    }
    val mathOffset = u32(mathRecord + 8)
    return mathOffset + u16(mathOffset + 4)
}

private fun ByteArray.u16(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

private fun ByteArray.u32(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
}
