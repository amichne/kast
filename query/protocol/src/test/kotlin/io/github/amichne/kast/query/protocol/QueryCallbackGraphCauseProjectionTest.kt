package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryCallbackGraphCauseProjectionTest {
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
