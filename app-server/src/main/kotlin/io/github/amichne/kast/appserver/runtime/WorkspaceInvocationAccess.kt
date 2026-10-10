package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.appserver.core.HostedToolDefinition
import io.github.amichne.kast.appserver.query.WorkspaceLifecycleToolInput
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** Observation bypass is restricted to schema-qualified lifecycle inspection/status, never an effectful command. */
internal fun workspaceInvocationAccess(
    effect: BrokerOperationEffect,
    definition: HostedToolDefinition?,
    arguments: JsonElement,
): WorkspaceExecutionAccess {
    val operation = WorkspaceExecutionAccess.Operation(effect)
    if (definition?.operation != CanonicalOperation.WORKSPACE_LIFECYCLE) return operation
    if (effect != BrokerOperationEffect.Canonical(definition.effect)) return operation
    if (definition.inputSchema.admit(arguments) !is Validation.Validated) return operation
    val request =
        try {
            Json.decodeFromJsonElement<WorkspaceLifecycleToolInput>(arguments)
        } catch (_: SerializationException) {
            return operation
        }
    return when (request) {
        is WorkspaceLifecycleToolInput.Inspect,
        is WorkspaceLifecycleToolInput.Status -> WorkspaceExecutionAccess.Observation
        is WorkspaceLifecycleToolInput.Open,
        is WorkspaceLifecycleToolInput.Present,
        is WorkspaceLifecycleToolInput.Release,
        is WorkspaceLifecycleToolInput.Close,
        is WorkspaceLifecycleToolInput.RequestUserClose -> operation
    }
}

/** Qualification uses the executable route and its schema; rejected ingress keeps conservative effects. */
internal fun workspaceInvocationAccess(
    params: kotlinx.serialization.json.JsonObject,
    broker: io.github.amichne.kast.appserver.core.Broker,
    definitions: List<HostedToolDefinition>,
): WorkspaceExecutionAccess {
    val unknown = WorkspaceExecutionAccess.Operation(BrokerOperationEffect.Unknown)
    val namespace =
        when (
            val parsed =
                io.github.amichne.kast.appserver.core.ProviderNamespace.admit(
                    (params["namespace"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                )
        ) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> parsed.value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> return unknown
        }
    val name =
        when (
            val parsed =
                io.github.amichne.kast.appserver.core.ToolName.admit(
                    (params["tool"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                )
        ) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> parsed.value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> return unknown
        }
    val effect = broker.effect(io.github.amichne.kast.appserver.core.ToolAddress(namespace, name))
    return workspaceInvocationAccess(
        effect,
        definitions.singleOrNull { it.name.value == name.value },
        params["arguments"] ?: kotlinx.serialization.json.JsonNull,
    )
}
