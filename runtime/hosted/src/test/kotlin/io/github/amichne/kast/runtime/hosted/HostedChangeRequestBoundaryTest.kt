package io.github.amichne.kast.runtime.hosted

import com.google.gson.Gson
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireEncoding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HostedChangeRequestBoundaryTest {
    @Test
    fun `effect requests retain canonical identities and cannot enter hosted reads`() {
        val identity = hostedProtocolText("plan:" + "a".repeat(64))
        val apply =
            assertInstanceOf<WireEncoding.Encoded>(
                    CanonicalOperationWireBindings.changeApply.encodeRequest(ChangeApplyRequest(identity))
                )
                .document
        val recover =
            assertInstanceOf<WireEncoding.Encoded>(
                    CanonicalOperationWireBindings.changeRecover.encodeRequest(ChangeRecoverRequest(identity))
                )
                .document
        val applying = assertInstanceOf<HostedRequest.ApplyChange>(decode("CHANGE_APPLY", apply))
        val recovering = assertInstanceOf<HostedRequest.RecoverChange>(decode("CHANGE_RECOVER", recover))
        assertEquals(identity, applying.request.planIdentity)
        assertEquals(identity, recovering.request.planIdentity)
        assertFalse(decode("CHANGE_APPLY", apply) is HostedRequest.Read)
        assertFalse(decode("CHANGE_RECOVER", recover) is HostedRequest.Read)
        assertFalse("CHANGE_APPLY" in HostedReadCapabilities.operations)
        assertFalse("CHANGE_RECOVER" in HostedReadCapabilities.operations)
    }

    @Test
    fun `caller approval flag cannot replace authenticated assertion`() {
        val document =
            Gson()
                .toJson(
                    mapOf(
                        "type" to "CHANGE_APPLY",
                        "root" to "/workspace",
                        "document" to "{}",
                        "approved" to true,
                    )
                )
        assertEquals(Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST), HostedRequests.decode(document))
    }

    private fun decode(operation: String, document: String): HostedRequest =
        HostedRequests.decode(
                Gson()
                    .toJson(
                        mapOf(
                            "type" to operation,
                            "root" to "/workspace",
                            "document" to document,
                            "approval" to "representation-only-assertion",
                        )
                    )
            )
            .approvalRefined()
}
