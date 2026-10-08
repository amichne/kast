package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
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
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertInstanceOf

internal class ImpactWitnessPresentationFixture(resumedBinding: Boolean = false) {
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
    ): OperationOutcome.Qualified<QueryRunResult, QueryRunQualification> =
        protocol
            .execute(
                QueryRunRequest.ReadResult.impactWitness(
                    reference,
                    section,
                    QueryResultCursor.parse(cursor).value(),
                ),
                symbols.authority,
                budget,
            )
            .also { assertInstanceOf(OperationOutcome.Qualified::class.java, it, it.toString()) }
            as OperationOutcome.Qualified<QueryRunResult, QueryRunQualification>

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
                if (resumedBinding) ValueRole.LocalBinding else ValueRole.ExpressionResult,
            )
            .value()
    private val transfer =
        ValueTransfer.fromCompiler(
                site,
                target,
                if (resumedBinding) ValueTransferKind.LOCAL_BINDING else ValueTransferKind.BRANCH_ALTERNATIVE,
            )
            .value()
    private val first =
        ValueFlowStep.fromCompiler(
                site,
                listOf(transfer),
                emptyList(),
                ValueFlowTerminal.SupportedDomainExhausted,
                domain,
                RelationWorkCount.parse(2).value(),
            )
            .value()
    private val last =
        if (resumedBinding) {
            fun page(work: Long, terminal: ValueFlowTerminal): ValueFlowStep {
                val budget =
                    RelationBudget(
                        ResourceBudget(
                            ResultLimit.parse(1).value(),
                            WorkUnitLimit.parse(work).value(),
                            ElapsedTimeLimitMillis.parse(1000).value(),
                        ),
                        RelationByteLimit.parse(100000).value(),
                    )
                val domain =
                    RelationRequest.start(
                        symbols.selector,
                        RelationMeaning.References,
                        budget,
                        RelationSearchBoundary.WORKSPACE_EXPANSION,
                    )
                return ValueFlowStep.fromCompiler(
                        target,
                        emptyList(),
                        emptyList(),
                        terminal,
                        domain,
                        RelationWorkCount.parse(work).value(),
                    )
                    .value()
            }
            page(3, ValueFlowTerminal.ResourceSuspended)
                .append(page(4, ValueFlowTerminal.SupportedDomainExhausted))
                .value()
        } else
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
    val ledger =
        QueryImpactLedger.fromEvidence(
                listOf(site),
                RelationSearchBoundary.WORKSPACE_EXPANSION,
                QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                emptyList(),
                emptyList(),
                listOf(first, last),
                listOf(path),
                originalProducers = listOf(producer),
            )
            .value()
    val request =
        QueryRunRequest.Run(
            QueryFromDocument.References(bounded(listOf(QueryReferenceDocument.ExactSymbol(symbols.exact)))),
            bounded(emptyList()),
            QueryOutputDocument.ValuePaths,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            completion = io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument.Progressive,
        )
    val store = QueryStateStore(clock = { 0 })
    private val execution =
        QueryExecutionResult.Complete.create(
            QueryResult(QueryRows.ValuePaths.fromInvestigation(ledger).value(), emptyList()),
            QueryCoverage.Complete(QueryCount.parse(1).value()),
        )
    private val retained = QueryRetainedResult.capture(symbols.authority, execution).value()
    val reference = (store.issueResult(request, retained) as QueryResultIssuance.Issued).reference
}

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
