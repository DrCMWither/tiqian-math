package org.tiqian.math.font.opentype

/** First scalar in Unicode Plane 15's private-use area, reserved by Tiqian font instances. */
const val MathGlyphReplayScalarBase: Int = 0xF0000

fun mathGlyphReplayScalar(glyphId: UShort): Int = MathGlyphReplayScalarBase + glyphId.toInt()

/**
 * Adds a format-12 cmap group that maps Plane-15 private-use scalars directly to glyph ids.
 *
 * No outline, metric, MATH or shaping table is rewritten. Android can consequently ask its own
 * Typeface/Paint stack to measure and outline an already-resolved glyph id, including unencoded
 * MathVariants and assembly parts, without a native FreeType copy in the application.
 */
object OpenTypeGlyphReplayFont {
    fun attach(fontBytes: ByteArray): ByteArray {
        val sfnt = SfntFile(fontBytes)
        val glyphCount = sfnt.numGlyphs()
        require(glyphCount in 1..0xFFFE) { "Unsupported glyph count $glyphCount" }
        val cmapRecord = sfnt.tables.firstOrNull { it.tag == "cmap" }
            ?: throw IllegalArgumentException("Font has no cmap table")
        val cmap = fontBytes.copyOfRange(cmapRecord.offset, cmapRecord.offset + cmapRecord.length)
        val rebuiltCmap = attachReplayGroupToCmap(cmap, glyphCount)
        if (rebuiltCmap === cmap) return fontBytes.copyOf()
        return sfnt.rebuild(mapOf("cmap" to rebuiltCmap))
    }

    private fun attachReplayGroupToCmap(cmap: ByteArray, glyphCount: Int): ByteArray {
        val reader = BeReader(cmap)
        require(reader.u16(0) == 0) { "Unsupported cmap version" }
        val recordCount = reader.u16(2)
        data class EncodingRecord(val platform: Int, val encoding: Int, val oldOffset: Int)
        val records = List(recordCount) { index ->
            val offset = 4 + index * 8
            EncodingRecord(reader.u16(offset), reader.u16(offset + 2), reader.u32(offset + 4))
        }
        val uniqueOffsets = records.map { it.oldOffset }.distinct()
        require(uniqueOffsets.any { reader.u16(it) == 12 }) {
            "Glyph replay requires a Unicode cmap format 12 subtable"
        }
        val subtables = LinkedHashMap<Int, ByteArray>()
        var changed = false
        uniqueOffsets.forEach { oldOffset ->
            val format = reader.u16(oldOffset)
            val length = when (format) {
                12, 13 -> reader.u32(oldOffset + 4)
                14 -> reader.u32(oldOffset + 2)
                else -> reader.u16(oldOffset + 2)
            }
            val bytes = cmap.copyOfRange(oldOffset, oldOffset + length)
            if (format == 12) {
                val groups = reader.u32(oldOffset + 12)
                var replayGroupFound = false
                repeat(groups) { groupIndex ->
                    val group = oldOffset + 16 + groupIndex * 12
                    if (reader.u32(group) == MathGlyphReplayScalarBase && reader.u32(group + 8) == 0) {
                        require(reader.u32(group + 4) == MathGlyphReplayScalarBase + glyphCount - 1) {
                            "Existing replay cmap group has a different glyph count"
                        }
                        replayGroupFound = true
                    }
                }
                subtables[oldOffset] = if (replayGroupFound) {
                    bytes
                } else {
                    changed = true
                    bytes.withReplayGroup(glyphCount)
                }
            } else {
                subtables[oldOffset] = bytes
            }
        }
        if (!changed) return cmap

        val headerSize = 4 + records.size * 8
        val newOffsets = LinkedHashMap<Int, Int>()
        var size = headerSize
        subtables.forEach { (oldOffset, bytes) ->
            size = align4(size)
            newOffsets[oldOffset] = size
            size += bytes.size
        }
        val out = ByteArray(size)
        out.putU16(0, 0)
        out.putU16(2, records.size)
        records.forEachIndexed { index, record ->
            val offset = 4 + index * 8
            out.putU16(offset, record.platform)
            out.putU16(offset + 2, record.encoding)
            out.putU32(offset + 4, newOffsets.getValue(record.oldOffset))
        }
        subtables.forEach { (oldOffset, bytes) ->
            bytes.copyInto(out, newOffsets.getValue(oldOffset))
        }
        return out
    }

