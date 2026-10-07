package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryCallbackGraphCauseDocumentTest {
    @Test
    fun `graph causes encode exact finite details independently of result handles`() {
        val obligations =
            (QueryCallbackGraphObligationsDocument.from(listOf(QueryCallbackFlowCauseDocument.RETURNED_CALLBACK))
                    as Refinement.Refined)
                .value
        val cases =
            listOf(
                "UNAVAILABLE" to
                    QueryCallbackGraphCauseDocument.Unavailable(QueryCallbackFlowCauseDocument.STORED_CALLBACK),
                "INVALID_FLOW" to
                    QueryCallbackGraphCauseDocument.InvalidFlow(QueryCallbackFlowFailureDocument.BASIS_MISMATCH),
                "UNRESOLVED" to
                    QueryCallbackGraphCauseDocument.Unresolved(
                        obligations,
                        QueryCallbackInvocationScanDocument.INCOMPLETE,
                    ),
                "UNPROVEN_POLICY" to
                    QueryCallbackGraphCauseDocument.UnprovenPolicy(
                        QueryCallbackGraphPolicyDocument.Unavailable(
                            QueryCallbackNamedUnavailableCauseDocument.UNSUPPORTED_BOUNDARY
                        )
                    ),
                "INCOMPLETE_SCAN" to QueryCallbackGraphCauseDocument.IncompleteScan,
                "UNSUPPORTED_DEFAULT_SUPPLY" to QueryCallbackGraphCauseDocument.UnsupportedDefaultSupply,
                "MISSING_NAMED_OWNER" to QueryCallbackGraphCauseDocument.MissingNamedOwner,
                "SUPPLIER_IDENTITY_MISMATCH" to QueryCallbackGraphCauseDocument.SupplierIdentityMismatch,
                "OUTSIDE_WORKSPACE" to QueryCallbackGraphCauseDocument.OutsideWorkspace,
                "NON_CALLABLE_TARGET" to QueryCallbackGraphCauseDocument.NonCallableTarget,
                "CALLABLE_VALUE_UNPROVEN" to QueryCallbackGraphCauseDocument.CallableValueUnproven,
                "CYCLIC_ROUTE" to QueryCallbackGraphCauseDocument.CyclicRoute,
            )
        for ((type, cause) in cases) {
            val encoded = Json.encodeToJsonElement(QueryCallbackGraphCauseDocument.serializer(), cause)
            assertEquals(type, encoded.jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(cause, Json.decodeFromJsonElement(QueryCallbackGraphCauseDocument.serializer(), encoded))
        }
        val unavailable =
            Json.encodeToJsonElement(QueryCallbackGraphCauseDocument.serializer(), cases.first().second).jsonObject
        assertEquals(setOf("type", "cause"), unavailable.keys)
        assertEquals("STORED_CALLBACK", unavailable.getValue("cause").jsonPrimitive.content)
    }

    @Test
    fun `graph obligation boundary rejects empty duplicate noncanonical and unknown causes`() {
        for (values in
            listOf(
                emptyList(),
                listOf("STORED_CALLBACK", "STORED_CALLBACK"),
                listOf("RETURNED_CALLBACK", "STORED_CALLBACK"),
                listOf("UNKNOWN"),
            )) {
            val encoded = Json.encodeToString(ListSerializer(String.serializer()), values)
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString(QueryCallbackGraphObligationsDocument.serializer(), encoded)
            }
        }
    }

    @Test
    fun `graph cause boundary rejects unknown variants missing detail and unknown native causes`() {
        for (invalid in
            listOf(
                InvalidGraphCause("UNKNOWN"),
                InvalidGraphCause("UNAVAILABLE"),
                InvalidGraphCause("UNAVAILABLE", "UNKNOWN"),
                InvalidGraphCause("INVALID_FLOW", "UNKNOWN"),
            )) {
            val encoded = Json.encodeToJsonElement(InvalidGraphCause.serializer(), invalid)
            assertThrows(SerializationException::class.java) {
                Json.decodeFromJsonElement(QueryCallbackGraphCauseDocument.serializer(), encoded)
            }
        }
    }

    @Test
    fun `excluded policy preserves exact nonempty boundary and rejects reversed wire range`() {
        val file = (ProtocolText.parse("workspace:sample.kt") as Refinement.Refined).value
        val start = (ProtocolOffset.parse(2) as Refinement.Refined).value
        val end = (ProtocolOffset.parse(8) as Refinement.Refined).value
        val range = (SourceRangeDocument.create(start, end) as Refinement.Refined).value
        val policy =
            QueryCallbackGraphPolicyDocument.Excluded(
                QueryCallbackGraphUnprovenExclusionReasonDocument.RETURNED_CALLBACK,
                QueryCallbackGraphBoundaryDocument.from(file, range),
            )
        val encoded = Json.encodeToJsonElement(QueryCallbackGraphPolicyDocument.serializer(), policy).jsonObject
        val boundary = encoded.getValue("excludedBoundary").jsonObject
        assertEquals(setOf("file", "start", "end"), boundary.keys)
        assertEquals("workspace:sample.kt", boundary.getValue("file").jsonPrimitive.content)
        assertEquals("2", boundary.getValue("start").jsonPrimitive.content)
        assertEquals("8", boundary.getValue("end").jsonPrimitive.content)
        val invalid =
            Json.encodeToJsonElement(InvalidGraphBoundary.serializer(), InvalidGraphBoundary(file, end, start))
        assertThrows(SerializationException::class.java) {
            Json.decodeFromJsonElement(QueryCallbackGraphBoundaryDocument.serializer(), invalid)
        }
    }
}

@kotlinx.serialization.Serializable
private data class InvalidGraphBoundary(val file: ProtocolText, val start: ProtocolOffset, val end: ProtocolOffset)

@kotlinx.serialization.Serializable private data class InvalidGraphCause(val type: String, val cause: String? = null)
