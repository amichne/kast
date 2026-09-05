package io.github.amichne.kast.cli.broker.core

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import io.github.amichne.kast.cli.broker.schema.CompiledJsonSchema
import io.github.amichne.kast.cli.broker.schema.JsonDomainDefinition
import io.github.amichne.kast.cli.broker.schema.canonicalJson
import io.github.amichne.kast.kernel.Validation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

internal enum class ToolLoading { EAGER, DEFERRED }

internal data class ToolContent(
    val text: String,
)

internal sealed interface ObserverPresentation {
    data object None : ObserverPresentation

    data class Markdown(
        val source: ObserverMarkdown,
    ) : ObserverPresentation
}

@JvmInline
internal value class ObserverMarkdown(val value: String)

internal data class ToolPresentation private constructor(
    val content: List<ToolContent>,
    val success: Boolean,
    val observer: ObserverPresentation,
) {
    companion object {
        internal fun text(
            text: String,
            success: Boolean,
            observer: ObserverPresentation = ObserverPresentation.None,
        ): ToolPresentation = ToolPresentation(listOf(ToolContent(text)), success, observer)
    }
}

internal enum class ProviderFailureCode {
    UNEXPECTED_FAILURE,
    TIMED_OUT,
    IO_REJECTED,
    OUTPUT_LIMIT,
    SPAWN_FAILED,
    TERMINATED,
    KAST_QUALIFICATION_FAILED,
    KAST_CONTRACT_CHANGED,
    KAST_ARGUMENT_NOT_SCALAR,
    MALFORMED_KAST_OUTPUT,
    GRADLE_WRAPPER_UNAVAILABLE,
    ;

    val value: String get() = name
}

internal sealed interface ProviderCall<out Output> {
    data class Completed<Output>(val value: Output) : ProviderCall<Output>
    data class Rejected(val code: ProviderFailureCode) : ProviderCall<Nothing>
}

internal sealed interface ProviderStartup<out Runtime> {
    data class Started<Runtime>(val runtime: Runtime) : ProviderStartup<Runtime>
    data class Rejected(val code: ProviderFailureCode) : ProviderStartup<Nothing>
}

internal class BrokerTool<Runtime, Input, Output, InputFailure>(
    val name: ToolName,
    val description: ToolDescription,
    val loading: ToolLoading,
    internal val input: JsonDomainDefinition<Input, InputFailure>,
    internal val outputSchema: CompiledJsonSchema,
    internal val invoke: suspend (Runtime, Input, BrokerInvocationContext) -> ProviderCall<Output>,
    internal val encode: (Output) -> JsonElement,
    internal val present: (Output) -> ToolPresentation,
    internal val invocationBudget: ElapsedTimeLimitMillis = OperationExecutionBudget.SEMANTIC_READ.operation,
)

internal sealed interface ProviderDefinitionFailure {
    data object EmptyToolSet : ProviderDefinitionFailure
    data class DuplicateTool(val tool: ToolName) : ProviderDefinitionFailure
}

internal sealed interface ProviderDefinition {
    val namespace: ProviderNamespace
    val version: ProviderVersion
    val toolDocuments: List<ProviderToolDocument>

    fun route(limits: BrokerLimits): ProviderRoute
}

internal class ProviderRegistration<Runtime> private constructor(
    override val namespace: ProviderNamespace,
    override val version: ProviderVersion,
    internal val tools: List<BrokerTool<Runtime, *, *, *>>,
    internal val start: suspend () -> ProviderStartup<Runtime>,
) : ProviderDefinition {
    override val toolDocuments: List<ProviderToolDocument> = tools.map { tool ->
        ProviderToolDocument(
            tool.name,
            tool.description,
            tool.loading,
            tool.input.schema,
            tool.outputSchema,
        )
    }

    override fun route(limits: BrokerLimits): ProviderRoute = TypedProviderRoute(this, limits)

    companion object {
        internal fun <Runtime> define(
            namespace: ProviderNamespace,
            version: ProviderVersion,
            tools: List<BrokerTool<Runtime, *, *, *>>,
            start: suspend () -> ProviderStartup<Runtime>,
        ): Validation<ProviderRegistration<Runtime>, ProviderDefinitionFailure> {
            if (tools.isEmpty()) return Validation.rejected(ProviderDefinitionFailure.EmptyToolSet)
            val duplicate = tools.groupBy(BrokerTool<Runtime, *, *, *>::name)
                .entries.firstOrNull { (_, definitions) -> definitions.size > 1 }
                ?.key
            if (duplicate != null) {
                return Validation.rejected(ProviderDefinitionFailure.DuplicateTool(duplicate))
            }
            return Validation.validated(ProviderRegistration(namespace, version, tools, start))
        }
    }
}

