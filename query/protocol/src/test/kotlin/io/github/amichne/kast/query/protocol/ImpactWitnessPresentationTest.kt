package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingFailure
import io.github.amichne.kast.protocol.contract.ImpactAccountingStatusDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingViewDocument
import io.github.amichne.kast.protocol.contract.ImpactClosureDocument
import io.github.amichne.kast.protocol.contract.ImpactRequiredObligationDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.contract.presentationSuffix
import io.github.amichne.kast.protocol.contract.validateImpactAccounting
import io.github.amichne.kast.protocol.contract.validateImpactCompletion
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
import io.github.amichne.kast.query.contract.QueryImpactWitnessRecord
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOperations
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ImpactWitnessPresentationTest {
    @Test
    fun `read result exposes original producer invocation and flattened native records without replay`() = runTest {
        val fixture = Fixture()
        var executions = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    executions++
                    error("Unexpected semantic replay")
                },
                fixture.symbols.references,
                fixture.store,
            )
        val output = fixture.readWitness(protocol, ImpactWitnessSectionDocument.NATIVE_READS, 1)
        assertEquals(0, executions)
        assertEquals(fixture.request.let(QueryQuestionDocument::from), output.evidence.payload.question)
        val items = output.evidence.payload.items.values.map { (it as QueryResultItemDocument.ImpactWitness).item }
        assertEquals(listOf(1L, 2L), items.map { it.ordinal.value })
        assertInstanceOf(ImpactWitnessDocument.CompilerTransfer::class.java, items[0].witness)
        assertInstanceOf(ImpactWitnessDocument.NativeRead::class.java, items[1].witness)
        val accounting = output.evidence.payload.impactAccounting as ImpactAccountingDocument.Investigated
        assertEquals(0L, accounting.pagePathCount.value)
        assertEquals(1L, accounting.originalPathCount.value)
        assertEquals(2L, accounting.originalObservationCount.value)
        assertEquals(ImpactAccountingStatusDocument.SelectedSubset(ImpactClosureDocument.Discharged), accounting.status)
        assertEquals(
            ImpactAccountingViewDocument.Witness(
                ImpactWitnessSectionDocument.NATIVE_READS,
                count(1),
                count(3),
                count(3),
            ),
            accounting.view,
        )
        assertEquals(Refinement.Refined(Unit), output.evidence.payload.validateImpactAccounting())
        assertNull(output.evidence.payload.nextCursor)
        val producerPage = fixture.readWitness(protocol, ImpactWitnessSectionDocument.PRODUCERS)
        val producer =
            ((producerPage.evidence.payload.items.values.single() as QueryResultItemDocument.ImpactWitness).item.witness
                as ImpactWitnessDocument.Producer)
        assertEquals(fixture.producer.invocation.impactDocument().value(), producer.invocation)
        assertEquals(0, executions)
    }

    @Test
    fun `same retained result and section preserve stable ordinals empty sections and cursor bounds`() {
        val fixture = Fixture()
        val restored =
            fixture.store.restoreResult(fixture.reference, fixture.symbols.authority) as QueryResultRestoration.Restored
        val request =
            QueryRunRequest.ReadResult.impactWitness(fixture.reference, ImpactWitnessSectionDocument.NATIVE_READS)
        val first = RetainedQueryPresentation.create(restored, request).value()
        val second = RetainedQueryPresentation.create(restored, request).value()
        assertSame(fixture.ledger, (first.result.rows as QueryRows.ImpactWitness).view.ledger)
        assertEquals(
            (first.result.rows as QueryRows.ImpactWitness).values,
            (second.result.rows as QueryRows.ImpactWitness).values,
        )
        assertEquals(emptyList<QueryResultRowReference>(), first.rowIds)
        assertEquals(3, first.window.resultEnd.value)
        val empty =
            RetainedQueryPresentation.create(
                    restored,
                    QueryRunRequest.ReadResult.impactWitness(fixture.reference, ImpactWitnessSectionDocument.MODELS),
                )
                .value()
        assertEquals(emptyList<QueryImpactWitnessRecord>(), (empty.result.rows as QueryRows.ImpactWitness).values)
        assertNotNull(empty.coverage)
        assertEquals(
            Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_CURSOR_OUT_OF_RANGE),
            RetainedQueryPresentation.create(
                restored,
                QueryRunRequest.ReadResult.impactWitness(
                    fixture.reference,
                    ImpactWitnessSectionDocument.NATIVE_READS,
                    QueryResultCursor.parse(4).value(),
                ),
            ),
        )
    }

    @Test
    fun `connectivity only retained paths cannot manufacture original witness ledger`() {
        val fixture = Fixture()
        val weak =
            QueryRetainedResult.capture(
                    fixture.symbols.authority,
                    QueryExecutionResult.Qualified(
                        QueryResult(QueryRows.ValuePaths.of(fixture.ledger.paths), emptyList()),
                        QueryCoverage.Qualified.create(
                                QueryCount.parse(1).value(),
                                setOf(QueryLimitation.IMPACT_COVERAGE_UNPROVEN),
                            )
                            .value(),
                    ),
                )
                .value()
        val issued = fixture.store.issueResult(fixture.request, weak) as QueryResultIssuance.Issued
        val restored =
            fixture.store.restoreResult(issued.reference, fixture.symbols.authority) as QueryResultRestoration.Restored
        assertEquals(
            Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE),
            RetainedQueryPresentation.create(
                restored,
                QueryRunRequest.ReadResult.impactWitness(issued.reference, ImpactWitnessSectionDocument.PRODUCERS),
            ),
        )
    }

    @Test
    fun `path fitting keeps original closure and counts while weakening sliced completion`() = runTest {
        val fixture = Fixture()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Unexpected semantic replay") },
                fixture.symbols.references,
                fixture.store,
            )
        val complete =
            protocol.execute(
                QueryRunRequest.ReadResult.valuePaths(fixture.reference),
                fixture.symbols.authority,
                fixture.budget,
            ) as OperationOutcome.Complete
        val original = complete.evidence.payload
        val prefix = original.presentationPrefix(0).value()
        val accounting = prefix.impactAccounting as ImpactAccountingDocument.Investigated
        assertEquals(0L, accounting.pagePathCount.value)
        assertEquals(1L, accounting.originalPathCount.value)
        assertEquals(2L, accounting.originalObservationCount.value)
        assertEquals(ImpactAccountingStatusDocument.SelectedSubset(ImpactClosureDocument.Discharged), accounting.status)
        assertEquals(QueryResultCursor.Start, prefix.nextCursor)
        assertEquals(Refinement.Refined(Unit), prefix.validateImpactAccounting())
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.COMPLETION_NOT_CONSERVED),
            prefix.validateImpactCompletion(),
        )
        val required = bounded(listOf(ImpactRequiredObligationDocument.NATIVE_FLOW))
        val unresolved =
            original.copy(
                impactAccounting =
                    (original.impactAccounting as ImpactAccountingDocument.Investigated).copy(
                        status = ImpactAccountingStatusDocument.Unresolved(required)
                    )
            )
        val sliced = unresolved.presentationSuffix(1).value().impactAccounting as ImpactAccountingDocument.Investigated
        assertEquals(
            ImpactAccountingStatusDocument.SelectedSubset(ImpactClosureDocument.Unresolved(required)),
            sliced.status,
        )
        assertEquals(0L, sliced.pagePathCount.value)
    }

    @Test
    fun `witness fitting preserves ledger counts and section ordinals across prefix and suffix`() = runTest {
        val fixture = Fixture()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Unexpected semantic replay") },
                fixture.symbols.references,
                fixture.store,
            )
        val page = fixture.readWitness(protocol, ImpactWitnessSectionDocument.NATIVE_READS)
        val original = page.evidence.payload
        assertRejectedWitnessAccounting(original, fixture)
        val prefix = original.presentationPrefix(1).value()
        val suffix = original.presentationSuffix(1).value()
        val originalAccounting = original.impactAccounting as ImpactAccountingDocument.Investigated
        for (result in listOf(prefix, suffix)) {
            val accounting = result.impactAccounting as ImpactAccountingDocument.Investigated
            assertEquals(originalAccounting.originalPathCount, accounting.originalPathCount)
            assertEquals(originalAccounting.originalObservationCount, accounting.originalObservationCount)
            assertEquals(originalAccounting.seeds, accounting.seeds)
            assertEquals(originalAccounting.status, accounting.status)
            assertEquals(0L, accounting.pagePathCount.value)
            assertEquals(Refinement.Refined(Unit), result.validateImpactAccounting())
        }
        assertEquals(
            ImpactAccountingViewDocument.Witness(
                ImpactWitnessSectionDocument.NATIVE_READS,
                count(0),
                count(1),
                count(3),
            ),
            (prefix.impactAccounting as ImpactAccountingDocument.Investigated).view,
        )
        assertEquals(QueryResultCursor.parse(1).value(), prefix.nextCursor)
        assertEquals(
            ImpactAccountingViewDocument.Witness(
                ImpactWitnessSectionDocument.NATIVE_READS,
                count(1),
                count(3),
                count(3),
            ),
            (suffix.impactAccounting as ImpactAccountingDocument.Investigated).view,
        )
        assertNull(suffix.nextCursor)
        assertEquals(
            listOf(1L, 2L),
            suffix.items.values.map { (it as QueryResultItemDocument.ImpactWitness).item.ordinal.value },
        )
    }

    private fun assertRejectedWitnessAccounting(original: QueryRunResult, fixture: Fixture) {
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.MISSING_VALUE_ACCOUNTING),
            original.copy(impactAccounting = ImpactAccountingDocument.NotApplicable).validateImpactAccounting(),
        )
        val wrongRecord =
            (original.items.values.first() as QueryResultItemDocument.ImpactWitness)
                .item
                .copy(witness = ImpactWitnessDocument.ProducerSiteOnly(fixture.producer.site.impactDocument().value()))
        val mixed =
            original.copy(
                items =
                    bounded(listOf(QueryResultItemDocument.ImpactWitness(wrongRecord)) + original.items.values.drop(1))
            )
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            mixed.validateImpactAccounting(),
        )
    }

    private class Fixture {
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
                    ValueRole.ExpressionResult,
                )
                .value()
        private val transfer = ValueTransfer.fromCompiler(site, target, ValueTransferKind.BRANCH_ALTERNATIVE).value()
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

    private fun count(raw: Long) = QueryDiscoveryCountDocument.parse(raw).value()
}

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
