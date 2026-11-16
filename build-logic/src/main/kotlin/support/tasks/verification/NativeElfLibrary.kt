package support.tasks

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

internal enum class ElfLibraryAdmission { LIBRARY, EXECUTABLE, MALFORMED, UNREADABLE }

private data class ElfSegment(
    val type: Int, val offset: Long, val address: Long, val size: Long, val memorySize: Long,
)

/** ELF64 ET_DYN also describes PIE. Inspect PT_INTERP and DT_FLAGS_1 before library admission. */
internal fun admitElfLibrary(path: Path, target: String): ElfLibraryAdmission =
    try {
        RandomAccessFile(path.toFile(), "r").use { file ->
            admitElfLibrary(file, target)
        }
    } catch (_: IOException) {
        ElfLibraryAdmission.UNREADABLE
    }

private fun admitElfLibrary(file: RandomAccessFile, target: String): ElfLibraryAdmission {
    val length = file.length()
    if (length < 64) return ElfLibraryAdmission.MALFORMED
    val header = ByteArray(64).also(file::readFully)
    val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    if (!nativeQueryHeaderMatches(header.copyOf(20), target) || fields.getShort(16).toInt() != 3 ||
        fields.getInt(20) != 1 || fields.getShort(52).toInt() != 64 || fields.getShort(54).toInt() != 56
    ) return ElfLibraryAdmission.MALFORMED
    val offset = fields.getLong(32)
    val count = fields.getShort(56).toInt() and 0xffff
    if (count !in 1..256 || offset < 64 || offset > length - count * 56L) return ElfLibraryAdmission.MALFORMED
    file.seek(offset)
    val table = ByteBuffer.wrap(ByteArray(count * 56).also(file::readFully)).order(ByteOrder.LITTLE_ENDIAN)
    val segments = List(count) { index ->
        val start = index * 56
        ElfSegment(table.getInt(start), table.getLong(start + 8), table.getLong(start + 16),
            table.getLong(start + 32), table.getLong(start + 40))
    }
    if (segments.any { it.offset < 0 || it.size < 0 || it.offset > length || it.size > length - it.offset }) {
        return ElfLibraryAdmission.MALFORMED
    }
    if (segments.any { it.type == 3 }) return ElfLibraryAdmission.EXECUTABLE
    val loads = segments.filter { it.type == 1 }
    if (loads.isEmpty()) return ElfLibraryAdmission.MALFORMED
    if (segments.filter { it.type in setOf(1, 2) }.any {
        it.address < 0 || it.memorySize < it.size || it.address > Long.MAX_VALUE - it.memorySize
    }) return ElfLibraryAdmission.MALFORMED
    val dynamic = segments.filter { it.type == 2 }
    if (dynamic.size != 1) return ElfLibraryAdmission.MALFORMED
    val segment = dynamic.single()
    if (segment.size !in 16..65536 || segment.size % 16 != 0L) return ElfLibraryAdmission.MALFORMED
    // The inspected dynamic bytes must be the bytes mapped at its virtual address.
    val mapping = loads.singleOrNull {
        segment.address >= it.address && segment.address - it.address <= it.memorySize &&
            segment.memorySize <= it.memorySize - (segment.address - it.address)
    } ?: return ElfLibraryAdmission.MALFORMED
    val delta = segment.address - mapping.address
    if (delta > mapping.size || segment.size > mapping.size - delta ||
        segment.offset < mapping.offset || segment.offset - mapping.offset != delta
    ) return ElfLibraryAdmission.MALFORMED
    file.seek(segment.offset)
    val entries = ByteBuffer.wrap(ByteArray(segment.size.toInt()).also(file::readFully)).order(ByteOrder.LITTLE_ENDIAN)
    var flagsSeen = false
    var terminated = false
    while (entries.hasRemaining()) {
        val tag = entries.long
        val value = entries.long
        if (terminated && (tag != 0L || value != 0L)) return ElfLibraryAdmission.MALFORMED
        when (tag) {
            0L -> terminated = true
            0x6ffffffbL -> {
                if (flagsSeen) return ElfLibraryAdmission.MALFORMED
                flagsSeen = true
                if (value and 0x08000000L != 0L) return ElfLibraryAdmission.EXECUTABLE
            }
        }
    }
    return if (terminated) ElfLibraryAdmission.LIBRARY else ElfLibraryAdmission.MALFORMED
}
