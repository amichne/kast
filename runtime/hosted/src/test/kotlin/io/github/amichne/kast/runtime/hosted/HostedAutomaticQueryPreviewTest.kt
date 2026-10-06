package io.github.amichne.kast.runtime.hosted

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryEvidenceWindowDocument
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedAutomaticQueryPreviewTest {
    @Test
    fun `retained evidence read rejects an allowance that fits only its unchanged cursor envelope`() {
        val semantic = evidenceOnlyRead()
        val empty =
            semantic.copy(
                evidence =
                    semantic.evidence.copy(
                        payload =
                            semantic.evidence.payload.copy(
                                failures =
                                    BoundedProtocolList.create(semantic.evidence.payload.failures.values.take(0))
                                        .refined(),
                                evidenceWindow = evidenceWindow(100),
                            )
                    )
            )
        val envelope =
            HostedResponse.Canonical.encode(CanonicalOperationWireBindings.queryRun, empty)
                as HostedResponse.Canonical<*, *, *>
        val cutoff = ReturnedByteLimit.parse(envelope.document.toByteArray(Charsets.UTF_8).size.toLong()).refined()
        val response =
            encodeHostedQueryResponse(
                semantic,
                maximumBytes = cutoff,
                published = { error("A non-advancing retained read must not publish a page") },
                retain = { error("A retained read must not issue another output continuation") },
            )
        assertInstanceOf(HostedResponse.Oversized::class.java, response)
        val document = Json.parseToJsonElement(response.document).jsonObject
        assertEquals(setOf("type", "failure"), document.keys)
        assertEquals("HOST_REJECTED", document.getValue("type").jsonPrimitive.content)
        assertEquals("RESULT_TOO_LARGE", document.getValue("failure").jsonPrimitive.content)
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(checkNotNull(javaClass.getResource("/ide-hosted/hosted-endpoint.schema.json")).readText())
        assertTrue(schema.validate(response.document, InputFormat.JSON).isEmpty(), response.document)
    }

    @Test
    fun `automatic complete invocation can return an empty retained preview at the envelope cutoff`() {
        val original = HostedQueryRetainedPresentationTest().page(0, 1, 120, longTokens = true)
        fun invocation(rows: Int, bytes: Long) =
            io.github.amichne.kast.protocol.contract.QueryInvocationDocument.create(
                    120,
                    io.github.amichne.kast.protocol.contract.QueryPreviewDocument.Prefix(rows, bytes),
                    io.github.amichne.kast.protocol.contract.QueryInvocationStop.COMPLETED,
                )
                .refined()
        val empty =
            OperationOutcome.Complete(
                original.evidence.copy(
                    payload =
                        original.evidence.payload.presentationPrefix(0).refined().copy(invocation = invocation(0, 2))
                )
            )
        val envelope =
            HostedResponse.Canonical.encode(CanonicalOperationWireBindings.queryRun, empty)
                as HostedResponse.Canonical<*, *, *>
        val cutoff = ReturnedByteLimit.parse(envelope.document.toByteArray(Charsets.UTF_8).size.toLong()).refined()
        val response =
            encodeHostedQueryResponse(
                OperationOutcome.Complete(
                    original.evidence.copy(payload = original.evidence.payload.copy(invocation = invocation(1, 3_000)))
                ),
                maximumBytes = cutoff,
                retain = { error("An already retained invocation must not issue another continuation") },
            )
                as HostedResponse.Canonical<*, *, *>
        assertInstanceOf(OperationOutcome.Complete::class.java, response.semantic)
        assertEquals(envelope.document, response.document)
        val payload = (response.semantic as OperationOutcome.Complete).evidence.payload as QueryRunResult
        assertTrue(payload.items.values.isEmpty())
        assertEquals(0, payload.nextCursor!!.value)
        assertEquals(120, payload.invocation!!.accumulatedRowCount)
        assertEquals(original.evidence.payload.retention, payload.retention)
    }

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
                        evidenceWindow = evidenceWindow(200),
                    )
            ),
            qualification,
        )
    }

    private fun evidenceWindow(end: Int) =
        QueryEvidenceWindowDocument.create(
                QueryEvidenceCursor.parse(100).refined(),
                QueryEvidenceCursor.parse(end).refined(),
                QueryEvidenceCursor.parse(1100).refined(),
            )
            .refined()

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
