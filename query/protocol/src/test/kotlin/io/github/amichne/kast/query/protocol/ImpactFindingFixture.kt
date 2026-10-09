package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
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
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactFlowSemantics
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactRequestedSite
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertInstanceOf

internal class ImpactFindingFixture(withRequestedSites: Boolean = false) {
    val symbols = RelationPagingFixture.published()
    val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(10).value(),
                WorkUnitLimit.parse(100).value(),
                ElapsedTimeLimitMillis.parse(1000).value(),
            ),
            QueryByteLimit.parse(100000).value(),
        )

    suspend fun readWitness(
        protocol: CanonicalQueryProtocol,
        section: ImpactWitnessSectionDocument,
        cursor: Int = 0,
    ): OperationOutcome.Complete<QueryRunResult> =
        readWitnessPage(protocol, section, cursor).also {
            assertInstanceOf(OperationOutcome.Complete::class.java, it, it.toString())
        } as OperationOutcome.Complete<QueryRunResult>

    suspend fun readQualifiedWitness(
        protocol: CanonicalQueryProtocol,
        section: ImpactWitnessSectionDocument,
        cursor: Int = 0,
    ): OperationOutcome.Qualified<QueryRunResult, QueryRunQualification> =
        readWitnessPage(protocol, section, cursor).also {
            assertInstanceOf(OperationOutcome.Qualified::class.java, it, it.toString())
        } as OperationOutcome.Qualified<QueryRunResult, QueryRunQualification>

    private suspend fun readWitnessPage(
        protocol: CanonicalQueryProtocol,
        section: ImpactWitnessSectionDocument,
        cursor: Int,
    ): QueryPublishedPage =
        protocol.executePage(
            QueryRunRequest.ReadResult.impactWitness(reference, section, QueryResultCursor.parse(cursor).value()),
            symbols.authority,
            budget,
        )

    private val domain =
        RelationRequest.start(
            symbols.selector,
            RelationMeaning.References,
            RelationBudget(budget.resources, RelationByteLimit.parse(100000).value()),
            RelationSearchBoundary.WORKSPACE_EXPANSION,
        )
    private val site =
        ValueSite.fromCompiler(
                domain.subject,
                ExactDeclarationTextRange.parse(1, 2).value(),
                ValueRole.ExpressionResult,
            )
            .value()
    val producer =
        QueryImpactProducer.admit(
                site,
                ValueInvocation.fromCompiler(site.enclosing, site.range, site.enclosing).value(),
            )
            .value()
    private val target =
        ValueSite.fromCompiler(
                domain.subject,
                ExactDeclarationTextRange.parse(3, 4).value(),
                ValueRole.ExpressionResult,
            )
            .value()
    private val transfer = ValueTransfer.fromCompiler(site, target, ValueTransferKind.BRANCH_ALTERNATIVE).value()
    private val middle =
        ValueSite.fromCompiler(
                domain.subject,
                ExactDeclarationTextRange.parse(5, 6).value(),
                ValueRole.ExpressionResult,
            )
            .value()
    private val alternative = ValueTransfer.fromCompiler(site, middle, ValueTransferKind.BRANCH_ALTERNATIVE).value()
    private val arrival = ValueTransfer.fromCompiler(middle, target, ValueTransferKind.BRANCH_ALTERNATIVE).value()
    private val middleRead =
        ValueFlowStep.fromCompiler(
                middle,
                listOf(arrival),
                emptyList(),
                ValueFlowTerminal.SupportedDomainExhausted,
                domain,
                RelationWorkCount.parse(1).value(),
            )
            .value()
    private val first =
        ValueFlowStep.fromCompiler(
                site,
                listOf(transfer, alternative),
                emptyList(),
                ValueFlowTerminal.SupportedDomainExhausted,
                domain,
                RelationWorkCount.parse(2).value(),
            )
            .value()
    private val last =
        ValueFlowStep.fromCompiler(
                target,
                emptyList(),
                emptyList(),
                ValueFlowTerminal.SupportedDomainExhausted,
                domain,
                RelationWorkCount.parse(1).value(),
            )
            .value()
    private val path =
        QueryImpactPath.fromEvidence(
                site,
                listOf(QueryImpactStep.Compiler(transfer)),
                QueryImpactRepresentation.NotModeled,
                QueryImpactTerminal.SupportedDomainEnd.admit(last).value(),
            )
            .value()
    private val alternatePath =
        QueryImpactPath.fromEvidence(
                site,
                listOf(QueryImpactStep.Compiler(alternative), QueryImpactStep.Compiler(arrival)),
                QueryImpactRepresentation.NotModeled,
                QueryImpactTerminal.SupportedDomainEnd.admit(last).value(),
            )
            .value()
    val requestedSites =
        if (withRequestedSites)
            listOf(
                    target,
                    ValueSite.fromCompiler(
                            domain.subject,
                            ExactDeclarationTextRange.parse(4, 5).value(),
                            ValueRole.ExpressionResult,
                        )
                        .value(),
                )
                .map { exact ->
                    val request =
                        ValueSiteRevalidationRequest.create(
                                symbols.selector,
                                exact.range,
                                ValueSiteRoleClaim.ExpressionResult,
                                domain.budget,
                            )
                            .value()
                    QueryImpactRequestedSite.admit(
                            request,
                            RevalidatedValueSite.fromCompiler(request, exact).value(),
                            RelationWorkCount.parse(1).value(),
                        )
                        .value()
                }
        else emptyList()
    val ledger =
        QueryImpactLedger.fromEvidence(
                listOf(site),
                RelationSearchBoundary.WORKSPACE_EXPANSION,
                QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                emptyList(),
                emptyList(),
                listOf(first, middleRead, last),
                listOf(path, alternatePath),
                originalProducers = listOf(producer),
                requestedSites = requestedSites,
            )
            .value()
    val request =
        QueryRunRequest.Run(
            QueryFromDocument.Impact(
                QueryImpactSourceDocument(
                    bounded(
                        listOf(
                            QueryImpactProducerDocument(
                                symbols.exact,
                                symbols.exact,
                                ImpactSourceRangeDocument(
                                    ProtocolOffset.parse(site.range.startInclusive).value(),
                                    ProtocolOffset.parse(site.range.endExclusive).value(),
                                ),
                            )
                        )
                    ),
                    bounded(emptyList()),
                    QueryExpansionScopeDocument.Workspace,
                    QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                    bounded(emptyList()),
                    bounded(requestedSites.map { it.site.impactDocument().value() }),
                )
            ),
            bounded(emptyList()),
            QueryOutputDocument.ValuePaths,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            retention = QueryRetentionModeDocument.RETAIN,
        )
    val store = QueryStateStore(clock = { 0 })
    private val execution =
        if (withRequestedSites)
            QueryExecutionResult.Qualified(
                QueryResult(QueryRows.ValuePaths.fromInvestigation(ledger).value(), emptyList()),
                QueryCoverage.Qualified.create(
                        QueryCount.parse(2).value(),
                        setOf(QueryLimitation.IMPACT_COVERAGE_UNPROVEN),
                    )
                    .value(),
            )
        else
            QueryExecutionResult.Complete.create(
                QueryResult(QueryRows.ValuePaths.fromInvestigation(ledger).value(), emptyList()),
                QueryCoverage.Complete(QueryCount.parse(2).value()),
            )
    private val retained = QueryRetainedResult.capture(symbols.authority, execution).value()
    val reference = (store.issueResult(request, retained) as QueryResultIssuance.Issued).reference
}

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
