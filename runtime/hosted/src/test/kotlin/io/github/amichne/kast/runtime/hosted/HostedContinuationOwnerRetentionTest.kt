package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryResultSet
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.CanonicalQueryReferences
import io.github.amichne.kast.query.protocol.QueryCheckpointIssuance
import io.github.amichne.kast.query.protocol.QueryCheckpointRestoration
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HostedContinuationOwnerRetentionTest {
    @Test
    fun `entry bound is per store and epoch retirement clears all five stores`() = runTest {
        val fixture = RelationPagingFixture.live()
        val authority = fixture.authority
        val source = HostedSourcePagingFixture.create(fixture)
        val traversal = HostedTraversalPagingFixture.create(fixture)
        val limits = ReadLimits.resolve(environment = mapOf("KAST_READ_QUERY_CONTINUATION_ENTRIES" to "1")).value()
        val continuations = HostedQueryContinuations()
        val owner = continuations.forEpoch(authority, limits)
        val relationRequest = fixture.request(RelationReadPositionDocument.Start)
        val relationOutcome = fixture.page()
        val evidence = (relationOutcome as OperationOutcome.Qualified).evidence
        val queryRequest = queryRequest(fixture)
        val queryOutcome = queryOutcome(evidence.basis)
        val checkpoint = checkpoint(fixture)
        val queryState = owner.checkpoints.issue(queryRequest, checkpoint) as QueryCheckpointIssuance.Issued
        val queryOutput = owner.issue(queryRequest, authority, queryOutcome) as HostedOutputRetention.Retained
        val relationOutput =
            owner.relationOutputs.issue(relationRequest, authority, relationOutcome) as HostedOutputRetention.Retained
        val sourceOutput =
            owner.sourceOutputs.issue(source.request, authority, source.outcome) as HostedOutputRetention.Retained
        val traversalOutput =
            owner.traversalOutputs.issue(traversal.request, authority, traversal.outcome)
                as HostedOutputRetention.Retained
        assertInstanceOf(
            QueryCheckpointRestoration.Restored::class.java,
            owner.checkpoints.restore(queryState.token, queryRequest, authority),
        )
        assertEquals(queryOutcome, owner.restore(queryOutput.token, queryRequest, authority))
        assertEquals(relationOutcome, owner.relationOutputs.restore(relationOutput.token, relationRequest, authority))
        assertEquals(source.outcome, owner.sourceOutputs.restore(sourceOutput.token, source.request, authority))
        assertEquals(
            traversal.outcome,
            owner.traversalOutputs.restore(traversalOutput.token, traversal.request, authority),
        )

        continuations.forEpoch(RelationPagingFixture.live().authority, limits)
        assertQueryRetired(owner, queryRequest, queryState.token, queryOutput.token)
        assertEquals(
            OperationOutcome.Rejected(RelationReadRejection.CONTINUATION_UNAVAILABLE),
            owner.relationOutputs.restore(relationOutput.token, relationRequest, authority),
        )
        assertEquals(
            OperationOutcome.Rejected(SourceReadRejection.CONTINUATION_UNAVAILABLE),
            owner.sourceOutputs.restore(sourceOutput.token, source.request, authority),
        )
        assertEquals(
            OperationOutcome.Rejected(TraversalRunRejection.CONTINUATION_UNAVAILABLE),
            owner.traversalOutputs.restore(traversalOutput.token, traversal.request, authority),
        )
    }

    private fun queryOutcome(basis: EvidenceBasis) =
        OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperation.QUERY_RUN.id,
                basis,
                QueryRunResult(bounded(emptyList()), bounded(emptyList())),
            )
        )

    private fun assertQueryRetired(
        owner: HostedQueryContinuations.Active,
        queryRequest: QueryRunRequest,
        queryState: ProtocolText,
        queryOutput: ProtocolText,
    ) {
        assertEquals(
            QueryCheckpointRestoration.Unavailable,
            owner.checkpoints.restore(queryState, queryRequest, owner.lease),
        )
        assertEquals(
            OperationOutcome.Rejected(
                QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
            ),
            owner.restore(queryOutput, queryRequest, owner.lease),
        )
    }

    private fun queryRequest(fixture: RelationPagingFixture) =
        queryIdentityRequest(fixture.exact)
            .copy(
                from =
                    QueryFromDocument.Symbols(
                        QueryDiscoveryDocument(
                            QueryMatchDocument.All,
                            QueryScopeDocument(
                                bounded(
                                    listOf(io.github.amichne.kast.protocol.contract.ProtocolText.parse("main").value())
                                ),
                                null,
                                null,
                            ),
                            bounded(listOf(QueryDeclarationKindDocument.CLASS)),
                        )
                    )
            )

    private suspend fun checkpoint(fixture: RelationPagingFixture): QueryCheckpoint {
        val queryRequest = queryRequest(fixture)
        lateinit var checkpoint: QueryCheckpoint
        CanonicalQueryProtocol(
                QueryOperations { admitted ->
                    checkpoint =
                        object : QueryCheckpoint {
                            override val plan = admitted.plan
                            override val lease = admitted.lease
                            override val retainedBytes = 1024L
                        }
                    QueryExecutionResult.Complete(
                        QueryResult(QueryResultSet.Symbols(emptyList()), emptyList()),
                        QueryCoverage.Complete(QueryCount.parse(0).value()),
                    )
                },
                CanonicalQueryReferences(),
            )
            .execute(
                queryRequest,
                fixture.authority,
                QueryBudget(fixture.budget.resources, QueryByteLimit.parse(100_000).value()),
            )
        return checkpoint
    }
}

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
