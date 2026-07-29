package org.tiqian.math.font.opentype

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MathAssemblyAndKernTest {
    @Test
    fun mathKernIntervalsUseTheOpenTypeCorrectionHeightBoundaries() {
        val table = MathKernTable(listOf(-100, 300), listOf(11, 22, 33))
        assertEquals(11, table.valueAt(-101f))
        assertEquals(22, table.valueAt(-100f))
        assertEquals(22, table.valueAt(299.9f))
        assertEquals(33, table.valueAt(300f))
    }

    @Test
    fun assemblyStartsWithoutExtendersThenAddsEqualGrowthRounds() {
        val font = LeteSansMath.load().copy(
            verticalConstructions = mapOf(TEST_GLYPH to construction(withExtender = true)),
        )
        val noExtender = font.verticalConstruction(TEST_GLYPH, 170f, 1000f)!!
        assertEquals(0, noExtender.extenderRepetitions)
        assertEquals(2, noExtender.components.size)
        assertNear(170f, noExtender.advanceMeasurement)

        val oneRound = font.verticalConstruction(TEST_GLYPH, 220f, 1000f)!!
        assertEquals(1, oneRound.extenderRepetitions)
        assertEquals(3, oneRound.components.size)
        assertEquals(73, oneRound.assemblyItalicCorrection)
        assertEqualOverlaps(oneRound.connectorOverlaps)
        assertNear(220f, oneRound.advanceMeasurement)

        val multipleRounds = font.verticalConstruction(TEST_GLYPH, 260f, 1000f)!!
        assertEquals(2, multipleRounds.extenderRepetitions)
        assertEquals(4, multipleRounds.components.size)
        assertEqualOverlaps(multipleRounds.connectorOverlaps)
        assertNear(260f, multipleRounds.advanceMeasurement)
    }

    @Test
    fun assemblyWithoutExtendersReportsWhetherItsFiniteCoverageIsEnough() {
        val font = LeteSansMath.load().copy(
            verticalConstructions = mapOf(TEST_GLYPH to construction(withExtender = false)),
        )
        assertTrue(font.verticalConstruction(TEST_GLYPH, 170f, 1000f)!!.reachesTarget)
        assertFalse(font.verticalConstruction(TEST_GLYPH, 250f, 1000f)!!.reachesTarget)
    }

    private fun construction(withExtender: Boolean): MathGlyphConstruction = MathGlyphConstruction(
        variants = emptyList(),
        assembly = MathGlyphAssembly(
            parts = buildList {
                add(MathGlyphAssemblyPart(10u, 0, 40, 100, false))
                if (withExtender) add(MathGlyphAssemblyPart(11u, 30, 30, 60, true))
                add(MathGlyphAssemblyPart(12u, 40, 0, 100, false))
            },
            minimumConnectorOverlap = 10,
            italicCorrection = 73,
        ),
    )

    private fun assertEqualOverlaps(overlaps: List<Float>) {
        assertTrue(overlaps.size >= 2)
        assertTrue(overlaps.max() - overlaps.min() <= 0.01f, "overlaps must grow symmetrically: $overlaps")
    }

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) <= 0.02f, "expected $expected, got $actual")
    }

    private companion object {
        val TEST_GLYPH: UShort = 1u
    }
}
