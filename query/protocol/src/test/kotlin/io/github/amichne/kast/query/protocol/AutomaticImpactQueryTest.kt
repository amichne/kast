package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingStatusDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactRequiredObligationDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument
import io.github.amichne.kast.protocol.contract.QueryInvestigationCompletionFailureDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryPresentationExecution
import io.github.amichne.kast.query.service.QueryNanoClock
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueProducerSeed
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalOperations
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Production scheduler and ledger; only native observations are scripted. No native completeness claim. */
internal class AutomaticImpactQueryTest : AutomaticSymbolQueryCase() {
    @Test
    fun `strict original impact completion is independent of presentation page grant`() = runTest {
        val small = Case(1).run()
        val large = Case(20).run()
        assertEquals(large, small)
        assertEquals(3, small.size)
    }

    @Test
    fun `strict witness derives original finding identities and preserves the question without semantic replay`() =
        runTest {
            for (section in ImpactWitnessSectionDocument.entries) {
                val case = Case(1)
                val requested = case.request.copy(output = QueryOutputDocument.ImpactWitness(section))
                val page = case.automatic(requested)
                val complete = assertInstanceOf(OperationOutcome.Complete::class.java, page, page.toString())
                val result = complete.evidence.payload as QueryRunResult
                assertEquals(QueryQuestionDocument.from(requested), result.question)
                val retained = result.retention as QueryResultRetention.Retained
                val calls = case.reads
                val read =
                    case.protocol.executePage(
                        QueryRunRequest.ReadResult.impactWitness(retained.reference, section),
                        fixture.authority,
                        case.allowance.copy(
                            resources = case.allowance.resources.copy(resultLimit = ResultLimit.parse(20).refined())
                        ),
                    )
                val full = assertInstanceOf(OperationOutcome.Complete::class.java, read)
                val body = full.evidence.payload as QueryRunResult
                assertEquals(QueryQuestionDocument.from(requested), body.question)
                assertEquals(calls, case.reads)
                if (section == ImpactWitnessSectionDocument.FINDINGS) {
                    assertEquals(3, body.items.values.size)
                    val ids =
                        body.items.values.map {
                            ((it as QueryResultItemDocument.ImpactWitness).item.witness
                                    as ImpactWitnessDocument.Finding)
                                .finding
                                .path
                                .pathRowId
                        }
                    assertEquals(3, ids.distinct().size)
                }
                case.assertAlternateSection(retained.reference)
                assertEquals(calls, case.reads)
                case.assertDrained()
            }
        }

    @Test
    fun `unresolved native flow rejects strict completion and retains original obligations`() = runTest {
        val case = Case(1, unresolved = true)
        val page = case.automatic()
        val rejected = assertInstanceOf(OperationOutcome.Rejected::class.java, page)
        val failure = assertInstanceOf(QueryRunRejection.CompletionUnproven::class.java, rejected.reason)
        val cause = assertInstanceOf(QueryCompletionCauseDocument.InvestigationUnproven::class.java, failure.cause)
        val unresolved =
            assertInstanceOf(
                QueryInvestigationCompletionFailureDocument.ObligationsUnresolved::class.java,
                cause.investigationFailure,
            )
        assertEquals(listOf(ImpactRequiredObligationDocument.NATIVE_FLOW), unresolved.required.values)
        val retained = assertInstanceOf(QueryCompletionEvidenceDocument.Retained::class.java, failure.evidence)
        val read =
            case.protocol.executePage(
                QueryRunRequest.ReadResult.valuePaths(retained.result),
                fixture.authority,
                case.allowance.copy(
                    resources = case.allowance.resources.copy(resultLimit = ResultLimit.parse(20).refined())
                ),
            )
        val qualified = assertInstanceOf(OperationOutcome.Qualified::class.java, read)
        val result = qualified.evidence.payload as QueryRunResult
        val accounting = result.impactAccounting as ImpactAccountingDocument.Investigated
        assertEquals(3L, accounting.originalPathCount.value)
        assertInstanceOf(ImpactAccountingStatusDocument.Unresolved::class.java, accounting.status)
        assertInstanceOf(QueryResultInterpretationDocument.EvidenceOnly::class.java, result.interpretation)
        case.assertDrained()
    }

