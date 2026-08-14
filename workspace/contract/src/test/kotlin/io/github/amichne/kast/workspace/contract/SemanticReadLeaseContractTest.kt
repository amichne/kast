package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SemanticReadLeaseContractTest {
    @Test
    fun `lease retains canonical root and published generation`() {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace/root")).refinedValue()
        val generation = EvidenceGeneration.parse(7).refinedValue()

        assertEquals(
            SemanticReadLease(root, generation),
            SemanticReadLease(workspaceRoot = root, generation = generation),
        )
    }

    @Test
    fun `canonical root excludes relative and non-normalized paths`() {
        assertEquals(
            CanonicalWorkspaceRootFailure.NOT_ABSOLUTE,
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("workspace/root")).rejectedFailure(),
        )
        assertEquals(
            CanonicalWorkspaceRootFailure.NOT_NORMALIZED,
            CanonicalWorkspaceRoot
                .fromCanonicalPath(Path.of("/workspace/root/../other"))
                .rejectedFailure(),
        )
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.rejectedFailure(): Failure = when (this) {
        is Refinement.Refined -> error("Expected rejection, got $value")
        is Refinement.Rejected -> failure
    }
}
