package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedQueryGrantAdmissionTest {
    @Test
    fun `hosted query admission retains the explicit requested grant`() {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                EvidenceGeneration.parse(1).refined(),
            )
        val query =
            request("short")
                .copy(
                    executionBudget =
                        io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument(
                            maxResults = io.github.amichne.kast.kernel.ResultLimit.parse(2).refined()
                        )
                )
        val hosted = HostedRequest.Query(lease.workspaceRoot, query)
        assertEquals(query.executionBudget!!.requested(), hosted.executionBudget().requested)
    }

    private fun request(reference: String) =
        QueryRunRequest.Run(
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
