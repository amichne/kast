package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure
import io.github.amichne.kast.protocol.contract.QueryDiagnosticReadIdentity
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadTraceIdentity
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadTraceObservation
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HostedQueryDiagnosticIdentityTest {
    private val first = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val second = UUID.fromString("44444444-4444-4444-8444-444444444444")

    private fun observed(identity: UUID) =
        HostedReadTraceObservation.Observed(HostedReadTraceIdentity.fromBoundary(identity))

    private fun rejection(): HostedQueryOutcome =
        OperationOutcome.Rejected(
            QueryRunRejection.CompletionUnproven(
                QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                QueryCompletionCauseDocument.IncompleteExecution,
                QueryCompletionCoverageDocument.Complete,
                QueryInvocationStop.TIME_LIMIT,
                QueryCompletionEvidenceDocument.Unavailable(QueryCompletionRetentionFailure.CAPACITY_EXCEEDED),
            )
        )

    @Test
    fun `diagnostic projection preserves the finite rejection and original allocation across presentation`() {
        val original = rejection()
        val projected = original.withQueryDiagnosticIdentity(observed(first))
        val completion =
            (projected as OperationOutcome.Rejected).reason.reason() as QueryRunRejection.CompletionUnproven
        assertEquals(QueryDiagnosticReadIdentity.fromBoundary(first), completion.diagnosticReadId)
        assertEquals((original as OperationOutcome.Rejected).reason, completion.copy(diagnosticReadId = null))
        assertSame(projected, projected.withQueryDiagnosticIdentity(observed(second)))
        assertEquals(projected, projected.publicationPage())
        assertSame(original, original.withQueryDiagnosticIdentity(HostedReadTraceObservation.Unobserved))
        val ordinary: HostedQueryOutcome = OperationOutcome.Rejected(QueryRunRejection.WorkspaceNotReady)
        assertSame(ordinary, ordinary.withQueryDiagnosticIdentity(observed(first)))
    }

    @Test
    fun `diagnostic identity is included in the actual encoded byte bound`() {
        val original = rejection()
        val encoded = encodeHostedQueryResponse(original)
        val limit =
            (ReturnedByteLimit.parse(encoded.document.toByteArray(Charsets.UTF_8).size.toLong()) as Refinement.Refined)
                .value
        assertInstanceOf(
            HostedResponse.Canonical::class.java,
            encodeHostedQueryResponse(original, maximumBytes = limit),
        )
        assertInstanceOf(
            HostedResponse.Oversized::class.java,
            encodeHostedQueryResponse(original.withQueryDiagnosticIdentity(observed(first)), maximumBytes = limit),
        )
    }
}
