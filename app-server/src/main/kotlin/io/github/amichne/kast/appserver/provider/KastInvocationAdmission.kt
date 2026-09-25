package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.ProviderFailureCode
import io.github.amichne.kast.appserver.runtime.BrokerInvocationApproval
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Admits the bound operation before any IDEA effect; project close retains its separate controller gate. */
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
            return if (approval == BrokerInvocationApproval.Absent)
                Refinement.Refined(KastInvocationAdmission(arguments))
            else Refinement.Rejected(ProviderFailureCode.APPROVAL_BINDING_REJECTED)
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
