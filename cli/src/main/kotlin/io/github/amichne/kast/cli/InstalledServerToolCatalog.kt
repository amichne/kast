package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.query.PublicSourceReadIntent
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.command.CliCommandSurface
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.IndexSyncRequest
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.registry.AgentToolDefinition
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.registry.HostedBindingCompleteness
import io.github.amichne.kast.protocol.registry.HostedOperationProjection
import io.github.amichne.kast.protocol.registry.HostedToolLoading
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import kotlinx.serialization.KSerializer

internal const val MAXIMUM_PROTOCOL_TEXT_LENGTH = 1_048_576
internal const val MAXIMUM_WORKSPACE_FILE_LENGTH = 4_096
internal const val MAXIMUM_PROTOCOL_COUNT = 1_000

internal const val SERVER_PROJECTION_SCHEMA_VERSION = 15
private const val HOSTED_BOOTSTRAP_SCHEMA_VERSION = 1
private const val CLI_INVOCATIONS_SCHEMA_VERSION = 4

/** A hosted tool either has an executable CLI route or is hosted-only. */
internal sealed interface InstalledInvocationBinding {
    data class Cli(val document: InstalledCliOperationInvocationDocument) : InstalledInvocationBinding

    data object HostedOnly : InstalledInvocationBinding
}

/** One public hosted tool retained with its canonical operation and explicit invocation ownership. */
internal class InstalledServerBinding
private constructor(
    val operation: CanonicalOperation,
    val tool: InstalledHostedToolDocument,
    val invocation: InstalledInvocationBinding,
) {
    companion object {
        /** Derives the complete binding set without accepting independently associated members. */
        fun from(commandSurface: CliCommandSurface): List<InstalledServerBinding> {
            val commandByOperation = commandSurface.semanticCommands.associateBy { it.operation }
            val facadeByIdentity = commandSurface.toolCommands.associateBy { it.identity }
            val toolsByOperation = installedServerTools.associateBy(InstalledServerTool::operation)
            return CanonicalAgentToolDefinitions.all.map { definition ->
                val operation = definition.operation.operation
                val tool = toolsByOperation.getValue(operation)
                InstalledServerBinding(
                    operation = operation,
                    tool = tool.hostedDocument(definition),
                    invocation =
                        when (val input = definition.inputBinding) {
                            AgentToolInputBinding.Canonical ->
                                tool.cliInvocationDocument(definition.name.value, commandByOperation[operation]?.usage)
                            is AgentToolInputBinding.Facade ->
                                InstalledInvocationBinding.Cli(
                                    InstalledCliOperationInvocationDocument(
                                        toolName = definition.name.value,
                                        operationId = operation.id.value,
                                        cliUsage = facadeByIdentity.getValue(input.identity).usage,
                                        invocation =
                                            InstalledServerCliInvocationDocument(
                                                InstalledServerInvocationType.CLI,
                                                listOf("tool", input.identity.toolName),
                                            ),
                                    )
                                )
                        },
                )
            }
        }
    }
}

/** The hosted catalog remains authoritative when no command is published. */
internal data class InstalledHostedBinding(val operation: CanonicalOperation, val tool: InstalledHostedToolDocument)

internal fun installedHostedBindings(): List<InstalledHostedBinding> {
    val definitions = CanonicalAgentToolDefinitions.all
    val tools = installedHostedBootstrap().tools
    check(definitions.size == tools.size)
    return definitions.zip(tools).map { (definition, tool) ->
        check(definition.name.value == tool.name)
        InstalledHostedBinding(definition.operation.operation, tool)
    }
}

/** Canonical hosted contracts are independent of executable command metadata. */
internal fun installedHostedBootstrap(): InstalledHostedBootstrapDocument {
    val tools = installedServerTools.associateBy(InstalledServerTool::operation)
    return InstalledHostedBootstrapDocument(
        schemaVersion = HOSTED_BOOTSTRAP_SCHEMA_VERSION,
        policy = CanonicalAgentToolDefinitions.policy.text,
        tools =
            CanonicalAgentToolDefinitions.all.map { definition ->
                tools.getValue(definition.operation.operation).hostedDocument(definition)
            },
    )
}

/**
 * Proof transition: `CliCommandSurface -> InstalledServerProjectionDocument`.
 *
 * The proven canonical command graph supplies exact operation usage while this closed projection supplies the
 * corresponding server name, JSON shapes, and CLI binding grammar where a CLI route exists. The resulting document is
 * the sole broker-facing authority for an installed executable; no runtime or filesystem input is interpreted here.
 */
internal fun installedServerProjection(commandSurface: CliCommandSurface): InstalledServerProjectionDocument {
    val bindings = installedServerBindings(commandSurface)
    return InstalledServerProjectionDocument(
        schemaVersion = SERVER_PROJECTION_SCHEMA_VERSION,
        namespace = "kast",
        hostedBootstrap = installedHostedBootstrap(),
        cliInvocations =
            InstalledCliInvocationsDocument(
                schemaVersion = CLI_INVOCATIONS_SCHEMA_VERSION,
                operations =
                    bindings.mapNotNull { binding ->
                        when (val route = binding.invocation) {
                            is InstalledInvocationBinding.Cli -> route.document
                            InstalledInvocationBinding.HostedOnly -> null
                        }
                    },
            ),
    )
}

/**
 * Proof transition: `CliCommandSurface -> List<InstalledServerBinding>`.
 *
 * Retains the canonical operation while joining its hosted schema and explicit CLI or hosted-only route. Consumers can
 * project another representation without reconstructing operation identity from JSON text.
 */