    private fun ByteArray.withReplayGroup(glyphCount: Int): ByteArray {
        val reader = BeReader(this)
        val groupCount = reader.u32(12)
        val replayEnd = MathGlyphReplayScalarBase + glyphCount - 1
        var insertionIndex = groupCount
        var previousEnd = -1
        repeat(groupCount) { groupIndex ->
            val group = 16 + groupIndex * 12
            val start = reader.u32(group)
            val end = reader.u32(group + 4)
            require(start <= end && start > previousEnd) { "cmap format 12 groups are not sorted" }
            require(end < MathGlyphReplayScalarBase || start > replayEnd) {
                "Existing cmap group overlaps Tiqian's Plane-15 replay range"
            }
            if (insertionIndex == groupCount && start > MathGlyphReplayScalarBase) {
                insertionIndex = groupIndex
            }
            previousEnd = end
        }
        val out = copyOf(size + 12)
        out.putU32(4, out.size)
        out.putU32(12, groupCount + 1)
        val group = 16 + insertionIndex * 12
        copyInto(out, group + 12, group, size)
        out.putU32(group, MathGlyphReplayScalarBase)
        out.putU32(group + 4, replayEnd)
        out.putU32(group + 8, 0)
        return out
    }
}

private data class SfntTable(val tag: String, val offset: Int, val length: Int)

private class SfntFile(private val bytes: ByteArray) {
    private val reader = BeReader(bytes)
    val tables: List<SfntTable>

    init {
        require(bytes.size >= 12) { "Font is shorter than an sfnt header" }
        val count = reader.u16(4)
        tables = List(count) { index ->
            val record = 12 + index * 16
            val table = SfntTable(reader.tag(record), reader.u32(record + 8), reader.u32(record + 12))
            require(table.offset >= 0 && table.length >= 0 && table.offset <= bytes.size - table.length) {
                "Invalid ${table.tag} table range"
            }
            table
        }
    }

    fun numGlyphs(): Int {
        val maxp = tables.firstOrNull { it.tag == "maxp" }
            ?: throw IllegalArgumentException("Font has no maxp table")
        return reader.u16(maxp.offset + 4)
    }

    fun rebuild(replacements: Map<String, ByteArray>): ByteArray {
        val tableBytes = tables.associate { table ->
            val copied = replacements[table.tag]
                ?: bytes.copyOfRange(table.offset, table.offset + table.length)
            table.tag to if (table.tag == "head") copied.copyOf().also { it.putU32(8, 0) } else copied
        }
        val directorySize = 12 + tables.size * 16
        val offsets = LinkedHashMap<String, Int>()
        var size = directorySize
        tables.forEach { table ->
            size = align4(size)
            offsets[table.tag] = size
            size += align4(tableBytes.getValue(table.tag).size)
        }
        val out = ByteArray(size)
        bytes.copyInto(out, 0, 0, 12)
        tables.forEachIndexed { index, table ->
            val record = 12 + index * 16
            val data = tableBytes.getValue(table.tag)
            out.putTag(record, table.tag)
            out.putU32(record + 4, checksum(data))
            out.putU32(record + 8, offsets.getValue(table.tag))
            out.putU32(record + 12, data.size)
            data.copyInto(out, offsets.getValue(table.tag))
        }
        val headOffset = offsets["head"] ?: throw IllegalArgumentException("Font has no head table")
        val adjustment = (0xB1B0AFBAL - checksum(out).toLong()).and(0xFFFF_FFFFL).toInt()
        out.putU32(headOffset + 8, adjustment)
        return out
    }
}

private class BeReader(private val bytes: ByteArray) {
    fun u16(offset: Int): Int {
        require(offset >= 0 && offset <= bytes.size - 2)
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    fun u32(offset: Int): Int {
        require(offset >= 0 && offset <= bytes.size - 4)
        val value = ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
        require(value <= Int.MAX_VALUE) { "sfnt value exceeds supported byte-array range" }
        return value.toInt()
    }

    fun tag(offset: Int): String = buildString(4) {
        repeat(4) { append(bytes[offset + it].toInt().and(0xFF).toChar()) }
    }
}

private fun ByteArray.putU16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.putU32(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

private fun ByteArray.putTag(offset: Int, tag: String) {
    require(tag.length == 4)
    repeat(4) { this[offset + it] = tag[it].code.toByte() }
}

private fun checksum(bytes: ByteArray): Int {
    var sum = 0L
    var offset = 0
    while (offset < bytes.size) {
        var word = 0L
        repeat(4) { byteIndex ->
            word = word shl 8
            if (offset + byteIndex < bytes.size) word = word or (bytes[offset + byteIndex].toLong() and 0xFF)
        }
        sum = (sum + word) and 0xFFFF_FFFFL
        offset += 4
    }
    return sum.toInt()
}

private fun align4(value: Int): Int = (value + 3) and -4
