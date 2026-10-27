package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionBudgetCapacity
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryWorkCount
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.QueryExecutionClaim
import io.github.amichne.kast.query.protocol.QueryExecutionPublication
import io.github.amichne.kast.query.protocol.QueryExecutionPublicationResult
import io.github.amichne.kast.query.protocol.QueryInvocationPolicy
import io.github.amichne.kast.query.protocol.QueryPublishedPage
import io.github.amichne.kast.query.protocol.QueryStateStore
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.query.protocol.executeQueryPage
import io.github.amichne.kast.symbol.contract.SymbolDescription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame

internal class HostedCompletionRejectionCase(
    private val fixture: RelationPagingFixture = RelationPagingFixture.published(),
    private val connections: List<io.github.amichne.kast.relation.contract.RelationFact> = emptyList(),
) {
    val store = QueryStateStore(clock = { 0L })
    lateinit var claim: QueryExecutionClaim
    var executions = 0
    private val output = QueryOutputDocument.Symbols(bounded(listOf(QuerySymbolFieldDocument.NAME)))
    private val request =
        QueryRunRequest.Run(
            QueryFromDocument.References(bounded(listOf(QueryReferenceDocument.ExactSymbol(fixture.exact)))),
            bounded(emptyList()),
            output,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )
    private val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(2).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(100_000).refined(),
        )
    private val protocol =
        CanonicalQueryProtocol(
            QueryOperations {
                assertEquals(1, ++executions, "Unexpected semantic execution")
                QueryExecutionResult.Qualified(
                        QueryResult(
                            QueryRows.Symbols.of(
                                listOf(QuerySymbol(SymbolDescription.from(fixture.selector), connections))
                            ),
                            emptyList(),
                        ),
                        QueryCoverage.Qualified.create(
                                QueryCount.parse(1).refined(),
                                setOf(QueryLimitation.RELATION_INCOMPLETE),
                            )
                            .refined(),
                        QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE),
                    )
                    .observedWork(QueryWorkCount.parse(1).refined())
            },
            fixture.references,
            store,
            QueryExecutionPublication { retained, owner, page ->
                assertSame(store, retained)
                assertInstanceOf(OperationOutcome.Rejected::class.java, page)
                claim = owner
                QueryExecutionPublicationResult.PREPARED
            },
        )

    fun budgetReport(): ExecutionBudgetReport {
        val bytes = ReturnedByteLimit.parse(budget.returnedBytes.value).refined()
        return ExecutionBudgetReport.from(
            AdmittedExecutionBudget.admit(
                RequestedExecutionBudget(),
                budget.resources.boundedFixture(),
                bytes,
                budget.resources.boundedFixture(),
                bytes,
                ExecutionBudgetCapacity(
                    budget.resources.boundedFixture().elapsedTimeLimit,
                    budget.resources.boundedFixture().resultLimit,
                    bytes,
                ),
            )
        )
    }

    suspend fun run(
        inlinePresentation: (QueryPublishedPage) -> io.github.amichne.kast.query.protocol.QueryInlinePresentation = {
            io.github.amichne.kast.query.protocol.QueryInlinePresentation.FITS
        }
    ): OperationOutcome.Rejected<QueryRunRejection> {
        val result =
            protocol.execute(
                request,
                fixture.authority,
                budget,
                QueryInvocationPolicy(
                    ResultLimit.parse(2).refined(),
                    QueryByteLimit.parse(100_000).refined(),
                    QueryByteLimit.parse(128_000_000).refined(),
                    CanonicalQueryCliDocuments::previewBytes,
                    nanoTime = { 0L },
                    inlinePresentation = inlinePresentation,
                ),
            )
        assertInstanceOf(OperationOutcome.Rejected::class.java, result)
        val rejection = result as OperationOutcome.Rejected
        assertInstanceOf(QueryRunRejection.CompletionUnproven::class.java, rejection.reason)
        assertInstanceOf(
            QueryCompletionEvidenceDocument.Retained::class.java,
            (rejection.reason as QueryRunRejection.CompletionUnproven).evidence,
        )
        return rejection
    }

    suspend fun read(rejected: OperationOutcome.Rejected<QueryRunRejection>): QueryPublishedPage {
        val reference =
            ((rejected.reason as QueryRunRejection.CompletionUnproven).evidence
                    as QueryCompletionEvidenceDocument.Retained)
                .result
        return CanonicalQueryProtocol(
                QueryOperations {
                    executions++
                    error("Retained reads cannot execute providers")
                },
                fixture.references,
                store,
            )
            .executeQueryPage(
                QueryRunRequest.ReadResult.symbols(reference, output = output),
                fixture.authority,
                budget,
            )
    }

    suspend fun readDetail(
        rejected: OperationOutcome.Rejected<QueryRunRejection>,
        selector: io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument,
    ): QueryPublishedPage {
        val result =
            ((rejected.reason as QueryRunRejection.CompletionUnproven).evidence
                    as QueryCompletionEvidenceDocument.Retained)
                .result
        return CanonicalQueryProtocol(
                QueryOperations {
                    executions++
                    error("A retained detail cannot replay semantic work")
                },
                fixture.references,
                store,
            )
            .executeQueryPage(
                QueryRunRequest.ReadResult.detail(result, selector, output),
                fixture.authority,
                budget,
            )
    }
}

private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejection: $failure")
    }
