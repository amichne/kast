package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.command.CliCommandSurface
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.AgentToolDefinition
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.registry.HostedToolLoading
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import io.github.amichne.kast.protocol.registry.PUBLIC_TOOL_CONTRACT_VERSION

internal const val MAXIMUM_PROTOCOL_TEXT_LENGTH = 1_048_576
internal const val MAXIMUM_WORKSPACE_FILE_LENGTH = 4_096
internal const val MAXIMUM_PROTOCOL_COUNT = 1_000

internal const val SERVER_PROJECTION_SCHEMA_VERSION = 17
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
            val facadeByIdentity = commandSurface.toolCommands.associateBy { it.identity }
            return CanonicalAgentToolDefinitions.all.map { definition ->
                val operation = definition.operation.operation
                InstalledServerBinding(
                    operation = operation,
                    tool = definition.hostedDocument(),
                    invocation =
                        when (val input = definition.inputBinding) {
                            AgentToolInputBinding.Canonical -> InstalledInvocationBinding.HostedOnly
                            is AgentToolInputBinding.Facade ->
                                if (
                                    input.identity in
                                        setOf(
                                            io.github.amichne.kast.protocol.registry.PublicToolIdentity.ADD_DECLARATION,
                                            io.github.amichne.kast.protocol.registry.PublicToolIdentity.REPLACE_BODY,
                                        )
                                )
                                    InstalledInvocationBinding.HostedOnly
                                else
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
    return InstalledHostedBootstrapDocument(
        schemaVersion = PUBLIC_TOOL_CONTRACT_VERSION,
        policy = CanonicalAgentToolDefinitions.policy.text,
        tools = CanonicalAgentToolDefinitions.all.map(AgentToolDefinition::hostedDocument),
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

private fun AgentToolDefinition.hostedDocument(): InstalledHostedToolDocument =
    InstalledHostedToolDocument(
        operationId = operation.id.value,
        name = name.value,
        description = description.value,
        deferLoading = loading == HostedToolLoading.DEFERRED,
        effect = operation.effect.name.lowercase(),
        approvalPolicy = approval.name.lowercase(),
        executionBudget =
            InstalledServerExecutionBudgetDocument(
                readinessMillis = OperationExecutionBudget.WORKSPACE_READINESS.value,
                operationMillis = OperationExecutionBudget.forOperation(operation.operation).operation.value,
            ),
        inputSchema =
            when (val input = inputBinding) {
                is AgentToolInputBinding.Facade -> PublicToolContract.parameters(input.identity)
                AgentToolInputBinding.Canonical ->
                    generatedHostedRequestSchema(
                        io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest.serializer(),
                        operation.hostedVariants,
                    )
            },
        outputSchema = installedServerOutputSchema(operation.operation),
    )