    @Test
    fun `stopping before the final presentation page preserves original accounting but rejects completion`() = runTest {
        val case = Case(1, work = 8)
        val page = case.automatic()
        val rejected = assertInstanceOf(OperationOutcome.Rejected::class.java, page)
        val failure = assertInstanceOf(QueryRunRejection.CompletionUnproven::class.java, rejected.reason)
        assertEquals(QueryInvocationStop.WORK_LIMIT, failure.stop)
        val cause = assertInstanceOf(QueryCompletionCauseDocument.InvestigationUnproven::class.java, failure.cause)
        assertInstanceOf(
            QueryInvestigationCompletionFailureDocument.SelectionIncomplete::class.java,
            cause.investigationFailure,
        )
        val retained = assertInstanceOf(QueryCompletionEvidenceDocument.Retained::class.java, failure.evidence)
        val read =
            case.protocol.executePage(
                QueryRunRequest.ReadResult.valuePaths(retained.result),
                fixture.authority,
                case.allowance.copy(
                    resources = case.allowance.resources.copy(resultLimit = ResultLimit.parse(20).refined())
                ),
            )
        val qualified = assertInstanceOf(OperationOutcome.Qualified::class.java, read)
        val body = qualified.evidence.payload as QueryRunResult
        assertEquals(3L, (body.impactAccounting as ImpactAccountingDocument.Investigated).originalPathCount.value)
        assertEquals(3, body.items.values.size)
        case.assertDrained()
    }

    @Test
    fun `strict retained original value paths complete across pages without new semantic reads`() = runTest {
        val case = Case(1)
        val first = assertInstanceOf(OperationOutcome.Complete::class.java, case.automatic())
        val reference =
            ((first.evidence.payload as QueryRunResult).retention as QueryResultRetention.Retained).reference
        val repeated = case.automatic(case.request.copy(from = QueryFromDocument.Result(reference)))
        assertInstanceOf(OperationOutcome.Complete::class.java, repeated, repeated.toString())
        case.assertDrained()
    }

    private inner class Case(pageRows: Int, private val unresolved: Boolean = false, work: Long = 100) {
        val allowance =
            budget.copy(
                resources =
                    budget.resources.copy(
                        resultLimit = ResultLimit.parse(pageRows).refined(),
                        workUnitLimit = WorkUnitLimit.parse(work).refined(),
                    ),
                returnedBytes = QueryByteLimit.parse(1_000_000).refined(),
                checkpointBytes = QueryByteLimit.parse(5_000_000).refined(),
            )
        private val invocations =
            (0..2).map { offset ->
                ValueInvocation.fromCompiler(
                        RelationEndpoint.subject(fixture.selector),
                        ExactDeclarationTextRange.parse(offset * 2, offset * 2 + 1).refined(),
                        RelationEndpoint.subject(fixture.selector),
                    )
                    .refined()
            }
        private val sites = invocations.map { it.resultSite() }
        private val state = QueryStateStore()
        private var seeds = 0
        var reads = 0
            private set

        private var unexpected = 0
        val request =
            QueryRunRequest.Run(
                QueryFromDocument.Impact(
                    QueryImpactSourceDocument(
                        seeds =
                            bounded(
                                invocations.map { invocation ->
                                    QueryImpactProducerDocument(
                                        fixture.exact,
                                        fixture.exact,
                                        ImpactSourceRangeDocument(
                                            ProtocolOffset.parse(invocation.range.startInclusive).refined(),
                                            ProtocolOffset.parse(invocation.range.endExclusive).refined(),
                                        ),
                                    )
                                }
                            ),
                        declarations = bounded(emptyList()),
                        domain = QueryExpansionScopeDocument.RetainedSeed,
                        flow = QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                        models = bounded(emptyList()),
                    )
                ),
                bounded(emptyList()),
                QueryOutputDocument.ValuePaths,
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
                retention = QueryRetentionModeDocument.RETAIN,
            )

        private fun service(presentation: QueryPresentationExecution) =
            QueryService(
                SymbolDiscoveryOperations {
                    unexpected++
                    error("Unexpected discovery")
                },
                object : SymbolExactOperations {
                    override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult {
                        unexpected++
                        error("Unexpected resolution")
                    }

                    override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult {
                        unexpected++
                        error("Unexpected description")
                    }
                },
                SourceReadOperations {
                    unexpected++
                    error("Unexpected source")
                },
                RelationOperations {
                    unexpected++
                    error("Unexpected relation")
                },
                TraversalOperations {
                    unexpected++
                    error("Unexpected traversal")
                },
                TraversalBudget(
                    allowance.resources.resultLimit,
                    TraversalByteLimit.parse(1_000_000).refined(),
                    allowance.resources.workUnitLimit,
                    allowance.resources.elapsedTimeLimit,
                    TraversalDepthLimit.parse(1).refined(),
                    TraversalFrontierLimit.parse(100).refined(),
                    fixture.budget,
                ),
                QueryNanoClock { 0L },
                nativeFlow(),
                presentation,
            )

        private fun nativeFlow() = ValueFlowCompilerPort { input ->
            if (reads >= sites.size) {
                unexpected++
                error("Excess native observation")
            }
            assertEquals(sites[reads++], input.source)
            val obligations =
                if (unresolved)
                    listOf(ValueFlowObligation(input.source, ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW))
                else emptyList()
            ValueFlowRead.Observed(
                ValueFlowStep.fromCompiler(
                        input.source,
                        emptyList(),
                        obligations,
                        if (unresolved) ValueFlowTerminal.Unresolved else ValueFlowTerminal.SupportedDomainExhausted,
                        RelationRequest.start(
                            fixture.selector,
                            RelationMeaning.References,
                            input.budget,
                            input.boundary,
                        ),
                        RelationWorkCount.parse(1).refined(),
                    )
                    .refined()
            )
        }

        lateinit var protocol: CanonicalQueryProtocol
            private set

        private fun protocol(presentation: QueryPresentationExecution) =
            CanonicalQueryProtocol(
                service(presentation),
                fixture.references,
                state,
                producerSeeds =
                    object : ValueProducerSeedCompilerPort {
                        override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                            if (seeds >= sites.size) {
                                unexpected++
                                error("Excess seed")
                            }
                            val index = seeds++
                            assertEquals(invocations[index].range, request.anchor)
                            return ValueProducerSeedRead.Seeded(
                                ValueProducerSeed.fromCompiler(request, sites[index], invocations[index]).refined(),
                                RelationWorkCount.parse(1).refined(),
                            )
                        }

                        override suspend fun revalidate(
                            selector: SymbolSelector,
                            budget: RelationBudget,
                        ): ValueModelDeclarationRead {
                            unexpected++
                            error("Unexpected model revalidation")
                        }
                    },
            )

