package io.github.amichne.kast.appserver.provider

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
    fun `retired relation route is rejected while query reaches request admission`(@TempDir root: Path) = runTest {
        assertRoutes(root = root, removed = "read_relations", preferred = "query_symbols")
    }

    @Test
    fun `old traversal name is rejected while the published name reaches request admission`(@TempDir root: Path) =
        runTest {
            assertRoutes(
                root = root,
                removed = "impact_analyze",
                preferred = "traverse_relations",
            )
        }

    @Test
    fun `published inputs cannot bypass a different bound catalog`(@TempDir root: Path) = runTest {
        val (broker, executor) = fixture(root)
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
    }

    private suspend fun assertRoutes(root: Path, removed: String, preferred: String) {
        val (broker, executor) = fixture(root)
        val context =
            BrokerInvocationContext.admit(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    callId = "call-1",
                    workingDirectory = root.toRealPath(),
                )
                .refined()
        val arguments = Json.encodeToJsonElement(EmptyArguments).jsonObject
        for (name in listOf(removed, preferred)) {
            val result =
                broker.dispatch(
                    BrokerDispatchRequest(
                        ToolAddress(ProviderNamespace.admit("kast").refined(), ToolName.admit(name).refined()),
                        arguments,
                        context,
                    )
                )
            val rejected = assertInstanceOf(BrokerDispatch.Rejected::class.java, result, "$name: $result")
            if (name == removed) {
                assertInstanceOf(BrokerFailure.UnknownTool::class.java, rejected.failure)
            } else if (name == "query_symbols") {
                assertInstanceOf(BrokerFailure.InvalidArguments::class.java, rejected.failure)
            } else {
                val failure = assertInstanceOf(BrokerFailure.ProviderInvocationRejected::class.java, rejected.failure)
                assertEquals(ProviderFailureCode.IDE_INVALID_REQUEST, failure.code)
            }
        }

        assertEquals(
            listOf(preferred),
            broker.catalog.namespaces
                .single()
                .tools
                .filter { it.name.value in setOf(removed, preferred) }
                .map { it.name.value },
        )
    }

    private suspend fun fixture(root: Path): RoutingFixture {
        val executable = Files.writeString(root.resolve("kast"), "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        val executor = RecordingCatalogSource(installedKastCatalogFixture())

        val options = KastProviderOptions(catalogSource = executor)
        val provider =
            assertInstanceOf(KastProviderQualification.Qualified::class.java, KastProviderQualifier.qualify(options))
        val broker =
            when (val admitted = Broker.create(listOf(provider.registration), BrokerLimits.defaults())) {
                is Validation.Validated -> admitted.value
                is Validation.Rejected -> fail("Expected admitted broker: $admitted")
            }
        return RoutingFixture(broker, executor)
    }

    private data class RoutingFixture(val broker: Broker, val executor: RecordingCatalogSource)

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

    private val readNames = listOf("query_symbols", "traverse_relations")

    @Serializable private data object EmptyArguments

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> fail("Expected refinement: $failure")
        }
}
