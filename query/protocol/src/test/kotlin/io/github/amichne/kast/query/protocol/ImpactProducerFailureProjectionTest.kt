package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.ValueCallbackInputFailure
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueProducerExecutionFailure
import io.github.amichne.kast.relation.contract.ValueProducerSeedDiagnostic
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ImpactProducerFailureProjectionTest {
    @Test
    fun `finite seed causes survive without owner mismatch substitution`() {
        ValueProducerSeedRejection.entries.forEach { cause -> assertEquals(cause.name, cause.impactFailure().name) }
    }

    @Test
    fun `failed producer encodes original operation site and callback cause`() {
        val source = ImpactPathProjectionFixture().producer
        val rejected =
            ValueProducerSeedRead.Rejected(
                ValueProducerSeedRejection.NESTED_EXECUTION,
                ValueProducerSeedDiagnostic.InitialExecution(
                    source.enclosing,
                    source.range,
                    ValueProducerExecutionFailure.Unsupported(
                        ValueFlowUnsupportedCause.NESTED_EXECUTION,
                        listOf(ValueCallbackInputFailure.Callback(CallbackInvocationFlowCause.NO_INVOCATION_PROVEN)),
                    ),
                ),
            )
        val reason = rejected.producerFailure(0).failure as QueryRunRejection.ImpactSourceRejected
        val encoded = Json.encodeToString<QueryImpactSourceFailureDocument>(reason.cause)
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("type", "cause", "position", "enclosing", "anchor", "failure", "stage"), root.keys)
        assertEquals("PRODUCER_EXECUTION", root.getValue("type").jsonPrimitive.content)
        assertEquals("INITIAL_EXECUTION", root.getValue("stage").jsonPrimitive.content)
        assertEquals("NESTED_EXECUTION", root.getValue("cause").jsonPrimitive.content)
        assertEquals("UNSUPPORTED", root.getValue("failure").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(reason.cause, Json.decodeFromString<QueryImpactSourceFailureDocument>(encoded))
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<QueryImpactSourceFailureDocument>(encoded.replace("NESTED_EXECUTION", "UNKNOWN"))
        }
    }
}
