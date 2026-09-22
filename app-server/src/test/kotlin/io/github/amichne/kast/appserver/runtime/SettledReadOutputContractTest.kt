@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SettledReadOutputContractTest {
    @Test
    fun `settled schema invalid read rejects only its invocation and drains the serialized lane`(@TempDir root: Path) =
        runTest {
            val fixture =
                OutputContractExecutionFixture.create(
                    root,
                    this,
                    BrokerOperationEffect.Canonical(
                        io.github.amichne.kast.protocol.registry.OperationEffect.INTELLIJ_READ
                    ),
                    FixtureTermination.INVALID_OUTPUT,
                )
            try {
                val first = fixture.submit("first")
                fixture.entered.await()
                val second = fixture.submit("second")
                assertEquals(1, fixture.lane().getValue("queued").jsonPrimitive.int)
                assertFalse(second.isCompleted)
                assertEquals(listOf("first"), fixture.calls)

                fixture.release.complete(Unit)
                val rejected = withTimeout(5_000) { first.await() }.reply()
                val failure = rejected.failureDocument()
                assertEquals(JsonPrimitive("OUTPUT_CONTRACT_REJECTED"), failure["failure"])
                assertEquals(
                    listOf("PATTERN" to "CONTINUATION"),
                    failure.getValue("outputViolationEvidence").jsonObject.getValue("observations").jsonArray.map {
                        it.jsonObject.getValue("keyword").jsonPrimitive.content to
                            it.jsonObject.getValue("field").jsonPrimitive.content
                    },
                )
                assertEquals(
                    InvocationCertainty.KNOWN,
                    rejected.certainty,
                    "settled read output rejection quarantined the workspace",
                )
                assertSuccess(withTimeout(5_000) { second.await() })
                assertSuccess(withTimeout(5_000) { fixture.submit("later").await() })
                assertEquals(listOf("first", "second", "later"), fixture.calls)
                assertEquals(1, fixture.maximumActive)
                assertEquals(0, fixture.active)
                assertEquals("idle", fixture.lane().getValue("state").jsonPrimitive.content)
            } finally {
                fixture.release.complete(Unit)
                fixture.close()
            }
        }

    private fun WorkspaceExecutionResult.reply() =
        (this as WorkspaceExecutionResult.Completed).routing as ProtocolRouting.ReplyUpstream

    private fun ProtocolRouting.ReplyUpstream.result() =
        Json.parseToJsonElement(message).jsonObject.getValue("result").jsonObject

    private fun ProtocolRouting.ReplyUpstream.failureDocument(): JsonObject {
        assertEquals(JsonPrimitive(false), result()["success"])
        return Json.parseToJsonElement(
                result().getValue("contentItems").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            )
            .jsonObject
    }

    private fun assertSuccess(result: WorkspaceExecutionResult) {
        assertEquals(JsonPrimitive(true), result.reply().result()["success"])
    }
}
