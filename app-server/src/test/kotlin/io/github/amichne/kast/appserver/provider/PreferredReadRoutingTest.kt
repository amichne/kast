package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.KastToolSelection
import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerDispatch
import io.github.amichne.kast.appserver.core.BrokerDispatchRequest
import io.github.amichne.kast.appserver.core.BrokerFailure
import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.CatalogDigest
import io.github.amichne.kast.appserver.core.ProviderFailureCode
import io.github.amichne.kast.appserver.core.ProviderNamespace
import io.github.amichne.kast.appserver.core.ToolAddress
import io.github.amichne.kast.appserver.core.ToolName
import io.github.amichne.kast.appserver.installedKastCatalogFixture
import io.github.amichne.kast.appserver.protocol.MemoryThreadCatalogStore
import io.github.amichne.kast.appserver.protocol.ThreadCatalogBinding
import io.github.amichne.kast.appserver.protocol.codex.CodexOwnedSchema
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolAdapter
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolContracts
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Registration fixture proves name routing and canonical request admission, not semantic execution. */
class PreferredReadRoutingTest {
    @Test
    fun `old and preferred relation input reach the same canonical request admission`(@TempDir root: Path) = runTest {
        assertRoutes(root = root, operation = "relation.read", legacy = "semantic_query", preferred = "read_relations")
    }

    @Test
    fun `old and preferred traversal input reach the same canonical request admission`(@TempDir root: Path) = runTest {
        assertRoutes(
            root = root,
            operation = "traversal.run",
            legacy = "impact_analyze",
            preferred = "traverse_relations",
        )
    }

    @Test
    fun `old and preferred inputs cannot expose omitted tools`(@TempDir root: Path) = runTest {
        val (broker, executor) = fixture(root, "source.read", KastToolSelection.admit("source_read").refined())
        val context =
            BrokerInvocationContext.admit(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    callId = "call-1",
                    workingDirectory = root.toRealPath(),
                )
                .refined()
        for (name in readNames) {
            val address = ToolAddress(ProviderNamespace.admit("kast").refined(), ToolName.admit(name).refined())
            assertEquals(
                BrokerDispatch.Rejected(BrokerFailure.UnknownTool(address)),
                broker.dispatch(
                    BrokerDispatchRequest(address, Json.encodeToJsonElement(EmptyArguments).jsonObject, context)
                ),
            )
        }
        assertEquals(0, executor.requests.count { !it.arguments.first().startsWith("--") })
    }

    @Test
    fun `old and preferred inputs cannot bypass a different bound catalog`(@TempDir root: Path) = runTest {
        val (broker, executor) = fixture(root, "relation.read")
        val store = MemoryThreadCatalogStore()
        store.write(
            ThreadCatalogBinding.admit("thread-1", CatalogDigest.derive("different catalog"), root.toRealPath())
                .refined()
        )
        val schema = Json.encodeToJsonElement(ObjectSchema("object")).jsonObject
        val contracts = CodexProtocolContracts.define(CodexOwnedSchema.entries.associateWith { schema }).validated()
        val adapter = CodexProtocolAdapter(broker, contracts, store)
        for (name in readNames) {
            val request =
                ToolCall(
                    9,
                    "item/tool/call",
                    ToolCallParams(
                        threadId = "thread-1",
                        turnId = "turn-1",
                        callId = "call-1",
                        namespace = "kast",
                        tool = name,
                        arguments = EmptyArguments,
                    ),
                )
            val routing =
                assertInstanceOf(
                    ProtocolRouting.ReplyUpstream::class.java,
                    adapter.fromUpstream(Json.encodeToString(request)),
                )
            val result = Json.parseToJsonElement(routing.message).jsonObject.getValue("result").jsonObject
            assertEquals(false, result.getValue("success").jsonPrimitive.content.toBoolean())
            val content =
                result.getValue("contentItems").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            assertEquals(
                "CATALOG_INCOMPATIBLE",
                Json.parseToJsonElement(content).jsonObject.getValue("failure").jsonPrimitive.content,
            )
        }
        assertEquals(0, executor.requests.count { !it.arguments.first().startsWith("--") })
    }

    private suspend fun assertRoutes(root: Path, operation: String, legacy: String, preferred: String) {
        val (broker, executor) = fixture(root, operation)
        val context =
            BrokerInvocationContext.admit(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    callId = "call-1",
                    workingDirectory = root.toRealPath(),
                )
                .refined()
        val arguments = Json.encodeToJsonElement(EmptyArguments).jsonObject
        for (name in listOf(legacy, preferred)) {
            val result =
                broker.dispatch(
                    BrokerDispatchRequest(
                        ToolAddress(ProviderNamespace.admit("kast").refined(), ToolName.admit(name).refined()),
                        arguments,
                        context,
                    )
                )
            val rejected = assertInstanceOf(BrokerDispatch.Rejected::class.java, result, "$name: $result")
            val failure = assertInstanceOf(BrokerFailure.ProviderInvocationRejected::class.java, rejected.failure)
            assertEquals(ProviderFailureCode.IDE_INVALID_REQUEST, failure.code)
        }
        val calls = executor.requests.filterNot { it.arguments.first().startsWith("--") }
        assertEquals(emptyList<BrokerProcessRequest>(), calls)
        assertEquals(
            listOf(preferred),
            broker.catalog.namespaces
                .single()
                .tools
                .filter { it.name.value in setOf(legacy, preferred) }
                .map { it.name.value },
        )
    }

    private suspend fun fixture(
        root: Path,
        operation: String,
        selection: KastToolSelection = KastToolSelection.defaults(),
    ): RoutingFixture {
        val executable = Files.writeString(root.resolve("kast"), "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        val executor =
            RecordingProcessExecutor(
                installedKastCatalogFixture(),
                invocationDocument = Json.encodeToString(RouteRejection(operation, "rejected", "workspace-not-ready")),
            )
        val options =
            KastProviderOptions.admit(
                    executable = executable.toRealPath(),
                    qualificationDirectory = root.toRealPath(),
                    processExecutor = executor,
                    toolSelection = selection,
                )
                .refined()
        val provider =
            assertInstanceOf(KastProviderQualification.Qualified::class.java, KastProviderQualifier.qualify(options))
        val broker =
            when (val admitted = Broker.create(listOf(provider.registration), BrokerLimits.defaults())) {
                is Validation.Validated -> admitted.value
                is Validation.Rejected -> fail("Expected admitted broker: $admitted")
            }
        return RoutingFixture(broker, executor)
    }

    private data class RoutingFixture(val broker: Broker, val executor: RecordingProcessExecutor)

    @Serializable private data class ObjectSchema(val type: String)

    @Serializable private data class ToolCall(val id: Int, val method: String, val params: ToolCallParams)

    @Serializable
    private data class ToolCallParams(
        val threadId: String,
        val turnId: String,
        val callId: String,
        val namespace: String,
        val tool: String,
        val arguments: EmptyArguments,
    )

    private fun <T, F> Validation<T, F>.validated(): T =
        when (this) {
            is Validation.Validated -> value
            is Validation.Rejected -> fail("Expected validation: $this")
        }

    private val readNames = listOf("semantic_query", "read_relations", "impact_analyze", "traverse_relations")

    @Serializable private data object EmptyArguments

    @Serializable
    private data class RouteRejection(
        val operation: String,
        val status: String,
        val reason: String,
    )

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> fail("Expected refinement: $failure")
        }
}
