package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.ProviderFailureCode
import io.github.amichne.kast.appserver.runtime.BrokerInvocationApproval
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.HostedApprovalPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Admits an exact operation and trusted approval before any IDEA effect. */
internal class KastInvocationAdmission private constructor(val arguments: JsonElement) {
    companion object {
        fun prepare(
            tool: QualifiedKastTool,
            arguments: JsonElement,
            context: BrokerInvocationContext,
        ): Refinement<KastInvocationAdmission, ProviderFailureCode> {
            val operation = tool.hostedDefinition.operation
            val approval = context.approval
            if (operation == CanonicalOperation.WORKSPACE_LIFECYCLE) return lifecycle(arguments, context)
            if (tool.hostedDefinition.approval != HostedApprovalPolicy.EXPLICIT) {
                return if (approval == BrokerInvocationApproval.Absent)
                    Refinement.Refined(KastInvocationAdmission(arguments))
                else Refinement.Rejected(ProviderFailureCode.APPROVAL_BINDING_REJECTED)
            }
            if (approval !is BrokerInvocationApproval.Granted)
                return Refinement.Rejected(ProviderFailureCode.APPROVAL_REQUIRED)
            if (
                operation !in setOf(CanonicalOperation.CHANGE_APPLY, CanonicalOperation.CHANGE_RECOVER) ||
                    !approval.grant.matchesRequest(operation, context, arguments)
            )
                return Refinement.Rejected(ProviderFailureCode.APPROVAL_BINDING_REJECTED)
            return Refinement.Refined(KastInvocationAdmission(arguments))
        }

        private fun lifecycle(
            arguments: JsonElement,
            context: BrokerInvocationContext,
        ): Refinement<KastInvocationAdmission, ProviderFailureCode> {
            val approval = context.approval

            val request =
                try {
                    Json.decodeFromJsonElement(
                        io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest.serializer(),
                        arguments,
                    )
                } catch (_: kotlinx.serialization.SerializationException) {
                    return Refinement.Rejected(ProviderFailureCode.APPROVAL_BINDING_REJECTED)
                }
            if (request is io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest.RequestUserClose) {
                if (approval !is BrokerInvocationApproval.ProjectClose)
                    return Refinement.Rejected(ProviderFailureCode.APPROVAL_REQUIRED)
                if (!approval.grant.matches(context, request))
                    return Refinement.Rejected(ProviderFailureCode.APPROVAL_BINDING_REJECTED)
                return Refinement.Refined(KastInvocationAdmission(arguments))
            }
            return if (approval == BrokerInvocationApproval.Absent)
                Refinement.Refined(KastInvocationAdmission(arguments))
            else Refinement.Rejected(ProviderFailureCode.APPROVAL_BINDING_REJECTED)
        }
    }
}
