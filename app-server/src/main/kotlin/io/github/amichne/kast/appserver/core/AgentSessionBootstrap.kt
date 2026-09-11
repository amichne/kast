package io.github.amichne.kast.appserver.core

import io.github.amichne.kast.appserver.schema.CompiledJsonSchema
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.registry.AgentToolName
import io.github.amichne.kast.protocol.registry.AgentToolPolicy
import io.github.amichne.kast.protocol.registry.HostedApprovalPolicy
import io.github.amichne.kast.protocol.registry.HostedToolLoading
import io.github.amichne.kast.protocol.registry.OperationEffect
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget

/** One installed, schema-qualified hosted operation independent of any agent provider. */
internal data class HostedToolDefinition(
    val operation: CanonicalOperation,
    val name: AgentToolName,
    val description: ProtocolText,
    val inputSchema: CompiledJsonSchema,
    val outputSchema: CompiledJsonSchema,
    val effect: OperationEffect,
    val approval: HostedApprovalPolicy,
    val executionBudget: OperationExecutionBudget,
    val loading: HostedToolLoading,
    val generationSchema: kotlinx.serialization.json.JsonObject = inputSchema.document,
)

/** Exact ordered hosted surface whose members all have executable installed routes. */
internal class HostedToolCatalog private constructor(
    val definitions: List<HostedToolDefinition>,
) {
    companion object {
        internal fun qualify(
            definitions: List<HostedToolDefinition>,
            executableRoutes: Set<ToolName>,
        ): HostedToolCatalogQualification {
            if (definitions.isEmpty()) {
                return HostedToolCatalogQualification.Rejected(
                    HostedToolCatalogFailure.EMPTY,
                )
            }
            if (definitions.groupBy(HostedToolDefinition::operation).values.any { presentations ->
                val owner = presentations.first()
                presentations.any { it.effect != owner.effect || it.approval != owner.approval ||
                    it.executionBudget != owner.executionBudget || it.outputSchema.digest != owner.outputSchema.digest }
            }) {
                return HostedToolCatalogQualification.Rejected(HostedToolCatalogFailure.INCONSISTENT_OPERATION_METADATA)
            }
            if (definitions.map { it.name.value }.toSet().size != definitions.size) {
                return HostedToolCatalogQualification.Rejected(
                    HostedToolCatalogFailure.DUPLICATE_NAME,
                )
            }
            val advertised = definitions.mapTo(linkedSetOf()) { it.name.value }
            val executable = executableRoutes.mapTo(linkedSetOf()) { it.value }
            return when {
                advertised.any { it !in executable } -> HostedToolCatalogQualification.Rejected(
                    HostedToolCatalogFailure.ADVERTISED_ROUTE_MISSING,
                )
                executable.any { it !in advertised } -> HostedToolCatalogQualification.Rejected(
                    HostedToolCatalogFailure.UNADVERTISED_ROUTE_PRESENT,
                )
                else -> HostedToolCatalogQualification.Qualified(HostedToolCatalog(definitions))
            }
        }
    }
}

internal enum class HostedToolCatalogFailure {
    EMPTY,
    INCONSISTENT_OPERATION_METADATA,
    DUPLICATE_NAME,
    ADVERTISED_ROUTE_MISSING,
    UNADVERTISED_ROUTE_PRESENT,
}

internal sealed interface HostedToolCatalogQualification {
    data class Qualified(val catalog: HostedToolCatalog) : HostedToolCatalogQualification
    data class Rejected(val failure: HostedToolCatalogFailure) : HostedToolCatalogQualification
}

/** Provider-neutral context installed only after executable-route qualification succeeds. */
internal class AgentSessionBootstrap private constructor(
    val tools: HostedToolCatalog,
    val policy: AgentToolPolicy,
) {
    companion object {
        internal fun qualify(
            definitions: List<HostedToolDefinition>,
            policy: AgentToolPolicy,
            executableRoutes: Set<ToolName>,
        ): AgentSessionBootstrapQualification = when (
            val qualification = HostedToolCatalog.qualify(definitions, executableRoutes)
        ) {
            is HostedToolCatalogQualification.Qualified ->
                AgentSessionBootstrapQualification.Qualified(
                    AgentSessionBootstrap(qualification.catalog, policy),
                )
            is HostedToolCatalogQualification.Rejected ->
                AgentSessionBootstrapQualification.Rejected(qualification.failure)
        }
    }
}

internal sealed interface AgentSessionBootstrapQualification {
    data class Qualified(val bootstrap: AgentSessionBootstrap) : AgentSessionBootstrapQualification
    data class Rejected(val failure: HostedToolCatalogFailure) : AgentSessionBootstrapQualification
}
