package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionBudgetCapacity
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.AdmittedQueryRunRejection
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryWorkCount
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.QueryExecutionClaim
import io.github.amichne.kast.query.protocol.QueryExecutionPublication
import io.github.amichne.kast.query.protocol.QueryExecutionPublicationResult
import io.github.amichne.kast.query.protocol.QueryInvocationPolicy
import io.github.amichne.kast.query.protocol.QueryPublicationCommit
import io.github.amichne.kast.query.protocol.QueryPublicationPageCharge
import io.github.amichne.kast.query.protocol.QueryPublishedPage
import io.github.amichne.kast.query.protocol.QueryStateStore
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.query.protocol.executeQueryPage
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadRejectedPublication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real protocol/store/encoder publication; scripted semantic observations make no native claim. */
class HostedCompletionRejectionPublicationTest {
    @Test
    fun `diagnostic bytes shrink only the rejection preview and preserve its retained rows`() = runTest {
        val case = Case()
        val trace =
            io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadTraceObservation.Observed(
                io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadTraceIdentity.fromBoundary(
                    java.util.UUID.fromString("33333333-3333-4333-8333-333333333333")
                )
            )
        var maximum: ReturnedByteLimit? = null
        val rejection = case.run { page ->
            val limit =
                maximum
                    ?: ReturnedByteLimit.parse(
                            encodeHostedQueryResponse(page).document.toByteArray(Charsets.UTF_8).size.toLong()
                        )
                        .refined()
                        .also { maximum = it }
            when (encodeHostedQueryResponse(page.withQueryDiagnosticIdentity(trace), maximumBytes = limit)) {
                is HostedResponse.Canonical<*, *, *> ->
                    io.github.amichne.kast.query.protocol.QueryInlinePresentation.FITS
                is HostedResponse.Oversized ->
                    io.github.amichne.kast.query.protocol.QueryInlinePresentation.RETENTION_REQUIRED
                else -> error("Unexpected fixture encoding failure")
            }
        }
        val decorated = rejection.withQueryDiagnosticIdentity(trace).publicationPage()
        val completion = (decorated as OperationOutcome.Rejected).reason as QueryRunRejection.CompletionUnproven
        assertEquals(0, (completion.evidence as QueryCompletionEvidenceDocument.Retained).preview.values.size)
        var fitted: QueryPublicationPageCharge.Encoded? = null
        val response =
            encodeHostedQueryResponse(decorated, maximumBytes = checkNotNull(maximum), published = { fitted = it })
        assertInstanceOf(HostedResponse.Canonical::class.java, response)
        assertEquals(decorated, checkNotNull(fitted).page)
        assertEquals(
            QueryPublicationCommit.Committed,
            case.store.commitPublication(case.claim, checkNotNull(fitted).page, fitted),
        )
        case.store.releasePublication(case.claim)
        val retained = assertInstanceOf(OperationOutcome.Qualified::class.java, case.read(rejection))
        assertEquals(
            1,
            (retained.evidence.payload as io.github.amichne.kast.protocol.contract.QueryRunResult).items.values.size,
        )
        assertEquals(1, case.executions)
    }

