package io.github.amichne.kast.topology.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SemanticInputDigestTest {
    @Test
    fun `argument boundaries cannot collapse into equal concatenations`() {
        assertEquals("f2939f903016e5bb29b1e4a61cdbd376220ca03a24180b39995f2d50f2e0a647", digest("ab", "c"))
        assertNotEquals(digest("ab", "c"), digest("a", "bc"))
    }

    @Test
    fun `empty argument and ordering remain semantic inputs`() {
        assertNotEquals(digest("a", "b"), digest("b", "a"))
        assertNotEquals(digest("", "a"), digest("a"))
        assertEquals(digest("a", "b"), digest("a", "b"))
    }

    private fun digest(vararg values: String): String =
        SemanticInputDigest().also { digest -> values.forEach(digest::text) }.finish().value
}