        suspend fun automatic(requested: QueryRunRequest.Run = request): QueryPublishedPage =
            QueryPresentationExecution.evaluateAndFit(
                evaluate = { presentation ->
                    protocol = protocol(presentation)
                    protocol.execute(
                        requested,
                        fixture.authority,
                        allowance,
                        policy(rows = 1, retainedBytes = 10_000_000),
                    )
                },
                fit = { page ->
                    val projected = CanonicalQueryCliDocuments.project(page)
                    val document =
                        when (projected) {
                            is ProjectedOperationOutcome.Complete -> projected.document
                            is ProjectedOperationOutcome.Qualified -> projected.document
                            is ProjectedOperationOutcome.Rejected -> projected.document
                        }
                    if (page is OperationOutcome.Complete)
                        assertInstanceOf(ProjectedOperationOutcome.Complete::class.java, projected, document.value)
                    assertTrue(document.value.toByteArray(Charsets.UTF_8).size <= allowance.returnedBytes.value)
                    page
                },
            )

        suspend fun run(): List<ImpactPathDocument> {
            val page = automatic()
            val complete = assertInstanceOf(OperationOutcome.Complete::class.java, page, page.toString())
            val result = complete.evidence.payload as QueryRunResult
            val reference = (result.retention as QueryResultRetention.Retained).reference
            val read =
                protocol.executePage(
                    QueryRunRequest.ReadResult.valuePaths(reference),
                    fixture.authority,
                    allowance.copy(resources = allowance.resources.copy(resultLimit = ResultLimit.parse(20).refined())),
                )
            val full = assertInstanceOf(OperationOutcome.Complete::class.java, read)
            assertDrained()
            return (full.evidence.payload as QueryRunResult).items.values.map {
                (it as QueryResultItemDocument.ValuePath).path
            }
        }

        suspend fun assertAlternateSection(reference: QueryResultReference) {
            val other =
                protocol.executePage(
                    QueryRunRequest.ReadResult.impactWitness(
                        reference,
                        ImpactWitnessSectionDocument.PRODUCERS,
                    ),
                    fixture.authority,
                    allowance,
                )
            val otherPage = assertInstanceOf(OperationOutcome.Complete::class.java, other)
            assertInstanceOf(
                ProjectedOperationOutcome.Complete::class.java,
                CanonicalQueryCliDocuments.project(other),
            )
        }

        fun assertDrained() {
            assertEquals(0, unexpected)
            assertEquals(3, seeds)
            assertEquals(3, reads)
        }
    }
}
