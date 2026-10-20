package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryCallbackGraphCauseProjectionTest {
    @Test
    fun `proof limits keep their finite callback and impact wire names`() {
        val failure = CallbackInvocationFlowFailure.PROOF_LIMIT_REACHED.protocolCallbackDocument()
        val cause = CallbackInvocationFlowCause.PROOF_LIMIT_REACHED.protocolCallbackDocument()
        assertEquals(QueryCallbackFlowFailureDocument.PROOF_LIMIT_REACHED, failure)
        assertEquals(QueryCallbackFlowCauseDocument.PROOF_LIMIT_REACHED, cause)
        assertEquals(
            JsonPrimitive("PROOF_LIMIT_REACHED"),
            Json.encodeToJsonElement(QueryCallbackFlowFailureDocument.serializer(), failure),
        )
        assertEquals(
            JsonPrimitive("PROOF_LIMIT_REACHED"),
            Json.encodeToJsonElement(QueryCallbackFlowCauseDocument.serializer(), cause),
        )
        val unsupported = ValueFlowUnsupportedCause.CALLBACK_PROOF_LIMIT_REACHED
        assertEquals(ImpactFlowUnsupportedDocument.CALLBACK_PROOF_LIMIT_REACHED, unsupported.impactDocument())
        assertEquals(QueryImpactSourceFailureCode.CALLBACK_PROOF_LIMIT_REACHED, unsupported.boundaryFailure())
    }

    @Test
    fun `known unavailable and invalid flow causes survive exhaustive projection`() {
        assertEquals(
            QueryCallbackGraphCauseDocument.Unavailable(QueryCallbackFlowCauseDocument.STORED_CALLBACK),
            (StaticCallbackGraphFailure.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK).protocolGraphCause()
                    as Refinement.Refined)
                .value,
        )
        assertEquals(
            QueryCallbackGraphCauseDocument.InvalidFlow(QueryCallbackFlowFailureDocument.BASIS_MISMATCH),
            (StaticCallbackGraphFailure.InvalidFlow(CallbackInvocationFlowFailure.BASIS_MISMATCH).protocolGraphCause()
                    as Refinement.Refined)
                .value,
        )
    }

    @Test
    fun `try completion refusals retain exact callback cause`() {
        for ((native, wire) in
            listOf(
                CallbackInvocationFlowCause.FINALLY_UNSUPPORTED to QueryCallbackFlowCauseDocument.FINALLY_UNSUPPORTED,
                CallbackInvocationFlowCause.ABRUPT_COMPLETION to QueryCallbackFlowCauseDocument.ABRUPT_COMPLETION,
            )) assertEquals(
            QueryCallbackGraphCauseDocument.Unavailable(wire),
            (StaticCallbackGraphFailure.Unavailable(native).protocolGraphCause() as Refinement.Refined).value,
        )
    }

    @Test
    fun `admitted named policies cannot become unproven policy failure documents`() {
        assertEquals(
            Refinement.Rejected(
                io.github.amichne.kast.protocol.contract.QueryCallbackGraphProjectionFailureDocument
                    .ADMITTED_DIRECT_POLICY_REJECTED
            ),
            StaticCallbackGraphFailure.UnprovenPolicy(
                    io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy.AdmittedDirect
                )
                .protocolGraphCause(),
        )
        assertEquals(
            Refinement.Rejected(
                io.github.amichne.kast.protocol.contract.QueryCallbackGraphProjectionFailureDocument
                    .ADMITTED_INLINE_POLICY_REJECTED
            ),
            StaticCallbackGraphFailure.UnprovenPolicy(
                    io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy.AdmittedInline
                )
                .protocolGraphCause(),
        )
    }
}
