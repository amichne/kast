package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.query.contract.*
import io.github.amichne.kast.query.service.QueryNanoClock
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.*
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.*
import io.github.amichne.kast.traversal.contract.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Production evaluator, automatic runner and store; only external semantic observations are scripted. */
internal class AutomaticDenseRetentionTest : AutomaticSymbolQueryCase() {
    private val unexpectedCalls = mutableListOf<String>()

    private fun unexpected(boundary: String): Nothing {
        unexpectedCalls += boundary
        error("Unexpected $boundary")
    }

    @AfterEach
    fun `external observation violations cannot be swallowed by production`() {
        assertEquals(emptyList<String>(), unexpectedCalls)
    }

    private val locators =
        (0 until 1_001).map { ordinal ->
            val start = 100 + ordinal * 32
            RelationProviderLocator.Reference(
                fixture.selector.file,
                ExactDeclarationTextRange.parse(start, start + 20).refined(),
                RelationProviderItemDescriptor.parse("dense-reference:$start").refined(),
            )
        }
    private val inventory = RelationProviderState.references(locators)
    private val positions = mutableListOf<Long>()
    private val checkpointAdmissions = mutableListOf<QueryCheckpointStorageAdmission>()
    private val input =
        request.copy(
            output = QueryOutputDocument.Occurrences,
            steps = bounded(listOf(QueryStepDocument.Related(RelationKindDocument.REFERENCES))),
        )
    private val grant =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(20).refined(),
                WorkUnitLimit.parse(100_000).refined(),
                ElapsedTimeLimitMillis.parse(10_000).refined(),
            ),
            QueryByteLimit.parse(49_152).refined(),
        )
    private val service =
        QueryService(
            discovery = SymbolDiscoveryOperations { unexpected("discovery") },
            exact =
                object : SymbolExactOperations {
                    override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult =
                        unexpected("resolution")

                    override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult =
                        SymbolDescriptionResult.Described(SymbolDescription.from(request.selector))
                },
            source = SourceReadOperations { unexpected("source read") },
            relations = RelationOperations(::read),
            traversal = TraversalOperations { unexpected("traversal") },
            traversalCeiling =
                TraversalBudget(
                    grant.resources.resultLimit,
                    TraversalByteLimit.parse(100_000).refined(),
                    grant.resources.workUnitLimit,
                    grant.resources.elapsedTimeLimit,
                    TraversalDepthLimit.parse(1).refined(),
                    TraversalFrontierLimit.parse(100).refined(),
                    fixture.budget,
                ),
            clock = QueryNanoClock { 0L },
            checkpointObservation = QueryCheckpointStorageObservation(checkpointAdmissions::add),
        )

    @Test
    fun `dense accounting preserves finite capacity rejection under unchanged grants`() = runTest {
        val state = QueryStateStore(clock = { 0L })
        val ledger = mutableListOf<QueryInvocationRetentionAdmission>()
        val base = policy(rows = 20, byteLimit = 32_768, retainedBytes = QueryByteLimit.DefaultCheckpoint.value)
        val invocationPolicy =
            QueryInvocationPolicy(
                base.previewRows,
                base.previewBytesLimit,
                base.retainedBytes,
                base.previewBytes,
                nanoTime = { 0L },
                retentionObservation = QueryInvocationRetentionObservation(ledger::add),
            )
        val recording = QueryInvocationExecution(service)
        val protocol = CanonicalQueryProtocol(recording, fixture.references, state)
        val issued = mutableSetOf<QueryExecutionContinuation>()
        val pages = mutableListOf<SymbolInvocationPage>()
        val runner =
            AutomaticSymbolQueryRunner(state, fixture.authority, invocationPolicy) { action, remaining ->
                val observed = recording.page(remaining) { protocol.executePage(action, fixture.authority, remaining) }
                val admitted = SymbolInvocationPage.admit(observed, input)
                if (admitted is Refinement.Refined) {
                    val page = admitted.value
                    page.issuedToken?.let(issued::add)
                    pages += page
                }
                observed
            }
        val accumulated = runner.run(input, grant).refined()
        val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, accumulated.execution)
        assertEquals(QueryInvocationStop.TERMINAL_INCOMPLETE, accumulated.stop)
        assertEquals(
            QueryContinuationState.Terminal(QueryTerminalReason.CHECKPOINT_CAPACITY_EXCEEDED),
            qualified.continuation,
        )
        val rows = assertInstanceOf(QueryRows.Occurrences::class.java, qualified.result.rows).values
        val starts = rows.map { (it as QueryOccurrence.Reference).value.occurrence.range.startInclusive }
        assertEquals(starts.size, starts.distinct().size)
        assertTrue(starts.isNotEmpty() && starts.size < locators.size)
        assertTrue(locators.map { it.range.startInclusive }.containsAll(starts))
        assertEquals(starts.size, qualified.coverage.knownMinimum.value)
        assertEquals((0 until positions.size).map { it * 20L }, positions)
        assertEquals(QueryCheckpointStorageOutcome.CAPACITY_EXCEEDED, checkpointAdmissions.last().outcome)
        assertTrue(checkpointAdmissions.all { it.allowance.value <= grant.checkpointBytes.value })
        assertTrue(ledger.all { it.total.value <= invocationPolicy.retainedBytes.value })
        assertTrue(ledger.all { it.total.value == it.facts.value + it.issuedState.value + it.request.value })
        assertTrue(ledger.all { it.request.value == input.accountedRequestBytes() })

        // Compare exactly the same semantic prefix, not terminal totals from different workloads.
        val prefix = pages.take(2)
        val prefixReceipt = ledger.filter { it.stage == QueryInvocationRetentionStage.BEFORE_EXECUTION }[2]
        val standalonePrefix = prefix.sumOf { page ->
            QueryRetainedResult.capture(fixture.authority, page.execution).refined().retainedBytes +
                invocationPolicy.previewBytes(page.items) +
                page.items.size * QUERY_ROW_REFERENCE_CHARGE_BYTES
        }
        assertTrue(
            prefixReceipt.facts.value < standalonePrefix - inventory.retainedBytes / 2,
            "Shared live inventory was repeatedly charged: ${prefixReceipt.facts.value} vs $standalonePrefix",
        )
        assertTrue(prefixReceipt.issuedState.value > 0L, "Replay owners must remain charged")
    }

    @Test
    fun `dense capacity rejection retains an evidence only prefix without replaying providers`() = runTest {
        val state = QueryStateStore(clock = { 0L })
        val protocol = CanonicalQueryProtocol(service, fixture.references, state)
        val rejection =
            completionRejection(
                protocol.execute(
                    input,
                    fixture.authority,
                    grant,
                    policy(rows = 20, byteLimit = 32_768, retainedBytes = QueryByteLimit.DefaultCheckpoint.value),
                )
            )
        val retained = retainedEvidence(rejection)
        val known = originalCoverage(rejection).knownMinimum.value
        assertTrue(known > 0 && known < locators.size)
        val providerCalls = positions.toList()
        val firstRequest = QueryRunRequest.ReadResult.occurrences(retained.result, QueryResultCursor.parse(0).refined())
        val first = protocol.executePage(firstRequest, fixture.authority, grant)
        assertEquals(first, protocol.executePage(firstRequest, fixture.authority, grant))
        val items = mutableListOf<QueryResultItemDocument>()
        var next: QueryPublishedPage = first
        repeat(locators.size) {
            val qualified = assertInstanceOf(OperationOutcome.Qualified::class.java, next)
            val payload = qualified.evidence.payload as QueryRunResult
            val interpretation =
                assertInstanceOf(QueryResultInterpretationDocument.EvidenceOnly::class.java, payload.interpretation)
            assertEquals(rejection.originalCoverage, interpretation.originalCoverage)
            items += payload.items.values
            val cursor = payload.nextCursor
            if (cursor == null) {
                assertEquals(known, items.size)
                assertEquals(items.size, items.map { it.rowId }.distinct().size)
                assertEquals(providerCalls, positions)
                return@runTest
            }
            next =
                protocol.executePage(
                    QueryRunRequest.ReadResult.occurrences(retained.result, cursor),
                    fixture.authority,
                    grant,
                )
        }
        error("Finite retained prefix exceeded the independent inventory ceiling")
    }

    @Test
    fun `production facts charge a shared inventory owner once across a matched page prefix`() = runTest {
        val state = QueryStateStore(clock = { 0L })
        val recording = QueryInvocationExecution(service)
        val protocol = CanonicalQueryProtocol(recording, fixture.references, state)
        val first =
            SymbolInvocationPage.admit(
                    recording.page(grant) { protocol.executePage(input, fixture.authority, grant) },
                    input,
                )
                .refined()
        val second =
            SymbolInvocationPage.admit(
                    recording.page(grant) {
                        protocol.executePage(
                            QueryRunRequest.Resume(requireNotNull(first.issuedToken)),
                            fixture.authority,
                            grant,
                        )
                    },
                    input,
                )
                .refined()
        val invocationPolicy = policy()
        val facts = QueryInvocationFacts(fixture.authority, invocationPolicy)
        facts.append(first, Long.MAX_VALUE).refined()
        facts.append(second, Long.MAX_VALUE).refined()
        val standalone =
            listOf(first, second).sumOf { page ->
                QueryRetainedResult.capture(fixture.authority, page.execution).refined().retainedBytes +
                    invocationPolicy.previewBytes(page.items) +
                    page.items.size * QUERY_ROW_REFERENCE_CHARGE_BYTES
            }
        println(
            "MatchedPrefixFacts(bytes=${facts.retainedBytes}, standalone=$standalone, inventory=${inventory.retainedBytes}, " +
                "providerPositions=${positions.take(2)}, rows=${listOf(first, second).map { it.items.size }}, " +
                "work=${listOf(first, second).map { it.work.count.value }}, " +
                "occurrenceStarts=${listOf(first, second).flatMap { (it.result.rows as QueryRows.Occurrences).values }.take(40).map { (it as QueryOccurrence.Reference).value.occurrence.range.startInclusive }})"
        )
        assertTrue(
            facts.retainedBytes < standalone - inventory.retainedBytes / 2,
            "Matched two-page owner charges: facts=${facts.retainedBytes}, standalone=$standalone, inventory=${inventory.retainedBytes}",
        )
    }

    @Test
    fun `retention observations distinguish zero capacity and saturated exhaustion`() {
        val allowance = QueryByteLimit.parse(100L).refined()
        val exact =
            QueryInvocationRetentionAdmission(
                QueryInvocationRetentionStage.BEFORE_EXECUTION,
                allowance,
                QueryRetentionByteCount.parse(70L).refined(),
                QueryRetentionByteCount.parse(20L).refined(),
                QueryRetentionByteCount.parse(10L).refined(),
            )
        assertEquals(100L, exact.total.value)
        assertEquals(0L, (exact.capacity as QueryInvocationRetentionCapacity.Available).bytes.value)
        val exhausted =
            QueryInvocationRetentionAdmission(
                QueryInvocationRetentionStage.BEFORE_FACTS,
                allowance,
                QueryRetentionByteCount.parse(Long.MAX_VALUE).refined(),
                QueryRetentionByteCount.parse(20L).refined(),
                QueryRetentionByteCount.parse(10L).refined(),
            )
        assertEquals(Long.MAX_VALUE, exhausted.total.value)
        assertEquals(QueryInvocationRetentionCapacity.Exhausted, exhausted.capacity)
    }

    @Test
    fun `advancing inventory keeps exact state proof and reference charges`() {
        val graph = RelationProviderRetainedGraph()
        assertEquals(inventory.retainedBytes + 1_024L + 8L, inventory.retainedBytes(graph))
        var next = inventory
        repeat(20) {
            next = next.consume()
            assertEquals(512L + 8L, next.retainedBytes(graph))
        }
        val detached = RelationProviderState.references(locators)
        assertEquals(inventory.retainedBytes + 1_024L + 8L, detached.retainedBytes(graph))
        val result = read(RelationRequest.start(fixture.selector, RelationMeaning.References, fixture.budget))
        val confirmed = (result as RelationReadResult.Qualified).coverage as RelationIncompleteCoverage.Resumable
        val provider = confirmed.continuation.providerState
        val proofBytes = provider.retainedBytes - inventory.retainedBytes
        assertTrue(proofBytes > 0L)
        assertEquals(512L + 8L + proofBytes, provider.retainedBytes(graph))
    }

    @Test
    fun `rejected page admission does not commit shared owners or lose non advancing proof`() = runTest {
        val state = QueryStateStore(clock = { 0L })
        val recording = QueryInvocationExecution(service)
        val protocol = CanonicalQueryProtocol(recording, fixture.references, state)
        val observed = recording.page(grant) { protocol.executePage(input, fixture.authority, grant) }
        val page = SymbolInvocationPage.admit(observed, input).refined()
        val facts = QueryInvocationFacts(fixture.authority, policy())
        assertEquals(
            QueryInvocationStop.RETAINED_BYTES_LIMIT,
            (facts.append(page, 0L) as Refinement.Rejected).failure.reason,
        )
        assertEquals(0L, facts.retainedBytes)
        facts.append(page, Long.MAX_VALUE).refined()
        val independent = QueryInvocationFacts(fixture.authority, policy())
        independent.append(page, Long.MAX_VALUE).refined()
        assertEquals(independent.retainedBytes, facts.retainedBytes)
        assertEquals(
            QueryInvocationStop.NON_ADVANCING,
            (facts.append(page, Long.MAX_VALUE) as Refinement.Rejected).failure.reason,
        )
    }

    private fun read(request: RelationRequest): RelationReadResult {
        var state = (request.position as? RelationReadPosition.Resume)?.continuation?.providerState ?: inventory
        positions += state.consumedLocatorCount.value
        val references =
            state.prepared
                .take(minOf(20, request.budget.resources.resultLimit.value))
                .map { locator ->
                    val occurrence =
                        RelationReferenceOccurrence.confirmed(
                                request,
                                RelationConfirmedReferenceTarget.fromCompiler(
                                        request.subject,
                                        CompilerGroundedSymbolEvidence.fromSelector(fixture.selector),
                                    )
                                    .refined(),
                                RelationOccurrence.fromBoundary(
                                        locator.file,
                                        locator.range.startInclusive,
                                        locator.range.endExclusive,
                                    )
                                    .refined(),
                                RelationReferenceContext.TYPE,
                                RelationReferenceOwnership.DeclarationOwned(
                                    RelationEndpoint.resolve(
                                            fixture.authority,
                                            request.searchScope,
                                            CompilerGroundedSymbolEvidence.fromSelector(fixture.selector),
                                        )
                                        .refined()
                                ),
                                RelationProvenance.K2_AUTHORED_SOURCE,
                            )
                            .refined()
                    state = state.consume(RelationProviderConsumption.Confirmed(occurrence))
                    occurrence
                }
                .sorted()
        val batch =
            RelationBatch.create(
                    request,
                    references.map { it.declarationFact(request).refined() }.sorted(),
                    RelationByteCount.parse(
                            references.sumOf {
                                it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() +
                                    it.declarationFact(request)
                                        .refined()
                                        .canonicalProjection()
                                        .toByteArray(Charsets.UTF_8)
                                        .size
                            }
                        )
                        .refined(),
                    RelationWorkCount.parse(references.size.toLong()).refined(),
                    RelationResultCount.parse(references.size).refined(),
                    references,
                )
                .refined()
        return if (state.hasUnfinishedWork)
            RelationReadResult.Qualified(
                batch,
                RelationIncompleteCoverage.resumable(
                        batch,
                        setOf(RelationLimitation.RESULT_LIMIT_REACHED),
                        state.providerCursor,
                        state,
                    )
                    .refined(),
            )
        else RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
    }
}
