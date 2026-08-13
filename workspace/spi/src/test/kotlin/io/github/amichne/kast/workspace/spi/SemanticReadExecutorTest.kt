package io.github.amichne.kast.workspace.spi

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

class SemanticReadExecutorTest {
    @Test
    fun `executor returns detached result only while the exact lease stays current`() = runBlocking {
        val authority = FakeAuthority(lease())
        val result = SemanticReadExecutor(authority).current { evidence ->
            "detached@" + evidence.generation.value
        }

        assertEquals(
            SemanticReadExecution.Completed(
                lease = authority.evidence,
                payload = "detached@7",
            ),
            result,
        )
        assertTrue(authority.closed)
    }

    @Test
    fun `executor discards a payload when publication moves before return`() = runBlocking {
        val authority = FakeAuthority(lease())
        val failure = SemanticReadLeaseFailure.PublishedGenerationMoved(
            expected = generation(7),
            observed = generation(8),
        )

        val result = SemanticReadExecutor(authority).current {
            authority.validation = SemanticReadLeaseValidation.Rejected(failure)
            "must-not-return"
        }

        assertEquals(SemanticReadExecution.Rejected(failure), result)
        assertTrue(authority.closed)
    }

    @Test
    fun `executor closes the lease when the operation throws`() {
        val authority = FakeAuthority(lease())

        val failure = assertThrows<IllegalStateException> {
            runBlocking {
                SemanticReadExecutor(authority).current {
                    error("operation failed")
                }
            }
        }

        assertEquals("operation failed", failure.message)
        assertTrue(authority.closed)
    }

    private class FakeAuthority(
        val evidence: SemanticReadLease,
    ) : SemanticReadLeaseAuthority {
        var validation: SemanticReadLeaseValidation = SemanticReadLeaseValidation.Current
        var closed: Boolean = false

        override fun open(): SemanticReadLeaseAdmission =
            SemanticReadLeaseAdmission.Admitted(
                object : OpenSemanticReadLease {
                    override val evidence: SemanticReadLease = this@FakeAuthority.evidence

                    override fun validate(): SemanticReadLeaseValidation = validation

                    override fun close() {
                        closed = true
                    }
                },
            )
    }

    private fun lease(): SemanticReadLease = SemanticReadLease(
        workspaceRoot = CanonicalWorkspaceRoot
            .fromCanonicalPath(Path.of("/workspace/root"))
            .refinedValue(),
        generation = generation(7),
    )

    private fun generation(value: Long): EvidenceGeneration =
        EvidenceGeneration.parse(value).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }
}
