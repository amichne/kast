package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HostedReferenceAnchorTest {
    @org.junit.jupiter.api.Test
    fun `v5 handles admit only canonical unpadded 128 bit digests`() {
        val valid = (ProtocolText.parse("exact:v5:" + "A".repeat(22)) as Refinement.Refined).value
        org.junit.jupiter.api.Assertions.assertTrue(HostedSymbolHandle.parse(valid) is Refinement.Refined)
        for (suffix in listOf("A".repeat(21), "A".repeat(23), "A".repeat(21) + "B", "A".repeat(22) + "==")) {
            val token = (ProtocolText.parse("exact:v5:" + suffix) as Refinement.Refined).value
            org.junit.jupiter.api.Assertions.assertTrue(HostedSymbolHandle.parse(token) is Refinement.Rejected)
        }
    }

    @Test
    fun `compact reference parser rejects malformed and noncanonical digests`() {
        listOf(
                "exact:v4:bad",
                "candidate:v4:" + "A".repeat(64),
                "exact:v4:" + "a".repeat(63),
                "exact:v4:" + "a".repeat(64) + ":extra",
            )
            .forEach { raw ->
                val text = (ProtocolText.parse(raw) as Refinement.Refined).value
                assertInstanceOf(Refinement.Rejected::class.java, SourceReadAnchorDocument.admit(text))
            }
    }

    @Test
    fun `compact reference syntax retains its disjoint source anchor family`() {
        val exact = (ProtocolText.parse("exact:v4:" + "a".repeat(64)) as Refinement.Refined).value
        val candidate = (ProtocolText.parse("candidate:v4:" + "b".repeat(64)) as Refinement.Refined).value
        assertEquals(
            SourceReadAnchorDocument.Symbol(exact),
            assertInstanceOf(Refinement.Refined::class.java, SourceReadAnchorDocument.admit(exact)).value,
        )
        assertEquals(
            SourceReadAnchorDocument.Candidate(candidate),
            assertInstanceOf(Refinement.Refined::class.java, SourceReadAnchorDocument.admit(candidate)).value,
        )
    }
}
