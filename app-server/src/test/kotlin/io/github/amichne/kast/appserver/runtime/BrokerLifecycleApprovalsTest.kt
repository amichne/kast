package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BrokerLifecycleApprovalsTest {
    @Test
    fun `only one exact controller acceptance produces project close authority`(@TempDir root: Path) = runBlocking {
        for (decision in listOf("accept", "acceptForSession", "decline", "cancel")) {
            var signed = 0
            val fixture =
                HubTestFixture(
                    root,
                    closeSigner = { proof ->
                        signed++
                        Refinement.Refined(ProjectCloseApprovalGrant.signed(proof, "signed"))
                    },
                )
            try {
                val peer = fixture.connect()
                fixture.bind(peer, "thread/start")
                val target = IdeProjectTarget("host", "incarnation", root.toRealPath().toString())
                val request = WorkspaceLifecycleRequest.RequestUserClose(target, "close")
                val json = Json { encodeDefaults = true }
                peer.upstream.received.send(
                    BrokerUpstreamFrame.Text(json.encodeToString(Call(params = Params(arguments = request))))
                )
                val started = Json.parseToJsonElement(withTimeout(3000) { peer.session.output.receive() }).jsonObject
                assertEquals(
                    "commandExecution",
                    started["params"]!!.jsonObject["item"]!!.jsonObject["type"]!!.jsonPrimitive.content,
                )
                val prompt = Json.parseToJsonElement(withTimeout(3000) { peer.session.output.receive() }).jsonObject
                assertEquals("item/commandExecution/requestApproval", prompt["method"]!!.jsonPrimitive.content)
                assertTrue(prompt["params"]!!.jsonObject["reason"]!!.jsonPrimitive.content.contains(target.project))
                assertEquals(0, signed)
                peer.session.accept(
                    json.encodeToString(Answer(prompt["id"]!!.jsonPrimitive.content, Decision(decision)))
                )
                withTimeout(3000) { peer.upstream.sent.receive() }
                assertEquals(if (decision == "accept") 1 else 0, signed)
                if (decision == "accept") {
                    val grant = (fixture.approvedInvocations.single() as BrokerInvocationApproval.ProjectClose).grant
                    assertEquals(request, grant.approval.request)
                    assertTrue(grant.matches(grant.approval.invocation, request))
                    assertFalse(
                        grant.matches(
                            grant.approval.invocation,
                            request.copy(target = target.copy(project = "successor")),
                        )
                    )
                } else assertTrue(fixture.approvedInvocations.isEmpty())
            } finally {
                fixture.hub.close()
            }
        }
    }
}

@Serializable private data class Call(val params: Params, val id: Int = 70, val method: String = "item/tool/call")

@Serializable
private data class Params(
    val arguments: WorkspaceLifecycleRequest,
    val threadId: String = "thread-1",
    val turnId: String = "turn-close",
    val callId: String = "close",
    val namespace: String = "kast",
    val tool: String = "workspace_lifecycle",
)

@Serializable private data class Answer(val id: String, val result: Decision)

@Serializable private data class Decision(val decision: String)
