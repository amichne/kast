package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactCallbackInputFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowObligationEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationFlow
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure
import io.github.amichne.kast.relation.contract.ValueCallbackInputFailure
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ImpactCallbackInputFailureProjectionTest {
    @Test
    fun `all callback and contract enums survive projection without unknown or generic substitution`() {
        for (cause in CallbackInvocationFlowCause.entries) {
            val projected =
                ValueCallbackInputFailure.Callback(cause).impactDocument().value()
                    as ImpactCallbackInputFailureDocument.Callback
            assertEquals(cause.name, projected.cause.name)
        }
        for (cause in CallbackInvocationFlowFailure.entries) {
            val projected =
                ValueCallbackInputFailure.Contract(cause).impactDocument().value()
                    as ImpactCallbackInputFailureDocument.Contract
            assertEquals(cause.name, projected.cause.name)
        }
    }

    @Test
    fun `obligation encodes exact graph failure rather than a generic nested execution explanation`() {
        val site = ImpactPathProjectionFixture().producer
        val failure = ValueCallbackInputFailure.Graph(StaticCallbackGraphFailure.SupplierIdentityMismatch)
        val obligation = ValueFlowObligation.fromCallbackInput(site, failure).value()
        val projected = obligation.evidence.impactDocument().value()
        val encoded = Json.encodeToString<ImpactFlowObligationEvidenceDocument>(projected)
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("type", "failure"), root.keys)
        assertEquals("CALLBACK_INPUT", root.getValue("type").jsonPrimitive.content)
        val reason = root.getValue("failure").jsonObject
        assertEquals(setOf("type", "cause"), reason.keys)
        assertEquals("GRAPH", reason.getValue("type").jsonPrimitive.content)
        assertEquals(
            "SUPPLIER_IDENTITY_MISMATCH",
            reason.getValue("cause").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            ImpactFlowObligationEvidenceDocument.CallbackInput(
                ImpactCallbackInputFailureDocument.Graph(QueryCallbackGraphCauseDocument.SupplierIdentityMismatch)
            ),
            Json.decodeFromString<ImpactFlowObligationEvidenceDocument>(encoded),
        )
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<ImpactFlowObligationEvidenceDocument>(
                encoded.replace("SUPPLIER_IDENTITY_MISMATCH", "UNKNOWN")
            )
        }
    }

    @Test
    fun `immutable unresolved graph retains its distinct provenance and exact finite obligation`() {
        val f = ImpactPathProjectionFixture()
        val source = f.producer
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(source.enclosing.file, source.range),
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .value() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(source.enclosing.file, source.range, signature).value()
        val flow =
            ImmutableCallbackInvocationFlow.fromCompiler(
                    ImmutableCallbackValueOrigin.Anonymous(body),
                    source,
                    emptyList(),
                    setOf(CallbackInvocationFlowCause.NO_INVOCATION_PROVEN),
                    CallbackInvocationScan.INCOMPLETE,
                )
                .value()
        val projected =
            ValueCallbackInputFailure.Graph(StaticCallbackGraphFailure.ImmutableUnresolved(flow))
                .impactDocument()
                .value() as ImpactCallbackInputFailureDocument.ImmutableGraph
        val encoded =
            Json.parseToJsonElement(Json.encodeToString<ImpactCallbackInputFailureDocument>(projected)).jsonObject
        assertEquals("IMMUTABLE_GRAPH", encoded.getValue("type").jsonPrimitive.content)
        val cause = projected.cause as QueryCallbackGraphCauseDocument.Unresolved
        assertEquals("INCOMPLETE", cause.scan.name)
        assertEquals(listOf("NO_INVOCATION_PROVEN"), cause.obligations.values.map { it.name })
    }

    private fun <T, F> Refinement<T, F>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }
}
