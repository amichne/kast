package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.FIXTURE_ROOT
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostedClassLookupTest {
    @Test
    fun `class lookup retains root identity and admits only bounded exact Kotlin names`() {
        val lookup = (HostedClassLookup.parse(FIXTURE_ROOT, "Refinement") as Refinement.Refined).value
        assertEquals(FIXTURE_ROOT, lookup.root)
        assertEquals("Refinement", lookup.name.value)
        for (name in listOf("", " ", "com.example.Refinement", "Refin*", "A\nB", "1Class", "a".repeat(513))) {
            assertEquals(
                Refinement.Rejected(HostedQueryFailure.INVALID_SELECTION),
                HostedClassLookup.parse(FIXTURE_ROOT, name),
                name,
            )
        }
    }

    @Test
    fun `candidate collection stops at the limit and cannot publish a partial result`() {
        val bounded = HostedIndexCandidates<Int>()
        repeat(HOSTED_MAX_INDEX_CANDIDATES) { assertEquals(HostedIndexCollection.CONTINUE, bounded.accept(it)) }
        assertEquals(HostedIndexCollection.STOP, bounded.accept(HOSTED_MAX_INDEX_CANDIDATES))
        assertEquals(Refinement.Rejected(HostedQueryFailure.RESULT_LIMIT_EXCEEDED), bounded.finish())
    }

    @Test
    fun `candidate collection preserves complete empty and detached bounded results`() {
        assertEquals(emptyList<Int>(), (HostedIndexCandidates<Int>().finish() as Refinement.Refined).value)
        val bounded = HostedIndexCandidates<Int>()
        bounded.accept(1)
        val first = (bounded.finish() as Refinement.Refined).value
        bounded.accept(2)
        assertEquals(listOf(1), first)
        assertThrows(UnsupportedOperationException::class.java) { (first as MutableList).add(3) }
    }
}
