package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryCompletionCauseDocumentTest {
    private val graph =
        QueryCallbackGraphFailureDocument(
            QueryCallbackGraphCauseDocument.Unavailable(QueryCallbackFlowCauseDocument.STORED_CALLBACK),
            QueryCallbackObservationOrigin.RELATION,
            (ProtocolOffset.parse(0) as Refinement.Refined).value,
            (ProtocolOffset.parse(1) as Refinement.Refined).value,
        )

    @Test
    fun `completion cases encode only their required proof details`() {
        for ((type, cause) in
            listOf(
                "INCOMPLETE_EXECUTION" to QueryCompletionCauseDocument.IncompleteExecution,
                "ITEM_FAILURE" to QueryCompletionCauseDocument.ItemFailure,
                "OMITTED_EVIDENCE" to QueryCompletionCauseDocument.OmittedEvidence,
            )) {
            val encoded = Json.encodeToJsonElement(QueryCompletionCauseDocument.serializer(), cause).jsonObject
            assertEquals(setOf("type"), encoded.keys)
            assertEquals(type, encoded.getValue("type").jsonPrimitive.content)
        }
        val callback = QueryCompletionCauseDocument.CallbackGraphUnproven(graph)
        val encoded = Json.encodeToJsonElement(QueryCompletionCauseDocument.serializer(), callback).jsonObject
        assertEquals(setOf("type", "graphFailure"), encoded.keys)
        assertEquals("CALLBACK_GRAPH_UNPROVEN", encoded.getValue("type").jsonPrimitive.content)
        assertEquals(
            "STORED_CALLBACK",
            encoded
                .getValue("graphFailure")
                .jsonObject
                .getValue("cause")
                .jsonObject
                .getValue("cause")
                .jsonPrimitive
                .content,
        )
        assertEquals(callback, Json.decodeFromJsonElement(QueryCompletionCauseDocument.serializer(), encoded))
    }

    @Test
    fun `investigation failures encode exact discriminators and require nonempty canonical obligations`() {
        val failures =
            listOf(
                "MISSING_ORIGINAL_INVESTIGATION" to QueryInvestigationCompletionFailureDocument.MissingOriginal,
                "ORIGINAL_PATH_SELECTION_INCOMPLETE" to QueryInvestigationCompletionFailureDocument.SelectionIncomplete,
                "REQUIRED_OBLIGATIONS_UNRESOLVED" to
                    QueryInvestigationCompletionFailureDocument.ObligationsUnresolved(
                        (QueryImpactRequiredObligationsDocument.from(
                                listOf(ImpactRequiredObligationDocument.NATIVE_FLOW)
                            ) as Refinement.Refined)
                            .value
                    ),
            )
        for ((type, failure) in failures) {
            val cause = QueryCompletionCauseDocument.InvestigationUnproven(failure)
            val encoded = Json.encodeToJsonElement(QueryCompletionCauseDocument.serializer(), cause).jsonObject
            assertEquals(setOf("type", "investigationFailure"), encoded.keys)
            assertEquals("INVESTIGATION_UNPROVEN", encoded.getValue("type").jsonPrimitive.content)
            val detail = encoded.getValue("investigationFailure").jsonObject
            assertEquals(type, detail.getValue("type").jsonPrimitive.content)
            assertEquals(
                if (failure is QueryInvestigationCompletionFailureDocument.ObligationsUnresolved)
                    setOf("type", "required")
                else setOf("type"),
                detail.keys,
            )
            assertEquals(cause, Json.decodeFromJsonElement(QueryCompletionCauseDocument.serializer(), encoded))
        }
        for (required in
            listOf(
                emptyList(),
                listOf(ImpactRequiredObligationDocument.NATIVE_FLOW, ImpactRequiredObligationDocument.NATIVE_FLOW),
                listOf(ImpactRequiredObligationDocument.BOUNDARY, ImpactRequiredObligationDocument.NATIVE_FLOW),
            )) {
            val encoded =
                Json.encodeToJsonElement(
                    InvalidInvestigationFailure.serializer(),
                    InvalidInvestigationFailure("REQUIRED_OBLIGATIONS_UNRESOLVED", required),
                )
            assertThrows(SerializationException::class.java) {
                Json.decodeFromJsonElement(QueryInvestigationCompletionFailureDocument.serializer(), encoded)
            }
        }
    }

    @Test
    fun `retention failure preserves each finite storage cause without erasing completion proof`() {
        for (failure in QueryCompletionRetentionFailure.entries) {
            val cause = QueryCompletionCauseDocument.RetentionUnavailable(failure)
            val encoded = Json.encodeToJsonElement(QueryCompletionCauseDocument.serializer(), cause).jsonObject
            assertEquals(setOf("type", "failure"), encoded.keys)
            assertEquals("RETENTION_UNAVAILABLE", encoded.getValue("type").jsonPrimitive.content)
            assertEquals(failure.name, encoded.getValue("failure").jsonPrimitive.content)
            assertEquals(QueryCompletionUnprovenReason.RETENTION_UNAVAILABLE, cause.reason)
        }
        for (failure in listOf(null, "UNKNOWN")) {
            val encoded = Json.encodeToJsonElement(InvalidRetentionCause.serializer(), InvalidRetentionCause(failure))
            assertThrows(SerializationException::class.java) {
                Json.decodeFromJsonElement(QueryCompletionCauseDocument.serializer(), encoded)
            }
        }
    }

    @Test
    fun `completion boundary excludes missing callback detail and unrelated scalar detail`() {
        for (invalid in
            listOf(
                InvalidCompletionCause("UNKNOWN"),
                InvalidCompletionCause("CALLBACK_GRAPH_UNPROVEN"),
                InvalidCompletionCause("INVESTIGATION_UNPROVEN"),
                InvalidCompletionCause("RETENTION_UNAVAILABLE"),
                InvalidCompletionCause("INCOMPLETE_EXECUTION", graph),
            )) {
            val encoded = Json.encodeToJsonElement(InvalidCompletionCause.serializer(), invalid)
            assertThrows(SerializationException::class.java) {
                Json.decodeFromJsonElement(QueryCompletionCauseDocument.serializer(), encoded)
            }
        }
    }
}

@Serializable
private data class InvalidCompletionCause(val type: String, val graphFailure: QueryCallbackGraphFailureDocument? = null)

@Serializable
private data class InvalidInvestigationFailure(val type: String, val required: List<ImpactRequiredObligationDocument>)

/** Negative storage-cause fixture omits or supplies an unknown finite detail. */
@Serializable
private data class InvalidRetentionCause(
    val failure: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
    val type: String = "RETENTION_UNAVAILABLE",
)
