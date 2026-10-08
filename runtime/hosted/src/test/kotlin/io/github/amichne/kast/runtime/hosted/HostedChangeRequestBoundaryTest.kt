package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireEncoding
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HostedChangeRequestBoundaryTest {
    @Test
    fun `local endpoint change requests need no approval credential`() {
        val identity = hostedProtocolText("plan:" + "a".repeat(64))
        val document =
            assertInstanceOf<WireEncoding.Encoded>(
                    CanonicalOperationWireBindings.changeApply.encodeRequest(ChangeApplyRequest(identity))
                )
                .document
        val raw = Json.encodeToString(LocalChangeDocument("CHANGE_APPLY", "/workspace", document))
        val admitted = HostedRequests.decode(raw).approvalRefined()
        assertEquals(identity, assertInstanceOf<HostedRequest.ApplyChange>(admitted).request.planIdentity)
    }

    @Test
    fun `legacy signature field cannot enter the local effect contract`() {
        val identity = hostedProtocolText("plan:" + "a".repeat(64))
        val document =
            assertInstanceOf<WireEncoding.Encoded>(
                    CanonicalOperationWireBindings.changeApply.encodeRequest(ChangeApplyRequest(identity))
                )
                .document
        val raw = Json.encodeToString(LegacyChangeDocument("CHANGE_APPLY", "/workspace", document, "legacy"))
        assertEquals(Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST), HostedRequests.decode(raw))
    }

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
    fun `caller approval flag cannot widen local change request`() {
        val identity = hostedProtocolText("plan:" + "a".repeat(64))
        val request =
            assertInstanceOf<WireEncoding.Encoded>(
                    CanonicalOperationWireBindings.changeApply.encodeRequest(ChangeApplyRequest(identity))
                )
                .document
        val document = Json.encodeToString(FlaggedChangeDocument("CHANGE_APPLY", "/workspace", request, true))
        assertEquals(Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST), HostedRequests.decode(document))
    }

    private fun decode(operation: String, document: String): HostedRequest =
        HostedRequests.decode(Json.encodeToString(LocalChangeDocument(operation, "/workspace", document)))
            .approvalRefined()
}

@Serializable private data class LocalChangeDocument(val type: String, val root: String, val document: String)

@Serializable
private data class LegacyChangeDocument(val type: String, val root: String, val document: String, val approval: String)

@Serializable
private data class FlaggedChangeDocument(
    val type: String,
    val root: String,
    val document: String,
    val approved: Boolean,
)
