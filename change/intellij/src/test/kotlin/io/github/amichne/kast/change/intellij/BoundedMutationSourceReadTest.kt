package io.github.amichne.kast.change.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class BoundedMutationSourceReadTest {
    @Test
    fun `source exactly at configured byte limit remains complete`() {
        val bytes = ByteArray(ReadLimits.Default[ReadLimitParameter.SOURCE_RETURNED_BYTES].value) { 42 }
        val result = readBoundedMutationSource(ByteArrayInputStream(bytes), ReadLimits.Default)
        assertArrayEquals(bytes, assertInstanceOf(BoundedMutationSourceRead.Complete::class.java, result).bytes)
    }

    @Test
    fun `growing stream is rejected after at most limit plus one bytes`() {
        var consumed = 0
        val stream =
            object : InputStream() {
                override fun read(): Int {
                    consumed++
                    return 42
                }
            }
        assertEquals(BoundedMutationSourceRead.LimitExceeded, readBoundedMutationSource(stream, ReadLimits.Default))
        assertEquals(ReadLimits.Default[ReadLimitParameter.SOURCE_RETURNED_BYTES].value + 1, consumed)
    }
}
