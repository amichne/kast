package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.SymbolIdDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure detached ownership: the explicit pending charge is not a native memory or compiler claim. */
class QueryProducerPayloadReleaseTest {
    @Test
    fun `encoded page charge cannot be rebound to a different publication`() = runTest {
        val fixture = fixture()
        val store = QueryStateStore(capacity = 3, maximumBytes = 100_000L, clock = { 0L })
        val token = (store.issueCheckpoint(fixture.request, fixture.checkpoint) as QueryCheckpointIssuance.Issued).token
        val producer = store.acquireCheckpoint(token, fixture.lease, 10_000) as QueryCheckpointAcquisition.Acquired
        val complete = fixture.page as OperationOutcome.Complete
        val changed =
            OperationOutcome.Complete(
                complete.evidence.copy(
                    payload = complete.evidence.payload.copy(presentationOrigin = QueryKnownMinimum.parse(1).refined())
                )
            )
        assertEquals(
            QueryPublicationCommit.Rejected(QueryPublicationFailure.PUBLISHED_PAGE_MISMATCH),
            store.commitPublication(producer.claim, changed, encoded(fixture.page)),
        )
        store.releasePublication(producer.claim)
        assertSame(
            fixture.checkpoint,
            (store.restoreCheckpoint(token, fixture.lease) as QueryCheckpointRestoration.Restored).checkpoint,
        )
    }

    @Test
    fun `sequential publications exhaust more facts than replay capacity by evicting only old roots`() = runTest {
        val fixture = fixture()
        val store = QueryStateStore(capacity = 3, maximumBytes = 100_000L, clock = { 0L })
        val expected = (0 until 12).map { "fact$it" }
        val first = (store.issueCheckpoint(fixture.request, fixture.checkpoint) as QueryCheckpointIssuance.Issued).token
        var token = first
        val facts = mutableListOf<String>()
        for (name in expected) {
            val producer = store.acquireCheckpoint(token, fixture.lease, 10_000) as QueryCheckpointAcquisition.Acquired
            val next =
                if (name == expected.last()) null
                else {
                    val pending = object : QueryCheckpoint by fixture.checkpoint {}
                    (store.issueCheckpoint(fixture.request, pending, publicationOwner = producer.claim)
                            as QueryCheckpointIssuance.Issued)
                        .token
                }
            val page = factPage(fixture.page, name, expected.indexOf(name), facts.size + 1, next)
            assertEquals(QueryPublicationCommit.Committed, store.commitPublication(producer.claim, page, encoded(page)))
            store.releasePublication(producer.claim)
            facts += page.factName()
            assertTrue(store.retentionMeasurements().retainedEntries.value <= 3)
            if (next != null) token = next
        }
        assertEquals(expected, facts)
        assertEquals(QueryCheckpointAcquisition.Unavailable, store.acquireCheckpoint(first, fixture.lease, 10_000))
        val replay =
            assertInstanceOf(
                QueryCheckpointAcquisition.Published::class.java,
                store.acquireCheckpoint(token, fixture.lease, 10_000),
            )
        store.releasePublication(replay.claim)
    }

