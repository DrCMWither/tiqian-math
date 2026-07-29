package org.tiqian.math.font.opentype

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun assemblyWithoutExtendersIsInvalidAndCannotBeSelected() {
        val font = LeteSansMath.load().copy(
            verticalConstructions = mapOf(TEST_GLYPH to construction(withExtender = false)),
        )
        val validation = assertNotNull(font.verticalAssemblyValidation(TEST_GLYPH))
        assertFalse(validation.valid)
        assertTrue(MathGlyphAssemblyInvalidReason.NoExtender in validation.invalidReasons)
        assertNull(font.verticalConstruction(TEST_GLYPH, 170f, 1000f))
    }

    @Test
    fun invalidAssembliesCompletePromptlyAndFallbackToTheLastVariant() {
        val invalidFixtures = listOf(
            MathGlyphAssemblyInvalidReason.NoExtender to MathGlyphAssembly(
                parts = listOf(
                    MathGlyphAssemblyPart(10u, 10, 20, 100, false),
                    MathGlyphAssemblyPart(12u, 20, 10, 100, false),
                ),
                minimumConnectorOverlap = 10,
            ),
            MathGlyphAssemblyInvalidReason.NonPositiveExtenderGrowth to MathGlyphAssembly(
                parts = listOf(MathGlyphAssemblyPart(11u, 10, 10, 10, true)),
                minimumConnectorOverlap = 10,
            ),
            MathGlyphAssemblyInvalidReason.ConnectorShorterThanMinimumOverlap to MathGlyphAssembly(
                parts = listOf(
                    MathGlyphAssemblyPart(10u, 10, 20, 100, false),
                    MathGlyphAssemblyPart(11u, 5, 20, 60, true),
                    MathGlyphAssemblyPart(12u, 20, 10, 100, false),
                ),
                minimumConnectorOverlap = 10,
            ),
        )
        invalidFixtures.forEach { (reason, assembly) ->
            val font = LeteSansMath.load().copy(
                verticalConstructions = mapOf(
                    TEST_GLYPH to MathGlyphConstruction(
                        variants = listOf(MathGlyphVariant(FALLBACK_GLYPH, 120)),
                        assembly = assembly,
                    ),
                ),
            )
            val selected = assertCompletesWithinOneSecond {
                assertNotNull(font.verticalConstruction(TEST_GLYPH, 250f, 1000f))
            }
            assertEquals(MathConstructionKind.Variant, selected.kind, reason.toString())
            assertEquals(FALLBACK_GLYPH, selected.components.single().glyphId, reason.toString())
            assertFalse(selected.reachesTarget, reason.toString())
            assertEquals("MathMLCore5.3.2LastVariantAfterInvalidAssembly", selected.constructionPolicy)
            assertEquals(false, selected.assemblyValidation?.valid)
            assertTrue(reason in selected.assemblyValidation!!.invalidReasons)
        }
    }

    @Test
    fun unusedTerminalConnectorsMayBeZeroButEveryParticipatingConnectionIsValidated() {
        val assembly = MathGlyphAssembly(
            parts = listOf(
                MathGlyphAssemblyPart(10u, 0, 40, 100, false),
                MathGlyphAssemblyPart(11u, 30, 30, 60, true),
                MathGlyphAssemblyPart(12u, 40, 0, 100, false),
            ),
            minimumConnectorOverlap = 10,
        )
        val font = LeteSansMath.load().copy(
            verticalConstructions = mapOf(
                TEST_GLYPH to MathGlyphConstruction(emptyList(), assembly),
            ),
        )

        val validation = assertNotNull(font.verticalAssemblyValidation(TEST_GLYPH))
        assertTrue(validation.valid, validation.toString())
        assertEquals("MathMLCore5.3.1ParticipatingConnections", validation.connectorValidationPolicy)
        assertEquals(3, validation.checkedConnectionCount)
        assertEquals(MathConstructionKind.Assembly, font.verticalConstruction(TEST_GLYPH, 220f, 1000f)?.kind)
    }

    @Test
    fun heterogeneousConnectorMaximaStillUseOneStandardOverlap() {
        val assembly = MathGlyphAssembly(
            parts = listOf(
                MathGlyphAssemblyPart(10u, 10, 80, 100, false),
                MathGlyphAssemblyPart(11u, 60, 40, 100, true),
                MathGlyphAssemblyPart(12u, 30, 10, 100, false),
            ),
            minimumConnectorOverlap = 10,
        )
        val font = LeteSansMath.load().copy(
            verticalConstructions = mapOf(
                TEST_GLYPH to MathGlyphConstruction(emptyList(), assembly),
            ),
        )
        val selected = assertNotNull(font.verticalConstruction(TEST_GLYPH, 220f, 1000f))
        assertEquals(MathConstructionKind.Assembly, selected.kind)
        assertEquals("MathMLCore5.3.1UniformOverlap", selected.constructionPolicy)
        assertEquals(true, selected.assemblyValidation?.valid)
        assertEquals(listOf(30f, 30f), selected.connectorOverlaps)
        assertNear(30f, assertNotNull(selected.uniformConnectorOverlap))
        assertEquals(listOf(0f, 70f, 140f), selected.components.map { it.offset })
        assertNear(240f, selected.advanceMeasurement)
    }

    private fun construction(withExtender: Boolean): MathGlyphConstruction = MathGlyphConstruction(
        variants = emptyList(),
        assembly = MathGlyphAssembly(
            parts = buildList {
                add(MathGlyphAssemblyPart(10u, 10, 40, 100, false))
                if (withExtender) add(MathGlyphAssemblyPart(11u, 30, 30, 60, true))
                add(MathGlyphAssemblyPart(12u, 40, 10, 100, false))
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
        val FALLBACK_GLYPH: UShort = 2u
    }
}

private fun <T> assertCompletesWithinOneSecond(block: () -> T): T {
    val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "math-assembly-timeout").apply { isDaemon = true }
    }
    return try {
        executor.submit<T> { block() }.get(1, TimeUnit.SECONDS)
    } finally {
        executor.shutdownNow()
    }
}
