@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.OperationEffect
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OutputContractRecoveryPolicyTest {
    @Test
    fun `all possible writing effects and unknown effects quarantine without replay and preserve other workspaces`(
        @TempDir root: Path
    ) = runTest {
        val effects =
            listOf(
                    OperationEffect.INTELLIJ_READ_AND_PERSISTENCE_WRITE,
                    OperationEffect.INTELLIJ_WRITE,
                    OperationEffect.FILESYSTEM_WRITE,
                    OperationEffect.PERSISTENCE_WRITE,
                    OperationEffect.WORKSPACE_MODEL_WRITE,
                    OperationEffect.PROCESS_CONTROL,
                )
                .map { BrokerOperationEffect.Canonical(it) } + BrokerOperationEffect.Unknown
        for ((index, effect) in effects.withIndex()) {
            val fixture =
                OutputContractExecutionFixture.create(
                    Files.createDirectory(root.resolve("case-$index")),
                    this,
                    effect,
                    FixtureTermination.MUTATION_THEN_INVALID_OUTPUT,
                )
            try {
                val first = fixture.submit("first")
                fixture.entered.await()
                val queued = fixture.submit("queued")
                assertEquals(1, fixture.lane().getValue("queued").jsonPrimitive.int)
                fixture.release.complete(Unit)
                val rejection = first.await().reply()
                assertPatternEvidence(rejection)
                assertEquals(InvocationCertainty.UNCERTAIN, rejection.certainty, effect.toString())
                assertRecovery(queued.await())
                assertRecovery(fixture.submit("later").await())
                assertEquals(listOf("first"), fixture.calls)
                assertEquals(1, fixture.mutations)
                assertEquals("recovery_required", fixture.lane().getValue("state").jsonPrimitive.content)
                assertSuccess(fixture.submit("independent", fixture.otherWorkspace).await())
                assertEquals(listOf("first", "independent"), fixture.calls)
                assertEquals(1, fixture.maximumActive)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `both canonical read-only effects preserve pattern evidence while allowing subsequent calls`(
        @TempDir root: Path
    ) = runTest {
        for (effect in listOf(OperationEffect.NONE, OperationEffect.INTELLIJ_READ)) {
            val fixture =
                OutputContractExecutionFixture.create(
                    Files.createDirectory(root.resolve(effect.name)),
                    this,
                    BrokerOperationEffect.Canonical(effect),
                    FixtureTermination.INVALID_OUTPUT,
                )
            try {
                val first = fixture.submit("first")
                fixture.entered.await()
                val queued = fixture.submit("queued")
                fixture.release.complete(Unit)
                val rejection = first.await().reply()
                assertPatternEvidence(rejection)
                assertEquals(InvocationCertainty.KNOWN, rejection.certainty)
                assertSuccess(queued.await())
                assertEquals(listOf("first", "queued"), fixture.calls)
                assertEquals(1, fixture.maximumActive)
                assertEquals("idle", fixture.lane().getValue("state").jsonPrimitive.content)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `cancelled read with unproven termination never grants queued or later work`(@TempDir root: Path) = runTest {
        val fixture =
            OutputContractExecutionFixture.create(
                root,
                this,
                BrokerOperationEffect.Canonical(OperationEffect.INTELLIJ_READ),
                FixtureTermination.AWAIT_CANCELLATION,
            )
        try {
            val first = fixture.submit("first")
            fixture.entered.await()
            val queued = fixture.submit("queued")
            fixture.release.complete(Unit)
            runCurrent()
            fixture.cancelFirst()
            fixture.retiring.await()
            runCurrent()
            assertEquals(1, fixture.active, "provider retirement gate must still be held")
            assertRecovery(queued.await())
            assertRecovery(fixture.submit("later").await())
            assertEquals(listOf("first"), fixture.calls)
            assertEquals(
                WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.WORKSPACE_OUTCOME_UNCERTAIN),
                first.await(),
            )
            fixture.retire.complete(Unit)
            runCurrent()
            assertEquals(0, fixture.active)
            assertRecovery(fixture.submit("after-retirement").await())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `timed out read keeps its permit through retirement and remains recovery required`(@TempDir root: Path) =
        runTest {
            val budget = (ElapsedTimeLimitMillis.parse(100) as Refinement.Refined).value
            val fixture =
                OutputContractExecutionFixture.create(
                    root,
                    this,
                    BrokerOperationEffect.Canonical(OperationEffect.INTELLIJ_READ),
                    FixtureTermination.AWAIT_CANCELLATION,
                    budget,
                )
            try {
                val first = fixture.submit("first")
                fixture.entered.await()
                val queued = fixture.submit("queued")
                fixture.release.complete(Unit)
                runCurrent()
                advanceTimeBy(100)
                runCurrent()
                assertTrue(fixture.retiring.isCompleted)
                assertFalse(first.isCompleted)
                assertFalse(queued.isCompleted)
                assertEquals(1, fixture.active)
                assertEquals(listOf("first"), fixture.calls)
                fixture.retire.complete(Unit)
                val rejection = first.await().reply()
                assertEquals(InvocationCertainty.UNCERTAIN, rejection.certainty)
                assertEquals(JsonPrimitive("TIMED_OUT"), rejection.failureDocument()["failure"])
                assertRecovery(queued.await())
                assertRecovery(fixture.submit("later").await())
                assertEquals(0, fixture.active)
                assertEquals(1, fixture.maximumActive)
            } finally {
                fixture.close()
            }
        }

    private fun assertPatternEvidence(reply: ProtocolRouting.ReplyUpstream) {
        val failure = reply.failureDocument()
        assertEquals(JsonPrimitive("OUTPUT_CONTRACT_REJECTED"), failure["failure"])
        assertEquals(
            listOf("PATTERN" to "CONTINUATION"),
            failure.getValue("outputViolationEvidence").jsonObject.getValue("observations").jsonArray.map {
                it.jsonObject.getValue("keyword").jsonPrimitive.content to
                    it.jsonObject.getValue("field").jsonPrimitive.content
            },
        )
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

    private fun assertSuccess(result: WorkspaceExecutionResult) =
        assertEquals(JsonPrimitive(true), result.reply().result()["success"])

    private fun assertRecovery(result: WorkspaceExecutionResult) =
        assertEquals(WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.WORKSPACE_RECOVERY_REQUIRED), result)
}