internal data class ProviderToolDocument(
    val name: ToolName,
    val description: ToolDescription,
    val loading: ToolLoading,
    val inputSchema: CompiledJsonSchema,
    val outputSchema: CompiledJsonSchema,
)

internal data class CatalogTool(
    val name: ToolName,
    val description: ToolDescription,
    val loading: ToolLoading,
    val inputSchema: JsonObject,
)

internal data class CatalogNamespace(
    val name: ProviderNamespace,
    val description: ToolDescription,
    val tools: List<CatalogTool>,
)

@JvmInline
internal value class CatalogDigest private constructor(val value: String) {
    companion object {
        internal fun derive(canonicalDocument: String): CatalogDigest = CatalogDigest(
            "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(canonicalDocument.toByteArray(StandardCharsets.UTF_8)),
            ),
        )

        internal fun admit(raw: String): CatalogDigest? =
            raw.takeIf { DIGEST.matches(it) }?.let(::CatalogDigest)

        private val DIGEST = Regex("sha256:[0-9a-f]{64}")
    }
}

internal data class BrokerCatalog(
    val digest: CatalogDigest,
    val namespaces: List<CatalogNamespace>,
    internal val identityDocument: JsonArray,
)

internal data class BrokerLimits private constructor(
    val inFlightCallsPerConnection: Int,
    val inFlightCallsPerProvider: Int,
    val maximumDescriptorCount: Int,
    val maximumCatalogBytes: Int,
    val maximumToolArgumentBytes: Int,
    val maximumToolResultBytes: Int,
    val providerStartupTimeoutMillis: Long,
) {
    companion object {
        internal fun defaults(): BrokerLimits = BrokerLimits(
            inFlightCallsPerConnection = 8,
            inFlightCallsPerProvider = 4,
            maximumDescriptorCount = 64,
            maximumCatalogBytes = 1_024 * 1_024,
            maximumToolArgumentBytes = 64 * 1_024,
            maximumToolResultBytes = 1_024 * 1_024,
            providerStartupTimeoutMillis = OperationExecutionBudget.PROVIDER_QUALIFICATION.value,
        )
    }
}

internal enum class BrokerLimit {
    IN_FLIGHT_CALLS_PER_CONNECTION,
    IN_FLIGHT_CALLS_PER_PROVIDER,
    MAXIMUM_TOOL_ARGUMENT_BYTES,
    MAXIMUM_TOOL_RESULT_BYTES,
}

internal sealed interface CatalogFailure {
    data class DuplicateNamespace(val namespace: ProviderNamespace) : CatalogFailure
    data object DescriptorLimitExceeded : CatalogFailure
    data object CatalogSizeExceeded : CatalogFailure
}

internal data class BrokerDispatchRequest(
    val address: ToolAddress,
    val arguments: JsonElement,
    val context: BrokerInvocationContext,
)

internal sealed interface BrokerFailure {
    data class UnknownNamespace(val namespace: ProviderNamespace) : BrokerFailure
    data class UnknownTool(val address: ToolAddress) : BrokerFailure
    data class InvalidArguments(val address: ToolAddress, val failureCount: Int) : BrokerFailure
    data class ProviderStartupRejected(
        val namespace: ProviderNamespace,
        val code: ProviderFailureCode,
    ) : BrokerFailure
    data class ProviderInvocationRejected(
        val address: ToolAddress,
        val code: ProviderFailureCode,
    ) : BrokerFailure
    data class OutputContractRejected(val address: ToolAddress, val failureCount: Int) : BrokerFailure
    data class InvocationCancelled(val address: ToolAddress) : BrokerFailure
    data class Overloaded(val limit: BrokerLimit) : BrokerFailure
}

internal sealed interface BrokerDispatch {
    data class Completed(val presentation: ToolPresentation) : BrokerDispatch
    data class Rejected(val failure: BrokerFailure) : BrokerDispatch
}