internal fun installedServerBindings(commandSurface: CliCommandSurface): List<InstalledServerBinding> =
    InstalledServerBinding.from(commandSurface)

private val installedServerTools: List<InstalledServerTool> =
    InstalledServerTool.entries
        .filter { tool ->
            HostedOperationProjection.publicDefinitions.any { it.operation == tool.operation }
        }
        .sortedBy { tool -> CanonicalOperation.entries.indexOf(tool.operation) }
        .also {
            val operations = it.map { tool -> tool.operation }
            when (val completeness = HostedOperationProjection.verifyBindings(operations)) {
                HostedBindingCompleteness.Complete -> Unit
                is HostedBindingCompleteness.Rejected ->
                    error("Invalid installed server projection: ${completeness.failures}")
            }
        }

private sealed interface InstalledToolRoute {
    data object HostedOnly : InstalledToolRoute

    data class Cli(val command: List<String>) : InstalledToolRoute
}

private enum class InstalledServerTool(
    val operation: CanonicalOperation,
    private val requestSerializer: KSerializer<*>,
    private val route: InstalledToolRoute,
) {
    WORKSPACE_LIFECYCLE(
        operation = CanonicalOperation.WORKSPACE_LIFECYCLE,
        requestSerializer = io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest.serializer(),
        route = InstalledToolRoute.HostedOnly,
    ),
    INDEX_SYNC(
        operation = CanonicalOperation.INDEX_SYNC,
        requestSerializer = IndexSyncRequest.serializer(),
        route = InstalledToolRoute.Cli(listOf("index", "sync")),
    ),
    TOPOLOGY_BUILD(
        operation = CanonicalOperation.TOPOLOGY_BUILD,
        requestSerializer = TopologyBuildRequest.serializer(),
        route = InstalledToolRoute.Cli(listOf("topology", "build")),
    ),
    SOURCE_READ(
        operation = CanonicalOperation.SOURCE_READ,
        requestSerializer = SourceReadRequest.serializer(),
        route = InstalledToolRoute.Cli(listOf("source", "read")),
    ),
    QUERY_RUN(
        operation = CanonicalOperation.QUERY_RUN,
        requestSerializer = QueryRunRequest.serializer(),
        route = InstalledToolRoute.HostedOnly,
    ),
    DIAGNOSTIC_CHECK(
        operation = CanonicalOperation.DIAGNOSTIC_CHECK,
        requestSerializer = DiagnosticCheckRequest.serializer(),
        route = InstalledToolRoute.HostedOnly,
    ),
    CHANGE(
        operation = CanonicalOperation.CHANGE,
        requestSerializer = ChangeRequest.serializer(),
        route = InstalledToolRoute.HostedOnly,
    ),
    CHANGE_PLAN(
        operation = CanonicalOperation.CHANGE_PLAN,
        requestSerializer = ChangePlanRequest.serializer(),
        route = InstalledToolRoute.Cli(listOf("change", "plan")),
    ),
    CHANGE_APPLY(
        operation = CanonicalOperation.CHANGE_APPLY,
        requestSerializer = ChangeApplyRequest.serializer(),
        route = InstalledToolRoute.Cli(listOf("change", "apply")),
    ),
    CHANGE_RECOVER(
        operation = CanonicalOperation.CHANGE_RECOVER,
        requestSerializer = ChangeRecoverRequest.serializer(),
        route = InstalledToolRoute.Cli(listOf("change", "recover")),
    );

    fun hostedDocument(definition: AgentToolDefinition): InstalledHostedToolDocument =
        InstalledHostedToolDocument(
            operationId = definition.operation.id.value,
            name = definition.name.value,
            description = definition.description.value,
            deferLoading = definition.loading == HostedToolLoading.DEFERRED,
            effect = definition.operation.effect.name.lowercase(),
            approvalPolicy = definition.approval.name.lowercase(),
            executionBudget =
                InstalledServerExecutionBudgetDocument(
                    readinessMillis = OperationExecutionBudget.WORKSPACE_READINESS.value,
                    operationMillis = OperationExecutionBudget.forOperation(operation).operation.value,
                ),
            inputSchema =
                when (val input = definition.inputBinding) {
                    is AgentToolInputBinding.Facade -> PublicToolContract.parameters(input.identity)
                    AgentToolInputBinding.Canonical ->
                        if (operation == CanonicalOperation.SOURCE_READ)
                            sourceReadToolInputSchema(
                                generatedHostedRequestSchema(requestSerializer, definition.operation.hostedVariants),
                                generatedRequestSchema(PublicSourceReadIntent.serializer()),
                            )
                        else generatedHostedRequestSchema(requestSerializer, definition.operation.hostedVariants)
                },
            outputSchema = installedServerOutputSchema(operation),
        )

    fun cliInvocationDocument(toolName: String, cliUsage: String?): InstalledInvocationBinding =
        when (val selected = route) {
            InstalledToolRoute.HostedOnly -> InstalledInvocationBinding.HostedOnly
            is InstalledToolRoute.Cli ->
                InstalledInvocationBinding.Cli(
                    InstalledCliOperationInvocationDocument(
                        toolName = toolName,
                        operationId = operation.id.value,
                        cliUsage = checkNotNull(cliUsage),
                        invocation =
                            InstalledServerCliInvocationDocument(
                                type = InstalledServerInvocationType.CLI,
                                command = selected.command,
                            ),
                    )
                )
        }
}
