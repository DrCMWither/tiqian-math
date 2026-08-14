package org.tiqian.math.font.opentype

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenTypeGlyphReplayFontTest {
    @Test
    fun attachesDeterministicPlane15GlyphMapping() {
        val original = checkNotNull(javaClass.classLoader.getResourceAsStream(
            "org/tiqian/math/fonts/LeteSansMath-Regular.otf",
        )).use { it.readBytes() }
        val attached = OpenTypeGlyphReplayFont.attach(original)
        assertTrue(attached.isNotEmpty())
        assertEquals(0xB1B0AFBAu, attached.sfntChecksum())
        val format12Groups = attached.format12Groups()
        assertTrue(format12Groups.isNotEmpty())
        assertTrue(format12Groups.all { groups -> groups.zipWithNext().all { (left, right) -> left.first < right.first } })
        assertTrue(format12Groups.all { groups ->
            groups.any { (start, end, glyph) ->
                start == MathGlyphReplayScalarBase &&
                    end > start &&
                    glyph == 0
            }
        })
        assertContentEquals(attached, OpenTypeGlyphReplayFont.attach(attached))
    }

    @Test
    fun rejectsFontsWithoutAUnicodeFormat12ReplaySurface() {
        val original = checkNotNull(javaClass.classLoader.getResourceAsStream(
            "org/tiqian/math/fonts/LeteSansMath-Regular.otf",
        )).use { it.readBytes() }
        val withoutFormat12 = original.copyOf().also { bytes ->
            bytes.format12SubtableOffsets().forEach { subtable ->
                bytes[subtable] = 0
                bytes[subtable + 1] = 13
            }
        }

        assertFailsWith<IllegalArgumentException> {
            OpenTypeGlyphReplayFont.attach(withoutFormat12)
        }
    }
}

private fun ByteArray.format12SubtableOffsets(): List<Int> {
    val tableCount = u16(4)
    val cmapRecord = (0 until tableCount)
        .map { 12 + it * 16 }
        .first { record -> String(this, record, 4) == "cmap" }
    val cmapOffset = u32(cmapRecord + 8)
    val encodingCount = u16(cmapOffset + 2)
    return (0 until encodingCount)
        .map { cmapOffset + 4 + it * 8 }
        .map { record -> cmapOffset + u32(record + 4) }
        .distinct()
        .filter { subtable -> u16(subtable) == 12 }
}

private fun ByteArray.format12Groups(): List<List<Triple<Int, Int, Int>>> {
    val tableCount = u16(4)
    val cmapRecord = (0 until tableCount)
        .map { 12 + it * 16 }
        .first { record -> String(this, record, 4) == "cmap" }
    val cmapOffset = u32(cmapRecord + 8)
    val encodingCount = u16(cmapOffset + 2)
    return (0 until encodingCount)
        .map { cmapOffset + 4 + it * 8 }
        .map { record -> cmapOffset + u32(record + 4) }
        .distinct()
        .filter { subtable -> u16(subtable) == 12 }
        .map { subtable ->
            List(u32(subtable + 12)) { groupIndex ->
                val group = subtable + 16 + groupIndex * 12
                Triple(u32(group), u32(group + 4), u32(group + 8))
            }
        }
}

private fun ByteArray.u16(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

private fun ByteArray.u32(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.sfntChecksum(): UInt {
    var sum = 0u
    var offset = 0
    while (offset < size) {
        var word = 0u
        repeat(4) { index ->
            word = word shl 8
            if (offset + index < size) word = word or (this[offset + index].toUInt() and 0xFFu)
        }
        sum += word
        offset += 4
    }
    return sum
}
