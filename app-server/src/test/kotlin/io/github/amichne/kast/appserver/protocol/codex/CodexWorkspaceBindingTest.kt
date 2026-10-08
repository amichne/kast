package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.BrokerTool
import io.github.amichne.kast.appserver.core.ProviderCall
import io.github.amichne.kast.appserver.core.ProviderNamespace
import io.github.amichne.kast.appserver.core.ProviderRegistration
import io.github.amichne.kast.appserver.core.ProviderStartup
import io.github.amichne.kast.appserver.core.ProviderVersion
import io.github.amichne.kast.appserver.core.ToolDescription
import io.github.amichne.kast.appserver.core.ToolLoading
import io.github.amichne.kast.appserver.core.ToolName
import io.github.amichne.kast.appserver.core.ToolPresentation
import io.github.amichne.kast.appserver.protocol.MemoryThreadCatalogStore
import io.github.amichne.kast.appserver.protocol.ThreadBindingOwner
import io.github.amichne.kast.appserver.protocol.ThreadCatalogBinding
import io.github.amichne.kast.appserver.protocol.ThreadStoreRead
import io.github.amichne.kast.appserver.schema.JsonDomainDefinition
import io.github.amichne.kast.appserver.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CodexWorkspaceBindingTest {
    @Test
    fun `legacy home binding cannot resume or admit tools and is never retargeted`(@TempDir temporary: Path) =
        runBlocking {
            val home = temporary.toRealPath()
            val repository = Files.createDirectories(home.resolve("code/repository"))
            Files.writeString(repository.resolve("settings.gradle.kts"), "")
            val registry =
                io.github.amichne.kast.appserver.WorkspaceEnrollmentStore(home.resolve("config/workspaces.json"))
            registry.enroll(home)
            val enrollment = (registry.read() as io.github.amichne.kast.appserver.EnrollmentRead.Read).enrollment
            val owner =
                io.github.amichne.kast.appserver.protocol.ThreadBindingOwner.admit(
                        "installation",
                        "00000000-0000-0000-0000-000000000001",
                    )
                    .refinedValue()
            val broker = echoBroker()
            val store = MemoryThreadCatalogStore()
            val binding =
                ThreadCatalogBinding.admit(
                        threadId = "legacy",
                        catalogDigest = broker.catalog.digest,
                        workingDirectory = repository,
                        workspaceRoot = home,
                        owner = owner,
                    )
                    .refinedValue()
            store.write(binding)
            val adapter =
                CodexProtocolAdapter(
                    broker = broker,
                    contracts = protocolContracts(),
                    threadStore = store,
                    enrollment = enrollment,
                    bindingOwner = owner,
                )
            try {
                assertRejectedLegacyBinding(adapter, store, binding)
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `nearest settings owner resolves overlap and explicit registered root is preserved`(@TempDir temporary: Path) =
        runBlocking {
            val fixture = OverlapFixture(temporary)
            val adapter = fixture.adapter()
            try {
                startOverlapThread(adapter = adapter, requestId = 0, threadId = "thread-nearest", cwd = fixture.child)
                assertEquals(
                    fixture.child,
                    (fixture.store.read("thread-nearest") as ThreadStoreRead.Found).binding.workspace.root.path,
                )
                startOverlapThread(
                    adapter = adapter,
                    requestId = 1,
                    threadId = "thread-1",
                    cwd = fixture.child,
                    explicitRoot = fixture.root,
                )
                assertEquals(
                    fixture.root,
                    (fixture.store.read("thread-1") as ThreadStoreRead.Found).binding.workspace.root.path,
                )
            } finally {
                adapter.close()
            }
        }

    @Test
    fun `stale owner cannot resume or invoke an existing overlapping root binding`(@TempDir temporary: Path) =
        runBlocking {
            val fixture = OverlapFixture(temporary)
            val adapter = fixture.adapter()
            val staleOwner =
                ThreadBindingOwner.admit("installation", "00000000-0000-0000-0000-000000000002").refinedValue()
            val restarted = fixture.adapter(staleOwner)
            try {
                startOverlapThread(
                    adapter = adapter,
                    requestId = 1,
                    threadId = "thread-1",
                    cwd = fixture.child,
                    explicitRoot = fixture.root,
                )
                assertInstanceOf(
                    ProtocolRouting.ReplyDownstream::class.java,
                    restarted.fromDownstream(resumeOverlapMessage()),
                )
                assertEquals(
                    ThreadWorkspaceFailure.OWNER_INCOMPATIBLE,
                    (restarted.boundWorkspace(io.github.amichne.kast.appserver.core.BrokerThreadId.admit("thread-1")!!)
                            as Refinement.Rejected)
                        .failure,
                )
                val call = restarted.fromUpstream(callOverlapMessage()) as ProtocolRouting.ReplyUpstream
                assertFalse(call.message.objectValue("result").getValue("success").jsonPrimitive.content.toBoolean())
            } finally {
                adapter.close()
                restarted.close()
            }
        }

    private suspend fun startOverlapThread(
        adapter: CodexProtocolAdapter,
        requestId: Int,
        threadId: String,
        cwd: Path,
        explicitRoot: Path? = null,
    ) {
        val selected =
            assertInstanceOf(
                ProtocolRouting.ForwardUpstream::class.java,
                adapter.fromDownstream(startOverlapMessage(requestId, cwd, explicitRoot)),
            )
        assertFalse(selected.message.objectValue("params").containsKey("kastWorkspaceRoot"))
        adapter.fromUpstream(
            Json.encodeToString(
                BindingResponseFixture(
                    requestId,
                    ThreadResultFixture(ThreadFixture(threadId, emptyList()), cwd.toString()),
                )
            )
        )
    }

    private fun startOverlapMessage(requestId: Int, cwd: Path, explicitRoot: Path?): String =
        if (explicitRoot == null)
            Json.encodeToString(BindingRequestFixture(requestId, "thread/start", StartBindingFixture(cwd.toString())))
        else
            Json.encodeToString(
                BindingRequestFixture(
                    requestId,
                    "thread/start",
                    SelectedBindingFixture(cwd.toString(), explicitRoot.toString()),
                )
            )

    private fun resumeOverlapMessage(): String =
        Json.encodeToString(BindingRequestFixture(2, "thread/resume", ResumeBindingFixture("thread-1")))

    private fun callOverlapMessage(): String =
        Json.encodeToString(
            BindingRequestFixture(
                3,
                "item/tool/call",
                ToolBindingFixture(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    callId = "call-1",
                    namespace = "echo",
                    tool = "say",
                    arguments = EchoArgumentsFixture("hello"),
                ),
            )
        )

    private inner class OverlapFixture(temporary: Path) {
        val root: Path = temporary.toRealPath()
        val child: Path = Files.createDirectory(root.resolve("child"))
        private val registry =
            io.github.amichne.kast.appserver.WorkspaceEnrollmentStore(root.resolve("workspaces.json"))

        init {
            Files.writeString(root.resolve("settings.gradle.kts"), "")
            Files.writeString(child.resolve("settings.gradle.kts"), "")
            registry.enroll(root)
            registry.enroll(child)
        }

        private val enrollment = (registry.read() as io.github.amichne.kast.appserver.EnrollmentRead.Read).enrollment
        private val owner =
            ThreadBindingOwner.admit("installation", "00000000-0000-0000-0000-000000000001").refinedValue()
        val store = MemoryThreadCatalogStore()
        private val broker = echoBroker()

        fun adapter(bindingOwner: ThreadBindingOwner = owner) =
            CodexProtocolAdapter(
                broker = broker,
                contracts = protocolContracts(),
                threadStore = store,
                enrollment = enrollment,
                bindingOwner = bindingOwner,
            )
    }

    private fun echoBroker(): Broker {
        val inputSchema = NetworkntJsonSchemaCompiler.compile(resource("echo-input.json")).refinedValue()
        val outputSchema = NetworkntJsonSchemaCompiler.compile(resource("echo-output.json")).refinedValue()
        val input =
            JsonDomainDefinition(
                inputSchema,
                RefinementDefinition { admitted ->
                    Validation.validated(EchoValue(admitted.element.jsonObject.getValue("value").jsonPrimitive.content))
                },
            )
        val tool: BrokerTool<Unit, EchoValue, EchoValue, Nothing> =
            BrokerTool(
                name = ToolName.admit("say").refinedValue(),
                description = ToolDescription.admit("Echo a value.").refinedValue(),
                loading = ToolLoading.EAGER,
                input = input,
                outputSchema = outputSchema,
                invoke = { _, argument, _ -> ProviderCall.Completed(argument) },
                encode = { output -> Json.encodeToJsonElement(serializer<EchoValue>(), output).jsonObject },
                present = { output -> ToolPresentation.text(output.value, success = true) },
            )
        val provider =
            ProviderRegistration.define(
                    namespace = ProviderNamespace.admit("echo").refinedValue(),
                    version = ProviderVersion.admit("1.0.0").refinedValue(),
                    tools = listOf(tool),
                    start = { ProviderStartup.Started(Unit) },
                )
                .validatedValue()
        return Broker.create(listOf(provider), BrokerLimits.defaults()).validatedValue()
    }

    private fun protocolContracts(): CodexProtocolContracts =
        CodexProtocolContracts.define(CodexOwnedSchema.entries.associateWith { resource("object.json") })
            .validatedValue()

    private fun resource(name: String) =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/workspace-binding/$name")).readText()).jsonObject

    private fun String.objectValue(name: String) = Json.parseToJsonElement(this).jsonObject.getValue(name).jsonObject

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> throw AssertionError("Expected refinement, received $failure")
        }

    private fun <Strong, Failure> Validation<Strong, Failure>.validatedValue(): Strong =
        when (this) {
            is Validation.Validated -> value
            is Validation.Rejected -> throw AssertionError("Expected validation, received $failures")
        }

    private suspend fun assertRejectedLegacyBinding(
        adapter: CodexProtocolAdapter,
        store: MemoryThreadCatalogStore,
        binding: ThreadCatalogBinding,
    ) {
        assertEquals(
            ThreadWorkspaceFailure.WORKSPACE_REJECTED,
            (adapter.boundWorkspace(io.github.amichne.kast.appserver.core.BrokerThreadId.admit("legacy")!!)
                    as Refinement.Rejected)
                .failure,
        )
        assertInstanceOf(
            ProtocolRouting.ReplyDownstream::class.java,
            adapter.fromDownstream(
                Json.encodeToString(BindingRequestFixture(1, "thread/resume", ResumeBindingFixture("legacy")))
            ),
        )
        assertEquals(binding, (store.read("legacy") as ThreadStoreRead.Found).binding)
    }
}

@Serializable private data class EchoValue(val value: String)

@Serializable private data class BindingRequestFixture<T>(val id: Int, val method: String, val params: T)

@Serializable private data class ResumeBindingFixture(val threadId: String)

@Serializable private data class StartBindingFixture(val cwd: String)

@Serializable private data class SelectedBindingFixture(val cwd: String, val kastWorkspaceRoot: String)

@Serializable private data class BindingResponseFixture(val id: Int, val result: ThreadResultFixture)

@Serializable private data class ThreadResultFixture(val thread: ThreadFixture, val cwd: String)

@Serializable private data class ThreadFixture(val id: String, val turns: List<String>)

@Serializable
private data class ToolBindingFixture(
    val threadId: String,
    val turnId: String,
    val callId: String,
    val namespace: String,
    val tool: String,
    val arguments: EchoArgumentsFixture,
)

@Serializable private data class EchoArgumentsFixture(val value: String)
