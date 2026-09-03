package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class CandidateSelectorContractTest {
    @Test
    fun `range candidates retain zero width compiler insertion points`() {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(3).refined())
        val file = SymbolDiscoveryFileIdentity.Workspace(
            CanonicalWorkspaceFilePath.fromCanonicalPath(
                root,
                Path.of("/workspace/src/Subject.kt"),
            ).refined(),
        )

        val selector = CandidateSelector.restoreRange(lease, file, 7, 7).refined()

        assertEquals(7, selector.startInclusive.value)
        assertEquals(7, selector.endExclusive.value)
        assertEquals(
            CandidateSelectorFailure.REVERSED_RANGE,
            (CandidateSelector.restoreRange(lease, file, 8, 7) as Refinement.Rejected).failure,
        )
        assertInstanceOf(
            Refinement.Rejected::class.java,
            CandidateSelector.restoreRange(lease, file, -1, 0),
        )
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("unexpected rejection: $failure")
}
