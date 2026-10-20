package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactFlowObligationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowFailureDocument
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactCallbackUnavailableProjectionTest {
    @Test
    fun `every callback contract rejection preserves its raw finite failure`() {
        val f = ImpactCallbackProjectionCase()
        assertEquals(
            CallbackInvocationFlowFailure.entries.map { it.name }.toSet(),
            QueryCallbackFlowFailureDocument.entries.map { it.name }.toSet(),
        )
        for (failure in CallbackInvocationFlowFailure.entries) {
            val obligation = ValueFlowObligation.fromCallbackContractRejected(f.argument, failure).value()
            val expected =
                if (failure == CallbackInvocationFlowFailure.PROOF_LIMIT_REACHED)
                    ImpactFlowUnsupportedDocument.CALLBACK_PROOF_LIMIT_REACHED
                else ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN
            assertEquals(expected, obligation.cause.impactDocument(), failure.name)
            val evidence =
                obligation.evidence.impactDocument(f.references, f.argument.enclosing.lease).value()
                    as ImpactFlowObligationEvidenceDocument.CallbackContractRejected
            assertEquals(QueryCallbackFlowFailureDocument.valueOf(failure.name), evidence.cause)
        }
    }

    @Test
    fun `a contract rejection keeps its exact type and cause in encoded output`() {
        val f = ImpactCallbackProjectionCase()
        val obligation =
            ValueFlowObligation.fromCallbackContractRejected(
                    f.argument,
                    CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH,
                )
                .value()
        val evidence = obligation.evidence.impactDocument(f.references, f.argument.enclosing.lease).value()
        val encoded = Json.encodeToJsonElement(ImpactFlowObligationEvidenceDocument.serializer(), evidence).jsonObject
        assertEquals(setOf("type", "cause"), encoded.keys)
        assertEquals("CALLBACK_CONTRACT_REJECTED", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("INVALID_FORWARDING_PATH", encoded.getValue("cause").jsonPrimitive.content)
    }

    @Test
    fun `every closed callback refusal keeps its exact cause and outer classification`() {
        val f = ImpactCallbackProjectionCase()
        val expected =
            requireNotNull(javaClass.getResource("/query/impact-callback-unavailable-policy.tsv"))
                .readText()
                .lineSequence()
                .filter(String::isNotEmpty)
                .map { it.split('\t') }
                .toList()
        assertEquals(expected.map { it[0] }, CallbackInvocationFlowCause.entries.map { it.name })
        assertEquals(expected.map { it[0] }, QueryCallbackFlowCauseDocument.entries.map { it.name })
        for ((raw, outer) in expected) {
            val obligation =
                ValueFlowObligation.fromCallbackUnavailable(f.argument, CallbackInvocationFlowCause.valueOf(raw))
                    .value()
            assertEquals(ImpactFlowUnsupportedDocument.valueOf(outer), obligation.cause.impactDocument(), raw)
            val evidence =
                obligation.evidence.impactDocument(f.references, f.argument.enclosing.lease).value()
                    as ImpactFlowObligationEvidenceDocument.CallbackUnavailable
            assertEquals(QueryCallbackFlowCauseDocument.valueOf(raw), evidence.cause)
        }
    }

    @Test
    fun `parameter escape keeps its raw typed evidence in encoded output`() {
        val f = ImpactCallbackProjectionCase()
        val obligation =
            ValueFlowObligation.fromCallbackUnavailable(f.argument, CallbackInvocationFlowCause.PARAMETER_ESCAPES)
                .value()
        val evidence = obligation.evidence.impactDocument(f.references, f.argument.enclosing.lease).value()
        val encoded = Json.encodeToJsonElement(ImpactFlowObligationEvidenceDocument.serializer(), evidence).jsonObject
        assertEquals(ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN, obligation.cause.impactDocument())
        assertEquals(setOf("type", "cause"), encoded.keys)
        assertEquals("CALLBACK_UNAVAILABLE", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("PARAMETER_ESCAPES", encoded.getValue("cause").jsonPrimitive.content)
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
