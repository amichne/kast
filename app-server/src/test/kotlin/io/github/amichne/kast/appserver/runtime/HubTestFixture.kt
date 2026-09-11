package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.WorkspaceEnrollment
import io.github.amichne.kast.appserver.core.AgentSessionBootstrap
import io.github.amichne.kast.appserver.core.AgentSessionBootstrapQualification
import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.BrokerTool
import io.github.amichne.kast.appserver.core.HostedToolDefinition
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
import io.github.amichne.kast.appserver.protocol.codex.CodexOwnedSchema
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolContracts
import io.github.amichne.kast.appserver.schema.JsonDomainDefinition
import io.github.amichne.kast.appserver.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.AgentToolName
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.registry.HostedApprovalPolicy
import io.github.amichne.kast.protocol.registry.OperationEffect
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject

internal class HubTestPeer(val session: BrokerSessionHub.Session, val upstream: HubTestUpstream)

internal class HubTestUpstream : BrokerUpstreamConnection {
    val sent = Channel<String>(16)
    val received = Channel<BrokerUpstreamFrame>(16)
    var block: CompletableDeferred<Unit>? = null
    @Volatile var closed = false

    override suspend fun send(message: String): BrokerUpstreamSend {
        block?.await()
        sent.send(message)
        return BrokerUpstreamSend.SENT
    }

    override suspend fun receive(): BrokerUpstreamFrame =
        received.receiveCatching().getOrNull() ?: BrokerUpstreamFrame.Closed

    override suspend fun close() {
        closed = true
        received.close()
        sent.close()
        block?.cancel()
    }
}

