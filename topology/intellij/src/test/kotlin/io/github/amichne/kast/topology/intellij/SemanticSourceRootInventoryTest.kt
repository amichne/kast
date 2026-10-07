package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SemanticSourceRootInventoryTest {
    private val kotlin = Path.of("/workspace/src/kotlin")
    private val java = Path.of("/workspace/src/java")
    private val declared = setOf(kotlin, java)

    @Test
    fun `declared root confirmed absent remains explicit negative evidence`() {
        val inventory =
            CompleteSemanticSourceRoots.admit(
                    declared,
                    setOf(kotlin),
                    mapOf(kotlin to SemanticSourceRootPresence.DIRECTORY, java to SemanticSourceRootPresence.ABSENT),
                )
                .value()
        assertEquals(SemanticSourceRootPresence.ABSENT, inventory.roots[java])
        assertEquals(declared, inventory.roots.keys)
    }

    @Test
    fun `disk present without VFS directory cannot be treated as absent`() {
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.SOURCE_UNAVAILABLE),
            CompleteSemanticSourceRoots.admit(
                declared,
                setOf(kotlin),
                mapOf(kotlin to SemanticSourceRootPresence.DIRECTORY, java to SemanticSourceRootPresence.UNAVAILABLE),
            ),
        )
    }

    @Test
    fun `native root outside imported model and unobserved root are rejected`() {
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.SOURCE_ROOT_INVENTORY_MISMATCH),
            CompleteSemanticSourceRoots.admit(
                setOf(kotlin),
                declared,
                mapOf(kotlin to SemanticSourceRootPresence.DIRECTORY),
            ),
        )
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.SOURCE_ROOT_INVENTORY_MISMATCH),
            CompleteSemanticSourceRoots.admit(
                declared,
                setOf(kotlin),
                mapOf(kotlin to SemanticSourceRootPresence.DIRECTORY),
            ),
        )
    }

    @Test
    fun `created previously absent root changes resolution input identity`() {
        val absent =
            CompleteSemanticSourceRoots.admit(
                    declared,
                    setOf(kotlin),
                    mapOf(kotlin to SemanticSourceRootPresence.DIRECTORY, java to SemanticSourceRootPresence.ABSENT),
                )
                .value()
        val created =
            CompleteSemanticSourceRoots.admit(
                    declared,
                    declared,
                    mapOf(kotlin to SemanticSourceRootPresence.DIRECTORY, java to SemanticSourceRootPresence.DIRECTORY),
                )
                .value()
        fun digest(inventory: CompleteSemanticSourceRoots): String {
            val digest = SemanticInputDigest()
            inventory.appendTo(digest)
            return digest.finish().value
        }
        assertNotEquals(digest(absent), digest(created))
    }
}

private fun <V, F> Refinement<V, F>.value(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
