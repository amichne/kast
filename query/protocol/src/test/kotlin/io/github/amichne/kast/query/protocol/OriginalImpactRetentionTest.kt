package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingStatusDocument
import io.github.amichne.kast.protocol.contract.ImpactClosureDocument
import io.github.amichne.kast.protocol.contract.ImpactRequiredObligationDocument
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
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.contract.presentationSuffix
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactFlowSemantics
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryTerminalReason
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
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Starting facts are detached contract evidence; these tests establish no native compiler behavior. */
class OriginalImpactRetentionTest {
    @Test
    fun `zero emitted rows retain original investigated paths for qualified reads without replay`() = runTest {
        val fixture = Fixture()
        val initial = fixture.publish(emptyList())
        val reference = (initial.evidence.payload.retention as QueryResultRetention.Retained).reference
        val restored =
            fixture.store.restoreResult(reference, fixture.symbols.authority) as QueryResultRestoration.Restored
        assertEquals(3, restored.result.rowCount)
        assertEquals(emptyList<QueryResultItemDocument>(), initial.evidence.payload.items.values)
        assertEquals(QueryResultCursor.Start, initial.evidence.payload.nextCursor)
        val page = fixture.read(reference)
        assertEquals(fixture.ledger.paths.map { it.impactDocument().value() }, page.evidence.payload.paths())
        assertEquals(restored.rowIds, page.evidence.payload.items.values.map { it.rowId })
        fixture.assertOriginalEvidence(page.evidence.payload)
    }

    @Test
    fun `initial prefix keeps original row identities across repeated retained reads`() = runTest {
        val fixture = Fixture()
        val initial = fixture.publish(listOf(0))
        val reference = (initial.evidence.payload.retention as QueryResultRetention.Retained).reference
        assertEquals(QueryResultCursor.parse(1).value(), initial.evidence.payload.nextCursor)
        val first = fixture.read(reference)
        val again = fixture.read(reference)
        assertEquals(
            first.evidence.payload.items.values.map { it.rowId },
            again.evidence.payload.items.values.map { it.rowId },
        )
        assertEquals(first.evidence.payload.items.values[0].rowId, initial.evidence.payload.items.values.single().rowId)
        fixture.assertOriginalEvidence(first.evidence.payload)
    }

    @Test
    fun `nonprefix initial selection uses original ordinals rather than compact row positions`() = runTest {
        val fixture = Fixture()
        val initial = fixture.publish(listOf(2, 0))
        val reference = (initial.evidence.payload.retention as QueryResultRetention.Retained).reference
        assertNull(initial.evidence.payload.nextCursor)
        val prefix = initial.evidence.payload.presentationPrefix(1).value()
        assertNull(prefix.nextCursor)
        assertEquals(listOf(initial.evidence.payload.items.values[0].rowId), prefix.items.values.map { it.rowId })
        val fittedSuffix = initial.evidence.payload.presentationSuffix(1).value()
        assertNull(fittedSuffix.nextCursor)
        assertEquals(listOf(initial.evidence.payload.items.values[1].rowId), fittedSuffix.items.values.map { it.rowId })
        val all = fixture.read(reference)
        val originalIds = all.evidence.payload.items.values.map { it.rowId }
        assertEquals(listOf(originalIds[2], originalIds[0]), initial.evidence.payload.items.values.map { it.rowId })
        assertNotEquals(originalIds[0], originalIds[2])
        val suffix = fixture.read(reference, 2)
        assertEquals(listOf(originalIds[2]), suffix.evidence.payload.items.values.map { it.rowId })
        assertEquals(listOf(fixture.ledger.paths[2].impactDocument().value()), suffix.evidence.payload.paths())
    }

    @Test
    fun `generic selected result capture preserves only the explicit selection`() = runTest {
        val fixture = Fixture()
        val original = fixture.publish(listOf(0, 1, 2)).evidence.payload
        val originalReference = (original.retention as QueryResultRetention.Retained).reference
        val source =
            QueryFromDocument.Result(originalReference, bounded(listOf(requireNotNull(original.items.values[2].rowId))))
        val selected = fixture.publish(listOf(2), source).evidence.payload
        val reference = (selected.retention as QueryResultRetention.Retained).reference
        val restored =
            fixture.store.restoreResult(reference, fixture.symbols.authority) as QueryResultRestoration.Restored
        assertEquals(1, restored.result.rowCount)
        val read = fixture.read(reference).evidence.payload
        assertEquals(listOf(fixture.ledger.paths[2].impactDocument().value()), read.paths())
        assertEquals(selected.items.values.single().rowId, read.items.values.single().rowId)
        assertEquals(source, read.question.from)
        assertNull(read.nextCursor)
        val accounting = read.impactAccounting as ImpactAccountingDocument.Investigated
        val status = accounting.status as ImpactAccountingStatusDocument.SelectedSubset
        val originalClosure = status.originalClosure as ImpactClosureDocument.Unresolved
        assertEquals(listOf(ImpactRequiredObligationDocument.NATIVE_FLOW), originalClosure.required.values)
    }

