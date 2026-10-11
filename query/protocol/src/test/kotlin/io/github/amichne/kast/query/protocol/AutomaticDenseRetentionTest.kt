package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpointStorageOutcome
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOccurrence
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProviderRetentionLedger
import io.github.amichne.kast.symbol.contract.SymbolDescription
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real service, runner, state and publication boundaries under unchanged invocation grants. */
internal class AutomaticDenseRetentionTest : AutomaticDenseRetentionCase() {
    @Test
    fun `dense accounting preserves finite capacity rejection under unchanged grants`() = runTest {
        val state = QueryStateStore(clock = { 0L })
        val observer = RetentionCapture()
        val base = policy(rows = 20, byteLimit = 32_768, retainedBytes = QueryByteLimit.DefaultCheckpoint.value)
        val invocationPolicy =
            QueryInvocationPolicy(
                base.previewRows,
                base.previewBytesLimit,
                base.retainedBytes,
                base.previewBytes,
                nanoTime = { 0L },
                retentionObservation = observer,
            )
        val recording = QueryInvocationExecution(service)
        val protocol = CanonicalQueryProtocol(recording, fixture.references, state)
        val runner =
            AutomaticSymbolQueryRunner(state, fixture.authority, invocationPolicy) { action, remaining ->
                recording.page(remaining) { protocol.executePage(action, fixture.authority, remaining) }
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
        assertEquals(
            List(starts.size) { 100 + it * 32 },
            starts.sorted(),
            "Independent fixture oracle for retained prefix",
        )
        assertEquals(starts.size, qualified.coverage.knownMinimum.value)
        assertEquals((0 until positions.size).map { it * 20L }, positions)
        assertEquals(QueryCheckpointStorageOutcome.CAPACITY_EXCEEDED, checkpointAdmissions.last().outcome)
        assertCheckpointReservation(observer.admissions, starts.size)
        assertInvocationLedger(observer, invocationPolicy)
    }

    private fun assertInvocationLedger(observer: RetentionCapture, invocationPolicy: QueryInvocationPolicy) {
        val ledger = observer.admissions
        val factsLedger = observer.estimates
        val inventoryLedgers = observer.owners
        val factStages = observer.stages
        assertTrue(checkpointAdmissions.all { it.allowance.value <= grant.checkpointBytes.value })
        assertTrue(ledger.all { it.total.value <= invocationPolicy.retainedBytes.value })
        assertTrue(ledger.all { it.total.value == it.facts.value + it.issuedState.value + it.request.value })
        assertTrue(ledger.all { it.request.value == input.accountedRequestBytes() })

        assertTrue(ledger.any { it.issuedState.value > 0L }, "Replay owners must remain charged")
        assertEquals(ledger.map { it.stage }, factStages)
        assertEquals(ledger.map { it.facts }, factsLedger.map { it.total })
        assertTrue(inventoryLedgers.first().charges.isEmpty())
        assertTrue(inventoryLedgers.last().charges.size == 1)
        assertEquals(596_430L, inventoryLedgers.last().charges.single().inventory.value)
        assertTrue(
            factsLedger.zip(inventoryLedgers).all { (facts, owners) -> owners.total.value <= facts.semantic.value }
        )
        assertEquals(QueryInvocationRetentionStage.FACTS_ACCEPTED, factStages.last())
        val final = factsLedger.last()
        val owners = inventoryLedgers.last()
        assertEquals(19_921_968L, final.total.value)
        assertEquals(31_609_640L, ledger.last().total.value)
        assertEquals(20L, owners.charges.single().references.value)
        assertEquals(597_102L, owners.total.value)
        println(
            "DenseFacts(semantic=${final.semantic.value}, preview=${final.preview.value}, " +
                "rowCells=${final.rowReferenceCells.value}, total=${final.total.value}, " +
                "inventoryReferences=${owners.charges.single().references.value}, " +
                "inventoryLedger=${owners.total.value}, " +
                "request=${ledger.last().request.value}, issued=${ledger.last().issuedState.value}, " +
                "invocationTotal=${ledger.last().total.value})"
        )
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
        assertMatchedPrefix(listOf(first, second))
        val facts = QueryInvocationFacts(fixture.authority, invocationPolicy)
        facts.append(first, Long.MAX_VALUE).refined()
        facts.append(second, Long.MAX_VALUE).refined()
        val standalone =
            listOf(first, second).sumOf { page ->
                QueryRetainedResult.capture(fixture.authority, page.execution).refined().retainedBytes +
                    invocationPolicy.previewBytes(page.items) +
                    page.items.size * QUERY_ROW_REFERENCE_CHARGE_BYTES
            }
        val starts =
            listOf(first, second)
                .flatMap { (it.result.rows as QueryRows.Occurrences).values }
                .map { (it as QueryOccurrence.Reference).value.occurrence.range.startInclusive }
        assertMatchedEstimates(facts, standalone, starts, listOf(first, second))
    }

    private fun assertMatchedEstimates(
        facts: QueryInvocationFacts,
        standalone: Long,
        starts: List<Int>,
        pages: List<SymbolInvocationPage>,
    ) {
        val estimate = facts.retentionEstimate
        assertEquals(1_339_764L, estimate.total.value)
        assertEquals(2_049_422L, standalone)
        assertEquals(
            facts.retainedBytes,
            estimate.semantic.value + estimate.preview.value + estimate.rowReferenceCells.value,
        )
        val owners = facts.providerRetentionLedger()
        assertEquals(1, owners.charges.size)
        assertEquals(596_430L, owners.charges.single().inventory.value)
        assertEquals(1L, owners.charges.single().owner.value)
        assertTrue(
            owners.total.value <= estimate.semantic.value,
            "Inventory ledger is contained in semantic bytes, not added twice",
        )
        println(
            "MatchedPrefixComponents(semantic=${estimate.semantic.value}, preview=${estimate.preview.value}, " +
                "rowCells=${estimate.rowReferenceCells.value}, inventoryOwners=${owners.charges.size}, " +
                "inventoryReferences=${owners.charges.single().references.value}, inventoryLedger=${owners.total.value})"
        )
        println(
            "MatchedPrefixFacts(bytes=${facts.retainedBytes}, standalone=$standalone, " +
                "inventory=${inventory.retainedBytes}, " +
                "providerPositions=${positions.take(2)}, rows=${pages.map { it.items.size }}, " +
                "work=${pages.map { it.work.count.value }}, " +
                "occurrenceStarts=$starts)"
        )
        assertTrue(
            facts.retainedBytes < standalone - inventory.retainedBytes / 2,
            "Matched two-page owner charges: facts=${facts.retainedBytes}, " +
                "standalone=$standalone, inventory=${inventory.retainedBytes}",
        )
    }

    private fun assertMatchedPrefix(pages: List<SymbolInvocationPage>) {
        // Independent fixture oracle, not a selection from returned rows or provider locators.
        val starts = listOf(100, 132, 164, 196, 228, 260, 292, 324, 356, 388, 420, 452, 484, 516, 548, 580)
        val expected = starts.map { RelationOccurrence.fromBoundary(fixture.selector.file, it, it + 20).refined() }
        val rows =
            pages
                .flatMap { (it.result.rows as QueryRows.Occurrences).values }
                .map { (it as QueryOccurrence.Reference).value }
        assertEquals(expected, rows.map { it.occurrence }, "Exact semantic row identities and order")
        assertEquals(
            List(16) { SymbolDescription.from(fixture.selector).compilerIdentity },
            rows.map { it.target.compilerIdentity },
        )
        assertEquals(listOf(8, 8), pages.map { it.items.size })
        assertEquals(listOf(0L), positions, "One provider batch; second page drains its pending tasks")
        assertEquals(listOf(21L, 0L), pages.map { it.work.count.value })
        val items = pages.flatMap { it.items }.map { it as QueryResultItemDocument.ReferenceOccurrence }
        assertEquals(starts, items.map { it.occurrence.occurrence.range.startInclusive.value })
        assertEquals(starts.map { it + 20 }, items.map { it.occurrence.occurrence.range.endExclusive.value })
        assertEquals(List(16) { fixture.selector.file.stableValue }, items.map { it.occurrence.occurrence.file.value })
        // These semantic pages precede retained-result UUID issuance. Signed selectors can vary between runs.
        assertEquals(List(16) { null }, items.map { it.rowId })
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
        assertEquals(0, facts.providerRetentionLedger().charges.size)
        assertEquals(QueryInvocationFactsRetentionEstimate.Empty, facts.retentionEstimate)
        facts.append(page, Long.MAX_VALUE).refined()
        val independent = QueryInvocationFacts(fixture.authority, policy())
        independent.append(page, Long.MAX_VALUE).refined()
        assertEquals(independent.retainedBytes, facts.retainedBytes)
        assertEquals(
            QueryInvocationStop.NON_ADVANCING,
            (facts.append(page, Long.MAX_VALUE) as Refinement.Rejected).failure.reason,
        )
    }

    private fun assertCheckpointReservation(ledger: List<QueryInvocationRetentionAdmission>, rowCount: Int) {
        val last = checkpointAdmissions.last()
        assertEquals(393, rowCount)
        assertEquals(789_650L, last.estimate.required.value)
        assertEquals(685_851L, last.allowance.value)
        assertEquals(last.estimate.required, last.estimate.tasks)
        assertEquals(
            0L,
            last.estimate.identityRows.value +
                last.estimate.inputs.value +
                last.estimate.impact.value +
                last.estimate.joins.value,
        )
        val beforeCheckpoint = ledger.last { it.stage == QueryInvocationRetentionStage.BEFORE_EXECUTION }
        val remaining = beforeCheckpoint.allowance.value - beforeCheckpoint.total.value
        assertEquals(last.allowance.value, remaining / 2L, "Existing checkpoint/output reservation remains enforced")
        println(
            "CheckpointReservation(facts=${beforeCheckpoint.facts.value}, " +
                "issued=${beforeCheckpoint.issuedState.value}, request=${beforeCheckpoint.request.value}, " +
                "total=${beforeCheckpoint.total.value}, remaining=$remaining)"
        )
        println(
            "DenseCheckpoint(rows=$rowCount, required=${last.estimate.required.value}, " +
                "allowance=${last.allowance.value}, components=${last.estimate})"
        )
    }

    private class RetentionCapture : QueryInvocationRetentionObservation {
        val admissions = mutableListOf<QueryInvocationRetentionAdmission>()
        val estimates = mutableListOf<QueryInvocationFactsRetentionEstimate>()
        val owners = mutableListOf<RelationProviderRetentionLedger>()
        val stages = mutableListOf<QueryInvocationRetentionStage>()

        override fun observe(admission: QueryInvocationRetentionAdmission) {
            admissions += admission
        }

        override fun observeFacts(
            stage: QueryInvocationRetentionStage,
            estimate: QueryInvocationFactsRetentionEstimate,
            inventoryOwners: RelationProviderRetentionLedger,
        ) {
            stages += stage
            estimates += estimate
            owners += inventoryOwners
        }
    }
}
