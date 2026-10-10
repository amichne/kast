package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.appserver.core.BrokerTool
import io.github.amichne.kast.appserver.core.HostedToolCatalog
import io.github.amichne.kast.appserver.core.HostedToolCatalogQualification
import io.github.amichne.kast.appserver.core.HostedToolDefinition
import io.github.amichne.kast.appserver.core.ProviderNamespace
import io.github.amichne.kast.appserver.core.ProviderRegistration
import io.github.amichne.kast.appserver.core.ProviderVersion
import io.github.amichne.kast.appserver.core.ToolDescription
import io.github.amichne.kast.appserver.core.ToolLoading
import io.github.amichne.kast.appserver.core.ToolName
import io.github.amichne.kast.appserver.query.WorkspaceLifecycleToolInput
import io.github.amichne.kast.appserver.schema.JsonDomainDefinition
import io.github.amichne.kast.appserver.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.registry.AgentToolName
import io.github.amichne.kast.protocol.registry.HostedApprovalPolicy
import io.github.amichne.kast.protocol.registry.HostedToolLoading
import io.github.amichne.kast.protocol.registry.OperationEffect
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Actual executable-route and schema qualification, with no registry resources or startup effects. */
internal class WorkspaceInspectionIngressFixture {
    private val json = Json { encodeDefaults = true }
    private val schema =
        (NetworkntJsonSchemaCompiler.compile(json.encodeToJsonElement(InspectionSchema()).jsonObject)
                as Refinement.Refined)
            .value
    private val tool: BrokerTool<Unit, WorkspaceLifecycleToolInput, JsonElement, Nothing> =
        BrokerTool(
            name = (ToolName.admit("workspace_lifecycle") as Refinement.Refined).value,
            description = (ToolDescription.admit("Inspect current native lifecycle") as Refinement.Refined).value,
            loading = ToolLoading.EAGER,
            input =
                JsonDomainDefinition(
                    schema,
                    RefinementDefinition { admitted ->
                        Validation.validated(json.decodeFromJsonElement<WorkspaceLifecycleToolInput>(admitted.element))
                    },
                ),
            outputSchema = schema,
            invoke = { _, _, _ -> error("qualification must not perform native transport") },
            encode = { it },
            present = { error("qualification must not render a native reply") },
            effect = BrokerOperationEffect.Canonical(OperationEffect.WORKSPACE_MODEL_WRITE),
        )
    private val registration =
        (ProviderRegistration.define(
                namespace = (ProviderNamespace.admit("kast") as Refinement.Refined).value,
                version = (ProviderVersion.admit("1") as Refinement.Refined).value,
                tools = listOf(tool),
                start = { error("qualification must not start a native provider") },
            ) as Validation.Validated)
            .value
    val broker = (Broker.create(listOf(registration), BrokerLimits.defaults()) as Validation.Validated).value
    private val definition =
        HostedToolDefinition(
            operation = CanonicalOperation.WORKSPACE_LIFECYCLE,
            name = (AgentToolName.parse("workspace_lifecycle") as Refinement.Refined).value,
            description = (ProtocolText.parse("Inspect current native lifecycle") as Refinement.Refined).value,
            inputSchema = schema,
            outputSchema = schema,
            effect = OperationEffect.WORKSPACE_MODEL_WRITE,
            approval = HostedApprovalPolicy.NONE,
            executionBudget = OperationExecutionBudget.SEMANTIC_READ,
            loading = HostedToolLoading.EAGER,
        )
    val definitions =
        (HostedToolCatalog.qualify(listOf(definition), setOf(tool.name)) as HostedToolCatalogQualification.Qualified)
            .catalog
            .definitions
    val params = json.encodeToJsonElement(InspectionParams()).jsonObject

    fun params(request: WorkspaceLifecycleToolInput, namespace: String = "kast", tool: String = "workspace_lifecycle") =
        json.encodeToJsonElement(InspectionParams(namespace, tool, request)).jsonObject

    @Serializable
    private data class InspectionParams(
        val namespace: String = "kast",
        val tool: String = "workspace_lifecycle",
        val arguments: WorkspaceLifecycleToolInput = WorkspaceLifecycleToolInput.Inspect(),
    )

    @Serializable
    private data class InspectionSchema(
        val type: String = "object",
        val required: List<String> = listOf("type"),
        val additionalProperties: Boolean = false,
        val properties: InspectionProperties = InspectionProperties(),
    )

    @Serializable
    private data class InspectionProperties(
        val type: InspectionAction = InspectionAction(),
        val verbose: InspectionVerbose = InspectionVerbose(),
    )

    @Serializable
    private data class InspectionAction(
        val type: String = "string",
        @SerialName("const") val action: String = "inspect",
    )

    @Serializable private data class InspectionVerbose(val type: String = "boolean")
}
