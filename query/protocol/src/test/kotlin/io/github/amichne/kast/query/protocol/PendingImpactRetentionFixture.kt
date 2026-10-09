package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactFlowSemantics
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryPlanAdmission
import io.github.amichne.kast.query.contract.QueryPlanCompiler
import io.github.amichne.kast.query.contract.QueryPlanSyntax
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Detached exact facts exercise production presentation/retention; no compiler or clock effects are permitted. */
internal class PendingImpactRetentionFixture {
    val symbols = RelationPagingFixture.published()
    val invocation =
        ValueInvocation.fromCompiler(
                RelationEndpoint.subject(symbols.selector),
                ExactDeclarationTextRange.parse(0, 1).value(),
                RelationEndpoint.subject(symbols.selector),
            )
            .value()
    val producer = QueryImpactProducer.admit(invocation.resultSite(), invocation).value()
    private val source =
        QueryImpactSource.admit(listOf(producer), emptyList(), emptyList(), RelationSearchBoundary.RETAINED_SUBJECT)
            .value()
    private val plan =
        (QueryPlanCompiler.admit(
                QueryPlanSyntax(QuerySourceSyntax.Impact(source), emptyList(), QueryOutputSyntax.ValuePaths)
            ) as QueryPlanAdmission.Admitted)
            .plan
    val checkpoint =
        object : QueryCheckpoint {
            override val plan = this@PendingImpactRetentionFixture.plan
            override val lease = symbols.authority
            override val retainedBytes = 1024L
        }
    val store = QueryStateStore(clock = { 0 })
    val observed = mutableListOf<QueryResultRetentionEvidence>()
    val projection = QueryOutcomeProjection(symbols.references, store)
    val request =
        QueryRunRequest.Run(
            from =
                QueryFromDocument.Impact(
                    QueryImpactSourceDocument(
                        seeds =
                            bounded(
                                listOf(
                                    QueryImpactProducerDocument(
                                        symbols.exact,
                                        symbols.exact,
                                        ImpactSourceRangeDocument(
                                            ProtocolOffset.parse(0).value(),
                                            ProtocolOffset.parse(1).value(),
                                        ),
                                    )
                                )
                            ),
                        declarations = bounded(emptyList()),
                        domain = QueryExpansionScopeDocument.RetainedSeed,
                        flow = QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                        models = bounded(emptyList()),
                    )
                ),
            steps = bounded(emptyList()),
            output = QueryOutputDocument.ValuePaths,
            execution =
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            retention = QueryRetentionModeDocument.RETAIN,
        )

    fun pending(
        rows: QueryRows.ValuePaths = QueryRows.ValuePaths.of(emptyList()),
        continuation: QueryContinuationState = QueryContinuationState.Resumable(checkpoint),
    ) =
        QueryExecutionResult.Qualified(
            QueryResult(rows, emptyList()),
            QueryCoverage.Qualified.create(
                    QueryCount.parse(rows.values.size).value(),
                    setOf(QueryLimitation.WORK_LIMIT_REACHED),
                )
                .value(),
            continuation,
        )

    fun investigatedRows(): QueryRows.ValuePaths {
        val obligation = ValueFlowObligation(producer.site, ValueFlowUnsupportedCause.UNMODELED_CALL)
        val observation =
            ValueFlowStep.fromCompiler(
                    producer.site,
                    emptyList(),
                    listOf(obligation),
                    ValueFlowTerminal.Unresolved,
                    RelationRequest.start(symbols.selector, RelationMeaning.References, symbols.budget),
                    RelationWorkCount.parse(1).value(),
                )
                .value()
        val ledger =
            QueryImpactLedger.fromEvidence(
                    seeds = listOf(producer.site),
                    domain = RelationSearchBoundary.RETAINED_SUBJECT,
                    semantics = QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                    representationModels = emptyList(),
                    boundaryModels = emptyList(),
                    observations = listOf(observation),
                    paths = listOf(path()),
                    originalProducers = listOf(producer),
                )
                .value()
        return QueryRows.ValuePaths.fromInvestigation(ledger).value()
    }

    fun path(): QueryImpactPath =
        QueryImpactPath.fromEvidence(
                producer = producer.site,
                steps = emptyList(),
                representation = QueryImpactRepresentation.NotModeled,
                terminal =
                    QueryImpactTerminal.Unresolved.Flow(
                        ValueFlowObligation(producer.site, ValueFlowUnsupportedCause.UNMODELED_CALL)
                    ),
            )
            .value()
}

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
