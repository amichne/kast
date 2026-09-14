package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedQueryStoreTest {
    @Test
    fun `output replay changes allowances while preserving semantic identity and token`() {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                EvidenceGeneration.parse(1).refined(),
            )
        val store = HostedQueryContinuations.Active(lease, ReadLimits.Default)
        val low =
            request("short")
                .copy(
                    executionBudget =
                        io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument(
                            maxResults = io.github.amichne.kast.kernel.ResultLimit.parse(1).refined()
                        )
                )
        val high =
            low.copy(
                executionBudget =
                    io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument(
                        maxResults = io.github.amichne.kast.kernel.ResultLimit.parse(2).refined()
                    )
            )
        val output =
            OperationOutcome.Complete(
                EvidenceEnvelope(
                    CanonicalOperation.QUERY_RUN.id,
                    lease.generation,
                    QueryRunResult(bounded(emptyList()), bounded(emptyList())),
                )
            )
        val first = store.issue(low, lease, output) as HostedOutputRetention.Retained
        assertEquals(output, store.restore(first.token, high, lease))
        assertEquals(output, store.restore(first.token, high, lease))
        assertEquals(first, store.issue(high, lease, output))
        assertTrue(store.restore(first.token, request("different"), lease) is OperationOutcome.Rejected)
        val hosted = HostedRequest.Query(lease.workspaceRoot, high)
        assertEquals(high.executionBudget!!.requested(), hosted.executionBudget().requested)
    }

    @Test
    fun `retention accounts for query request independently of its small output suffix`() {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                EvidenceGeneration.parse(1).refined(),
            )
        val limits =
            ReadLimits.resolve(
                    environment =
                        mapOf(
                            ReadLimitParameter.QUERY_CONTINUATION_BYTES.environmentKey to "8192",
                            ReadLimitParameter.QUERY_CHECKPOINT_BYTES.environmentKey to "8192",
                        )
                )
                .refined()
        val store = HostedQueryContinuations.Active(lease, limits)
        val output =
            OperationOutcome.Complete(
                EvidenceEnvelope(
                    CanonicalOperation.QUERY_RUN.id,
                    lease.generation,
                    QueryRunResult(bounded(emptyList()), bounded(emptyList())),
                )
            )
        assertTrue(store.issue(request("short"), lease, output) is HostedOutputRetention.Retained)
        assertEquals(HostedOutputRetention.CapacityExceeded, store.issue(request("x".repeat(6000)), lease, output))
    }

    private fun request(reference: String) =
        QueryRunRequest(
            QueryFromDocument.References(
                bounded(listOf(QueryReferenceDocument.ExactSymbol(ProtocolText.parse("exact:v3:$reference").refined())))
            ),
            bounded(emptyList()),
            QueryOutputDocument.Symbols(bounded(emptyList())),
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )

    private fun <T> bounded(values: List<T>): BoundedProtocolList<T> = BoundedProtocolList.create(values).refined()

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value
}
