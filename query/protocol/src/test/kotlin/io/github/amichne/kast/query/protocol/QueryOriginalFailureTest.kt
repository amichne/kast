package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactExecutionFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactPresentationFailureDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure
import io.github.amichne.kast.protocol.contract.QueryCompletionUnsupportedReason
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryReferenceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QuerySourceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class QueryOriginalFailureTest {
    @Test
    fun `original execution identity survives refinement`() {
        val source = QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.NON_ADVANCING_CONTINUATION)
        val result = source.originalFailure() as Refinement.Refined
        assertSame(source, result.value)
        assertEquals(
            Refinement.Refined(QueryRunRejection.WorkspaceNotReady),
            QueryRunRejection.WorkspaceNotReady.originalFailure(),
        )
    }

    @Test
    fun `every original leaf retains exact detail without a completion root`() {
        val offset = (ProtocolOffset.parse(4) as Refinement.Refined).value
        val execution = QueryRunRejection.ImpactExecutionRejected(ImpactExecutionFailureDocument.PresentationOnly)
        val source =
            QueryRunRejection.ImpactSourceRejected(
                QueryImpactSourceFailureDocument.Reference(QueryReferenceRejectionReason.MALFORMED, offset)
            )
        val presentation =
            QueryRunRejection.ImpactPresentationRejected(ImpactPresentationFailureDocument.DomainProjectionRejected)
        val reference = QueryRunRejection.ReferenceRejected(offset, QueryReferenceRejectionReason.STALE_AUTHORITY)
        val step = QueryRunRejection.StepReferenceRejected(offset, offset, QueryReferenceRejectionReason.WRONG_KIND)
        val declaration =
            QueryRunRejection.SourceRejected(
                QueryDeclarationKindDocument.CLASS,
                QuerySourceRejectionReason.UNSUPPORTED_DECLARATION_KIND,
            )
        val leaves =
            listOf(
                execution to execution,
                source to source,
                presentation to presentation,
                reference to reference,
                step to step,
                declaration to declaration,
            )
        leaves.forEach { (original, expected) ->
            assertEquals(Refinement.Refined(expected), original.originalFailure())
        }
    }

    @Test
    fun `both completion verdicts have typed refinement rejection`() {
        val model = QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1
        val verdicts =
            listOf(
                QueryRunRejection.CompletionUnsupported(model, QueryCompletionUnsupportedReason.UNSUPPORTED_OUTPUT),
                QueryRunRejection.CompletionUnproven(
                    model,
                    io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument.IncompleteExecution,
                    QueryCompletionCoverageDocument.Complete,
                    QueryInvocationStop.INVALID_STATE,
                    QueryCompletionEvidenceDocument.Unavailable(QueryCompletionRetentionFailure.UNAVAILABLE),
                ),
            )
        verdicts.forEach { verdict ->
            assertEquals(
                Refinement.Rejected(QueryOriginalFailureRejection.CompletionPolicyFailure),
                verdict.originalFailure(),
            )
        }
    }
}
