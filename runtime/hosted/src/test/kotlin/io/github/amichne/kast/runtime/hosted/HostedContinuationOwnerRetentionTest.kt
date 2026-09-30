package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
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
    fun `query progress shares one quota and epoch retirement revokes detached source output`() = runTest {
        val epochs =
            io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture(
                RelationPagingFixture.live().authority.workspaceRoot
            )
        val authority = epochs.admit()
        val fixture = RelationPagingFixture(authority)
        val source = HostedSourcePagingFixture.create(fixture)
        val limits = ReadLimits.resolve(environment = mapOf("KAST_READ_QUERY_CONTINUATION_ENTRIES" to "2")).value()
        val continuations = HostedQueryContinuations()
        val owner = continuations.forEpoch(authority, limits).value()
        val queryRequest = queryRequest(fixture)
        val checkpoint = checkpoint(fixture)
        val queryState = owner.queryState.issueCheckpoint(queryRequest, checkpoint) as QueryCheckpointIssuance.Issued
        val sourceOutput =
            owner.sourceState.retainAcceptedFixtureSuffix(source.request, authority, source.outcome)
                as HostedOutputRetention.Retained
        assertInstanceOf(
            QueryCheckpointRestoration.Restored::class.java,
            owner.queryState.restoreCheckpoint(queryState.token, authority),
        )
        assertEquals(source.outcome, owner.sourceState.readFixtureSuffix(sourceOutput.token, source.request, authority))

        val nextAuthority = epochs.advance()
        val next = continuations.forEpoch(nextAuthority, limits).value()
        assertEquals(
            Refinement.Rejected(io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure.EPOCH_MOVED),
            continuations.forEpoch(authority, limits),
        )
        org.junit.jupiter.api.Assertions.assertSame(next, continuations.forEpoch(nextAuthority, limits).value())
        assertQueryRetired(owner, queryState.token)
        assertEquals(QueryCheckpointIssuance.Unavailable, owner.queryState.issueCheckpoint(queryRequest, checkpoint))
        assertEquals(
            HostedOutputRetention.Rejected(
                io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause.CLAIM_UNAVAILABLE
            ),
            owner.sourceState.retainAcceptedFixtureSuffix(source.request, authority, source.outcome),
        )
        assertEquals(
            OperationOutcome.Rejected(SourceReadRejection.CONTINUATION_UNAVAILABLE),
            owner.sourceState.readFixtureSuffix(sourceOutput.token, source.request, authority),
        )
    }

    @Test
    fun `disposed continuation owner cannot be resurrected`() {
        val epochs =
            io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture(
                RelationPagingFixture.live().authority.workspaceRoot
            )
        val owner = HostedQueryContinuations()
        owner.forEpoch(epochs.admit(), ReadLimits.Default).value()
        owner.dispose()
        assertEquals(
            Refinement.Rejected(io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure.RETIRED),
            owner.forEpoch(epochs.advance(), ReadLimits.Default),
        )
    }

    private fun assertQueryRetired(
        owner: HostedQueryContinuations.Active,
        queryState: QueryExecutionContinuation.Pipeline,
    ) {
        assertEquals(
            QueryCheckpointRestoration.Unavailable,
            owner.queryState.restoreCheckpoint(queryState, owner.lease),
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
                                bounded(listOf(ProtocolText.parse("main").value())),
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
                        QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
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
