@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerWorkspaceId
import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerCallId
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.BrokerTool
import io.github.amichne.kast.appserver.core.BrokerTurnId
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
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
import io.github.amichne.kast.appserver.protocol.ThreadCatalogBinding
import io.github.amichne.kast.appserver.protocol.codex.CodexOwnedSchema
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolAdapter
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolContracts
import io.github.amichne.kast.appserver.schema.JsonDomainDefinition
import io.github.amichne.kast.appserver.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal enum class FixtureTermination {
    INVALID_OUTPUT,
    MUTATION_THEN_INVALID_OUTPUT,
    AWAIT_CANCELLATION,
}

/** Uses one virtual scheduler for broker invocation and workspace ownership, with explicit retirement gates. */
internal class OutputContractExecutionFixture
private constructor(
    private val root: Path,
    scope: TestScope,
    effect: BrokerOperationEffect,
    termination: FixtureTermination,
    invocationBudget: ElapsedTimeLimitMillis = ElapsedTimeLimitMillis.parse(60_000).refined(),
) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val retiring = CompletableDeferred<Unit>()
    val retire = CompletableDeferred<Unit>()
    val calls = mutableListOf<String>()
    var active = 0
        private set

    var maximumActive = 0
        private set

    var mutations = 0
        private set

    val workspace = workspace(root)
    private val otherRoot = Files.createDirectory(root.resolve("independent")).toRealPath()
    val otherWorkspace = workspace(otherRoot)
    val executions = WorkspaceExecution(scope, WorkspaceExecutionPolicy.Default, scope.testTimeSource)
    private val schema = NetworkntJsonSchemaCompiler.compile(OutputContractTestSchemas.output).refined()
    private val inputSchema = NetworkntJsonSchemaCompiler.compile(OutputContractTestSchemas.input).refined()
    private val tool: BrokerTool<Unit, Input, Output, Nothing> =
        BrokerTool(
            ToolName.admit("read").refined(),
            ToolDescription.admit("Controlled output boundary").refined(),
            ToolLoading.EAGER,
            JsonDomainDefinition(
                inputSchema,
                RefinementDefinition { input ->
                    Validation.validated(Json.decodeFromJsonElement<Input>(input.element))
                },
            ),
            schema,
            invoke = { _, input, _ ->
                calls += input.call
                active++
                maximumActive = maxOf(active, maximumActive)
                try {
                    if (input.call == "first") {
                        entered.complete(Unit)
                        release.await()
                        when (termination) {
                            FixtureTermination.INVALID_OUTPUT -> Unit
                            FixtureTermination.MUTATION_THEN_INVALID_OUTPUT -> mutations++
                            FixtureTermination.AWAIT_CANCELLATION ->
                                try {
                                    awaitCancellation()
                                } finally {
                                    withContext(NonCancellable) {
                                        retiring.complete(Unit)
                                        retire.await()
                                    }
                                }
                        }
                        ProviderCall.Completed(Output("unsupported"))
                    } else ProviderCall.Completed(Output("relation-continuation:v1:structural-fixture"))
                } finally {
                    active--
                }
            },
            encode = { Json.encodeToJsonElement(it) },
            present = { ToolPresentation.text(Json.encodeToString(it), success = true) },
            invocationBudget = invocationBudget,
            effect = effect,
        )
    private val broker =
        Broker.create(
                listOf(
                    ProviderRegistration.define(
                            ProviderNamespace.admit("boundary").refined(),
                            ProviderVersion.admit("1").refined(),
                            listOf(tool),
                        ) {
                            ProviderStartup.Started(Unit)
                        }
                        .validated()
                ),
                BrokerLimits.defaults(),
            )
            .validated()
    private val store = MemoryThreadCatalogStore()
    val adapter =
        CodexProtocolAdapter(
            broker,
            CodexProtocolContracts.define(
                    CodexOwnedSchema.entries.associateWith {
                        OutputContractTestSchemas.objectDocument
                    }
                )
                .validated(),
            store,
            invocationDispatcher = StandardTestDispatcher(scope.testScheduler),
        )

    fun submit(call: String, workspace: BrokerWorkspaceId = this.workspace): Deferred<WorkspaceExecutionResult> {
        val thread = if (workspace == this.workspace) "thread" else "other"
        return executions.submit(
            WorkspaceExecutionIdentity(
                workspace,
                ClientConnectionId.fresh(),
                requireNotNull(BrokerThreadId.admit(thread)),
                requireNotNull(BrokerTurnId.admit(call)),
                requireNotNull(BrokerCallId.admit(call)),
            )
        ) {
            adapter.fromUpstream(
                Json.encodeToString(
                    Call(1, "item/tool/call", Params(thread, call, call, "boundary", "read", Input(call)))
                )
            )
        }
    }

    fun cancelFirst() =
        executions.cancel(
            workspace,
            requireNotNull(BrokerThreadId.admit("thread")),
            requireNotNull(BrokerTurnId.admit("first")),
        )

    fun lane(workspace: BrokerWorkspaceId = this.workspace) =
        executions
            .snapshot()
            .getValue("lanes")
            .jsonArray
            .map { it.jsonObject }
            .single { it.getValue("workspaceId").jsonPrimitive.content == workspace.value }

    suspend fun close() {
        release.complete(Unit)
        retire.complete(Unit)
        adapter.closeAndJoin()
    }

    @Serializable private data class Input(val call: String)

    @Serializable private data class Output(val continuation: String)

    @Serializable private data class Call(val id: Int, val method: String, val params: Params)

    @Serializable
    private data class Params(
        val threadId: String,
        val turnId: String,
        val callId: String,
        val namespace: String,
        val tool: String,
        val arguments: Input,
    )

    companion object {
        suspend fun create(
            root: Path,
            scope: TestScope,
            effect: BrokerOperationEffect,
            termination: FixtureTermination,
            invocationBudget: ElapsedTimeLimitMillis = ElapsedTimeLimitMillis.parse(60_000).refined(),
        ): OutputContractExecutionFixture =
            OutputContractExecutionFixture(root, scope, effect, termination, invocationBudget).also {
                it.store.write(
                    ThreadCatalogBinding.admit("thread", it.broker.catalog.digest, root.toRealPath()).refined()
                )
                it.store.write(ThreadCatalogBinding.admit("other", it.broker.catalog.digest, it.otherRoot).refined())
            }

        private fun workspace(root: Path) =
            BrokerWorkspaceId.derive(requireNotNull(CanonicalBrokerDirectory.admit(root.toRealPath())))

        private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value

        private fun <T, F> Validation<T, F>.validated(): T = (this as Validation.Validated).value
    }
}