internal class HubTestFixture(
    root: Path,
    enrollment: WorkspaceEnrollment = WorkspaceEnrollment.ProtocolFixture,
    invocationJournal: Path? = null,
    executionPolicy: WorkspaceExecutionPolicy = WorkspaceExecutionPolicy.Default,
    cancellationRetirement: CompletableDeferred<Unit>? = null,
    planGateway: HostedPlanApprovalGateway? = null,
) {
    val root: Path = root.toRealPath()
    val activities = java.util.concurrent.CopyOnWriteArrayList<SessionActivity>()
    val invocations = AtomicInteger()
    val approvedInvocations = java.util.concurrent.CopyOnWriteArrayList<BrokerInvocationApproval>()
    val entered = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val allowExecution = CompletableDeferred<Unit>()
    val thread = checkNotNull(BrokerThreadId.admit("thread-1"))
    private val connecting = Channel<HubTestUpstream>(16)
    private val objectSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject
    private val schema = NetworkntJsonSchemaCompiler.compile(objectSchema).refined()
    private val tool: BrokerTool<Unit, JsonElement, JsonElement, Nothing> =
        BrokerTool(
            name = ToolName.admit("query").refined(),
            description = ToolDescription.admit("Query test workspace").refined(),
            loading = ToolLoading.EAGER,
            input = JsonDomainDefinition(schema, RefinementDefinition { input -> Validation.validated(input.element) }),
            outputSchema = schema,
            invoke = { _, input, _ ->
                invocations.incrementAndGet()
                if ((input as? JsonObject)?.get("independent") != JsonPrimitive(true)) {
                    entered.complete(Unit)
                    try {
                        allowExecution.await()
                    } catch (failure: CancellationException) {
                        cancelled.complete(Unit)
                        cancellationRetirement?.let { retirement ->
                            withContext(NonCancellable) { retirement.await() }
                        }
                        throw failure
                    }
                }
                ProviderCall.Completed(input)
            },
            encode = { it },
            present = { ToolPresentation.text(it.toString(), true) },
        )
    private val changeTool: BrokerTool<Unit, JsonElement, JsonElement, Nothing> =
        BrokerTool(
            name = ToolName.admit("change_apply").refined(),
            description = ToolDescription.admit("Apply exact approved plan").refined(),
            loading = ToolLoading.EAGER,
            input = JsonDomainDefinition(schema, RefinementDefinition { input -> Validation.validated(input.element) }),
            outputSchema = schema,
            invoke = { _, input, context ->
                approvedInvocations.add(context.approval)
                ProviderCall.Completed(input)
            },
            encode = { it },
            present = { ToolPresentation.text(it.toString(), true) },
        )
    private val bootstrap =
        if (planGateway == null) null
        else {
            val canonical = CanonicalAgentToolDefinitions.changeApply
            val apply =
                HostedToolDefinition(
                    operation = CanonicalOperation.CHANGE_APPLY,
                    name = canonical.name,
                    description = canonical.description,
                    inputSchema = schema,
                    outputSchema = schema,
                    effect = OperationEffect.INTELLIJ_WRITE,
                    approval = canonical.approval,
                    executionBudget = OperationExecutionBudget.SEMANTIC_READ,
                    loading = canonical.loading,
                )
            val query =
                apply.copy(
                    operation = CanonicalOperation.QUERY_RUN,
                    name = AgentToolName.parse("query").refined(),
                    effect = OperationEffect.INTELLIJ_READ,
                    approval = HostedApprovalPolicy.NONE,
                )
            (AgentSessionBootstrap.qualify(
                    listOf(query, apply),
                    CanonicalAgentToolDefinitions.policy,
                    setOf(tool.name, changeTool.name),
                ) as AgentSessionBootstrapQualification.Qualified)
                .bootstrap
        }
    private val broker =
        Broker.create(
                listOf(
                    ProviderRegistration.define(
                            namespace = ProviderNamespace.admit("kast").refined(),
                            version = ProviderVersion.admit("1").refined(),
                            tools = if (planGateway == null) listOf(tool) else listOf(tool, changeTool),
                            start = { ProviderStartup.Started(Unit) },
                        )
                        .validated()
                ),
                BrokerLimits.defaults(),
            )
            .validated()
    val hub =
        BrokerSessionHub(
            KtorBrokerServerOptions(
                publicSocket = BrokerSocketPath.admit(Path.of("/tmp/hub-test.sock")).validated(),
                broker = broker,
                contracts =
                    CodexProtocolContracts.define(
                            CodexOwnedSchema.entries.associateWith { schema ->
                                if (schema == CodexOwnedSchema.TURN_INTERRUPT_PARAMS)
                                    Json.parseToJsonElement("""{"type":"object","required":["threadId","turnId"]}""")
                                        .jsonObject
                                else objectSchema
                            }
                        )
                        .validated(),
                threadStore = MemoryThreadCatalogStore(),
                upstream =
                    BrokerUpstreamConnector { BrokerUpstreamConnectionAdmission.Connected(connecting.receive()) },
                maximumConnections = 4,
                maximumMessageBytes = 4 * 1_024 * 1_024,
                enrollment = enrollment,
                sessionBootstrap = bootstrap,
                planApprovalGateway = planGateway ?: HostedPlanApprovalGateway.Unavailable,
                invocationJournal = invocationJournal,
                sessionActivitySink = SessionActivitySink { activities.add(it) },
            ),
            executionPolicy,
        )

    suspend fun connect(): HubTestPeer {
        val upstream = HubTestUpstream()
        connecting.send(upstream)
        val session =
            checkNotNull(hub.attach("""{"id":0,"method":"initialize","params":{"clientInfo":{"name":"test"}}}"""))
        upstream.sent.receive()
        upstream.received.send(BrokerUpstreamFrame.Text("""{"id":0,"result":{}}"""))
        session.output.receive()
        session.accept("""{"method":"initialized"}""")
        upstream.sent.receive()
        return HubTestPeer(session, upstream)
    }

    suspend fun bind(peer: HubTestPeer, method: String, threadId: String = "thread-1", directory: Path = root) {
        peer.session.accept("""{"id":1,"method":"$method","params":{"threadId":"$threadId","cwd":"$directory"}}""")
        peer.upstream.sent.receive()
        peer.upstream.received.send(
            BrokerUpstreamFrame.Text(
                """{"id":1,"result":{"thread":{"id":"$threadId","turns":[]},"cwd":"$directory"}}"""
            )
        )
        peer.session.output.receive()
    }
}

private fun <T, E> Refinement<T, E>.refined(): T = (this as Refinement.Refined).value

private fun <T, E> Validation<T, E>.validated(): T = (this as Validation.Validated).value