    @Test
    fun `encoded strict rejection commits its retained evidence and preserves the original reason`() = runTest {
        val case = Case()
        val rejection = case.run()
        var fitted: QueryPublicationPageCharge.Encoded? = null
        val response = encodeHostedQueryResponse(rejection, published = { fitted = it })
        assertInstanceOf(HostedResponse.Canonical::class.java, response)
        assertNotNull(fitted, "A fitted rejection carrying retained evidence must reach publication")
        assertEquals(rejection, fitted!!.page)
        assertEquals(
            HostedReadRejectedPublication.RetainedEvidence(
                (rejection.reason as QueryRunRejection.CompletionUnproven).evidence
                    as QueryCompletionEvidenceDocument.Retained
            ),
            fittedRejectedQueryPublication(rejection, fitted!!),
        )
        assertEquals(QueryPublicationCommit.Committed, case.store.commitPublication(case.claim, fitted!!.page, fitted))
        case.store.releasePublication(case.claim)
        val retained = case.read(rejection)
        assertInstanceOf(OperationOutcome.Qualified::class.java, retained)
        val retainedPage = retained as OperationOutcome.Qualified
        assertEquals(1, retainedPage.evidence.payload.items.values.size)
        assertTrue(QueryLimitationDocument.STATIC_MODEL_UNPROVEN in retainedPage.qualification.limitations)
        assertInstanceOf(
            QueryQualifiedProgressDocument.TerminalIncomplete::class.java,
            retainedPage.qualification.progress,
        )
        val original = rejection.reason as QueryRunRejection.CompletionUnproven
        assertEquals(
            QueryResultInterpretationDocument.EvidenceOnly(original.model, original.cause, original.originalCoverage),
            retainedPage.evidence.payload.interpretation,
        )
        assertEquals(1, case.executions)
        assertEquals(rejection, (response as HostedResponse.Canonical<*, *, *>).semantic)
        assertEncodedRejection(response, rejection)
    }

    @Test
    fun `budgeted encoded policy rejection preserves exact publication identity and its admitted report`() = runTest {
        val case = Case()
        val rejection = case.run()
        val report = case.budgetReport()
        val decorated =
            rejection
                .withQueryDiagnosticIdentity(
                    io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadTraceObservation.Observed(
                        io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadTraceIdentity.fromBoundary(
                            java.util.UUID.fromString("33333333-3333-4333-8333-333333333333")
                        )
                    )
                )
                .publicationPage()
        val budgeted = decorated.withQueryBudget(report)
        var fitted: QueryPublicationPageCharge.Encoded? = null
        val response =
            assertInstanceOf(
                HostedResponse.Canonical::class.java,
                encodeHostedQueryResponse(budgeted, published = { fitted = it }),
            )
        assertNotNull(fitted)
        assertEquals(decorated, fitted!!.page)
        assertInstanceOf(
            HostedReadRejectedPublication.RetainedEvidence::class.java,
            fittedRejectedQueryPublication(decorated, fitted!!),
        )
        val decoded =
            assertInstanceOf(
                WireDecoding.Decoded::class.java,
                CanonicalOperationWireBindings.queryRun.decodeOutcome(response.document),
            )
        val decodedRejection = assertInstanceOf(OperationOutcome.Rejected::class.java, decoded.value)
        assertEquals(
            AdmittedQueryRunRejection((decorated as OperationOutcome.Rejected).reason, report),
            decodedRejection.reason,
        )
        assertEquals(QueryPublicationCommit.Committed, case.store.commitPublication(case.claim, fitted!!.page, fitted))
        case.store.releasePublication(case.claim)
        assertInstanceOf(OperationOutcome.Qualified::class.java, case.read(rejection))
        assertEquals(1, case.executions)
    }