    @Test
    fun `immutable publication releases consumed checkpoint payload and retains successor`() = runTest {
        val fixture = fixture()
        val store = QueryStateStore(capacity = 8, maximumBytes = 2_000_000L, clock = { 0L })
        val pending =
            object : QueryCheckpoint by fixture.checkpoint {
                override val retainedBytes = 1_000_000L
            }
        val token = (store.issueCheckpoint(fixture.request, pending) as QueryCheckpointIssuance.Issued).token
        val producer = store.acquireCheckpoint(token, fixture.lease, 10_000) as QueryCheckpointAcquisition.Acquired
        val successor =
            object : QueryCheckpoint by fixture.checkpoint {
                override val retainedBytes = 1024L
            }
        val next =
            store.issueCheckpoint(fixture.request, successor, publicationOwner = producer.claim)
                as QueryCheckpointIssuance.Issued
        val complete = fixture.page as OperationOutcome.Complete
        val page =
            OperationOutcome.Qualified(
                complete.evidence,
                QueryRunQualification.create(
                        QueryKnownMinimum.parse(0).refined(),
                        listOf(QueryLimitationDocument.WORK_LIMIT_REACHED),
                        QueryQualifiedProgressDocument.Resumable(
                            QueryCheckpointDocument.Upstream(next.token),
                            ReadResumeActionDocument.RESUME,
                        ),
                    )
                    .refined(),
            )
        val before = store.retentionMeasurements().retainedBytes.value
        assertEquals(QueryPublicationCommit.Committed, store.commitPublication(producer.claim, page))
        store.releasePublication(producer.claim)
        assertTrue(store.retentionMeasurements().retainedBytes.value < before - 900_000L)
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restoreCheckpoint(token, fixture.lease))
        assertSame(
            successor,
            (store.restoreCheckpoint(next.token, fixture.lease) as QueryCheckpointRestoration.Restored).checkpoint,
        )
        assertEquals(QueryCheckpointIssuance.Unavailable, store.retainedCheckpoint(fixture.request, pending))
        val replay = store.acquireCheckpoint(token, fixture.lease, 10_000) as QueryCheckpointAcquisition.Published
        assertSame(page, replay.page)
        assertEquals(QueryPublicationCommit.Committed, store.commitPublication(replay.claim, replay.page))
        store.releasePublication(replay.claim)
    }

    @Test
    fun `consumed output releases pending reservation and replays fitted immutable page`() = runTest {
        val fixture = fixture()
        val store = QueryStateStore(capacity = 8, maximumBytes = 100_000L, clock = { 0L })
        val initial = (store.acquireInitial(fixture.lease) as QueryInitialAcquisition.Acquired).claim
        val output =
            store.issueOutput(
                fixture.request,
                fixture.lease,
                fixture.page,
                initial,
                QueryRetentionByteCount.parse(512).refined(),
            ) as QueryOutputIssuance.Issued
        assertEquals(
            QueryPublicationCommit.Committed,
            store.commitPublication(initial, fixture.page.advertisingOutput(output.token)),
        )
        store.releasePublication(initial)
        val producer = store.acquireOutput(output.token, fixture.lease, 10_000) as QueryOutputAcquisition.Acquired
        val before = store.retentionMeasurements().retainedBytes.value
        assertEquals(
            QueryPublicationCommit.Committed,
            store.commitPublication(producer.claim, fixture.page, encoded(fixture.page)),
        )
        store.releasePublication(producer.claim)
        assertTrue(store.retentionMeasurements().retainedBytes.value < before - 30_000L)
        val replay = store.acquireOutput(output.token, fixture.lease, 10_000) as QueryOutputAcquisition.Published
        assertSame(fixture.page, replay.page)
        assertEquals(QueryPublicationCommit.Committed, store.commitPublication(replay.claim, replay.page))
        store.releasePublication(replay.claim)
    }

    private fun factPage(
        original: QueryPublishedPage,
        name: String,
        ordinal: Int,
        knownMinimum: Int,
        next: io.github.amichne.kast.protocol.contract.QueryExecutionContinuation.Pipeline?,
    ): QueryPublishedPage {
        val complete = original as OperationOutcome.Complete
        val item =
            QueryResultItemDocument.ExactSymbol(
                QueryReferenceDocument.ExactSymbol(ProtocolText.parse("exact:v3:fixture-$name").refined()),
                SymbolKindDocument.CLASSLIKE,
                ProtocolText.parse(name).refined(),
                null,
                null,
                bounded(emptyList()),
                SymbolIdDocument.parse("sym:" + ordinal.toString().padStart(42, 'A') + "A").refined(),
            )
        val evidence = complete.evidence.copy(payload = complete.evidence.payload.copy(items = bounded(listOf(item))))
        return if (next == null) OperationOutcome.Complete(evidence)
        else
            OperationOutcome.Qualified(
                evidence,
                QueryRunQualification.create(
                        QueryKnownMinimum.parse(knownMinimum).refined(),
                        listOf(QueryLimitationDocument.WORK_LIMIT_REACHED),
                        QueryQualifiedProgressDocument.Resumable(
                            QueryCheckpointDocument.Upstream(next),
                            ReadResumeActionDocument.RESUME,
                        ),
                    )
                    .refined(),
            )
    }

    private fun QueryPublishedPage.factName(): String {
        val items =
            when (this) {
                is OperationOutcome.Complete -> evidence.payload.items.values
                is OperationOutcome.Qualified -> evidence.payload.items.values
                is OperationOutcome.Rejected -> error("The fixture permits only published positive facts")
            }
        return checkNotNull((items.single() as QueryResultItemDocument.ExactSymbol).name).value
    }

    private data class Fixture(
        val request: QueryRunRequest.Run,
        val lease: SemanticReadLease,
        val checkpoint: QueryCheckpoint,
        val page: QueryPublishedPage,
    )

    private suspend fun fixture(): Fixture {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                EvidenceGeneration.parse(7).refined(),
            )
        val request =
            QueryRunRequest.Run(
                QueryFromDocument.Symbols(
                    QueryDiscoveryDocument(
                        QueryMatchDocument.All,
                        QueryScopeDocument(bounded(listOf(ProtocolText.parse("main").refined())), null, null),
                        bounded(listOf(QueryDeclarationKindDocument.CLASS)),
                    )
                ),
                bounded(emptyList()),
                QueryOutputDocument.Symbols(bounded(emptyList())),
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            )
        lateinit var checkpoint: QueryCheckpoint
        val page =
            CanonicalQueryProtocol(
                    QueryOperations { admitted ->
                        checkpoint =
                            object : QueryCheckpoint {
                                override val plan = admitted.plan
                                override val lease = admitted.lease
                                override val retainedBytes = 1024L
                            }
                        QueryExecutionResult.Complete(
                            QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
                            QueryCoverage.Complete(QueryCount.parse(0).refined()),
                        )
                    },
                    CanonicalQueryReferences(),
                )
                .execute(
                    request,
                    lease,
                    QueryBudget(
                        ResourceBudget(
                            ResultLimit.parse(10).refined(),
                            WorkUnitLimit.parse(100).refined(),
                            ElapsedTimeLimitMillis.parse(1000).refined(),
                        ),
                        QueryByteLimit.parse(10_000).refined(),
                    ),
                )
        return Fixture(request, lease, checkpoint, page)
    }

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun encoded(page: QueryPublishedPage): QueryPublicationPageCharge.Encoded {
        val document = (CanonicalOperationWireBindings.queryRun.encodeOutcome(page) as WireEncoding.Encoded).document
        return QueryPublicationPageCharge.Encoded.fromEncoding(
            page,
            QueryRetentionByteCount.parse(document.toByteArray(Charsets.UTF_8).size.toLong()).refined(),
        )
    }

    private fun <T> Refinement<T, *>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Detached ownership fixture rejected: $failure")
        }
}