internal class Broker private constructor(
    val catalog: BrokerCatalog,
    val limits: BrokerLimits,
    private val routes: Map<ProviderNamespace, ProviderRoute>,
) {
    internal suspend fun dispatch(request: BrokerDispatchRequest): BrokerDispatch {
        if (
            canonicalJson(request.arguments).toByteArray(StandardCharsets.UTF_8).size >
            limits.maximumToolArgumentBytes
        ) {
            return BrokerDispatch.Rejected(
                BrokerFailure.Overloaded(BrokerLimit.MAXIMUM_TOOL_ARGUMENT_BYTES),
            )
        }
        return routes[request.address.namespace]?.dispatch(request)
            ?: BrokerDispatch.Rejected(
                BrokerFailure.UnknownNamespace(request.address.namespace),
            )
    }

    companion object {
        internal fun create(
            definitions: List<ProviderDefinition>,
            limits: BrokerLimits,
        ): Validation<Broker, CatalogFailure> {
            if (definitions.size > limits.maximumDescriptorCount) {
                return Validation.rejected(CatalogFailure.DescriptorLimitExceeded)
            }
            val duplicate = definitions.groupBy(ProviderDefinition::namespace)
                .entries.firstOrNull { (_, providers) -> providers.size > 1 }
                ?.key
            if (duplicate != null) {
                return Validation.rejected(CatalogFailure.DuplicateNamespace(duplicate))
            }
            val ordered = definitions.sortedBy { definition -> definition.namespace }
            val identity = buildJsonArray {
                ordered.forEach { definition -> add(definition.identityDocument()) }
            }
            val canonical = canonicalJson(identity)
            if (canonical.toByteArray(StandardCharsets.UTF_8).size > limits.maximumCatalogBytes) {
                return Validation.rejected(CatalogFailure.CatalogSizeExceeded)
            }
            val namespaces = ordered.map(ProviderDefinition::catalogNamespace)
            val routes = ordered.associate { definition ->
                definition.namespace to definition.route(limits)
            }
            return Validation.validated(
                Broker(
                    BrokerCatalog(CatalogDigest.derive(canonical), namespaces, identity),
                    limits,
                    routes,
                ),
            )
        }
    }
}

internal interface ProviderRoute {
    val namespace: ProviderNamespace
    suspend fun dispatch(request: BrokerDispatchRequest): BrokerDispatch
}

