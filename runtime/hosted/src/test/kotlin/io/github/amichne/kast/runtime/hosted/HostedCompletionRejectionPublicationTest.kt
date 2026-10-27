package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.AdmittedQueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.query.protocol.QueryPublicationCommit
import io.github.amichne.kast.query.protocol.QueryPublicationPageCharge
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadRejectedPublication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real protocol/store/encoder publication; scripted semantic observations make no native claim. */
class HostedCompletionRejectionPublicationTest {
    @Test
    fun `diagnostic bytes shrink only the rejection preview and preserve its retained rows`() = runTest {
        val case = HostedCompletionRejectionCase()
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
                            HostedResponse.Canonical.encode(CanonicalOperationWireBindings.queryRun, page)
                                .document
                                .toByteArray(Charsets.UTF_8)
                                .size
                                .toLong()
                        )
                        .refined()
                        .also { maximum = it }
            hostedInlineQueryPresentation(
                page.withQueryDiagnosticIdentity(trace),
                io.github.amichne.kast.kernel.ReadLimits.Default,
                limit,
            )
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
        val case = HostedCompletionRejectionCase()
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
        val case = HostedCompletionRejectionCase()
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
        val original = HostedCompletionRejectionCase()
        val other = HostedCompletionRejectionCase()
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
        val case = HostedCompletionRejectionCase()
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
        val case = HostedCompletionRejectionCase()
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
        val case = HostedCompletionRejectionCase()
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
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejection: $failure")
    }
