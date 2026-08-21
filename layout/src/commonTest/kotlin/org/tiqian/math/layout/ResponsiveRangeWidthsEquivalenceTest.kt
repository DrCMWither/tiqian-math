package org.tiqian.math.layout

import org.tiqian.math.core.MathAtomClass
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathGlueAdjustment
import org.tiqian.math.core.MathGlueKind
import org.tiqian.math.core.MathAdjustmentPriority
import org.tiqian.math.core.MathInlineFragment
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.SourceRange
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ResponsiveRangeWidths and geometry() are two implementations of the same accumulation
 * semantics (leading kern, ink seeding vs merging, withheld trailing glue at the range end).
 * The DP trusts the memo while placement trusts geometry — this test pins them to each other
 * over randomized fragment sequences so a fix applied to one walk cannot silently skip the
 * other.
 */
class ResponsiveRangeWidthsEquivalenceTest {
    @Test
    fun memoWidthsMatchGeometryForEveryBoundaryPair() {
        val random = Random(42)
        repeat(50) {
            val fragments = randomFragments(random)
            val boundaries = responsiveBoundaries(fragments)
            val widths = ResponsiveRangeWidths(fragments, boundaries)
            for (start in 0 until boundaries.lastIndex) {
                for (end in start + 1..boundaries.lastIndex) {
                    val range = boundaries[start].position..(boundaries[end].position - 1)
                    val minimum = geometry(fragments, range, internalGlue(fragments, range) { it.minimumPx })
                    val natural = geometry(fragments, range, internalGlue(fragments, range) { it.naturalPx })
                    assertNear(minimum.visualWidth, widths.minimum(start, end), fragments, range, "minimum")
                    assertNear(natural.visualWidth, widths.natural(start, end), fragments, range, "natural")
                }
            }
        }
    }

    private fun randomFragments(random: Random): List<MathInlineFragment> {
        val count = random.nextInt(3, 12)
        return List(count) { index ->
            val width = random.nextInt(5, 200).toFloat()
            val inkLeft = random.nextInt(-8, 1).toFloat()
            val inkRight = width + random.nextInt(0, 9)
            val atomClass = when (random.nextInt(5)) {
                0 -> MathAtomClass.Relation
                1 -> MathAtomClass.Binary
                2 -> MathAtomClass.Opening
                3 -> MathAtomClass.Closing
                else -> MathAtomClass.Ordinary
            }
            val glueNatural = random.nextInt(0, 20).toFloat()
            val glueMinimum = (glueNatural - random.nextInt(0, 10)).coerceAtLeast(0f)
            val glueMaximum = glueNatural + 5f
            MathInlineFragment(
                index = index,
                sourceRange = SourceRange(index, index + 1),
                atomClass = if (index == 0) MathAtomClass.Ordinary else atomClass,
                box = MathBox(
                    width = width,
                    ascent = 8f,
                    descent = 2f,
                    inkBounds = if (random.nextInt(6) == 0) {
                        MathRect(0f, 0f, 0f, 0f)
                    } else {
                        MathRect(inkLeft, -8f, inkRight, 2f)
                    },
                    glyphs = emptyList(),
                    rules = emptyList(),
                    range = SourceRange(index, index + 1),
                ),
                leadingKernPx = random.nextInt(-3, 6).toFloat(),
                trailingItalicCorrectionPx = random.nextInt(0, 4).toFloat(),
                trailingGlue = MathGlueAdjustment(
                    kind = MathGlueKind.Thin,
                    naturalPx = glueNatural,
                    minimumPx = glueMinimum,
                    maximumPx = glueMaximum,
                    shrinkPx = glueNatural - glueMinimum,
                    stretchPx = glueMaximum - glueNatural,
                    priority = MathAdjustmentPriority.Other,
                ),
                breakAfter = null,
            )
        }
    }

    private fun assertNear(
        expected: Float,
        actual: Float,
        fragments: List<MathInlineFragment>,
        range: IntRange,
        label: String,
    ) {
        assertTrue(
            abs(expected - actual) <= 0.02f,
            "$label width mismatch for range $range: geometry=$expected memo=$actual " +
                "fragments=${fragments.map { Triple(it.box.width, it.leadingKernPx, it.trailingGlue.naturalPx) }}",
        )
    }
}