    @Test
    fun `ordinary encoded rejection has no retained policy publication capability`() {
        val rejection =
            OperationOutcome.Rejected(
                QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE)
            )
        val response = assertInstanceOf(HostedResponse.Canonical::class.java, encodeHostedQueryResponse(rejection))
        assertEquals(
            HostedReadRejectedPublication.Discard,
            fittedRejectedQueryPublication(rejection, encodedPublication(rejection, response)),
        )
    }

    @Test
    fun `another fitted rejection cannot authorize publication of the prepared policy evidence`() = runTest {
        val original = Case()
        val other = Case()
        val prepared = original.run()
        val encodedRejection = other.run()
        var fitted: QueryPublicationPageCharge.Encoded? = null
        assertInstanceOf(
            HostedResponse.Canonical::class.java,
            encodeHostedQueryResponse(encodedRejection, published = { fitted = it }),
        )
        assertNotNull(fitted)
        assertEquals(HostedReadRejectedPublication.Discard, fittedRejectedQueryPublication(prepared, fitted!!))
        original.store.releasePublication(original.claim)
        other.store.releasePublication(other.claim)
        assertInstanceOf(OperationOutcome.Rejected::class.java, original.read(prepared))
        assertInstanceOf(OperationOutcome.Rejected::class.java, other.read(encodedRejection))
    }

    @Test
    fun `encoded completion rejection without retained evidence has no publication capability`() = runTest {
        val case = Case()
        val rejected = case.run()
        val reason = rejected.reason as QueryRunRejection.CompletionUnproven
        val unavailable =
            OperationOutcome.Rejected(
                reason.copy(
                    evidence =
                        QueryCompletionEvidenceDocument.Unavailable(QueryCompletionRetentionFailure.CAPACITY_EXCEEDED)
                )
            )
        val response = assertInstanceOf(HostedResponse.Canonical::class.java, encodeHostedQueryResponse(unavailable))
        val encoded = encodedPublication(unavailable, response)
        assertEquals(HostedReadRejectedPublication.Discard, fittedRejectedQueryPublication(unavailable, encoded))
        case.store.releasePublication(case.claim)
        assertInstanceOf(OperationOutcome.Rejected::class.java, case.read(rejected))
    }

    @Test
    fun `discarding an encoded strict rejection releases its unpublished retained evidence`() = runTest {
        val case = Case()
        val rejection = case.run()
        var fitted: QueryPublicationPageCharge.Encoded? = null
        assertInstanceOf(
            HostedResponse.Canonical::class.java,
            encodeHostedQueryResponse(rejection, published = { fitted = it }),
        )
        assertNotNull(fitted)
        case.store.releasePublication(case.claim)
        val read = case.read(rejection)
        assertInstanceOf(OperationOutcome.Rejected::class.java, read)
        assertEquals(0, case.store.retentionMeasurements().retainedEntries.value)
        assertEquals(1, case.executions)
    }

    @Test
    fun `a byte rejection cannot publish or leave its pending evidence accessible`() = runTest {
        val case = Case()
        val rejection = case.run()
        val response =
            encodeHostedQueryResponse(
                rejection,
                maximumBytes = ReturnedByteLimit.parse(1).refined(),
                published = { error("An oversized rejection cannot publish") },
            )
        assertInstanceOf(HostedResponse.Oversized::class.java, response)
        case.store.releasePublication(case.claim)
        assertInstanceOf(OperationOutcome.Rejected::class.java, case.read(rejection))
        assertEquals(0, case.store.retentionMeasurements().retainedEntries.value)
        assertEquals(1, case.executions)
    }

    private fun assertEncodedRejection(
        response: HostedResponse.Canonical<*, *, *>,
        rejection: OperationOutcome.Rejected<QueryRunRejection>,
    ) {
        val body = Json.parseToJsonElement(response.document).jsonObject.getValue("body").jsonObject
        assertEquals("rejected", body.getValue("type").jsonPrimitive.content)
        val encoded = body.getValue("rejection").jsonObject
        assertEquals("COMPLETION_UNPROVEN", encoded.getValue("type").jsonPrimitive.content)
        val detail = encoded.getValue("detail").jsonObject
        assertEquals("INCOMPLETE_EXECUTION", detail.getValue("cause").jsonObject.getValue("type").jsonPrimitive.content)
        val evidence = detail.getValue("evidence").jsonObject
        assertEquals("RETAINED", evidence.getValue("type").jsonPrimitive.content)
        val reference =
            ((rejection.reason as QueryRunRejection.CompletionUnproven).evidence
                    as QueryCompletionEvidenceDocument.Retained)
                .result
        assertEquals(reference.value, evidence.getValue("result").jsonPrimitive.content)
    }

    private class Case {
        private val fixture = RelationPagingFixture.published()
        val store = QueryStateStore(clock = { 0L })
        lateinit var claim: QueryExecutionClaim
        var executions = 0
        private val output = QueryOutputDocument.Symbols(bounded(listOf(QuerySymbolFieldDocument.NAME)))
        private val request =
            QueryRunRequest.Run(
                QueryFromDocument.References(bounded(listOf(QueryReferenceDocument.ExactSymbol(fixture.exact)))),
                bounded(emptyList()),
                output,
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            )
        private val budget =
            QueryBudget(
                ResourceBudget(
                    ResultLimit.parse(2).refined(),
                    WorkUnitLimit.parse(100).refined(),
                    ElapsedTimeLimitMillis.parse(1000).refined(),
                ),
                QueryByteLimit.parse(100_000).refined(),
            )
        private val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    assertEquals(1, ++executions, "Unexpected semantic execution")
                    QueryExecutionResult.Qualified(
                            QueryResult(
                                QueryRows.Symbols.of(
                                    listOf(QuerySymbol(SymbolDescription.from(fixture.selector), emptyList()))
                                ),
                                emptyList(),
                            ),
                            QueryCoverage.Qualified.create(
                                    QueryCount.parse(1).refined(),
                                    setOf(QueryLimitation.RELATION_INCOMPLETE),
                                )
                                .refined(),
                            QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE),
                        )
                        .observedWork(QueryWorkCount.parse(1).refined())
                },
                fixture.references,
                store,
                QueryExecutionPublication { retained, owner, page ->
                    assertSame(store, retained)
                    assertInstanceOf(OperationOutcome.Rejected::class.java, page)
                    claim = owner
                    QueryExecutionPublicationResult.PREPARED
                },
            )

        fun budgetReport(): ExecutionBudgetReport {
            val bytes = ReturnedByteLimit.parse(budget.returnedBytes.value).refined()
            return ExecutionBudgetReport.from(
                AdmittedExecutionBudget.admit(
                    RequestedExecutionBudget(),
                    budget.resources,
                    bytes,
                    budget.resources,
                    bytes,
                    ExecutionBudgetCapacity(budget.resources.elapsedTimeLimit, budget.resources.resultLimit, bytes),
                )
            )
        }

        suspend fun run(
            inlinePresentation: (QueryPublishedPage) -> io.github.amichne.kast.query.protocol.QueryInlinePresentation =
                {
                    io.github.amichne.kast.query.protocol.QueryInlinePresentation.FITS
                }
        ): OperationOutcome.Rejected<QueryRunRejection> {
            val result =
                protocol.execute(
                    request,
                    fixture.authority,
                    budget,
                    QueryInvocationPolicy(
                        ResultLimit.parse(2).refined(),
                        QueryByteLimit.parse(100_000).refined(),
                        QueryByteLimit.parse(128_000_000).refined(),
                        CanonicalQueryCliDocuments::previewBytes,
                        nanoTime = { 0L },
                        inlinePresentation = inlinePresentation,
                    ),
                )
            assertInstanceOf(OperationOutcome.Rejected::class.java, result)
            val rejection = result as OperationOutcome.Rejected
            assertInstanceOf(QueryRunRejection.CompletionUnproven::class.java, rejection.reason)
            assertInstanceOf(
                QueryCompletionEvidenceDocument.Retained::class.java,
                (rejection.reason as QueryRunRejection.CompletionUnproven).evidence,
            )
            return rejection
        }

        suspend fun read(rejected: OperationOutcome.Rejected<QueryRunRejection>): QueryPublishedPage {
            val reference =
                ((rejected.reason as QueryRunRejection.CompletionUnproven).evidence
                        as QueryCompletionEvidenceDocument.Retained)
                    .result
            return CanonicalQueryProtocol(
                    QueryOperations {
                        executions++
                        error("Retained reads cannot execute providers")
                    },
                    fixture.references,
                    store,
                )
                .executeQueryPage(
                    QueryRunRequest.ReadResult.symbols(reference, output = output),
                    fixture.authority,
                    budget,
                )
        }
    }
}

private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejection: $failure")
    }
