package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionLimitationsDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCompletionWireTest {
    @Test
    fun `original diagnostic allocation survives wire and public rejection without live authority`() {
        val identity =
            io.github.amichne.kast.protocol.contract.QueryDiagnosticReadIdentity.fromBoundary(
                java.util.UUID.fromString("33333333-3333-4333-8333-333333333333")
            )
        val rejection =
            QueryRunRejection.CompletionUnproven(
                QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument.IncompleteExecution,
                QueryCompletionCoverageDocument.Complete,
                QueryInvocationStop.TIME_LIMIT,
                QueryCompletionEvidenceDocument.Unavailable(QueryCompletionRetentionFailure.CAPACITY_EXCEEDED),
                diagnosticReadId = identity,
            )
        val encoded =
            CanonicalQuerySerializers.rejection.encode(rejection, WireValueRole.REJECTION) as WireValueEncoding.Encoded
        assertEquals(
            "33333333-3333-4333-8333-333333333333",
            encoded.value.jsonObject.getValue("detail").jsonObject.getValue("diagnosticReadId").jsonPrimitive.content,
        )
        assertEquals(
            rejection,
            (CanonicalQuerySerializers.rejection.decode(encoded.value, WireValueRole.REJECTION) as WireDecoding.Decoded)
                .value,
        )
        val unobserved =
            CanonicalQuerySerializers.rejection.encode(
                rejection.copy(diagnosticReadId = null),
                WireValueRole.REJECTION,
            ) as WireValueEncoding.Encoded
        assertTrue("diagnosticReadId" !in unobserved.value.jsonObject.getValue("detail").jsonObject)
        val malformed = Json.parseToJsonElement(encoded.value.toString().replace(identity.value, "read"))
        assertTrue(
            CanonicalQuerySerializers.rejection.decode(malformed, WireValueRole.REJECTION) is WireDecoding.Rejected
        )
        val cli =
            io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.project(
                io.github.amichne.kast.kernel.OperationOutcome.Rejected(rejection)
            ) as io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome.Rejected
        val document =
            Json.parseToJsonElement(
                    cli.document.present(io.github.amichne.kast.protocol.contract.ToolOutputDetail.VERBOSE).value
                )
                .jsonObject
        assertTrue("live" !in document)
        assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
        val detail = document.getValue("rejection").jsonObject.getValue("detail").jsonObject
        assertEquals("33333333-3333-4333-8333-333333333333", detail.getValue("diagnosticReadId").jsonPrimitive.content)
        assertEquals("COMPLETE", detail.getValue("originalCoverage").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `retention rejection retains independently completed coverage in wire output`() {
        for (failure in QueryCompletionRetentionFailure.entries) {
            val rejection =
                QueryRunRejection.CompletionUnproven(
                    QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                    io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument.RetentionUnavailable(failure),
                    QueryCompletionCoverageDocument.Complete,
                    QueryInvocationStop.RETENTION_FAILED,
                    QueryCompletionEvidenceDocument.Unavailable(failure),
                )
            val encoded =
                CanonicalQuerySerializers.rejection.encode(rejection, WireValueRole.REJECTION)
                    as WireValueEncoding.Encoded
            val detail = encoded.value.jsonObject.getValue("detail").jsonObject
            val cause = detail.getValue("cause").jsonObject
            assertEquals(setOf("type", "failure"), cause.keys)
            assertEquals("RETENTION_UNAVAILABLE", cause.getValue("type").jsonPrimitive.content)
            assertEquals(failure.name, cause.getValue("failure").jsonPrimitive.content)
            assertEquals(
                "COMPLETE",
                detail.getValue("originalCoverage").jsonObject.getValue("type").jsonPrimitive.content,
            )
            assertEquals("RETENTION_FAILED", detail.getValue("stop").jsonPrimitive.content)
            assertEquals(failure.name, detail.getValue("evidence").jsonObject.getValue("cause").jsonPrimitive.content)
            assertEquals(
                rejection,
                (CanonicalQuerySerializers.rejection.decode(encoded.value, WireValueRole.REJECTION)
                        as WireDecoding.Decoded)
                    .value,
            )
        }
    }

    @Test
    fun `completion rejection retains exact coverage resource cause and evidence only progress`() {
        val rejection =
            QueryRunRejection.CompletionUnproven(
                QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument.IncompleteExecution,
                QueryCompletionCoverageDocument.Qualified(
                    (ProtocolOffset.parse(2) as Refinement.Refined).value,
                    (QueryCompletionLimitationsDocument.from(listOf(QueryLimitationDocument.TIME_LIMIT_REACHED))
                            as Refinement.Refined)
                        .value,
                    QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE),
                ),
                QueryInvocationStop.TIME_LIMIT,
                QueryCompletionEvidenceDocument.Unavailable(QueryCompletionRetentionFailure.CAPACITY_EXCEEDED),
            )
        val encoded =
            CanonicalQuerySerializers.rejection.encode(rejection, WireValueRole.REJECTION) as WireValueEncoding.Encoded
        val shape = encoded.value.jsonObject
        assertEquals("COMPLETION_UNPROVEN", shape.getValue("type").jsonPrimitive.content)
        val detail = shape.getValue("detail").jsonObject
        assertEquals("TIME_LIMIT", detail.getValue("stop").jsonPrimitive.content)
        assertEquals(
            "CAPACITY_EXCEEDED",
            detail.getValue("evidence").jsonObject.getValue("cause").jsonPrimitive.content,
        )
        assertEquals(
            "EVIDENCE_ONLY",
            detail.getValue("policyProgress").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            rejection,
            (CanonicalQuerySerializers.rejection.decode(encoded.value, WireValueRole.REJECTION) as WireDecoding.Decoded)
                .value,
        )
        for (replacement in listOf("UNKNOWN_MODEL", "unknown")) {
            assertTrue(
                CanonicalQuerySerializers.rejection.decode(
                    Json.parseToJsonElement(
                        encoded.value.toString().replace("COMPILER_RESOLVED_STATIC_V1", replacement)
                    ),
                    WireValueRole.REJECTION,
                ) is WireDecoding.Rejected
            )
        }
    }
}
