package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
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
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.QueryCheckpointIssuance
import io.github.amichne.kast.query.protocol.QueryExecutionClaim
import io.github.amichne.kast.query.protocol.QueryExecutionPublication
import io.github.amichne.kast.query.protocol.QueryExecutionPublicationResult
import io.github.amichne.kast.query.protocol.QueryOutputIssuance
import io.github.amichne.kast.query.protocol.QueryPublicationCommit
import io.github.amichne.kast.query.protocol.QueryPublicationPageCharge
import io.github.amichne.kast.query.protocol.QueryRetentionByteCount
import io.github.amichne.kast.query.protocol.QueryRetentionMeasurements
import io.github.amichne.kast.query.protocol.QueryStateStore
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HostedQueryPublicationProjectionTest {
    @Test
    fun `output issuance projection preserves each closed result`() {
        val token =
            QueryExecutionContinuation.Output.parse("query-output:v1:00000000-0000-0000-0000-000000000001").refined()
        val cases =
            listOf(
                QueryOutputIssuance.Issued(token) to HostedOutputRetention.Retained(text(token.value)),
                QueryOutputIssuance.CapacityExceeded to HostedOutputRetention.CapacityExceeded,
                QueryOutputIssuance.EncodingRejected to
                    HostedOutputRetention.Rejected(HostedPublicationFailureCause.INVALID_FITTED_PAGE),
                QueryOutputIssuance.Unavailable to
                    HostedOutputRetention.Rejected(HostedPublicationFailureCause.CLAIM_UNAVAILABLE),
                QueryOutputIssuance.NonAdvancing to
                    HostedOutputRetention.Rejected(HostedPublicationFailureCause.NON_ADVANCING_SUCCESSOR),
                QueryOutputIssuance.PublishedPageImmutable to
                    HostedOutputRetention.Rejected(HostedPublicationFailureCause.PUBLISHED_PAGE_MISMATCH),
            )
        cases.forEach { (issued, expected) -> assertEquals(expected, issued.hostedRetention()) }
    }

    @Test
    fun `smaller hosted replay refuses immutable checkpoint fitting without allocating or changing its page`() =
        runTest {
            val owner = RelationPagingFixture.live()
            val request = request(owner)
            val budget = budget()
            val checkpoint = captureCheckpoint(owner, request, budget)
            val case = CheckpointReplayCase(owner, request, budget, checkpoint)
            val original = case.publishOriginal()
            case.assertSmallerRefitRejected(original)
            case.assertOriginalReplay(original)
        }

    private suspend fun captureCheckpoint(
        owner: RelationPagingFixture,
        request: QueryRunRequest.Run,
        budget: QueryBudget,
    ): QueryCheckpoint {
        lateinit var checkpoint: QueryCheckpoint
        var captures = 0
        CanonicalQueryProtocol(
                QueryOperations { admitted ->
                    captures++
                    checkpoint =
                        object : QueryCheckpoint {
                            override val plan = admitted.plan
                            override val lease = admitted.lease
                            override val retainedBytes = 1024L
                        }
                    complete(owner)
                },
                owner.references,
            )
            .execute(request, owner.authority, budget)
        assertEquals(1, captures)
        return checkpoint
    }

    private data class PublishedCheckpointPage(
        val semantic: HostedQueryOutcome,
        val response: HostedResponse,
        val measurements: QueryRetentionMeasurements,
    )

    private inner class CheckpointReplayCase(
        private val owner: RelationPagingFixture,
        request: QueryRunRequest.Run,
        private val budget: QueryBudget,
        checkpoint: QueryCheckpoint,
    ) {
        private val store = QueryStateStore(clock = { 0L })
        private val issued =
            assertInstanceOf(QueryCheckpointIssuance.Issued::class.java, store.issueCheckpoint(request, checkpoint))
        private val resume = QueryRunRequest.Resume(issued.token)
        private lateinit var claim: QueryExecutionClaim
        private var fitted: QueryPublicationPageCharge.Encoded? = null
        private var preparations = 0
        private var executions = 0
        private var suffixAttempts = 0
        private val protocol =
            CanonicalQueryProtocol(
                QueryOperations { admitted ->
                    executions++
                    assertEquals(1, executions)
                    assertSame(checkpoint, admitted.checkpoint)
                    complete(owner)
                },
                owner.references,
                store,
                QueryExecutionPublication { retained, active, _ ->
                    assertSame(store, retained)
                    preparations++
                    claim = active
                    QueryExecutionPublicationResult.PREPARED
                },
            )

        suspend fun publishOriginal(): PublishedCheckpointPage {
            val originalPage = protocol.execute(resume, owner.authority, budget)
            assertInstanceOf(OperationOutcome.Complete::class.java, originalPage)
            val original = encodeOriginalGrant(originalPage)
            assertInstanceOf(HostedResponse.Canonical::class.java, original)
            commitFitted()
            return PublishedCheckpointPage(originalPage, original, store.retentionMeasurements())
        }

        suspend fun assertSmallerRefitRejected(original: PublishedCheckpointPage) {
            val smallerLimit = ResultLimit.parse(1).refined()
            val smallerRequest = resume.copy(executionBudget = ExecutionBudgetDocument(maxResults = smallerLimit))
            val smallerBudget = budget.copy(resources = budget.resources.copy(resultLimit = smallerLimit))
            val replayedPage = protocol.execute(smallerRequest, owner.authority, smallerBudget)
            assertEquals(original.semantic, replayedPage)
            val beforeFitting = store.retentionMeasurements()
            fitted = null
            val refused =
                encodeHostedQueryResponse(
                    replayedPage,
                    maximumResults = smallerLimit,
                    maximumBytes = bytes(),
                    published = { fitted = it },
                    retain = { suffix -> retainSuffix(smallerRequest, suffix) },
                )
            val rejection = assertInstanceOf(HostedResponse.ReadRejected::class.java, refused)
            assertEquals(
                HostedQueryFailure.Publication(HostedPublicationFailureCause.PUBLISHED_PAGE_MISMATCH),
                rejection.failure,
            )
            assertEquals(HostedQueryStage.RESULT_DETACHED, rejection.stage)
            assertEquals(1, suffixAttempts)
            assertNull(fitted)
            assertEquals(beforeFitting, store.retentionMeasurements())
            store.releasePublication(claim)
            assertEquals(original.measurements.retainedBytes, store.retentionMeasurements().retainedBytes)
            assertEquals(original.measurements.retainedEntries, store.retentionMeasurements().retainedEntries)
        }

        suspend fun assertOriginalReplay(original: PublishedCheckpointPage) {
            val unchanged = protocol.execute(resume, owner.authority, budget)
            assertEquals(original.semantic, unchanged)
            val replay = encodeOriginalGrant(unchanged)
            assertEquals(original.response.document, replay.document)
            commitFitted()
            assertEquals(1, executions)
            assertEquals(3, preparations)
        }

        private fun encodeOriginalGrant(page: HostedQueryOutcome): HostedResponse =
            encodeHostedQueryResponse(
                page,
                maximumResults = budget.resources.resultLimit,
                maximumBytes = bytes(),
                published = { fitted = it },
            )

        private fun retainSuffix(request: QueryRunRequest.Resume, suffix: HostedQueryOutcome): HostedOutputRetention {
            suffixAttempts++
            val page = suffix.publicationPage()
            val encoded =
                assertInstanceOf(
                    WireEncoding.Encoded::class.java,
                    CanonicalOperationWireBindings.queryRun.encodeOutcome(page),
                )
            val result =
                store.issueOutput(
                    request,
                    owner.authority,
                    page,
                    claim,
                    QueryRetentionByteCount.parse(encoded.document.toByteArray(Charsets.UTF_8).size.toLong()).refined(),
                )
            assertEquals(QueryOutputIssuance.PublishedPageImmutable, result)
            return result.hostedRetention()
        }

        private fun commitFitted() {
            val charge = requireNotNull(fitted)
            assertEquals(
                QueryPublicationCommit.Committed,
                store.commitPublication(claim, charge.page, charge),
            )
            store.releasePublication(claim)
        }
    }

    private fun request(owner: RelationPagingFixture) =
        QueryRunRequest.Run(
            QueryFromDocument.References(bounded(listOf(QueryReferenceDocument.ExactSymbol(text(owner.exact.value))))),
            bounded(emptyList()),
            QueryOutputDocument.Symbols(bounded(listOf(QuerySymbolFieldDocument.NAME))),
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            completion = io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument.Progressive,
        )

    private fun complete(owner: RelationPagingFixture) =
        QueryExecutionResult.Complete.create(
            QueryResult(
                QueryRows.Symbols.of(List(3) { QuerySymbol(SymbolDescription.from(owner.selector), emptyList()) }),
                emptyList(),
            ),
            QueryCoverage.Complete(QueryCount.parse(3).refined()),
        )

    private fun budget() =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(10).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(100_000).refined(),
        )

    private fun bytes() = ReturnedByteLimit.parse(100_000).refined()

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejection: $failure")
        }
}