    private class Fixture {
        val symbols = RelationPagingFixture.published()
        val store = QueryStateStore(clock = { 0 })
        private val sites = listOf(0, 2, 4).map(::site)
        private val observations = sites.map(::observation)
        val ledger = ledger()
        private val rows = QueryRows.ValuePaths.fromInvestigation(ledger).value()
        private val request = request()
        private val projection = QueryOutcomeProjection(symbols.references, store)
        private var semanticExecutions = 0
        private val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    semanticExecutions++
                    error("Retained presentation must not execute semantic work")
                },
                symbols.references,
                store,
            )

        fun publish(
            indices: List<Int>,
            from: QueryFromDocument = request.from,
        ): OperationOutcome.Qualified<QueryRunResult, QueryRunQualification> {
            val execution = execution(indices)
            val projected = projection.projectExecution(request.copy(from = from), symbols.authority, execution)
            assertInstanceOf(OperationOutcome.Qualified::class.java, projected)
            val qualified = projected as OperationOutcome.Qualified<QueryRunResult, QueryRunQualification>
            assertEquals(
                QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.OUTPUT_ITEM_TOO_LARGE),
                qualified.qualification.progress,
            )
            return qualified
        }

        fun execution(indices: List<Int>) =
            QueryExecutionResult.Qualified(
                QueryResult(rows.selectRows(indices).value(), emptyList()),
                QueryCoverage.Qualified.create(
                        QueryCount.parse(indices.size).value(),
                        setOf(QueryLimitation.BYTE_LIMIT_REACHED, QueryLimitation.IMPACT_COVERAGE_UNPROVEN),
                    )
                    .value(),
                QueryContinuationState.Terminal(QueryTerminalReason.OUTPUT_ITEM_TOO_LARGE),
            )

        suspend fun read(
            reference: io.github.amichne.kast.protocol.contract.QueryResultReference,
            cursor: Int = 0,
        ): OperationOutcome.Qualified<QueryRunResult, QueryRunQualification> {
            val larger = QueryBudget(symbols.budget.resources, QueryByteLimit.parse(1_000_000).value())
            val read =
                protocol.executePage(
                    QueryRunRequest.ReadResult.valuePaths(reference, QueryResultCursor.parse(cursor).value()),
                    symbols.authority,
                    larger,
                )
            assertEquals(0, semanticExecutions)
            assertInstanceOf(OperationOutcome.Qualified::class.java, read)
            return read as OperationOutcome.Qualified<QueryRunResult, QueryRunQualification>
        }

        fun assertOriginalEvidence(result: QueryRunResult) {
            assertEquals(QueryQuestionDocument.from(request), result.question)
            val accounting = result.impactAccounting as ImpactAccountingDocument.Investigated
            assertEquals(3L, accounting.originalPathCount.value)
            assertEquals(3L, accounting.pagePathCount.value)
            val status = assertInstanceOf(ImpactAccountingStatusDocument.Unresolved::class.java, accounting.status)
            assertEquals(listOf(ImpactRequiredObligationDocument.NATIVE_FLOW), status.required.values)
            assertEquals(sites.map { it.impactDocument().value() }, accounting.seeds.values)
        }

        private fun site(offset: Int): ValueSite =
            ValueSite.fromCompiler(
                    RelationEndpoint.subject(symbols.selector),
                    ExactDeclarationTextRange.parse(offset, offset + 1).value(),
                    ValueRole.ExpressionResult,
                )
                .value()

        private fun observation(site: ValueSite): ValueFlowStep =
            ValueFlowStep.fromCompiler(
                    site,
                    emptyList(),
                    listOf(ValueFlowObligation(site, ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW)),
                    ValueFlowTerminal.Unresolved,
                    RelationRequest.start(symbols.selector, RelationMeaning.References, symbols.budget),
                    RelationWorkCount.parse(1).value(),
                )
                .value()

        private fun ledger(): QueryImpactLedger {
            val paths = observations.map { observation ->
                QueryImpactPath.fromEvidence(
                        observation.source,
                        emptyList(),
                        QueryImpactRepresentation.NotModeled,
                        QueryImpactTerminal.Unresolved.Flow(observation.obligations.single()),
                    )
                    .value()
            }
            val producers = sites.map { site ->
                QueryImpactProducer.admit(
                        site,
                        ValueInvocation.fromCompiler(site.enclosing, site.range, site.enclosing).value(),
                    )
                    .value()
            }
            return QueryImpactLedger.fromEvidence(
                    sites,
                    RelationSearchBoundary.RETAINED_SUBJECT,
                    QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                    emptyList(),
                    emptyList(),
                    observations,
                    paths,
                    originalProducers = producers,
                )
                .value()
        }

        private fun request(): QueryRunRequest.Run {
            val source =
                QueryImpactSourceDocument(
                    bounded(
                        sites.map { site ->
                            QueryImpactProducerDocument(
                                symbols.exact,
                                symbols.exact,
                                ImpactSourceRangeDocument(
                                    ProtocolOffset.parse(site.range.startInclusive).value(),
                                    ProtocolOffset.parse(site.range.endExclusive).value(),
                                ),
                            )
                        }
                    ),
                    bounded(emptyList()),
                    QueryExpansionScopeDocument.RetainedSeed,
                    QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                    bounded(emptyList()),
                )
            return QueryRunRequest.Run(
                QueryFromDocument.Impact(source),
                bounded(emptyList()),
                QueryOutputDocument.ValuePaths,
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
                retention = QueryRetentionModeDocument.RETAIN,
            )
        }
    }
}

private fun QueryRunResult.paths() = items.values.map { (it as QueryResultItemDocument.ValuePath).path }

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
