package io.github.amichne.kast.diagnostic.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DiagnosticScopeQueryTest {
    private val lease = SemanticReadLease(
        (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value,
        (EvidenceGeneration.parse(1) as Refinement.Refined).value,
    )

    @Test
    fun `a root directory or Kotlin file remains a lease-bound query until source admission`() {
        for (raw in listOf(".", "src", "src/Subject.kt", "src/Subject.kts")) {
            val query = (DiagnosticScopeQuery.parse(lease, raw) as Refinement.Refined).value
            assertEquals(lease, query.lease)
            assertEquals(Path.of("/workspace").resolve(raw).normalize(), query.path)
        }
    }

    @Test
    fun `escaped sibling unnormalized absolute and malformed scopes are rejected`() {
        for (raw in listOf("../outside", "/workspace-other/src", "/workspace/src/../src", "", " ", "a\u0000b")) {
            assertTrue(DiagnosticScopeQuery.parse(lease, raw) is Refinement.Rejected, raw)
        }
    }
}
