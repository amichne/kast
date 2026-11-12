package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ImpactNativeOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactNativePrerequisiteDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeRelationOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeRelationRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeUnexpectedKindDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ImpactNativeFailureWireTest {
    @Test
    fun `every native failure variant encodes its discriminator and required originating evidence`() {
        val expected =
            Json.parseToJsonElement(
                checkNotNull(javaClass.getResource("/impact-native-failures.expected.json")).readText()
            )
        val actual =
            wireJson.encodeToJsonElement(ListSerializer(ImpactNativeReadRejectionDocument.serializer()), cases())
        assertEquals(expected, actual)
    }

    @Test
    fun `old generic failures unknown tags and omitted supporting evidence cannot decode`() {
        val malformed =
            Json.parseToJsonElement(
                    checkNotNull(javaClass.getResource("/impact-native-failures.invalid.json")).readText()
                )
                .jsonArray
        for (input in malformed) {
            assertThrows(SerializationException::class.java) {
                wireJson.decodeFromString(ImpactNativeReadRejectionDocument.serializer(), input.toString())
            }
        }
    }

    private fun cases(): List<ImpactNativeReadRejectionDocument> =
        listOf(
            ImpactNativeReadRejectionDocument.STALE_SITE,
            ImpactNativeReadRejectionDocument.OUTSIDE_DOMAIN,
            ImpactNativeReadRejectionDocument.OWNER_UNAVAILABLE,
            ImpactNativeReadRejectionDocument.AUTHORITY_MOVED,
            ImpactNativeReadRejectionDocument.UNSUPPORTED_SEED,
            ImpactNativeReadRejectionDocument.UNRESOLVED_SEED,
            ImpactNativeReadRejectionDocument.GRANT_TOO_SMALL,
            ImpactNativeReadRejectionDocument.NESTED_EXECUTION,
            ImpactNativeReadRejectionDocument.HostUnavailable(
                ImpactNativeOperationDocument.FLOW_READ,
                ImpactNativePrerequisiteDocument.PROJECT_DISPOSED,
            ),
            ImpactNativeReadRejectionDocument.HostUnavailable(
                ImpactNativeOperationDocument.MODEL_SITE,
                ImpactNativePrerequisiteDocument.INDEXES_UNAVAILABLE,
            ),
            ImpactNativeReadRejectionDocument.Unexpected(
                operation = ImpactNativeOperationDocument.FLOW_READ,
                kind = ImpactNativeUnexpectedKindDocument.RUNTIME,
                exceptionType = "java.lang.IllegalStateException",
                adapterFrames = listOf("io.github.amichne.kast.Adapter.read:12"),
            ),
            ImpactNativeReadRejectionDocument.Unexpected(
                operation = ImpactNativeOperationDocument.PRODUCER_SEED,
                kind = ImpactNativeUnexpectedKindDocument.LINKAGE,
                exceptionType = "java.lang.NoSuchMethodError",
                adapterFrames = emptyList(),
            ),
            ImpactNativeReadRejectionDocument.RelationRejected(
                ImpactNativeRelationOriginDocument.IMPLEMENTATION_DISPATCH,
                ImpactNativeRelationRejectionDocument.WORKSPACE_INDEX_UNAVAILABLE,
            ),
            ImpactNativeReadRejectionDocument.RelationRejected(
                ImpactNativeRelationOriginDocument.RETURN_CALLER_REFERENCES,
                ImpactNativeRelationRejectionDocument.COMPILER_CONTRACT_VIOLATION,
            ),
            ImpactNativeReadRejectionDocument.PortUnconfigured(ImpactNativeOperationDocument.FLOW_READ),
        )
}
