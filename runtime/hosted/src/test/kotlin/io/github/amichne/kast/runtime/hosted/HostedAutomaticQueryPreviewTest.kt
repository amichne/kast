package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.QueryRunResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedAutomaticQueryPreviewTest {
    @Test
    fun `evidence only retained read fits bytes and advances only its evidence cursor`() {
        val semantic = evidenceOnlyRead()
        val failures = semantic.evidence.payload.failures
        val qualification = semantic.qualification
        val response =
            encodeHostedQueryResponse(
                semantic,
                maximumBytes = ReturnedByteLimit.parse(8_000).refined(),
                retain = { error("Retained evidence must not issue another output continuation") },
            )
                as HostedResponse.Canonical<*, *, *>
        assertTrue(response.document.toByteArray().size <= 8_000)
        val fitted = (response.semantic as OperationOutcome.Qualified).evidence.payload as QueryRunResult
        assertTrue(fitted.failures.values.size in 1 until 100)
        assertEquals(failures.values.take(fitted.failures.values.size), fitted.failures.values)
        assertEquals(100 + fitted.failures.values.size, fitted.evidenceWindow!!.end.value)
        assertEquals(1100, fitted.evidenceWindow!!.total.value)
        assertEquals(120, fitted.presentationWindow!!.start.value)
        assertEquals(120, fitted.presentationWindow!!.end.value)
        assertEquals(null, fitted.nextCursor)
        assertEquals(qualification, (response.semantic as OperationOutcome.Qualified).qualification)
    }

    private fun evidenceOnlyRead() = run {
        val original = HostedQueryRetainedPresentationTest().page(120, 120, 120, longTokens = true)
        val ref =
            (HostedQueryRetainedPresentationTest()
                    .page(0, 1, 120, longTokens = true)
                    .evidence
                    .payload
                    .items
                    .values
                    .single() as io.github.amichne.kast.protocol.contract.QueryResultItemDocument.ExactSymbol)
                .ref
        val evidenceWindow =
            io.github.amichne.kast.protocol.contract.QueryEvidenceWindowDocument.create(
                    io.github.amichne.kast.protocol.contract.QueryEvidenceCursor.parse(100).refined(),
                    io.github.amichne.kast.protocol.contract.QueryEvidenceCursor.parse(200).refined(),
                    io.github.amichne.kast.protocol.contract.QueryEvidenceCursor.parse(1100).refined(),
                )
                .refined()
        val qualification =
            io.github.amichne.kast.protocol.contract.QueryRunQualification.create(
                    io.github.amichne.kast.protocol.contract.QueryKnownMinimum.parse(120).refined(),
                    listOf(io.github.amichne.kast.protocol.contract.QueryLimitationDocument.REFINEMENT_INCOMPLETE),
                    io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument.TerminalIncomplete(
                        io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE
                    ),
                )
                .refined()
        OperationOutcome.Qualified(
            original.evidence.copy(
                payload =
                    original.evidence.payload.copy(
                        failures = predicateFailures(ref),
                        evidenceWindow = evidenceWindow,
                    )
            ),
            qualification,
        )
    }

    private fun predicateFailures(ref: io.github.amichne.kast.protocol.contract.QueryReferenceDocument.ExactSymbol) =
        io.github.amichne.kast.protocol.contract.BoundedProtocolList.create<
                io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
            >(
                List(100) {
                    io.github.amichne.kast.protocol.contract.QueryItemFailureDocument.Predicate(
                        ref,
                        io.github.amichne.kast.protocol.contract.QueryPredicateFailureDocument.PREDICATE_UNPROVEN,
                    )
                }
            )
            .refined()

    @Test
    fun `automatic retained complete query stays complete after full envelope preview fitting`() {
        val original = HostedQueryRetainedPresentationTest().page(0, 10, 120, longTokens = true)
        val invocation =
            io.github.amichne.kast.protocol.contract.QueryInvocationDocument.create(
                    120,
                    io.github.amichne.kast.protocol.contract.QueryPreviewDocument.Prefix(10, 30_000),
                    io.github.amichne.kast.protocol.contract.QueryInvocationStop.COMPLETED,
                )
                .refined()
        val automatic =
            OperationOutcome.Complete(
                original.evidence.copy(payload = original.evidence.payload.copy(invocation = invocation))
            )
        val response =
            encodeHostedQueryResponse(
                automatic,
                maximumResults = ResultLimit.parse(2).refined(),
                maximumBytes = ReturnedByteLimit.parse(8_000).refined(),
                retain = { error("Already retained preview must not issue an output continuation") },
            )
                as HostedResponse.Canonical<*, *, *>
        assertInstanceOf(OperationOutcome.Complete::class.java, response.semantic)
        assertTrue(response.document.toByteArray().size <= 8_000)
        val fitted = (response.semantic as OperationOutcome.Complete).evidence.payload as QueryRunResult
        assertTrue(fitted.items.values.size <= 2)
        assertEquals(120, fitted.invocation!!.accumulatedRowCount)
        assertEquals(fitted.items.values.size, fitted.invocation!!.preview.rowCount)
        assertEquals(fitted.items.values.size, fitted.nextCursor!!.value)
        assertEquals(original.evidence.payload.retention, fitted.retention)
        val publicDocument =
            io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.project(
                OperationOutcome.Complete(original.evidence.copy(payload = fitted))
            ) as io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome.Complete
        val items = Json.parseToJsonElement(publicDocument.document.value).jsonObject.getValue("items")
        assertEquals(
            items.toString().toByteArray(Charsets.UTF_8).size.toLong(),
            fitted.invocation!!.preview.encodedBytes,
        )
    }

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid automatic preview fixture: $failure")
        }
}