private class TypedProviderRoute<Runtime>(
    private val registration: ProviderRegistration<Runtime>,
    private val limits: BrokerLimits,
) : ProviderRoute {
    override val namespace: ProviderNamespace = registration.namespace
    private val startupLock = Mutex()
    private val invocationCapacity = Semaphore(limits.inFlightCallsPerProvider)
    private var startup: ProviderStartup<Runtime>? = null
    private val tools: Map<ToolName, TypedToolRoute<Runtime>> = registration.tools.associate { tool ->
        tool.name to typedToolRoute(tool)
    }

    override suspend fun dispatch(request: BrokerDispatchRequest): BrokerDispatch =
        tools[request.address.tool]?.dispatch(request, ::acquire)
            ?: BrokerDispatch.Rejected(BrokerFailure.UnknownTool(request.address))

    private suspend fun acquire(): ProviderStartup<Runtime> = startupLock.withLock {
        startup ?: startBounded().also { result -> startup = result }
    }

    private suspend fun startBounded(): ProviderStartup<Runtime> = try {
        withTimeout(limits.providerStartupTimeoutMillis) { registration.start() }
    } catch (_: TimeoutCancellationException) {
        ProviderStartup.Rejected(ProviderFailureCode.TIMED_OUT)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        ProviderStartup.Rejected(ProviderFailureCode.UNEXPECTED_FAILURE)
    }

    private fun <Input, Output, InputFailure> typedToolRoute(
        tool: BrokerTool<Runtime, Input, Output, InputFailure>,
    ): TypedToolRoute<Runtime> = object : TypedToolRoute<Runtime> {
        override suspend fun dispatch(
            request: BrokerDispatchRequest,
            acquire: suspend () -> ProviderStartup<Runtime>,
        ): BrokerDispatch {
            val admitted = tool.input.admit(request.arguments)
            val input = when (admitted) {
                is Validation.Validated -> admitted.value
                is Validation.Rejected -> return BrokerDispatch.Rejected(
                    BrokerFailure.InvalidArguments(request.address, admitted.failures.size),
                )
            }
            if (!invocationCapacity.tryAcquire()) {
                return BrokerDispatch.Rejected(
                    BrokerFailure.Overloaded(BrokerLimit.IN_FLIGHT_CALLS_PER_PROVIDER),
                )
            }
            val invocation = try {
                val runtime = when (val started = acquire()) {
                    is ProviderStartup.Started -> started.runtime
                    is ProviderStartup.Rejected -> return BrokerDispatch.Rejected(
                        BrokerFailure.ProviderStartupRejected(namespace, started.code),
                    )
                }
                try {
                    withTimeout(tool.invocationBudget.value) {
                        tool.invoke(runtime, input, request.context)
                    }
                } catch (_: TimeoutCancellationException) {
                    return BrokerDispatch.Rejected(
                        BrokerFailure.ProviderInvocationRejected(
                            request.address,
                            ProviderFailureCode.TIMED_OUT,
                        ),
                    )
                } catch (_: CancellationException) {
                    return BrokerDispatch.Rejected(BrokerFailure.InvocationCancelled(request.address))
                } catch (_: RuntimeException) {
                    return BrokerDispatch.Rejected(
                        BrokerFailure.ProviderInvocationRejected(
                            request.address,
                            ProviderFailureCode.UNEXPECTED_FAILURE,
                        ),
                    )
                }
            } finally {
                invocationCapacity.release()
            }
            val output = when (invocation) {
                is ProviderCall.Completed -> invocation.value
                is ProviderCall.Rejected -> return BrokerDispatch.Rejected(
                    BrokerFailure.ProviderInvocationRejected(request.address, invocation.code),
                )
            }
            val encoded = try {
                tool.encode(output)
            } catch (_: RuntimeException) {
                return BrokerDispatch.Rejected(
                    BrokerFailure.ProviderInvocationRejected(
                        request.address,
                        ProviderFailureCode.UNEXPECTED_FAILURE,
                    ),
                )
            }
            if (
                canonicalJson(encoded).toByteArray(StandardCharsets.UTF_8).size >
                limits.maximumToolResultBytes
            ) {
                return BrokerDispatch.Rejected(
                    BrokerFailure.Overloaded(BrokerLimit.MAXIMUM_TOOL_RESULT_BYTES),
                )
            }
            when (val outputAdmission = tool.outputSchema.admit(encoded)) {
                is Validation.Validated -> Unit
                is Validation.Rejected -> return BrokerDispatch.Rejected(
                    BrokerFailure.OutputContractRejected(
                        request.address,
                        outputAdmission.failures.size,
                    ),
                )
            }
            return try {
                BrokerDispatch.Completed(tool.present(output))
            } catch (_: RuntimeException) {
                BrokerDispatch.Rejected(
                    BrokerFailure.ProviderInvocationRejected(
                        request.address,
                        ProviderFailureCode.UNEXPECTED_FAILURE,
                    ),
                )
            }
        }
    }
}

private fun interface TypedToolRoute<Runtime> {
    suspend fun dispatch(
        request: BrokerDispatchRequest,
        acquire: suspend () -> ProviderStartup<Runtime>,
    ): BrokerDispatch
}

private fun ProviderDefinition.identityDocument(): JsonObject = buildJsonObject {
    put("namespace", namespace.value)
    put("providerVersion", version.value)
    put("tools", buildJsonArray {
        toolDocuments.sortedBy(ProviderToolDocument::name).forEach { tool ->
            add(buildJsonObject {
                put("name", tool.name.value)
                put("description", tool.description.value)
                put("loading", tool.loading.name.lowercase())
                put("inputSchema", tool.inputSchema.document)
                put("outputSchema", tool.outputSchema.document)
            })
        }
    })
}

private fun ProviderDefinition.catalogNamespace(): CatalogNamespace = CatalogNamespace(
    name = namespace,
    description = ToolDescription.admit("Typed read-only tools provided by ${namespace.value}.")
        .let { refinement ->
            when (refinement) {
                is io.github.amichne.kast.kernel.Refinement.Refined -> refinement.value
                is io.github.amichne.kast.kernel.Refinement.Rejected ->
                    error("Static catalog description violated its construction proof")
            }
        },
    tools = toolDocuments.sortedBy(ProviderToolDocument::name).map { tool ->
        CatalogTool(
            tool.name,
            tool.description,
            tool.loading,
            tool.inputSchema.document,
        )
    },
)
