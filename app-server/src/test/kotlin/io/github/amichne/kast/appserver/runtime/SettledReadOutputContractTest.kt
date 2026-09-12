@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerWorkspaceId
import io.github.amichne.kast.appserver.KastToolSelection
import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerCallId
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.BrokerTurnId
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.protocol.MemoryThreadCatalogStore
import io.github.amichne.kast.appserver.protocol.ThreadCatalogBinding
import io.github.amichne.kast.appserver.protocol.codex.CodexOwnedSchema
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolAdapter
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolContracts
import io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.appserver.provider.BrokerProcessExecution
import io.github.amichne.kast.appserver.provider.BrokerProcessExecutor
import io.github.amichne.kast.appserver.provider.BrokerProcessInput
import io.github.amichne.kast.appserver.provider.BrokerProcessRequest
import io.github.amichne.kast.appserver.provider.KastProviderOptions
import io.github.amichne.kast.appserver.provider.KastProviderQualification
import io.github.amichne.kast.appserver.provider.KastProviderQualifier
import io.github.amichne.kast.appserver.provider.capabilitySchema
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.registry.OperationEffect
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
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
            val fixture = Fixture.create(root, this)
            try {
                val first = fixture.submit("first")
                fixture.process.entered.await()
                val second = fixture.submit("second")
                assertEquals(1, fixture.lane().getValue("queued").jsonPrimitive.int)
                assertFalse(second.isCompleted)
                assertEquals(listOf("first"), fixture.process.calls)

                fixture.process.release.complete(Unit)
                val rejected = withTimeout(5_000) { first.await() }.reply()
                val failure = rejected.failureDocument()
                assertEquals(JsonPrimitive("OUTPUT_CONTRACT_REJECTED"), failure["failure"])
                assertEquals(
                    listOf("TYPE" to "DOCUMENT"),
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
                assertEquals(listOf("first", "second", "later"), fixture.process.calls)
                assertEquals(1, fixture.process.maximumActive.get())
                assertEquals(0, fixture.process.active.get())
                assertEquals("idle", fixture.lane().getValue("state").jsonPrimitive.content)
            } finally {
                fixture.process.release.complete(Unit)
                fixture.adapter.closeAndJoin()
            }
        }

    private class Fixture(
        val adapter: CodexProtocolAdapter,
        val executions: WorkspaceExecution,
        val workspace: BrokerWorkspaceId,
        val process: ControlledReadProcess,
    ) {
        fun submit(call: String): Deferred<WorkspaceExecutionResult> =
            executions.submit(
                WorkspaceExecutionIdentity(
                    workspace,
                    ClientConnectionId.fresh(),
                    requireNotNull(BrokerThreadId.admit("thread")),
                    requireNotNull(BrokerTurnId.admit("turn")),
                    requireNotNull(BrokerCallId.admit(call)),
                )
            ) {
                adapter.fromUpstream(
                    Json.encodeToString(
                        CallRequest(
                            1,
                            "item/tool/call",
                            CallParams("thread", "turn", call, "kast", "symbol_lookup", LookupInput(call)),
                        )
                    )
                )
            }

        fun lane() =
            executions
                .snapshot()
                .getValue("lanes")
                .jsonArray
                .map { it.jsonObject }
                .single { it.getValue("workspaceId").jsonPrimitive.content == workspace.value }

        companion object {
            suspend fun create(root: Path, scope: TestScope): Fixture {
                val process = ControlledReadProcess()
                val executable = Files.writeString(root.resolve("kast-fixture"), "#!/bin/sh\nexit 0\n")
                Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
                val options =
                    KastProviderOptions.admit(
                            executable.toRealPath(),
                            root.toRealPath(),
                            process,
                            toolSelection = KastToolSelection.admit("symbol_lookup").refined(),
                        )
                        .refined()
                val qualified = KastProviderQualifier.qualify(options) as KastProviderQualification.Qualified
                assertEquals(OperationEffect.INTELLIJ_READ, qualified.bootstrap.tools.definitions.single().effect)
                val broker = Broker.create(listOf(qualified.registration), BrokerLimits.defaults()).validated()
                val store = MemoryThreadCatalogStore()
                store.write(ThreadCatalogBinding.admit("thread", broker.catalog.digest, root.toRealPath()).refined())
                val contracts =
                    CodexProtocolContracts.define(
                            CodexOwnedSchema.entries.associateWith { OutputContractTestSchemas.objectDocument }
                        )
                        .validated()
                return Fixture(
                    CodexProtocolAdapter(
                        broker,
                        contracts,
                        store,
                        invocationDispatcher = StandardTestDispatcher(scope.testScheduler),
                    ),
                    WorkspaceExecution(scope, WorkspaceExecutionPolicy.Default, scope.testTimeSource),
                    BrokerWorkspaceId.derive(requireNotNull(CanonicalBrokerDirectory.admit(root.toRealPath()))),
                    process,
                )
            }
        }
    }

    private class ControlledReadProcess : BrokerProcessExecutor {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<String>()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()

        override suspend fun execute(request: BrokerProcessRequest): BrokerProcessExecution =
            when (request.arguments) {
                listOf("--version") -> BrokerProcessExecution.Completed(0, "kast 9.9.9\n", "")
                listOf("--schema") -> BrokerProcessExecution.Completed(0, capabilitySchema(), "")
                else -> invoke(request)
            }

        private suspend fun invoke(request: BrokerProcessRequest): BrokerProcessExecution {
            assertEquals(listOf("symbol", "discover"), request.arguments)
            val input = Json.decodeFromString<LookupInput>((request.input as BrokerProcessInput.Document).value)
            calls += input.query
            maximumActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            return try {
                if (input.query == "first") {
                    entered.complete(Unit)
                    release.await()
                    // Intentionally incompatible output independent of continuation formats.
                    BrokerProcessExecution.Completed(0, "\"invalid document\"", "")
                } else BrokerProcessExecution.Completed(0, Json.encodeToString(LookupOutput(input.query)), "")
            } finally {
                active.decrementAndGet()
            }
        }
    }

    @Serializable private data class LookupInput(val query: String)

    @Serializable private data class LookupOutput(val query: String)

    @Serializable private data class CallRequest(val id: Int, val method: String, val params: CallParams)

    @Serializable
    private data class CallParams(
        val threadId: String,
        val turnId: String,
        val callId: String,
        val namespace: String,
        val tool: String,
        val arguments: LookupInput,
    )

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

    companion object {
        private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value

        private fun <T, F> Validation<T, F>.validated(): T = (this as Validation.Validated).value
    }
}
