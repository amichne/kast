package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryWorkCount
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.QueryInvocationPolicy
import io.github.amichne.kast.query.protocol.QueryPublishedPage
import io.github.amichne.kast.query.protocol.QueryStateStore
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.query.protocol.executeQueryPage
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadPublicationFixture
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf

/** Actual protocol/session/store/encoder, with one scripted semantic observation and an owned detached read basis. */
internal class HostedAutomaticPublicationCase(retainedRows: Boolean = true) {
    val hosted = HostedReadPublicationFixture()
    private val fixture = RelationPagingFixture(hosted.context.authority)
    private val output = QueryOutputDocument.Symbols(bounded(listOf(QuerySymbolFieldDocument.NAME)))
    private val request =
        QueryRunRequest.Run(
            QueryFromDocument.References(bounded(listOf(QueryReferenceDocument.ExactSymbol(fixture.exact)))),
            bounded(emptyList()),
            output,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )
    // Independent serialized request size makes room for the checkpoint and initial claim, but no retained rows.
    private val checkpointBytes =
        Json.encodeToString(QueryRunRequest.serializer(), request.copy(executionBudget = null))
            .toByteArray(Charsets.UTF_8)
            .size * 4L + 100L
    var now = 0L
    val store =
        QueryStateStore(
            maximumBytes = if (retainedRows) 64_000_000L else checkpointBytes + 256L,
            clock = { now },
            ttlMillis = 1,
        )
    val publication = HostedQueryPublicationSession(hosted.context, request)
    var executions = 0
        private set

    private val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(2).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(100_000).refined(),
        )
    private val operations = QueryOperations { execution ->
        assertEquals(1, ++executions, "Cancellation must prevent another semantic observation")
        assertEquals(hosted.context.authority, execution.lease)
        QueryExecutionResult.Qualified(
                QueryResult(
                    QueryRows.Symbols.of(listOf(QuerySymbol(SymbolDescription.from(fixture.selector), emptyList()))),
                    emptyList(),
                ),
                QueryCoverage.Qualified.create(
                        QueryCount.parse(1).refined(),
                        setOf(QueryLimitation.RESULT_LIMIT_REACHED),
                    )
                    .refined(),
                QueryContinuationState.Resumable(Checkpoint(execution.plan, execution.lease)),
            )
            .observedWork(QueryWorkCount.parse(7).refined())
    }

    suspend fun run(): QueryPublishedPage =
        CanonicalQueryProtocol(operations, fixture.references, store, publication)
            .execute(
                request,
                hosted.context.authority,
                budget,
                QueryInvocationPolicy(
                    ResultLimit.parse(2).refined(),
                    QueryByteLimit.parse(100_000).refined(),
                    QueryByteLimit.parse(1_000_000).refined(),
                    CanonicalQueryCliDocuments::previewBytes,
                    nanoTime = { 0L },
                    cancelled = { executions == 1 },
                ),
            )

    fun rejection(page: QueryPublishedPage): QueryRunRejection.CompletionUnproven =
        assertInstanceOf(
            QueryRunRejection.CompletionUnproven::class.java,
            assertInstanceOf(OperationOutcome.Rejected::class.java, page).reason,
        )

    fun token(page: QueryPublishedPage): QueryExecutionContinuation.Pipeline =
        assertInstanceOf(QueryCompletionCoverageDocument.Qualified::class.java, rejection(page).originalCoverage)
            .progress
            .continuationToken as QueryExecutionContinuation.Pipeline

    fun fit(page: QueryPublishedPage) {
        assertInstanceOf(
            HostedResponse.Canonical::class.java,
            encodeHostedQueryResponse(page, published = publication::fitted),
        )
    }

    fun restore(token: QueryExecutionContinuation.Pipeline) = store.restoreCheckpoint(token, hosted.context.authority)

    suspend fun read(result: QueryResultReference): QueryPublishedPage =
        CanonicalQueryProtocol(
                QueryOperations { error("Retained reads cannot execute semantic operations") },
                fixture.references,
                store,
            )
            .executeQueryPage(
                QueryRunRequest.ReadResult.symbols(result, output = output),
                hosted.context.authority,
                budget,
            )

    private data class Checkpoint(
        override val plan: AdmittedQueryPlan,
        override val lease: SemanticReadAuthority,
    ) : QueryCheckpoint {
        override val retainedBytes = 100L
    }
}

private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Hosted automatic fixture rejection: $failure")
    }
