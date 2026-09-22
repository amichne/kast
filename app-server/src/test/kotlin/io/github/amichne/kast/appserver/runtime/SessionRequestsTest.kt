package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.protocol.codex.ProtocolCloseFailure
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SessionRequestsTest {
    @Test
    fun `retirement before admission rejects without registering work`() {
        val requests = SessionRequests()
        assertEquals(SessionRetirement.IDLE, requests.retire())
        assertEquals(Refinement.Rejected(SessionRequestFailure.SESSION_RETIRED), requests.admit(JsonPrimitive(1)))
        assertEquals(emptySet<UpgradeBlocker>(), requests.upgradeBlockers())
    }

    @Test
    fun `admission before retirement preserves uncertainty despite a late response`() {
        val requests = SessionRequests()
        assertInstanceOf(Refinement.Refined::class.java, requests.admit(JsonPrimitive(1)))
        assertEquals(setOf(UpgradeBlocker.REQUEST_PENDING), requests.upgradeBlockers())
        assertEquals(SessionRetirement.UNCERTAIN, requests.retire())
        requests.resolve(JsonPrimitive(1))
        assertEquals(setOf(UpgradeBlocker.RECONCILIATION_REQUIRED), requests.upgradeBlockers())
        assertEquals(SessionRetirement.ALREADY_RETIRED, requests.retire())
    }

    @Test
    fun `response before retirement proves idle and duplicate or invalid IDs cannot erase pending work`() {
        val requests = SessionRequests()
        requests.admit(JsonPrimitive(1))
        assertEquals(Refinement.Rejected(SessionRequestFailure.REQUEST_ID_CONFLICT), requests.admit(JsonPrimitive(1)))
        assertEquals(Refinement.Rejected(SessionRequestFailure.REQUEST_ID_REJECTED), requests.admit(JsonNull))
        requests.resolve(JsonPrimitive("1"))
        assertEquals(setOf(UpgradeBlocker.REQUEST_PENDING), requests.upgradeBlockers())
        requests.resolve(JsonPrimitive(1))
        assertEquals(SessionRetirement.IDLE, requests.retire())
    }

    @Test
    fun `incomplete or rejected response cannot discharge a pending request`() {
        val requests = SessionRequests()
        requests.admit(JsonPrimitive(1))
        requests.resolveReply(
            Json.encodeToJsonElement(IncompleteResponse(1)).jsonObject,
            ProtocolRouting.ForwardDownstream("opaque"),
        )
        val complete = Json.encodeToJsonElement(CompleteResponse(1, "value")).jsonObject
        requests.resolveReply(complete, ProtocolRouting.Close(ProtocolCloseFailure.MalformedUpstream))
        assertEquals(setOf(UpgradeBlocker.REQUEST_PENDING), requests.upgradeBlockers())
        requests.resolveReply(complete, ProtocolRouting.ForwardDownstream("opaque"))
        assertEquals(emptySet<UpgradeBlocker>(), requests.upgradeBlockers())
    }

    @Serializable private data class IncompleteResponse(val id: Int)

    @Serializable private data class CompleteResponse(val id: Int, val result: String)

    @Test
    fun `capacity rejects new identities while an existing response can release its own slot`() {
        val requests = SessionRequests()
        repeat(BrokerOperationalLimits.sessionChannelCapacity) { requests.admit(JsonPrimitive(it)) }
        assertEquals(
            Refinement.Rejected(SessionRequestFailure.REQUEST_CAPACITY_EXCEEDED),
            requests.admit(JsonPrimitive(-1)),
        )
        assertEquals(Refinement.Rejected(SessionRequestFailure.REQUEST_ID_CONFLICT), requests.admit(JsonPrimitive(0)))
        requests.resolve(JsonPrimitive(0))
        assertInstanceOf(Refinement.Refined::class.java, requests.admit(JsonPrimitive(-1)))
    }
}
